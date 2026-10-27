package app.cinemahall.tv;

import org.json.*;

/** Metadata provider. KinoAPI never supplies playable video URLs. */
final class KinoApi {
    private static final String BASE = "https://kinoapi.lampac.dev/api/v1";

    static JSONObject byKinopoisk(String id, String key) throws Exception {
        if (key == null || key.trim().isEmpty() || id == null || !id.matches("[0-9]+")) return null;
        JSONObject response = Net.json(BASE + "/titles/by/kinopoisk/" + id + "?include=images", key.trim());
        JSONObject title = response.optJSONObject("data");
        if (title == null || title.optJSONObject("id") == null ||
            !id.equals(title.getJSONObject("id").optString("kinopoisk"))) return null;
        return title;
    }

    static void enrich(JSONObject result, JSONObject title, boolean details) throws JSONException {
        JSONObject ids = title.optJSONObject("id");
        JSONObject images = title.optJSONObject("images");
        if (ids != null && ids.optString("imdb").matches("tt[0-9]+")) result.put("imdb", ids.getString("imdb"));
        if (images != null) {
            String poster = valid(images.optString("poster"));
            if (poster.isEmpty()) poster = first(images.optJSONArray("posters"), "");
            JSONObject kp = images.optJSONObject("kinopoisk");
            if (poster.isEmpty() && kp != null) poster = valid(kp.optString("poster"));
            String backdrop = valid(images.optString("backdrop"));
            if (backdrop.equals(poster)) backdrop = "";
            if (backdrop.isEmpty()) backdrop = firstBackdrop(images.optJSONArray("backdrops"), poster, images.optJSONArray("posters"));
            if (!poster.isEmpty()) result.put("poster", poster);
            if (!backdrop.isEmpty()) result.put("background", backdrop);
            if (result.optString("background").equals(result.optString("poster"))) result.remove("background");
        }
        if (details) {
            // Voiceovers here are descriptive metadata, not verified playable tracks.
            for (String field : new String[]{"ratings", "genres", "countries", "voiceovers", "trailers"})
                if (title.has(field)) result.put(field, title.get(field));
            result.put("kinoapi", true);
        }
    }

    private static String firstBackdrop(JSONArray items, String poster, JSONArray posters) {
        if (items == null) return "";
        for (int i = 0; i < items.length(); i++) {
            String url = valid(items.optString(i));
            if (url.isEmpty() || url.equals(poster)) continue;
            boolean portrait = false;
            if (posters != null) for (int j = 0; j < posters.length(); j++) {
                if (url.equals(posters.optString(j))) { portrait = true; break; }
            }
            if (!portrait) return url;
        }
        return "";
    }

    private static String valid(String url) {
        return url.startsWith("https://") ? url : "";
    }
    private static String first(JSONArray items, String excluded) {
        if (items != null) for (int i = 0; i < items.length(); i++) {
            String url = valid(items.optString(i));
            if (!url.isEmpty() && !url.equals(excluded)) return url;
        }
        return "";
    }
}
