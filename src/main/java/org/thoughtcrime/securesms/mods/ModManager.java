package org.thoughtcrime.securesms.mods;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Prefs;

/**
 * Installed mods registry plus the logic that applies a mod to the app.
 *
 * <p>Only one mod can be active at a time: a mod behaves like a "profile" that switches the colour
 * theme, the chat background and the notification sound. Before applying anything, the current
 * values are stored, so {@link #deactivate(Context)} restores the user's previous configuration
 * exactly.
 */
public final class ModManager {

  private static final String TAG = ModManager.class.getSimpleName();

  private static final String PREFS = "mods_state";
  private static final String KEY_ACTIVE = "active_id";
  private static final String KEY_HAS_BACKUP = "has_backup";
  private static final String KEY_BACKUP_THEME = "backup_theme";
  private static final String KEY_BACKUP_SOUND = "backup_sound";
  private static final String KEY_BACKUP_BACKGROUND = "backup_background_";
  private static final String KEY_ACTIVE_SOUND = "active_sound_uri";

  private static final String MANIFEST = "manifest.json";

  private ModManager() {}

  // --------------------------------------------------------------- storage

  public static @NonNull File modsDir(@NonNull Context context) {
    File dir = new File(context.getFilesDir(), "mods");
    if (!dir.exists() && !dir.mkdirs()) {
      Log.w(TAG, "Could not create mods directory " + dir);
    }
    return dir;
  }

  /** All installed mods, sorted by display name. Never throws for a broken package. */
  public static @NonNull List<Mod> list(@NonNull Context context) {
    List<Mod> mods = new ArrayList<>();
    File[] children = modsDir(context).listFiles();
    if (children == null) return mods;
    for (File child : children) {
      if (!child.isDirectory()) continue;
      Mod mod = load(child);
      if (mod != null) mods.add(mod);
    }
    Collections.sort(
        mods,
        new Comparator<Mod>() {
          @Override
          public int compare(Mod a, Mod b) {
            return a.name().compareToIgnoreCase(b.name());
          }
        });
    return mods;
  }

  public static @Nullable Mod find(@NonNull Context context, @Nullable String id) {
    if (id == null || id.isEmpty()) return null;
    for (Mod mod : list(context)) {
      if (mod.id().equals(id)) return mod;
    }
    return null;
  }

  /** Reads a mod from disk; {@code null} when the package is incomplete or unreadable. */
  public static @Nullable Mod load(@NonNull File dir) {
    File manifestFile = new File(dir, MANIFEST);
    if (!manifestFile.isFile()) return null;
    try {
      String json = readText(manifestFile, ModManifest.MAX_MANIFEST_BYTES);
      return new Mod(ModManifest.fromJson(json), dir);
    } catch (ModException | IOException e) {
      Log.w(TAG, "Ignoring invalid mod in " + dir, e);
      return null;
    }
  }

  // ------------------------------------------------------------ activation

  public static @Nullable String getActiveId(@NonNull Context context) {
    String id = prefs(context).getString(KEY_ACTIVE, "");
    return id == null || id.isEmpty() ? null : id;
  }

  public static boolean isActive(@NonNull Context context, @NonNull String id) {
    return id.equals(getActiveId(context));
  }

  public static @Nullable Mod getActive(@NonNull Context context) {
    return find(context, getActiveId(context));
  }

  /**
   * Activates {@code mod}: any previously active mod is deactivated first (restoring its backup),
   * then the current settings are saved and the mod is applied.
   */
  public static void activate(@NonNull Context context, @NonNull Mod mod) {
    deactivate(context);

    SharedPreferences.Editor editor = prefs(context).edit();
    editor.putBoolean(KEY_HAS_BACKUP, true);
    editor.putString(KEY_BACKUP_THEME, Prefs.getTheme(context));
    editor.putString(KEY_BACKUP_SOUND, Prefs.getNotificationRingtone(context).toString());
    int accountId = currentAccountId(context);
    if (accountId > 0) {
      editor.putString(
          KEY_BACKUP_BACKGROUND + accountId, Prefs.getBackgroundImagePath(context, accountId));
    }
    editor.apply();

    ModManifest manifest = mod.manifest;

    if (manifest.hasTheme()) {
      setThemePreference(context, manifest.theme);
    }

    if (manifest.hasBackground() && accountId > 0) {
      File background = mod.backgroundFile();
      if (background != null) {
        Prefs.setBackgroundImagePath(context, accountId, background.getAbsolutePath());
      }
    }

    if (manifest.hasSound()) {
      File sound = mod.soundFile();
      if (sound != null) {
        // The notification sound is played by the system, so it has to be published in
        // MediaStore (a private FileProvider URI would be unreadable for the notification service).
        Uri mediaUri =
            ModSound.install(context, sound, "arcanechat-mod-" + manifest.id + "-" + sound.getName());
        if (mediaUri != null) {
          Prefs.setNotificationRingtone(context, mediaUri);
          prefs(context).edit().putString(KEY_ACTIVE_SOUND, mediaUri.toString()).apply();
        }
      }
    }

    prefs(context).edit().putString(KEY_ACTIVE, manifest.id).apply();
    Log.i(TAG, "Activated mod " + manifest.id);
  }

  /**
   * Deactivates the current mod and restores the settings that were in place before activating it.
   * Safe to call when no mod is active.
   */
  public static void deactivate(@NonNull Context context) {
    SharedPreferences preferences = prefs(context);
    if (!preferences.getBoolean(KEY_HAS_BACKUP, false)) {
      preferences.edit().remove(KEY_ACTIVE).apply();
      return;
    }

    String theme = preferences.getString(KEY_BACKUP_THEME, null);
    if (theme != null && !theme.isEmpty()) {
      setThemePreference(context, theme);
    }

    String sound = preferences.getString(KEY_BACKUP_SOUND, null);
    if (sound != null && !sound.isEmpty()) {
      Prefs.setNotificationRingtone(context, Uri.parse(sound));
    }

    int accountId = currentAccountId(context);
    if (accountId > 0) {
      String background = preferences.getString(KEY_BACKUP_BACKGROUND + accountId, null);
      if (background != null) {
        Prefs.setBackgroundImagePath(context, accountId, background);
      }
    }

    String activeSound = preferences.getString(KEY_ACTIVE_SOUND, null);
    if (activeSound != null) {
      ModSound.remove(context, activeSound);
    }

    SharedPreferences.Editor editor =
        preferences
            .edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_HAS_BACKUP)
            .remove(KEY_BACKUP_THEME)
            .remove(KEY_BACKUP_SOUND)
            .remove(KEY_ACTIVE_SOUND);
    if (accountId > 0) {
      editor.remove(KEY_BACKUP_BACKGROUND + accountId);
    }
    editor.apply();
    Log.i(TAG, "Deactivated mod, previous settings restored");
  }

  /** Deactivates (if needed) and deletes the mod from disk. */
  public static void uninstall(@NonNull Context context, @NonNull Mod mod) {
    if (isActive(context, mod.id())) {
      deactivate(context);
    }
    deleteRecursively(mod.dir);
  }

  // -------------------------------------------------------------- helpers

  /** Public {@code content://} URI for a file inside {@code files/mods/}. */
  public static @Nullable String contentUri(@NonNull Context context, @NonNull File file) {
    try {
      return FileProvider.getUriForFile(
              context, BuildConfig.APPLICATION_ID + ".fileprovider", file)
          .toString();
    } catch (IllegalArgumentException e) {
      Log.w(TAG, "Cannot expose mod file " + file, e);
      return null;
    }
  }

  private static void setThemePreference(@NonNull Context context, @NonNull String theme) {
    // Prefs has no setter for the theme: it is normally written by the ListPreference widget.
    PreferenceManager.getDefaultSharedPreferences(context)
        .edit()
        .putString(Prefs.THEME_PREF, theme)
        .apply();
  }

  private static int currentAccountId(@NonNull Context context) {
    try {
      return DcHelper.getContext(context).getAccountId();
    } catch (RuntimeException e) {
      Log.w(TAG, "No account available while applying mod", e);
      return -1;
    }
  }

  private static SharedPreferences prefs(@NonNull Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static String readText(File file, long limit) throws IOException, ModException {
    try (InputStream in = new FileInputStream(file)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8 * 1024];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > limit) {
          throw new ModException("manifest.json is too large");
        }
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  static void deleteRecursively(@Nullable File file) {
    if (file == null || !file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }
    if (!file.delete()) {
      Log.w(TAG, "Could not delete " + file);
    }
  }
}
