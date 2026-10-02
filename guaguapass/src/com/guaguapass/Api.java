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
    public static String BASE = Identity.SERVER_URL; // https://arcanechat.me (reconfigurable en Login)

    public static class ApiException extends Exception {
        public final int code;
        public final String body;
        ApiException(int code, String body) { super("HTTP " + code + ": " + body); this.code = code; this.body = body; }
    }

    public static String get(String path) throws Exception {
        return request("GET", path, null, 0);
    }

    public static String post(String path, String json) throws Exception {
        return request("POST", path, json, 0);
    }

    /** POST con cabeceras extra (auto-registro estilo arcanechat). */
    public static String postWith(String path, String json, String header, String value) throws Exception {
        return request("POST", path, json, 0, header, value);
    }

    private static String request(String method, String path, String json, int readTimeoutMs, String... extraHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(5000);
            c.setReadTimeout(readTimeoutMs > 0 ? readTimeoutMs : 7000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            for (int i = 0; i + 1 < extraHeaders.length; i += 2)
                if (extraHeaders[i] != null) c.setRequestProperty(extraHeaders[i], extraHeaders[i + 1]);
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

    /** Descarga un archivo binario (p.ej. /apk/GuaguaPass.apk) a dest. */
    public static void download(String path, java.io.File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 400) throw new ApiException(code, "descarga fallo HTTP " + code);
            java.io.InputStream in = c.getInputStream();
            java.io.File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            java.io.OutputStream out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush(); out.close(); in.close();
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

    // ================= TIEMPO REAL: Server-Sent Events =================
    public interface SseHandler {
        /** event == "state" -> data JSON de /api/state; "conflict" -> aviso. */
        void onEvent(String event, String data);
        void onEnd(Exception err); // stream caido o cancelado
    }

    /** Abre GET /api/events?… con leectura incremental; bloquea el hilo llamante. */
    public static void sse(String path, final SseHandler h, java.util.concurrent.atomic.AtomicBoolean stopFlag) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000); // el servidor manda ": ping" cada 15 s
            c.setRequestProperty("Accept", "text/event-stream");
            String token = Session.token(null);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code >= 400) throw new ApiException(code, readAll(c.getErrorStream()));
            InputStream is = c.getInputStream();
            StringBuilder ev = new StringBuilder(), data = new StringBuilder();
            byte[] buf = new byte[2048];
            StringBuilder text = new StringBuilder();
            while (!stopFlag.get()) {
                int n = is.read(buf);
                if (n < 0) break;
                text.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                int idx;
                while ((idx = text.indexOf("\n")) >= 0) {
                    String line = text.substring(0, idx);
                    text.delete(0, idx + 1);
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (line.isEmpty()) {
                        if (data.length() > 0) h.onEvent(ev.length() > 0 ? ev.toString() : "message", data.toString());
                        ev.setLength(0); data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        ev.setLength(0); ev.append(line.substring(6).trim());
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    } // ": ping" y otros campos se ignoran
                }
            }
            h.onEnd(stopFlag.get() ? null : new Exception("stream cerrado por el servidor"));
        } catch (Exception e) {
            h.onEnd(stopFlag.get() ? null : e);
        } finally {
            if (c != null) c.disconnect();
        }
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
