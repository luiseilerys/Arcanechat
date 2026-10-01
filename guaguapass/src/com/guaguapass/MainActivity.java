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
    private TextView tvDate, tvStatus, tvLegend, tvSales;
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
        title.setText("GuaguaPass");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        root.addView(bar);
        bar.addView(title, tlp);
        Button btnOut = smallBtn("Salir " + Session.user());
        btnOut.setOnClickListener(v -> { Session.clear(this); System.exit(0); });
        bar.addView(btnOut);

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
        tvStatus.setTextColor(Color.parseColor("#757575"));
        tvStatus.setPadding(0, dp(6), 0, dp(2));
        root.addView(tvStatus);

        tvLegend = new TextView(this);
        tvLegend.setTextSize(12);
        tvLegend.setText("Libre · Ocupado por otro · Tu venta · Toca un asiento libre para vender");
        tvLegend.setPadding(0, dp(2), 0, dp(8));
        root.addView(tvLegend);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(grid);

        Button btnSell = new Button(this);
        btnSell.setText("+ REGISTRAR VENTA (asiento seleccionado)");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(50));
        blp.topMargin = dp(12);
        root.addView(btnSell, blp);
        btnSell.setOnClickListener(v -> openSale(-1));

        root.addView(label("Ventas registradas en esta salida"));
        tvSales = new TextView(this);
        tvSales.setTextSize(13);
        tvSales.setTextColor(color(R.color.text));
        tvSales.setLineSpacing(dp(4), 1f);
        root.addView(tvSales);
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
        tvStatus.setText((connected ? "● Conectado (push tiempo real)" : "○ Sin conexión")
                + " — rev " + lastRev);
        tvStatus.setTextColor(connected ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));
    }

    /** Parseo defensivo: /api/state devuelve {"rev":N,"seats":[{"seat":5,"user_id":2,...},...]} */
    private void applyState(String json) {
        long rev = Long.parseLong(LoginActivity.extractNum(json, "rev"));
        if (rev == lastRev) return; // sin cambios
        lastRev = rev;
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
        b.setText(String.valueOf(num));
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (occ == null) {
            b.setTextColor(Color.parseColor("#424242"));
            bg.setColor(color(R.color.free));
        } else if (occ.userId == myUid) {
            b.setTextColor(Color.WHITE);
            bg.setColor(color(R.color.mine));
        } else {
            b.setTextColor(Color.WHITE);
            bg.setColor(color(R.color.taken));
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(46));
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(p);
        if (occ != null) {
            final Sale o = occ;
            b.setOnLongClickListener(v -> { showSeatInfo(o); return true; });
        } else {
            b.setOnClickListener(v -> openSale(num));
        }
        return b;
    }

    private void showSeatInfo(Sale s) {
        String who = (s.userId == Session.uid(this)) ? "TU VENTA" : "de " + s.userName;
        Toast.makeText(this, "#" + s.seat + " (" + who + ")\n" + s.customer
                + "\nDestino: " + s.dest + " · " + s.price + " CUP", Toast.LENGTH_LONG).show();
    }

    private void renderSales() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss", Locale.US);
        hm.setTimeZone(TimeZone.getDefault());
        int myUid = Session.uid(this);
        for (Sale s : sales) {
            sb.append("#").append(s.seat).append("  ").append(s.customer);
            if (s.dest != null) sb.append(" → ").append(s.dest);
            sb.append("  ").append(s.price).append(" CUP\n")
              .append("     ").append(s.userName)
              .append(s.userId == myUid ? " (tú)" : "")
              .append(" · ").append(hm.format(new Date(s.saleTime))).append("\n");
        }
        if (sales.isEmpty()) sb.append("— sin ventas en esta salida —");
        tvSales.setText(sb.toString());
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
        stopStream(); // cierra el stream SSE; al volver se reabre en onResume
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastRev = -1;
        refreshNow();
    }
}
