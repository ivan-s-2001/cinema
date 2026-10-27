package app.cinemahall.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class Net {
    static final String CATALOG = "https://zsolr3.zonasearch.com/solr/";
    static int ENGINE_PORT = 18990;
    static String ENGINE = "http://127.0.0.1:18990";
    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    static byte[] request(String url, JSONObject body, int limit) throws Exception {
        return request(url, body, limit, "");
    }
    static byte[] request(String url, JSONObject body, int limit, String apiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "CinemaHall/0.3");
        if (!apiKey.isEmpty()) {
            c.setInstanceFollowRedirects(false); // Never forward credentials to a redirected host.
            c.setRequestProperty("X-API-Key", apiKey);
        }
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Сервер ответил HTTP " + status);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (out.size() + n > limit) throw new IOException("Слишком большой ответ сервера");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
    static JSONObject json(String url, JSONObject body) throws Exception {
        byte[] bytes = request(url, body, 8 * 1024 * 1024);
        return bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    static JSONObject json(String url, String apiKey) throws Exception {
        byte[] bytes = request(url, null, 8 * 1024 * 1024, apiKey);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    static JSONObject select(String core, String query, String sort, int start, int rows, String fields) throws Exception {
        return json(CATALOG + core + "/select?wt=json&q=" + enc(query) + "&sort=" + enc(sort)
            + "&start=" + start + "&rows=" + rows + "&fl=" + enc(fields), (JSONObject) null).getJSONObject("response");
    }
    static JSONObject action(String path, String action) throws Exception {
        return json(ENGINE + path, new JSONObject().put("action", action));
    }
}
