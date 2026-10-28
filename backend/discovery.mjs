// Read-only discovery API. Works with existing titles.payload JSON; no schema migration.
const kinds=new Set(['movie','series']);
const sortColumns={recent:'updated_at DESC',year:'year DESC',title:'title ASC'};
const text=v=>typeof v==='string'?v:'';
const array=v=>Array.isArray(v)?v:[];
const obj=v=>{if(v&&typeof v==='object')return v;try{return JSON.parse(v||'{}')}catch{return {}}};
const labels=v=>array(v).map(g=>typeof g==='string'?g:(g?.name||g?.title||'')).filter(Boolean);
function card(row){
 const p=obj(row.payload);
 const images=p.images||{};
 return {id:row.id,type:row.kind,title:row.title,titleRu:text(p.titleRu)||row.title,
  titleEn:text(p.titleEn),originalTitle:text(p.originalTitle),aliases:array(p.aliases),
  year:row.year,description:row.description||'',poster:row.poster||'',
  backdrop:row.backdrop||'',genres:labels(p.genres),countries:labels(p.countries),
  ratings:p.ratings||{},status:p.status||null,releaseDate:p.releaseDate||null,
  runtimeMinutes:p.runtimeMinutes||null,ageRating:p.ageRating||null,
  ids:p.ids||{},sources:array(p.sources),updatedAt:row.updated_at};
}
const columns='id,kind,title,year,description,poster,backdrop,payload,updated_at';
async function select(pool,where,values,sort,limit,offset=0){
 const rows=await pool.query('SELECT '+columns+' FROM titles '+where+
 ' ORDER BY '+(sortColumns[sort]||sortColumns.recent)+' LIMIT ? OFFSET ?',
 [...values,limit,offset]);
 return rows.map(card);
}
export async function handleDiscovery(req,res,url,pool,send){
 const path=url.pathname;
 if(!['/api/v1/genres','/api/v1/discover','/api/v1/filters','/api/v1/catalog-stats'].includes(path))return false;
 if(req.method!=='GET'){send(res,405,{error:'Method not allowed'});return true;}
 try{
  if(path==='/api/v1/catalog-stats'){
   const counts=await pool.query('SELECT kind, COUNT(*) AS total, SUM(poster IS NOT NULL AND poster<>"") AS withPoster, SUM(description IS NOT NULL AND description<>"") AS withDescription FROM titles GROUP BY kind');
   send(res,200,{counts});return true;
  }
  const rows=await pool.query('SELECT kind,year,payload FROM titles');
  const genreMap=new Map(),countryMap=new Map(),years=new Set();
  for(const r of rows){
   const p=obj(r.payload);
   if(r.year)years.add(r.year);
   for(const g of labels(p.genres)){if(/^(anime|аниме)$/i.test(g))continue;const k=g.trim();genreMap.set(k,(genreMap.get(k)||0)+1);}
   for(const c of labels(p.countries)){const k=c.trim();countryMap.set(k,(countryMap.get(k)||0)+1);}
  }
  const sorted=m=>[...m].map(([name,count])=>({name,count})).sort((a,b)=>b.count-a.count||a.name.localeCompare(b.name,'ru'));
  if(path==='/api/v1/genres'){send(res,200,{genres:sorted(genreMap)});return true;}
  if(path==='/api/v1/filters'){send(res,200,{types:['movie','series'],genres:sorted(genreMap),countries:sorted(countryMap),years:[...years].sort((a,b)=>b-a)});return true;}
  const kind=url.searchParams.get('type');
  const genre=text(url.searchParams.get('genre')).trim().slice(0,80);
  const country=text(url.searchParams.get('country')).trim().slice(0,80);
  const from=Number(url.searchParams.get('yearFrom')||0),to=Number(url.searchParams.get('yearTo')||0);
  const limit=Math.min(60,Math.max(1,parseInt(url.searchParams.get('limit')||'24',10)||24));
  const page=Math.min(10000,Math.max(1,parseInt(url.searchParams.get('page')||'1',10)||1));
  const sort=url.searchParams.get('sort')||'recent';
  if((kind&&!kinds.has(kind))||(genre&&genre.length<2)||(country&&country.length<2)||
    (from&&(!Number.isInteger(from)||from<1888||from>2100))||
    (to&&(!Number.isInteger(to)||to<1888||to>2100))||
    !Object.hasOwn(sortColumns,sort)){send(res,400,{error:'Invalid filter'});return true;}
  const clauses=['1=1'],values=[];
  if(kind){clauses.push('kind=?');values.push(kind);}
  if(from){clauses.push('year>=?');values.push(from);}
  if(to){clauses.push('year<=?');values.push(to);}
  // Match complete genre/country entries, not substrings.
  if(genre){clauses.push("JSON_CONTAINS(COALESCE(JSON_EXTRACT(payload,'$.genres'),'[]'),JSON_QUOTE(?))");values.push(genre);}
  if(country){clauses.push("JSON_CONTAINS(COALESCE(JSON_EXTRACT(payload,'$.countries'),'[]'),JSON_QUOTE(?))");values.push(country);}
  const where='WHERE '+clauses.join(' AND ');
  const items=await select(pool,where,values,sort,limit,(page-1)*limit);
  send(res,200,{items,page,limit,hasMore:items.length===limit,filters:{type:kind||null,genre:genre||null,country:country||null,yearFrom:from||null,yearTo:to||null,sort}});
 }catch(error){console.error('Discovery API',error.code||error.name);send(res,503,{error:'Catalog temporarily unavailable'});}
 return true;
}
