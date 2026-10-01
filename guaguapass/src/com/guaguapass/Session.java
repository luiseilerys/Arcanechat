package com.guaguapass;

import android.content.Context;
import android.content.SharedPreferences;

/** Sesion del gestor: usuario, id y token JWT (persistidos localmente). */
public class Session {
    private static String user, pass, token;
    private static int uid = -1;

    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("guagua", Context.MODE_PRIVATE);
        user = p.getString("user", null);
        pass = p.getString("pass", null);
        token = p.getString("token", null);
        uid = p.getInt("uid", -1);
    }

    public static void set(Context ctx, String u, String pw, String tk, int id) {
        user = u; pass = pw; token = tk; uid = id;
        p(ctx).edit().putString("user", u).putString("pass", pw)
               .putString("token", tk).putInt("uid", id).apply();
    }

    public static void clear(Context ctx) {
        user = null; pass = null; token = null; uid = -1;
        p(ctx).edit().remove("user").remove("pass").remove("token").remove("uid").apply();
    }

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences("guagua", Context.MODE_PRIVATE);
    }

    public static String user() { return user; }
    public static String pass() { return pass; }
    public static Integer uidObj() { return uid < 0 ? null : uid; }
    public static int uid(Context ctx) {
        if (uid < 0) load(ctx);
        return uid;
    }
    public static String token(Context ctx) {
        if (token == null && ctx != null) load(ctx);
        return token;
    }
}
