package org.thoughtcrime.securesms.mods;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Publishes a mod's notification sound where the system can read it.
 *
 * <p>Notification sounds are played by the system (through {@code NotificationChannel.setSound()}
 * on Android 8+), therefore a {@code content://} URI served by our non-exported {@code
 * FileProvider} is not readable by the notification service. Instead the sound is inserted into
 * {@link MediaStore} as an audio file under {@code Notifications/ArcaneChatMods}, which yields a
 * world-readable {@code content://media/...} URI.
 *
 * <p>Scoped storage makes this possible without any permission on Android 10+; on older versions
 * the mod is still activated but the notification sound is skipped (see the docs).
 */
final class ModSound {

  private static final String TAG = ModSound.class.getSimpleName();
  private static final String ALBUM_DIR = "ArcaneChatMods";

  private ModSound() {}

  /**
   * Inserts {@code source} into MediaStore.
   *
   * @return the {@code content://} URI to store in the preferences, or {@code null} when the
   *     platform cannot handle it.
   */
  static @Nullable Uri install(
      @NonNull Context context, @NonNull File source, @NonNull String displayName) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      Log.w(TAG, "Notification sounds from mods need Android 10+; skipping " + displayName);
      return null;
    }
    return installQ(context.getContentResolver(), source, sanitize(displayName));
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private static @Nullable Uri installQ(
      @NonNull ContentResolver resolver, @NonNull File source, @NonNull String displayName) {
    ContentValues values = new ContentValues();
    values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
    values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType(displayName));
    values.put(
        MediaStore.Audio.Media.RELATIVE_PATH,
        Environment.DIRECTORY_NOTIFICATIONS + "/" + ALBUM_DIR);
    values.put(MediaStore.Audio.Media.IS_PENDING, 1);

    Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    Uri item = null;
    try {
      item = resolver.insert(collection, values);
      if (item == null) {
        Log.w(TAG, "MediaStore refused the notification sound");
        return null;
      }
      try (InputStream in = new FileInputStream(source);
          OutputStream out = resolver.openOutputStream(item)) {
        if (out == null) {
          throw new IOException("openOutputStream() returned null");
        }
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
      }
      ContentValues published = new ContentValues();
      published.put(MediaStore.Audio.Media.IS_PENDING, 0);
      resolver.update(item, published, null, null);
      return item;
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "Could not publish the notification sound", e);
      if (item != null) {
        try {
          resolver.delete(item, null, null);
        } catch (RuntimeException ignored) {
          // best effort cleanup
        }
      }
      return null;
    }
  }

  /** Removes a previously published sound; safe to call with {@code null} or stale URIs. */
  static void remove(@NonNull Context context, @Nullable String uri) {
    if (uri == null || uri.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
    try {
      context.getContentResolver().delete(Uri.parse(uri), null, null);
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not delete the mod notification sound", e);
    }
  }

  private static String sanitize(String name) {
    return name.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String mimeType(String name) {
    String lower = name.toLowerCase(Locale.US);
    if (lower.endsWith(".mp3")) return "audio/mpeg";
    if (lower.endsWith(".m4a")) return "audio/mp4";
    if (lower.endsWith(".wav")) return "audio/x-wav";
    if (lower.endsWith(".flac")) return "audio/flac";
    return "audio/ogg";
  }
}
