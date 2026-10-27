import test from 'node:test';
import assert from 'node:assert/strict';
import { createCatalogClient } from '../web/catalogClient.mjs';

const film = { id: 'movie:tmdb:550', type: 'movie', ids: { tmdb: '550', imdb: 'tt0137523' },
  title: 'Бойцовский клуб', aliases: ['Бойцовский клуб', 'Fight Club'], shard: 'shards/movie-001.json' };
const files = {
  'index.json': { home: { movies: [film] }, filters: {}, shards: [{ type: 'movie', path: 'shards/movie-001.json' }] },
  'search.json': [film],
  'shards/movie-001.json': [{ ...film, details: { cast: ['Brad Pitt'] } }],
};

test('loads small home first, search and full detail on demand, with a shard cache', async () => {
  const calls = [];
  const fetcher = async url => {
    const path = url.split('/catalog/')[1];
    calls.push(path);
    return { ok: true, json: async () => files[path] };
  };
  const client = createCatalogClient('https://example.org/catalog/', fetcher);
  assert.equal((await client.home()).movies[0].title, 'Бойцовский клуб');
  assert.deepEqual(calls, ['index.json']);
  assert.equal((await client.search('FIGHT CLUB'))[0].id, film.id);
  assert.equal((await client.search('бойцовский'))[0].id, film.id);
  assert.equal((await client.browse('movie', 0))[0].id, film.id);
  assert.equal((await client.detail(film.id)).details.cast[0], 'Brad Pitt');
  assert.deepEqual(calls, ['index.json', 'search.json', 'shards/movie-001.json']);
  assert.deepEqual(await client.browse('tv', 0), []);
});

test('failed responses are retried and unsafe paths never fetched', async () => {
  let calls = 0;
  const client = createCatalogClient('https://example.org/catalog', async () => {
    calls += 1;
    return { ok: calls > 1, status: 503, json: async () => files['index.json'] };
  });
  await assert.rejects(() => client.home(), /HTTP 503/);
  assert.equal((await client.home()).movies.length, 1);
  assert.deepEqual(await client.browse('../secret', 0), []);
  assert.equal(calls, 2);
});
