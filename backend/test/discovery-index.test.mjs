import test from 'node:test';
import assert from 'node:assert/strict';
import {updateDiscoveryIndex} from '../discovery-index.mjs';
import {seasonInventory,personCredit} from '../metadata-library.mjs';
import {orderedWatchItems,watchOrdersFor} from '../watch-orders.mjs';
const row={id:'ch:movie:1',kind:'movie',title:'Fight Club',year:1999,payload:{ids:{kinopoisk:'361'},ratings:{imdb:8.8}}};
test('availability requires fresh live seeded sources and stores stable popularity and scores',async()=>{
 const writes=[];const pool={query:async(sql,args)=>writes.push({sql,args})};
 const query=async(kind,params)=>kind==='movie'?{response:{docs:[{id:361,name_eng:'Fight Club',year:1999,serial:false,popularity:123,rating_kinopoisk:8.7}]}}:{facet_counts:{facet_fields:{kinopoisk_id:['361',12]}}};
 const result=await updateDiscoveryIndex(pool,[row],{query,clock:()=>1000});
 assert.equal(result.available,1);assert.equal(writes[0].args[0],'361');assert.equal(writes[0].args[1],12);assert.equal(writes[0].args[4],123);assert.equal(writes[0].args[5],8.7);
 assert.ok(writes[0].args[3].getTime()>writes[0].args[2].getTime());
});
test('an upstream outage never overwrites the previous availability with an empty success',async()=>{
 const writes=[];const result=await updateDiscoveryIndex({query:async(...args)=>writes.push(args)},[row],{query:async()=>{throw Error('offline');}});
 assert.equal(result.failed,1);assert.equal(writes.length,0);
});
test('authoritative season counts ignore specials, malformed counts and duplicate seasons',()=>{
 assert.deepEqual(seasonInventory([{season_number:0,episode_count:3},{season_number:1,episode_count:10},{season_number:1,episode_count:12},{season_number:2,episode_count:-1}]),[{number:1,count:10,airAt:null}]);
 const first=personCredit({id:99,name_ru:'Актёр',profile_path:'/actor.jpg'});
 assert.equal(first.id,personCredit({id:99,name_en:'Actor'}).id);assert.ok(first.photo.includes('/w185/'));
 assert.equal(personCredit(first).id,first.id);
});
test('watch order follows the curated timeline, retaining positions when parts are missing',()=>{
 const order=watchOrdersFor(11).find(o=>o.id==='skywalker');
 const rows=[{id:'old',title:'Episode IV',payload:{ids:{tmdb:11}}},{id:'new',title:'Episode I',payload:{ids:{tmdb:1893}}}];
 const result=orderedWatchItems(order,rows,r=>({id:r.id,name:r.title}));
 assert.deepEqual(result.map(r=>r.id),['new','old']);assert.deepEqual(result.map(r=>r.watchOrderPosition),[1,4]);assert.equal(result.length,2);
});
