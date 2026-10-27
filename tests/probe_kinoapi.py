"""Read-only KinoAPI contract probe. Optional auth via KINOAPI_KEY environment variable."""
import json
import os
import urllib.request


BASE = "https://kinoapi.lampac.dev/api/v1"


def get(path, key=""):
    headers = {"User-Agent": "CinemaHall/0.3"}
    if key:
        headers["X-API-Key"] = key
    with urllib.request.urlopen(urllib.request.Request(BASE + path, headers=headers), timeout=25) as response:
        return json.load(response)


if __name__ == "__main__":
    public = get("/search/suggest?q=matrix&limit=2")
    assert isinstance(public["data"], list) and public["data"], "No public suggestions"
    assert public["data"][0]["id"] and public["data"][0]["title_ru"], "Suggestion shape changed"
    key = os.environ.get("KINOAPI_KEY", "")
    if key:
        result = get("/titles/by/kinopoisk/301?include=images", key)
        title = result["data"]
        assert str(title["id"]["kinopoisk"]) == "301", "Kinopoisk mapping changed"
        assert "images" in title and "voiceovers" in title, "Title schema changed"
    print("KinoAPI public contract OK; private metadata " + ("OK" if key else "not tested (no KINOAPI_KEY)"))
