"""Build a lightweight, public catalogue snapshot without exposing provider credentials.

KinoAPI supplies stable cross-provider IDs and Russian metadata. Optional APIs
fill gaps only for a bounded number of items; no source is treated as playable.
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


KINO = "https://kinoapi.lampac.dev/api/v1"
TMDB = "https://api.themoviedb.org/3"
ANILIST = "https://graphql.anilist.co"
CINEMETA = "https://v3-cinemeta.strem.io"
TVMAZE = "https://api.tvmaze.com"
ID_FIELDS = ("tmdb", "kinopoisk", "imdb", "shikimori", "myAnimeList", "anilist")
MAX_RESPONSE = 6 * 1024 * 1024
_last_kino_request = 0.0


class SameHostRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).hostname != urllib.parse.urlparse(request.full_url).hostname:
            return None  # Never forward a private API token to another host.
        return super().redirect_request(request, fp, code, msg, headers, newurl)


_opener = urllib.request.build_opener(SameHostRedirect())


def fetch(url, *, key="", body=None, optional=False):
    """Small bounded HTTP client. Keys are sent in headers, never URLs or logs."""
    global _last_kino_request
    headers = {"User-Agent": "CinemaHall/0.4", "Accept": "application/json"}
    if url.startswith(KINO + "/"):
        if not key:
            if optional:
                return None
            raise ValueError("Set KINOAPI_KEY in the environment")
        headers["X-API-Key"] = key
        # Below the published 6 req/s quota, including sequential enrichment.
        delay = 0.25 - (time.monotonic() - _last_kino_request)
        if delay > 0:
            time.sleep(delay)
        _last_kino_request = time.monotonic()
    elif url.startswith(TMDB + "/"):
        if not key:
            return None
        headers["Authorization"] = "Bearer " + key
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, headers=headers, data=json.dumps(body).encode() if body else None)
    for attempt in range(3):
        try:
            with _opener.open(request, timeout=12) as response:
                if int(response.headers.get("Content-Length", "0")) > MAX_RESPONSE:
                    raise ValueError("Response too large")
                payload = response.read(MAX_RESPONSE + 1)
                if len(payload) > MAX_RESPONSE:
                    raise ValueError("Response too large")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            if error.code in (429, 503) and attempt < 2:
                try:
                    delay = min(5, max(1, int(error.headers.get("Retry-After", "2"))))
                except ValueError:
                    delay = 2
                time.sleep(delay)
                continue
            if optional:
                return None
            raise RuntimeError(f"{urllib.parse.urlparse(url).hostname}: HTTP {error.code}") from None
        except (OSError, ValueError, json.JSONDecodeError):
            if optional:
                return None
            raise


def distinct(values):
    return list(dict.fromkeys(v for v in values if isinstance(v, str) and v.strip()))


def image_url(value):
    return value if isinstance(value, str) and value.startswith("https://") else ""


def title_id(raw):
    return {k: str(v) for k, v in (raw or {}).items() if k in ID_FIELDS and v is not None and str(v).strip()}


def normalize(raw):
    """Preserve identities/aliases and separate voiceover labels from video sources."""
    ids = title_id(raw.get("id"))
    kind = raw.get("type")
    if kind not in ("movie", "tv") or not ids:
        return None
    name = raw.get("title") or {}
    art = raw.get("images") or {}
    kp_art = art.get("kinopoisk") or {}
    p_candidates = distinct([image_url(art.get("poster")),
                             *(image_url(x) for x in art.get("posters") or []),
                             image_url(kp_art.get("poster"))])
    # Only actual landscape candidates: never stretch a portrait poster into a banner.
    b_candidates = distinct([image_url(art.get("backdrop")),
                             *(image_url(x) for x in art.get("backdrops") or []),
                             image_url(art.get("cover")), image_url(kp_art.get("cover"))])
    aliases = distinct([name.get("ru"), name.get("en"), name.get("original")])
    if not aliases:
        return None
    release = raw.get("release") or {}
    desc = raw.get("description") or {}
    anime = raw.get("anime") or {}
    return {
        "type": kind, "ids": ids, "title": (name.get("ru") or name.get("original") or aliases[0]) if aliases else "",
        "aliases": aliases, "year": release.get("year"), "releaseDate": release.get("date"),
        "status": release.get("status"), "runtimeMinutes": release.get("runtimeMinutes"),
        "ageRating": release.get("ageRating"), "description": desc.get("ru") or desc.get("en") or "",
        "tagline": (raw.get("tagline") or {}).get("ru") or (raw.get("tagline") or {}).get("en") or "",
        "genres": distinct((raw.get("genres") or []) + (anime.get("genres") or [])),
        "countries": distinct(raw.get("countries") or []), "studios": distinct(anime.get("studios") or []),
        "trailers": raw.get("trailers") or {},
        "ratings": raw.get("ratings") or {}, "images": {
            "poster": p_candidates[0] if p_candidates else "",
            "backdrop": b_candidates[0] if b_candidates else "",
            "logo": image_url(art.get("logo")),
            "posters": p_candidates[:12], "backdrops": b_candidates[:12],
        },
        "voiceoverLabels": distinct(raw.get("voiceovers") or []),
        "playableSources": [], "sources": ["kinoapi"],
        "updatedAt": raw.get("updatedAt") or "",
    }


def identity_keys(row):
    return [(row["type"], k, v) for k, v in row["ids"].items()]


def merge_rows(old, new):
    """Never overwrite a known field with an empty value from another list."""
    old["ids"].update(new["ids"])
    for field in ("title", "year", "status", "description", "releaseDate", "runtimeMinutes", "ageRating", "tagline"):
        if not old.get(field) and new.get(field):
            old[field] = new[field]
    for field in ("aliases", "genres", "countries", "studios", "voiceoverLabels", "sources"):
        old[field] = distinct(old[field] + new[field])
    old["ratings"].update({k: v for k, v in new["ratings"].items() if v is not None})
    for language, trailer in new["trailers"].items():
        if trailer and not old["trailers"].get(language):
            old["trailers"][language] = trailer
    for field in ("posters", "backdrops"):
        old["images"][field] = distinct(old["images"][field] + new["images"][field])[:12]
    for field in ("poster", "backdrop", "logo"):
        if not old["images"].get(field):
            old["images"][field] = new["images"].get(field, "")
    if new["updatedAt"] > old["updatedAt"]:
        old["updatedAt"] = new["updatedAt"]
    return old


def merge_catalog(batches):
    rows = []
    indexes = {}
    for batch in batches:
        for raw in batch:
            row = normalize(raw)
            if not row:
                continue
            matches = {indexes[k] for k in identity_keys(row) if k in indexes}
            if matches:
                target = min(matches)
                row = merge_rows(rows[target], row)
                for other in sorted(matches - {target}):
                    if rows[other] is not None:
                        merge_rows(row, rows[other])
                        rows[other] = None
                for k, v in list(indexes.items()):
                    if v in matches:
                        indexes[k] = target
            else:
                target = len(rows)
                rows.append(row)
            for k in identity_keys(row):
                indexes[k] = target
    return [row for row in rows if row is not None]


def append_art(row, field, url):
    url = image_url(url)
    if not url:
        return
    pictures = row["images"]
    list_key = "backdrops" if field == "backdrop" else "posters"
    pictures[list_key] = distinct(pictures[list_key] + [url])[:12]
    if not pictures[field]:
        pictures[field] = url


def enrich(row, tmdb_key=""):
    """Conditional enrichment: anime by MAL, missing art by IMDb, TVmaze for series."""
    ids, art = row["ids"], row["images"]
    if tmdb_key and ids.get("tmdb"):
        kind = row["type"]
        url = f"{TMDB}/{kind}/{ids['tmdb']}?language=ru-RU&append_to_response=images,external_ids,videos&include_image_language=ru,en,null"
        data = fetch(url, key=tmdb_key, optional=True)
        if isinstance(data, dict):
            tmdb_images = data.get("images") or {}
            posters = sorted(tmdb_images.get("posters") or [],
                             key=lambda item: item.get("width", 0) * item.get("height", 0), reverse=True)
            backs = sorted(tmdb_images.get("backdrops") or [],
                           key=lambda item: item.get("width", 0) * item.get("height", 0), reverse=True)
            for x in posters[:3]:
                if x.get("height", 0) > x.get("width", 0):
                    append_art(row, "poster", "https://image.tmdb.org/t/p/original" + x.get("file_path", ""))
            for x in backs[:4]:
                if x.get("width", 0) >= x.get("height", 1) * 1.5:
                    append_art(row, "backdrop", "https://image.tmdb.org/t/p/original" + x.get("file_path", ""))
            row["sources"].append("tmdb")
    if ids.get("myAnimeList"):
        query = "query ($id:Int) { Media(idMal:$id,type:ANIME) { title { romaji english native } synonyms bannerImage coverImage { extraLarge } genres } }"
        result = fetch(ANILIST, body={"query": query, "variables": {"id": int(ids["myAnimeList"]) }}, optional=True)
        media = ((result or {}).get("data") or {}).get("Media") or {}
        if media:
            row["aliases"] = distinct(row["aliases"] + list((media.get("title") or {}).values()) + (media.get("synonyms") or []))
            row["genres"] = distinct(row["genres"] + (media.get("genres") or []))
            append_art(row, "poster", (media.get("coverImage") or {}).get("extraLarge"))
            append_art(row, "backdrop", media.get("bannerImage"))
            row["sources"].append("anilist")
    if not art["poster"] or not art["backdrop"]:
        imdb = ids.get("imdb", "")
        if imdb.startswith("tt") and imdb[2:].isdigit():
            kind = "series" if row["type"] == "tv" else "movie"
            result = fetch(f"{CINEMETA}/meta/{kind}/{imdb}.json", optional=True) or {}
            meta = result.get("meta") or {}
            append_art(row, "poster", meta.get("poster"))
            append_art(row, "backdrop", meta.get("background"))
            if meta:
                row["sources"].append("cinemeta")
    if row["type"] == "tv" and not art["backdrop"] and ids.get("imdb"):
        show = fetch(f"{TVMAZE}/lookup/shows?imdb={urllib.parse.quote(ids['imdb'])}", optional=True) or {}
        if show.get("id"):
            pictures = fetch(f"{TVMAZE}/shows/{int(show['id'])}/images", optional=True) or []
            for picture in pictures:
                if picture.get("type") == "background":
                    append_art(row, "backdrop", ((picture.get("resolutions") or {}).get("original") or {}).get("url"))
            row["sources"].append("tvmaze")
    row["sources"] = distinct(row["sources"])


def details(row, key, season_limit=3):
    """Static detail bundle for selected titles; no playback or unchecked voice tracks."""
    tmdb = row["ids"].get("tmdb")
    if not tmdb:
        return
    kind = row["type"]
    bundle = {}
    actors = fetch(f"{KINO}/titles/by/tmdb/{tmdb}/credits?type={kind}", key=key, optional=True)
    if isinstance(actors, dict):
        bundle["cast"] = [{"id": p.get("id"), "name": p.get("name_ru") or p.get("name_en"),
                           "character": p.get("character_ru") or p.get("character_en") or "",
                           "image": image_url(p.get("profile_path"))}
                          for p in (actors.get("data") or [])[:20] if p.get("profession") == "actor"]
    recs = fetch(f"{KINO}/titles/{kind}/{tmdb}/recommendations?limit=12", key=key, optional=True)
    if isinstance(recs, dict):
        bundle["recommendations"] = [r for raw in (recs.get("data") or [])[:12]
                                     if (r := normalize(raw)) is not None]
    similar = fetch(f"{KINO}/titles/{kind}/{tmdb}/similar?limit=12", key=key, optional=True)
    if isinstance(similar, dict):
        bundle["similar"] = [r for raw in (similar.get("data") or [])[:12]
                             if (r := normalize(raw)) is not None]
    if kind == "tv":
        schedule = fetch(f"{KINO}/tv/{tmdb}/schedule", key=key, optional=True)
        if isinstance(schedule, dict):
            bundle["upcomingEpisodes"] = (schedule.get("data") or [])[:24]
        seasons = fetch(f"{KINO}/tv/by/tmdb/{tmdb}/seasons", key=key, optional=True)
        if isinstance(seasons, dict):
            bundle["seasons"] = []
            for season in (seasons.get("data") or []):
                number = season.get("season_number")
                if not isinstance(number, int) or number < 0:
                    continue
                summary = {
                    "number": number, "name": season.get("name_ru") or season.get("name_en") or f"Сезон {number}",
                    "poster": image_url(season.get("poster_path")), "count": season.get("episode_count"),
                    "animeMappings": season.get("anime_mappings") or [],
                }
                if len(bundle["seasons"]) < season_limit:
                    episodes = fetch(f"{KINO}/tv/by/tmdb/{tmdb}/seasons/{number}/episodes", key=key, optional=True)
                    if isinstance(episodes, dict):
                        summary["episodes"] = [{"number": e.get("episode_number"),
                                                "name": e.get("name_ru") or e.get("name_en") or "",
                                                "overview": e.get("overview_ru") or e.get("overview_en") or "",
                                                "date": e.get("air_date"), "still": image_url(e.get("still_path")),
                                                "animeMapping": e.get("anime_mapping")}
                                               for e in (episodes.get("data") or [])[:100]]
                bundle["seasons"].append(summary)
    else:
        markers = fetch(f"{KINO}/titles/by/tmdb/{tmdb}/skip-markers?type=movie", key=key, optional=True)
        if isinstance(markers, dict):
            bundle["skipMarkers"] = markers.get("data") or []
    if bundle:
        row["details"] = bundle


def build(key, pages, enrichment, tmdb_key="", detail_count=0, season_limit=3):
    batches = []
    for kind in ("movie", "tv"):
        for page in range(1, pages + 1):
            query = urllib.parse.urlencode({"type": kind, "sort": "popularity", "order": "desc", "page": page, "limit": 100})
            batch = fetch(f"{KINO}/titles?{query}", key=key)
            batches.append(batch.get("data") or [])
            if not batch.get("meta", {}).get("hasNext", False):
                break
    for endpoint in ("/titles/latest?limit=100", "/anime?page=1&limit=100"):
        batches.append((fetch(KINO + endpoint, key=key) or {}).get("data") or [])
    filters = (fetch(KINO + "/catalog/filters", key=key, optional=True) or {}).get("data") or {}
    calendar = (fetch(KINO + "/releases/calendar", key=key, optional=True) or {}).get("data") or []
    rows = merge_catalog(batches)
    # Enrich the most visible cards and those without usable art; bound requests.
    candidates = sorted(rows, key=lambda r: (bool(r["images"]["backdrop"]),
                                               bool(r["images"]["poster"]),
                                               not bool(r["ids"].get("myAnimeList"))))
    for row in candidates[:enrichment]:
        enrich(row, tmdb_key)
    # Balance film and TV detail cards instead of starving TV behind movie pages.
    movies = [r for r in rows if r["type"] == "movie"]
    shows = [r for r in rows if r["type"] == "tv"]
    selected = [r for pair in zip(movies[:detail_count], shows[:detail_count]) for r in pair]
    selected += (movies[len(shows):] if len(shows) < len(movies) else shows[len(movies):])
    for row in selected[:detail_count]:
        details(row, key, season_limit)
    return {"version": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
            "count": len(rows), "filters": filters,
            "releaseCalendar": [r for raw in calendar[:100] if (r := normalize(raw)) is not None],
            "titles": rows}


def main():
    parser = argparse.ArgumentParser(description="Build a KinoAPI-led static metadata catalog")
    parser.add_argument("--pages", type=int, default=3, help="Popular pages per movie/TV type (100 titles each)")
    parser.add_argument("--enrich", type=int, default=30, help="Maximum optional cross-provider enrichments")
    parser.add_argument("--details", type=int, default=12, help="Popular detail cards with cast, recommendations and seasons")
    parser.add_argument("--season-limit", type=int, default=3, help="Maximum seasons with full episode metadata per detailed show")
    parser.add_argument("--output", default="catalog/index.json")
    args = parser.parse_args()
    if not 1 <= args.pages <= 50 or not 0 <= args.enrich <= 500 or not 0 <= args.details <= 100 or not 0 <= args.season_limit <= 20:
        parser.error("pages 1..50, enrich 0..500, details 0..100, season-limit 0..20")
    result = build(os.environ.get("KINOAPI_KEY", ""), args.pages, args.enrich,
                   os.environ.get("TMDB_READ_TOKEN", ""), args.details, args.season_limit)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Catalog created: {result['count']} deduplicated titles in {out}")


if __name__ == "__main__":
    main()
