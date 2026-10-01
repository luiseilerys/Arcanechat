package com.guaguapass;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Login del gestor. Si hay credenciales guardadas, inicia sesion automaticamente. */
public class LoginActivity extends Activity {

    private EditText etUser, etPass, etUrl;
    private TextView tvTitle, tvSub;
    private Button btnLogin, btnAuto;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        Api.loadBaseUrl(this);
        Identity.load(this); // credenciales derivadas estilo chatmail (seed local)

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(40), dp(28), dp(28));
        root.setBackgroundColor(color(R.color.bg));

        // Tarjeta-banner superior con degradado + icono del bus (mejora visual v1.1)
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setGravity(Gravity.CENTER_HORIZONTAL);
        banner.setBackgroundResource(R.drawable.login_banner);
        int bh = dp(190);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadii(new float[] { dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0 });
        banner.setBackground(bannerBg);
        root.addView(banner, new LinearLayout.LayoutParams(-1, bh));

        android.widget.ImageView bus = new android.widget.ImageView(this);
        bus.setImageResource(R.drawable.ic_bus);
        LinearLayout.LayoutParams buslp = new LinearLayout.LayoutParams(dp(96), dp(48));
        buslp.topMargin = dp(26);
        bus.setLayoutParams(buslp);
        banner.addView(bus);

        tvTitle = new TextView(this);
        tvTitle.setText("GuaguaPass");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(30);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        banner.addView(tvTitle);

        tvSub = new TextView(this);
        tvSub.setText(getString(R.string.tagline));
        tvSub.setTextColor(Color.parseColor("#B2DFDB"));
        tvSub.setTextSize(14);
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setPadding(0, dp(4), 0, 0);
        banner.addView(tvSub);

        // Formulario dentro de una tarjeta elevada
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadii(new float[] { dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0 });
        cardBg.setColor(color(R.color.cardBg));
        cardBg.setStroke(dp(1), Color.parseColor("#1A000000"));
        card.setBackground(cardBg);
        card.setElevation(dp(6));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        etUrl = input("URL del servidor (http://IP:3000)", Api.BASE, false);
        etUser = input("Usuario", Session.user(), false);
        etPass = input("Contraseña", Session.pass(), true);
        card.addView(etUrl);
        card.addView(etUser);
        card.addView(etPass);

        btnLogin = new Button(this);
        btnLogin.setText("INICIAR SESIÓN");
        btnLogin.setTextColor(Color.WHITE);
        btnLogin.setStateListAnimator(null);
        GradientDrawable loginBg = new GradientDrawable();
        loginBg.setCornerRadius(dp(14));
        loginBg.setColor(color(R.color.primary));
        btnLogin.setBackground(loginBg);
        btnLogin.setOnClickListener(v -> doLogin(false, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(18);
        card.addView(btnLogin, lp);

        // Boton estilo ARCANECHAT: "crear gestor automaticamente" (borde accent, v1.1)
        btnAuto = new Button(this);
        btnAuto.setText("⚡ ENTRAR AUTOMÁTICO (nuevo gestor)");
        btnAuto.setAllCaps(false);
        btnAuto.setTextColor(color(R.color.accent));
        btnAuto.setStateListAnimator(null);
        GradientDrawable autoBg = new GradientDrawable();
        autoBg.setCornerRadius(dp(14));
        autoBg.setColor(Color.TRANSPARENT);
        autoBg.setStroke(dp(2), color(R.color.accent));
        btnAuto.setBackground(autoBg);
        btnAuto.setOnClickListener(v -> doLogin(true, true));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(52));
        lp2.topMargin = dp(8);
        card.addView(btnAuto, lp2);

        TextView hint = new TextView(this);
        hint.setText("El botón automático deriva un usuario y contraseña al azar\n"
                + "(gg-xxxxxx) y los registra en el servidor sin captcha,\n"
                + "igual que ArcaneChat/chatmail. También se intenta solo si\n"
                + "hay sesión previa guardada.");
        hint.setTextColor(color(R.color.muted));
        hint.setTextSize(11);
        hint.setPadding(0, dp(14), 0, 0);
        hint.setGravity(Gravity.CENTER);
        card.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        // Autologin silencioso: primero con credenciales explicitas guardadas,
        // y si no hay, con la identidad derivada (metodo arcanechat).
        if (Session.token(this) != null && Session.user() != null && Session.pass() != null) {
            doLogin(false, false);
        } else {
            doLogin(true, false);
        }
    }

    private int color(int resId) { return getResources().getColor(resId, null); }

    private EditText input(String hint, String val, boolean pass) {
        EditText e = new EditText(this);
        e.setHint(hint);
        if (val != null) e.setText(val);
        e.setTextColor(color(R.color.text));
        e.setHintTextColor(color(R.color.muted));
        e.setSingleLine();
        e.setTextSize(15);
        if (pass) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        // campo con fondo redondeado sutil (v1.1)
        GradientDrawable fbg = new GradientDrawable();
        fbg.setCornerRadius(dp(12));
        fbg.setColor(Color.parseColor("#11000000"));
        e.setBackground(fbg);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        e.setLayoutParams(p);
        return e;
    }

    /**
     * @param auto      true = usa la identidad derivada (metodo arcanechat); false = formulario.
     * @param forceShow true = muestra resultados en Toast aunque sea arranque silencioso.
     */
    private void doLogin(final boolean auto, final boolean forceShow) {
        if (busy) return;
        busy = true;
        btnLogin.setText("Conectando...");
        final String url = etUrl.getText().toString().trim();
        final String u = auto ? Identity.login() : etUser.getText().toString().trim();
        final String pw = auto ? Identity.password() : etPass.getText().toString();
        if (!url.isEmpty()) Api.saveBaseUrl(this, url);
        new Thread(() -> {
            String err = null;
            try {
                String body = "{\"username\":" + jq(u) + ",\"password\":" + jq(pw) + "}";
                String resp;
                if (auto) {
                    // Paso 1: intentar login con la identidad derivada (cuenta ya creada antes)
                    try {
                        resp = Api.post("/api/login", body);
                    } catch (Api.ApiException e401) {
                        if (e401.code != 401) throw e401;
                        // Paso 2: no existe -> AUTO-REGISTRO tipo /secret-api/new-user de chatmail
                        String rb = "{\"login\":" + jq(u) + ",\"password\":" + jq(pw)
                                + ",\"name\":" + jq(Identity.name()) + "}";
                        resp = Api.postWith("/api/auto-register", rb, "X-Provision-Key", Identity.PROVISION_KEY);
                    }
                } else {
                    resp = Api.post("/api/login", body);
                }
                String token = Api.jstr(resp, "token");
                if (token == null) throw new Exception("respuesta invalida");
                int uid = Integer.parseInt(extractNum(resp, "id"));
                Session.set(this, u, pw, token, uid);
            } catch (Exception ex) {
                err = ex.getMessage();
            }
            final String e = err;
            runOnUiThread(() -> {
                busy = false;
                btnLogin.setText("INICIAR SESIÓN");
                if (e == null) {
                    Toast.makeText(this, "Bienvenido " + u, Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                } else if (forceShow || !auto) {
                    Toast.makeText(this, "Error: " + e, Toast.LENGTH_LONG).show();
                } else {
                    // autologin fallo: deja el formulario relleno para reintento manual
                    Toast.makeText(this, "Servidor no disponible, entra manualmente", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    static String jq(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String extractNum(String json, String key) {
        String k = "\"" + key + "\":";
        int i = json.indexOf(k);
        if (i < 0) return "-1";
        i += k.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            sb.append(json.charAt(i++));
        }
        return sb.length() == 0 ? "-1" : sb.toString();
    }

    int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
