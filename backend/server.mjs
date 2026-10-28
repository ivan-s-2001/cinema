import http from 'node:http';
import mariadb from 'mariadb';
import { handleAdmin, startAdminScheduler } from './admin.mjs';
import { handleAccountRequest } from './accounts.mjs';

const pool=mariadb.createPool({
  host:'127.0.0.1', port:3306,
  user:process.env.DB_USER||'cinemahall',
  password:process.env.DB_PASSWORD,
  database:process.env.DB_NAME||'cinemahall',
  connectionLimit:2, connectTimeout:3000, acquireTimeout:4000,
  idleTimeout:30, bigIntAsNumber:true
});
const host='127.0.0.1',port=8091;
const responseCache=new Map();
const responseCacheTtl=10_000;
const responseCacheLimit=64;
function send(res,status,body){
  res.writeHead(status,{'content-type':'application/json; charset=utf-8',
    'cache-control':status===200?'private, max-age=15':'no-store',
    'x-content-type-options':'nosniff','content-length':Buffer.byteLength(body)});
  res.end(body);
}
function reply(res,status,data){
  const body=JSON.stringify(data);
  if(status===200&&res.cacheKey){
    responseCache.set(res.cacheKey,{expiresAt:Date.now()+responseCacheTtl,body});
    if(responseCache.size>responseCacheLimit)responseCache.delete(responseCache.keys().next().value);
  }
  send(res,status,body);
}
async function query(sql,params=[]){
 const conn=await pool.getConnection();
 try{return await conn.query(sql,params);}
 finally{conn.release();}
}
const allowedKind=new Set(['movie','series']);
const server=http.createServer(async(req,res)=>{
 try{
  if(await handleAdmin(req,res,pool))return;
  if(await handleAccountRequest(req,res,new URL(req.url,'http://localhost'),pool))return;
  if(req.method!=='GET'){reply(res,405,{error:'Method not allowed'});return;}
  const url=new URL(req.url,'http://localhost');
  const route=url.pathname;
  const cacheKey=route==='/api/v1/home'||route==='/api/v1/search'?url.pathname+url.search:null;
  if(cacheKey){
    const cached=responseCache.get(cacheKey);
    if(cached&&cached.expiresAt>Date.now()){send(res,200,cached.body);return;}
    if(cached)responseCache.delete(cacheKey);
    res.cacheKey=cacheKey;
  }
  if(route==='/health'){
    await query('SELECT 1 AS ok');
    reply(res,200,{status:'ok',service:'cinemahall-api',database:'connected'});
    return;
  }
  if(route==='/api/v1/home'){
    const [movies,series]=await Promise.all([
      query("SELECT id,kind,title,year,poster,backdrop FROM titles WHERE kind='movie' ORDER BY updated_at DESC LIMIT 24"),
      query("SELECT id,kind,title,year,poster,backdrop FROM titles WHERE kind='series' ORDER BY updated_at DESC LIMIT 24")
    ]);
    reply(res,200,{rows:[{id:'movies',name:'Фильмы',items:movies},{id:'series',name:'Сериалы',items:series}]});return;
  }
  if(route==='/api/v1/search'){
    const q=(url.searchParams.get('q')||'').trim().slice(0,120);
    if(q.length<2){reply(res,400,{error:'Search query must have at least 2 characters'});return;}
    const kind=url.searchParams.get('type');
    if(kind&&!allowedKind.has(kind)){reply(res,400,{error:'Invalid type'});return;}
    const escaped=q.replace(/[\\%_]/g,m=>'\\'+m);
    const rows=await query(
      'SELECT id,kind,title,year,poster,backdrop FROM titles WHERE title LIKE ?'+(kind?' AND kind = ?':'')+' ORDER BY year DESC LIMIT 40',
      kind?['%'+escaped+'%',kind]:['%'+escaped+'%']);
    reply(res,200,{items:rows});return;
  }
  const match=/^\/api\/v1\/titles\/([A-Za-z0-9:_-]{1,160})(\/seasons)?$/.exec(route);
  if(match){
    const id=match[1];
    if(match[2]){
      const episodes=await query('SELECT season_number AS season, episode_number AS episode, title, air_date AS airDate, still FROM episodes WHERE title_id=? ORDER BY season_number,episode_number LIMIT 2000',[id]);
      reply(res,200,{id,episodes});return;
    }
    const rows=await query('SELECT id,kind,title,year,description,poster,backdrop,payload FROM titles WHERE id=? LIMIT 1',[id]);
    if(!rows.length){reply(res,404,{error:'Not found'});return;}
    reply(res,200,{title:rows[0]});return;
  }
  reply(res,404,{error:'Not found'});
 }catch(err){
  console.error('API failure',err?.code||err?.name||'Error');
  reply(res,503,{error:'Service temporarily unavailable'});
 }
});
server.requestTimeout=10000;
server.headersTimeout=8000;
server.listen(port,host,()=>{
 console.log('CinemaHall API listening on '+host+':'+port);
 if(process.env.ADMIN_SESSION_SECRET&&process.env.ADMIN_PASSWORD_SCRYPT)startAdminScheduler(pool);
});
process.on('SIGTERM',()=>server.close(()=>pool.end()));
