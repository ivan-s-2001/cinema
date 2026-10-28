import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {Readable} from 'node:stream';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createImageResolver,createImageTransport,publicImageIPv4} from '../image-transport.mjs';
const address='138.199.37.232';
const dns=(answers)=>new Response(JSON.stringify({Status:0,Answer:answers}),{headers:{'content-type':'application/dns-json'}});
test('image destinations reject loopback, private, link-local and reserved DNS answers',()=>{
  for(const value of [undefined,'localhost','::1','127.0.0.1','0.0.0.0','10.1.2.3','192.168.0.1','172.16.0.1','172.31.255.255','169.254.169.254','100.64.0.1','192.0.2.1','198.18.0.1','198.51.100.1','203.0.113.1','224.0.0.1'])assert.equal(publicImageIPv4(value),false,String(value));
  assert.equal(publicImageIPv4(address),true);assert.equal(publicImageIPv4('169.150.236.97'),true);
});
test('image resolver removes client-subnet hints, coalesces queries and honors TTL',async()=>{
  let calls=0,now=0;
  const resolve=createImageResolver({clock:()=>now,fetcher:async(url,options)=>{
    calls++;assert.equal(url.origin,'https://dns.google');assert.equal(url.searchParams.get('name'),'image.tmdb.org');
    assert.equal(url.searchParams.get('edns_client_subnet'),'0.0.0.0/0');assert.equal(url.searchParams.get('type'),'A');assert.equal(options.redirect,'error');
    await new Promise(resolve=>setTimeout(resolve,2));
    return dns([{type:1,data:'127.0.0.1',TTL:2},{type:1,data:address,TTL:2}]);
  }});
  assert.ok((await Promise.all(Array.from({length:10},()=>resolve('image.tmdb.org')))).every(value=>value===address));assert.equal(calls,1);
  now=1999;assert.equal(await resolve('image.tmdb.org'),address);assert.equal(calls,1);
  now=2001;await resolve('image.tmdb.org');assert.equal(calls,2);
  await assert.rejects(()=>resolve('localhost'));assert.equal(calls,2);
});
test('loopback-only DNS responses are rejected and never cached as valid destinations',async()=>{
  let calls=0;
  const resolve=createImageResolver({fetcher:async()=>dns([{type:1,data:++calls===1?'127.0.0.1':address,TTL:60}])});
  await assert.rejects(()=>resolve('image.tmdb.org'),{code:'ERR_IMAGE_DNS_NON_PUBLIC'});
  assert.equal(await resolve('image.tmdb.org'),address);assert.equal(calls,2);
});
test('image transport pins a public IP while preserving original Host, SNI and TLS verification',async()=>{
  let requested=false;
  const transport=createImageTransport({routeFile:null,resolver:async host=>{assert.equal(host,'image.tmdb.org');return address;},requester:(options,callback)=>{
    requested=true;assert.equal(options.hostname,'image.tmdb.org');assert.equal(options.servername,'image.tmdb.org');assert.equal(options.rejectUnauthorized,true);assert.equal(options.checkServerIdentity,undefined);
    const req=new EventEmitter();req.end=()=>{
      options.lookup('image.tmdb.org',{all:true},(error,addresses)=>{assert.equal(error,null);assert.deepEqual(addresses,[{address,family:4}]);});
      options.lookup('image.tmdb.org',{},(error,ip,family)=>{assert.equal(error,null);assert.equal(ip,address);assert.equal(family,4);});
      const response=Readable.from([Buffer.from([255,216,255,0])]);response.statusCode=200;response.headers={'content-type':'image/jpeg'};callback(response);
    };return req;
  }});
  const response=await transport('https://image.tmdb.org/t/p/w342/poster.jpg');
  assert.equal(requested,true);assert.equal(response.status,200);assert.equal(response.headers.get('content-type'),'image/jpeg');
  assert.deepEqual(Buffer.from(await new Response(response.body).arrayBuffer()),Buffer.from([255,216,255,0]));
  for(const url of ['https://localhost/t/p/w342/poster.jpg','http://image.tmdb.org/t/p/w342/poster.jpg','https://image.tmdb.org:8091/t/p/w342/poster.jpg','https://image.tmdb.org/t/p/w342/poster.jpg?url=http://localhost'])await assert.rejects(()=>transport(url));
});
test('image transport never opens a socket to non-public resolver results',async()=>{
  const transport=createImageTransport({routeFile:null,resolver:async()=> '127.0.0.1',requester:()=>assert.fail('Socket must not be opened')});
  await assert.rejects(()=>transport('https://image.tmdb.org/t/p/w342/poster.jpg'));
});
test('a verified staged image route survives transport restart and avoids choosing a new DNS edge',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'cinema-image-route-'));
  const file=join(directory,'routes.json');let dnsCalls=0,requests=0;
  const requester=(options,callback)=>{
    const req=new EventEmitter();req.end=()=>{
      requests++;options.lookup(options.hostname,{},(_error,ip)=>assert.equal(ip,address));
      assert.equal(options.rejectUnauthorized,true);assert.equal(options.servername,'image.tmdb.org');
      const response=Readable.from([Buffer.from([255,216,255,0])]);response.statusCode=200;response.headers={'content-type':'image/jpeg'};callback(response);
    };return req;
  };
  try{
    const stage=createImageTransport({routeFile:file,requester,resolver:async()=>{dnsCalls++;return address;}});
    const first=await stage('https://image.tmdb.org/t/p/w342/poster.jpg');await new Response(first.body).arrayBuffer();
    await assert.rejects(()=>readFile(file)); // A successful DNS lookup alone is not a verified route.
    await first.confirm();
    const saved=JSON.parse(await readFile(file,'utf8'));assert.equal(saved['image.tmdb.org'][0].address,address);
    const production=createImageTransport({routeFile:file,requester,resolver:async()=>assert.fail('Must use the verified staged route')});
    assert.equal(await production.preferredHost(),'https://image.tmdb.org');
    const second=await production('https://image.tmdb.org/t/p/w342/another.jpg');await new Response(second.body).arrayBuffer();await second.confirm();
    assert.equal(dnsCalls,1);assert.equal(requests,2);
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('operator route hints are checked over TLS and are not persisted before image validation',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'cinema-image-hint-'));const file=join(directory,'routes.json');
  try{
    const transport=createImageTransport({routeFile:file,routeHints:['127.0.0.1',address],resolver:async()=>assert.fail('Use the public hint first'),requester:(options,callback)=>{
      const req=new EventEmitter();req.end=()=>{
        options.lookup(options.hostname,{},(_error,ip)=>assert.equal(ip,address));assert.equal(options.rejectUnauthorized,true);assert.equal(options.servername,'image.tmdb.org');
        const response=Readable.from([Buffer.from([255,216,255,0])]);response.statusCode=200;response.headers={'content-type':'image/jpeg'};callback(response);
      };return req;
    }});
    const response=await transport('https://image.tmdb.org/t/p/w342/poster.jpg');await new Response(response.body).arrayBuffer();
    await assert.rejects(()=>readFile(file));await response.confirm();assert.ok((await readFile(file,'utf8')).includes(address));
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('an unreachable public DNS edge falls back to another public edge under verified TLS',async()=>{
  const bad='138.199.37.227',seen=[];
  const transport=createImageTransport({routeFile:null,resolver:async()=>bad,alternateResolver:async()=>address,requester:(options,callback)=>{
    const req=new EventEmitter();req.end=()=>options.lookup(options.hostname,{},(_error,ip)=>{
      seen.push(ip);assert.equal(options.rejectUnauthorized,true);assert.equal(options.servername,'image.tmdb.org');
      if(ip===bad){req.emit('error',Object.assign(new Error('timeout'),{code:'ETIMEDOUT'}));return;}
      const response=Readable.from([Buffer.from([255,216,255,0])]);response.statusCode=200;response.headers={'content-type':'image/jpeg'};callback(response);
    });return req;
  }});
  const response=await transport('https://image.tmdb.org/t/p/w342/poster.jpg');assert.equal(response.status,200);await new Response(response.body).arrayBuffer();assert.deepEqual(seen,[bad,address]);
});
