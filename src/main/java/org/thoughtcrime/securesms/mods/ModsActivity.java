package org.thoughtcrime.securesms.mods;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.MediaUtil;

/**
 * Lists installed community mods and lets the user install, activate, deactivate, inspect and
 * remove them.
 *
 * <p>The UI is built programmatically on purpose: it keeps the feature self contained (no extra
 * layouts) and easy to review.
 */
public class ModsActivity extends PassphraseRequiredActionBarActivity {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private LinearLayout listContainer;
  private Button importButton;
  private ActivityResultLauncher<String[]> importLauncher;

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(buildRootView());

    importLauncher =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
              if (uri != null) {
                installMod(uri);
              }
            });

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.mods_title);
      actionBar.setDisplayHomeAsUpEnabled(true);
    }

    refresh();
  }

  @Override
  protected void onDestroy() {
    executor.shutdownNow();
    super.onDestroy();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  // ------------------------------------------------------------------- UI

  private View buildRootView() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(16), dp(12), dp(16), dp(28));
    scroll.addView(root);

    TextView help = new TextView(this);
    help.setText(R.string.mods_help);
    help.setTextSize(13);
    help.setPadding(0, 0, 0, dp(14));
    root.addView(help);

    importButton = new Button(this);
    importButton.setText(R.string.mods_import);
    importButton.setAllCaps(false);
    importButton.setOnClickListener(
        view -> importLauncher.launch(new String[] {"*/*"}));
    root.addView(importButton, new LinearLayout.LayoutParams(-1, -2));

    TextView oneActive = new TextView(this);
    oneActive.setText(R.string.mods_one_active);
    oneActive.setTextSize(12);
    oneActive.setPadding(0, dp(8), 0, dp(14));
    root.addView(oneActive);

    listContainer = new LinearLayout(this);
    listContainer.setOrientation(LinearLayout.VERTICAL);
    root.addView(listContainer);

    return scroll;
  }

  private void refresh() {
    if (listContainer == null) return;
    listContainer.removeAllViews();

    List<Mod> mods = ModManager.list(this);
    if (mods.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText(R.string.mods_empty);
      empty.setTextSize(14);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, dp(24), 0, dp(24));
      listContainer.addView(empty);
      return;
    }

    String activeId = ModManager.getActiveId(this);
    for (Mod mod : mods) {
      listContainer.addView(buildCard(mod, mod.id().equals(activeId)));
    }
  }

  private View buildCard(@NonNull Mod mod, boolean active) {
    boolean dark = DynamicTheme.isDarkTheme(this);

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable background = new GradientDrawable();
    background.setCornerRadius(dp(14));
    background.setColor(dark ? 0xFF262626 : 0xFFF2F2F2);
    background.setStroke(dp(1), dark ? 0xFF3A3A3A : 0xFFDDDDDD);
    card.setBackground(background);
    card.setPadding(dp(16), dp(14), dp(16), dp(12));

    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
    cardParams.bottomMargin = dp(12);
    card.setLayoutParams(cardParams);

    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setOrientation(LinearLayout.HORIZONTAL);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(titleRow);

    TextView title = new TextView(this);
    title.setText(mod.name());
    title.setTextSize(17);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

    if (active) {
      TextView badge = new TextView(this);
      badge.setText(R.string.mods_active);
      badge.setTextSize(11);
      badge.setTextColor(0xFFFFFFFF);
      GradientDrawable badgeBackground = new GradientDrawable();
      badgeBackground.setCornerRadius(dp(20));
      badgeBackground.setColor(0xFF2E7D32);
      badge.setBackground(badgeBackground);
      badge.setPadding(dp(10), dp(3), dp(10), dp(3));
      titleRow.addView(badge);
    }

    TextView subtitle = new TextView(this);
    String author =
        TextUtils.isEmpty(mod.manifest.author)
            ? getString(R.string.mods_unknown_author)
            : mod.manifest.author;
    subtitle.setText(getString(R.string.mods_version_author, mod.manifest.version, author));
    subtitle.setTextSize(12);
    subtitle.setPadding(0, dp(2), 0, dp(6));
    card.addView(subtitle);

    if (!TextUtils.isEmpty(mod.manifest.description)) {
      TextView description = new TextView(this);
      description.setText(mod.manifest.description);
      description.setTextSize(13);
      description.setPadding(0, 0, 0, dp(6));
      card.addView(description);
    }

    TextView capabilities = new TextView(this);
    capabilities.setText(capabilitiesSummary(mod));
    capabilities.setTextSize(12);
    capabilities.setPadding(0, 0, 0, dp(10));
    card.addView(capabilities);

    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    card.addView(actions);

    Button primary = new Button(this);
    primary.setAllCaps(false);
    primary.setText(active ? R.string.mods_deactivate : R.string.mods_activate);
    primary.setOnClickListener(
        view -> {
          if (active) {
            deactivate();
          } else {
            activate(mod);
          }
        });
    actions.addView(primary, new LinearLayout.LayoutParams(0, -2, 1.2f));

    if (mod.manifest.hasWebxdc()) {
      Button share = new Button(this);
      share.setAllCaps(false);
      share.setText(R.string.mods_share_app);
      share.setOnClickListener(view -> shareWebxdcApps(mod));
      actions.addView(share, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    Button details = new Button(this);
    details.setAllCaps(false);
    details.setText(R.string.mods_details);
    details.setOnClickListener(view -> showDetails(mod));
    actions.addView(details, new LinearLayout.LayoutParams(0, -2, 0.8f));

    Button delete = new Button(this);
    delete.setAllCaps(false);
    delete.setText(R.string.mods_delete);
    delete.setOnClickListener(view -> confirmDelete(mod, active));
    actions.addView(delete, new LinearLayout.LayoutParams(0, -2, 0.8f));

    return card;
  }

  private String capabilitiesSummary(@NonNull Mod mod) {
    List<String> capabilities = mod.manifest.capabilities();
    StringBuilder sb = new StringBuilder();
    for (String capability : capabilities) {
      if (sb.length() > 0) sb.append("  ·  ");
      sb.append(capabilityLabel(capability));
    }
    return sb.toString();
  }

  private String capabilityLabel(@Nullable String capability) {
    if (ModManifest.CAP_THEME.equals(capability)) return getString(R.string.mods_cap_theme);
    if (ModManifest.CAP_BACKGROUND.equals(capability)) {
      return getString(R.string.mods_cap_background);
    }
    if (ModManifest.CAP_SOUND.equals(capability)) return getString(R.string.mods_cap_sound);
    if (ModManifest.CAP_WEBXDC.equals(capability)) return getString(R.string.mods_cap_webxdc);
    return "";
  }

  // -------------------------------------------------------------- actions

  private void installMod(@NonNull Uri uri) {
    importButton.setEnabled(false);
    Toast.makeText(this, R.string.mods_installing, Toast.LENGTH_SHORT).show();
    executor.execute(
        () -> {
          try {
            Mod mod = ModInstaller.install(getApplicationContext(), uri);
            runOnUiThread(
                () -> {
                  if (isFinishing()) return;
                  importButton.setEnabled(true);
                  Toast.makeText(
                          this,
                          getString(R.string.mods_installed_ok, mod.name()),
                          Toast.LENGTH_LONG)
                      .show();
                  refresh();
                });
          } catch (ModException | RuntimeException e) {
            runOnUiThread(
                () -> {
                  if (isFinishing()) return;
                  importButton.setEnabled(true);
                  new AlertDialog.Builder(this)
                      .setTitle(R.string.mods_install_failed)
                      .setMessage(e.getMessage())
                      .setPositiveButton(android.R.string.ok, null)
                      .show();
                });
          }
        });
  }

  private void activate(@NonNull Mod mod) {
    try {
      ModManager.activate(this, mod);
      Toast.makeText(this, getString(R.string.mods_activated, mod.name()), Toast.LENGTH_SHORT)
          .show();
      recreate();
    } catch (RuntimeException e) {
      Toast.makeText(this, getString(R.string.mods_install_failed) + ": " + e.getMessage(),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void deactivate() {
    ModManager.deactivate(this);
    Toast.makeText(this, R.string.mods_deactivated, Toast.LENGTH_SHORT).show();
    recreate();
  }

  private void confirmDelete(@NonNull Mod mod, boolean active) {
    new AlertDialog.Builder(this)
        .setTitle(R.string.mods_delete)
        .setMessage(getString(R.string.mods_delete_confirm, mod.name()))
        .setPositiveButton(
            R.string.mods_delete,
            (dialog, which) -> {
              ModManager.uninstall(this, mod);
              Toast.makeText(this, R.string.mods_deleted, Toast.LENGTH_SHORT).show();
              recreate();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showDetails(@NonNull Mod mod) {
    StringBuilder sb = new StringBuilder();
    sb.append(mod.manifest.id).append('\n');
    sb.append(getString(R.string.mods_version_author, mod.manifest.version,
        TextUtils.isEmpty(mod.manifest.author)
            ? getString(R.string.mods_unknown_author)
            : mod.manifest.author));
    sb.append('\n');
    if (!TextUtils.isEmpty(mod.manifest.homepage)) {
      sb.append(mod.manifest.homepage).append('\n');
    }
    sb.append('\n');
    if (!TextUtils.isEmpty(mod.manifest.description)) {
      sb.append(mod.manifest.description).append('\n');
    }
    sb.append('\n').append(capabilitiesSummary(mod)).append('\n');
    sb.append(String.format(Locale.US, "%.1f MB", mod.sizeBytes() / (1024.0 * 1024.0)))
        .append('\n');
    sb.append(mod.dir.getAbsolutePath());

    new AlertDialog.Builder(this)
        .setTitle(mod.name())
        .setMessage(sb.toString())
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private void shareWebxdcApps(@NonNull Mod mod) {
    final List<File> files = mod.webxdcFiles();
    if (files.isEmpty()) {
      Toast.makeText(this, R.string.mods_no_webxdc, Toast.LENGTH_SHORT).show();
      return;
    }
    if (files.size() == 1) {
      shareFile(files.get(0));
      return;
    }
    List<String> names = new ArrayList<>(files.size());
    for (File file : files) names.add(file.getName());
    new AlertDialog.Builder(this)
        .setTitle(R.string.mods_share_app)
        .setItems(
            names.toArray(new String[0]),
            (dialog, which) -> shareFile(files.get(which)))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void shareFile(@NonNull File file) {
    String uri = ModManager.contentUri(this, file);
    if (uri == null) {
      Toast.makeText(this, R.string.mods_install_failed, Toast.LENGTH_SHORT).show();
      return;
    }
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType(MediaUtil.WEBXDC);
    send.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      startActivity(Intent.createChooser(send, getString(R.string.mods_share_app)));
    } catch (RuntimeException e) {
      Toast.makeText(this, R.string.mods_no_webxdc, Toast.LENGTH_SHORT).show();
    }
  }

  private int dp(int value) {
    return Math.round(getResources().getDisplayMetrics().density * value);
  }
}
