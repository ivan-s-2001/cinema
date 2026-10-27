package app.cinemahall.nativeui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;

final class Artwork {
    private static final ExecutorService posters = Executors.newFixedThreadPool(3);
    private static final ExecutorService backdrops = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
    };
    private Artwork() {}

    static void into(ImageView target, String url, int width, int height, boolean hero) {
        final String key = url + "|" + width + "x" + height;
        target.setTag(key);
        target.setImageDrawable(null);
        if (url == null || !url.startsWith("https://")) return;
        Bitmap hit;
        synchronized (cache) { hit = cache.get(key); }
        if (hit != null) { target.setImageBitmap(hit); return; }
        (hero ? backdrops : posters).execute(() -> {
            Bitmap result = fetch(url, width, height);
            if (result != null) synchronized (cache) { cache.put(key, result); }
            target.post(() -> {
                if (key.equals(target.getTag()) && result != null) target.setImageBitmap(result);
            });
        });
    }
    private static Bitmap fetch(String url, int width, int height) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(7000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (output.size() + read > 5 * 1024 * 1024) return null;
                    output.write(buffer, 0, read);
                }
                byte[] bytes = output.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth < 1 || options.outHeight < 1) return null;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > width * 2
                        && options.outHeight / options.inSampleSize > height * 2) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            }
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
