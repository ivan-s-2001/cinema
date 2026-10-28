// Server-side source discovery. This service returns bounded candidate metadata;
// the TV engine still opens the chosen source and streams media locally.
const CATALOG = 'https://zsolr3.zonasearch.com/solr/torrent/select';
const MAX_BODY = 1024 * 1024;
const MAX_RESULTS = 60;
const CACHE_MS = 180000;
const keyFor = (id, episode) => id + '|' + episode;
function validSource(raw) {
  if (typeof raw !== 'string' || raw.length > 2048) return false;
  if (/^magnet:\?xt=urn:btih:[a-zA-Z0-9]{32,40}(?:&|$)/.test(raw)) return true;
  try {const url = new URL(raw);return url.protocol === 'https:' && !url.username && !url.password;}
  catch {return false;}
}
function boundedText(value, max=160) {return typeof value === 'string' ? value.trim().slice(0,max) : '';}
function parseCandidate(row) {
  const link=boundedText(row.torrent_download_link,2048);
  if (!validSource(link)) return null;
  const resolution=boundedText(row.resolution,32);
  const name=boundedText(row.name || row.title || row.torrent_name,200);
  const voice=boundedText(row.translate_info,120);
  const languages=boundedText(row.languages_parser,160);
  const magnetHash=/^magnet:\?xt=urn:btih:([a-fA-F0-9]{40})/i.exec(link)?.[1] || '';
  const hash=/^[a-fA-F0-9]{40}$/.test(row.hash||'') ? row.hash.toLowerCase() : magnetHash.toLowerCase();
  const episodeMap=typeof row.episodes_map_auto==='string' && row.episodes_map_auto.length<4096 ? row.episodes_map_auto : '{}';
  const seeds=Number.isInteger(row.seeds)&&row.seeds>0 ? Math.min(row.seeds,1000000) : 0;
  return {hash,torrent_download_link:link,resolution,name,translate_info:voice,languages_parser:languages,seeds,episodes_map_auto:episodeMap};
}
export function createSourceDiscovery({ fetcher=fetch, enabled=false, clock=Date.now }={}) {
  const cache=new Map(), pending=new Map();
  async function load(kinopoiskId, episode='') {
    if (!enabled) return { status:'disabled', candidates:[] };
    if (!/^[0-9]{1,12}$/.test(String(kinopoiskId)) || (episode&&!/^S[0-9]{1,3}E[0-9]{1,4}$/.test(episode)))
      throw Object.assign(new Error('Invalid source identity'),{status:400});
    const key=keyFor(kinopoiskId,episode),now=clock(),stored=cache.get(key);
    if(stored&&stored.expires>now)return {status:'ready',candidates:stored.candidates};
    if(pending.has(key))return pending.get(key);
    const work=(async()=>{
      const q='kinopoisk_id:'+kinopoiskId+' AND playable:true AND trailer:false'+(episode?' AND episodes:"'+episode+'"':'');
      const u=new URL(CATALOG);u.search=new URLSearchParams({wt:'json',q,sort:'seeds desc',start:'0',rows:'100',fl:'hash,torrent_download_link,resolution,name,translate_info,languages_parser,seeds,episodes_map_auto'});
      const response=await fetcher(u,{redirect:'error',signal:AbortSignal.timeout(6000),headers:{accept:'application/json'}});
      if(!response.ok)throw new Error('Source upstream failed');
      const announced=Number(response.headers?.get?.('content-length')||0);
      if(announced>MAX_BODY)throw new Error('Source response too large');
      const bytes=Buffer.from(await response.arrayBuffer());
      if(bytes.length>MAX_BODY)throw new Error('Source response too large');
      const data=JSON.parse(bytes.toString('utf8'));
      const docs=Array.isArray(data?.response?.docs)?data.response.docs:[];
      const candidates=[],seen=new Set();
      for(const raw of docs){
        const item=parseCandidate(raw);if(!item)continue;
        const identity=item.hash||item.torrent_download_link;
        if(seen.has(identity))continue;
        seen.add(identity);candidates.push(item);
        if(candidates.length>=MAX_RESULTS)break;
      }
      // A query without an exact episode hit may have generic season packs.
      // The TV engine validates the selected file before playback.
      if(episode&&!candidates.length){
        const generic=await load(kinopoiskId,'');
        candidates.push(...generic.candidates);
      }
      const result={status:'ready',candidates};
      cache.set(key,{candidates,expires:clock()+(candidates.length?CACHE_MS:30000)});
      while(cache.size>64)cache.delete(cache.keys().next().value);
      return result;
    })();
    pending.set(key,work);
    try{return await work;}finally{pending.delete(key);}
  }
  return {load,enabled};
}
