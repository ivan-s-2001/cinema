"""Offline data-merging tests; never require API credentials or network access."""
import unittest
from unittest.mock import patch

from scripts.build_catalog import build, details, enrich, merge_catalog, normalize


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
    def test_art_aliases_voiceovers_are_not_streams(self):
        row = normalize(record())
        self.assertEqual(row["images"]["backdrop"], "https://image.tmdb.org/t/p/original/landscape.jpg")
        self.assertEqual(row["images"]["poster"], "https://image.tmdb.org/t/p/original/portrait.jpg")
        self.assertIn("Fight Club", row["aliases"])
        self.assertEqual(row["voiceoverLabels"], ["Дублированный", "Субтитры"])
        self.assertEqual(row["playableSources"], [])

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
        result = build("dummy-key", pages=5, enrichment=0)
        self.assertEqual(result["count"], 1)
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
