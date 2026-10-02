package com.guaguapass;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Panel del gestor: elige guagua, fecha y hora; ve la rejilla de asientos en tiempo real.
 * Sincronizacion ESTILO TIEMPO REAL: stream SSE (push inmediato del servidor) con
 * fallback a polling de 1 segundo sobre /api/state si el stream cae.
 */
public class MainActivity extends Activity {

    private static final int POLL_MS = 1000;

    private Spinner spGuagua, spHora;
    private TextView tvDate, tvStatus, tvLegend, tvSales, tvOccupancy, tvTotal;
    private LinearLayout grid;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pollTask;
    private boolean fetching = false;
    private volatile long lastRev = -1;
    // estado del stream SSE
    private Thread sseThread;
    private AtomicBoolean sseStop;
    private volatile boolean sseAlive = false;
    private volatile String sseKey = "";
    // indicador de "actualizado hace N s" (mejora visual v1.1)
    private long lastSyncAt = 0;
    private Runnable clockTask;

    // estado traido del servidor
    private String selectedDate;
    private List<String> guaguas = new ArrayList<>();
    private List<String> routes = new ArrayList<>();
    private List<String> horarys = new ArrayList<>();
    private int cols = 4, rows = 12;
    private List<Sale> sales = new ArrayList<>();

    static class Sale {
        int id, seat, userId;
        String userName, customer, phone, dest, price;
        long saleTime;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        Api.loadBaseUrl(this);
        selectedDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(System.currentTimeMillis() + 3600_000L)); // manana por defecto

        buildUi();
        loadConfig();
        refreshMailCount(); // badge de correo sin leer (buzon @arcanechat.me)
        checkUpdate(false); // auto-update silencioso desde el servidor de correo
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(24));
        sv.addView(root);
        setContentView(sv);

        // Barra superior
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("🚌 GuaguaPass");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        root.addView(bar);
        bar.addView(title, tlp);
        Button btnMail = smallBtn("✉ Correo" + (mailUnread > 0 ? " (" + mailUnread + ")" : ""));
        btnMail.setOnClickListener(v -> openMailbox());
        bar.addView(btnMail);
        btnMailRef = btnMail;
        Button btnUpdate = smallBtn("Actualizar");
        btnUpdate.setOnClickListener(v -> checkUpdate(true));
        bar.addView(btnUpdate);
        Button btnOut = smallBtn("Salir " + Session.user());
        btnOut.setOnClickListener(v -> { Session.clear(this); System.exit(0); });
        bar.addView(btnOut);

        // Chip del gestor conectado (mejora visual v1.1)
        TextView chip = new TextView(this);
        String ident = Identity.login() != null ? Identity.login() : Session.user();
        chip.setText("👤 " + (ident == null ? "gestor" : ident));
        chip.setTextSize(11);
        chip.setTextColor(color(R.color.primaryDark));
        chip.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(20));
        chipBg.setColor(Color.parseColor("#2200695C"));
        chip.setBackground(chipBg);
        LinearLayout.LayoutParams chiplp = new LinearLayout.LayoutParams(-2, -2);
        chiplp.bottomMargin = dp(4);
        root.addView(chip, chiplp);

        tvDate = new TextView(this);
        tvDate.setTextSize(15);
        tvDate.setTypeface(Typeface.DEFAULT_BOLD);
        tvDate.setTextColor(color(R.color.text));
        tvDate.setText("Fecha: " + selectedDate + "   (toca para cambiar)");
        tvDate.setPadding(0, dp(8), 0, dp(8));
        tvDate.setOnClickListener(v -> pickDate());
        root.addView(tvDate);

        spGuagua = new Spinner(this);
        root.addView(label("Guagua / ruta"));
        root.addView(spGuagua);
        spGuagua.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < horarys.size()) setupHoras();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        spHora = new Spinner(this);
        root.addView(label("Hora de salida"));
        root.addView(spHora);
        spHora.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                lastRev = -1; refreshNow();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(color(R.color.muted));
        tvStatus.setPadding(0, dp(6), 0, dp(2));
        root.addView(tvStatus);

        // Leyenda con puntos de colores reales (mejora visual v1.1)
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, dp(2), 0, dp(8));
        legend.addView(legendDot(R.color.free, "Libre"));
        legend.addView(legendDot(R.color.taken, "Ocupado"));
        legend.addView(legendDot(R.color.mine, "Tu venta"));
        TextView legHint = new TextView(this);
        legHint.setText("· toca un asiento libre para vender");
        legHint.setTextSize(11);
        legHint.setTextColor(color(R.color.muted));
        legend.addView(legHint);
        root.addView(legend);
        tvLegend = new TextView(this); // se conserva por compatibilidad, oculto
        tvLegend.setVisibility(View.GONE);
        root.addView(tvLegend);

        // Tarjeta de ocupacion: numero grande + barra de progreso (v1.1)
        LinearLayout occCard = new LinearLayout(this);
        occCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(14));
        cardBg.setColor(color(R.color.cardBg));
        cardBg.setStroke(dp(1), Color.parseColor("#22000000"));
        occCard.setBackground(cardBg);
        occCard.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams occLp = new LinearLayout.LayoutParams(-1, -2);
        occLp.bottomMargin = dp(10);
        root.addView(occCard, occLp);

        LinearLayout occRow = new LinearLayout(this);
        occRow.setOrientation(LinearLayout.HORIZONTAL);
        occRow.setGravity(Gravity.CENTER_VERTICAL);
        occCard.addView(occRow);
        tvOccupancy = new TextView(this);
        tvOccupancy.setTextSize(22);
        tvOccupancy.setTypeface(Typeface.DEFAULT_BOLD);
        tvOccupancy.setTextColor(color(R.color.primary));
        occRow.addView(tvOccupancy, new LinearLayout.LayoutParams(0, -2, 1f));
        tvTotal = new TextView(this);
        tvTotal.setTextSize(13);
        tvTotal.setTextColor(color(R.color.muted));
        occRow.addView(tvTotal);

        View barTrack = new View(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(6));
        trackBg.setColor(Color.parseColor("#33000000"));
        barTrack.setBackground(trackBg);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(-1, dp(10));
        tlp2.topMargin = dp(8);
        occCard.addView(barTrack, tlp2);
        occupancyBar = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(6));
        fillBg.setColor(color(R.color.accent));
        occupancyBar.setBackground(fillBg);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(10));
        flp.topMargin = -dp(10);
        occCard.addView(occupancyBar, flp);
        occCard.addView(label("Toca un asiento ocupado para ver el detalle · mantén pulsado para cancelar"));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(grid);

        Button btnSell = new Button(this);
        btnSell.setText("+ REGISTRAR VENTA");
        btnSell.setAllCaps(false);
        btnSell.setTextColor(Color.WHITE);
        GradientDrawable sellBg = new GradientDrawable();
        sellBg.setCornerRadius(dp(14));
        sellBg.setColor(color(R.color.primary));
        btnSell.setBackground(sellBg);
        btnSell.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
        blp.topMargin = dp(12);
        root.addView(btnSell, blp);
        btnSell.setOnClickListener(v -> openSale(-1));

        root.addView(label("Ventas registradas en esta salida"));
        tvSales = new TextView(this);
        tvSales.setTextSize(13);
        tvSales.setTextColor(color(R.color.text));
        tvSales.setLineSpacing(dp(4), 1f);
        // tarjeta para la lista de ventas (v1.1)
        GradientDrawable salesCard = new GradientDrawable();
        salesCard.setCornerRadius(dp(14));
        salesCard.setColor(color(R.color.cardBg));
        salesCard.setStroke(dp(1), Color.parseColor("#22000000"));
        tvSales.setBackground(salesCard);
        tvSales.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        root.addView(tvSales, slp);
    }

    private View occupancyBar;

    private View legendDot(int colorRes, String txt) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.rightMargin = dp(12);
        ll.setLayoutParams(llp);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color(colorRes));
        d.setStroke(dp(1), Color.parseColor("#33000000"));
        dot.setBackground(d);
        ll.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView t = new TextView(this);
        t.setText(" " + txt);
        t.setTextSize(11);
        t.setTextColor(color(R.color.text));
        ll.addView(t);
        return ll;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#616161"));
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private Button smallBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(11);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinimumHeight(dp(40));
        return b;
    }

    // ---------- Correo (buzon sincronizado @arcanechat.me) + actualizacion APK ----------
    private int mailUnread = 0;
    private Button btnMailRef;

    /** Recuento de no leidos desde /api/mail/list (se refresca con el evento SSE "mail"). */
    private void refreshMailCount() {
        new Thread(() -> {
            try {
                String resp = Api.get("/api/mail/list");
                final int unread = Integer.parseInt(LoginActivity.extractNum(resp, "unread"));
                runOnUiThread(() -> {
                    mailUnread = unread;
                    if (btnMailRef != null)
                        btnMailRef.setText("✉ Correo" + (unread > 0 ? " (" + unread + ")" : ""));
                });
            } catch (Exception e) { /* offline: sin aviso */ }
        }).start();
    }

    /** Buzon simple: lista inbox/sent y compose contra el servidor de correo. */
    private void openMailbox() {
        new Thread(() -> {
            String list = null;
            try { list = Api.get("/api/mail/list"); } catch (Exception e) {
                final String err = e.getMessage();
                runOnUiThread(() -> toast("Correo no disponible: " + err));
                return;
            }
            final StringBuilder sb = new StringBuilder();
            sb.append("Buzon: ").append(Api.jstr(list, "from")).append("\n\n");
            sb.append("── Recibidos ──\n");
            appendMails(sb, extractArray(list, "inbox"));
            sb.append("\n── Enviados ──\n");
            appendMails(sb, extractArray(list, "sent"));
            final String body = sb.toString();
            runOnUiThread(() -> showMailDialog(body));
        }).start();
    }

    private void appendMails(StringBuilder sb, String arr) {
        java.util.List<String> objs = splitObjects(arr);
        if (objs.isEmpty()) { sb.append("(vacio)\n"); return; }
        for (int i = objs.size() - 1; i >= 0 && i >= objs.size() - 15; i--) {
            String m = objs.get(i);
            String from = Api.jstr(m, "from"), to = Api.jstr(m, "to");
            String subj = Api.jstr(m, "subject"), text = Api.jstr(m, "body");
            String ts = LoginActivity.extractNum(m, "ts");
            String when = "?";
            try {
                when = new SimpleDateFormat("dd/MM HH:mm", Locale.US)
                        .format(new Date(Long.parseLong(ts)));
            } catch (Exception e) {}
            sb.append(("-".equals(Api.jstr(m, "read")) ? "" : "") )
              .append("[").append(when).append("] ")
              .append(from == null ? to : from).append(": ").append(subj).append("\n")
              .append(text == null ? "" : text).append("\n\n");
        }
    }

    private void showMailDialog(String body) {
        android.app.AlertDialog.Builder ab = new android.app.AlertDialog.Builder(this);
        ab.setTitle("✉ Correo (" + Identity.DOMAIN + ")");
        final EditText et = new EditText(this);
        et.setText(body);
        et.setTextSize(12);
        et.setHorizontallyScrolling(false);
        ScrollView sv = new ScrollView(this);
        sv.addView(et);
        ab.setView(sv);
        ab.setPositiveButton("Redactar", (d, w) -> composeMail());
        ab.setNeutralButton("Marcar leidos", (d, w) -> markMailRead());
        ab.setNegativeButton("Cerrar", null);
        ab.show();
    }

    private void composeMail() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), 0);
        final EditText etTo = new EditText(this); etTo.setHint("Para (gestor@arcanechat.me)");
        final EditText etSub = new EditText(this); etSub.setHint("Asunto");
        final EditText etBody = new EditText(this); etBody.setHint("Mensaje");
        etBody.setMinLines(3);
        box.addView(etTo); box.addView(etSub); box.addView(etBody);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Nuevo correo")
                .setView(box)
                .setPositiveButton("Enviar", (d, w) -> sendMail(
                        etTo.getText().toString(), etSub.getText().toString(),
                        etBody.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void sendMail(final String to, final String subject, final String bodyText) {
        new Thread(() -> {
            String err = null;
            try {
                String json = "{\"to\":" + LoginActivity.jq(to)
                        + ",\"subject\":" + LoginActivity.jq(subject)
                        + ",\"body\":" + LoginActivity.jq(bodyText) + "}";
                Api.post("/api/mail/send", json);
            } catch (Exception e) { err = e.getMessage(); }
            final String er = err;
            runOnUiThread(() -> {
                toast(er == null ? "Correo enviado a " + to : "No envio: " + er);
                if (er == null) refreshMailCount();
            });
        }).start();
    }

    private void markMailRead() {
        new Thread(() -> {
            try { Api.post("/api/mail/read", "{\"all\":true}"); } catch (Exception e) {}
            runOnUiThread(this::refreshMailCount);
        }).start();
    }

    // ---------- AUTO-ACTUALIZACION (mismo servicio de correo arcanechat.me) ----------

    /** Consulta GET /api/update del servidor que compila y reparte la APK. */
    private void checkUpdate(final boolean manual) {
        Updater.check(this, info -> runOnUiThread(() -> {
            if (info == null) {
                if (manual) toast("Servidor de actualizaciones no disponible");
                return;
            }
            if (!info.update) {
                if (manual) toast("Ya estas al dia (v" + Identity.APP_VERSION + ")");
                return;
            }
            toast("Nueva version v" + info.latest + ": descargando...");
            new Thread(() -> {
                String err = null;
                try { Updater.downloadAndInstall(this, info); }
                catch (Exception e) { err = e.getMessage(); }
                final String er = err;
                runOnUiThread(() -> {
                    if (er != null) toast("Actualizacion falló: " + er
                            + "\nDescargala en " + Api.BASE + "/apk/GuaguaPass.apk");
                    else toast("Instala la descarga para actualizar a v" + info.latest);
                });
            }).start();
        }));
    }

    // ---------- Configuracion (guaguas, rutas, posiciones) ----------
    private void loadConfig() {
        new Thread(() -> {
            try {
                String resp = Api.get("/api/config");
                guaguas = extractStrArray(resp, "guaguas");
                routes = extractStrArray(resp, "rutas");
                String dims = extractObj(resp, "dims");
                cols = Integer.parseInt(LoginActivity.extractNum(dims, "cols"));
                rows = Integer.parseInt(LoginActivity.extractNum(dims, "rows"));
                horarys = extractStrArray(resp, "horarios");
                runOnUiThread(() -> {
                    spGuagua.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, guaguas));
                    setupHoras();
                    refreshNow();
                });
            } catch (Exception e) {
                toast("Sin conexion al servidor: " + e.getMessage());
                runOnUiThread(this::refreshNow); // sigue intentando en local
            }
        }).start();
    }

    private void setupHoras() {
        spHora.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, horarys));
    }

    // ---------- Fecha ----------
    private void pickDate() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(20), dp(16), dp(20), 0);
        EditText et = new EditText(this);
        et.setText(selectedDate);
        et.setHint("AAAA-MM-DD");
        box.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        new android.app.AlertDialog.Builder(this)
            .setTitle("Fecha de la salida")
            .setView(box)
            .setPositiveButton("OK", (d, w) -> {
                String v = et.getText().toString().trim();
                if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    selectedDate = v;
                    tvDate.setText("Fecha: " + selectedDate + "   (toca para cambiar)");
                    lastRev = -1;
                    refreshNow();
                } else toast("Formato AAAA-MM-DD");
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ---------- Sincronizacion en tiempo real (SSE push + fallback 1 s) ----------
    private void refreshNow() { schedulePoll(0); }

    private void schedulePoll(long delay) {
        ui.removeCallbacks(pollTask);
        pollTask = this::doPoll;
        ui.postDelayed(pollTask, delay);
    }

    private String currentKey() {
        final String guagua = spGuagua.getSelectedItem() == null ? "" : spGuagua.getSelectedItem().toString();
        final String hora = spHora.getSelectedItem() == null ? "" : spHora.getSelectedItem().toString();
        return selectedDate + "|" + guagua + "|" + hora;
    }

    private void doPoll() {
        if (isFinishing()) return;
        final String key = currentKey();
        if (!key.equals(sseKey)) {          // cambio de canal: reinicia el stream
            stopStream();
            startStream(key);
        }
        if (!sseAlive && !fetching) {       // sin stream -> fallback polling cada 1 s
            fetching = true;
            final String date = selectedDate;
            new Thread(() -> {
                String resp = null;
                try {
                    String guagua = spGuagua.getSelectedItem() == null ? "" : spGuagua.getSelectedItem().toString();
                    String hora = spHora.getSelectedItem() == null ? "" : spHora.getSelectedItem().toString();
                    resp = Api.get("/api/state?date=" + Uri.encode(date)
                            + "&guagua=" + Uri.encode(guagua) + "&hora=" + Uri.encode(hora));
                } catch (Exception e) { /* offline: reintenta igual */ }
                final String r = resp;
                fetching = false;
                runOnUiThread(() -> {
                    if (r != null) applyState(r);
                    updateStatus(r != null);
                    schedulePoll(POLL_MS);
                });
            }).start();
        } else {
            updateStatus(sseAlive || !fetching);
            schedulePoll(POLL_MS); // tick suave solo para refrescar indicador
        }
    }

    private void startStream(final String key) {
        sseKey = key;
        sseStop = new AtomicBoolean(false);
        final AtomicBoolean stop = sseStop;
        final String[] parts = key.split("\\|", -1);
        sseThread = new Thread(() -> Api.sse(
                "/api/events?date=" + Uri.encode(parts[0])
                        + "&guagua=" + Uri.encode(parts.length > 1 ? parts[1] : "")
                        + "&hora=" + Uri.encode(parts.length > 2 ? parts[2] : ""),
                new Api.SseHandler() {
                    public void onEvent(String event, String data) {
                        if (stop.get()) return;
                        if ("state".equals(event)) {
                            runOnUiThread(() -> { applyState(data); updateStatus(true); });
                        } else if ("conflict".equals(event)) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "Conflicto: un asiento acaba de venderse por otro gestor", Toast.LENGTH_LONG).show());
                        } else if ("mail".equals(event)) {
                            // aviso de correo nuevo (chat de correo estilo arcanechat)
                            refreshMailCount();
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "✉ Correo nuevo", Toast.LENGTH_SHORT).show());
                        }
                    }
                    public void onEnd(Exception err) {
                        if (stop.get()) return;
                        sseAlive = false;
                        runOnUiThread(() -> { updateStatus(false); schedulePoll(0); });
                    }
                }, stop));
        sseAlive = true; // optimista: se marca conectado al recibir el primer evento
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void stopStream() {
        if (sseStop != null) sseStop.set(true);
        sseThread = null;
        sseAlive = false;
        sseKey = "";
    }

    private void updateStatus(boolean connected) {
        String sync = lastSyncAt == 0 ? "" : " · hace " + Math.max(0, (System.currentTimeMillis() - lastSyncAt) / 1000) + " s";
        tvStatus.setText((connected ? "● En vivo (push SSE)" : "○ Reintentando conexión…")
                + " — rev " + lastRev + sync);
        tvStatus.setTextColor(connected ? color(R.color.ok) : color(R.color.bad));
    }

    /** Reloj suave para el "hace N s" del indicador de sincronizacion. */
    private void startClock() {
        if (clockTask != null) ui.removeCallbacks(clockTask);
        clockTask = () -> {
            if (!isFinishing()) {
                updateStatus(sseAlive || lastSyncAt > System.currentTimeMillis() - POLL_MS * 3);
                ui.postDelayed(clockTask, 1000);
            }
        };
        ui.postDelayed(clockTask, 1000);
    }

    private void stopClock() {
        if (clockTask != null) ui.removeCallbacks(clockTask);
    }

    /** Parseo defensivo: /api/state devuelve {"rev":N,"seats":[{"seat":5,"user_id":2,...},...]} */
    private void applyState(String json) {
        long rev = Long.parseLong(LoginActivity.extractNum(json, "rev"));
        boolean changed = rev != lastRev;
        lastRev = rev;
        lastSyncAt = System.currentTimeMillis();
        if (!changed) return; // sin cambios
        sales = new ArrayList<>();
        String arr = extractArray(json, "seats");
        for (String item : splitObjects(arr)) {
            Sale s = new Sale();
            s.seat = Integer.parseInt(LoginActivity.extractNum(item, "seat"));
            s.userId = Integer.parseInt(firstOf(item, "user_id", "userId"));
            s.userName = Api.jstr(item, "user_name");
            if (s.userName == null) s.userName = Api.jstr(item, "userName");
            s.customer = Api.jstr(item, "customer");
            s.phone = Api.jstr(item, "phone");
            s.dest = Api.jstr(item, "destino");
            if (s.dest == null) s.dest = Api.jstr(item, "dest");
            s.price = firstOf(item, "precio", "price");
            s.saleTime = Long.parseLong(firstOf(item, "sale_time", "saleTime"));
            sales.add(s);
        }
        renderGrid();
        renderSales();
        renderOccupancy(myUidAfterRender());
    }

    private int myUidAfterRender() { return Session.uid(this); }

    /** Tarjeta de ocupacion: "12/48 vendidos" + barra proporcional (v1.1) */
    private void renderOccupancy(int myUid) {
        int total = cols * rows;
        int n = sales.size();
        tvOccupancy.setText(n + "/" + total + " vendidos");
        tvTotal.setText("libres: " + Math.max(0, total - n));
        if (occupancyBar != null && occupancyBar.getParent() != null) {
            int w = getResources().getDisplayMetrics().widthPixels - dp(28 + 28 + 14 + 14);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) occupancyBar.getLayoutParams();
            lp.width = Math.max(0, (int) ((long) w * n / Math.max(1, total)));
            occupancyBar.setLayoutParams(lp);
        }
    }

    private void renderGrid() {
        grid.removeAllViews();
        int myUid = Session.uid(this);
        // separador conductor
        addDriverRow();
        for (int r = 1; r <= rows; r++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setGravity(Gravity.CENTER);
            for (int c = 1; c <= cols; c++) {
                int num = (c - 1) * rows + r;
                rowL.addView(seatButton(num, myUid));
            }
            grid.addView(rowL);
        }
        // animacion de entrada en cascada de las filas (mejora visual v1.1)
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            ScaleAnimation sa = new ScaleAnimation(0.92f, 1f, 0.92f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            sa.setDuration(180);
            sa.setStartOffset(i * 18L);
            child.startAnimation(sa);
        }
    }

    private void addDriverRow() {
        TextView t = new TextView(this);
        t.setText("🚌 CONDUCTOR / PARABRISAS");
        t.setTextSize(11);
        t.setTextColor(Color.parseColor("#9E9E9E"));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, dp(6));
        grid.addView(t);
    }

    private View seatButton(final int num, int myUid) {
        Sale occ = null;
        for (Sale s : sales) if (s.seat == num) { occ = s; break; }
        TextView b = new TextView(this);
        // numero + inicial del comprador en asientos ocupados (v1.1)
        b.setText(occ == null ? String.valueOf(num)
                : num + "\n" + initials(occ.customer));
        b.setTextSize(occ == null ? 13 : 10);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (occ == null) {
            b.setTextColor(color(R.color.seatTextFree));
            bg.setColor(color(R.color.free));
            bg.setStroke(dp(1), Color.parseColor("#22000000"));
        } else if (occ.userId == myUid) {
            b.setTextColor(Color.WHITE);
            bg.setColor(color(R.color.mine));
            bg.setStroke(dp(1), Color.parseColor("#33000000"));
        } else {
            b.setTextColor(Color.WHITE);
            bg.setColor(color(R.color.taken));
            bg.setStroke(dp(1), Color.parseColor("#33000000"));
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(46));
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(p);
        if (occ != null) {
            final Sale o = occ;
            b.setOnClickListener(v -> showSeatInfo(o));
            b.setOnLongClickListener(v -> { confirmCancel(o); return true; });
        } else {
            b.setOnClickListener(v -> openSale(num));
        }
        return b;
    }

    static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++)
            sb.append(parts[i].substring(0, 1).toUpperCase(Locale.US));
        return sb.toString();
    }

    /** Cancelar venta propia con confirmacion visual (v1.1). */
    private void confirmCancel(final Sale s) {
        if (s.userId != Session.uid(this)) {
            Toast.makeText(this, "Solo el gestor que vendió #" + s.seat + " puede cancelarla",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Cancelar asiento #" + s.seat)
            .setMessage(s.customer + (s.dest == null ? "" : " → " + s.dest)
                    + "\n" + s.price + " CUP\n\n¿Devolver el asiento a la rejilla?")
            .setPositiveButton("Cancelar venta", (d, w) -> cancelSale(s))
            .setNegativeButton("Mantener", null)
            .show();
    }

    private void cancelSale(final Sale s) {
        new Thread(() -> {
            String err = null;
            try {
                Api.post("/api/cancel", "{\"id\":" + s.id + "}");
            } catch (Exception e) { err = e.getMessage(); }
            final String e = err;
            runOnUiThread(() -> {
                if (e == null) {
                    Toast.makeText(this, "Asiento #" + s.seat + " liberado", Toast.LENGTH_SHORT).show();
                    lastRev = -1; refreshNow();
                } else Toast.makeText(this, "No se pudo cancelar: " + e, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void showSeatInfo(Sale s) {
        String who = (s.userId == Session.uid(this)) ? "TU VENTA" : "de " + s.userName;
        Toast.makeText(this, "#" + s.seat + " (" + who + ")\n" + s.customer
                + "\nDestino: " + s.dest + " · " + s.price + " CUP", Toast.LENGTH_LONG).show();
    }

    private void renderSales() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        hm.setTimeZone(TimeZone.getDefault());
        int myUid = Session.uid(this);
        double totalCup = 0;
        for (Sale s : sales) {
            boolean mine = s.userId == myUid;
            if (mine) { try { totalCup += Double.parseDouble(s.price); } catch (Exception ignored) {} }
            sb.append(mine ? "🟢" : "🔴").append(" #").append(s.seat).append("  ").append(s.customer);
            if (s.dest != null) sb.append(" → ").append(s.dest);
            sb.append("  · ").append(s.price).append(" CUP\n")
              .append("      ").append(s.userName).append(mine ? " (tú)" : "")
              .append(" · ").append(hm.format(new Date(s.saleTime))).append("\n");
        }
        if (sales.isEmpty()) sb.append("— sin ventas en esta salida —");
        else if (totalCup > 0) sb.append("\n💰 Tus ventas: ").append(fmtMoney(totalCup)).append(" CUP");
        tvSales.setText(sb.toString());
    }

    static String fmtMoney(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.2f", v);
    }

    // ---------- Venta ----------
    private void openSale(int seat) {
        Intent i = new Intent(this, SaleActivity.class);
        i.putExtra("seat", seat);
        i.putExtra("date", selectedDate);
        i.putExtra("guagua", spGuagua.getSelectedItem() == null ? "" : spGuagua.getSelectedItem().toString());
        i.putExtra("hora", spHora.getSelectedItem() == null ? "" : spHora.getSelectedItem().toString());
        startActivity(i);
    }

    // ---------- Utilidades JSON minimalistas ----------
    static List<String> extractStrArray(String json, String key) {
        List<String> out = new ArrayList<>();
        String arr = extractArray(json, key);
        int i = 0;
        while (true) {
            int a = arr.indexOf('"', i);
            if (a < 0) break;
            int b = a + 1;
            while (b < arr.length() && !(arr.charAt(b) == '"' && arr.charAt(b - 1) != '\\')) b++;
            out.add(arr.substring(a + 1, b));
            i = b + 1;
        }
        return out;
    }

    static String extractArray(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int s = json.indexOf('[', i);
        if (s < 0) return "";
        int depth = 0;
        for (int j = s; j < json.length(); j++) {
            if (json.charAt(j) == '[') depth++;
            else if (json.charAt(j) == ']') { depth--; if (depth == 0) return json.substring(s + 1, j); }
        }
        return "";
    }

    static String extractObj(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "{}";
        int s = json.indexOf('{', i);
        int depth = 0;
        for (int j = s; j < json.length(); j++) {
            if (json.charAt(j) == '{') depth++;
            else if (json.charAt(j) == '}') { depth--; if (depth == 0) return json.substring(s, j + 1); }
        }
        return "{}";
    }

    static List<String> splitObjects(String arr) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        for (int j = 0; j < arr.length(); j++) {
            char ch = arr.charAt(j);
            if (ch == '{') { if (depth == 0) start = j; depth++; }
            else if (ch == '}') { depth--; if (depth == 0 && start >= 0) { out.add(arr.substring(start, j + 1)); start = -1; } }
        }
        return out;
    }

    static String firstOf(String json, String... keys) {
        for (String k : keys) {
            String v = LoginActivity.extractNum(json, k);
            if (!v.equals("-1") || json.contains("\"" + k + "\"")) return v;
        }
        return "0";
    }

    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    private int color(int resId) { return getResources().getColor(resId, null); }

    int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    @Override
    protected void onPause() {
        super.onPause();
        if (pollTask != null) ui.removeCallbacks(pollTask);
        stopClock();
        stopStream(); // cierra el stream SSE; al volver se reabre en onResume
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastRev = -1;
        refreshNow();
        startClock();
    }
}
