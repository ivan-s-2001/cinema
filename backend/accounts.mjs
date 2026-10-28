import { randomBytes, randomUUID, scrypt as scryptCallback, createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCallback);
const MAX_BODY_BYTES = 1_048_576;
const MAX_PROFILES = 6;
const SESSION_DAYS = 30;
const ACCESS_TOKEN_SECONDS = 3600;
const RATE_WINDOW_MS = 15 * 60_000;
const RATE_LIMIT = 8;
const authAttempts = new Map();

function send(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'content-length': Buffer.byteLength(body)
  });
  res.end(body);
}

async function readJson(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (Buffer.byteLength(body) > MAX_BODY_BYTES) {
      const error = new Error('Request body too large');
      error.status = 413;
      throw error;
    }
  }
  try {
    return body ? JSON.parse(body) : {};
  } catch {
    const error = new Error('Invalid JSON');
    error.status = 400;
    throw error;
  }
}

function requireApiKey(req) {
  const expected = process.env.CINEMA_PUBLISHABLE_KEY || '';
  if (!expected) return;
  const supplied = req.headers.apikey || '';
  const left = Buffer.from(supplied);
  const right = Buffer.from(expected);
  if (left.length !== right.length || !timingSafeEqual(left, right)) {
    throw Object.assign(new Error('Invalid API key'), { status: 401 });
  }
}

function sendNoContent(res) {
  res.writeHead(204, { 'cache-control': 'no-store', 'content-length': '0' });
  res.end();
}

function rateLimitKey(req, action) {
  const address = req.headers['x-real-ip'] || req.socket.remoteAddress || 'unknown';
  return action + ':' + address;
}

function checkRateLimit(req, action) {
  const key = rateLimitKey(req, action);
  const now = Date.now();
  const recent = (authAttempts.get(key) || []).filter(timestamp => now - timestamp < RATE_WINDOW_MS);
  if (recent.length >= RATE_LIMIT) {
    authAttempts.set(key, recent);
    const error = new Error('Too many attempts');
    error.status = 429;
    throw error;
  }
  recent.push(now);
  authAttempts.set(key, recent);
  if (authAttempts.size > 2048) {
    for (const [entry, attempts] of authAttempts) {
      if (!attempts.some(timestamp => now - timestamp < RATE_WINDOW_MS)) authAttempts.delete(entry);
    }
  }
}

async function passwordHash(password) {
  const salt = randomBytes(16);
  const hash = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return 'scrypt$16384$8$1$' + salt.toString('hex') + '$' + Buffer.from(hash).toString('hex');
}

async function passwordMatches(password, encoded) {
  const [algorithm, cost, blockSize, parallel, saltHex, hashHex] = encoded.split('$');
  if (algorithm !== 'scrypt' || cost !== '16384' || blockSize !== '8' || parallel !== '1' ||
      !/^[0-9a-f]{32}$/i.test(saltHex || '') || !/^[0-9a-f]{128}$/i.test(hashHex || '')) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const actual = Buffer.from(await scrypt(password, Buffer.from(saltHex, 'hex'), expected.length,
    { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }));
  return timingSafeEqual(actual, expected);
}

function tokenHash(token) {
  return createHash('sha256').update(token).digest('hex');
}

function bearer(req) {
  const match = /^Bearer ([A-Za-z0-9_.-]{40,2048})$/i.exec(req.headers.authorization || '');
  return match?.[1] || null;
}

function jwtSecret() {
  const secret = process.env.AUTH_JWT_SECRET || '';
  if (Buffer.byteLength(secret) < 32) {
    const error = new Error('Account authentication is not configured');
    error.status = 503;
    throw error;
  }
  return secret;
}

function jwtSign(account, sessionId) {
  const now = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const payload = Buffer.from(JSON.stringify({
    aud: 'authenticated',
    role: 'authenticated',
    sub: account.id,
    email: account.email,
    session_id: sessionId,
    app_metadata: { provider: 'email', providers: ['email'] },
    user_metadata: {},
    is_anonymous: false,
    iat: now,
    exp: now + ACCESS_TOKEN_SECONDS
  })).toString('base64url');
  const input = header + '.' + payload;
  const signature = createHmac('sha256', jwtSecret()).update(input).digest('base64url');
  return input + '.' + signature;
}

function jwtVerify(token) {
  const parts = token.split('.');
  if (parts.length !== 3) throw Object.assign(new Error('Invalid session'), { status: 401 });
  const input = parts[0] + '.' + parts[1];
  const expected = createHmac('sha256', jwtSecret()).update(input).digest();
  let actual;
  let claims;
  try {
    actual = Buffer.from(parts[2], 'base64url');
    claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  } catch {
    throw Object.assign(new Error('Invalid session'), { status: 401 });
  }
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected) ||
      claims.aud !== 'authenticated' || claims.role !== 'authenticated' ||
      !claims.sub || !claims.session_id || !Number.isInteger(claims.exp) ||
      claims.exp <= Math.floor(Date.now() / 1000)) {
    throw Object.assign(new Error('Session expired'), { status: 401 });
  }
  return claims;
}

function authUser(account) {
  const createdAt = account.created_at ? new Date(account.created_at).toISOString() : new Date().toISOString();
  return {
    id: account.id,
    aud: 'authenticated',
    role: 'authenticated',
    email: account.email,
    app_metadata: { provider: 'email', providers: ['email'] },
    user_metadata: {},
    identities: [],
    created_at: createdAt,
    updated_at: createdAt,
    email_confirmed_at: createdAt,
    is_anonymous: false
  };
}

async function requireAccount(req, pool) {
  const token = bearer(req);
  if (!token) throw Object.assign(new Error('Authentication required'), { status: 401 });
  const claims = jwtVerify(token);
  const rows = await pool.query(
    'SELECT a.id,a.email,a.created_at,s.session_id FROM account_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.session_id=? AND s.account_id=? AND s.expires_at>UTC_TIMESTAMP() AND a.disabled=0',
    [claims.session_id, claims.sub]
  );
  if (!rows.length) throw Object.assign(new Error('Session expired'), { status: 401 });
  return rows[0];
}

async function issueSession(account, pool, sessionId = randomUUID()) {
  const refreshToken = randomBytes(32).toString('base64url');
  const expiresAt = Math.floor(Date.now() / 1000) + ACCESS_TOKEN_SECONDS;
  await pool.query('DELETE FROM account_sessions WHERE expires_at <= UTC_TIMESTAMP()');
  if (account.sessionId || sessionId !== null) {
    await pool.query(
      'INSERT INTO account_sessions (session_id,refresh_token_hash,account_id,expires_at) VALUES (?,?,?,DATE_ADD(UTC_TIMESTAMP(), INTERVAL 30 DAY)) ON DUPLICATE KEY UPDATE refresh_token_hash=VALUES(refresh_token_hash),expires_at=VALUES(expires_at)',
      [sessionId, tokenHash(refreshToken), account.id]
    );
  }
  return {
    access_token: jwtSign(account, sessionId),
    token_type: 'bearer',
    expires_in: ACCESS_TOKEN_SECONDS,
    expires_at: expiresAt,
    refresh_token: refreshToken,
    user: authUser(account)
  };
}

function validProfileIndex(value) {
  const index = Number(value);
  if (!Number.isInteger(index) || index < 1 || index > MAX_PROFILES) {
    const error = new Error('Invalid profile');
    error.status = 400;
    throw error;
  }
  return index;
}

async function requireProfile(accountId, index, pool) {
  const rows = await pool.query(
    'SELECT profile_index AS id, name, avatar_color AS avatarColor, created_at AS createdAt, updated_at AS updatedAt FROM profiles WHERE account_id=? AND profile_index=?',
    [accountId, validProfileIndex(index)]
  );
  if (!rows.length) {
    const error = new Error('Profile not found');
    error.status = 404;
    throw error;
  }
  return rows[0];
}

async function createAccount(req, res, pool) {
  jwtSecret();
  checkRateLimit(req, 'register');
  const body = await readJson(req);
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  if (!/^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,30}$/.test(email) || password.length < 8 || Buffer.byteLength(password) > 256) {
    send(res, 400, { error: 'Enter a valid email and password with at least 8 characters' });
    return;
  }

  const id = randomUUID();
  const hash = await passwordHash(password);
  const connection = await pool.getConnection();
  try {
    await connection.beginTransaction();
    await connection.query('INSERT INTO accounts (id,email,password_hash) VALUES (?,?,?)', [id, email, hash]);
    await connection.query('INSERT INTO profiles (account_id,profile_index,name,avatar_color) VALUES (?,1,?,?)', [id, 'Профиль 1', '#1E88E5']);
    await connection.commit();
  } catch (error) {
    await connection.rollback();
    if (error.code === 'ER_DUP_ENTRY') {
      send(res, 409, { error: 'Email already registered' });
      return;
    }
    throw error;
  } finally {
    connection.release();
  }
  send(res, 200, await issueSession({ id, email, created_at: new Date() }, pool));
}

async function signIn(req, res, pool) {
  jwtSecret();
  checkRateLimit(req, 'login');
  const body = await readJson(req);
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  if (email.length > 254 || password.length > 256) {
    send(res, 401, { error: 'Invalid email or password' });
    return;
  }
  const rows = await pool.query('SELECT id,email,password_hash,created_at FROM accounts WHERE email=? AND disabled=0 LIMIT 1', [email]);
  const valid = rows.length ? await passwordMatches(password, rows[0].password_hash) : (await scrypt(password, Buffer.alloc(16), 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }), false);
  if (!valid) {
    send(res, 401, { error: 'Invalid email or password' });
    return;
  }
  send(res, 200, await issueSession(rows[0], pool));
}

async function refreshSession(req, res, pool) {
  checkRateLimit(req, 'refresh');
  const body = await readJson(req);
  const refreshToken = typeof body.refresh_token === 'string' ? body.refresh_token : '';
  if (!/^[A-Za-z0-9_-]{40,100}$/.test(refreshToken)) {
    send(res, 401, { message: 'Invalid refresh token', error: 'invalid_grant' });
    return;
  }
  const rows = await pool.query(
    'SELECT a.id,a.email,a.created_at,s.session_id FROM account_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.refresh_token_hash=? AND s.expires_at>UTC_TIMESTAMP() AND a.disabled=0',
    [tokenHash(refreshToken)]
  );
  if (!rows.length) {
    send(res, 401, { message: 'Invalid refresh token', error: 'invalid_grant' });
    return;
  }
  send(res, 200, await issueSession(rows[0], pool, rows[0].session_id));
}

async function syncProfile(req, res, url, account, pool) {
  const parts = /^\/api\/v1\/profiles\/([1-6])\/sync$/.exec(url.pathname);
  if (!parts) return false;
  const profileIndex = validProfileIndex(parts[1]);
  await requireProfile(account.id, profileIndex, pool);

  if (req.method === 'GET') {
    const cursor = Math.max(0, Number(url.searchParams.get('cursor') || 0));
    const limit = Math.min(500, Math.max(1, Number(url.searchParams.get('limit') || 200)));
    if (!Number.isSafeInteger(cursor) || !Number.isInteger(limit)) {
      send(res, 400, { error: 'Invalid cursor or limit' });
      return true;
    }
    const rows = await pool.query(
      'SELECT event_id AS eventId, mutation_id AS mutationId, entity_type AS entityType, operation, item_key AS itemKey, payload, created_at AS createdAt FROM sync_events WHERE account_id=? AND profile_index=? AND event_id>? ORDER BY event_id ASC LIMIT ?',
      [account.id, profileIndex, cursor, limit]
    );
    send(res, 200, { events: rows, cursor: rows.length ? Number(rows[rows.length - 1].eventId) : cursor });
    return true;
  }

  if (req.method !== 'POST') {
    send(res, 405, { error: 'Method not allowed' });
    return true;
  }
  const body = await readJson(req);
  if (!Array.isArray(body.mutations) || body.mutations.length > 200) {
    send(res, 400, { error: 'mutations must be an array of at most 200 entries' });
    return true;
  }
  const connection = await pool.getConnection();
  let applied = 0;
  try {
    await connection.beginTransaction();
    for (const mutation of body.mutations) {
      const mutationId = typeof mutation?.mutationId === 'string' ? mutation.mutationId : '';
      const entityType = mutation?.entityType;
      const operation = mutation?.operation;
      const itemKey = typeof mutation?.itemKey === 'string' ? mutation.itemKey : '';
      const payload = mutation?.payload ?? null;
      if (!/^[0-9a-f-]{36}$/i.test(mutationId) || !['progress','watched','library'].includes(entityType) ||
          !['upsert','delete'].includes(operation) || itemKey.length < 1 || itemKey.length > 255 ||
          (operation === 'upsert' && (!payload || typeof payload !== 'object')) ||
          Buffer.byteLength(JSON.stringify(payload)) > 65_536) {
        const error = new Error('Invalid mutation');
        error.status = 400;
        throw error;
      }

      const inserted = await connection.query(
        'INSERT IGNORE INTO sync_mutations (account_id,profile_index,mutation_id) VALUES (?,?,?)',
        [account.id, profileIndex, mutationId]
      );
      if (inserted.affectedRows === 0) continue;

      await connection.query(
        'INSERT INTO sync_items (account_id,profile_index,entity_type,item_key,payload,is_deleted) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload),is_deleted=VALUES(is_deleted),updated_at=UTC_TIMESTAMP()',
        [account.id, profileIndex, entityType, itemKey, operation === 'delete' ? null : JSON.stringify(payload), operation === 'delete']
      );
      const event = await connection.query(
        'INSERT INTO sync_events (account_id,profile_index,mutation_id,entity_type,operation,item_key,payload) VALUES (?,?,?,?,?,?,?)',
        [account.id, profileIndex, mutationId, entityType, operation, itemKey, operation === 'delete' ? null : JSON.stringify(payload)]
      );
      await connection.query(
        'UPDATE sync_mutations SET event_id=? WHERE account_id=? AND mutation_id=?',
        [event.insertId, account.id, mutationId]
      );
      applied++;
    }
    await connection.commit();
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
  const rows = await pool.query('SELECT COALESCE(MAX(event_id),0) AS cursor FROM sync_events WHERE account_id=? AND profile_index=?', [account.id, profileIndex]);
  send(res, 200, { applied, cursor: Number(rows[0].cursor) });
  return true;
}

function decodeJson(value) {
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch { return null; }
}

function watchedKey(item) {
  return [item.content_id || '', item.season ?? '', item.episode ?? ''].join('|');
}

function libraryKey(item) {
  return [item.content_type || '', item.content_id || ''].join('|').toLowerCase();
}

async function writeSyncItem(connection, accountId, profileIndex, entityType, itemKey, operation, payload, originClientId = null) {
  const current = await connection.query(
    'SELECT payload,is_deleted FROM sync_items WHERE account_id=? AND profile_index=? AND entity_type=? AND item_key=?',
    [accountId, profileIndex, entityType, itemKey]
  );
  const currentPayload = decodeJson(current[0]?.payload);
  if (operation === 'delete' && current[0]?.is_deleted) return null;
  if (operation === 'upsert' && current[0] && !current[0].is_deleted &&
      JSON.stringify(currentPayload) === JSON.stringify(payload)) return null;

  const mutationId = randomUUID();
  await connection.query(
    'INSERT INTO sync_mutations (account_id,profile_index,mutation_id) VALUES (?,?,?)',
    [accountId, profileIndex, mutationId]
  );

  let eventPayload = payload;
  if (operation === 'delete') {
    eventPayload = currentPayload ?? null;
    await connection.query(
      'INSERT INTO sync_items (account_id,profile_index,entity_type,item_key,payload,is_deleted) VALUES (?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE is_deleted=TRUE,updated_at=UTC_TIMESTAMP()',
      [accountId, profileIndex, entityType, itemKey, null]
    );
  } else {
    await connection.query(
      'INSERT INTO sync_items (account_id,profile_index,entity_type,item_key,payload,is_deleted) VALUES (?,?,?,?,?,FALSE) ON DUPLICATE KEY UPDATE payload=VALUES(payload),is_deleted=FALSE,updated_at=UTC_TIMESTAMP()',
      [accountId, profileIndex, entityType, itemKey, JSON.stringify(payload)]
    );
  }

  const event = await connection.query(
    'INSERT INTO sync_events (account_id,profile_index,mutation_id,entity_type,operation,item_key,origin_client_id,payload) VALUES (?,?,?,?,?,?,?,?)',
    [accountId, profileIndex, mutationId, entityType, operation, itemKey, originClientId, eventPayload == null ? null : JSON.stringify(eventPayload)]
  );
  await connection.query(
    'UPDATE sync_mutations SET event_id=? WHERE account_id=? AND mutation_id=?',
    [event.insertId, accountId, mutationId]
  );
  return event.insertId;
}

async function syncBatch(pool, account, profileIndex, work) {
  await requireProfile(account.id, profileIndex, pool);
  const connection = await pool.getConnection();
  try {
    await connection.beginTransaction();
    const result = await work(connection);
    await connection.commit();
    return result;
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
}

async function syncCursor(pool, account, profileIndex, entityType) {
  const rows = await pool.query(
    'SELECT COALESCE(MAX(event_id),0) AS cursor FROM sync_events WHERE account_id=? AND profile_index=? AND entity_type=?',
    [account.id, profileIndex, entityType]
  );
  return Number(rows[0].cursor);
}

async function syncDelta(pool, account, profileIndex, entityType, cursor, limit) {
  const rows = await pool.query(
    'SELECT event_id,operation,item_key,payload FROM sync_events WHERE account_id=? AND profile_index=? AND entity_type=? AND event_id>? ORDER BY event_id ASC LIMIT ?',
    [account.id, profileIndex, entityType, cursor, limit]
  );
  return rows.map(row => {
    const payload = decodeJson(row.payload) || {};
    return {
      event_id: Number(row.event_id),
      operation: row.operation,
      ...payload,
      ...(entityType === 'progress' ? { progress_key: row.item_key } : {})
    };
  });
}

async function pullSyncItems(pool, account, profileIndex, entityType, limit, offset, since = null) {
  const sql = since == null
    ? 'SELECT item_key,payload FROM sync_items WHERE account_id=? AND profile_index=? AND entity_type=? AND is_deleted=FALSE ORDER BY updated_at DESC,item_key LIMIT ? OFFSET ?'
    : "SELECT item_key,payload FROM sync_items WHERE account_id=? AND profile_index=? AND entity_type=? AND is_deleted=FALSE AND CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.last_watched')) AS UNSIGNED)>? ORDER BY updated_at DESC,item_key LIMIT ?";
  const values = since == null
    ? [account.id, profileIndex, entityType, limit, offset]
    : [account.id, profileIndex, entityType, Math.max(0, Number(since)), limit];
  const rows = await pool.query(sql, values);
  return rows.map(row => {
    const payload = decodeJson(row.payload) || {};
    return entityType === 'progress' ? { ...payload, progress_key: row.item_key, profile_id: profileIndex, user_id: account.id } :
      { ...payload, profile_id: profileIndex, user_id: account.id };
  });
}

async function handleRpc(req, res, name, account, pool) {
  if (req.method !== 'POST') {
    send(res, 405, { code: '405', message: 'POST required' });
    return true;
  }
  const body = await readJson(req);
  const profileIndex = validProfileIndex(body.p_profile_id ?? 1);
  await requireProfile(account.id, profileIndex, pool);
  const origin = typeof body.p_origin_client_id === 'string' ? body.p_origin_client_id.slice(0, 128) : null;

  if (name === 'sync_pull_profiles') {
    const rows = await pool.query(
      'SELECT profile_index,name,avatar_color AS avatar_color_hex,uses_primary_addons,uses_primary_plugins,avatar_id,avatar_url,profile_background_id,profile_background_url,created_at,updated_at FROM profiles WHERE account_id=? ORDER BY profile_index',
      [account.id]
    );
    send(res, 200, rows);
    return true;
  }
  if (name === 'sync_push_profiles') {
    const profiles = Array.isArray(body.p_profiles) ? body.p_profiles.slice(0, MAX_PROFILES) : [];
    await syncBatch(pool, account, 1, async connection => {
      for (const profile of profiles) {
        const index = validProfileIndex(profile.profile_index);
        const name = typeof profile.name === 'string' ? profile.name.trim().slice(0, 48) : '';
        if (!name) continue;
        const color = /^#[0-9A-Fa-f]{6}$/.test(profile.avatar_color_hex || '') ? profile.avatar_color_hex : '#1E88E5';
        await connection.query(
          'INSERT INTO profiles (account_id,profile_index,name,avatar_color,uses_primary_addons,uses_primary_plugins,avatar_id,avatar_url,profile_background_id,profile_background_url) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),avatar_color=VALUES(avatar_color),uses_primary_addons=VALUES(uses_primary_addons),uses_primary_plugins=VALUES(uses_primary_plugins),avatar_id=VALUES(avatar_id),avatar_url=VALUES(avatar_url),profile_background_id=VALUES(profile_background_id),profile_background_url=VALUES(profile_background_url),updated_at=UTC_TIMESTAMP()',
          [account.id,index,name,color,Boolean(profile.uses_primary_addons),Boolean(profile.uses_primary_plugins),profile.avatar_id || null,profile.avatar_url || null,profile.profile_background_id || null,profile.profile_background_url || null]
        );
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_delete_profile_data') {
    if (profileIndex > 1) await pool.query('DELETE FROM profiles WHERE account_id=? AND profile_index=?', [account.id, profileIndex]);
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_pull_profile_locks') {
    const rows = await pool.query('SELECT profile_index,profile_pin_hash,profile_pin_locked_until FROM profiles WHERE account_id=? ORDER BY profile_index', [account.id]);
    send(res, 200, rows.map(row => ({
      profile_index: row.profile_index,
      pin_enabled: Boolean(row.profile_pin_hash),
      pin_locked_until: row.profile_pin_locked_until
    })));
    return true;
  }
  if (name === 'set_profile_pin' || name === 'clear_profile_pin' || name === 'verify_profile_pin') {
    const pin = typeof body.p_pin === 'string' ? body.p_pin : '';
    const rows = await pool.query('SELECT profile_pin_hash,profile_pin_failed_attempts,profile_pin_locked_until FROM profiles WHERE account_id=? AND profile_index=?', [account.id,profileIndex]);
    const row = rows[0];
    if (name === 'set_profile_pin') {
      if (!/^\d{4,8}$/.test(pin)) throw Object.assign(new Error('PIN must contain 4 to 8 digits'), { status: 400 });
      if (row.profile_pin_hash) {
        if (!body.p_current_pin) throw Object.assign(new Error('Current PIN is required'), { status: 400 });
        if (!(await passwordMatches(String(body.p_current_pin),row.profile_pin_hash))) throw Object.assign(new Error('Current PIN is incorrect'), { status: 403 });
      }
      await pool.query('UPDATE profiles SET profile_pin_hash=?,profile_pin_failed_attempts=0,profile_pin_locked_until=NULL WHERE account_id=? AND profile_index=?', [await passwordHash(pin),account.id,profileIndex]);
      send(res, 200, null);
      return true;
    }
    if (name === 'clear_profile_pin') {
      if (row.profile_pin_hash && !body.p_current_pin) throw Object.assign(new Error('Current PIN is required'), { status: 400 });
      if (row.profile_pin_hash && !(await passwordMatches(String(body.p_current_pin),row.profile_pin_hash))) throw Object.assign(new Error('Current PIN is incorrect'), { status: 403 });
      await pool.query('UPDATE profiles SET profile_pin_hash=NULL,profile_pin_failed_attempts=0,profile_pin_locked_until=NULL WHERE account_id=? AND profile_index=?', [account.id,profileIndex]);
      send(res, 200, null);
      return true;
    }
    if (!row.profile_pin_hash) {
      send(res, 200, [{ unlocked: false, retry_after_seconds: 0 }]);
      return true;
    }
    if (row.profile_pin_locked_until && new Date(row.profile_pin_locked_until).getTime() > Date.now()) {
      const retry = Math.ceil((new Date(row.profile_pin_locked_until).getTime()-Date.now())/1000);
      send(res, 200, [{ unlocked: false, retry_after_seconds: retry }]);
      return true;
    }
    if (await passwordMatches(pin,row.profile_pin_hash)) {
      await pool.query('UPDATE profiles SET profile_pin_failed_attempts=0,profile_pin_locked_until=NULL WHERE account_id=? AND profile_index=?', [account.id,profileIndex]);
      send(res, 200, [{ unlocked: true, retry_after_seconds: 0 }]);
      return true;
    }
    await pool.query('UPDATE profiles SET profile_pin_failed_attempts=profile_pin_failed_attempts+1,profile_pin_locked_until=IF(profile_pin_failed_attempts+1>=5,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 SECOND),NULL) WHERE account_id=? AND profile_index=?', [account.id,profileIndex]);
    send(res, 200, [{ unlocked: false, retry_after_seconds: 0 }]);
    return true;
  }

  const cursorMatch = {
    sync_get_watch_progress_delta_cursor: 'progress',
    sync_get_watched_items_delta_cursor: 'watched',
    sync_get_library_delta_cursor: 'library'
  }[name];
  if (cursorMatch) {
    send(res, 200, await syncCursor(pool, account, profileIndex, cursorMatch));
    return true;
  }
  const deltaType = {
    sync_pull_watch_progress_delta: 'progress',
    sync_pull_watched_items_delta: 'watched',
    sync_pull_library_delta: 'library'
  }[name];
  if (deltaType) {
    const cursor = Math.max(0, Number(body.p_since_event_id) || 0);
    const limit = Math.min(1000, Math.max(1, Number(body.p_limit) || 200));
    send(res, 200, await syncDelta(pool, account, profileIndex, deltaType, cursor, limit));
    return true;
  }

  if (name === 'sync_push_watch_progress') {
    const entries = Array.isArray(body.p_entries) ? body.p_entries.slice(0, 200) : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const entry of entries) {
        const key = typeof entry.progress_key === 'string' ? entry.progress_key.slice(0, 255) : '';
        if (key) await writeSyncItem(connection, account.id, profileIndex, 'progress', key, 'upsert', entry, origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_delete_watch_progress') {
    const keys = Array.isArray(body.p_keys) ? body.p_keys : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const key of keys.slice(0, 200)) {
        if (typeof key === 'string' && key.length <= 255) await writeSyncItem(connection, account.id, profileIndex, 'progress', key, 'delete', null, origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_pull_watch_progress') {
    const since = body.p_since_last_watched == null ? null : Number(body.p_since_last_watched);
    const limit = Math.min(2000, Math.max(1, Number(body.p_limit) || 1000));
    send(res, 200, await pullSyncItems(pool, account, profileIndex, 'progress', limit, 0, since));
    return true;
  }

  if (name === 'sync_push_watched_items') {
    const items = Array.isArray(body.p_items) ? body.p_items.slice(0, 200) : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const item of items) {
        const key = watchedKey(item);
        if (key.length <= 255 && item.content_id) await writeSyncItem(connection, account.id, profileIndex, 'watched', key, 'upsert', item, origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_delete_watched_items') {
    const keys = Array.isArray(body.p_keys) ? body.p_keys : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const item of keys.slice(0, 200)) {
        if (item && typeof item.content_id === 'string') await writeSyncItem(connection, account.id, profileIndex, 'watched', watchedKey(item), 'delete', null, origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_pull_watched_items') {
    const page = Math.min(10000, Math.max(1, Number(body.p_page) || 1));
    const pageSize = Math.min(1000, Math.max(1, Number(body.p_page_size) || 900));
    send(res, 200, await pullSyncItems(pool, account, profileIndex, 'watched', pageSize, (page - 1) * pageSize));
    return true;
  }

  if (name === 'sync_push_library_items') {
    const items = Array.isArray(body.p_items) ? body.p_items.slice(0, 200) : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const item of items) {
        const key = libraryKey(item);
        if (key.length <= 255 && item.content_id) await writeSyncItem(connection, account.id, profileIndex, 'library', key, 'upsert', item, origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_delete_library_items') {
    const keys = Array.isArray(body.p_keys) ? body.p_keys : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const item of keys.slice(0, 200)) {
        if (item && typeof item.content_id === 'string') await writeSyncItem(connection, account.id, profileIndex, 'library', libraryKey(item), 'delete', null, origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_pull_library') {
    const limit = Math.min(2000, Math.max(1, Number(body.p_limit) || 1000));
    const offset = Math.max(0, Number(body.p_offset) || 0);
    send(res, 200, await pullSyncItems(pool, account, profileIndex, 'library', limit, offset));
    return true;
  }
  if (name === 'sync_push_collections' || name === 'sync_pull_collections' ||
      name === 'sync_push_profile_settings_blob' || name === 'sync_pull_profile_settings_blob' ||
      name === 'sync_push_home_catalog_settings' || name === 'sync_pull_home_catalog_settings') {
    const definitions = {
      sync_push_collections: ['collections', 'p_collections_json', 'collections_json'],
      sync_pull_collections: ['collections', null, 'collections_json'],
      sync_push_profile_settings_blob: ['profile_settings', 'p_settings_json', 'settings_json'],
      sync_pull_profile_settings_blob: ['profile_settings', null, 'settings_json'],
      sync_push_home_catalog_settings: ['home_catalog_settings', 'p_settings_json', 'settings_json'],
      sync_pull_home_catalog_settings: ['home_catalog_settings', null, 'settings_json']
    };
    const [blobType, inputName, outputName] = definitions[name];
    const platform = typeof body.p_platform === 'string' ? body.p_platform.slice(0,40) : 'tv';
    if (inputName) {
      const payload = body[inputName] ?? (blobType === 'collections' ? [] : {});
      await pool.query(
        'INSERT INTO account_blobs (account_id,profile_index,blob_type,platform,payload) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload),updated_at=UTC_TIMESTAMP()',
        [account.id,profileIndex,blobType,platform,JSON.stringify(payload)]
      );
      send(res, 200, null);
    } else {
      const rows = await pool.query(
        'SELECT profile_index AS profile_id,payload,updated_at FROM account_blobs WHERE account_id=? AND profile_index=? AND blob_type=? AND platform=? LIMIT 1',
        [account.id,profileIndex,blobType,platform]
      );
      send(res, 200, rows.map(row => ({ profile_id: row.profile_id, [outputName]: decodeJson(row.payload), updated_at: row.updated_at })));
    }
    return true;
  }
  if (name === 'sync_push_addons' || name === 'sync_push_plugins') {
    const entityType = name === 'sync_push_addons' ? 'addon' : 'plugin';
    const field = name === 'sync_push_addons' ? 'p_addons' : 'p_plugins';
    const entries = Array.isArray(body[field]) ? body[field].slice(0,200) : [];
    await syncBatch(pool, account, profileIndex, async connection => {
      for (const entry of entries) {
        const key = typeof entry.url === 'string' ? entry.url.trim().slice(0,255) : '';
        if (key) await writeSyncItem(connection,account.id,profileIndex,entityType,key,'upsert',entry,origin);
      }
    });
    send(res, 200, null);
    return true;
  }
  if (name === 'get_sync_overview') {
    const rows = await pool.query(
      'SELECT p.profile_index,p.name,p.avatar_color, ' +
      'SUM(s.entity_type=\'addon\' AND s.is_deleted=FALSE) AS addons, ' +
      'SUM(s.entity_type=\'plugin\' AND s.is_deleted=FALSE) AS plugins, ' +
      'SUM(s.entity_type=\'library\' AND s.is_deleted=FALSE) AS library_items, ' +
      'SUM(s.entity_type=\'progress\' AND s.is_deleted=FALSE) AS watch_progress, ' +
      'SUM(s.entity_type=\'watched\' AND s.is_deleted=FALSE) AS watched_items ' +
      'FROM profiles p LEFT JOIN sync_items s ON s.account_id=p.account_id AND s.profile_index=p.profile_index ' +
      'WHERE p.account_id=? GROUP BY p.profile_index,p.name,p.avatar_color ORDER BY p.profile_index',
      [account.id]
    );
    const byProfile = field => Object.fromEntries(rows.map(row => [String(row.profile_index), Number(row[field] || 0)]));
    const profiles = Object.fromEntries(rows.map(row => [String(row.profile_index), { name: row.name, color: row.avatar_color }]));
    send(res, 200, {
      addons: byProfile('addons'),
      plugins: byProfile('plugins'),
      library_items: byProfile('library_items'),
      watch_progress: byProfile('watch_progress'),
      watched_items: byProfile('watched_items'),
      profiles
    });
    return true;
  }
  if (name === 'register_current_device') {
    // Device inventory is not needed for profile-scoped sync yet.
    send(res, 200, null);
    return true;
  }
  if (name === 'get_sync_owner') {
    send(res, 200, account.id);
    return true;
  }
  if (name === 'sync_seed_provider_credentials' || name === 'sync_push_provider_credentials') {
    // Credentials remain local until encrypted-at-rest storage is configured.
    send(res, 200, null);
    return true;
  }
  if (name === 'sync_pull_provider_credentials') {
    send(res, 200, []);
    return true;
  }
  if (name === 'get_avatar_catalog' || name === 'get_member_profile_avatar_catalog' ||
      name === 'get_member_profile_background_catalog' || name === 'get_my_member_access' ||
      name === 'get_my_membership_overview') {
    send(res, 200, []);
    return true;
  }

  send(res, 404, { code: 'PGRST202', message: 'Function not found' });
  return true;
}

export async function handleAccountRequest(req, res, url, pool) {
  const route = url.pathname;
  try {
    if (route === '/auth/v1/signup' && req.method === 'POST') {
      requireApiKey(req);
      await createAccount(req, res, pool);
      return true;
    }
    if (route === '/auth/v1/token' && req.method === 'POST') {
      requireApiKey(req);
      if (url.searchParams.get('grant_type') === 'password') await signIn(req, res, pool);
      else if (url.searchParams.get('grant_type') === 'refresh_token') await refreshSession(req, res, pool);
      else send(res, 400, { message: 'Unsupported grant type', error: 'unsupported_grant_type' });
      return true;
    }
    if (route === '/auth/v1/user' && req.method === 'GET') {
      requireApiKey(req);
      const account = await requireAccount(req, pool);
      send(res, 200, authUser(account));
      return true;
    }
    if (route === '/auth/v1/logout' && req.method === 'POST') {
      requireApiKey(req);
      const account = await requireAccount(req, pool);
      await pool.query('DELETE FROM account_sessions WHERE session_id=? AND account_id=?', [account.session_id, account.id]);
      sendNoContent(res);
      return true;
    }
    if (route === '/rest/v1/addons' || route === '/rest/v1/plugins') {
      requireApiKey(req);
      if (req.method !== 'GET') {
        send(res, 405, { code: '405', message: 'GET required' });
        return true;
      }
      const account = await requireAccount(req, pool);
      const profileIndex = validProfileIndex(url.searchParams.get('profile_id') || 1);
      if (url.searchParams.get('user_id') && url.searchParams.get('user_id') !== account.id) {
        send(res, 200, []);
        return true;
      }
      await requireProfile(account.id, profileIndex, pool);
      const entityType = route.endsWith('/addons') ? 'addon' : 'plugin';
      const rows = await pool.query(
        'SELECT payload FROM sync_items WHERE account_id=? AND profile_index=? AND entity_type=? AND is_deleted=FALSE ORDER BY updated_at,item_key',
        [account.id, profileIndex, entityType]
      );
      send(res, 200, rows.map(row => ({ ...(decodeJson(row.payload) || {}), user_id: account.id, profile_id: profileIndex })));
      return true;
    }
    if (route.startsWith('/rest/v1/rpc/')) {
      requireApiKey(req);
      const account = await requireAccount(req, pool);
      return await handleRpc(req, res, route.slice('/rest/v1/rpc/'.length), account, pool);
    }
    if (route === '/api/v1/auth/register' && req.method === 'POST') {
      await createAccount(req, res, pool);
      return true;
    }
    if (route === '/api/v1/auth/login' && req.method === 'POST') {
      await signIn(req, res, pool);
      return true;
    }
    if (route === '/api/v1/auth/logout' && req.method === 'POST') {
      const token = bearer(req);
      if (token) { const claims = jwtVerify(token); await pool.query('DELETE FROM account_sessions WHERE session_id=? AND account_id=?', [claims.session_id, claims.sub]); }
      send(res, 200, { ok: true });
      return true;
    }
    if (route.startsWith('/api/v1/auth/') || route === '/api/v1/profiles' || route.startsWith('/api/v1/profiles/')) {
      const account = await requireAccount(req, pool);
      if (route === '/api/v1/auth/me' && req.method === 'GET') {
        send(res, 200, { account });
        return true;
      }
      if (route === '/api/v1/profiles' && req.method === 'GET') {
        const profiles = await pool.query(
          'SELECT profile_index AS id,name,avatar_color AS avatarColor,created_at AS createdAt,updated_at AS updatedAt FROM profiles WHERE account_id=? ORDER BY profile_index',
          [account.id]
        );
        send(res, 200, { profiles });
        return true;
      }
      if (route === '/api/v1/profiles' && req.method === 'POST') {
        const body = await readJson(req);
        const name = typeof body.name === 'string' ? body.name.trim().slice(0, 48) : '';
        const color = typeof body.avatarColor === 'string' && /^#[0-9A-Fa-f]{6}$/.test(body.avatarColor) ? body.avatarColor : '#1E88E5';
        if (!name) {
          send(res, 400, { error: 'Profile name is required' });
          return true;
        }
        const connection = await pool.getConnection();
        let profile;
        try {
          await connection.beginTransaction();
          await connection.query('SELECT id FROM accounts WHERE id=? FOR UPDATE', [account.id]);
          const rows = await connection.query('SELECT profile_index FROM profiles WHERE account_id=? ORDER BY profile_index', [account.id]);
          if (rows.length >= MAX_PROFILES) {
            await connection.rollback();
            send(res, 409, { error: 'Profile limit reached' });
            return true;
          }
          const used = new Set(rows.map(row => Number(row.profile_index)));
          const index = Array.from({ length: MAX_PROFILES }, (_, i) => i + 1).find(value => !used.has(value));
          await connection.query('INSERT INTO profiles (account_id,profile_index,name,avatar_color) VALUES (?,?,?,?)', [account.id,index,name,color]);
          await connection.commit();
          profile = await requireProfile(account.id,index,pool);
        } catch (error) {
          await connection.rollback();
          throw error;
        } finally {
          connection.release();
        }
        send(res, 201, { profile });
        return true;
      }
      if (/^\/api\/v1\/profiles\/[1-6]\/sync$/.test(route)) {
        return await syncProfile(req, res, url, account, pool);
      }
      const profileMatch = /^\/api\/v1\/profiles\/([1-6])$/.exec(route);
      if (profileMatch && req.method === 'PUT') {
        const profileIndex = validProfileIndex(profileMatch[1]);
        await requireProfile(account.id, profileIndex, pool);
        const body = await readJson(req);
        const name = typeof body.name === 'string' ? body.name.trim().slice(0, 48) : '';
        const color = typeof body.avatarColor === 'string' && /^#[0-9A-Fa-f]{6}$/.test(body.avatarColor) ? body.avatarColor : '#1E88E5';
        if (!name) {
          send(res, 400, { error: 'Profile name is required' });
          return true;
        }
        await pool.query('UPDATE profiles SET name=?,avatar_color=?,updated_at=UTC_TIMESTAMP() WHERE account_id=? AND profile_index=?', [name,color,account.id,profileIndex]);
        send(res, 200, { profile: await requireProfile(account.id,profileIndex,pool) });
        return true;
      }
      if (profileMatch && req.method === 'DELETE') {
        const profileIndex = validProfileIndex(profileMatch[1]);
        if (profileIndex === 1) {
          send(res, 409, { error: 'The primary profile cannot be deleted' });
          return true;
        }
        await pool.query('DELETE FROM profiles WHERE account_id=? AND profile_index=?', [account.id,profileIndex]);
        send(res, 200, { ok: true });
        return true;
      }
      send(res, 404, { error: 'Not found' });
      return true;
    }
  } catch (error) {
    const status = error.status || 503;
    const body = route.startsWith('/rest/v1/rpc/') ? { code: String(status), message: error.status ? error.message : 'Service temporarily unavailable' } : { error: error.status ? error.message : 'Service temporarily unavailable' };
    send(res, status, body);
    if (!error.status) console.error('Account API failure', error?.code || error?.name || 'Error');
    return true;
  }
  return false;
}
