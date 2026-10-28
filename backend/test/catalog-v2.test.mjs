import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
const runFile=promisify(execFile);
import mariadb from 'mariadb';
import Ajv from 'ajv/dist/2020.js';
import { createCatalogV2 } from '../catalog-v2.mjs';
import { titleCard, titleDetail, releaseState, imageFor } from '../catalog-model.mjs';
import { canonicalId, saveExternalIds, recordRefresh, refreshDelayMs } from '../catalog-identity.mjs';
import { handleSearch } from '../search.mjs';

const contract=JSON.parse(await readFile(new URL('../catalog-v2.schema.json',import.meta.url),'utf8'));
const ajv=new Ajv({strict:true,allowUnionTypes:true});ajv.addSchema(contract);
function validate(name,value){const check=ajv.compile({$ref:contract.$id+'#/$defs/'+name});assert.ok(check(value),JSON.stringify(check.errors));}

test('DTOs strip internal metadata, unsafe assets and invalid dates',()=>{
  const row={id:'ch:42',kind:'movie',title:'Title',year:2000,poster:'javascript:alert(1)',payload:{ids:{tmdb:42,secret:'value'},curator:{password:'private'},apiKey:'private',releaseDate:'2000-02-31',ratings:{imdb:8.8}},updated_at:new Date('2026-01-01')};
  const value=titleDetail(row);validate('Title', {title:value});
  assert.equal(value.poster,null);assert.equal(value.releaseDate,null);
  assert.deepEqual(value.externalIds,{tmdb:'42'});
  assert.ok(!JSON.stringify(value).includes('private'));
  assert.equal(releaseState({status:'Released',releaseDate:'2030-01-01'},2030,new Date('2026-01-01')),'upcoming');
  assert.equal(releaseState({},2000,new Date('2026-01-01')),'unknown');
  validate('TitleCard',titleCard(row));
});
test('TV cards exclude heavy fields and use appropriately sized TMDB artwork',()=>{
  const row={id:'ch:42',kind:'movie',title:'Title',year:2000,description:'x'.repeat(100000),poster:'https://image.tmdb.org/t/p/original/poster.jpg',backdrop:'https://image.tmdb.org/t/p/original/wide.jpg',payload:{cast:Array(100).fill('Actor'),aliases:Array(100).fill('Alias')}};
  const card=titleCard(row);validate('TitleCard',card);
  assert.ok(Buffer.byteLength(JSON.stringify(card))<1024);
  assert.equal(card.poster,'https://image.tmdb.org/t/p/w342/poster.jpg');
  assert.equal(card.backdrop,'https://image.tmdb.org/t/p/w1280/wide.jpg');
  assert.equal(imageFor('https://example.org/original.jpg','poster'),'https://example.org/original.jpg');
});
test('completed metadata gets a finite refresh interval',()=>{
  assert.equal(refreshDelayMs({status:'Ended',description:'Full',images:{poster:'https://example.org/p.jpg'}}),30*24*3600000);
  assert.equal(refreshDelayMs({type:'tv',status:'Returning Series',description:'Full',images:{poster:'https://example.org/p.jpg'}}),6*3600000);
  assert.equal(refreshDelayMs({status:'Ended'}),24*3600000);
});

test('MariaDB catalogue integration',{skip:!process.env.CATALOG_TEST_PORT},async t=>{
  const dbName='cinemahome_test_'+randomUUID().replaceAll('-','');
  const connection={host:'127.0.0.1',port:Number(process.env.CATALOG_TEST_PORT),user:process.env.CATALOG_TEST_USER||'root',password:process.env.CATALOG_TEST_PASSWORD||'',multipleStatements:true,bigIntAsNumber:true,dateStrings:true,timezone:'Z'};
  const root=await mariadb.createConnection(connection);
  let pool,server;
  try{
    await root.query('CREATE DATABASE '+dbName+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
    pool=mariadb.createPool({...connection,database:dbName,connectionLimit:2});
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    const migration=await readFile(new URL('../migrations/005_catalog_identity_refresh.sql',import.meta.url),'utf8');
    await pool.query(migration);await pool.query(migration);
    const titles=[
      ['ch:snapshot:movie:tmdb:550','movie','Бойцовский клуб',1999,{titleEn:'Fight Club',originalTitle:'Fight Club',aliases:['Fight Club','Клуб бойцов'],ids:{tmdb:'550',kinopoisk:'361',imdb:'tt0137523'},status:'Released',genres:['Драма'],countries:['США'],curator:{note:'PRIVATE'},apiKey:'PRIVATE'}],
      ['ch:movie:percent','movie','100%_! тест',2000,{status:'Released',aliases:['Literal%_!']}],
      ['ch:movie:next','movie','Анонс',2043,{status:'Released',releaseDate:'2043-01-01'}],
      ['ch:movie:hidden','movie','Скрытый',2000,{status:'Released',publicationState:'hidden'}],
      ['ch:series:42','series','Сериал',2020,{status:'Ended'}],
      ['ch:movie:null','movie','Неизвестная дата',null,{}],
      ['ch:movie:animation','movie','Анимация',2021,{status:'Released',genres:['Мультфильм'],ratings:{kinopoisk:{value:8.4}}}],
      ['ch:movie:scifi','movie','Космос',2022,{status:'Released',genres:['Фантастика'],ratings:{kinopoisk:8.1}}],
      ['ch:movie:low','movie','Малая оценка',2023,{status:'Released',ratings:{kinopoisk:5.1}}],
    ];
    for(const [id,kind,title,year,payload]of titles)await pool.query('INSERT INTO titles(id,kind,title,year,poster,description,payload)VALUES(?,?,?,?,?,?,?)',[id,kind,title,year,'https://example.org/poster.jpg','Description',JSON.stringify(payload)]);
    for(const [sn,en,air]of [[0,1,'2020-01-01'],[1,1,'2020-01-01'],[1,3,'2020-01-08'],[2,1,'2043-01-01']])await pool.query('INSERT INTO episodes(title_id,season_number,episode_number,title,air_date)VALUES(?,?,?,?,?)',['ch:series:42',sn,en,'Episode '+en,air]);
    const handle=createCatalogV2(pool);
    server=http.createServer(async(req,res)=>{if(!await handle(req,res,new URL(req.url,'http://localhost'))){res.writeHead(404);res.end();}});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const base='http://127.0.0.1:'+server.address().port+'/api/v2';
    async function get(path,schema,status=200,method='GET'){
      const res=await fetch(base+path,{method});assert.equal(res.status,status,path);
      assert.ok(res.headers.get('x-request-id'));const body=method==='HEAD'?await res.text():await res.json();
      if(schema)validate(schema,body);return body;
    }
    await t.test('config and home publish only released nonhidden records',async()=>{
      await get('/config','Config');const home=await get('/home','Home');
      assert.deepEqual(home.rows.map(r=>r.id),['popular','series','new','new_series','top','top_series','animation','scifi']);
      const ids=home.rows.flatMap(r=>r.items.map(i=>i.id));
      assert.ok(ids.includes('ch:snapshot:movie:tmdb:550'));assert.ok(!ids.includes('ch:movie:next'));assert.ok(!ids.includes('ch:movie:hidden'));assert.ok(!ids.includes('ch:movie:null'));
      assert.deepEqual(home.rows.find(r=>r.id==='animation').items.map(i=>i.id),['ch:movie:animation']);
      assert.deepEqual(home.rows.find(r=>r.id==='scifi').items.map(i=>i.id),['ch:movie:scifi']);
      assert.deepEqual(home.rows.find(r=>r.id==='top').items.map(i=>i.id),['ch:movie:animation','ch:movie:scifi']);
    });
    await t.test('search aliases are case insensitive and wildcards are literal',async()=>{
      for(const q of ['fight club','FIGHT CLUB','клуб БОЙЦОВ']){const result=await get('/search?q='+encodeURIComponent(q),'Page');assert.deepEqual(result.items.map(r=>r.id),['ch:snapshot:movie:tmdb:550']);}
      const exact=await get('/search?q='+encodeURIComponent('100%_!'),'Page');assert.equal(exact.items.length,1);
      await handleSearch({},new URL('https://example.org/api/v1/search?q=fight%20club'),pool.query.bind(pool),(_r,status,value)=>{assert.equal(status,200);assert.equal(value.items.length,1);});
    });
    await t.test('card details omit internals and preserve canonical IDs',async()=>{
      const detail=await get('/titles/ch:snapshot:movie:tmdb:550','Title');assert.equal(detail.title.id,'ch:snapshot:movie:tmdb:550');assert.ok(!JSON.stringify(detail).includes('PRIVATE'));
      const local=await get('/titles/ch:snapshot:movie:tmdb:550/playback-options','Playback');assert.equal(local.strategy,'local');assert.equal(local.matchIdentity.externalIds.tmdb,'550');
      await get('/titles/ch:movie:hidden','Error',404);
      const discovered=createCatalogV2(pool,{sourceDiscovery:{enabled:true,load:async(id,ep)=>({status:'ready',candidates:[{hash:'',torrent_download_link:'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567',resolution:'1080p',name:'',translate_info:'',languages_parser:'',seeds:1,episodes_map_auto:'{}'}]})}});
      let status,body;await discovered({method:'GET'},{writeHead(s){status=s;},end(b){body=b;}},new URL('http://localhost/api/v2/titles/ch:snapshot:movie:tmdb:550/source-candidates?episode=S1E2'));
      assert.equal(status,200);validate('SourceDiscovery',JSON.parse(body));
      await get('/titles/ch:snapshot:movie:tmdb:550/source-candidates','Error',503);
    });
    await t.test('keyset pagination covers all records with no phantom page',async()=>{
      for(const sort of ['title','year']){
        let path='/discover?limit=2&sort='+sort,all=[];
        for(let i=0;i<10&&path;i++){const page=await get(path,'Page');all.push(...page.items.map(r=>r.id));path=page.nextCursor?'/discover?limit=2&sort='+sort+'&cursor='+page.nextCursor:null;}
        assert.equal(all.length,8);assert.equal(new Set(all).size,8);
      }
      const first=await get('/discover?limit=1','Page');await get('/discover?type=movie&cursor='+first.nextCursor,'Error',400);
      const home=await get('/rows/popular/items?limit=1','Page');assert.ok(home.nextCursor);await get('/rows/popular/items?limit=1&cursor='+home.nextCursor,'Page');
      const top=await get('/rows/top/items?limit=1','Page');assert.ok(top.nextCursor);
      const rest=await get('/rows/top/items?limit=1&cursor='+top.nextCursor,'Page');assert.equal(rest.items.length,1);assert.notEqual(rest.items[0].id,top.items[0].id);
    });
    await t.test('invalid filters, cursor and methods return typed errors',async()=>{
      for(const q of ['limit=0','limit=abc','limit=61','yearFrom=NaN','yearFrom=2026&yearTo=2000','sort=sql','extra=1','cursor=abc'])await get('/discover?'+q,'Error',400);
      await get('/search?q=a','Error',400);await get('/missing','Error',404);await get('/titles/no_such_title','Error',404);await get('/config','Error',405,'POST');
      assert.equal(await get('/config',null,200,'HEAD'),'');
    });
    await t.test('season zero, gaps and next season use actual episodes',async()=>{
      const seasons=await get('/titles/ch:series:42/seasons','Seasons');assert.deepEqual(seasons.items.map(s=>s.number),[0,1,2]);
      const first=await get('/seasons/ch:series:42:s1/episodes?limit=1','Episodes');assert.equal(first.nextAfter,1);
      const last=await get('/seasons/ch:series:42:s1/episodes?after=1&limit=1','Episodes');assert.equal(last.items[0].number,3);assert.equal(last.nextAfter,null);
      await get('/episodes/ch:series:42:1:1','EpisodeDetail');
      const next=await get('/episodes/ch:series:42:1:1/next','NextEpisode');assert.equal(next.episode.number,3);assert.equal(next.canAutoPlay,true);
      const future=await get('/episodes/ch:series:42:1:3/next','NextEpisode');assert.equal(future.episode.season,2);assert.equal(future.canAutoPlay,false);
      const special=await get('/episodes/ch:series:42:0:1/next','NextEpisode');assert.equal(special.episode,null);
      await get('/episodes/ch:series:42:1:2','Error',404);await get('/seasons/ch:series:42:s8/episodes','Error',404);
    });
    await t.test('new external IDs preserve legacy identity; conflicts rollback',async()=>{
      const db=await pool.getConnection();
      try{
        await db.beginTransaction();
        await saveExternalIds(db,{id:'ch:snapshot:movie:tmdb:550',kind:'movie',ids:{tmdb:'550',imdb:'tt0137523'}});
        const record={id:'ch:snapshot:movie:kinopoisk:361',kind:'movie',ids:{tmdb:'550',kinopoisk:'361'}};
        assert.equal(await canonicalId(db,record),'ch:snapshot:movie:tmdb:550');
        await saveExternalIds(db,{...record,id:await canonicalId(db,record)});
        await recordRefresh(db,'ch:snapshot:movie:tmdb:550',{status:'Released',description:'Full',images:{poster:'https://example.org/a.jpg'}});
        await db.commit();
        const state=await db.query('SELECT TIMESTAMPDIFF(DAY,last_success_at,next_refresh_at) AS days FROM title_refresh_state WHERE title_id=?',['ch:snapshot:movie:tmdb:550']);assert.equal(state[0].days,30);
        await db.beginTransaction();
        await assert.rejects(()=>saveExternalIds(db,{id:'ch:movie:percent',kind:'movie',ids:{tmdb:'550'}}),{code:'CATALOG_IDENTITY_CONFLICT'});await db.rollback();
        const identity=await db.query('SELECT title_id FROM title_external_ids WHERE provider=? AND kind=? AND external_id=?',['tmdb','movie','550']);assert.equal(identity[0].title_id,'ch:snapshot:movie:tmdb:550');
      }finally{db.release();}
    });
    await t.test('identity backfill CLI is idempotent and conflicts abort atomically',async()=>{
      const script=fileURLToPath(new URL('../backfill-catalog-identity.mjs',import.meta.url));
      const env={...process.env,DB_HOST:'127.0.0.1',DB_PORT:process.env.CATALOG_TEST_PORT,DB_USER:connection.user,DB_PASSWORD:connection.password,DB_NAME:dbName};
      for(let i=0;i<2;i++)await runFile(process.execPath,[script],{env,timeout:10000});
      await pool.query('INSERT INTO titles(id,kind,title,payload)VALUES(?,?,?,?)',['ch:movie:conflict','movie','Conflict',JSON.stringify({ids:{tmdb:'550'}})]);
      await assert.rejects(()=>runFile(process.execPath,[script],{env,timeout:10000}),error=>error.code===1&&error.stderr.includes('CATALOG_IDENTITY_CONFLICT'));
      const identity=await pool.query('SELECT title_id FROM title_external_ids WHERE provider=? AND kind=? AND external_id=?',['tmdb','movie','550']);assert.equal(identity[0].title_id,'ch:snapshot:movie:tmdb:550');
      await pool.query('DELETE FROM titles WHERE id=?',['ch:movie:conflict']);
    });
    await t.test('database failure is controlled and never returns private errors',async()=>{
      const failure=createCatalogV2({query:async()=>{throw Object.assign(new Error('PRIVATE'),{code:'TEST_DB_DOWN'});}});
      let status,bytes;await failure({method:'GET'},{writeHead(s){status=s;},end(b){bytes=b;}},new URL('http://localhost/api/v2/discover'));
      assert.equal(status,503);validate('Error',JSON.parse(bytes));assert.ok(!bytes.includes('PRIVATE'));
    });
  }finally{
    if(server)await new Promise(resolve=>server.close(resolve));
    if(pool)await pool.end();
    await root.query('DROP DATABASE IF EXISTS '+dbName);await root.end();
  }
});
