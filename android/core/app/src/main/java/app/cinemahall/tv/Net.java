package app.cinemahall.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

final class Net {
    private static final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"cinemahall-network-deadlines");t.setDaemon(true);return t;});
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
        return requestUntil(url,body,limit,apiKey,System.nanoTime()+TimeUnit.SECONDS.toNanos(25));
    }
    static byte[] requestUntil(String url, JSONObject body, int limit, String apiKey, long deadline) throws Exception {
        return requestUntil(url,body,limit,apiKey,deadline,true);
    }
    static byte[] requestUntil(String url, JSONObject body, int limit, String apiKey, long deadline, boolean followRedirects) throws Exception {
        long remaining=deadline-System.nanoTime();
        if(remaining<=0)throw new SocketTimeoutException("Video preparation timed out");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(followRedirects && apiKey.isEmpty());
        int timeout=(int)Math.min(15000,Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
        c.setConnectTimeout(Math.min(10000,timeout)); c.setReadTimeout(timeout);
        ScheduledFuture<?> cancellation=deadlines.schedule(c::disconnect,remaining,TimeUnit.NANOSECONDS);
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
            if(c.getContentLengthLong()>limit)throw new IOException("Слишком большой ответ сервера");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if(System.nanoTime()>=deadline)throw new SocketTimeoutException("Video preparation timed out");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (out.size() + n > limit) throw new IOException("Слишком большой ответ сервера");
                    out.write(buf, 0, n);
                }
                if(System.nanoTime()>=deadline)throw new SocketTimeoutException("Video preparation timed out");
                return out.toByteArray();
            }
        } finally { cancellation.cancel(false);c.disconnect(); }
    }
    static void verifyStreamBytes(String url,int timeoutMs) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(Math.min(1500,timeoutMs));c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Range","bytes=0-16383");
        ScheduledFuture<?> cancellation=deadlines.schedule(c::disconnect,Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        try {
            int status=c.getResponseCode();if(status!=200&&status!=206)throw new IOException("Video source HTTP "+status);
            try(InputStream input=c.getInputStream()){
                byte[] first=new byte[4096];if(input.read(first)<=0)throw new EOFException("Video source returned no bytes");
            }
        } finally { cancellation.cancel(false);c.disconnect(); }
    }
    static JSONObject jsonUntil(String url,JSONObject body,long deadline) throws Exception {
        byte[] bytes=requestUntil(url,body,8*1024*1024,"",deadline);
        return bytes.length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));
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
