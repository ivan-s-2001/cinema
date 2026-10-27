package app.cinemahall.tv;

import android.content.Context;
import android.content.SharedPreferences;
import com.arflix.tv.BuildConfig;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Best-effort metadata enrichment; catalogue and playback never depend on these APIs. */
final class Metadata {
    static final String CINEMETA = "https://v3-cinemeta.strem.io";
    static final String TVMAZE = "https://api.tvmaze.com";
    static final String TMDB = "https://api.themoviedb.org/3";
    private static final long DAY = 86400000L;

    static JSONArray array(String url) throws Exception {
        return new JSONArray(new String(Net.request(url, null, 8 * 1024 * 1024), StandardCharsets.UTF_8));
    }

    private static List<String> titles(JSONObject film) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String field : new String[]{"name_original", "name_eng", "name_rus"}) {
            String name = film.optString(field).trim();
            if (!name.isEmpty()) names.add(name);
        }
        return new ArrayList<>(names);
    }

    private static JSONObject cinemetaSearch(String type, List<String> names, int year) {
        for (String name : names) {
            try {
                JSONArray hits = Net.json(CINEMETA + "/catalog/" + type + "/top/search=" + Net.enc(name) + ".json", null).optJSONArray("metas");
                if (hits == null) continue;
                for (int i = 0; i < hits.length(); i++) {
                    JSONObject hit = hits.getJSONObject(i);
                    if (Logic.sameTitle(name, year, hit.optString("name"), hit.optString("releaseInfo"))) return hit;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void cinemetaDetails(JSONObject result, String type, String imdb) {
        try {
            JSONObject full = Net.json(CINEMETA + "/meta/" + type + "/" + imdb + ".json", null).getJSONObject("meta");
            for (String field : new String[]{"background", "poster", "cast", "director", "imdbRating", "description"}) {
                if (full.has(field) && !full.isNull(field)) result.put(field, full.get(field));
            }
        } catch (Exception ignored) {}
    }

    /** Match a Kinopoisk ID to IMDb without guessing between remakes with identical names. */
    private static String wikidataImdb(String kinopoiskId) {
        if (!kinopoiskId.matches("[0-9]{1,9}")) return "";
        try {
            String sparql = "SELECT ?imdb WHERE { ?work <http://www.wikidata.org/prop/direct/P2603> \"" +
                kinopoiskId + "\"; <http://www.wikidata.org/prop/direct/P345> ?imdb. } LIMIT 1";
            JSONArray bindings = Net.json("https://query.wikidata.org/sparql?format=json&query=" + Net.enc(sparql), null)
                .getJSONObject("results").getJSONArray("bindings");
            for (int i = 0; i < bindings.length(); i++) {
                String id = bindings.getJSONObject(i).getJSONObject("imdb").optString("value");
                if (id.matches("tt[0-9]+")) return id;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static void tvmaze(JSONObject result, List<String> names, int year) {
        try {
            JSONObject show = null;
            String imdb = result.optString("imdb");
            if (imdb.matches("tt[0-9]+")) {
                try { show = Net.json(TVMAZE + "/lookup/shows?imdb=" + imdb, null); } catch (Exception ignored) {}
            }
            if (show == null) for (String name : names) {
                try {
                    JSONArray hits = array(TVMAZE + "/search/shows?q=" + Net.enc(name));
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject candidate = hits.getJSONObject(i).getJSONObject("show");
                        if (Logic.sameTitle(name, year, candidate.optString("name"), candidate.optString("premiered"))) {
                            show = candidate;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
                if (show != null) break;
            }
            if (show == null) return;
            int id = show.getInt("id");
            result.put("tvmaze", show.optString("url")).put("status", show.optString("status"));
            result.put("episodes", array(TVMAZE + "/shows/" + id + "/episodes"));
            JSONObject mainImage = show.optJSONObject("image");
            if (result.optString("poster").isEmpty() && mainImage != null) {
                result.put("poster", mainImage.optString("original"));
            }
            if (result.optString("background").isEmpty()) {
                JSONArray images = array(TVMAZE + "/shows/" + id + "/images");
                int bestWidth = 0;
                for (int i = 0; i < images.length(); i++) {
                    JSONObject item = images.getJSONObject(i);
                    if (!"background".equals(item.optString("type"))) continue;
                    JSONObject original = item.optJSONObject("resolutions") == null ? null :
                        item.getJSONObject("resolutions").optJSONObject("original");
                    if (original != null && !original.optString("url").isEmpty()) {
                        int width = original.optInt("width", 1);
                        if (width > bestWidth) {
                            result.put("background", original.getString("url"));
                            bestWidth = width;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    static String tmdbArtwork(String path, boolean backdrop) {
        if (!path.matches("/[A-Za-z0-9._-]+\\.(?:jpg|jpeg|png|webp)")) return "";
        return "https://image.tmdb.org/t/p/" + (backdrop ? "original" : "w780") + path;
    }

    /** Optional provider: TMDB requires a free developer key and source attribution. */
    private static void tmdb(JSONObject result, JSONObject film, List<String> names, boolean serial) {
        String key = BuildConfig.TMDB_API_KEY.trim();
        if (key.isEmpty()) return;
        String type = serial ? "tv" : "movie";
        String auth = "api_key=" + Net.enc(key);
        int id = 0;
        try {
            String imdb = result.optString("imdb");
            if (imdb.matches("tt[0-9]+")) {
                JSONObject found = Net.json(TMDB + "/find/" + imdb + "?" + auth + "&external_source=imdb_id", null);
                JSONArray matches = found.optJSONArray(type + "_results");
                if (matches != null && matches.length() > 0) id = matches.getJSONObject(0).optInt("id");
            }
            if (id == 0) for (String title : names) {
                JSONArray hits = Net.json(TMDB + "/search/" + type + "?" + auth + "&query=" +
                    Net.enc(title) + "&language=ru-RU&include_adult=false", null).optJSONArray("results");
                if (hits == null) continue;
                for (int i = 0; i < Math.min(20, hits.length()); i++) {
                    JSONObject hit = hits.getJSONObject(i);
                    String date = hit.optString(serial ? "first_air_date" : "release_date");
                    String original = hit.optString(serial ? "original_name" : "original_title");
                    String localized = hit.optString(serial ? "name" : "title");
                    if (Logic.sameTitle(title, film.optInt("year"), original, date) ||
                        Logic.sameTitle(title, film.optInt("year"), localized, date)) {
                        id = hit.optInt("id");
                        break;
                    }
                }
                if (id > 0) break;
            }
            if (id <= 0) return;
            JSONObject detail = Net.json(TMDB + "/" + type + "/" + id + "?" + auth + "&language=ru-RU", null);
            result.put("tmdb", id);
            String poster = tmdbArtwork(detail.optString("poster_path"), false);
            String background = tmdbArtwork(detail.optString("backdrop_path"), true);
            if (!poster.isEmpty()) result.put("poster", poster);
            if (!background.isEmpty()) result.put("background", background);
            if (film.optString("description").isEmpty() && !detail.optString("overview").isEmpty()) {
                result.put("description", detail.getString("overview"));
            }
            if (film.optString("genre_name").isEmpty()) {
                JSONArray genres = detail.optJSONArray("genres");
                if (genres != null) {
                    JSONArray values = new JSONArray();
                    for (int i = 0; i < genres.length(); i++) {
                        String name = genres.getJSONObject(i).optString("name");
                        if (!name.isEmpty()) values.put(name);
                    }
                    result.put("genres", values);
                }
            }
        } catch (Exception ignored) {}
    }

    private static JSONObject bestFanart(JSONArray images, boolean portrait) {
        JSONObject best = null;
        long highest = -1;
        if (images == null) return null;
        for (int i = 0; i < images.length(); i++) {
            JSONObject item = images.optJSONObject(i);
            if (item == null || !item.optString("url").startsWith("https://")) continue;
            int width = item.optInt("width"), height = item.optInt("height");
            if (width < 1 || height < 1) continue;
            double ratio = (double) width / height;
            if (portrait ? (ratio < 0.55 || ratio > 0.85 || height < 1200) :
                (ratio < 1.55 || ratio > 2.1 || width < 1280)) continue;
            String lang = item.optString("lang");
            int preferred = "ru".equals(lang) ? 400 : "en".equals(lang) ? 200 : 0;
            long score = ((long) width * height) / 10000 + preferred + item.optInt("likes") * 5L;
            if (score > highest) { best = item; highest = score; }
        }
        return best;
    }

    private static JSONObject bestLogo(JSONArray images) {
        JSONObject best = null;
        long score = -1;
        if (images == null) return null;
        for (int i = 0; i < images.length(); i++) {
            JSONObject item = images.optJSONObject(i);
            if (item == null || !item.optString("url").startsWith("https://")) continue;
            int width = item.optInt("width"), height = item.optInt("height");
            if (width < 400 || height < 100 || width < height) continue;
            long candidate = (long) width * height / 10000 + item.optInt("likes") * 5L +
                ("ru".equals(item.optString("lang")) ? 400 : 0);
            if (candidate > score) { best = item; score = candidate; }
        }
        return best;
    }

    /** Optional fan artwork. Never mix records by title: IMDb is the shared identity. */
    private static void fanart(JSONObject result, boolean serial) {
        String key = BuildConfig.FANART_API_KEY.trim();
        String imdb = result.optString("imdb");
        if (key.isEmpty() || !imdb.matches("tt[0-9]+")) return;
        try {
            JSONObject art = Net.json("https://webservice.fanart.tv/v3.2/" +
                (serial ? "tv" : "movies") + "/" + imdb +
                "?source=imdb&api_key=" + Net.enc(key), null);
            JSONObject backdrop = bestFanart(art.optJSONArray(serial ? "showbackground" : "moviebackground"), false);
            JSONObject poster = bestFanart(art.optJSONArray(serial ? "tvposter" : "movieposter"), true);
            JSONObject logo = bestLogo(art.optJSONArray(serial ? "hdtvlogo" : "hdmovielogo"));
            if (logo == null) logo = bestLogo(art.optJSONArray(serial ? "clearlogo" : "movielogo"));
            if (logo != null) result.put("logo", logo.getString("url"));
            if (backdrop != null && (result.optString("background").isEmpty() ||
                (!result.has("tmdb") && backdrop.optInt("width") >= 2500))) {
                result.put("background", backdrop.getString("url"));
            }
            if (poster != null && (result.optString("poster").isEmpty() ||
                (!result.has("tmdb") && poster.optInt("height") >= 2000))) {
                result.put("poster", poster.getString("url"));
            }
        } catch (Exception ignored) {}
    }

    /** Optional richer credits; OMDb may be used only under its data licence. */
    private static void omdb(JSONObject result, JSONObject film) {
        String key = BuildConfig.OMDB_API_KEY.trim();
        String imdb = result.optString("imdb");
        if (key.isEmpty() || !imdb.matches("tt[0-9]+")) return;
        try {
            JSONObject info = Net.json("https://www.omdbapi.com/?apikey=" + Net.enc(key) +
                "&i=" + imdb + "&plot=full", null);
            if (!"True".equalsIgnoreCase(info.optString("Response")) ||
                !imdb.equalsIgnoreCase(info.optString("imdbID"))) return;
            String director = info.optString("Director");
            String actors = info.optString("Actors");
            ArrayList<String> credits = new ArrayList<>();
            if (!director.isEmpty() && !"N/A".equals(director)) credits.add("Режиссёр: " + director);
            if (!actors.isEmpty() && !"N/A".equals(actors)) credits.add("В ролях: " + actors);
            if (!credits.isEmpty()) result.put("credits", String.join("\n", credits));
            if (film.optString("description").isEmpty() && result.optString("description").isEmpty() &&
                !"N/A".equals(info.optString("Plot"))) result.put("description", info.optString("Plot"));
        } catch (Exception ignored) {}
    }

    static JSONObject load(Context context, JSONObject film, boolean details) {
        SharedPreferences cache = context.getSharedPreferences("metadata-v2", 0);
        String key = film.optString("id") + (details ? ":detail" : ":art");
        JSONObject result = new JSONObject();
        try {
            String stored = cache.getString(key, "");
            if (!stored.isEmpty()) {
                result = new JSONObject(stored);
                if (System.currentTimeMillis() - result.optLong("fetched") < (result.optBoolean("matched") ? 7 * DAY : DAY / 24)) {
                    return result;
                }
            }
            result = new JSONObject();
            boolean serial = film.optBoolean("serial");
            String type = serial ? "series" : "movie";
            List<String> names = titles(film);
            JSONObject match = cinemetaSearch(type, names, film.optInt("year"));
            if (match != null && match.optString("id").matches("tt[0-9]+")) {
                result.put("imdb", match.getString("id"));
                result.put("background", match.optString("background"));
                result.put("poster", match.optString("poster"));
            }
            if (details) {
                if (!result.has("imdb")) {
                    String mapped = wikidataImdb(film.optString("id"));
                    if (!mapped.isEmpty()) result.put("imdb", mapped);
                }
                if (result.has("imdb")) cinemetaDetails(result, type, result.getString("imdb"));
                if (serial) tvmaze(result, names, film.optInt("year"));
                tmdb(result, film, names, serial);
                fanart(result, serial);
                omdb(result, film);
            }
            boolean matched = result.has("imdb") || result.has("tvmaze") || result.has("tmdb");
            result.put("matched", matched).put("fetched", System.currentTimeMillis());
            cache.edit().putString(key, result.toString()).apply();
        } catch (Exception ignored) {}
        return result;
    }
}
