import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,readdir,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createImageDiskCache} from '../image-disk-cache.mjs';
import {createImageProxy} from '../image-proxy.mjs';
const body=Buffer.from([255,216,255,0]);
async function request(proxy){let status,result;await proxy({method:'GET'},{writeHead(s){status=s;},end(b){result=b;}},new URL('http://local/api/v2/images/tmdb/w185/actor.jpg'));return {status,body:result};}
test('actor portrait survives process replacement and works without the upstream',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'cinema-art-'));
  try{
    const first=createImageProxy({diskCache:createImageDiskCache({directory}),fetcher:async()=>new Response(body,{headers:{'content-type':'image/jpeg'}})});
    assert.equal((await request(first)).status,200);
    const second=createImageProxy({diskCache:createImageDiskCache({directory}),fetcher:async()=>{throw Error('offline');}});
    assert.deepEqual(await request(second),{status:200,body});
  }finally{await rm(directory,{recursive:true,force:true});}
});
test('persistent cache enforces byte budget, expiration and content validation',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'cinema-art-'));let now=1000;
  try{
    const cache=createImageDiskCache({directory,budget:85,clock:()=>now});
    await cache.put('first',{body,type:'image/jpeg'});now++;
    await cache.put('second',{body,type:'image/jpeg'});
    const files=await readdir(directory);let bytes=0;for(const name of files)bytes+=(await readFile(join(directory,name))).length;
    assert.ok(bytes<=85);assert.equal(await cache.get('first'),null);assert.deepEqual((await cache.get('second')).body,body);
    await cache.put('html',{body:Buffer.from('<html>'),type:'image/jpeg'});assert.equal(await cache.get('html'),null);
    now+=8*86400000;assert.equal(await cache.get('second'),null);
  }finally{await rm(directory,{recursive:true,force:true});}
});
