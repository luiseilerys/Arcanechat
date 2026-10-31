package org.thoughtcrime.securesms.mods;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** An installed mod: its manifest plus the directory that holds its files. */
public final class Mod {

  public final @NonNull ModManifest manifest;
  public final @NonNull File dir;

  Mod(@NonNull ModManifest manifest, @NonNull File dir) {
    this.manifest = manifest;
    this.dir = dir;
  }

  public @NonNull String id() {
    return manifest.id;
  }

  public @NonNull String name() {
    return manifest.name;
  }

  /** Resolves a manifest-relative path, or {@code null} when the file is absent. */
  public @Nullable File file(@NonNull String relativePath) {
    if (relativePath.isEmpty()) return null;
    File file = new File(dir, relativePath);
    return file.isFile() ? file : null;
  }

  public @Nullable File backgroundFile() {
    return file(manifest.background);
  }

  public @Nullable File soundFile() {
    return file(manifest.sound);
  }

  public @Nullable File iconFile() {
    File icon = file("icon.png");
    if (icon != null) return icon;
    icon = file("icon.jpg");
    if (icon != null) return icon;
    return file("icon.webp");
  }

  public @NonNull List<File> webxdcFiles() {
    List<File> result = new ArrayList<>(manifest.webxdc.size());
    for (String path : manifest.webxdc) {
      File file = file(path);
      if (file != null) result.add(file);
    }
    return result;
  }

  /** Size on disk in bytes. */
  public long sizeBytes() {
    return sizeOf(dir);
  }

  private static long sizeOf(File file) {
    if (file == null || !file.exists()) return 0;
    if (file.isFile()) return file.length();
    long total = 0;
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        total += sizeOf(child);
      }
    }
    return total;
  }
}
