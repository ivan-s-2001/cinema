import mariadb from 'mariadb';
import { readFileSync } from 'node:fs';

const db=mariadb.createPool({host:'127.0.0.1',user:process.env.DB_USER||'cinemahall',password:process.env.DB_PASSWORD,database:process.env.DB_NAME||'cinemahall',connectionLimit:1});
const mode=process.argv[2]||'report';
if(!['report','delete'].includes(mode))throw Error('Use report or delete <reviewed-ids.json>');
const anime=/(?:аниме|\banime\b)/i;
function classify(row){
 let p;try{p=typeof row.payload==='string'?JSON.parse(row.payload):row.payload||{}}catch{p={}}
 const ids=p.ids||{};
 const genres=Array.isArray(p.genres)?p.genres:[];
 const reasons=[];
 if(row.id.startsWith('ch:anilist:'))reasons.push('anilist-provider');
 if(ids.myAnimeList||ids.shikimori||ids.anilist)reasons.push('anime-linked-id');
 if(genres.some(g=>anime.test(typeof g==='string'?g:g?.name||'')))reasons.push('anime-genre');
 if(anime.test([p.category,p.subtype,p.typeName].filter(v=>typeof v==='string').join(' ')))reasons.push('anime-category');
 return reasons;
}
try{
 const rows=await db.query('SELECT id,kind,title,year,payload FROM titles');
 const candidates=rows.map(row=>({id:row.id,title:row.title,year:row.year,kind:row.kind,reasons:classify(row)})).filter(r=>r.reasons.length);
 if(mode==='report'){
  console.log(JSON.stringify({total:rows.length,candidates:candidates.length,items:candidates},null,2));
 }else{
  if(!process.argv[3])throw Error('Provide reviewed JSON array of exact IDs to delete');
  const reviewed=JSON.parse(readFileSync(process.argv[3],'utf8'));
  if(!Array.isArray(reviewed)||!reviewed.length||reviewed.some(id=>typeof id!=='string'))throw Error('Expected nonempty JSON array of IDs');
  const approved=new Set(reviewed);
  if(approved.size!==reviewed.length)throw Error('Duplicate IDs');
  const matched=candidates.filter(item=>approved.has(item.id));
  if(matched.length!==approved.size)throw Error('Some IDs are not currently classified as anime; nothing deleted');
  const conn=await db.getConnection();
  try{
   await conn.beginTransaction();
   for(const item of matched)await conn.query('DELETE FROM titles WHERE id=?',[item.id]);
   await conn.commit();
   console.log(JSON.stringify({deleted:matched.length,ids:matched.map(r=>r.id)}));
  }catch(err){await conn.rollback();throw err}finally{conn.release()}
 }
}finally{await db.end()}
