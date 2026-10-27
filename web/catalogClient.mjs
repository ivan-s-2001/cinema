/** Lazy TV/mobile catalogue reader. Only fetches a full shard when it is opened. */
export function createCatalogClient(baseUrl, fetcher = fetch) {
  const base = baseUrl.replace(/\/+$/, "");
  let manifestPromise;
  let searchPromise;
  const shardCache = new Map();

  async function get(path) {
    if (!/^(index|search)\.json$|^shards\/(movie|tv)-\d+\.json$/.test(path)) {
      throw new Error("Invalid catalogue path");
    }
    const response = await fetcher(`${base}/${path}`);
    if (!response.ok) throw new Error(`Catalogue HTTP ${response.status}`);
    return response.json();
  }

  const index = () => (manifestPromise ??= get("index.json").catch(e => {
    manifestPromise = undefined;
    throw e;
  }));
  const allTitles = () => (searchPromise ??= get("search.json").catch(e => {
    searchPromise = undefined;
    throw e;
  }));

  async function shard(path) {
    if (shardCache.has(path)) {
      const cached = shardCache.get(path);
      shardCache.delete(path);
      shardCache.set(path, cached);
      return cached;
    }
    const pending = get(path).catch(error => {
      shardCache.delete(path);
      throw error;
    });
    shardCache.set(path, pending);
    if (shardCache.size > 4) shardCache.delete(shardCache.keys().next().value);
    return pending;
  }

  function normalized(value) {
    return String(value || "").normalize("NFKD").toLocaleLowerCase()
      .replace(/\p{M}/gu, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
  }

  return {
    home: () => index().then(data => data.home),
    filters: () => index().then(data => data.filters),
    async browse(type, page = 0) {
      if (!Number.isInteger(page) || page < 0 || !["movie", "tv"].includes(type)) return [];
      const parts = (await index()).shards.filter(item => item.type === type);
      return parts[page] ? shard(parts[page].path) : [];
    },
    async search(query, limit = 30) {
      const needle = normalized(query);
      if (needle.length < 2) return [];
      const entries = await allTitles();
      return entries.map(item => {
        const matches = item.aliases.map(normalized);
        const score = matches.some(x => x === needle) ? 3
          : matches.some(x => x.startsWith(needle)) ? 2
          : matches.some(x => x.includes(needle)) ? 1 : 0;
        return { item, score };
      }).filter(x => x.score).sort((a, b) => b.score - a.score)
        .slice(0, Math.max(0, Math.min(100, limit))).map(x => x.item);
    },
    async detail(item) {
      const entry = typeof item === "string"
        ? (await allTitles()).find(x => x.id === item)
        : item;
      if (!entry?.shard) return null;
      const cards = await shard(entry.shard);
      return cards.find(x => {
        const source = ["tmdb", "kinopoisk", "imdb", "myAnimeList", "shikimori"]
          .find(name => x.ids[name]);
        const suffix = x.collisionSuffix ? `:${x.collisionSuffix}` : "";
        return source && `${x.type}:${source}:${x.ids[source]}${suffix}` === entry.id;
      }) || null;
    },
  };
}
