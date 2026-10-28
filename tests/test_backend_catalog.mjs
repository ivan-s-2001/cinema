import test from 'node:test';
import assert from 'node:assert/strict';
import { handleDiscovery } from '../backend/discovery.mjs';
import { handleSearch } from '../backend/search.mjs';
const url = path => new URL(path, 'https://cinemahome.duckdns.org');
function capture() {
  const result = {};
  return [result, (_res, status, body) => Object.assign(result, { status, body })];
}
test('search includes original names and alias array with parameter binding', async () => {
  const [out, reply] = capture();
  await handleSearch({}, url('/api/v1/search?q=Fight%20Club&type=movie'), async (sql, params) => {
    assert.match(sql, /JSON_SEARCH/);
    assert.match(sql, /\$\.titleEn/);
    assert.match(sql, /\$\.originalTitle/);
    assert.match(sql, /\$\.aliases\[\*\]/);
    assert.doesNotMatch(sql, /Fight Club/);
    assert.deepEqual(params, [...Array(5).fill('%fight club%'), 'movie']);
    return [{ id: 'ch:snapshot:movie:tmdb:550' }];
  }, reply);
  assert.equal(out.status, 200);
  assert.equal(out.body.items[0].id, 'ch:snapshot:movie:tmdb:550');
});
test('search treats wildcard characters as literal text', async () => {
  const [out, reply] = capture();
  await handleSearch({}, url('/api/v1/search?q=100%25_!'), async (_sql, args) => {
    assert.equal(args[0], '%100!%!_!!%');
    return [];
  }, reply);
  assert.equal(out.status, 200);
});
test('invalid searches never reach the database', async () => {
  for (const path of ['/api/v1/search?q=a', '/api/v1/search?q=ok&type=tv', '/api/v1/search?q=' + 'a'.repeat(121)]) {
    const [out, reply] = capture();
    await handleSearch({}, url(path), () => assert.fail('database called'), reply);
    assert.equal(out.status, 400);
  }
});
test('discover avoids filter aggregation and uses lookahead for hasMore', async () => {
  const [out, reply] = capture();
  const pool = { query: async (sql, args) => {
    assert.match(sql, /WHERE/);
    assert.match(sql, /updated_at DESC,id ASC/);
    assert.deepEqual(args, ['movie', 3, 2]);
    return [1, 2, 3].map(id => ({ id: String(id), kind: 'movie', title: 'Test', payload: {} }));
  } };
  await handleDiscovery({ method: 'GET' }, {}, url('/api/v1/discover?type=movie&limit=2&page=2'), pool, reply);
  assert.equal(out.status, 200);
  assert.equal(out.body.items.length, 2);
  assert.equal(out.body.hasMore, true);
});
test('a full final page has no phantom next page', async () => {
  const [out, reply] = capture();
  await handleDiscovery({ method: 'GET' }, {}, url('/api/v1/discover?limit=2'), {
    query: async () => [{ id: 'a' }, { id: 'b' }]
  }, reply);
  assert.equal(out.body.hasMore, false);
});
test('invalid year filters fail before any database work', async () => {
  for (const query of ['yearFrom=abc', 'yearTo=Infinity', 'yearFrom=2025&yearTo=2000', 'yearFrom=1800']) {
    const [out, reply] = capture();
    await handleDiscovery({ method: 'GET' }, {}, url('/api/v1/discover?' + query), {
      query: () => assert.fail('database called')
    }, reply);
    assert.equal(out.status, 400);
  }
});
test('database failure returns controlled discovery error', async () => {
  const [out, reply] = capture();
  await handleDiscovery({ method: 'GET' }, {}, url('/api/v1/discover'), {
    query: async () => { throw new Error('unavailable'); }
  }, reply);
  assert.equal(out.status, 503);
});
test('filters remain available after separating discovery query', async () => {
  const [out, reply] = capture();
  await handleDiscovery({ method: 'GET' }, {}, url('/api/v1/filters'), {
    query: async () => [{ kind: 'movie', year: 2000, payload: { genres: ['Драма'], countries: ['США'] } }]
  }, reply);
  assert.equal(out.status, 200);
  assert.deepEqual(out.body.genres, [{ name: 'Драма', count: 1 }]);
});
