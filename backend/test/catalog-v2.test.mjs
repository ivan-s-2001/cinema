import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile,mkdtemp,mkdir,writeFile,rm } from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { execFile,spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
const runFile=promisify(execFile);
import mariadb from 'mariadb';
import Ajv from 'ajv/dist/2020.js';
import { createCatalogV2 } from '../catalog-v2.mjs';
import { titleCard, titleDetail, releaseState, imageFor } from '../catalog-model.mjs';
import { canonicalId, saveExternalIds, recordRefresh, refreshDelayMs } from '../catalog-identity.mjs';
import { handleSearch } from '../search.mjs';
import { tvMetadata } from '../tv-meta.mjs';
import {createMetadataLibrary} from '../metadata-library.mjs';
import {collections} from '../collections.mjs';
import {identityReport} from '../identity-report.mjs';

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
  assert.equal(card.poster,'https://cinemahome.duckdns.org/api/v2/images/tmdb/w342/poster.jpg');
  assert.equal(card.backdrop,'https://cinemahome.duckdns.org/api/v2/images/tmdb/w1280/wide.jpg');
  assert.equal(card.description.length,400);
  assert.equal(imageFor('https://example.org/original.jpg','poster'),'https://example.org/original.jpg');
});
test('snapshot actor image and profile paths are returned as server-hosted portraits',()=>{
  const value=titleDetail({id:'ch:actor-test',kind:'movie',title:'Title',payload:{details:{cast:[
    {name:'First',image:'https://image.tmdb.org/t/p/original/actor.jpg'},
    {name:'Second',profile_path:'/second.png'},
    {name:'Third',photo:'javascript:bad'}]}}});
  assert.equal(value.cast[0].photo,'https://cinemahome.duckdns.org/api/v2/images/tmdb/w185/actor.jpg');
  assert.equal(value.cast[1].photo,'https://cinemahome.duckdns.org/api/v2/images/tmdb/w185/second.png');
  assert.equal(value.cast[2].photo,null);
});
test('completed metadata gets a finite refresh interval',()=>{
  assert.equal(refreshDelayMs({status:'Ended',description:'Full',images:{poster:'https://example.org/p.jpg'}}),30*24*3600000);
  assert.equal(refreshDelayMs({type:'tv',status:'Returning Series',description:'Full',images:{poster:'https://example.org/p.jpg'}}),6*3600000);
  assert.equal(refreshDelayMs({status:'Ended'}),24*3600000);
});
test('server TV metadata preserves ratings, episode identity and timestamps without private payload',()=>{
  const result=tvMetadata({id:'ch:series:test',kind:'series',title:'Show',year:2020,payload:{releaseDate:'2020-01-01T12:00:00.000Z',ratings:{imdb:8.3},cast:[{name:'Actor',photo:'https://image.tmdb.org/t/p/original/actor.jpg'}],apiKey:'PRIVATE'}},[
    {title_id:'ch:series:test',season_number:0,episode_number:1,title:'Special'},
    {title_id:'ch:series:test',season_number:1,episode_number:3,title:'Episode',air_date:'2020-01-01',still:'javascript:bad'}]);
  validate('TvMetadata',result);
  assert.equal(result.meta.released,'2020-01-01T12:00:00.000Z');
  assert.equal(result.meta.imdbRating,'8.3');assert.equal(result.meta.cinemaRatings[0].label,'IMDb');
  assert.deepEqual(result.meta.cast,['Actor']);assert.equal(result.meta.app_extras.cast[0].photo,'https://cinemahome.duckdns.org/api/v2/images/tmdb/w185/actor.jpg');
  assert.deepEqual(result.meta.videos,[{id:'ch:series:test:1:3',season:1,episode:3,title:'Episode',released:'2020-01-01T00:00:00.000Z'}]);
  assert.ok(!JSON.stringify(result).includes('PRIVATE'));
});
test('oversized TV episode lists fall back explicitly instead of returning incomplete metadata',async()=>{
  const row={id:'ch:series:large',kind:'series',title:'Large',payload:{}};
  const handle=createCatalogV2({query:async(sql)=>sql.includes('FROM titles')?[row]:Array(10001).fill({})});
  let status,body;
  await handle({method:'GET'},{writeHead(s){status=s;},end(b){body=b;}},new URL('https://example.org/api/v2/titles/ch:series:large/tv-meta'));
  assert.equal(status,503);const value=JSON.parse(body);validate('Error',value);assert.equal(value.error.code,'tv_metadata_too_large');
});
test('identity report includes conflicts in payloads and existing mappings without exposing internals',()=>{
  const rows=[{id:'one',kind:'movie',title:'Title',payload:{ids:{tmdb:1},secret:'PRIVATE'}},{id:'two',kind:'movie',payload:{ids:{tmdb:1}}},{id:'series',kind:'series',payload:{ids:{tmdb:1}}}];
  const report=identityReport(rows,[{provider:'imdb',kind:'movie',external_id:'tt42',title_id:'one'},{provider:'imdb',kind:'movie',external_id:'tt42',title_id:'two'}]);
  assert.equal(report.conflictCount,2);assert.equal(report.conflicts[0].titles.length,2);
  assert.ok(!JSON.stringify(report).includes('PRIVATE'));
  assert.equal(identityReport(rows,[],0).truncated,true);
});
test('concurrent cold home requests share one connection and release it after success or failure',async()=>{
  let acquisitions=0,releases=0,queries=0,active=0,peak=0,fail=false;
  const pool={getConnection:async()=>{
    acquisitions++;
    return {release(){releases++;},async query(){
      queries++;active++;peak=Math.max(peak,active);
      try{await new Promise(resolve=>setTimeout(resolve,2));if(fail)throw Object.assign(new Error('busy'),{code:'TEST_TIMEOUT'});return [];}
      finally{active--;}
    }};
  }};
  async function batch(handle){
    return Promise.all(Array.from({length:12},async()=>{
      let status,body;await handle({method:'GET'},{writeHead(s){status=s;},end(b){body=JSON.parse(b);}},new URL('http://localhost/api/v2/home'));
      return {status,body};
    }));
  }
  const success=await batch(createCatalogV2(pool));
  for(const response of success){assert.equal(response.status,200);validate('Home',response.body);}
  assert.deepEqual({acquisitions,releases,queries,peak},{acquisitions:1,releases:1,queries:collections.filter(r=>r.home).length,peak:1});
  const recovering=createCatalogV2(pool);fail=true;
  for(const response of await batch(recovering))assert.equal(response.status,503);
  assert.equal(releases,2);fail=false;
  for(const response of await batch(recovering))assert.equal(response.status,200);
  assert.equal(acquisitions,3);assert.equal(releases,3);assert.equal(peak,1);
});

test('MariaDB catalogue integration',{skip:!process.env.CATALOG_TEST_PORT},async t=>{
  const dbName='cinemahome_test_'+randomUUID().replaceAll('-','');
  const connection={host:'127.0.0.1',port:Number(process.env.CATALOG_TEST_PORT),user:process.env.CATALOG_TEST_USER||'root',password:process.env.CATALOG_TEST_PASSWORD||'',multipleStatements:true,bigIntAsNumber:true,dateStrings:true,timezone:'Z'};
  const root=await mariadb.createConnection(connection);
  let pool,server;
  try{
    await root.query('CREATE DATABASE '+dbName+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
    pool=mariadb.createPool({...connection,database:dbName,connectionLimit:1});
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    const migration=await readFile(new URL('../migrations/005_catalog_identity_refresh.sql',import.meta.url),'utf8');
    await pool.query(migration);await pool.query(migration);
    await pool.query(await readFile(new URL('../migrations/006_discovery_people.sql',import.meta.url),'utf8'));
    const titles=[
      ['ch:snapshot:movie:tmdb:550','movie','Бойцовский клуб',1999,{titleEn:'Fight Club',originalTitle:'Fight Club',aliases:['Fight Club','Клуб бойцов'],ids:{tmdb:'550',kinopoisk:'361',imdb:'tt0137523'},ratings:{imdb:8.8},status:'Released',genres:['Драма'],countries:['США'],curator:{note:'PRIVATE'},apiKey:'PRIVATE'}],
      ['ch:movie:percent','movie','100%_! тест',2000,{status:'Released',aliases:['Literal%_!']}],
      ['ch:movie:next','movie','Анонс',2043,{status:'Released',releaseDate:'2043-01-01'}],
      ['ch:movie:hidden','movie','Скрытый',2000,{status:'Released',publicationState:'hidden'}],
      ['ch:series:42','series','Сериал',2020,{status:'Ended',ids:{tmdb:'42'}}],
      ['ch:movie:null','movie','Неизвестная дата',null,{}],
      ['ch:movie:animation','movie','Анимация',2021,{status:'Released',genres:['Мультфильм'],ratings:{kinopoisk:{value:8.4}}}],
      ['ch:movie:scifi','movie','Космос',2022,{status:'Released',genres:['Фантастика'],ratings:{kinopoisk:8.1}}],
      ['ch:movie:low','movie','Малая оценка',2023,{status:'Released',ratings:{kinopoisk:5.1}}],
    ];
    for(const [id,kind,title,year,payload]of titles){
      const rating=Number(payload.ratings?.kinopoisk?.value||payload.ratings?.kinopoisk||payload.ratings?.imdb||7);
      await pool.query('INSERT INTO titles(id,kind,title,year,poster,description,payload,source_count,source_checked_at,source_expires_at,catalog_rating,catalog_popularity)VALUES(?,?,?,?,?,?,?,1,UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 6 HOUR),?,100)',[id,kind,title,year,'https://example.org/poster.jpg','Description',JSON.stringify(payload),rating]);
    }
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
      await get('/config','Config');const [home]=await Promise.all(Array.from({length:8},()=>get('/home','Home')));
      assert.equal((await pool.query('SELECT 1 AS ok'))[0].ok,1);
      assert.deepEqual(home.rows.map(r=>r.id),collections.filter(r=>r.home).map(r=>r.id));
      const ids=home.rows.flatMap(r=>r.items.map(i=>i.id));
      assert.ok(ids.includes('ch:snapshot:movie:tmdb:550'));assert.ok(!ids.includes('ch:movie:next'));assert.ok(!ids.includes('ch:movie:hidden'));assert.ok(!ids.includes('ch:movie:null'));
      assert.deepEqual(home.rows.find(r=>r.id==='animation').items.map(i=>i.id),['ch:movie:animation']);
      assert.deepEqual(home.rows.find(r=>r.id==='scifi').items.map(i=>i.id),['ch:movie:scifi']);
      assert.deepEqual(home.rows.find(r=>r.id==='top').items.map(i=>i.id),['ch:snapshot:movie:tmdb:550','ch:movie:animation','ch:movie:scifi']);
    });
    await t.test('title image fallback has SVG content type, conditional caching and hidden-title protection',async()=>{
      const url=base+'/titles/ch:snapshot:movie:tmdb:550/logo.svg';
      const response=await fetch(url);assert.equal(response.status,200);assert.ok(response.headers.get('content-type').startsWith('image/svg+xml'));
      const svg=await response.text();assert.ok(svg.includes('<svg'));assert.ok(svg.includes('Бойцовский'));
      const same=await fetch(url,{headers:{'If-None-Match':response.headers.get('etag')}});assert.equal(same.status,304);assert.equal(await same.text(),'');
      const head=await fetch(url,{method:'HEAD'});assert.equal(head.status,200);assert.equal(await head.text(),'');
      await get('/titles/ch:movie:hidden/logo.svg','Error',404);
    });
    await t.test('navigator facets come from watchable records and filters search the entire catalogue',async()=>{
      await pool.query("UPDATE titles SET payload=JSON_SET(payload,'$.countries',JSON_ARRAY('США'),'$.runtimeMinutes',85) WHERE id='ch:movie:animation'");
      await pool.query("UPDATE titles SET payload=JSON_SET(payload,'$.countries',JSON_ARRAY('HIDDEN COUNTRY')) WHERE id='ch:movie:hidden'");
      const facets=await get('/facets?type=movie','Facets');
      assert.ok(facets.genres.includes('Мультфильм'));assert.ok(facets.countries.includes('США'));assert.ok(!facets.countries.includes('HIDDEN COUNTRY'));
      assert.ok(facets.years.includes(2021));assert.ok(!facets.years.includes(2043));
      const result=await get('/discover?q='+encodeURIComponent('Анимация')+'&genre='+encodeURIComponent('мультфильм')+'&country='+encodeURIComponent('сша')+'&yearFrom=2021&yearTo=2021','Page');
      assert.deepEqual(result.items.map(r=>r.id),['ch:movie:animation']);
      assert.equal((await get('/discover?q='+encodeURIComponent('Анимация')+'&yearFrom=2000&yearTo=2001','Page')).items.length,0);
      assert.equal((await get('/rows/short_evening/items','Page')).items[0].id,'ch:movie:animation');
      await get('/facets?type=bad','Error',400);
    });
    await t.test('metadata hydration persists cast, filmography and complete season numbering',async()=>{
      const provider={enabled:true,async get(path){
        if(path.includes('credits'))return {data:[{id:99,name_ru:'Актёр',profession:'actor',profile_path:'/actor.jpg',character_ru:'Герой'}]};
        if(path.endsWith('/seasons'))return {data:[{season_number:1,episode_count:3},{season_number:2,episode_count:2}]};
        return {data:[{episode_number:1,name:'Episode name',still_path:'/episode.jpg',air_date:'2020-01-01',overview:'Episode story'}]};
      }};
      const library=createMetadataLibrary(pool,{provider});
      const movie=(await pool.query('SELECT * FROM titles WHERE id=?',['ch:snapshot:movie:tmdb:550']))[0];
      await pool.query("INSERT INTO titles(id,kind,title,year,payload,source_count,source_expires_at,catalog_rating)VALUES('ch:series:hydration','series','Hydrated',2020,?,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 6 HOUR),7)",[JSON.stringify({status:'Ended',ids:{tmdb:'43'}})]);
      const series=(await pool.query('SELECT * FROM titles WHERE id=?',['ch:series:hydration']))[0];
      await Promise.all([library.ensure(movie),library.ensure(movie)]);await library.ensure(series);
      const firstArtwork=await pool.query('SELECT still,title FROM episodes WHERE title_id=? AND season_number=1 AND episode_number=1',[series.id]);
      assert.ok(firstArtwork[0].still.includes('/episode.jpg'));assert.equal(firstArtwork[0].title,'Episode name');
      await library.idle();
      await pool.query("UPDATE episodes SET payload=JSON_SET(COALESCE(payload,JSON_OBJECT()),'$.seasonAirAt','2030-01-01') WHERE title_id=? AND season_number=1 AND episode_number=1",[series.id]);
      const incomplete=createMetadataLibrary(pool,{provider:{enabled:true,async get(path){
        if(path.includes('credits'))return {data:[]};
        if(path.endsWith('/seasons'))return {data:[{season_number:1,episode_count:3},{season_number:2,episode_count:2}]};
        return {data:[{episode_number:1}]};
      }}});
      const refreshed=(await pool.query('SELECT * FROM titles WHERE id=?',[series.id]))[0];
      const payload=typeof refreshed.payload==='string'?JSON.parse(refreshed.payload):refreshed.payload;
      delete payload.episodesDetailsCheckedAt;delete payload.episodesArtworkCheckedAt;
      await incomplete.ensure({...refreshed,payload});await incomplete.idle();await incomplete.close();
      const preserved=(await pool.query('SELECT title,still,payload FROM episodes WHERE title_id=? AND season_number=1 AND episode_number=1',[series.id]))[0];
      assert.equal(preserved.title,'Episode name');assert.ok(preserved.still.includes('/episode.jpg'));
      const details=typeof preserved.payload==='string'?JSON.parse(preserved.payload):preserved.payload;
      assert.equal(details.description,'Episode story');assert.equal(details.seasonAirAt,'2030-01-01');
      const person=(await pool.query('SELECT * FROM people'))[0];assert.ok(person.id.startsWith('person:'));assert.equal(person.name,'Актёр');
      const cast=await pool.query('SELECT title_id FROM title_cast WHERE person_id=?',[person.id]);assert.equal(cast.length,2);
      const inventory=await pool.query('SELECT season_number,episode_number FROM episodes WHERE title_id=? AND season_number>0 ORDER BY season_number,episode_number',['ch:series:hydration']);
      assert.equal(inventory.length,5);assert.deepEqual(inventory.map(r=>[r.season_number,r.episode_number]),[[1,1],[1,2],[1,3],[2,1],[2,2]]);
      const detail=await get('/titles/ch:snapshot:movie:tmdb:550/tv-meta','TvMetadata');
      assert.equal(detail.meta.app_extras.cast[0].personId,person.id);
      const filmography=await get('/people/'+person.id+'/filmography?sort=year');assert.equal(filmography.items.length,2);
      await library.close();
    });
    await t.test('unknown, expired and unavailable titles never enter browse or search',async()=>{
      for(const [id,count,expiry]of [['ch:no-sources',0,'2099-01-01'],['ch:expired',8,'2000-01-01']])await pool.query("INSERT INTO titles(id,kind,title,year,poster,payload,source_count,source_expires_at,catalog_rating)VALUES(?,'movie',?,2020,'https://example.org/p.jpg',?, ?,?,9)",[id,'Невидимый '+id,JSON.stringify({status:'Released'}),count,expiry]);
      const result=await get('/search?q='+encodeURIComponent('Невидимый'),'Page');assert.equal(result.items.length,0);
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
        assert.equal(all.length,7);assert.equal(new Set(all).size,7);
      }
      const first=await get('/discover?limit=1','Page');await get('/discover?type=movie&cursor='+first.nextCursor,'Error',400);
      const home=await get('/rows/popular/items?limit=1','Page');assert.ok(home.nextCursor);await get('/rows/popular/items?limit=1&cursor='+home.nextCursor,'Page');
      const top=await get('/rows/top/items?limit=1','Page');assert.ok(top.nextCursor);
      const rest=await get('/rows/top/items?limit=1&cursor='+top.nextCursor,'Page');assert.equal(rest.items.length,1);assert.notEqual(rest.items[0].id,top.items[0].id);
    });
    await t.test('watch order combines related series by release date, keeping unrelated numeric movie IDs out',async()=>{
      for(const [id,kind,tmdb,year]of [['ch:game','series',1399,2011],['ch:house','series',94997,2022],['ch:wrong-cycle','movie',1399,2010]]){
        await pool.query("INSERT INTO titles(id,kind,title,year,payload,source_count,source_expires_at,catalog_rating)VALUES(?,?,?,?,?,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 6 HOUR),8)",[id,kind,id,year,JSON.stringify({status:'Released',ids:{tmdb}})]);
      }
      try{
        const a=await get('/titles/ch:house/watch-orders'),b=await get('/titles/ch:game/watch-orders');
        assert.deepEqual(a.items[0].items.map(i=>i.id),['ch:game','ch:house']);
        assert.deepEqual(b.items[0].items.map(i=>i.id),['ch:game','ch:house']);
        assert.equal(a.items[0].order,'Порядок выхода сериалов');
        assert.equal((await get('/titles/ch:wrong-cycle/watch-orders')).items.length,0);
      }finally{await pool.query("DELETE FROM titles WHERE id IN ('ch:game','ch:house','ch:wrong-cycle')");}
    });
    await t.test('invalid filters, cursor and methods return typed errors',async()=>{
      for(const q of ['limit=0','limit=abc','limit=61','yearFrom=NaN','yearFrom=2026&yearTo=2000','sort=sql','extra=1','cursor=abc'])await get('/discover?'+q,'Error',400);
      await get('/search?q=a','Error',400);await get('/missing','Error',404);await get('/titles/no_such_title','Error',404);await get('/config','Error',405,'POST');
      assert.equal(await get('/config',null,200,'HEAD'),'');
    });
    await t.test('season zero, gaps and next season use actual episodes',async()=>{
      const ready=await get('/titles/ch:series:42/tv-meta');
      validate('TvMetadata',ready);
      assert.deepEqual(ready.meta.videos.map(v=>v.id),['ch:series:42:1:1','ch:series:42:1:3','ch:series:42:2:1']);
      assert.equal(ready.meta.videos[0].released,'2020-01-01T00:00:00.000Z');
      await get('/titles/ch:movie:hidden/tv-meta','Error',404);
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
      const before=await pool.query('SELECT COUNT(*) AS n FROM title_external_ids');
      const check=await runFile(process.execPath,[script,'--check'],{env,timeout:10000});
      const report=JSON.parse(check.stdout);assert.equal(report.conflictCount,1);
      assert.deepEqual(report.conflicts[0].titles.map(row=>row.id).sort(),['ch:movie:conflict','ch:snapshot:movie:tmdb:550']);
      assert.equal((await pool.query('SELECT COUNT(*) AS n FROM title_external_ids'))[0].n,before[0].n);
      await assert.rejects(()=>runFile(process.execPath,[script],{env,timeout:10000}),error=>error.code===1&&error.stderr.includes('CATALOG_IDENTITY_CONFLICT'));
      const identity=await pool.query('SELECT title_id FROM title_external_ids WHERE provider=? AND kind=? AND external_id=?',['tmdb','movie','550']);assert.equal(identity[0].title_id,'ch:snapshot:movie:tmdb:550');
      await pool.query('DELETE FROM titles WHERE id=?',['ch:movie:conflict']);
    });
    await t.test('snapshot import preserves canonical titles and imports episode dates idempotently',async()=>{
      const directory=await mkdtemp(join(tmpdir(),'cinema-import-'));
      try{
        await mkdir(join(directory,'shards'));
        const rows=[{type:'movie',ids:{tmdb:'550',kinopoisk:'361'},title:'Must not overwrite'},
          {type:'tv',ids:{tmdb:'999'},title:'New series',details:{seasons:[{number:1,episodes:[{number:2,name:'Second',date:'2020-01-02',overview:'Story',still:'https://example.org/still.jpg'}]}]}},
          {type:'tv',ids:{tmdb:'999'},title:'Ambiguous',collisionSuffix:'other'}];
        await writeFile(join(directory,'index.json'),JSON.stringify({count:3,shards:[{path:'shards/tv-0.json'}]}));
        await writeFile(join(directory,'shards/tv-0.json'),JSON.stringify(rows));
        const env={...process.env,DB_HOST:'127.0.0.1',DB_PORT:process.env.CATALOG_TEST_PORT,DB_USER:connection.user,DB_PASSWORD:connection.password,DB_NAME:dbName};
        const script=fileURLToPath(new URL('../import-snapshot.mjs',import.meta.url));
        for(let i=0;i<2;i++)await runFile(process.execPath,[script,directory],{env,timeout:10000});
        const old=await pool.query('SELECT title FROM titles WHERE id=?',['ch:snapshot:movie:tmdb:550']);assert.equal(old[0].title,'Бойцовский клуб');
        const episodes=await pool.query('SELECT air_date,payload FROM episodes WHERE title_id=?',['ch:snapshot:tv:tmdb:999']);assert.equal(episodes.length,1);assert.equal(episodes[0].air_date,'2020-01-02');
        const identity=await pool.query('SELECT title_id FROM title_external_ids WHERE provider=? AND kind=? AND external_id=?',['tmdb','series','999']);assert.equal(identity[0].title_id,'ch:snapshot:tv:tmdb:999');
        await pool.query('DELETE FROM titles WHERE id=?',['ch:snapshot:tv:tmdb:999']);
      }finally{await rm(directory,{recursive:true,force:true});}
    });
    await t.test('standalone staged server uses configured database and port for concurrent home requests',async()=>{
      const reservation=http.createServer();await new Promise(resolve=>reservation.listen(0,'127.0.0.1',resolve));
      const port=reservation.address().port;await new Promise(resolve=>reservation.close(resolve));
      const env={...process.env,DB_HOST:'127.0.0.1',DB_PORT:String(connection.port),DB_USER:connection.user,DB_PASSWORD:connection.password,DB_NAME:dbName,CINEMA_API_PORT:String(port),CINEMA_DISABLE_SCHEDULERS:'1'};
      const child=spawn(process.execPath,[fileURLToPath(new URL('../server.mjs',import.meta.url))],{env,stdio:['ignore','pipe','pipe']});
      let logs='';child.stdout.on('data',data=>{logs+=data;});child.stderr.on('data',data=>{logs+=data;});
      try{
        let ready=false;
        for(let attempt=0;attempt<100;attempt++){
          if(child.exitCode!==null)throw Error('Staged server exited: '+logs);
          try{const response=await fetch('http://127.0.0.1:'+port+'/api/v2/config',{signal:AbortSignal.timeout(100)});if(response.ok){await response.json();ready=true;break;}}catch{}
          await new Promise(resolve=>setTimeout(resolve,50));
        }
        assert.ok(ready,logs);
        const health=await fetch('http://127.0.0.1:'+port+'/health');assert.equal(health.status,200);assert.equal((await health.json()).database,'connected');
        await Promise.all(Array.from({length:10},async()=>{const response=await fetch('http://127.0.0.1:'+port+'/api/v2/home');assert.equal(response.status,200,logs);validate('Home',await response.json());}));
      }finally{
        if(child.exitCode===null&&child.signalCode===null)await new Promise(resolve=>{child.once('exit',resolve);child.kill('SIGTERM');});
      }
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
