import mariadb from 'mariadb';
import {readFile} from 'node:fs/promises';
import {updateDiscoveryIndex} from './discovery-index.mjs';
import {createMetadataLibrary} from './metadata-library.mjs';
const pool=mariadb.createPool({host:process.env.DB_HOST||'127.0.0.1',port:Number(process.env.DB_PORT||3306),user:process.env.DB_USER||'cinemahall',password:process.env.DB_PASSWORD,database:process.env.DB_NAME||'cinemahall',connectionLimit:2,multipleStatements:true});
let lock;
const metadata=createMetadataLibrary(pool);
try{
 await pool.query(await readFile(new URL('./migrations/006_discovery_people.sql',import.meta.url),'utf8'));
 lock=await pool.getConnection();
 const acquired=await lock.query("SELECT GET_LOCK('cinemahall-discovery',0) AS acquired");
 if(!Number(acquired[0].acquired)){console.log('Discovery preparation is already running');process.exitCode=0;}
 else{
  const full=process.argv.includes('--full');
  const rows=await pool.query('SELECT * FROM titles '+(full?'':'WHERE source_expires_at IS NULL OR source_expires_at<=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 HOUR) ')+'ORDER BY (source_count>0) DESC,COALESCE(source_expires_at,\'1970-01-01\'),id'+(full?'':' LIMIT 400'));
  console.log('Indexing source availability for '+rows.length+' titles');
  const result=await updateDiscoveryIndex(pool,rows,{progress:s=>console.log('Discovery '+s.processed+'/'+s.total+'; available='+s.available+'; failed='+s.failed)});
  const ready=await pool.query('SELECT COUNT(*) AS n FROM titles WHERE source_count>0 AND source_expires_at>UTC_TIMESTAMP(3)');
  if(!Number(ready[0].n))throw Error('No watchable titles indexed; existing API is not switched');
  console.log('Indexing existing actor credits');
  const existing=await pool.query("SELECT id,payload FROM titles WHERE JSON_LENGTH(JSON_EXTRACT(payload,'$.cast'))>0 OR JSON_LENGTH(JSON_EXTRACT(payload,'$.details.cast'))>0");
  for(const row of existing)await metadata.indexExisting(row);
  console.log('Preparing cast and episode inventories');
  const titles=await pool.query("SELECT * FROM titles WHERE source_count>0 AND source_expires_at>UTC_TIMESTAMP(3) AND (JSON_VALUE(payload,'$.creditsCheckedAt') IS NULL OR JSON_VALUE(payload,'$.creditsCheckedAt')<DATE_FORMAT(DATE_SUB(UTC_TIMESTAMP(),INTERVAL 7 DAY),'%Y-%m-%d')) ORDER BY catalog_popularity DESC,catalog_rating DESC LIMIT ?",[full?24:40]);
  for(const [i,row]of titles.entries()){await metadata.ensure(row);console.log('Metadata '+(i+1)+'/'+titles.length);}
  console.log('DISCOVERY_READY '+JSON.stringify(result));
 }
}catch(error){console.error('Discovery preparation failed:',error.code||error.message);process.exitCode=1;}
finally{await metadata.close();if(lock){await lock.query("SELECT RELEASE_LOCK('cinemahall-discovery')");lock.release();}await pool.end();}
