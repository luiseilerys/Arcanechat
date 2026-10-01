package com.guaguapass;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Formulario de venta: cliente, telefono, destino, precio. El asiento ya esta elegido. */
public class SaleActivity extends Activity {

    private EditText etName, etPhone, etPrice;
    private Spinner spDest;
    private Button btnSend;
    private boolean busy = false;

    private String date, guagua, hora;
    private int seat;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Session.load(this);
        Api.loadBaseUrl(this);
        date = getIntent().getStringExtra("date");
        guagua = getIntent().getStringExtra("guagua");
        hora = getIntent().getStringExtra("hora");
        seat = getIntent().getIntExtra("seat", -1);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        root.setBackgroundColor(Color.WHITE);
        sv.addView(root);
        setContentView(sv);

        TextView head = new TextView(this);
        head.setText(seat > 0
                ? "Venta — asiento #" + seat + "\n" + guagua + " · " + date + " · " + hora
                : "Venta rápida\n" + guagua + " · " + date + " · " + hora + " (elige asiento en la lista)");
        head.setTextSize(16);
        head.setTextColor(colorRes(R.color.primary));
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(head);

        etName = field(root, "Nombre del pasajero", false);
        etPhone = field(root, "Teléfono (opcional)", true);

        root.addView(label("Destino"));
        spDest = new Spinner(this);
        root.addView(spDest);
        loadRoutes();

        etPrice = field(root, "Precio (CUP)", true);

        btnSend = new Button(this);
        btnSend.setText("CONFIRMAR RESERVA");
        btnSend.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(18);
        root.addView(btnSend, lp);
    }

    private void loadRoutes() {
        new Thread(() -> {
            List<String> rutas = null;
            try {
                String resp = Api.get("/api/config");
                rutas = MainActivity.extractStrArray(resp, "rutas");
            } catch (Exception ignored) {}
            final List<String> r = rutas;
            runOnUiThread(() -> {
                if (r != null && !r.isEmpty()) {
                    spDest.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, r));
                } else {
                    // modo offline: permite escribir el destino libremente
                    spDest.setVisibility(android.view.View.GONE);
                    EditText free = new EditText(this);
                    free.setHint("Destino");
                    if (contentRoot != null) contentRoot.addView(free, 2);
                    spDest.setTag(free);
                }
            });
        }).start();
    }

    private LinearLayout contentRoot;

    private EditText field(LinearLayout parent, String hint, boolean numeric) {
        if (contentRoot == null) contentRoot = parent;
        parent.addView(label(hint));
        EditText e = new EditText(this);
        e.setSingleLine();
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        parent.addView(e);
        return e;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#757575"));
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private void submit() {
        if (busy) return;
        String name = etName.getText().toString().trim();
        String price = etPrice.getText().toString().trim();
        if (name.isEmpty()) { toast("Falta el nombre del pasajero"); return; }
        if (price.isEmpty()) { toast("Falta el precio"); return; }
        String dest;
        if (spDest.getAdapter() != null && spDest.getAdapter().getCount() > 0) {
            Object sel = spDest.getSelectedItem();
            dest = sel == null ? "" : sel.toString();
        } else {
            dest = spDest.getTag() instanceof EditText ? ((EditText) spDest.getTag()).getText().toString().trim() : "";
        }
        busy = true;
        btnSend.setText("Enviando...");
        final String body = "{"
                + "\"seat\":" + seat + ","
                + "\"date\":" + LoginActivity.jq(date) + ","
                + "\"guagua\":" + LoginActivity.jq(guagua) + ","
                + "\"hora\":" + LoginActivity.jq(hora) + ","
                + "\"customer\":" + LoginActivity.jq(name) + ","
                + "\"phone\":" + LoginActivity.jq(etPhone.getText().toString().trim()) + ","
                + "\"destino\":" + LoginActivity.jq(dest) + ","
                + "\"precio\":" + LoginActivity.jq(price) + "}";
        new Thread(() -> {
            String err = null;
            try {
                Api.post("/api/sale", body);
            } catch (Api.ApiException ae) {
                if (ae.code == 409) err = "El asiento ya fue vendido por otro gestor (acaba de actualizarse).";
                else err = ae.getMessage();
            } catch (Exception ex) {
                err = "Sin conexión: " + ex.getMessage();
            }
            final String e = err;
            runOnUiThread(() -> {
                busy = false;
                btnSend.setText("CONFIRMAR RESERVA");
                if (e == null) {
                    Toast.makeText(this, "✔ Reservado #" + seat + " para " + name, Toast.LENGTH_LONG).show();
                    finish(); // vuelve a la rejilla, que se refresca sola en <1s
                } else {
                    Toast.makeText(this, "✖ " + e, Toast.LENGTH_LONG).show();
                    if (e.startsWith("El asiento")) finish();
                }
            });
        }).start();
    }

    private int colorRes(int id) { return getResources().getColor(id, null); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }
}
