package com.guaguapass;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.security.MessageDigest;

/**
 * Auto-actualizacion estilo ArcaneChat: la propia APK consulta el SERVIDOR DE
 * CORREO (arcanechat.me) que ademas de sincronizar los buzones <usuario>@
 * arcanechat.me reparte la ultima compilacion de GuaguaPass.apk, igual que
 * arcanechat.apk se actualiza desde su servidor.
 *
 *   1) GET /api/update?version=X  -> {update, latest, url, size, sha256}
 *      El servidor recompila la APK automaticamente cuando cambian los fuentes.
 *   2) Si hay novedad: GET <url> descarga GuaguaPass-update.apk al cache dir
 *      y se lanza ACTION_VIEW con intent de instalacion (el usuario confirma).
 */
public class Updater {

    public static class Info {
        public boolean update;
        public String latest, url, sha256;
        public long size;
    }

    /** Comprueba novedades en segundo plano; llama al callback en hilo UI-less. */
    public static void check(final Context ctx, final Listener listener) {
        new Thread(() -> {
            Info info = null;
            try {
                String resp = Api.get("/api/update?version=" + Identity.APP_VERSION);
                info = parse(resp);
            } catch (Exception e) {
                // sin servidor de updates accesible: silencio
            }
            if (listener != null) listener.onResult(info);
        }).start();
    }

    public interface Listener { void onResult(Info info); }

    static Info parse(String json) {
        Info i = new Info();
        i.update = json.contains("\"update\":true");
        i.latest = Api.jstr(json, "latest");
        i.url = Api.jstr(json, "url");
        i.sha256 = Api.jstr(json, "sha256");
        try { i.size = Long.parseLong(LoginActivity.extractNum(json, "size")); } catch (Exception e) {}
        return i;
    }

    /** Descarga la nueva APK y abre el instalador del sistema. */
    public static File downloadAndInstall(Context ctx, Info info) throws Exception {
        File dest = new File(ctx.getExternalCacheDir() != null
                ? ctx.getExternalCacheDir() : ctx.getCacheDir(), Identity.UPDATE_APK_NAME);
        Api.download(info.url, dest);
        if (info.sha256 != null && !info.sha256.isEmpty()) {
            String got = sha256(dest);
            if (got != null && !got.equalsIgnoreCase(info.sha256)) {
                dest.delete();
                throw new Exception("checksum sha256 no coincide");
            }
        }
        Uri uri = Uri.fromFile(dest); // app sin androidx: file:// (targetSdk 34 pide
        // REQUEST_INSTALL_PACKAGES; si el ROM lo rechaza, el gestor puede abrir
        // https://arcanechat.me/apk/GuaguaPass.apk en el navegador).
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(it);
        return dest;
    }

    private static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.io.InputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
