// Match display names and aliases, never descriptions or external IDs.
export async function handleSearch(res, url, query, reply) {
  if (url.pathname !== '/api/v1/search') return false;
  const q = (url.searchParams.get('q') || '').trim();
  const kind = url.searchParams.get('type');
  if (q.length < 2 || q.length > 120 || (kind && !['movie', 'series'].includes(kind))) {
    reply(res, 400, { error: 'Invalid search query or type' });
    return true;
  }
  const pattern = '%' + q.toLowerCase().replace(/[!%_]/g, char => '!' + char) + '%';
  const names = ['title', ...['titleRu', 'titleEn', 'originalTitle'].map(
    field => `JSON_UNQUOTE(JSON_EXTRACT(payload,'$.${field}'))`
  )];
  const conditions = names.map(name => `LOWER(${name}) LIKE ? ESCAPE '!'`);
  conditions.push("JSON_SEARCH(LOWER(payload), 'one', ?, '!', '$.aliases[*]') IS NOT NULL");
  const rows = await query(
    'SELECT id,kind,title,year,poster,backdrop FROM titles WHERE (' + conditions.join(' OR ') + ')' +
      (kind ? ' AND kind = ?' : '') + ' ORDER BY year DESC,id ASC LIMIT 40',
    [...names.map(() => pattern), pattern, ...(kind ? [kind] : [])]
  );
  reply(res, 200, { items: rows });
  return true;
}
