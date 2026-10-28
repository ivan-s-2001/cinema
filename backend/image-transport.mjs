import {Agent,request} from 'node:https';
import {isIP} from 'node:net';
import {Readable} from 'node:stream';
import {readFile,writeFile,rename,mkdir} from 'node:fs/promises';
import {dirname} from 'node:path';

const hosts=new Set(['image.tmdb.org','tmdb-image-prod.b-cdn.net']);
const imagePath=/^\/t\/p\/(w185|w342|w1280)\/[A-Za-z0-9_-]{1,160}\.(jpg|jpeg|png|webp)$/;
export function publicImageIPv4(address){
  if(typeof address!=='string'||isIP(address)!==4)return false;
  const [a,b,c]=address.split('.').map(Number);
  return !(a===0||a===10||a===127||a>=224||a===169&&b===254||a===172&&b>=16&&b<=31||
    a===192&&b===168||a===100&&b>=64&&b<=127||a===192&&b===0&&(c===0||c===2)||
    a===198&&(b===18||b===19)||a===198&&b===51&&c===100||a===203&&b===0&&c===113);
}
export function createImageResolver({fetcher=fetch,clock=Date.now,subnet='0.0.0.0/0'}={}){
  if(!['0.0.0.0/0','8.8.8.0/24'].includes(subnet))throw Error('Unsupported image DNS subnet');
  const cache=new Map(),pending=new Map();
  return async function resolve(host,signal){
    if(!hosts.has(host))throw Error('Image hostname not allowed');
    const saved=cache.get(host);
    if(saved&&saved.until>clock())return saved.address;
    if(pending.has(host))return pending.get(host);
    const work=(async()=>{
      // Disable client-subnet hints: the CDN otherwise returns 127.0.0.1 for
      // this VPS. Do not trust or edit the machine's global resolver settings.
      const url=new URL('https://dns.google/resolve');
      url.search=new URLSearchParams({name:host,type:'A',edns_client_subnet:subnet});
      const timeout=AbortSignal.timeout(4000);
      const response=await fetcher(url,{redirect:'error',signal:signal?AbortSignal.any([signal,timeout]):timeout,headers:{accept:'application/dns-json'}});
      if(!response.ok){await response.body?.cancel();throw Error('Image DNS provider unavailable');}
      if(Number(response.headers.get('content-length')||0)>65536){await response.body?.cancel();throw Error('Image DNS response too large');}
      const chunks=[];let bytes=0;
      for await(const chunk of response.body){bytes+=chunk.length;if(bytes>65536)throw Error('Image DNS response too large');chunks.push(chunk);}
      const result=JSON.parse(Buffer.concat(chunks).toString('utf8'));
      const record=result.Status===0&&Array.isArray(result.Answer)?result.Answer.find(row=>row.type===1&&publicImageIPv4(row.data)):null;
      if(!record)throw Object.assign(new Error('Image DNS did not return a public IPv4 address'),{code:'ERR_IMAGE_DNS_NON_PUBLIC'});
      const ttl=Number.isFinite(record.TTL)?Math.max(0,Math.min(record.TTL,300)):30;
      cache.set(host,{address:record.data,until:clock()+ttl*1000});
      console.info('Image DNS resolved',host,record.data,'TTL',ttl);
      return record.data;
    })();
    pending.set(host,work);try{return await work;}finally{pending.delete(host);}
  };
}
function verifiedRoutes(file,clock){
  const routes=new Map();const lifetime=24*3600000;let writing=Promise.resolve();
  const loaded=(async()=>{
    if(!file)return;
    try{
      const saved=JSON.parse(await readFile(file,'utf8'));
      for(const host of hosts){
        const entries=(Array.isArray(saved[host])?saved[host]:[]).filter(entry=>publicImageIPv4(entry.address)&&Number.isFinite(entry.until)&&entry.until>clock()&&entry.until<=clock()+lifetime+300000).slice(0,3);
        if(entries.length){routes.set(host,entries);console.info('Verified image routes restored',host,entries.map(entry=>entry.address).join(','));}
      }
    }catch(error){if(error.code!=='ENOENT')console.warn('Image route cache read failed',error.code||error.name);}
  })();
  async function save(){
    if(!file)return;
    writing=writing.catch(()=>{}).then(async()=>{
      await mkdir(dirname(file),{recursive:true,mode:0o700});
      const temporary=file+'.'+process.pid+'.tmp';
      await writeFile(temporary,JSON.stringify(Object.fromEntries(routes)),{mode:0o600});await rename(temporary,file);
    });
    try{await writing;}catch(error){console.warn('Image route cache write failed',error.code||error.name);}
  }
  return {
    async preferredHost(){await loaded;return [...routes].filter(([_host,entries])=>entries.some(entry=>entry.until>clock())).sort((a,b)=>b[1][0].until-a[1][0].until)[0]?.[0];},
    async list(host){await loaded;return (routes.get(host)||[]).filter(entry=>entry.until>clock()).map(entry=>entry.address);},
    async confirm(host,address){
      await loaded;
      const entries=routes.get(host)||[];
      if(entries.some(entry=>entry.address===address&&entry.until>clock()+23*3600000))return;
      routes.set(host,[{address,until:clock()+lifetime},...entries.filter(entry=>entry.address!==address&&entry.until>clock())].slice(0,3));
      await save();console.info('Image route verified',host,address);
    },
    async reject(host,address){await loaded;const entries=routes.get(host)||[];if(entries.some(entry=>entry.address===address)){routes.set(host,entries.filter(entry=>entry.address!==address));await save();}}
  };
}
export function createImageTransport({resolver=createImageResolver(),alternateResolver=createImageResolver({subnet:'8.8.8.0/24'}),requester=request,routeFile='/var/lib/cinemahall/image-cache/routes.json',routeHints=(process.env.CINEMA_IMAGE_ROUTE_HINTS||'').split(','),clock=Date.now,attemptTimeoutMs=2500}={}){
  const agent=new Agent({keepAlive:true,maxSockets:2,maxFreeSockets:2,timeout:10000});
  const routes=verifiedRoutes(routeFile,clock);
  const transport=async function transport(raw,{signal}={}){
    const url=new URL(raw);
    if(url.protocol!=='https:'||!hosts.has(url.hostname)||url.username||url.password||url.port&&url.port!=='443'||url.search||url.hash||!imagePath.test(url.pathname))throw Error('Image URL not allowed');
    const tried=new Set();let lastError;
    async function attempt(address){
      if(tried.has(address))return null;
      if(!publicImageIPv4(address))throw Error('Non-public image destination rejected');
      tried.add(address);
      const timeout=AbortSignal.timeout(attemptTimeoutMs);
      const requestSignal=signal?AbortSignal.any([signal,timeout]):timeout;
      try{return await new Promise((resolve,reject)=>{
      const req=requester({
        protocol:'https:',hostname:url.hostname,port:443,path:url.pathname,method:'GET',
        servername:url.hostname,rejectUnauthorized:true,agent,signal:requestSignal,
        headers:{accept:'image/jpeg,image/png,image/webp','accept-encoding':'identity','user-agent':'CinemaHall-Images/1.0'},
        lookup(host,options,callback){
          if(host!==url.hostname){callback(Error('Unexpected image hostname'));return;}
          if(options.all)callback(null,[{address,family:4}]);else callback(null,address,4);
        }
      },response=>{
        resolve({ok:response.statusCode>=200&&response.statusCode<300,status:response.statusCode,
          headers:{get(name){const value=response.headers[name.toLowerCase()];return Array.isArray(value)?value.join(', '):value||null;}},
          body:Readable.toWeb(response),confirm:()=>routes.confirm(url.hostname,address),rejectRoute:()=>routes.reject(url.hostname,address)});
      });
      req.once('error',reject);req.end();
      });}catch(error){lastError=error;await routes.reject(url.hostname,address);console.warn('Image route failed',url.hostname,address,error.name,error.code||error.cause?.code||'');return null;}
    }
    for(const address of [...await routes.list(url.hostname),...routeHints.filter(publicImageIPv4).slice(0,3)]){
      if(signal?.aborted)throw signal.reason;
      const response=await attempt(address);if(response)return response;
    }
    for(const resolve of [resolver,alternateResolver]){
      if(signal?.aborted)throw signal.reason;
      let address;
      try{address=await resolve(url.hostname,signal);}catch(error){lastError=error;continue;}
      const response=await attempt(address);if(response)return response;
    }
    throw lastError||Error('No reachable public image route');
  };
  transport.preferredHost=async()=>{const host=await routes.preferredHost();return host?'https://'+host:undefined;};
  return transport;
}
