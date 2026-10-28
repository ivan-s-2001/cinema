import {Agent,request} from 'node:https';
import {isIP} from 'node:net';
import {Readable} from 'node:stream';

const hosts=new Set(['image.tmdb.org','tmdb-image-prod.b-cdn.net']);
const imagePath=/^\/t\/p\/(w185|w342|w1280)\/[A-Za-z0-9_-]{1,160}\.(jpg|jpeg|png|webp)$/;
export function publicImageIPv4(address){
  if(typeof address!=='string'||isIP(address)!==4)return false;
  const [a,b,c]=address.split('.').map(Number);
  return !(a===0||a===10||a===127||a>=224||a===169&&b===254||a===172&&b>=16&&b<=31||
    a===192&&b===168||a===100&&b>=64&&b<=127||a===192&&b===0&&(c===0||c===2)||
    a===198&&(b===18||b===19)||a===198&&b===51&&c===100||a===203&&b===0&&c===113);
}
export function createImageResolver({fetcher=fetch,clock=Date.now}={}){
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
      url.search=new URLSearchParams({name:host,type:'A',edns_client_subnet:'0.0.0.0/0'});
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
export function createImageTransport({resolver=createImageResolver(),requester=request}={}){
  const agent=new Agent({keepAlive:true,maxSockets:2,maxFreeSockets:2,timeout:10000});
  return async function transport(raw,{signal}={}){
    const url=new URL(raw);
    if(url.protocol!=='https:'||!hosts.has(url.hostname)||url.username||url.password||url.port&&url.port!=='443'||url.search||url.hash||!imagePath.test(url.pathname))throw Error('Image URL not allowed');
    const address=await resolver(url.hostname,signal);
    if(!publicImageIPv4(address))throw Error('Non-public image destination rejected');
    return new Promise((resolve,reject)=>{
      const req=requester({
        protocol:'https:',hostname:url.hostname,port:443,path:url.pathname,method:'GET',
        servername:url.hostname,rejectUnauthorized:true,agent,signal,
        headers:{accept:'image/jpeg,image/png,image/webp','accept-encoding':'identity','user-agent':'CinemaHall-Images/1.0'},
        lookup(host,options,callback){
          if(host!==url.hostname){callback(Error('Unexpected image hostname'));return;}
          if(options.all)callback(null,[{address,family:4}]);else callback(null,address,4);
        }
      },response=>{
        resolve({ok:response.statusCode>=200&&response.statusCode<300,status:response.statusCode,
          headers:{get(name){const value=response.headers[name.toLowerCase()];return Array.isArray(value)?value.join(', '):value||null;}},
          body:Readable.toWeb(response)});
      });
      req.once('error',reject);req.end();
    });
  };
}
