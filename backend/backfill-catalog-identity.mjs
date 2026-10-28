import mariadb from 'mariadb';
import { object } from './catalog-model.mjs';
import { saveExternalIds } from './catalog-identity.mjs';
import { identityReport } from './identity-report.mjs';

// Run once after migration 005 and before importing new metadata. Atomic on conflict.
const pool=mariadb.createPool({host:process.env.DB_HOST||'127.0.0.1',port:Number(process.env.DB_PORT||3306),user:process.env.DB_USER||'cinemahall',password:process.env.DB_PASSWORD,database:process.env.DB_NAME||'cinemahall',connectionLimit:1});
let db,locked=false;
try {
  db=await pool.getConnection();
  const lock=await db.query("SELECT GET_LOCK('cinemahall-catalog-sync',0) AS locked");
  if(Number(lock[0].locked)!==1)throw Error('Catalogue import already running');
  locked=true;
  await db.beginTransaction();
  const rows=await db.query('SELECT id,kind,title,year,payload FROM titles ORDER BY id');
  const report=identityReport(rows,await db.query('SELECT provider,kind,external_id,title_id FROM title_external_ids ORDER BY provider,kind,external_id'));
  if(process.argv.includes('--check')){
    console.log(JSON.stringify(report,null,2));
    await db.rollback();
  } else {
    if(report.conflictCount){
      console.error(JSON.stringify(report,null,2));
      throw Object.assign(new Error('Conflicting catalogue identities'),{code:'CATALOG_IDENTITY_CONFLICT'});
    }
    for(const row of rows) {
      await saveExternalIds(db,{...object(row.payload),id:row.id,kind:row.kind});
      await db.query('INSERT IGNORE INTO title_refresh_state(title_id) VALUES (?)',[row.id]);
    }
    await db.commit();
    console.log(JSON.stringify({backfilled:rows.length}));
  }
} catch(error) {
  if(db)await db.rollback();
  console.error('Identity backfill failed:',error.code||error.message);
  process.exitCode=1;
} finally {
  if(locked)await db.query("SELECT RELEASE_LOCK('cinemahall-catalog-sync')");
  if(db)db.release();
  await pool.end();
}
