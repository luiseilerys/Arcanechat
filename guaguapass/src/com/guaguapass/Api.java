package com.guaguapass;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Cliente HTTP minimo contra el servidor de sincronizacion (Node.js, ver server/). */
public class Api {
    public static String BASE = "http://192.168.1.100:3000"; // reconfigurable en Login

    public static class ApiException extends Exception {
        public final int code;
        public final String body;
        ApiException(int code, String body) { super("HTTP " + code + ": " + body); this.code = code; this.body = body; }
    }

    public static String get(String path) throws Exception {
        return request("GET", path, null);
    }

    public static String post(String path, String json) throws Exception {
        return request("POST", path, json);
    }

    private static synchronized String request(String method, String path, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String token = Session.token(null);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (json != null) {
                c.setDoOutput(true);
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = readAll(is);
            if (code >= 400) throw new ApiException(code, body);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** JSON string minimalista: extrae el valor de una clave string. */
    public static String jstr(String json, String key) {
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        i += k.length();
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\' && j + 1 < json.length()) {
                char nx = json.charAt(++j);
                switch (nx) { case 'n': sb.append('\n'); break; case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break; default: sb.append(nx); }
            } else if (ch == '"') break;
            else sb.append(ch);
        }
        return sb.toString();
    }

    public static void saveBaseUrl(Context ctx, String url) {
        BASE = url.replaceAll("/+$", "");
        ctx.getSharedPreferences("guagua", Context.MODE_PRIVATE)
           .edit().putString("base", BASE).apply();
    }

    public static String loadBaseUrl(Context ctx) {
        String s = ctx.getSharedPreferences("guagua", Context.MODE_PRIVATE).getString("base", null);
        if (s != null) BASE = s;
        return BASE;
    }
}
