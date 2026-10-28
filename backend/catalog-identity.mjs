import { object } from './catalog-model.mjs';
const providers = new Set(['tmdb','kinopoisk','imdb','myAnimeList','shikimori','anilist','tvmaze']);
export function externalIds(payload) {
  return Object.entries(object(object(payload).ids)).filter(([provider,value]) =>
    providers.has(provider) && /^[a-zA-Z0-9_-]{1,48}$/.test(String(value)))
    .map(([provider,value]) => [provider,String(value)]);
}
// Caller holds a transaction and serializes imports. A conflicting identity is never merged silently.
export async function canonicalId(db, row) {
  const matches = new Set();
  const own = await db.query('SELECT id FROM titles WHERE id=?',[row.id]);
  if(own.length) matches.add(row.id);
  for(const [provider,id] of externalIds(row)) {
    const mapped = await db.query('SELECT title_id FROM title_external_ids WHERE provider=? AND kind=? AND external_id=?',[provider,row.kind,id]);
    for(const entry of mapped)matches.add(entry.title_id);
  }
  if(matches.size>1) throw Object.assign(new Error('Conflicting catalogue identities; review required'),{code:'CATALOG_IDENTITY_CONFLICT'});
  return [...matches][0]||row.id;
}
export async function saveExternalIds(db,row) {
  for(const [provider,id] of externalIds(row)) {
    const existing=await db.query('SELECT title_id FROM title_external_ids WHERE provider=? AND kind=? AND external_id=? FOR UPDATE',[provider,row.kind,id]);
    if(existing.length&&existing[0].title_id!==row.id)throw Object.assign(new Error('External ID already belongs to another title'),{code:'CATALOG_IDENTITY_CONFLICT'});
    if(!existing.length)await db.query('INSERT INTO title_external_ids(provider,kind,external_id,title_id) VALUES (?,?,?,?)',[provider,row.kind,id,row.id]);
  }
}
export function refreshDelayMs(payload) {
  const p=object(payload), status=String(p.status||'').trim();
  if(!p.description || !object(p.images).poster)return 24*3600000;
  if(/^(released|ended|finished|completed|canceled|cancelled|заверш[её]н[а-я]*|законч[а-я]*|вышел|вышло)$/iu.test(status))return 30*24*3600000;
  return (p.type==='tv'?6:24)*3600000;
}
export async function recordRefresh(db,id,payload,now=new Date()) {
  const next=new Date(now.getTime()+refreshDelayMs(payload));
  await db.query('INSERT INTO title_refresh_state(title_id,last_success_at,next_refresh_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE last_success_at=VALUES(last_success_at),next_refresh_at=VALUES(next_refresh_at)',[id,now,next]);
}
