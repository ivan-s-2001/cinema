import mariadb from 'mariadb';

const KINO = 'https://kinoapi.lampac.dev/api/v1';
const key = process.env.KINOAPI_KEY || '';
const mode = process.argv[2] || 'daily';
const pages = Number(process.argv[3] || (mode === 'bootstrap' ? 50 : 3));
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const completed = status => /^(released|ended|finished|completed|canceled|cancelled|заверш[её]н[а-я]*|законч[а-я]*|вышел|вышло)$/i.test(String(status || '').trim());
const validUrl = value => {
  if (typeof value !== 'string' || value.length > 2048) return '';
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : '';
  } catch { return ''; }
};
const distinct = values => [...new Set(values.filter(v => typeof v === 'string' && v.trim()).map(v => v.trim()))];
const idsFor = raw => {
  const ids = raw.id || {};
  const result = {};
  for (const field of ['tmdb', 'kinopoisk', 'imdb', 'myAnimeList', 'shikimori', 'anilist']) {
    if (ids[field] !== undefined && ids[field] !== null && /^[a-zA-Z0-9_-]{1,48}$/.test(String(ids[field])))
      result[field] = String(ids[field]);
  }
  return result;
};
function normalize(raw) {
  if (!raw || !['movie', 'tv'].includes(raw.type)) return null;
  const ids = idsFor(raw);
  const genres = Array.isArray(raw.genres) ? raw.genres : [];
  const category = [raw.category,raw.subtype,raw.genre,raw.typeName].filter(v=>typeof v==='string').join(' ');
  if (genres.some(g => /аниме|\banime\b/i.test(typeof g==='string'?g:(g?.name||''))) ||
      /аниме|\banime\b/i.test(category) || ids.shikimori || ids.anilist) return null;
  const chosen = ['tmdb', 'kinopoisk', 'imdb', 'myAnimeList', 'shikimori'].find(k => ids[k]);
  if (!chosen) return null;
  const title = raw.title || {}, images = raw.images || {}, release = raw.release || {};
  const aliases = distinct([title.ru, title.en, title.original]);
  if (!aliases.length) return null;
  const poster = [images.poster, ...(Array.isArray(images.posters) ? images.posters : []), images.kinopoisk?.poster].map(validUrl).find(Boolean) || '';
  const backdrop = [images.backdrop, ...(Array.isArray(images.backdrops) ? images.backdrops : [])].map(validUrl).find(v => v && v !== poster) || '';
  const year = Number(release.year);
  const id = 'ch:snapshot:' + raw.type + ':' + chosen + ':' + ids[chosen];
  const payload = {
    type: raw.type, ids, title: title.ru || title.original || aliases[0],
    titleRu: title.ru || '', titleEn: title.en || '', originalTitle: title.original || '',
    aliases, year: Number.isInteger(year) && year >= 1888 && year <= 2100 ? year : null,
    status: String(release.status || '').slice(0, 70),
    releaseDate: String(release.date || '').slice(0, 30),
    runtimeMinutes: Number.isInteger(Number(release.runtimeMinutes)) && Number(release.runtimeMinutes)>0 ? Number(release.runtimeMinutes) : null,
    ageRating: release.ageRating ?? null,
    description: String(raw.description?.ru || raw.description?.en || '').slice(0, 30000),
    genres: distinct(raw.genres || []).slice(0, 40),
    countries: distinct(raw.countries || []).slice(0, 40),
    ratings: raw.ratings && typeof raw.ratings === 'object' && !Array.isArray(raw.ratings) ? raw.ratings : {},
    images: { poster, backdrop, backdropVerified: Boolean(backdrop) },
    sources: ['kinoapi'], upstreamUpdatedAt: String(raw.updatedAt || '').slice(0, 60)
  };
  return { id, kind: raw.type === 'tv' ? 'series' : 'movie', ...payload, poster, backdrop };
}
function selfTest() {
  const item = normalize({ type: 'movie', id: { tmdb: 42 }, title: { ru: 'Тест', en: 'Test' },
    images: { poster: 'https://images.example/poster.jpg', backdrop: 'https://images.example/wide.jpg' },
    release: { status: 'Released', year: 2000 } });
  if (item.id !== 'ch:snapshot:movie:tmdb:42' || item.poster === item.backdrop ||
      !completed(item.status) || validUrl('file:///private/file.jpg') || !item.aliases.includes('Test'))
    throw Error('Catalog normalization self-test failed');
  console.log('Catalog sync self-test passed');
}
let lastCall = 0;
async function get(path, optional = false) {
  for (let attempt = 0; attempt < 4; attempt++) {
    const delay = 300 - (Date.now() - lastCall);
    if (delay > 0) await pause(delay);
    lastCall = Date.now();
    try {
      const response = await fetch(KINO + path, {
        headers: { 'X-API-Key': key, 'Accept': 'application/json', 'User-Agent': 'CinemaHall-Sync/1.0' },
        redirect: 'error', signal: AbortSignal.timeout(12000)
      });
      if ([429, 502, 503].includes(response.status) && attempt < 3) {
        await pause(Math.min(20000, 1500 * 2 ** attempt));
        continue;
      }
      if (!response.ok) throw Error('KinoAPI HTTP ' + response.status + ' at ' + path.split('?')[0]);
      const data = await response.arrayBuffer();
      if (data.byteLength > 6 * 1024 * 1024) throw Error('Oversized KinoAPI response');
      return JSON.parse(Buffer.from(data).toString('utf8'));
    } catch (err) {
      if (attempt < 3 && (/fetch failed|timeout|aborted/i.test(err.message))) {
        await pause(1500 * 2 ** attempt);
        continue;
      }
      if (optional) { console.error('Optional source unavailable: ' + path.split('?')[0]); return null; }
      throw err;
    }
  }
  return null;
}
const stats = { scanned: 0, inserted: 0, updated: 0, skipped: 0, invalid: 0 };
function fromJson(value) {
  if (value && typeof value === 'object') return value;
  try { return JSON.parse(value || '{}'); } catch { return {}; }
}
async function store(db, raw, force = false) {
  stats.scanned++;
  const row = normalize(raw);
  if (!row) { stats.invalid++; return; }
  const result = await db.query('SELECT title,description,year,poster,backdrop,payload,updated_at FROM titles WHERE id=?', [row.id]);
  const previous = result[0], old = fromJson(previous?.payload);
  if (previous && !force && (completed(old.status) || (mode === 'daily' && Date.now() - new Date(previous.updated_at).getTime() < 20 * 3600000))) {
    stats.skipped++; return;
  }
  const curator = old.curator || {};
  const locked = new Set(Array.isArray(curator.fields) ? curator.fields : []);
  const field = (name, value) => locked.has(name) && previous ? previous[name] : value;
  const oldImages = old.images || {};
  const artFromSpecializedSource = Array.isArray(old.sources) && old.sources.some(source => ['tmdb', 'anilist', 'cinemeta'].includes(source));
  const poster = artFromSpecializedSource && oldImages.poster ? oldImages.poster : row.poster || previous?.poster || oldImages.poster || '';
  const backdrop = oldImages.backdropVerified && oldImages.backdrop && artFromSpecializedSource
    ? oldImages.backdrop : row.backdrop || previous?.backdrop || oldImages.backdrop || '';
  const payload = {
    ...old, ...row, ratings: { ...(old.ratings || {}), ...(row.ratings || {}) },
    sources: distinct([...(Array.isArray(old.sources) ? old.sources : []), ...row.sources]),
    images: { ...oldImages, poster, backdrop,
      backdropVerified: Boolean(row.backdrop || oldImages.backdropVerified) },
    curator
  };
  row.poster = payload.images.poster;
  row.backdrop = payload.images.backdrop;
  for (const name of ['title', 'description', 'year', 'poster', 'backdrop']) {
    payload[name] = field(name, row[name]);
    if (name === 'poster' || name === 'backdrop') payload.images[name] = payload[name];
  }
  if (locked.has('aliases')) payload.aliases = old.aliases || row.aliases;
  if (locked.has('ratings')) payload.ratings = old.ratings || row.ratings;
  await db.query(
    'INSERT INTO titles (id,kind,title,year,description,poster,backdrop,payload) VALUES (?,?,?,?,?,?,?,?) ' +
    'ON DUPLICATE KEY UPDATE kind=VALUES(kind),title=VALUES(title),year=VALUES(year),description=VALUES(description),' +
    'poster=VALUES(poster),backdrop=VALUES(backdrop),payload=VALUES(payload)',
    [row.id, row.kind, payload.title, payload.year, payload.description, payload.poster, payload.backdrop, JSON.stringify(payload)]
  );
  if (previous) stats.updated++; else stats.inserted++;
}
async function run() {
  if (mode === 'selftest') { selfTest(); return; }
  if (!['bootstrap', 'daily', 'one'].includes(mode) || (mode !== 'one' && (!Number.isInteger(pages) || pages < 1 || pages > 200)))
    throw Error('Usage: node sync-catalog.mjs bootstrap|daily [pages 1..200], or one kinopoisk|tmdb ID');
  if (!key || !process.env.DB_PASSWORD) throw Error('KINOAPI_KEY and DB_PASSWORD must be configured');
  const pool = mariadb.createPool({
    host: process.env.DB_HOST || '127.0.0.1', user: process.env.DB_USER || 'cinemahall',
    password: process.env.DB_PASSWORD, database: process.env.DB_NAME || 'cinemahall',
    connectionLimit: 1, connectTimeout: 5000
  });
  let db, locked = false;
  try {
    db = await pool.getConnection();
    const result = await db.query("SELECT GET_LOCK('cinemahall-catalog-sync', 0) AS locked");
    if (Number(result[0]?.locked) !== 1) throw Error('Another catalog sync is already running');
    locked = true;
    if (mode === 'one') {
      const source = process.argv[3], id = process.argv[4];
      if (!['kinopoisk', 'tmdb'].includes(source) || !/^[0-9]{1,12}$/.test(id || ''))
        throw Error('Use: one kinopoisk|tmdb numeric-ID');
      const record = await get('/titles/by/' + source + '/' + id + '?include=images');
      if (!record?.data) throw Error('No title returned from KinoAPI');
      await store(db, record.data, true);
    } else {
      for (const kind of ['movie', 'tv']) {
        for (let page = 1; page <= pages; page++) {
          const params = new URLSearchParams({ type: kind, sort: 'popularity', order: 'desc', page: String(page), limit: '100' });
          const data = await get('/titles?' + params);
          const entries = Array.isArray(data?.data) ? data.data : [];
          for (const item of entries) await store(db, item);
          console.log(kind + ' page ' + page + ': ' + entries.length + ' titles');
          if (!entries.length || data?.meta?.hasNext === false) break;
        }
      }
      for (const path of ['/titles/latest?limit=100']) {
        const data = await get(path, true);
        for (const item of (Array.isArray(data?.data) ? data.data : [])) await store(db, item);
      }
    }
    console.log(JSON.stringify({ mode, ...stats }));
  } finally {
    if (locked) await db.query("SELECT RELEASE_LOCK('cinemahall-catalog-sync')");
    if (db) db.release();
    await pool.end();
  }
}
run().catch(err => { console.error(err.message); process.exitCode = 1; });
