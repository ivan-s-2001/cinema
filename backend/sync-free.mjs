import mariadb from 'mariadb';

const source = process.argv[2] || 'tvmaze';
const limit = Math.min(100,Math.max(1,Number(process.argv[3]||20)));
if(source!=='tvmaze')throw Error('CinemaHall imports series only: node sync-free.mjs tvmaze [limit 1..100]');
const pool=mariadb.createPool({host:'127.0.0.1',user:process.env.DB_USER||'cinemahall',password:process.env.DB_PASSWORD,database:process.env.DB_NAME||'cinemahall',connectionLimit:1,connectTimeout:5000});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function fetchJson(url,options={}){
 const response=await fetch(url,{...options,signal:AbortSignal.timeout(18000),headers:{'User-Agent':'CinemaHallMetadata/0.1 (self-hosted; cache importer)','Accept':'application/json',...(options.headers||{})}});
 if(response.status===429)throw Error('Rate limited by provider; stop and retry later');
 if(!response.ok)throw Error('Upstream HTTP '+response.status);
 return response.json();
}
const cleanText=s=>typeof s==='string'?s.replace(/<[^>]*>/g,' ').replace(/\\s+/g,' ').trim().slice(0,10000):'';
const safeUrl=s=>typeof s==='string'&&s.startsWith('https://')?s:'';
async function save(row){
 const id=row.id,kind=row.kind,title=cleanText(row.title).slice(0,512);
 if(!id||!title)return false;
 const year=Number(row.year);
 const record=[id,kind,title,year>=1888&&year<=2100?year:null,cleanText(row.description),safeUrl(row.poster),safeUrl(row.backdrop),JSON.stringify(row.payload)];
 await pool.query(`INSERT INTO titles (id,kind,title,year,description,poster,backdrop,payload)
 VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),year=VALUES(year),
 description=VALUES(description),poster=VALUES(poster),backdrop=VALUES(backdrop),payload=VALUES(payload)`,record);
 return true;
}
let added=0;
try{
 if(source==='tvmaze'){
  // Official paginated show index, one call per page; avoid high request rates.
  const pages=Math.ceil(limit/20);
  for(let page=0;page<pages;page++){
   const shows=await fetchJson('https://api.tvmaze.com/shows?page='+page);
   if(!Array.isArray(shows))throw Error('Invalid TVmaze response');
   for(const show of shows.slice(0,limit-added)){
    if((show.genres||[]).some(g=>/аниме|\\banime\\b/i.test(g)))continue;
    const row={id:'ch:tvmaze:'+show.id,kind:'series',title:show.name,
      year:(show.premiered||'').slice(0,4),description:show.summary,
      poster:show.image?.original||show.image?.medium,backdrop:'',
      payload:{provider:'tvmaze',externalId:show.id,sourceUrl:show.url,
        genres:show.genres,language:show.language,network:show.network?.name,
        rating:show.rating?.average,officialSite:show.officialSite}};
    if(await save(row))added++;
   }
   if(added>=limit||shows.length===0)break;
   await sleep(1200);
  }
 }
 console.log(JSON.stringify({source,imported:added,limit}));
}finally{await pool.end();}
