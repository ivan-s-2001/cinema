import {createHash} from 'node:crypto';
import {object,text,imageFor,date} from './catalog-model.mjs';
import {createMetadataProvider} from './metadata-provider.mjs';

export function personCredit(raw){
  const name=(text(raw.name_ru)||text(raw.name_en)||text(raw.name))?.slice(0,256);
  if(!name)return null;
  const identity=['number','string'].includes(typeof raw.id)?'kinoapi:'+String(raw.id):name.toLowerCase().replaceAll('ё','е');
  const id=typeof raw.id==='string'&&/^person:[a-f0-9]{32}$/.test(raw.id)?raw.id:'person:'+createHash('sha256').update(identity).digest('hex').slice(0,32);
  const path=text(raw.profile_path)||text(raw.photo)||text(raw.image);
  return {id,name,character:(text(raw.character_ru)||text(raw.character_en)||text(raw.character))?.slice(0,512)||null,
    photo:imageFor(path?.startsWith('/')?'https://image.tmdb.org/t/p/w185'+path:path,'person')};
}
export function seasonInventory(raw){
  if(!Array.isArray(raw))throw Error('Invalid season inventory');
  const seen=new Set();
  return raw.flatMap(value=>{
    const number=value.season_number,count=value.episode_count;
    if(!Number.isInteger(number)||number<1||number>999||!Number.isInteger(count)||count<1||count>2000||seen.has(number))return [];
    seen.add(number);return [{number,count,airAt:date(value.air_date)?.slice(0,10)||null}];
  }).sort((a,b)=>a.number-b.number).slice(0,100);
}
export function createMetadataLibrary(pool,{provider=createMetadataProvider(),clock=Date.now}={}){
  const pending=new Map(),retry=new Map();let background=Promise.resolve(),queued=0,closed=false;
  const detailPending=new Set();
  async function credits(row,payload,tmdb){
    if(payload.creditsCheckedAt&&Date.parse(payload.creditsCheckedAt)>clock()-7*86400000)return;
    const response=await provider.get('/titles/by/tmdb/'+tmdb+'/credits?type='+(row.kind==='series'?'tv':'movie'));
    if(!Array.isArray(response.data))throw Error('Invalid credits response');
    const cast=response.data.filter(p=>['actor','actress','voice','voice_actor'].includes(String(p.profession).toLowerCase())).map(personCredit).filter(Boolean).slice(0,80);
    const db=await pool.getConnection();
    try{
      await db.beginTransaction();
      for(const [order,person]of cast.entries()){
        await db.query('INSERT INTO people(id,name,photo,payload) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),photo=COALESCE(VALUES(photo),photo)',[person.id,person.name,person.photo,JSON.stringify({source:'kinoapi'})]);
        await db.query('INSERT INTO title_cast(title_id,person_id,character_name,billing_order)VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE character_name=VALUES(character_name),billing_order=VALUES(billing_order)',[row.id,person.id,person.character,order]);
      }
      // A successful empty response is recorded, but never erases existing credits.
      if(cast.length)await db.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.cast',JSON_QUERY(?,'$'),'$.creditsCheckedAt',?) WHERE id=?",[JSON.stringify(cast),new Date(clock()).toISOString(),row.id]);
      else await db.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.creditsCheckedAt',?) WHERE id=?",[new Date(clock()).toISOString(),row.id]);
      await db.commit();
    }catch(error){await db.rollback();throw error;}finally{db.release();}
  }
  async function seasonDetails(row,tmdb,season){
    const answer=await provider.get('/tv/by/tmdb/'+tmdb+'/seasons/'+season.number+'/episodes');
    if(!Array.isArray(answer.data))throw Error('Invalid episode response');
    for(const raw of answer.data){
      const number=raw.episode_number;
      if(!Number.isInteger(number)||number<1||number>9999)continue;
      const air=date(raw.air_date)?.slice(0,10)||null;
      const path=text(raw.still_path)||text(raw.still)||text(raw.thumbnail)||text(raw.image?.original)||text(raw.image?.medium);
      const still=imageFor(path?.startsWith('/')?'https://image.tmdb.org/t/p/w1280'+path:path,'backdrop');
      await pool.query(`INSERT INTO episodes(title_id,season_number,episode_number,title,air_date,still,payload)VALUES(?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE title=COALESCE(NULLIF(VALUES(title),''),title),air_date=COALESCE(VALUES(air_date),air_date),still=COALESCE(VALUES(still),still),payload=JSON_MERGE_PATCH(COALESCE(payload,JSON_OBJECT()),VALUES(payload))`,
        [row.id,season.number,number,text(raw.name_ru)||text(raw.name_en)||text(raw.name),air,still,JSON.stringify({...((text(raw.overview_ru)||text(raw.overview_en)||text(raw.overview))?{description:text(raw.overview_ru)||text(raw.overview_en)||text(raw.overview)}:{})})]);
    }
  }
  async function episodes(row,payload,tmdb){
    const last=Date.parse(payload.episodesCheckedAt||'');
    if(payload.episodesArtworkCheckedAt&&Date.parse(payload.episodesDetailsCheckedAt||'')>clock()-6*3600000)return;
    let seasons;
    if(last>clock()-6*3600000){
      seasons=(await pool.query('SELECT season_number AS number,COUNT(*) AS count FROM episodes WHERE title_id=? AND season_number>0 GROUP BY season_number ORDER BY season_number',[row.id])).map(s=>({...s,count:Number(s.count)}));
    }else{
    const response=await provider.get('/tv/by/tmdb/'+tmdb+'/seasons');
    seasons=seasonInventory(response.data);
    const count=seasons.reduce((n,s)=>n+s.count,0);
    if(count>10000)throw Error('Series exceeds the episode inventory budget');
    const db=await pool.getConnection();
    try{
      await db.beginTransaction();
      // Numbered placeholders come only from authoritative per-season counts.
      // Dates and episode names are never invented. Existing details are preserved.
      for(const season of seasons){
        const values=Array.from({length:season.count},(_,i)=>[row.id,season.number,i+1,'Серия '+(i+1),JSON.stringify({seasonAirAt:season.airAt})]);
        for(let i=0;i<values.length;i+=100)await db.batch('INSERT IGNORE INTO episodes(title_id,season_number,episode_number,title,payload)VALUES(?,?,?,?,?)',values.slice(i,i+100));
      }
      await db.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.episodesCheckedAt',?) WHERE id=?",[new Date(clock()).toISOString(),row.id]);
      await db.commit();
    }catch(error){await db.rollback();throw error;}finally{db.release();}
    }
    // Warm one season before returning the first page; names and screenshots
    // no longer depend on a background task finishing after the TV cached it.
    let warmedSeason=null;
    if(seasons.length&&!payload.episodesArtworkCheckedAt){
      try{
        await seasonDetails(row,tmdb,seasons[0]);
        warmedSeason=seasons[0].number;
        await pool.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.episodesArtworkCheckedAt',?) WHERE id=?",[new Date(clock()).toISOString(),row.id]);
      }catch(error){console.warn('Episode artwork warmup',row.id,error.message);}
    }
    // The inventory is useful immediately. Names/stills for other seasons are
    // filled by one bounded background worker, never by the TV.
    if(seasons.length&&queued<8&&!closed&&!detailPending.has(row.id)){
      queued++;detailPending.add(row.id);
      background=background.catch(()=>{}).then(async()=>{
        let complete=true;
        for(const season of seasons){if(season.number===warmedSeason)continue;if(closed){complete=false;break;}try{await seasonDetails(row,tmdb,season);}catch(error){complete=false;console.warn('Episode detail refresh',row.id,season.number,error.message);}}
        if(complete)await pool.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.episodesDetailsCheckedAt',?) WHERE id=?",[new Date(clock()).toISOString(),row.id]);
      }).finally(()=>{queued--;detailPending.delete(row.id);});
    }
  }
  async function ensure(row){
    if(!provider.enabled||closed)return;
    if(pending.has(row.id))return pending.get(row.id);
    if((retry.get(row.id)||0)>clock())return;
    const payload=object(row.payload);
    const work=(async()=>{
      let tmdb=String(payload.ids?.tmdb||payload.metadataTmdbId||'');
      if(!/^\d{1,12}$/.test(tmdb)){
        const kp=String(payload.ids?.kinopoisk||row.source_kp||'');
        if(!/^\d{1,12}$/.test(kp))return;
        try{
          const response=await provider.get('/titles/by/kinopoisk/'+kp+'?include=images');
          const source=response.data;
          if(String(source?.id?.kinopoisk)!==kp||source?.type!==(row.kind==='series'?'tv':'movie'))throw Error('Metadata identity mismatch');
          tmdb=String(source?.id?.tmdb||'');if(!/^\d{1,12}$/.test(tmdb))return;
          await pool.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.metadataTmdbId',?) WHERE id=?",[tmdb,row.id]);
        }catch(error){retry.set(row.id,clock()+60000);console.warn('Metadata identity refresh',row.id,error.message);return;}
      }
      try{await credits(row,payload,tmdb);}catch(error){console.warn('Credits refresh',row.id,error.message);retry.set(row.id,clock()+60000);}
      if(row.kind==='series')try{await episodes(row,payload,tmdb);}catch(error){console.warn('Episode inventory refresh',row.id,error.message);retry.set(row.id,clock()+60000);}
      while(retry.size>256)retry.delete(retry.keys().next().value);
    })();pending.set(row.id,work);try{await work;}finally{pending.delete(row.id);}
  }
  async function indexExisting(row){
    const payload=object(row.payload),raw=Array.isArray(payload.cast)&&payload.cast.length?payload.cast:payload.details?.cast;
    if(!Array.isArray(raw)||!raw.length)return;
    const cast=raw.filter(value=>value&&typeof value==='object').map(personCredit).filter(Boolean).slice(0,80);
    for(const [order,person]of cast.entries()){
      await pool.query('INSERT INTO people(id,name,photo,payload)VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE photo=COALESCE(VALUES(photo),photo)',[person.id,person.name,person.photo,JSON.stringify({source:'snapshot'})]);
      await pool.query('INSERT IGNORE INTO title_cast(title_id,person_id,character_name,billing_order)VALUES(?,?,?,?)',[row.id,person.id,person.character,order]);
    }
    if(cast.length)await pool.query("UPDATE titles SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.cast',JSON_QUERY(?,'$')) WHERE id=?",[JSON.stringify(cast),row.id]);
  }
  return {ensure,indexExisting,async idle(){await background;},async close(){closed=true;await Promise.allSettled([...pending.values(),background]);}};
}
