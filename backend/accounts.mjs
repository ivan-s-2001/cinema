import { randomBytes, randomUUID, scrypt as scryptCallback, createHash, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCallback);
const MAX_BODY_BYTES = 1_048_576;
const MAX_PROFILES = 6;
const SESSION_DAYS = 30;
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
  const match = /^Bearer ([A-Za-z0-9_-]{40,100})$/i.exec(req.headers.authorization || '');
  return match?.[1] || null;
}

async function requireAccount(req, pool) {
  const token = bearer(req);
  if (!token) {
    const error = new Error('Authentication required');
    error.status = 401;
    throw error;
  }
  const rows = await pool.query(
    'SELECT a.id, a.email FROM account_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=? AND s.expires_at > UTC_TIMESTAMP() AND a.disabled=0',
    [tokenHash(token)]
  );
  if (!rows.length) {
    const error = new Error('Session expired');
    error.status = 401;
    throw error;
  }
  return rows[0];
}

async function issueSession(account, pool) {
  const token = randomBytes(32).toString('base64url');
  await pool.query('DELETE FROM account_sessions WHERE expires_at <= UTC_TIMESTAMP()');
  await pool.query(
    'INSERT INTO account_sessions (token_hash,account_id,expires_at) VALUES (?,?,DATE_ADD(UTC_TIMESTAMP(), INTERVAL 30 DAY))',
    [tokenHash(token), account.id]
  );
  return { token, expiresIn: SESSION_DAYS * 86400, account: { id: account.id, email: account.email } };
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
  send(res, 201, await issueSession({ id, email }, pool));
}

async function signIn(req, res, pool) {
  checkRateLimit(req, 'login');
  const body = await readJson(req);
  const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
  const password = typeof body.password === 'string' ? body.password : '';
  if (email.length > 254 || password.length > 256) {
    send(res, 401, { error: 'Invalid email or password' });
    return;
  }
  const rows = await pool.query('SELECT id,email,password_hash FROM accounts WHERE email=? AND disabled=0 LIMIT 1', [email]);
  const valid = rows.length ? await passwordMatches(password, rows[0].password_hash) : (await scrypt(password, Buffer.alloc(16), 64, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }), false);
  if (!valid) {
    send(res, 401, { error: 'Invalid email or password' });
    return;
  }
  send(res, 200, await issueSession(rows[0], pool));
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
        'INSERT IGNORE INTO sync_mutations (account_id,mutation_id) VALUES (?,?)',
        [account.id, mutationId]
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

export async function handleAccountRequest(req, res, url, pool) {
  const route = url.pathname;
  try {
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
      if (token) await pool.query('DELETE FROM account_sessions WHERE token_hash=?', [tokenHash(token)]);
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
    send(res, error.status || 503, { error: error.status ? error.message : 'Service temporarily unavailable' });
    if (!error.status) console.error('Account API failure', error?.code || error?.name || 'Error');
    return true;
  }
  return false;
}
