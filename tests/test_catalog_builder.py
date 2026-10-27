"""Offline data-merging tests; never require API credentials or network access."""
import unittest
from unittest.mock import patch

from scripts.build_catalog import append_art, build, details, enrich, merge_catalog, normalize, split_snapshot, zona_availability, tmdb_image_url, enrich_zona_metadata


def record(kind="movie", imdb="tt0137523", kp=361, tmdb=550, name="Бойцовский клуб"):
    return {
        "type": kind, "id": {"imdb": imdb, "kinopoisk": kp, "tmdb": tmdb},
        "title": {"ru": name, "en": name, "original": "Fight Club"},
        "description": {"ru": "Сюжет"}, "release": {"year": 1999, "status": "Released"},
        "images": {"poster": "https://image.tmdb.org/t/p/original/portrait.jpg",
                   "backdrop": None,
                   "backdrops": ["https://image.tmdb.org/t/p/original/landscape.jpg"]},
        "voiceovers": ["Дублированный", "Субтитры"],
        "updatedAt": "2026-10-27T10:00:00Z",
    }


class CatalogTests(unittest.TestCase):
    def test_tmdb_still_and_poster_paths(self):
        self.assertEqual(tmdb_image_url("/still.jpg"), "https://image.tmdb.org/t/p/original/still.jpg")
        self.assertEqual(tmdb_image_url("https://example.org/still.jpg"), "https://example.org/still.jpg")
        self.assertEqual(tmdb_image_url("//example.org/still.jpg"), "")

    def test_russian_name_and_original_aliases_are_retained(self):
        raw = record()
        raw["title"] = {"ru": "Бойцовский клуб", "en": "Fight Club", "original": "Fight Club original"}
        row = normalize(raw)
        self.assertEqual(row["title"], "Бойцовский клуб")
        self.assertEqual(row["originalTitle"], "Fight Club original")
        self.assertIn("Fight Club", row["aliases"])
        self.assertIn("Fight Club original", row["aliases"])

    def test_later_russian_title_replaces_english_display_name(self):
        first = record()
        first["title"] = {"en": "Fight Club", "original": "Fight Club"}
        later = record()
        later["title"] = {"ru": "Бойцовский клуб", "en": "Fight Club"}
        merged = merge_catalog([[first], [later]])
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["title"], "Бойцовский клуб")
        self.assertIn("Fight Club", merged[0]["aliases"])

    def test_art_aliases_voiceovers_are_not_streams(self):
        row = normalize(record())
        self.assertEqual(row["images"]["backdrop"], "https://image.tmdb.org/t/p/original/landscape.jpg")
        self.assertTrue(row["images"]["backdropVerified"])
        self.assertEqual(row["images"]["poster"], "https://image.tmdb.org/t/p/original/portrait.jpg")
        self.assertIn("Fight Club", row["aliases"])
        self.assertEqual(row["voiceoverLabels"], ["Дублированный", "Субтитры"])
        self.assertEqual(row["playableSources"], [])
        self.assertEqual(row["sourceAvailability"]["zona"], "unchecked")

    @patch("scripts.build_catalog.fetch")
    def test_zona_russian_name_requires_exact_id_and_year(self, fetch):
        row = normalize(record())
        row["title"] = "Fight Club"
        row["titleRu"] = ""
        row["description"] = ""
        fetch.return_value = {"response": {"docs": [{"id": "361", "year": 1999,
            "name_rus": "Бойцовский клуб", "name_eng": "Fight Club", "description": "Описание",
            "rating_kinopoisk": 8.6}, {"id": "999", "name_rus": "Чужой фильм"}]}}
        enrich_zona_metadata([row])
        self.assertEqual(row["title"], "Бойцовский клуб")
        self.assertIn("Fight Club", row["aliases"])
        self.assertEqual(row["ratings"]["kinopoisk"], 8.6)
        row["title"] = "Fight Club"
        fetch.return_value["response"]["docs"][0]["year"] = 1984
        enrich_zona_metadata([row])
        self.assertEqual(row["title"], "Fight Club")

    @patch("scripts.build_catalog.fetch")
    def test_zona_hints_never_claim_verified_streams(self, fetch):
        fetch.return_value = {"response": {"docs": [{"kinopoisk_id": 361,
            "resolution": "3840x2160", "translate_info": "Дубляж"}]}}
        row = normalize(record())
        zona_availability([row], limit=1)
        self.assertEqual(row["sourceAvailability"]["zona"], "listed")
        self.assertEqual(row["sourceAvailability"]["reportedResolutions"], ["3840x2160"])
        self.assertEqual(row["playableSources"], [])

    def test_poster_cannot_be_a_backdrop(self):
        raw = record()
        portrait = raw["images"]["poster"]
        raw["images"]["backdrops"] = [portrait]
        row = normalize(raw)
        self.assertEqual(row["images"]["backdrop"], "")
        self.assertFalse(row["images"]["backdropVerified"])
        append_art(row, "backdrop", portrait, verified=True)
        self.assertEqual(row["images"]["backdrop"], "")
        append_art(row, "backdrop", "https://example.org/wide.jpg", verified=True)
        self.assertEqual(row["images"]["backdrop"], "https://example.org/wide.jpg")
        self.assertTrue(row["images"]["backdropVerified"])

    def test_cover_is_not_a_verified_widescreen_banner(self):
        raw = record()
        raw["images"] = {"poster": "https://example.org/portrait.jpg", "cover": "https://example.org/unknown.jpg"}
        row = normalize(raw)
        self.assertEqual(row["images"]["backdrop"], "https://example.org/unknown.jpg")
        self.assertFalse(row["images"]["backdropVerified"])

    def test_conflicting_cross_ids_do_not_merge_unrelated_titles(self):
        a = record(imdb="tt001", kp=10, tmdb=1)
        b = record(imdb="tt002", kp=20, tmdb=2)
        bridge = record(imdb="tt001", kp=20, tmdb=1)
        rows = merge_catalog([[a, b, bridge]])
        self.assertEqual(len(rows), 3)
        self.assertEqual({r["ids"]["tmdb"] for r in rows}, {"1", "2"})
        self.assertEqual({r["ids"]["kinopoisk"] for r in rows}, {"10", "20"})
        self.assertTrue(rows[0]["identityWarnings"])
        snapshot = {"version": 2, "generatedAt": "now", "count": len(rows),
                    "quality": {}, "filters": {}, "releaseCalendar": [], "titles": rows}
        files = split_snapshot(snapshot, shard_size=50)
        self.assertEqual(len({entry["id"] for entry in files["search.json"]}), 3)

    def test_shared_lower_priority_id_cannot_merge_different_films(self):
        a = record(imdb="tt001", kp=10, tmdb=1)
        b = record(imdb="tt001", kp=11, tmdb=2)
        rows = merge_catalog([[a, b]])
        self.assertEqual(len(rows), 2)
        self.assertNotEqual(rows[0]["ids"]["tmdb"], rows[1]["ids"]["tmdb"])
        self.assertTrue(all(r["identityWarnings"] for r in rows))

    def test_anime_season_ids_preserved(self):
        a = record(kind="tv")
        a["id"]["myAnimeList"] = 101
        b = record(kind="tv")
        b["id"]["myAnimeList"] = 202
        rows = merge_catalog([[a, b]])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["seasonIds"]["myAnimeList"], ["101", "202"])

    def test_shards_keep_first_screen_small_and_search_all_titles(self):
        items = []
        for i in range(125):
            item = record(kind="movie" if i % 2 else "tv", kp=i + 1, tmdb=i + 1, imdb=f"tt{i+1}")
            items.append(item)
        rows = merge_catalog([items])
        snapshot = {"version": 2, "generatedAt": "now", "count": len(rows),
                    "quality": {}, "filters": {}, "releaseCalendar": [], "titles": rows}
        files = split_snapshot(snapshot, shard_size=50)
        self.assertEqual(files["index.json"]["count"], 125)
        self.assertEqual(len(files["search.json"]), 125)
        self.assertTrue(all(len(v) <= 50 for name, v in files.items() if name.startswith("shards/")))
        self.assertLess(len(files["index.json"]["home"]["movies"]), 25)
        self.assertTrue(all(x["shard"].startswith("shards/") for x in files["index.json"]["home"]["movies"]))
        self.assertNotIn("titles", files["index.json"])

    def test_merge_by_cross_provider_id_but_never_by_title(self):
        a = record()
        b = record(kp=None, tmdb=550, name="Fight Club")
        b["id"]["imdb"] = None
        c = record(kind="tv", kp=123, tmdb=550)
        d = record(imdb="tt002", kp=999, tmdb=999, name="Бойцовский клуб")
        rows = merge_catalog([[a], [b, c, d]])
        self.assertEqual(len(rows), 3)
        self.assertIn("Бойцовский клуб", rows[0]["aliases"])
        self.assertEqual(rows[0]["ids"]["kinopoisk"], "361")

    @patch("scripts.build_catalog.fetch")
    def test_cinemeta_background_survives_snapshot(self, fetch):
        raw = record()
        raw["images"] = {"poster": raw["images"]["poster"]}
        row = normalize(raw)
        fetch.return_value = {"meta": {"background": "https://example.org/wide.jpg"}}
        enrich(row)
        self.assertTrue(row["images"]["backdropVerified"])
        self.assertEqual(row["images"]["backdrop"], "https://example.org/wide.jpg")

    @patch("scripts.build_catalog.fetch")
    def test_tvmaze_background_used_after_empty_cinemeta(self, fetch):
        raw = record(kind="tv")
        raw["images"] = {"poster": raw["images"]["poster"]}
        row = normalize(raw)
        fetch.side_effect = [{"meta": {}}, {"id": 1}, [{"type": "background",
            "resolutions": {"original": {"url": "https://example.org/tv-wide.jpg"}}}]]
        enrich(row)
        self.assertTrue(row["images"]["backdropVerified"])
        self.assertEqual(row["images"]["backdrop"], "https://example.org/tv-wide.jpg")

    @patch("scripts.build_catalog.fetch")
    def test_no_unnecessary_extra_calls_when_art_is_complete(self, fetch):
        row = normalize(record())
        enrich(row)
        fetch.assert_not_called()

    @patch("scripts.build_catalog.fetch")
    def test_base_lists_deduplicated_and_bounded(self, fetch):
        def canned(url, **kwargs):
            if url.endswith("/catalog/filters"):
                return {"data": {"genres": [{"name": "драма", "titles": 10}]}}
            if url.endswith("/releases/calendar"):
                return {"data": [record()]}
            if "/titles/latest" in url or "/anime?" in url:
                return {"data": [record()]}
            return {"data": [record()], "meta": {"hasNext": False}}
        fetch.side_effect = canned
        result = build("dummy-key", pages=5, enrichment=0, availability=0)
        self.assertEqual(result["count"], 1)
        self.assertEqual(result["quality"]["movies"], 1)
        self.assertEqual(result["filters"]["genres"][0]["name"], "драма")
        self.assertEqual(result["releaseCalendar"][0]["playableSources"], [])
        self.assertEqual(fetch.call_count, 6)  # movie, tv, latest, anime, filters, calendar

    @patch("scripts.build_catalog.fetch")
    def test_tv_detail_bundle_contains_series_navigation(self, fetch):
        def canned(url, **kwargs):
            if "/credits" in url:
                return {"data": [{"id": 1, "profession": "actor", "name_ru": "Актёр"}]}
            if "/recommendations" in url:
                return {"data": [record()]}
            if "/similar" in url or "/schedule" in url:
                return {"data": []}
            if url.endswith("/seasons"):
                return {"data": [{"season_number": 1, "name_ru": "Первый", "episode_count": 1}]}
            if url.endswith("/episodes"):
                return {"data": [{"episode_number": 1, "name_ru": "Начало", "still_path": "https://example.org/wide.jpg"}]}
            raise AssertionError(url)
        fetch.side_effect = canned
        row = normalize(record(kind="tv"))
        details(row, "dummy-key", season_limit=1)
        self.assertEqual(row["details"]["seasons"][0]["episodes"][0]["number"], 1)
        self.assertEqual(row["details"]["cast"][0]["name"], "Актёр")
        self.assertEqual(row["details"]["recommendations"][0]["playableSources"], [])


if __name__ == "__main__":
    unittest.main()
