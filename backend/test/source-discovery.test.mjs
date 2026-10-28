import test from 'node:test';
import assert from 'node:assert/strict';
import {createSourceDiscovery,matchKinopoisk} from '../source-discovery.mjs';
function response(docs){const bytes=Buffer.from(JSON.stringify({response:{docs}}));return {ok:true,headers:{get:()=>String(bytes.length)},arrayBuffer:async()=>bytes};}
const link='magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567';
test('server fetches by precise ID, coalesces requests and returns bounded safe candidates',async()=>{
 let calls=0,now=0;
 const api=createSourceDiscovery({enabled:true,clock:()=>now,fetcher:async url=>{calls++;assert.equal(url.searchParams.get('q'),'kinopoisk_id:361 AND playable:true AND trailer:false');return response([
  {hash:'0123456789abcdef0123456789abcdef01234567',torrent_download_link:link,resolution:'1080p',translate_info:'Дубляж',seeds:123},
  {torrent_download_link:link},
  {torrent_download_link:'file:///etc/passwd'},
  {torrent_download_link:'http://localhost/secret'}
 ]);}});
 const [one,two]=await Promise.all([api.load('361'),api.load('361')]);
 assert.equal(calls,1);assert.deepEqual(one,two);assert.equal(one.candidates.length,1);
 assert.equal(one.candidates[0].translate_info,'Дубляж');assert.ok(!JSON.stringify(one).includes('localhost'));
 await api.load('361');assert.equal(calls,1);now=180001;await api.load('361');assert.equal(calls,2);
});
test('episode query falls back to season pack and blocks unsafe IDs',async()=>{
 let calls=0;const api=createSourceDiscovery({enabled:true,fetcher:async url=>{calls++;return response(url.searchParams.get('q').includes('episodes:')?[]:[{torrent_download_link:link}]);}});
 const result=await api.load('361','S1E2');assert.equal(result.candidates.length,1);assert.equal(calls,2);
 await assert.rejects(()=>api.load('361 OR *:*'),{status:400});
 await assert.rejects(()=>api.load('361','S1E2 OR *:*'),{status:400});
});
test('upstream errors do not become success or cached empty answers',async()=>{
 let calls=0;const api=createSourceDiscovery({enabled:true,fetcher:async()=>{calls++;throw Error('temporary');}});
 await assert.rejects(()=>api.load('361'));
 await assert.rejects(()=>api.load('361'));
 assert.equal(calls,2);
 const disabled=createSourceDiscovery({enabled:false,fetcher:()=>assert.fail('network called')});
 assert.deepEqual(await disabled.load('361'),{status:'disabled',candidates:[]});
});
test('missing Kinopoisk ID resolves by exact title, year and type before loading sources',async()=>{
 let calls=0;
 const api=createSourceDiscovery({enabled:true,fetcher:async url=>{calls++;
  if(url.pathname.includes('/movie/'))return response([{id:361,name_eng:'Fight Club',year:1999,serial:false}]);
  assert.ok(url.searchParams.get('q').startsWith('kinopoisk_id:361 '));return response([{torrent_download_link:link}]);
 }});
 const title={id:'ch:snapshot:movie:tmdb:550',type:'movie',title:'Бойцовский клуб',originalTitle:'Fight Club',year:1999,externalIds:{tmdb:'550'},aliases:[]};
 assert.equal((await api.loadForTitle(title)).candidates.length,1);assert.equal(calls,2);
 await api.loadForTitle(title);assert.equal(calls,2);
 assert.equal(matchKinopoisk(title,[{id:1,name_eng:'Fight Club',year:1980,serial:false}]),null);
 assert.equal(matchKinopoisk(title,[{id:1,name_eng:'Fight Club',year:1999,serial:true}]),null);
 assert.equal(matchKinopoisk(title,[{id:1,name_eng:'Fight Club',year:1999,serial:false},{id:2,name_eng:'Fight Club',year:1999,serial:false}]),null);
 assert.equal(matchKinopoisk(title,[{id:361,name_rus:'Бойцовский клуб',name_eng:'Fight Club',year:1999,serial:false},{id:5139541,name_original:'Fight Club',year:1999,serial:false}]),'361');
});
