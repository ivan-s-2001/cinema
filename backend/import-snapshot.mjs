import { readFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import mariadb from 'mariadb';
import {canonicalId,saveExternalIds,recordRefresh} from './catalog-identity.mjs';

const root=resolve(process.argv[2]||'catalog');
const index=JSON.parse(readFileSync(join(root,'index.json'),'utf8'));
if(!Array.isArray(index.shards))throw Error('Invalid CinemaHall snapshot index');
const pool=mariadb.createPool({
 host:process.env.DB_HOST||'127.0.0.1',port:Number(process.env.DB_PORT||3306),user:process.env.DB_USER||'cinemahall',
 password:process.env.DB_PASSWORD,database:process.env.DB_NAME||'cinemahall',
 connectionLimit:1,connectTimeout:4000
});
const within=path=> {
 if(!/^(?:shards\/(?:movie|tv)-[0-9]+\.json)$/.test(path))throw Error('Invalid shard path');
 return join(root,path);
};
function stableId(row){
 const ids=row.ids||{};
 for(const source of ['tmdb','kinopoisk','imdb','myAnimeList','shikimori']){
  if(ids[source])return row.type+':'+source+':'+ids[source]+(row.collisionSuffix?':'+row.collisionSuffix:'');
 }
 return null;
}
function nonempty(value){return typeof value==='string'?value:'';}
function thumbnail(ep){
 const image=ep.image||{};
 return [ep.still,ep.thumbnail,image.original,image.medium,image.url]
  .find(v=>typeof v==='string'&&/^https?:\/\//.test(v))||'';
}
let db,locked=false,imported=0,episodeCount=0,skipped=0;
try{
 db=await pool.getConnection();
 const lock=await db.query("SELECT GET_LOCK('cinemahall-catalog-sync',0) AS locked");
 if(Number(lock[0].locked)!==1)throw Error('Another catalog sync is already running');
 locked=true;
 for(const shard of index.shards){
  const rows=JSON.parse(readFileSync(within(shard.path),'utf8'));
  if(!Array.isArray(rows))throw Error('Invalid shard '+shard.path);
  await db.beginTransaction();
  try{
   for(const row of rows){
    if(!['movie','tv'].includes(row.type)){skipped++;continue;}
    const stable=stableId(row);if(!stable){skipped++;continue;}
    // A collision suffix marks an ambiguous upstream identity; do not import it
    // as a second title sharing the same external IDs.
    if(row.collisionSuffix){skipped++;continue;}
    const kind=row.type==='tv'?'series':'movie';
    const id=await canonicalId(db,{...row,id:'ch:snapshot:'+stable,kind});
    const existing=await db.query('SELECT id FROM titles WHERE id=?',[id]);
    const images=row.images||{};
    const name=nonempty(row.titleRu||row.title||row.originalTitle);
    if(!name){skipped++;continue;}
    let year=Number(row.year);
    if(!Number.isInteger(year)||year<1888||year>2100)year=null;
    const poster=nonempty(images.poster);
    const backdrop=images.backdropVerified&&images.backdrop!==poster?nonempty(images.backdrop):'';
    if(!existing.length)await db.query(`INSERT INTO titles
      (id,kind,title,year,description,poster,backdrop,payload)
      VALUES (?,?,?,?,?,?,?,?)
      ON DUPLICATE KEY UPDATE kind=VALUES(kind),title=VALUES(title),
      year=VALUES(year),description=VALUES(description),poster=VALUES(poster),
      backdrop=VALUES(backdrop),payload=VALUES(payload)`,
     [id,kind,name,year,nonempty(row.description),
      poster,backdrop,JSON.stringify(row)]);
    await saveExternalIds(db,{...row,id,kind});
    if(!existing.length)await recordRefresh(db,id,row);
    imported++;
    const seasons=row.details?.seasons;
    if(row.type==='tv'&&Array.isArray(seasons)){
     for(const season of seasons){
      const sn=Number(season.number);
      if(!Number.isInteger(sn)||sn<1||sn>999||!Array.isArray(season.episodes))continue;
      for(const ep of season.episodes){
       const en=Number(ep.number);
       if(!Number.isInteger(en)||en<1||en>9999)continue;
       const date=ep.airdate||ep.date;
       const air=typeof date==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(date)&&Number.isFinite(Date.parse(date))&&new Date(date).toISOString().slice(0,10)===date?date:null;
       await db.query(`INSERT INTO episodes
         (title_id,season_number,episode_number,title,air_date,still,payload)
         VALUES (?,?,?,?,?,?,?)
         ON DUPLICATE KEY UPDATE title=VALUES(title),air_date=VALUES(air_date),
         still=VALUES(still),payload=VALUES(payload)`,
        [id,sn,en,nonempty(ep.name),air,thumbnail(ep),JSON.stringify(ep)]);
       episodeCount++;
      }
     }
    }
   }
   await db.commit();
   console.log(shard.path+': '+rows.length+' rows processed');
  }catch(error){await db.rollback();throw error;}
 }
 console.log(JSON.stringify({imported,episodes:episodeCount,skipped,expected:index.count}));
}finally{
 if(locked)await db.query("SELECT RELEASE_LOCK('cinemahall-catalog-sync')");
 if(db)db.release();
 await pool.end();
}
