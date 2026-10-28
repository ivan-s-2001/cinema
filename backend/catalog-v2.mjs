import { createHash, randomUUID } from 'node:crypto';
import { createSourceDiscovery } from './source-discovery.mjs';
import { titleCard, titleDetail, episode, seasonId } from './catalog-model.mjs';
import { tvMetadata, tvPreview } from './tv-meta.mjs';
import {collections, rowSpec, availableWhere} from './collections.mjs';
import {createMetadataLibrary} from './metadata-library.mjs';
import {object,imageFor} from './catalog-model.mjs';
import {watchOrdersFor,orderedWatchItems} from './watch-orders.mjs';

const prefix = '/api/v2';
const columns = 'id,kind,title,year,description,poster,backdrop,payload,updated_at,source_count,source_checked_at,source_expires_at,catalog_rating,catalog_popularity,source_kp';
const idPattern = /^[A-Za-z0-9:_-]{1,160}$/;
const ratingScore="COALESCE(CAST(JSON_VALUE(payload,'$.ratings.kinopoisk.value') AS DECIMAL(5,2)),CAST(JSON_VALUE(payload,'$.ratings.kinopoisk') AS DECIMAL(5,2)),CAST(JSON_VALUE(payload,'$.ratings.imdb.value') AS DECIMAL(5,2)),CAST(JSON_VALUE(payload,'$.ratings.imdb') AS DECIMAL(5,2)),0)";
const sorts = { title: ['title','ASC'], year: ['COALESCE(year,0)','DESC'], rating:["COALESCE(NULLIF(catalog_rating,0),"+ratingScore+")",'DESC'], popular:['(catalog_popularity+catalog_rating/100)','DESC'] };
const publicWhere = "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.publicationState')),'') NOT IN ('hidden','draft','review') AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.curator.hidden')),'false') <> 'true'";
const releasedWhere = "(year IS NULL OR year<=YEAR(UTC_DATE())) AND ((JSON_UNQUOTE(JSON_EXTRACT(payload,'$.releaseDate')) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}' AND LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.releaseDate')),10)<=UTC_DATE()) OR LOWER(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')),'')) IN ('released','ended','finished','completed','canceled','cancelled','вышел','вышло','завершён','завершен','returning series','ongoing','airing','выходит')) AND (JSON_EXTRACT(payload,'$.releaseDate') IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.releaseDate')) IN ('','null') OR LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.releaseDate')),10)<=UTC_DATE())";
const rows=collections;
class InputError extends Error {}
function number(params,key,fallback,min,max) {
  if (!params.has(key)) return fallback;
  const raw = params.get(key);
  if (!/^\d+$/.test(raw)) throw new InputError('Invalid '+key);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value<min || value>max) throw new InputError('Invalid '+key);
  return value;
}
const fingerprint = value => createHash('sha256').update(JSON.stringify(value)).digest('hex').slice(0,24);
function decodeCursor(raw, scope) {
  if (!raw || raw.length>2048 || !/^[A-Za-z0-9_-]+$/.test(raw)) throw new InputError('Invalid cursor');
  try {
    const c = JSON.parse(Buffer.from(raw,'base64url').toString());
    if (c.v!==1 || c.scope!==scope || typeof c.id!=='string' || !idPattern.test(c.id) || !['string','number'].includes(typeof c.key) || String(c.key).length>512) throw new Error();
    return c;
  } catch { throw new InputError('Cursor does not match this query'); }
}
const cursor = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const escaped = value => value.replace(/[!%_]/g,c=>'!'+c);
function filters(params) {
  for (const [key] of params) if (!['type','q','genre','country','yearFrom','yearTo','sort','limit','cursor'].includes(key)) throw new InputError('Unknown parameter: '+key);
  const type=params.get('type')||null, sort=params.get('sort')||'title', q=(params.get('q')||'').trim();
  const genre=(params.get('genre')||'').trim(), country=(params.get('country')||'').trim();
  if ((type&&!['movie','series'].includes(type)) || !Object.hasOwn(sorts,sort) || q.length>120 || (q&&q.length<2) || genre.length>80 || country.length>80) throw new InputError('Invalid filter');
  const yearFrom=number(params,'yearFrom',null,1888,2100), yearTo=number(params,'yearTo',null,1888,2100);
  if (yearFrom&&yearTo&&yearFrom>yearTo) throw new InputError('Invalid year range');
  return { type,sort,q,genre,country,yearFrom,yearTo,limit:number(params,'limit',24,1,60) };
}
export function createCatalogV2(pool,{sourceDiscovery=createSourceDiscovery({enabled:process.env.CINEMA_SOURCE_DISCOVERY==='1'}),metadataLibrary=createMetadataLibrary(pool)}={}) {
  let homePending=null;
  // Only public metadata lives here. Bound both entry count and total serialized bytes.
  const cache=new Map();let cacheBytes=0;
  function remember(key,bytes) {
    const size=Buffer.byteLength(bytes);
    if(size>512*1024)return;
    if(cache.has(key)){cacheBytes-=cache.get(key).size;cache.delete(key);}
    cache.set(key,{bytes,size,until:Date.now()+15000});cacheBytes+=size;
    while(cache.size>64||cacheBytes>2*1024*1024){const first=cache.keys().next().value;cacheBytes-=cache.get(first).size;cache.delete(first);}
  }
  async function browse(params, shelf=null, db=pool, person=null) {
    const f=filters(params);
    if(shelf&&!params.has('sort'))f.sort=shelf.sort;
    if (shelf) { if(f.type&&f.type!==shelf.type) throw new InputError('Invalid row type'); f.type=shelf.type; }
    const scope=fingerprint({ ...f, limit:undefined, shelf:shelf?.id||null,person });
    const clauses=[publicWhere,availableWhere,releasedWhere], values=[];
    if(person){clauses.push('id IN (SELECT title_id FROM title_cast WHERE person_id=?)');values.push(person);}
    if (shelf) {
      clauses.push(releasedWhere,"poster IS NOT NULL AND poster<>''");
      if(shelf.minRating){clauses.push('catalog_rating>=?');values.push(shelf.minRating);}
      if(shelf.recent)clauses.push('year>=YEAR(UTC_DATE())-2');
      if(shelf.genres){clauses.push('('+shelf.genres.map(()=>"JSON_CONTAINS(LOWER(COALESCE(JSON_EXTRACT(payload,'$.genres'),'[]')),JSON_QUOTE(?))").join(' OR ')+')');values.push(...shelf.genres);}
    }
    if(f.type){clauses.push('kind=?');values.push(f.type);}
    if(f.yearFrom){clauses.push('year>=?');values.push(f.yearFrom);}
    if(f.yearTo){clauses.push('year<=?');values.push(f.yearTo);}
    for (const field of ['genre','country']) if(f[field]) {
      clauses.push(`JSON_CONTAINS(COALESCE(JSON_EXTRACT(payload,'$.${field==='genre'?'genres':'countries'}'),'[]'),JSON_QUOTE(?))`);
      values.push(f[field]);
    }
    if(f.q){
      const pattern='%'+escaped(f.q.toLowerCase())+'%';
      clauses.push("(LOWER(title) LIKE ? ESCAPE '!' OR JSON_SEARCH(LOWER(payload),'one',?,'!','$.titleru','$.titleen','$.originaltitle','$.aliases[*]') IS NOT NULL)");
      values.push(pattern,pattern);
    }
    const [column,direction]=sorts[f.sort];
    if(params.has('cursor')){
      const c=decodeCursor(params.get('cursor'),scope);
      if((f.sort==='year'&&(!Number.isInteger(c.key)||c.key<0||c.key>2100)) || (['rating','popular'].includes(f.sort)&&(typeof c.key!=='number'||c.key<0||c.key>(f.sort==='rating'?10:1e13))) || (f.sort==='title'&&typeof c.key!=='string')) throw new InputError('Invalid cursor key');
      clauses.push(`(${column} ${direction==='ASC'?'>':'<'} ? OR (${column}=? AND id>?))`);
      values.push(c.key,c.key,c.id);
    }
    const result=await db.query(`SELECT ${columns}${['rating','popular'].includes(f.sort)?','+column+' AS rank_score':''} FROM titles WHERE ${clauses.join(' AND ')} ORDER BY ${column} ${direction},id ASC LIMIT ?`,[...values,f.limit+1]);
    const page=result.slice(0,f.limit), last=page.at(-1);
    return { items:page.map(r=>titleCard(r)), nextCursor:result.length>f.limit?cursor({v:1,scope,id:last.id,key:f.sort==='year'?(last.year||0):['rating','popular'].includes(f.sort)?Number(last.rank_score||0):last.title}):null };
  }
  async function find(id) {
    if (!idPattern.test(id)) throw new InputError('Invalid title ID');
    const result=await pool.query(`SELECT ${columns} FROM titles WHERE id=? AND ${publicWhere} LIMIT 1`,[id]);
    return result[0]||null;
  }
  async function cycles(row){
    const orders=watchOrdersFor(object(row.payload).ids?.tmdb),result=[];
    for(const order of orders){
      const found=await pool.query(`SELECT ${columns} FROM titles WHERE kind='movie' AND ${publicWhere} AND ${availableWhere} AND ${releasedWhere} AND CAST(JSON_VALUE(payload,'$.ids.tmdb') AS UNSIGNED) IN (${order.tmdb.map(()=>'?').join(',')})`,order.tmdb);
      const items=orderedWatchItems(order,found,tvPreview);
      result.push({id:order.id,name:order.name,order:order.order,source:order.source,totalParts:order.tmdb.length,availableParts:items.length,items});
    }
    return result;
  }
  async function related(row){
    const genres=titleDetail(row).genres.slice(0,6).map(g=>g.toLowerCase());
    const expressions=genres.map(()=>"CASE WHEN JSON_CONTAINS(LOWER(COALESCE(JSON_EXTRACT(payload,'$.genres'),'[]')),JSON_QUOTE(?)) THEN 3 ELSE 0 END");
    expressions.push('(SELECT COUNT(*) FROM title_cast tc WHERE tc.title_id=titles.id AND tc.person_id IN (SELECT person_id FROM title_cast WHERE title_id=?))*2');
    return pool.query(`SELECT ${columns},(${expressions.join('+')}) AS related_rank FROM titles WHERE id<>? AND kind=? AND ${publicWhere} AND ${availableWhere} AND ${releasedWhere} AND catalog_rating>=6 HAVING related_rank>0 ORDER BY related_rank DESC,catalog_rating DESC,catalog_popularity DESC,id LIMIT 12`,[...genres,row.id,row.id,row.kind]);
  }
  return async function handle(req,res,url) {
    if(url.pathname!==prefix&&!url.pathname.startsWith(prefix+'/')) return false;
    const requestId=randomUUID();
    const cacheKey=url.pathname+url.search;
    function sendBytes(status,bytes) {
      res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':status===200?'public, max-age=15':'no-store','x-content-type-options':'nosniff','x-request-id':requestId,'content-length':Buffer.byteLength(bytes),...(status===405?{allow:'GET, HEAD'}:{})});
      res.end(req.method==='HEAD'?undefined:bytes);
    }
    function send(status,body){const bytes=JSON.stringify(body);if(status===200)remember(cacheKey,bytes);sendBytes(status,bytes);}
    const fail=(status,code,message)=>send(status,{error:{code,message,retryable:status===503,requestId}});
    try {
      if(!['GET','HEAD'].includes(req.method)){fail(405,'method_not_allowed','GET or HEAD required');return true;}
      const saved=cache.get(cacheKey);
      if(saved&&saved.until>Date.now()){sendBytes(200,saved.bytes);return true;}
      const path=url.pathname.slice(prefix.length);
      if(path==='/config') {
        send(200,{apiVersion:2,capabilities:{home:true,search:true,discover:true,seasons:true,episodes:true,playbackStrategy:'local',serverPlaybackOptions:false,sourceDiscovery:sourceDiscovery.enabled,profileSync:false},limits:{pageSize:60},rows:rows.map(rowSpec)});return true;
      }
      if(path==='/home') {
        // One connection and one query at a time. Concurrent cold-home callers
        // share this work instead of each queueing eight pool acquisitions.
        if(!homePending)homePending=(async()=>{
          const db=await pool.getConnection();
          try{
            const result=[];
            for(const shelf of rows.filter(r=>r.home))result.push({...rowSpec(shelf),...await browse(new URLSearchParams('limit=12'),shelf,db)});
            return {rows:result};
          }finally{db.release();}
        })().finally(()=>{homePending=null;});
        send(200,await homePending);return true;
      }
      const shelf=/^\/rows\/([a-z_]+)\/items$/.exec(path);
      if(shelf){const row=rows.find(r=>r.id===shelf[1]);if(!row){fail(404,'not_found','Row not found');return true;}send(200,await browse(url.searchParams,row));return true;}
      if(path==='/search'||path==='/discover'){
        if(path==='/search'&&(url.searchParams.get('q')||'').trim().length<2)throw new InputError('Search query must contain at least 2 characters');
        send(200,await browse(url.searchParams));return true;
      }
      const person=/^\/people\/(person:[a-f0-9]{32})(?:\/(filmography))?$/.exec(path);
      if(person){
        const people=await pool.query('SELECT id,name,photo FROM people WHERE id=?',[person[1]]);
        if(!people.length){fail(404,'not_found','Actor not found');return true;}
        const page=await browse(url.searchParams,null,pool,person[1]);
        send(200,{person:{...people[0],photo:imageFor(people[0].photo,'person')},...page});return true;
      }
      const cycle=/^\/titles\/([A-Za-z0-9:_-]+)\/watch-orders$/.exec(path);
      if(cycle){const row=await find(cycle[1]);if(!row){fail(404,'not_found','Title not found');return true;}send(200,{items:await cycles(row)});return true;}
      const similar=/^\/titles\/([A-Za-z0-9:_-]+)\/related$/.exec(path);
      if(similar){const row=await find(similar[1]);if(!row){fail(404,'not_found','Title not found');return true;}send(200,{items:(await related(row)).map(titleCard),nextCursor:null});return true;}
      const title=/^\/titles\/([A-Za-z0-9:_-]+)(?:\/(seasons|playback-options|source-candidates|tv-meta))?$/.exec(path);
      if(title){
        let row=await find(title[1]);if(!row){fail(404,'not_found','Title not found');return true;}
        if(!title[2]){await metadataLibrary.ensure(row);row=await find(row.id);send(200,{title:titleDetail(row)});return true;}
        if(title[2]==='tv-meta'){
          await metadataLibrary.ensure(row);row=await find(row.id);
          const episodes=row.kind==='series'?await pool.query('SELECT title_id,season_number,episode_number,title,air_date,still,payload FROM episodes WHERE title_id=? AND season_number>0 ORDER BY season_number,episode_number LIMIT 10001',[row.id]):[];
          if(episodes.length>10000){fail(503,'tv_metadata_too_large','Use paginated episode routes');return true;}
          const result=tvMetadata(row,episodes);
          result.meta.cinemaRelated=(await related(row)).map(tvPreview);
          result.meta.cinemaWatchOrders=await cycles(row);
          if(Buffer.byteLength(JSON.stringify(result))>512*1024)for(const video of result.meta.videos||[]){delete video.overview;delete video.thumbnail;}
          if(Buffer.byteLength(JSON.stringify(result))>2*1024*1024){fail(503,'tv_metadata_too_large','Use paginated episode routes');return true;}
          send(200,result);return true;
        }
        if(title[2]==='source-candidates'){
          if(!sourceDiscovery.enabled){fail(503,'source_discovery_disabled','Source discovery is not enabled');return true;}
          const detail=titleDetail(row), kp=row.source_kp||detail.externalIds.kinopoisk;
          if(!kp&&!sourceDiscovery.loadForTitle){fail(404,'source_identity_missing','Kinopoisk identity is unavailable');return true;}
          const ep=url.searchParams.get('episode')||'';
          if([...url.searchParams.keys()].some(key=>key!=='episode')||(ep&&!/^S[0-9]{1,3}E[0-9]{1,4}$/.test(ep)))throw new InputError('Invalid episode');
          const result=kp?await sourceDiscovery.load(kp,ep):await sourceDiscovery.loadForTitle(detail,ep);
          send(200,{titleId:row.id,episode:ep,engine:'local',status:result.status,candidates:result.candidates});return true;
        }
        if(title[2]==='playback-options'){
          const detail=titleDetail(row);
          send(200,{titleId:row.id,strategy:'local',matchIdentity:{type:row.kind,externalIds:detail.externalIds,names:[row.title,...detail.aliases].filter((v,i,a)=>a.indexOf(v)===i),year:row.year},availability:detail.availability});return true;
        }
        if(row.kind!=='series'){fail(404,'not_found','Title is not a series');return true;}
        await metadataLibrary.ensure(row);
        const seasons=await pool.query('SELECT season_number,COUNT(*) AS episode_count FROM episodes WHERE title_id=? GROUP BY season_number ORDER BY season_number',[row.id]);
        send(200,{titleId:row.id,items:seasons.map(s=>({id:seasonId(row.id,s.season_number),titleId:row.id,number:s.season_number,episodeCount:Number(s.episode_count)}))});return true;
      }
      const season=/^\/seasons\/([A-Za-z0-9:_-]+):s(\d{1,3})\/episodes$/.exec(path);
      if(season){
        const row=await find(season[1]);if(!row||row.kind!=='series'){fail(404,'not_found','Series not found');return true;}
        const allowed=new Set(['limit','after']);for(const [key]of url.searchParams)if(!allowed.has(key))throw new InputError('Unknown parameter: '+key);
        const limit=number(url.searchParams,'limit',40,1,100),after=number(url.searchParams,'after',0,0,9999);
        const items=await pool.query('SELECT * FROM episodes WHERE title_id=? AND season_number=? AND episode_number>? ORDER BY episode_number LIMIT ?',[row.id,Number(season[2]),after,limit+1]);
        if(!items.length){const count=await pool.query('SELECT COUNT(*) AS n FROM episodes WHERE title_id=? AND season_number=?',[row.id,Number(season[2])]);if(!Number(count[0].n)){fail(404,'not_found','Season not found');return true;}}
        send(200,{items:items.slice(0,limit).map(r=>episode(r)),nextAfter:items.length>limit?items[limit-1].episode_number:null});return true;
      }
      const ep=/^\/episodes\/([A-Za-z0-9:_-]+):(\d{1,3}):(\d{1,4})(\/next)?$/.exec(path);
      if(ep){
        const row=await find(ep[1]);if(!row||row.kind!=='series'){fail(404,'not_found','Series not found');return true;}
        const sn=Number(ep[2]),en=Number(ep[3]);
        const found=await pool.query('SELECT * FROM episodes WHERE title_id=? AND season_number=? AND episode_number=? LIMIT 1',[row.id,sn,en]);
        if(!found.length){fail(404,'not_found','Episode not found');return true;}
        if(!ep[4]){send(200,{episode:episode(found[0])});return true;}
        // Specials do not enter the main-series autoplay sequence.
        const next=sn===0?[]:await pool.query('SELECT * FROM episodes WHERE title_id=? AND season_number>0 AND (season_number>? OR (season_number=? AND episode_number>?)) ORDER BY season_number,episode_number LIMIT 1',[row.id,sn,sn,en]);
        const candidate=next[0]?episode(next[0]):null;
        send(200,{episode:candidate,canAutoPlay:candidate?.releaseState==='released',reason:!candidate?'end_of_series':candidate.releaseState==='released'?'aired':'not_aired'});return true;
      }
      fail(404,'not_found','Route not found');
    } catch(err) {
      if(err instanceof InputError)fail(400,'invalid_request',err.message);
      else { console.error('Catalog v2 failure',requestId,err.code||err.name);fail(503,'catalog_unavailable','Catalog temporarily unavailable'); }
    }
    return true;
  };
}
