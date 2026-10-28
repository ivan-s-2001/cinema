// Public metadata DTOs. Never expose provider payloads or curator data.
export function object(value) {
  if (typeof value === 'string') { try { value = JSON.parse(value); } catch { return {}; } }
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}
export const text = value => typeof value === 'string' && value.trim() ? value.trim() : null;
export const list = value => Array.isArray(value) ? value : [];
export function image(value) {
  if (!text(value) || value.length > 2048) return null;
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null; }
  catch { return null; }
}
export function imageFor(value, role) {
  const valid=image(value);
  if(!valid)return null;
  const url=new URL(valid);
  if(url.hostname==='image.tmdb.org' && /^\/t\/p\/(original|w[0-9]+)\//.test(url.pathname)) {
    const size=role==='poster'?'w342':role==='person'?'w185':'w1280';
    url.pathname=url.pathname.replace(/^\/t\/p\/(original|w[0-9]+)\//,'/t/p/'+size+'/');
  }
  return url.href;
}
export function date(value) {
  if (value instanceof Date) return value.toISOString();
  if (!text(value) || !/^\d{4}-\d{2}-\d{2}(?:T.*)?$/.test(value)) return null;
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value.slice(0, 10) ? value : null;
}
export function integer(value, min = 0, max = Number.MAX_SAFE_INTEGER) {
  return Number.isInteger(value) && value >= min && value <= max ? value : null;
}
export const labels = value => [...new Set(list(value).map(v => text(typeof v === 'string' ? v : v?.name || v?.title)).filter(Boolean))];
export function releaseState(payload, year, now = new Date()) {
  const release = date(payload.releaseDate);
  if ((release && release.slice(0, 10) > now.toISOString().slice(0, 10)) || (year && year > now.getUTCFullYear())) return 'upcoming';
  if (release || /^(released|ended|finished|completed|canceled|cancelled|returning series|ongoing|airing|выходит|вышел|вышло|заверш[её]н[а-я]*|законч[а-я]*)$/iu.test(text(payload.status) || '')) return 'released';
  return 'unknown';
}
function ratings(value) {
  return Object.entries(object(value)).flatMap(([source, raw]) => {
    const data = object(raw), number = typeof raw === 'number' ? raw : data.value;
    const scale = data.scale ?? 10;
    return Number.isFinite(number) && Number.isFinite(scale) && scale > 0 && number >= 0 && number <= scale
      ? [{ source, value: number, scale, votes: integer(data.votes), fetchedAt: date(data.fetchedAt) }] : [];
  });
}
export function titleCard(row, now) {
  const p = object(row.payload);
  return { id: row.id, type: row.kind, title: text(row.title) || 'Без названия',
    originalTitle: text(p.originalTitle), year: integer(row.year, 1888, 2100),
    poster: imageFor(row.poster,'poster'), backdrop: imageFor(row.backdrop,'backdrop'), ratings: ratings(p.ratings),
    ageRating: typeof p.ageRating === 'number' || typeof p.ageRating === 'string' ? p.ageRating : null,
    releaseState: releaseState(p, row.year, now), availability: { status: 'unknown', checkedAt: null, expiresAt: null },
    updatedAt: date(row.updated_at) };
}
function credit(value) {
  if (typeof value === 'string') return { id: null, name: text(value), character: null, photo: null };
  const c = object(value);
  return { id: text(c.id) || (c.tmdbId ? 'tmdb:' + c.tmdbId : null), name: text(c.name), character: text(c.character), photo: imageFor(c.photo,'person') };
}
export function titleDetail(row) {
  const p = object(row.payload), details = object(p.details);
  const externalIds = Object.fromEntries(Object.entries(object(p.ids)).filter(([key, value]) =>
    ['tmdb','kinopoisk','imdb','myAnimeList','shikimori','anilist','tvmaze'].includes(key) && /^[a-zA-Z0-9_-]{1,48}$/.test(String(value)))
    .map(([key,value]) => [key,String(value)]));
  return { ...titleCard(row), titlesByLanguage: { ru: text(p.titleRu), en: text(p.titleEn), original: text(p.originalTitle) },
    aliases: labels(p.aliases).slice(0,100), description: text(row.description),
    releaseDate: date(p.releaseDate), status: text(p.status), runtimeMinutes: integer(p.runtimeMinutes,1,100000),
    genres: labels(p.genres), countries: labels(p.countries), languages: labels(p.languages), externalIds,
    cast: list(p.cast || details.cast).map(credit).filter(c => c.name).slice(0,100),
    directors: list(p.directors || details.directors).map(credit).filter(c => c.name).slice(0,50),
    writers: list(p.writers || details.writers).map(credit).filter(c => c.name).slice(0,50),
    logo: image(object(p.images).logo), metadataState: row.description && image(row.poster) ? 'ready' : 'partial' };
}
export const seasonId = (titleId, season) => `${titleId}:s${season}`;
export const episodeId = (titleId, season, number) => `${titleId}:${season}:${number}`;
export function episode(row, now = new Date()) {
  const p = object(row.payload), air = date(row.air_date);
  return { id: episodeId(row.title_id,row.season_number,row.episode_number), titleId: row.title_id,
    seasonId: seasonId(row.title_id,row.season_number), season: row.season_number, number: row.episode_number,
    title: text(row.title), description: text(p.description || p.overview), still: imageFor(row.still,'backdrop'),
    airAt: air, runtimeMinutes: integer(p.runtimeMinutes,1,100000),
    releaseState: !air ? 'unknown' : air.slice(0,10) > now.toISOString().slice(0,10) ? 'upcoming' : 'released',
    availability: { status: 'unknown', checkedAt: null, expiresAt: null } };
}
