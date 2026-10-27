"""Small live integration check. Requests metadata only; never starts a torrent."""
import json
import urllib.parse
import urllib.request


def select(core, query, fields):
    params = urllib.parse.urlencode({"wt": "json", "q": query, "rows": 1, "fl": fields})
    url = f"https://zsolr3.zonasearch.com/solr/{core}/select?{params}"
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.load(response)["response"]


if __name__ == "__main__":
    movie = select("movie", "id:447301", "id,name_rus,year")
    assert movie["docs"][0]["year"] == 2010, movie
    torrents = select("torrent", "kinopoisk_id:447301 AND playable:true AND trailer:false", "id,kinopoisk_id")
    assert torrents["numFound"] > 0, torrents
    assert torrents["docs"][0]["kinopoisk_id"] == 447301, torrents
    print("Live catalog and movie-to-torrent relation passed")
