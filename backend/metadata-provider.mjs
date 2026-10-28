const base='https://kinoapi.lampac.dev/api/v1';
export function createMetadataProvider({fetcher=fetch,key=process.env.KINOAPI_KEY||''}={}){
  let tail=Promise.resolve();
  return {
    enabled:Boolean(key),
    get(path){
      if(!key)return Promise.reject(Error('Metadata provider key is not configured'));
      if(!/^\/(tv|titles)\/[A-Za-z0-9/_?=&.-]+$/.test(path))return Promise.reject(Error('Invalid metadata path'));
      const work=tail.catch(()=>{}).then(async()=>{
        const response=await fetcher(base+path,{headers:{'X-API-Key':key,accept:'application/json'},redirect:'error',signal:AbortSignal.timeout(8000)});
        if(!response.ok)throw Error('Metadata provider HTTP '+response.status);
        if(Number(response.headers.get('content-length')||0)>2*1024*1024)throw Error('Metadata response too large');
        const chunks=[];let bytes=0;
        for await(const chunk of response.body){bytes+=chunk.length;if(bytes>2*1024*1024)throw Error('Metadata response too large');chunks.push(chunk);}
        return JSON.parse(Buffer.concat(chunks));
      });
      tail=work.then(()=>new Promise(resolve=>setTimeout(resolve,150)),()=>{});
      return work;
    }
  };
}
