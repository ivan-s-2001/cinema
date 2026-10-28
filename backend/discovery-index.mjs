import {createHash} from 'node:crypto';
import {titleDetail} from './catalog-model.mjs';
import {matchKinopoisk} from './source-discovery.mjs';
const origin='https://zsolr3.zonasearch.com/solr/';
const quote=value=>'"'+String(value).replace(/([+\-&|!(){}\[\]^"~*?:\\/])/g,'\\$1')+'"';
export async function solr(collection,params,{fetcher=fetch}={}){
  const url=new URL(origin+collection+'/select');url.search=new URLSearchParams({wt:'json',...params});
  const response=await fetcher(url,{redirect:'error',signal:AbortSignal.timeout(10000),headers:{accept:'application/json'}});
  if(!response.ok)throw Error('Discovery upstream HTTP '+response.status);
  if(Number(response.headers.get('content-length')||0)>2*1024*1024)throw Error('Discovery response too large');
  const chunks=[];let size=0;for await(const chunk of response.body){size+=chunk.length;if(size>2*1024*1024)throw Error('Discovery response too large');chunks.push(chunk);}
  return JSON.parse(Buffer.concat(chunks));
}
export async function updateDiscoveryIndex(pool,rows,{query=solr,clock=Date.now,progress=()=>{}}={}){
  let indexed=0,available=0,failed=0;
  for(let offset=0;offset<rows.length;offset+=8){
    const batch=rows.slice(offset,offset+8),docs=[];
    try{
      const known=[...new Set(batch.map(r=>titleDetail(r).externalIds.kinopoisk).filter(id=>/^\d{1,12}$/.test(id||'')))];
      const names=[...new Set(batch.filter(r=>!titleDetail(r).externalIds.kinopoisk).flatMap(r=>{
        const t=titleDetail(r);return [t.originalTitle,t.title].filter(v=>v&&v.length<=100);
      }))];
      const clauses=[];
      if(known.length)clauses.push('id:('+known.join(' OR ')+')');
      for(const name of names)for(const field of ['name_rus','name_eng','name_original'])clauses.push(field+':'+quote(name));
      if(clauses.length){
        const parts=[];let part=[];
        for(const clause of clauses){
          if(part.length&&new URLSearchParams({q:part.concat(clause).join(' OR ')}).toString().length>5500){parts.push(part);part=[];}
          part.push(clause);
        }
        if(part.length)parts.push(part);
        for(const chunk of parts){
          const answer=await query('movie',{q:'('+chunk.join(' OR ')+') AND adult:false',rows:'200',fl:'id,serial,year,name_rus,name_eng,name_original,popularity,rating_kinopoisk,rating_imdb'});
          if(!Array.isArray(answer?.response?.docs))throw Error('Invalid discovery metadata');
          docs.push(...answer.response.docs);
        }
      }
      const identities=batch.map(row=>{
        const title=titleDetail(row),known=title.externalIds.kinopoisk;
        const precise=docs.find(d=>String(d.id)===known&&(d.serial===true||d.serial==='true')===(row.kind==='series')&&(!row.year||Math.abs(Number(d.year)-row.year)<=1));
        return {row,kp:precise?known:known?null:matchKinopoisk(title,docs)};
      });
      const ids=[...new Set(identities.map(r=>r.kp).filter(Boolean))];let counts={};
      if(ids.length){
        const result=await query('torrent',{q:'kinopoisk_id:('+ids.join(' OR ')+') AND playable:true AND trailer:false AND seeds:[1 TO *]',rows:'0',facet:'true','facet.field':'kinopoisk_id','facet.limit':'100','facet.mincount':'1'});
        const facets=result?.facet_counts?.facet_fields?.kinopoisk_id;
        if(!Array.isArray(facets)||facets.length%2)throw Error('Invalid discovery availability facets');
        for(let i=0;i<facets.length;i+=2){const n=Number(facets[i+1]);if(Number.isInteger(n)&&n>0)counts[String(facets[i])]=Math.min(n,1000000);}
      }
      for(const {row,kp}of identities){
        const doc=docs.find(d=>String(d.id)===kp),count=counts[kp]||0;
        const score=Number(doc?.rating_kinopoisk)||Number(doc?.rating_imdb)||titleDetail(row).ratings.find(r=>r.scale===10&&r.value>0)?.value||0;
        const popularity=Math.max(0,Math.min(Number(doc?.popularity)||0,1e12));
        await pool.query('UPDATE titles SET source_kp=?,source_count=?,source_checked_at=?,source_expires_at=?,catalog_popularity=?,catalog_rating=? WHERE id=?',
          [kp,count,new Date(clock()),new Date(clock()+(count?6*3600000:3*3600000)+createHash('sha256').update(row.id).digest().readUInt32BE(0)%(count?6*3600000:3*3600000)),popularity,Math.min(10,Math.max(0,score)),row.id]);
        indexed++;if(count)available++;
      }
    }catch(error){failed+=batch.length;console.warn('Discovery batch unavailable',offset,error.message);}
    progress({processed:Math.min(offset+batch.length,rows.length),total:rows.length,indexed,available,failed});
  }
  return {indexed,available,failed};
}
export function startDiscoveryScheduler({spawnChild,intervalMs=15*60000}={}){
  let child=null;const tick=()=>{
    if(child)return;
    child=spawnChild();child.once('exit',()=>{child=null;});child.once('error',()=>{child=null;});
  };
  const timer=setInterval(tick,intervalMs);timer.unref();const startup=setTimeout(tick,20000);startup.unref();
  return ()=>{clearInterval(timer);clearTimeout(startup);};
}
