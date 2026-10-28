const origin='https://cinemahome.duckdns.org';
const route=/^\/api\/v2\/images\/tmdb\/(w185|w342|w1280)\/([A-Za-z0-9_-]{1,160}\.(?:jpg|jpeg|png|webp))$/;
export function proxyTmdb(url){
  const match=/^\/t\/p\/(w185|w342|w1280)\/([A-Za-z0-9_-]{1,160}\.(?:jpg|jpeg|png|webp))$/.exec(url.pathname);
  return match?origin+'/api/v2/images/tmdb/'+match[1]+'/'+match[2]:url.href;
}
export function createImageProxy({fetcher=fetch,clock=Date.now,budget=16*1024*1024}={}){
  const cache=new Map(),pending=new Map(),queue=[];let bytes=0,active=0;
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
      const response=await fetcher('https://image.tmdb.org/t/p/'+match[1]+'/'+match[2],{redirect:'error',signal:AbortSignal.timeout(10000)});
      if(response.status===404)return {status:404};
      if(!response.ok)throw Error('Image provider unavailable');
      if(Number(response.headers.get('content-length')||0)>2*1024*1024)throw Error('Image too large');
      const chunks=[];let length=0;
      for await(const chunk of response.body){length+=chunk.length;if(length>2*1024*1024)throw Error('Image too large');chunks.push(chunk);}
      const body=Buffer.concat(chunks),type=response.headers.get('content-type')?.split(';')[0];
      const jpeg=body[0]===255&&body[1]===216&&body[2]===255;
      const png=body.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
      const webp=body.subarray(0,4).toString()==='RIFF'&&body.subarray(8,12).toString()==='WEBP';
      if(!(type==='image/jpeg'&&jpeg||type==='image/png'&&png||type==='image/webp'&&webp))throw Error('Invalid image');
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
