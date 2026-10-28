import test from 'node:test';
import assert from 'node:assert/strict';
import {createImageProxy} from '../image-proxy.mjs';
const jpeg=Buffer.from([255,216,255,0]);
function response(){return new Response(jpeg,{headers:{'content-type':'image/jpeg'}});}
async function request(handler,path='/api/v2/images/tmdb/w342/poster.jpg',method='GET'){
  let status,headers,body;const handled=await handler({method},{writeHead(s,h){status=s;headers=h;},end(b){body=b;}},new URL('http://localhost'+path));return {handled,status,headers,body};
}
test('image proxy fetches only fixed TMDB paths, shares downloads and supports cached HEAD',async()=>{
  let calls=0;const handler=createImageProxy({fetcher:async(url,options)=>{calls++;assert.equal(url,'https://image.tmdb.org/t/p/w342/poster.jpg');assert.equal(options.redirect,'error');await new Promise(resolve=>setTimeout(resolve,5));return response();}});
  for(const result of await Promise.all(Array.from({length:10},()=>request(handler)))){assert.equal(result.status,200);assert.deepEqual(result.body,jpeg);assert.equal(result.headers['content-type'],'image/jpeg');}
  assert.equal(calls,1);assert.equal((await request(handler,undefined,'HEAD')).body,undefined);assert.equal(calls,1);
  for(const path of ['/api/v2/images/tmdb/original/poster.jpg','/api/v2/images/tmdb/w342/%2Fetc%2Fpasswd','/api/v2/images/tmdb/w342/poster.jpg?url=https://localhost'])assert.equal((await request(handler,path)).status,404);
  assert.equal((await request(handler,undefined,'POST')).status,405);assert.equal(calls,1);
});
test('image cache has a byte budget and cold downloads have bounded concurrency',async()=>{
  let calls=0,active=0,peak=0;
  const handler=createImageProxy({budget:4,fetcher:async()=>{calls++;active++;peak=Math.max(peak,active);await new Promise(resolve=>setTimeout(resolve,3));active--;return response();}});
  await request(handler);await request(handler,'/api/v2/images/tmdb/w342/second.jpg');await request(handler);assert.equal(calls,3);
  const results=await Promise.all(Array.from({length:12},(_,i)=>request(handler,'/api/v2/images/tmdb/w342/image'+i+'.jpg')));
  assert.ok(results.every(result=>result.status===200));assert.equal(peak,2);
});
test('proxy rejects HTML masquerading as images and retries failed responses',async()=>{
  let calls=0;const handler=createImageProxy({fetcher:async()=>{calls++;return new Response('<html>error</html>',{headers:{'content-type':'image/jpeg'}});}});
  assert.equal((await request(handler)).status,503);assert.equal((await request(handler)).status,503);assert.equal(calls,2);
});
