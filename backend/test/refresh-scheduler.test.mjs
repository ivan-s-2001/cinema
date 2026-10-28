import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {startRefreshScheduler} from '../refresh-scheduler.mjs';

test('persistent due state starts at most one bounded refresh child',async()=>{
 let queries=0,started=0,child;
 const pool={query:async()=>{queries++;return [{title_id:'ch:1'}];}};
 const stop=startRefreshScheduler(pool,{intervalMs:3,spawnChild:(_node,args)=>{
   started++;assert.deepEqual(args.slice(1),['refresh','10']);
   child=new EventEmitter();child.stderr=new EventEmitter();return child;
 }});
 await new Promise(resolve=>setTimeout(resolve,30));
 assert.equal(started,1);assert.ok(queries>=1);
 child.emit('exit',0);
 await new Promise(resolve=>setTimeout(resolve,12));
 assert.ok(started>=2);
 stop();const count=started;
 await new Promise(resolve=>setTimeout(resolve,10));
 assert.equal(started,count);
});
test('empty due queue does not start worker',async()=>{
 const stop=startRefreshScheduler({query:async()=>[]},{intervalMs:3,spawnChild:()=>assert.fail('spawned')});
 await new Promise(resolve=>setTimeout(resolve,15));stop();
});
