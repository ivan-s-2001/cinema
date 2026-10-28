import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {dirname,join} from 'node:path';

// The due queue is durable in MariaDB; only the timer is in process memory.
export function startRefreshScheduler(pool,{spawnChild=spawn,intervalMs=30*60_000}={}) {
  let child=null,closed=false;
  const script=join(dirname(fileURLToPath(import.meta.url)),'sync-catalog.mjs');
  async function tick() {
    if(closed||child)return;
    try {
      const due=await pool.query('SELECT title_id FROM title_refresh_state WHERE next_refresh_at<=UTC_TIMESTAMP(3) ORDER BY next_refresh_at,title_id LIMIT 1');
      if(!due.length||closed||child)return;
      child=spawnChild(process.execPath,[script,'refresh','10'],{cwd:dirname(script),env:process.env,stdio:['ignore','ignore','pipe']});
      let errors='';
      child.stderr?.on('data',data=>{errors=(errors+String(data)).slice(-1000);});
      child.once('error',error=>{console.error('Catalog refresh startup:',error.code||error.name);child=null;});
      child.once('exit',code=>{if(code!==0)console.error('Catalog refresh failed:',code,errors.slice(-300));child=null;});
    }catch(error){console.error('Catalog refresh schedule:',error.code||error.name);}
  }
  const timer=setInterval(tick,intervalMs);timer.unref();
  const startup=setTimeout(tick,10000);startup.unref();
  return ()=>{closed=true;clearInterval(timer);clearTimeout(startup);};
}
