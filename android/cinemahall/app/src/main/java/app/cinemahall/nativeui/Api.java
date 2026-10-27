package app.cinemahall.nativeui;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class Api {
    private Api() {}
    static JSONObject get(Context context, String path) throws Exception {
        int port = context.getPackageName().contains("mobile") ? 18992 : 18991;
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Каталог временно недоступен");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Слишком большой ответ");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            connection.disconnect();
        }
    }
}
