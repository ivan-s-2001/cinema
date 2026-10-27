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
            String poster = images.optString("poster");
            if (!poster.startsWith("https://")) poster = first(images.optJSONArray("posters"));
            String backdrop = images.optString("backdrop");
            if (!backdrop.startsWith("https://")) backdrop = first(images.optJSONArray("backdrops"));
            if (poster.startsWith("https://")) result.put("poster", poster);
            if (backdrop.startsWith("https://")) result.put("background", backdrop);
        }
        if (details) {
            // Voiceovers here are descriptive metadata, not verified playable tracks.
            for (String field : new String[]{"ratings", "genres", "countries", "voiceovers", "trailers"})
                if (title.has(field)) result.put(field, title.get(field));
            result.put("kinoapi", true);
        }
    }

    private static String first(JSONArray items) {
        return items == null || items.length() == 0 ? "" : items.optString(0);
    }
}
