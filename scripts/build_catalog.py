"""Build a lightweight, public catalogue snapshot without exposing provider credentials.

KinoAPI supplies stable cross-provider IDs and Russian metadata. Optional APIs
fill gaps only for a bounded number of items; no source is treated as playable.
"""
import argparse
import hashlib
import http.client
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path


KINO = "https://kinoapi.lampac.dev/api/v1"
TMDB = "https://api.themoviedb.org/3"
ANILIST = "https://graphql.anilist.co"
CINEMETA = "https://v3-cinemeta.strem.io"
TVMAZE = "https://api.tvmaze.com"
ZONA = "https://zsolr3.zonasearch.com/solr/torrent/select"
ID_FIELDS = ("tmdb", "kinopoisk", "imdb", "shikimori", "myAnimeList", "anilist")
SEASON_IDS = ("shikimori", "myAnimeList", "anilist")
MAX_RESPONSE = 6 * 1024 * 1024
_last_kino_request = 0.0
_requests = Counter()
_failed = Counter()


class SameHostRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        old, new = urllib.parse.urlparse(request.full_url), urllib.parse.urlparse(newurl)
        if old.scheme != "https" or new.scheme != "https" or old.netloc != new.netloc:
            return None  # Never leak credentials to another host/port or plaintext HTTP.
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
    host = urllib.parse.urlparse(url).hostname or "unknown"
    for attempt in range(5):
        try:
            _requests[host] += 1
            with _opener.open(request, timeout=12) as response:
                if int(response.headers.get("Content-Length", "0")) > MAX_RESPONSE:
                    raise ValueError("Response too large")
                payload = response.read(MAX_RESPONSE + 1)
                if len(payload) > MAX_RESPONSE:
                    raise ValueError("Response too large")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            if error.code in (429, 503) and attempt < 4:
                try:
                    delay = max(2 ** attempt, int(error.headers.get("Retry-After", "0")))
                except ValueError:
                    delay = 2 ** attempt
                time.sleep(min(60, max(1, delay)))
                continue
            if optional:
                _failed[host] += 1
                return None
            raise RuntimeError(f"{urllib.parse.urlparse(url).hostname}: HTTP {error.code}") from None
        except (OSError, http.client.HTTPException):
            # KinoAPI occasionally cuts chunked responses mid-body; retry a fresh connection.
            if attempt < 4:
                time.sleep(min(8, 2 ** attempt))
                continue
            if optional:
                _failed[host] += 1
                return None
            raise
        except (ValueError, json.JSONDecodeError):
            if optional:
                _failed[host] += 1
                return None
            raise


def distinct(values):
    return list(dict.fromkeys(v for v in values if isinstance(v, str) and v.strip()))


def image_url(value):
    return value if isinstance(value, str) and value.startswith("https://") else ""


def tmdb_image_url(value):
    if isinstance(value, str) and value.startswith("/") and not value.startswith("//"):
        return "https://image.tmdb.org/t/p/original" + value
    return image_url(value)


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
    wide = distinct([image_url(art.get("backdrop")),
                     *(image_url(x) for x in art.get("backdrops") or [])])
    wide = [url for url in wide if url not in p_candidates]
    # Covers have unknown dimensions; let clients use a gradient instead of stretching one.
    b_candidates = distinct(wide + [image_url(art.get("cover")), image_url(kp_art.get("cover"))])
    aliases = distinct([name.get("ru"), name.get("en"), name.get("original")])
    if not aliases:
        return None
    release = raw.get("release") or {}
    desc = raw.get("description") or {}
    anime = raw.get("anime") or {}
    return {
        "type": kind, "ids": ids, "title": (name.get("ru") or name.get("original") or aliases[0]) if aliases else "",
        "aliases": aliases, "titleRu": name.get("ru") or "", "titleEn": name.get("en") or "", "originalTitle": name.get("original") or "", "year": release.get("year"), "releaseDate": release.get("date"),
        "status": release.get("status"), "runtimeMinutes": release.get("runtimeMinutes"),
        "ageRating": release.get("ageRating"), "description": desc.get("ru") or desc.get("en") or "",
        "tagline": (raw.get("tagline") or {}).get("ru") or (raw.get("tagline") or {}).get("en") or "",
        "genres": distinct((raw.get("genres") or []) + (anime.get("genres") or [])),
        "countries": distinct(raw.get("countries") or []), "studios": distinct(anime.get("studios") or []),
        "trailers": raw.get("trailers") or {},
        "ratings": raw.get("ratings") or {}, "images": {
            "poster": p_candidates[0] if p_candidates else "",
            "backdrop": b_candidates[0] if b_candidates else "",
            "backdropVerified": bool(wide),
            "logo": image_url(art.get("logo")),
            "posters": p_candidates[:12], "backdrops": b_candidates[:12],
        },
        "voiceoverLabels": distinct(raw.get("voiceovers") or []),
        "playableSources": [], "sources": ["kinoapi"],
        "sourceAvailability": {"zona": "unchecked", "candidateCount": 0,
                               "reportedResolutions": [], "reportedVoiceovers": []},
        "identityWarnings": [], "seasonIds": {},
        "updatedAt": raw.get("updatedAt") or "",
    }


def identity_keys(row):
    return [(row["type"], k, v) for k, v in row["ids"].items()]


def merge_rows(old, new):
    """Never overwrite a known field with an empty value from another list."""
    for field, value in new["ids"].items():
        original = old["ids"].get(field)
        if not original:
            old["ids"][field] = value
        elif original != value:
            if field in SEASON_IDS:
                old["seasonIds"][field] = distinct(old["seasonIds"].get(field, []) + [original, value])
            else:
                warning = f"{field}: {original} != {value}"
                if warning not in old["identityWarnings"]:
                    old["identityWarnings"].append(warning)
    if not old.get("titleRu") and new.get("titleRu"):
        old["title"] = new["titleRu"]
    for field in ("title", "titleRu", "titleEn", "originalTitle", "year", "status", "description", "releaseDate", "runtimeMinutes", "ageRating", "tagline"):
        if not old.get(field) and new.get(field):
            old[field] = new[field]
    for field in ("aliases", "genres", "countries", "studios", "voiceoverLabels", "sources"):
        old[field] = distinct(old[field] + new[field])
    old["identityWarnings"] = distinct(old["identityWarnings"] + new["identityWarnings"])
    for field, values in new["seasonIds"].items():
        old["seasonIds"][field] = distinct(old["seasonIds"].get(field, []) + values)
    old["ratings"].update({k: v for k, v in new["ratings"].items() if v is not None})
    if new["sourceAvailability"]["zona"] == "listed":
        old["sourceAvailability"]["zona"] = "listed"
        old["sourceAvailability"]["candidateCount"] += new["sourceAvailability"]["candidateCount"]
        for field in ("reportedResolutions", "reportedVoiceovers"):
            old["sourceAvailability"][field] = distinct(old["sourceAvailability"][field] + new["sourceAvailability"][field])[:30]
    for language, trailer in new["trailers"].items():
        if trailer and not old["trailers"].get(language):
            old["trailers"][language] = trailer
    for field in ("posters", "backdrops"):
        old["images"][field] = distinct(old["images"][field] + new["images"][field])[:12]
    for field in ("poster", "backdrop", "logo"):
        if not old["images"].get(field):
            old["images"][field] = new["images"].get(field, "")
    if new["images"]["backdropVerified"] and not old["images"]["backdropVerified"]:
        old["images"]["backdrop"] = new["images"]["backdrop"]
        old["images"]["backdropVerified"] = True
    if new["updatedAt"] > old["updatedAt"]:
        old["updatedAt"] = new["updatedAt"]
    return old


def incompatible_ids(a, b):
    """Conflicting film IDs must never cause two unrelated records to collapse."""
    return any(a["ids"].get(k) and b["ids"].get(k) and a["ids"][k] != b["ids"][k]
               for k in ("tmdb", "kinopoisk", "imdb"))


def merge_catalog(batches):
    rows = []
    indexes = defaultdict(set)
    for batch in batches:
        for raw in batch:
            row = normalize(raw)
            if not row:
                continue
            candidates = {i for k in identity_keys(row) for i in indexes[k] if rows[i] is not None}
            matches = {i for i in candidates if not incompatible_ids(rows[i], row)}
            for i in candidates - matches:
                # Never combine two records with contradictory film identities.
                warning = "Conflicting provider IDs on shared identity"
                rows[i]["identityWarnings"] = distinct(rows[i]["identityWarnings"] + [warning])
                row["identityWarnings"].append(warning)
            if matches:
                # Prefer exact TMDB match for a contradictory multi-ID bridge.
                tmdb = row["ids"].get("tmdb")
                exact = [i for i in matches if tmdb and rows[i]["ids"].get("tmdb") == tmdb]
                target = min(exact or matches)
                row = merge_rows(rows[target], row)
                for other in sorted(matches - {target}):
                    if rows[other] is not None and not incompatible_ids(row, rows[other]):
                        merge_rows(row, rows[other])
                        rows[other] = None
                        for positions in indexes.values():
                            if other in positions:
                                positions.remove(other)
                                positions.add(target)
            else:
                target = len(rows)
                rows.append(row)
            for k in identity_keys(row):
                indexes[k].add(target)
    result = [row for row in rows if row is not None]
    # A bad upstream ID collision must not make two search hits resolve to one shard row.
    collisions = defaultdict(list)
    for row in result:
        collisions[stable_id(row)].append(row)
    for group in collisions.values():
        if len(group) > 1:
            for row in group:
                signature = json.dumps(row["ids"], sort_keys=True, ensure_ascii=False)
                row["collisionSuffix"] = hashlib.sha256(signature.encode()).hexdigest()[:12]
    return result


def append_art(row, field, url, verified=False):
    url = image_url(url)
    if not url:
        return
    pictures = row["images"]
    if field == "backdrop" and url in pictures["posters"]:
        return
    list_key = "backdrops" if field == "backdrop" else "posters"
    pictures[list_key] = distinct(pictures[list_key] + [url])[:12]
    if not pictures[field] or (field == "backdrop" and verified and not pictures["backdropVerified"]):
        pictures[field] = url
    if field == "backdrop" and verified:
        pictures["backdropVerified"] = True


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
                    append_art(row, "backdrop", "https://image.tmdb.org/t/p/original" + x.get("file_path", ""), verified=True)
            row["sources"].append("tmdb")
    if ids.get("myAnimeList"):
        query = "query ($id:Int) { Media(idMal:$id,type:ANIME) { title { romaji english native } synonyms bannerImage coverImage { extraLarge } genres } }"
        result = fetch(ANILIST, body={"query": query, "variables": {"id": int(ids["myAnimeList"]) }}, optional=True)
        media = ((result or {}).get("data") or {}).get("Media") or {}
        if media:
            row["aliases"] = distinct(row["aliases"] + list((media.get("title") or {}).values()) + (media.get("synonyms") or []))
            row["genres"] = distinct(row["genres"] + (media.get("genres") or []))
            append_art(row, "poster", (media.get("coverImage") or {}).get("extraLarge"))
            append_art(row, "backdrop", media.get("bannerImage"), verified=True)
            row["sources"].append("anilist")
    if not art["poster"] or not art["backdropVerified"]:
        imdb = ids.get("imdb", "")
        if imdb.startswith("tt") and imdb[2:].isdigit():
            kind = "series" if row["type"] == "tv" else "movie"
            result = fetch(f"{CINEMETA}/meta/{kind}/{imdb}.json", optional=True) or {}
            meta = result.get("meta") or {}
            append_art(row, "poster", meta.get("poster"))
            append_art(row, "backdrop", meta.get("background"), verified=True)
            if meta:
                row["sources"].append("cinemeta")
    if row["type"] == "tv" and not art["backdropVerified"] and ids.get("imdb"):
        show = fetch(f"{TVMAZE}/lookup/shows?imdb={urllib.parse.quote(ids['imdb'])}", optional=True) or {}
        if show.get("id"):
            pictures = fetch(f"{TVMAZE}/shows/{int(show['id'])}/images", optional=True) or []
            for picture in pictures:
                if picture.get("type") == "background":
                    append_art(row, "backdrop", ((picture.get("resolutions") or {}).get("original") or {}).get("url"), verified=True)
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
                    "poster": tmdb_image_url(season.get("poster_path")), "count": season.get("episode_count"),
                    "animeMappings": season.get("anime_mappings") or [],
                }
                if len(bundle["seasons"]) < season_limit:
                    episodes = fetch(f"{KINO}/tv/by/tmdb/{tmdb}/seasons/{number}/episodes", key=key, optional=True)
                    if isinstance(episodes, dict):
                        summary["episodes"] = [{"number": e.get("episode_number"),
                                                "name": e.get("name_ru") or e.get("name_en") or "",
                                                "overview": e.get("overview_ru") or e.get("overview_en") or "",
                                                "date": e.get("air_date"), "still": tmdb_image_url(e.get("still_path")),
                                                "animeMapping": e.get("anime_mapping")}
                                               for e in (episodes.get("data") or [])[:100]]
                bundle["seasons"].append(summary)
    else:
        markers = fetch(f"{KINO}/titles/by/tmdb/{tmdb}/skip-markers?type=movie", key=key, optional=True)
        if isinstance(markers, dict):
            bundle["skipMarkers"] = markers.get("data") or []
    if bundle:
        row["details"] = bundle


def enrich_zona_metadata(rows):
    """Fill Russian metadata only for exact Kinopoisk IDs and compatible years."""
    by_id = defaultdict(list)
    for row in rows:
        kp = row.get("ids", {}).get("kinopoisk", "")
        if kp.isdigit():
            by_id[kp].append(row)
    ids = list(by_id)
    for offset in range(0, len(ids), 100):
        batch = ids[offset:offset + 100]
        params = urllib.parse.urlencode({
            "wt": "json", "q": "id:(" + " OR ".join(batch) + ") AND adult:false",
            "rows": 100, "fl": "id,name_rus,name_original,name_eng,description,year,rating_kinopoisk,rating_imdb",
        })
        response = fetch("https://zsolr3.zonasearch.com/solr/movie/select?" + params, optional=True) or {}
        for doc in ((response.get("response") or {}).get("docs") or []):
            for row in by_id.get(str(doc.get("id", "")), []):
                old_year, new_year = row.get("year"), doc.get("year")
                if old_year and new_year and str(old_year) != str(new_year):
                    continue
                russian = (doc.get("name_rus") or "").strip()
                if russian:
                    row["titleRu"] = russian
                    row["title"] = russian
                row["aliases"] = distinct(row["aliases"] + [russian, doc.get("name_eng"), doc.get("name_original")])
                if not row.get("description") and doc.get("description"):
                    row["description"] = doc["description"]
                if not row.get("titleEn") and doc.get("name_eng"):
                    row["titleEn"] = doc["name_eng"]
                if not row.get("originalTitle") and doc.get("name_original"):
                    row["originalTitle"] = doc["name_original"]
                for field, key in (("rating_kinopoisk", "kinopoisk"), ("rating_imdb", "imdb")):
                    value = doc.get(field)
                    if not row["ratings"].get(key) and isinstance(value, (float, int)) and 0 < value <= 10:
                        row["ratings"][key] = value
                if "zona-metadata" not in row["sources"]:
                    row["sources"].append("zona-metadata")


def zona_availability(rows, limit=200):
    """Optional provider hints, never a promise that a stream will actually play."""
    selected = [r for r in rows if r["ids"].get("kinopoisk", "").isdigit()][:limit]
    by_id = {r["ids"]["kinopoisk"]: r for r in selected}
    for offset in range(0, len(selected), 20):
        ids = [r["ids"]["kinopoisk"] for r in selected[offset:offset + 20]]
        params = urllib.parse.urlencode({
            "wt": "json", "q": "kinopoisk_id:(" + " OR ".join(ids) + ") AND playable:true AND trailer:false",
            "rows": 500, "sort": "seeds desc",
            "fl": "kinopoisk_id,resolution,translate_info,languages_parser,seeds",
        })
        response = fetch(f"{ZONA}?{params}", optional=True) or {}
        for doc in ((response.get("response") or {}).get("docs") or []):
            item = by_id.get(str(doc.get("kinopoisk_id", "")))
            if not item:
                continue
            hint = item["sourceAvailability"]
            hint["zona"] = "listed"  # not proven stream/playback
            hint["candidateCount"] += 1
            resolution = doc.get("resolution", "") or ""
            voice = (doc.get("translate_info", "") or "").strip()
            if resolution:
                hint["reportedResolutions"] = distinct(hint["reportedResolutions"] + [resolution])[:30]
            if voice:
                hint["reportedVoiceovers"] = distinct(hint["reportedVoiceovers"] + [voice])[:30]


def build(key, pages, enrichment, tmdb_key="", detail_count=0, season_limit=3, availability=200):
    _requests.clear()
    _failed.clear()
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
    batches.append(calendar[:100])
    rows = merge_catalog(batches)
    # Enrich the most visible cards and those without usable art; bound requests.
    candidates = sorted((r for r in rows if r["ids"].get("myAnimeList") or
                         r["ids"].get("imdb", "").startswith("tt") or
                         (tmdb_key and r["ids"].get("tmdb"))),
                        key=lambda r: (r["images"]["backdropVerified"],
                                       bool(r["images"]["poster"]),
                                       not bool(r["ids"].get("myAnimeList"))))
    for row in candidates[:enrichment]:
        enrich(row, tmdb_key)
    if availability:
        enrich_zona_metadata(rows)
        zona_availability(rows, availability)
    # Balance film and TV detail cards instead of starving TV behind movie pages.
    movies = [r for r in rows if r["type"] == "movie"]
    shows = [r for r in rows if r["type"] == "tv"]
    selected = [r for pair in zip(movies[:detail_count], shows[:detail_count]) for r in pair]
    selected += (movies[len(shows):] if len(shows) < len(movies) else shows[len(movies):])
    for row in selected[:detail_count]:
        details(row, key, season_limit)
    quality = {
        "movies": sum(r["type"] == "movie" for r in rows),
        "series": sum(r["type"] == "tv" for r in rows),
        "anime": sum(bool(r["ids"].get("myAnimeList")) for r in rows),
        "noPoster": sum(not r["images"]["poster"] for r in rows),
        "noVerifiedBackdrop": sum(not r["images"]["backdropVerified"] for r in rows),
        "multipleAliases": sum(len(r["aliases"]) > 1 for r in rows),
        "withVoiceoverLabels": sum(bool(r["voiceoverLabels"]) for r in rows),
        "withDetails": sum("details" in r for r in rows),
        "identityWarnings": sum(bool(r["identityWarnings"]) for r in rows),
        "enrichedBy": dict(Counter(source for r in rows for source in r["sources"])),
        "zonaListed": sum(r["sourceAvailability"]["zona"] == "listed" for r in rows),
        "requests": dict(_requests), "optionalFailures": dict(_failed),
    }
    return {"version": 2, "generatedAt": datetime.now(timezone.utc).isoformat(),
            "count": len(rows), "filters": filters,
            "releaseCalendar": [r for raw in calendar[:100] if (r := normalize(raw)) is not None],
            "quality": quality, "titles": rows}


def stable_id(row):
    for source in ("tmdb", "kinopoisk", "imdb", "myAnimeList", "shikimori"):
        if row["ids"].get(source):
            base = f"{row['type']}:{source}:{row['ids'][source]}"
            return base + (":" + row["collisionSuffix"] if row.get("collisionSuffix") else "")
    raise ValueError("Title without a stable identifier")


def rating_for_sort(scores):
    for key in ("kinopoisk", "imdb", "tmdb"):
        value = (scores or {}).get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
        if isinstance(value, dict):
            try:
                number = float(value.get("value") or 0)
            except (TypeError, ValueError):
                continue
            if number > 0:
                return number
    return 0.0


def compact(row):
    return {"id": stable_id(row), "type": row["type"], "ids": row["ids"],
            "title": row["title"], "aliases": row["aliases"], "year": row["year"],
            "poster": row["images"]["poster"],
            "background": row["images"].get("backdrop", "") if row["images"].get("backdropVerified") and row["images"].get("backdrop") != row["images"].get("poster") else "",
            "genres": row.get("genres") or [],
            "topRating": rating_for_sort(row.get("ratings"))}


def split_snapshot(result, shard_size=250):
    """Small home screen now; lazy-load only the requested catalogue shards later."""
    if not 50 <= shard_size <= 1000:
        raise ValueError("shard_size must be 50..1000")
    rows = result["titles"]
    grouped = {"movie": [r for r in rows if r["type"] == "movie"],
               "tv": [r for r in rows if r["type"] == "tv"]}
    manifest = {"version": result["version"], "generatedAt": result["generatedAt"],
                "count": result["count"], "quality": result["quality"],
                "filters": result["filters"], "search": "search.json", "shards": []}
    files = {}
    lookup = {}
    for kind, entries in grouped.items():
        for i in range(0, len(entries), shard_size):
            path = f"shards/{kind}-{i // shard_size + 1:03d}.json"
            files[path] = entries[i:i + shard_size]
            manifest["shards"].append({"path": path, "type": kind, "count": len(files[path])})
            for row in files[path]:
                lookup[stable_id(row)] = path
    def link(row):
        return {**compact(row), "shard": lookup.get(stable_id(row), "")}
    manifest["home"] = {"movies": [link(r) for r in grouped["movie"][:24]],
                        "series": [link(r) for r in grouped["tv"][:24]],
                        "anime": [link(r) for r in rows if r["ids"].get("myAnimeList")][:24],
                        "calendar": [link(r) for r in result["releaseCalendar"][:24]]}
    files["search.json"] = [link(r) for r in rows]
    files["index.json"] = manifest
    return files


def main():
    parser = argparse.ArgumentParser(description="Build a KinoAPI-led static metadata catalog")
    parser.add_argument("--pages", type=int, default=15, help="Popular pages per movie/TV type (100 titles each)")
    parser.add_argument("--enrich", type=int, default=100, help="Maximum optional cross-provider enrichments")
    parser.add_argument("--details", type=int, default=48, help="Popular detail cards with cast, recommendations and seasons")
    parser.add_argument("--season-limit", type=int, default=8, help="Maximum seasons with full episode metadata per detailed show")
    parser.add_argument("--output", default="catalog/index.json")
    parser.add_argument("--shard-size", type=int, default=250)
    parser.add_argument("--availability", type=int, default=200, help="Top KinoPoisk IDs to probe in Zona (0 disables)")
    args = parser.parse_args()
    if not 1 <= args.pages <= 50 or not 0 <= args.enrich <= 500 or not 0 <= args.details <= 100 or not 0 <= args.season_limit <= 20 or not 0 <= args.availability <= 1000:
        parser.error("pages 1..50, enrich 0..500, details 0..100, season-limit 0..20, availability 0..1000")
    result = build(os.environ.get("KINOAPI_KEY", ""), args.pages, args.enrich,
                   os.environ.get("TMDB_READ_TOKEN", ""), args.details, args.season_limit, args.availability)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    shards = split_snapshot(result, args.shard_size)
    for name, data in shards.items():
        path = out if name == "index.json" else out.parent / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Catalog created: {result['count']} deduplicated titles in {len(shards)} lazy-load files")
    print("Quality: " + json.dumps(result["quality"], ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
