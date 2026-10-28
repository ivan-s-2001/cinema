// Server-side source discovery. This service returns bounded candidate metadata;
// the TV engine still opens the chosen source and streams media locally.
const CATALOG = 'https://zsolr3.zonasearch.com/solr/torrent/select';
const MAX_BODY = 4 * 1024 * 1024;
const MAX_RESULTS = 600;
const CACHE_MS = 180000;
const keyFor = (id, episode) => id + '|' + episode;
const normalized=value=>String(value||'').toLowerCase().replaceAll('ё','е').replace(/[^\p{L}\p{N}]+/gu,' ').trim();
const solrPhrase=value=>'"'+value.replace(/([+\-&|!(){}\[\]^"~*?:\\/])/g,'\\$1')+'"';
export function matchKinopoisk(title,docs){
  const names=new Set([title.title,title.originalTitle,...(title.aliases||[])].map(normalized).filter(Boolean));
  const matches=docs.filter(row=>/^[0-9]{1,12}$/.test(String(row.id))&&
    Number.isInteger(title.year)&&Math.abs(Number(row.year)-title.year)<=1&&
    (row.serial===true||row.serial==='true')===(title.type==='series')&&
    [row.name_rus,row.name_eng,row.name_original].some(name=>names.has(normalized(name))))
    .map(row=>({id:String(row.id),score:(normalized(row.name_rus)===normalized(title.title)?4:1)+(Number(row.year)===title.year?1:0)}));
  const best=Math.max(0,...matches.map(row=>row.score));
  const ids=new Set(matches.filter(row=>row.score===best).map(row=>row.id));
  return ids.size===1?[...ids][0]:null;
}
function validSource(raw) {
  if (typeof raw !== 'string' || raw.length > 2048) return false;
  if (/^magnet:\?xt=urn:btih:[a-zA-Z0-9]{32,40}(?:&|$)/.test(raw)) return true;
  try {const url = new URL(raw);return url.protocol === 'https:' && !url.username && !url.password;}
  catch {return false;}
}
export function normalizeVoice(value){
 let label=String(value||'').trim();
 for(const [pattern,name]of [[/lost[ ._-]*film/gi,'LostFilm'],[/nova[ ._-]*film/gi,'NovaFilm'],[/(?:ren[ ._-]*tv|рен[ ._-]*тв)/gi,'REN TV'],[/sony[ ._-]*sci[ ._-]*fi/gi,'Sony Sci-Fi'],[/cold[ ._-]*film/gi,'ColdFilm']])label=label.replace(pattern,name);
 return label.replace(/\s+/g,' ').trim().slice(0,120);
}
function boundedText(value, max=160) {return typeof value === 'string' ? value.trim().slice(0,max) : '';}
const episodeKey=value=>{const m=/^S0*(\d+)E0*(\d+)$/i.exec(value);return m?'S'+Number(m[1])+'E'+Number(m[2]):null;};
function parseCandidate(row,episode='') {
  if(!row||typeof row!=='object'||Array.isArray(row))return null;
  const link=boundedText(row.torrent_download_link,2048);
  if (!validSource(link)) return null;
  const resolution=boundedText(row.resolution,32);
  let name=boundedText(row.name || row.title || row.torrent_name,200);
  const voice=normalizeVoice(row.translate_info);
  const languages=boundedText(row.languages_parser,160);
  const magnetHash=/^magnet:\?xt=urn:btih:([a-fA-F0-9]{40})/i.exec(link)?.[1] || '';
  const hash=/^[a-fA-F0-9]{40}$/.test(row.hash||'') ? row.hash.toLowerCase() : magnetHash.toLowerCase();
  let mapping={};
  if(typeof row.episodes_map_auto==='string'&&row.episodes_map_auto.length<=128*1024){try{const parsed=JSON.parse(row.episodes_map_auto);if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed))mapping=parsed;}catch{}}
  else if(row.episodes_map_auto&&typeof row.episodes_map_auto==='object'&&!Array.isArray(row.episodes_map_auto))mapping=row.episodes_map_auto;
  const known=Object.keys(mapping).filter(key=>episodeKey(key));
  const matching=episode?known.find(key=>episodeKey(key)===episodeKey(episode)):null;
  if(episode&&known.length&&!matching)return null;
  // Zona commonly supplies filenames instead of a release title. Only the
  // mapped episode or one unambiguous video file may supply technology hints.
  if(!name){
    let names=row.filenames;
    if(typeof names==='string'&&names.length<=128*1024){try{names=JSON.parse(names);}catch{names=[];}}
    const videos=Array.isArray(names)?names.filter(v=>typeof v==='string'&&/\.(mkv|mp4|avi|m4v|ts|m2ts|webm)$/i.test(v)&&!/(?:^|[\/._ -])sample(?:[\/._ -]|$)/i.test(v)):[];
    const path=matching&&typeof mapping[matching]==='string'?mapping[matching]:videos.length===1?videos[0]:'';
    name=boundedText(path.split(/[\\/]/).at(-1),200);
  }
  // Only the requested file mapping is needed by the TV, not all seven seasons.
  const compact=matching?{[episodeKey(episode)]:String(mapping[matching]).slice(0,1024)}:episode?{}:mapping;
  const encoded=JSON.stringify(compact);
  const episodeMap=encoded.length<=4096?encoded:'{}';
  const seeds=Number.isInteger(row.seeds)&&row.seeds>0 ? Math.min(row.seeds,1000000) : 0;
  return {hash,torrent_download_link:link,resolution,name,translate_info:voice,languages_parser:languages,seeds,episodes_map_auto:episodeMap};
}
export function createSourceDiscovery({ fetcher=fetch, enabled=false, clock=Date.now }={}) {
  const cache=new Map(), pending=new Map(),identities=new Map(),backgrounds=new Set();
  let activePages=0;const pageWaiters=[];
  async function pageSlot(work,signal){
    if(activePages>=2){
      if(pageWaiters.length>=16)throw Object.assign(Error('Source discovery busy'),{status:503});
      await new Promise((resolve,reject)=>{
        const granted=()=>{signal.removeEventListener('abort',aborted);resolve();};
        const aborted=()=>{const index=pageWaiters.indexOf(granted);if(index>=0)pageWaiters.splice(index,1);reject(Object.assign(Error('Source discovery busy'),{status:503}));};
        pageWaiters.push(granted);signal.addEventListener('abort',aborted,{once:true});
        if(signal.aborted)aborted();
      });
    }else activePages++;
    try{return await work();}finally{const next=pageWaiters.shift();if(next)next();else activePages--;}
  }
  async function document(url){
    const signal=AbortSignal.timeout(6000);
    return pageSlot(async()=>{
      const response=await fetcher(url,{redirect:'error',signal,headers:{accept:'application/json'}});
      if(!response.ok)throw Error('Source upstream failed');
      if(Number(response.headers?.get?.('content-length')||0)>MAX_BODY)throw Error('Source response too large');
      // Enforce the budget while reading, including responses without Content-Length.
      const chunks=[];let size=0;
      const body=response.body||[Buffer.from(await response.arrayBuffer())];
      for await(const chunk of body){size+=chunk.length;if(size>MAX_BODY)throw Error('Source response too large');chunks.push(chunk);}
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    },signal);
  }
  async function loadForTitle(title,episode=''){
    const known=title.externalIds?.kinopoisk;
    if(known)return load(known,episode);
    if(!enabled)return {status:'disabled',candidates:[]};
    const names=[...new Set([title.title,title.originalTitle,...(title.aliases||[])].filter(v=>typeof v==='string'&&v.trim().length>=2&&v.length<=120))].slice(0,3);
    if(!names.length||!Number.isInteger(title.year))return {status:'ready',candidates:[]};
    const key=JSON.stringify([title.id,title.year,names]),saved=identities.get(key);
    let kp=saved?.expires>clock()?saved.id:undefined;
    if(kp===undefined){
      const query='('+names.flatMap(name=>['name_rus','name_eng','name_original'].map(field=>field+':'+solrPhrase(name))).join(' OR ')+') AND year:['+(title.year-1)+' TO '+(title.year+1)+'] AND serial:'+(title.type==='series');
      const url=new URL('https://zsolr3.zonasearch.com/solr/movie/select');
      url.search=new URLSearchParams({wt:'json',q:query,rows:'20',fl:'id,serial,year,name_rus,name_eng,name_original'});
      const docs=(await document(url))?.response?.docs;
      kp=matchKinopoisk(title,Array.isArray(docs)?docs:[]);
      identities.set(key,{id:kp,expires:clock()+(kp?86400000:600000)});
      while(identities.size>256)identities.delete(identities.keys().next().value);
    }
    return kp?load(kp,episode):{status:'ready',candidates:[]};
  }
  async function load(kinopoiskId, episode='') {
    if (!enabled) return { status:'disabled', candidates:[] };
    if (!/^[0-9]{1,12}$/.test(String(kinopoiskId)) || (episode&&!/^S[0-9]{1,3}E[0-9]{1,4}$/.test(episode)))
      throw Object.assign(new Error('Invalid source identity'),{status:400});
    const key=keyFor(kinopoiskId,episode),now=clock(),stored=cache.get(key);
    if(stored&&stored.expires>now)return stored.result;
    if(pending.has(key))return pending.get(key);
    if(pending.size+backgrounds.size>=16)throw Object.assign(Error('Source discovery busy'),{status:503});
    const work=(async()=>{
      const base='kinopoisk_id:'+kinopoiskId+' AND playable:true AND trailer:false';
      const match=episode?new RegExp('^S(\\d+)E(\\d+)$').exec(episode):null;
      const padded=match?'S'+match[1].padStart(2,'0')+'E'+match[2].padStart(2,'0'):'';
      const fetchDocs=async(q,start=0)=>{
        const u=new URL(CATALOG);u.search=new URLSearchParams({wt:'json',q,sort:'seeds desc',start:String(start),rows:'100',fl:'hash,torrent_download_link,resolution,name,filenames,translate_info,languages_parser,seeds,episodes_map_auto'});
        const data=await document(u),docs=Array.isArray(data?.response?.docs)?data.response.docs:[];return {docs,total:Number(data?.response?.numFound??docs.length)};
      };
      const candidates=[],seen=new Set();
      const append=docs=>{for(const raw of docs){if(candidates.length>=MAX_RESULTS)break;const item=parseCandidate(raw,episode);if(!item)continue;const identity=item.hash||item.torrent_download_link;if(seen.has(identity))continue;seen.add(identity);candidates.push(item);if(candidates.length>=MAX_RESULTS)break;}};
      let query=base+(episode?' AND (episodes:"'+padded+'" OR episodes:"'+episode+'")':'');
      let first=await fetchDocs(query);append(first.docs);
      if(episode&&!candidates.length){query=base;first=await fetchDocs(query);append(first.docs);}
      // Exact episode hits must not hide season packs containing other voices.
      // Return exact hits immediately and merge the independent pack search later.
      const scanPacks=Boolean(episode&&query!==base);
      const result={status:'ready',candidates,complete:first.total<=100&&!scanPacks};
      cache.set(key,{result,expires:clock()+(candidates.length?CACHE_MS:30000)});
      while(cache.size>16)cache.delete(cache.keys().next().value);
      if(!result.complete){
        const background=(async()=>{
          try{
            async function scan(q,total,start){
              try{for(let offset=start;offset<Math.min(600,total)&&candidates.length<MAX_RESULTS;offset+=100)append((await fetchDocs(q,offset)).docs);}
              catch(error){console.warn('Source discovery continuation',error.message);}
            }
            // One worker per query keeps late exact hits and pack voices moving
            // together. The global two-request limit still applies to all titles.
            await Promise.all([
              scan(query,first.total,100),
              scanPacks?(async()=>{try{const packs=await fetchDocs(base);append(packs.docs);await scan(base,packs.total,100);}catch(error){console.warn('Season pack discovery',error.message);}})():Promise.resolve()
            ]);
          }catch(error){console.warn('Source discovery continuation',error.message);}
          finally{result.complete=true;const saved=cache.get(key);if(saved?.result===result)saved.expires=clock()+CACHE_MS;}
        })();backgrounds.add(background);background.finally(()=>backgrounds.delete(background));
      }
      return result;
    })();
    pending.set(key,work);
    try{return await work;}finally{pending.delete(key);}
  }
  return {load,loadForTitle,enabled,async idle(){await Promise.allSettled([...backgrounds]);}};
}
