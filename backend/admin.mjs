import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHmac, randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import { spawn } from 'node:child_process';

const directory = dirname(fileURLToPath(import.meta.url));
const COOKIE = 'cinemahall_session';
const MAX_AGE = 8 * 3600;
const SESSION_SECRET = process.env.ADMIN_SESSION_SECRET || '';
const PASSWORD_HASH = process.env.ADMIN_PASSWORD_SCRYPT || '';
const attempts = new Map();
const run = { status: 'idle', logs: '', startedAt: null, finishedAt: null, mode: null };
const addLog = chunk => { run.logs = (run.logs + chunk.toString('utf8')).slice(-12000); };
const badRequest = (message, status = 400) => Object.assign(new Error(message), { status });
function json(res, status, value, headers = {}) {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store', 'x-content-type-options': 'nosniff', ...headers });
  res.end(body);
}
async function body(req) {
  let data = '', length = 0;
  for await (const chunk of req) {
    length += chunk.length;
    if (length > 65536) throw badRequest('Слишком большой запрос', 413);
    data += chunk.toString('utf8');
  }
  try { return JSON.parse(data || '{}'); } catch { throw badRequest('Некорректный JSON'); }
}
function secret() {
  return /^[0-9a-f]{64,}$/i.test(SESSION_SECRET) && /^[0-9a-f]{32}:[0-9a-f]{128}$/i.test(PASSWORD_HASH);
}
function authenticate(password) {
  if (!secret() || typeof password !== 'string' || password.length > 256) return false;
  const [salt, digest] = PASSWORD_HASH.split(':');
  const actual = scryptSync(password, Buffer.from(salt, 'hex'), 64);
  return timingSafeEqual(actual, Buffer.from(digest, 'hex'));
}
function sign(message) {
  return createHmac('sha256', Buffer.from(SESSION_SECRET, 'hex')).update(message).digest('hex');
}
function cookieToken() {
  const unsigned = String(Date.now() + MAX_AGE * 1000) + '.' + randomBytes(16).toString('hex');
  return unsigned + '.' + sign(unsigned);
}
function authorized(req) {
  if (!secret()) return false;
  const match = (req.headers.cookie || '').split(';').map(v => v.trim()).find(v => v.startsWith(COOKIE + '='));
  if (!match) return false;
  const token = match.slice(COOKIE.length + 1), parts = token.split('.');
  if (parts.length !== 3 || !/^\d{13}$/.test(parts[0]) || !/^[0-9a-f]{32}$/.test(parts[1]) || !/^[0-9a-f]{64}$/.test(parts[2])) return false;
  if (Number(parts[0]) < Date.now() || Number(parts[0]) > Date.now() + MAX_AGE * 1000) return false;
  return timingSafeEqual(Buffer.from(sign(parts[0] + '.' + parts[1]), 'hex'), Buffer.from(parts[2], 'hex'));
}
function validateUrl(value) {
  if (value === '' || value === null) return '';
  if (typeof value !== 'string' || value.length > 2048) throw badRequest('Некорректная ссылка');
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password) throw Error();
    return url.href;
  } catch { throw badRequest('Используйте HTTPS-ссылки на изображения'); }
}
function fields(input) {
  const out = {};
  if ('title' in input) {
    if (typeof input.title !== 'string' || !input.title.trim() || input.title.length > 512) throw badRequest('Название: 1–512 символов');
    out.title = input.title.trim();
  }
  if ('description' in input) {
    if (typeof input.description !== 'string' || input.description.length > 30000) throw badRequest('Описание слишком длинное');
    out.description = input.description;
  }
  if ('year' in input) {
    const year = input.year === '' || input.year === null ? null : Number(input.year);
    if (year !== null && (!Number.isInteger(year) || year < 1888 || year > 2100)) throw badRequest('Некорректный год');
    out.year = year;
  }
  for (const name of ['poster', 'backdrop']) if (name in input) out[name] = validateUrl(input[name]);
  if ('aliases' in input) {
    if (!Array.isArray(input.aliases) || input.aliases.length > 30 || input.aliases.some(v => typeof v !== 'string' || v.length > 255))
      throw badRequest('Некорректные альтернативные названия');
    out.aliases = [...new Set(input.aliases.map(v => v.trim()).filter(Boolean))];
  }
  if ('ratings' in input) {
    if (!input.ratings || Array.isArray(input.ratings) || typeof input.ratings !== 'object' || JSON.stringify(input.ratings).length > 8000)
      throw badRequest('Рейтинги должны быть JSON-объектом');
    out.ratings = input.ratings;
  }
  return out;
}
async function query(pool, sql, args = []) {
  const conn = await pool.getConnection();
  try { return await conn.query(sql, args); } finally { conn.release(); }
}
function parsePayload(value) {
  if (value && typeof value === 'object') return value;
  try { return JSON.parse(value || '{}'); } catch { return {}; }
}
function job(mode, value) {
  if (run.status === 'running') return false;
  run.status = 'running';
  run.mode = mode;
  run.logs = '';
  run.startedAt = new Date().toISOString();
  run.finishedAt = null;
  const args = mode === 'one' ? ['sync-catalog.mjs', 'one', value.source, String(value.id)]
    : ['sync-catalog.mjs', mode, String(value.pages)];
  const child = spawn(process.execPath, args, { cwd: directory, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', addLog);
  child.stderr.on('data', addLog);
  child.on('error', err => { addLog(err.message); run.status = 'failed'; run.finishedAt = new Date().toISOString(); });
  child.on('exit', code => {
    run.status = code === 0 ? 'success' : 'failed';
    run.finishedAt = new Date().toISOString();
  });
  return true;
}
async function getSchedule(pool) {
  const rows = await query(pool, "SELECT value FROM admin_settings WHERE name='catalog_schedule'");
  return { enabled: false, hour: 3, minute: 0, pages: 3, ...(parsePayload(rows[0]?.value)) };
}
async function saveSchedule(pool, value) {
  await query(pool, "INSERT INTO admin_settings (name,value) VALUES ('catalog_schedule',?) ON DUPLICATE KEY UPDATE value=VALUES(value)", [JSON.stringify(value)]);
}
export function startAdminScheduler(pool) {
  const formatter = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Moscow',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  async function tick() {
    try {
      const setting = await getSchedule(pool);
      if (!setting.enabled || run.status === 'running') return;
      const parts = Object.fromEntries(formatter.formatToParts(new Date()).map(x => [x.type, x.value]));
      const date = parts.year + '-' + parts.month + '-' + parts.day;
      const current = Number(parts.hour) * 60 + Number(parts.minute);
      if (current < setting.hour * 60 + setting.minute || setting.lastRunDate === date) return;
      if (job('daily', { pages: setting.pages })) await saveSchedule(pool, { ...setting, lastRunDate: date });
    } catch (err) { console.error('Admin scheduler:', err.code || err.message); }
  }
  const timer = setInterval(tick, 60000);
  timer.unref();
  setTimeout(tick, 3000).unref();
}
const FILES = { '/admin': ['admin.html', 'text/html; charset=utf-8'],
  '/admin/': ['admin.html', 'text/html; charset=utf-8'],
  '/admin/app.js': ['admin.js', 'text/javascript; charset=utf-8'],
  '/admin/app.css': ['admin.css', 'text/css; charset=utf-8'] };
export async function handleAdmin(req, res, pool) {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (FILES[path] && req.method === 'GET') {
    const [file, type] = FILES[path];
    const csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src https: data:; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    res.writeHead(200, { 'content-type': type, 'content-security-policy': csp,
      'x-frame-options': 'DENY', 'x-content-type-options': 'nosniff', 'cache-control': 'no-store' });
    res.end(readFileSync(join(directory, file)));
    return true;
  }
  if (!path.startsWith('/api/admin/')) return false;
  try {
    if (path === '/api/admin/login' && req.method === 'POST') {
      const ip = req.socket.remoteAddress || 'unknown';
      const current = Date.now();
      const recent = (attempts.get(ip) || []).filter(t => current - t < 60000);
      if (recent.length >= 5) { json(res, 429, { error: 'Повторите через минуту' }); return true; }
      recent.push(current); attempts.set(ip, recent);
      const input = await body(req);
      if (!authenticate(input.password)) { json(res, 401, { error: 'Неверный пароль или админка не настроена' }); return true; }
      attempts.delete(ip);
      json(res, 200, { ok: true }, { 'set-cookie': COOKIE + '=' + cookieToken() + '; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=' + MAX_AGE });
      return true;
    }
    if (path === '/api/admin/session' && req.method === 'GET') {
      json(res, 200, { authenticated: authorized(req), configured: secret() }); return true;
    }
    if (!authorized(req)) { json(res, 401, { error: 'Требуется вход' }); return true; }
    if (req.method !== 'GET' && req.headers['x-cinemahall-admin'] !== '1') {
      json(res, 403, { error: 'Операция заблокирована' }); return true;
    }
    if (path === '/api/admin/logout' && req.method === 'POST') {
      json(res, 200, { ok: true }, { 'set-cookie': COOKIE + '=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0' }); return true;
    }
    if (path === '/api/admin/stats' && req.method === 'GET') {
      const counts = await query(pool, 'SELECT kind,COUNT(*) AS total FROM titles GROUP BY kind');
      json(res, 200, { counts, job: run }); return true;
    }
    if (path === '/api/admin/titles' && req.method === 'GET') {
      const u = new URL(req.url, 'http://localhost');
      const q = (u.searchParams.get('q') || '').trim().slice(0, 100);
      const offset = Math.min(100000, Math.max(0, Math.floor(Number(u.searchParams.get('offset')) || 0)));
      const rows = await query(pool,
        'SELECT id,kind,title,year,poster,backdrop,updated_at FROM titles WHERE title LIKE ? ORDER BY updated_at DESC LIMIT 40 OFFSET ?',
        ['%' + q.replace(/[%_\\]/g, '\\$&') + '%', offset]);
      json(res, 200, { items: rows }); return true;
    }
    if (path === '/api/admin/titles' && req.method === 'POST') {
      const input = await body(req), values = fields(input);
      if (!values.title || !['movie', 'series'].includes(input.kind)) throw badRequest('Укажите тип и название');
      const id = 'ch:manual:' + randomUUID().replace(/-/g, '');
      const payload = { type: input.kind === 'series' ? 'tv' : 'movie', aliases: values.aliases || [values.title],
        ratings: values.ratings || {}, images: { poster: values.poster || '', backdrop: values.backdrop || '' },
        curator: { fields: Object.keys(values), editedAt: new Date().toISOString() }, sources: ['manual'] };
      await query(pool, 'INSERT INTO titles (id,kind,title,year,description,poster,backdrop,payload) VALUES (?,?,?,?,?,?,?,?)',
        [id, input.kind, values.title, values.year || null, values.description || '', values.poster || '', values.backdrop || '', JSON.stringify(payload)]);
      json(res, 201, { id }); return true;
    }
    const titleMatch = /^\/api\/admin\/titles\/([A-Za-z0-9:_-]{1,160})$/.exec(path);
    if (titleMatch && req.method === 'GET') {
      const rows = await query(pool, 'SELECT id,kind,title,year,description,poster,backdrop,payload FROM titles WHERE id=?', [titleMatch[1]]);
      if (!rows.length) { json(res, 404, { error: 'Не найдено' }); return true; }
      json(res, 200, { title: { ...rows[0], payload: parsePayload(rows[0].payload) } }); return true;
    }
    if (titleMatch && req.method === 'PATCH') {
      const changes = fields(await body(req));
      if (!Object.keys(changes).length) throw badRequest('Нет изменений');
      const rows = await query(pool, 'SELECT payload FROM titles WHERE id=?', [titleMatch[1]]);
      if (!rows.length) { json(res, 404, { error: 'Не найдено' }); return true; }
      const payload = parsePayload(rows[0].payload), curator = payload.curator || {};
      const locked = new Set([...(curator.fields || []), ...Object.keys(changes)]);
      const merged = { ...payload, ...changes, curator: { fields: [...locked], editedAt: new Date().toISOString() } };
      merged.images = { ...(payload.images || {}), poster: changes.poster ?? payload.images?.poster ?? '',
        backdrop: changes.backdrop ?? payload.images?.backdrop ?? '' };
      const columns = ['title', 'year', 'description', 'poster', 'backdrop'].filter(k => k in changes);
      const sql = 'UPDATE titles SET ' + columns.map(k => k + '=?').concat('payload=?').join(',') + ' WHERE id=?';
      await query(pool, sql, [...columns.map(k => changes[k]), JSON.stringify(merged), titleMatch[1]]);
      json(res, 200, { ok: true }); return true;
    }
    if (path === '/api/admin/job' && req.method === 'GET') { json(res, 200, run); return true; }
    if (path === '/api/admin/job' && req.method === 'POST') {
      const input = await body(req);
      if (input.mode === 'one') {
        if (!['kinopoisk', 'tmdb'].includes(input.source) || !/^\d{1,12}$/.test(String(input.id || '')))
          throw badRequest('Нужен числовой ID Кинопоиска или TMDB');
      } else if (!['bootstrap', 'daily'].includes(input.mode) || !Number.isInteger(input.pages) || input.pages < 1 || input.pages > 200)
        throw badRequest('Выберите режим и количество страниц');
      if (!job(input.mode, input)) { json(res, 409, { error: 'Импорт уже выполняется' }); return true; }
      json(res, 202, { ok: true }); return true;
    }
    if (path === '/api/admin/schedule' && req.method === 'GET') {
      json(res, 200, await getSchedule(pool)); return true;
    }
    if (path === '/api/admin/schedule' && req.method === 'PATCH') {
      const input = await body(req);
      if (typeof input.enabled !== 'boolean' || !Number.isInteger(input.hour) || input.hour < 0 || input.hour > 23 ||
        !Number.isInteger(input.minute) || input.minute < 0 || input.minute > 59 ||
        !Number.isInteger(input.pages) || input.pages < 1 || input.pages > 50) throw badRequest('Неверное расписание');
      const previous = await getSchedule(pool);
      const saved = { enabled: input.enabled, hour: input.hour, minute: input.minute,
        pages: input.pages, lastRunDate: previous.lastRunDate || null };
      await saveSchedule(pool, saved);
      json(res, 200, saved); return true;
    }
    json(res, 404, { error: 'Маршрут не найден' });
  } catch (err) {
    console.error('Admin API error:', err.code || err.message);
    json(res, err.status || 503, { error: err.status ? err.message : 'Операция временно недоступна' });
  }
  return true;
}
