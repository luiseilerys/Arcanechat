package com.guaguapass;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/**
 * Identidad derivada estilo ARCANECHAT / chatmail.
 *
 * En chatmail la app se inventa <aleatorio>@dominio + password de alta entropia
 * y los registra en el endpoint secreto del servidor (/secret-api/new-user).
 * Aqui hacemos lo mismo: la APK deriva UNA VEZ un par {login, password} desde
 * un seed aleatorio guardado en SharedPreferences (persiste entre reinstalaciones?
 * no: persiste mientras no borres datos; si se pierden, se crea gestor nuevo).
 *
 *   login    = "gg-" + 6 consonantes/vocales alternadas  (ej. gg-mofaru)
 *   password = 20 chars base36 de alta entropia (SecureRandom)
 *
 * Esas credenciales se envian a POST /api/auto-register con la cabecera
 * X-Provision-Key; el servidor crea la cuenta al vuelo o autentica la existente.
 */
public class Identity {

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
        if (login == null || password == null) {
            SecureRandom rnd = new SecureRandom();
            StringBuilder lg = new StringBuilder("gg-");
            for (int i = 0; i < 3; i++) {
                lg.append(ABIL.charAt(rnd.nextInt(ABIL.length())));
                lg.append(VOW.charAt(rnd.nextInt(VOW.length())));
            }
            StringBuilder pw = new StringBuilder();
            byte[] b = new byte[20];
            rnd.nextBytes(b);
            for (int i = 0; i < b.length; i++) pw.append(PWD_ALPHA.charAt((b[i] & 0xff) % PWD_ALPHA.length()));
            login = lg.toString();
            password = pw.toString();
            name = "Gestor " + login.substring(3);
            p.edit().putString("id_login", login).putString("id_pass", password)
                        .putString("id_name", name).apply();
        }
    }

    public static String login()   { return login; }
    public static String password(){ return password; }
    public static String name()    { return name; }
}
