package com.guaguapass;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/**
 * Identidad derivada estilo ARCANECHAT / chatmail.
 *
 * En ArcaneChat (chatmail) la app se inventa una direccion de correo
 * <aleatorio>@arcanechat.me + password de alta entropia y la registra en el
 * endpoint secreto del servidor (/secret-api/new-user). El chat viaja por
 * email (SMTP/IMAP sobre el dominio arcanechat.me).
 *
 * Aqui hacemos lo mismo: la APK deriva UNA VEZ un par {email, password} desde
 * un seed aleatorio guardado en SharedPreferences (persiste mientras no borres
 * datos; si se pierden, se crea un gestor nuevo).
 *
 *   email    = 3 silabas (consonante+vocal) + "@arcanechat.me"  (ej. mofaru@arcanechat.me)
 *   password = 20 chars alfanumericos de alta entropia (SecureRandom)
 *
 * Esas credenciales se envian a POST /api/auto-register con la cabecera
 * X-Provision-Key; el servidor crea la cuenta al vuelo (buzon de correo
 * incluido) o autentica la existente.
 */
public class Identity {

    /** Dominio de correo/servidor igual que la APK ArcaneChat. */
    public static final String DOMAIN = "arcanechat.me";

    /** URL base del servidor de sincronizacion + correo (igual que ArcaneChat). */
    public static final String SERVER_URL = "https://" + DOMAIN;

    /** Clave de provision embebida (equivalente al endpoint secreto de chatmail).
     *  Cambiarla aqui y en el server (GUAGUA_PROVISION_KEY) para producción. */
    public static final String PROVISION_KEY = "guagua-provision-key";

    private static final String ABIL = "bcdfghjklmnpqrstvwxz";
    private static final String VOW = "aeiou";
    private static final String PWD_ALPHA = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static String login, password, name;

    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("guagua", Context.MODE_PRIVATE);
        login = p.getString("id_login", null);
        password = p.getString("id_pass", null);
        name = p.getString("id_name", null);
        // Migracion: identidades antiguas "gg-xxxxxx" pasan a formato correo.
        if (login != null && login.startsWith("gg-") && !login.contains("@")) {
            login = login.substring(3) + "@" + DOMAIN;
            p.edit().putString("id_login", login).apply();
        }
        if (login == null || password == null) {
            SecureRandom rnd = new SecureRandom();
            StringBuilder local = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                local.append(ABIL.charAt(rnd.nextInt(ABIL.length())));
                local.append(VOW.charAt(rnd.nextInt(VOW.length())));
            }
            StringBuilder pw = new StringBuilder();
            byte[] b = new byte[20];
            rnd.nextBytes(b);
            for (int i = 0; i < b.length; i++) pw.append(PWD_ALPHA.charAt((b[i] & 0xff) % PWD_ALPHA.length()));
            login = local.toString() + "@" + DOMAIN;   // ej. mofaru@arcanechat.me
            password = pw.toString();
            name = "Gestor " + local;
            p.edit().putString("id_login", login).putString("id_pass", password)
                        .putString("id_name", name).apply();
        }
    }

    /** Direccion de correo generada (usuario), ej. mofaru@arcanechat.me. */
    public static String login()   { return login; }
    public static String password(){ return password; }
    public static String name()    { return name; }
}
