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
 assert.deepEqual(result.map(r=>r.id),['old','new']);assert.deepEqual(result.map(r=>r.watchOrderPosition),[1,4]);assert.equal(result.length,2);
});

test('long localized titles are split into bounded discovery queries',async()=>{
 const rows=Array.from({length:8},(_,i)=>({...row,id:'ch:long:'+i,title:'Ж'.repeat(90)+i,payload:{originalTitle:'Щ'.repeat(90)+i,ids:{}}}));
 let requests=0;
 await updateDiscoveryIndex({query:async()=>{}},rows,{query:async(kind,params)=>{requests++;assert.ok(new URLSearchParams({q:params.q}).toString().length<6000);return {response:{docs:[]}};}});
 assert.ok(requests>1);
});

test('release order joins movie and TV namespaces without cross-matching numeric IDs',()=>{
 const order=watchOrdersFor(94997,'series').find(o=>o.id==='westeros');assert.ok(order);
 assert.equal(watchOrdersFor(94997,'movie').some(o=>o.id==='westeros'),false);
 const rows=[{id:'house',kind:'series',title:'House',payload:{ids:{tmdb:94997}}},{id:'wrong',kind:'movie',title:'Other movie',payload:{ids:{tmdb:1399}}},{id:'game',kind:'series',title:'Game',payload:{ids:{tmdb:1399}}}];
 assert.deepEqual(orderedWatchItems(order,rows,r=>({id:r.id})).map(r=>r.id),['game','house']);
 const marvel=watchOrdersFor(24428).find(o=>o.id==='infinity-saga');assert.equal(marvel.tmdb[0],1726);assert.equal(marvel.order,'Порядок выхода');
 const minions=watchOrdersFor(1315772).find(o=>o.id==='despicable-me');assert.equal(minions.tmdb[0],20352);
});

test('narrative cycles distinguish prequels, sequels and spin-off branches without reordering release positions',async()=>{
 const {availableConnections}=await import('../watch-orders.mjs');
 const order=watchOrdersFor(585).find(o=>o.id==='monsters');
 const rows=[{id:'inc',kind:'movie',title:'Корпорация монстров',payload:{ids:{tmdb:585}}},{id:'university',kind:'movie',title:'Университет монстров',payload:{ids:{tmdb:62211}}}];
 const items=orderedWatchItems(order,rows,r=>({id:r.id,releaseInfo:r.id==='inc'?'2001':'2013'}));
 assert.deepEqual(items.map(i=>i.id),['inc','university']);
 assert.deepEqual(items.map(i=>i.cycleRoles),[['original'],['prequel']]);
 assert.equal(items[1].releaseInfo,'2013 · Приквел');
 assert.deepEqual(availableConnections(order,rows).map(r=>[r.fromTitleId,r.toTitleId,r.type]),[['university','inc','prequel']]);
 assert.deepEqual(availableConnections(order,rows.slice(0,1)),[]);
 const shrek=watchOrdersFor(315162).find(o=>o.id==='shrek');
 const wish=shrek.parts.find(p=>p.id===315162);assert.deepEqual(wish.roles,['spin_off']);
 assert.ok(shrek.connections.some(c=>c.from.id===315162&&c.to.id===417859&&c.type==='sequel'));
});

test('an Avengers sequel cycle is separate from the shared Marvel universe',()=>{
 const groups=watchOrdersFor(299534);
 assert.equal(groups[0].id,'avengers');assert.equal(groups[0].scope,'narrative');
 assert.deepEqual(groups[0].parts.map(p=>p.id),[24428,99861,299536,299534]);
 assert.equal(groups.find(o=>o.id==='infinity-saga').scope,'universe');
 assert.equal(groups.find(o=>o.id==='infinity-saga').connections.length,0);
 assert.deepEqual(watchOrdersFor(299537).find(o=>o.id==='captain-marvel').parts.map(p=>p.id),[299537,609681]);
});

test('every declared relation has two distinct members in the same cycle and keeps movie/TV identity separate',async()=>{
 const {watchOrders}=await import('../watch-orders.mjs');
 for(const order of watchOrders){
  const keys=order.parts.map(p=>p.type+':'+p.id),members=new Set(keys);assert.equal(members.size,keys.length,order.id);
  for(const part of order.parts)assert.ok(part.roles.length>0,order.id);
  for(const link of order.connections){
   const from=link.from.type+':'+link.from.id,to=link.to.type+':'+link.to.id;
   assert.notEqual(from,to,order.id);assert.ok(members.has(from),order.id);assert.ok(members.has(to),order.id);
  }
 }
});
