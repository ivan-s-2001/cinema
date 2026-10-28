import {createImageTransport} from './image-transport.mjs';
const origin='https://cinemahome.duckdns.org';
const route=/^\/api\/v2\/images\/tmdb\/(w185|w342|w1280)\/([A-Za-z0-9_-]{1,160}\.(?:jpg|jpeg|png|webp))$/;
export function proxyTmdb(url){
  const match=/^\/t\/p\/(w185|w342|w1280)\/([A-Za-z0-9_-]{1,160}\.(?:jpg|jpeg|png|webp))$/.exec(url.pathname);
  return match?origin+'/api/v2/images/tmdb/'+match[1]+'/'+match[2]:url.href;
}
export function createImageProxy({fetcher=createImageTransport(),clock=Date.now,budget=16*1024*1024}={}){
  const cache=new Map(),pending=new Map(),queue=[];let bytes=0,active=0;
  const hosts=['https://image.tmdb.org','https://tmdb-image-prod.b-cdn.net'];
  const unavailable=new Map();let preferred=hosts[0];
  async function slot(work){
    if(active>=2){if(queue.length>=32)throw Error('Image queue full');await new Promise(resolve=>queue.push(resolve));}
    else active++;
    try{return await work();}finally{const next=queue.shift();if(next)next();else active--;}
  }
  async function load(path,match){
    const saved=cache.get(path);
    if(saved&&saved.until>clock()){cache.delete(path);cache.set(path,saved);return saved;}
    if(pending.has(path))return pending.get(path);
    const work=slot(async()=>{
      let body,type,lastError,missing=false;
      const verifiedHost=await fetcher.preferredHost?.();
      if(hosts.includes(verifiedHost))preferred=verifiedHost;
      const deadline=AbortSignal.timeout(10000);
      for(const host of [preferred,...hosts.filter(host=>host!==preferred)]){
        if(deadline.aborted)break;
        if((unavailable.get(host)||0)>clock())continue;
        let response;
        try{
          // Standard TLS verification is deliberately kept on both fixed hosts.
          response=await fetcher(host+'/t/p/'+match[1]+'/'+match[2],{redirect:'error',signal:deadline});
          if(!response.ok){await response.body?.cancel();if(response.status===404){missing=true;continue;}throw Error('Image provider HTTP '+response.status);}
          if(Number(response.headers.get('content-length')||0)>2*1024*1024){await response.body?.cancel();throw Error('Image too large');}
          const chunks=[];let length=0;
          for await(const chunk of response.body){length+=chunk.length;if(length>2*1024*1024)throw Error('Image too large');chunks.push(chunk);}
          const candidate=Buffer.concat(chunks),mime=response.headers.get('content-type')?.split(';')[0];
          const jpeg=candidate[0]===255&&candidate[1]===216&&candidate[2]===255;
          const png=candidate.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
          const webp=candidate.subarray(0,4).toString()==='RIFF'&&candidate.subarray(8,12).toString()==='WEBP';
          if(!(mime==='image/jpeg'&&jpeg||mime==='image/png'&&png||mime==='image/webp'&&webp))throw Error('Invalid image');
          await response.confirm?.();
          body=candidate;type=mime;preferred=host;unavailable.delete(host);break;
        }catch(error){
          await response?.rejectRoute?.();
          lastError=error;
          const code=error.cause?.code||error.code;
          if(code||['TimeoutError','AbortError'].includes(error.name)){
            unavailable.set(host,clock()+(code==='ERR_TLS_CERT_ALTNAME_INVALID'?300000:15000));
            console.warn('Image upstream temporarily skipped',new URL(host).hostname,code||error.name);
          }
        }
      }
      if(!body){if(missing&&!lastError)return {status:404};throw lastError||Error('Image providers temporarily unavailable');}
      const item={status:200,body,type,until:clock()+86400000};
      if(cache.has(path)){bytes-=cache.get(path).body.length;cache.delete(path);}
      if(body.length<=budget){cache.set(path,item);bytes+=body.length;}
      while(bytes>budget||cache.size>128){const key=cache.keys().next().value;bytes-=cache.get(key).body.length;cache.delete(key);}
      return item;
    });
    pending.set(path,work);try{return await work;}finally{pending.delete(path);}
  }
  return async(req,res,url)=>{
    if(!url.pathname.startsWith('/api/v2/images/'))return false;
    const match=route.exec(url.pathname);
    let item;
    if(!['GET','HEAD'].includes(req.method))item={status:405};
    else if(!match||url.search)item={status:404};
    else try{item=await load(url.pathname,match);}catch(error){console.error('Image proxy failure',error.cause?.code||error.name,error.message);item={status:503};}
    res.writeHead(item.status,{'content-type':item.type||'text/plain; charset=utf-8','x-content-type-options':'nosniff','cache-control':item.status===200?'public, max-age=86400':'no-store',...(item.status===405?{allow:'GET, HEAD'}:{})});
    res.end(req.method==='HEAD'?undefined:item.body||'Image unavailable');return true;
  };
}
