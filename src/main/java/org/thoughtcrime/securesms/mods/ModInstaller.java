package org.thoughtcrime.securesms.mods;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates and installs {@code .acmod} packages (plain ZIP files with a {@code manifest.json}).
 *
 * <p>Security rules enforced here, in the spirit of "never trust a file that came from the
 * community":
 *
 * <ul>
 *   <li>maximum archive size, entry count and total uncompressed size (zip-bomb protection);
 *   <li>no absolute paths, no {@code ..} segments, no Windows separators, no control characters
 *       (zip-slip protection), and every extracted file is re-checked against the canonical
 *       directory;
 *   <li>extension allow-list: images, audio, {@code .xdc} archives, JSON and text. Executables,
 *       libraries, DEX/APK/JAR, JavaScript and shell scripts are rejected;
 *   <li>per-file size caps, and every file referenced by the manifest must exist;
 *   <li>the package is extracted to a temporary directory and moved into place only if everything
 *       validated correctly.
 * </ul>
 */
public final class ModInstaller {

  /** Largest accepted {@code .acmod} file. */
  public static final long MAX_ARCHIVE_BYTES = 32L * 1024 * 1024;

  /** Largest accepted sum of uncompressed entry sizes. */
  public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

  public static final int MAX_ENTRIES = 256;

  public static final long MAX_BACKGROUND_BYTES = 8L * 1024 * 1024;
  public static final long MAX_SOUND_BYTES = 3L * 1024 * 1024;
  public static final long MAX_WEBXDC_BYTES = 16L * 1024 * 1024;

  /** Extensions a mod may contain. Everything else is rejected. */
  private static final String[] ALLOWED_EXTENSIONS = {
    "json", "md", "txt", "png", "jpg", "jpeg", "webp", "gif", "ogg", "mp3", "m4a", "opus", "wav",
    "xdc"
  };

  private static final String MANIFEST = "manifest.json";

  private ModInstaller() {}

  /**
   * Reads, validates and installs the package behind {@code uri}.
   *
   * @return the freshly installed mod (not activated yet).
   * @throws ModException with a user displayable message when the package is invalid.
   */
  public static @NonNull Mod install(@NonNull Context context, @NonNull Uri uri)
      throws ModException {
    File workDir = new File(context.getCacheDir(), "mod-install-" + System.nanoTime());
    if (!workDir.mkdirs() && !workDir.isDirectory()) {
      throw new ModException("Cannot create a temporary directory");
    }

    File archive = new File(workDir, "package.zip");
    try {
      copyToFile(context, uri, archive, MAX_ARCHIVE_BYTES);

      ModManifest manifest;
      File contentDir = new File(workDir, "content");
      try (ZipFile zip = new ZipFile(archive)) {
        Map<String, ZipEntry> entries = collectEntries(zip);
        manifest = readManifest(zip, entries);
        requireReferencedFiles(manifest, entries);
        extract(zip, entries, contentDir, manifest);
      }

      File destination = new File(ModManager.modsDir(context), manifest.id);
      if (destination.exists()) {
        // Reinstalling/updating: stop using the old copy first so that the restored preferences
        // refer to real files again.
        if (ModManager.isActive(context, manifest.id)) {
          ModManager.deactivate(context);
        }
        ModManager.deleteRecursively(destination);
      }
      if (!contentDir.renameTo(destination)) {
        copyRecursively(contentDir, destination);
      }

      Mod installed = ModManager.load(destination);
      if (installed == null) {
        throw new ModException("The mod could not be installed (manifest unreadable afterwards)");
      }
      return installed;
    } catch (IOException e) {
      throw new ModException("I/O error while reading the package: " + e.getMessage(), e);
    } finally {
      ModManager.deleteRecursively(workDir);
    }
  }

  // ------------------------------------------------------------- validation

  private static Map<String, ZipEntry> collectEntries(ZipFile zip) throws ModException {
    Map<String, ZipEntry> entries = new LinkedHashMap<>();
    long total = 0;
    int count = 0;
    Enumeration<? extends ZipEntry> enumeration = zip.entries();
    while (enumeration.hasMoreElements()) {
      ZipEntry entry = enumeration.nextElement();
      if (++count > MAX_ENTRIES) {
        throw new ModException("The package has more than " + MAX_ENTRIES + " files");
      }
      if (entry.isDirectory()) continue;

      String name = ModManifest.sanitizeRelativePath(entry.getName());
      if (name.isEmpty()) {
        throw new ModException("Unsafe path in package: " + entry.getName());
      }
      if (!hasAllowedExtension(name)) {
        throw new ModException("File type not allowed in a mod: " + name);
      }
      long size = entry.getSize();
      total += Math.max(0, size);
      entries.put(name, entry);
    }

    if (!entries.containsKey(MANIFEST)) {
      throw new ModException("manifest.json is missing (a .acmod is a ZIP with manifest.json)");
    }
    if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
      throw new ModException("The package is too big when uncompressed");
    }
    return entries;
  }

  private static ModManifest readManifest(ZipFile zip, Map<String, ZipEntry> entries)
      throws ModException, IOException {
    ZipEntry entry = entries.get(MANIFEST);
    byte[] bytes = readEntry(zip, entry, ModManifest.MAX_MANIFEST_BYTES);
    return ModManifest.fromJson(new String(bytes, StandardCharsets.UTF_8));
  }

  private static void requireReferencedFiles(ModManifest manifest, Map<String, ZipEntry> entries)
      throws ModException {
    for (String path : manifest.referencedFiles()) {
      ZipEntry entry = entries.get(path);
      if (entry == null) {
        throw new ModException("Missing file declared in manifest.json: " + path);
      }
      long size = entry.getSize();
      if (manifest.background.equals(path) && size > MAX_BACKGROUND_BYTES) {
        throw new ModException("Background image is larger than 8 MB");
      }
      if (manifest.sound.equals(path) && size > MAX_SOUND_BYTES) {
        throw new ModException("Notification sound is larger than 3 MB");
      }
      if (manifest.webxdc.contains(path) && size > MAX_WEBXDC_BYTES) {
        throw new ModException("WebXDC app " + path + " is larger than 16 MB");
      }
    }
  }

  private static void extract(
      ZipFile zip, Map<String, ZipEntry> entries, File contentDir, ModManifest manifest)
      throws ModException, IOException {
    if (!contentDir.mkdirs() && !contentDir.isDirectory()) {
      throw new ModException("Cannot create the mod directory");
    }
    String root = contentDir.getCanonicalPath() + File.separator;

    for (Map.Entry<String, ZipEntry> item : entries.entrySet()) {
      String name = item.getKey();
      File target = new File(contentDir, name);
      if (!target.getCanonicalPath().startsWith(root)) {
        throw new ModException("Path escapes the mod directory: " + name);
      }
      File parent = target.getParentFile();
      if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
        throw new ModException("Cannot create directory for " + name);
      }

      long limit = MAX_TOTAL_UNCOMPRESSED_BYTES;
      if (manifest.background.equals(name)) limit = MAX_BACKGROUND_BYTES;
      else if (manifest.sound.equals(name)) limit = MAX_SOUND_BYTES;
      else if (manifest.webxdc.contains(name)) limit = MAX_WEBXDC_BYTES;

      try (InputStream in = zip.getInputStream(item.getValue());
          FileOutputStream out = new FileOutputStream(target)) {
        copyWithLimit(in, out, limit);
      }
    }
  }

  private static boolean hasAllowedExtension(String name) {
    int dot = name.lastIndexOf('.');
    if (dot < 0) return false;
    String extension = name.substring(dot + 1).toLowerCase(Locale.US);
    for (String allowed : ALLOWED_EXTENSIONS) {
      if (allowed.equals(extension)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------- I/O

  private static void copyToFile(Context context, Uri uri, File target, long limit)
      throws ModException, IOException {
    InputStream in;
    try {
      in = context.getContentResolver().openInputStream(uri);
    } catch (SecurityException e) {
      throw new ModException("No permission to read the selected file", e);
    }
    if (in == null) {
      throw new ModException("The selected file cannot be opened");
    }
    try (InputStream input = in; FileOutputStream output = new FileOutputStream(target)) {
      copyWithLimit(input, output, limit);
    }
  }

  private static void copyWithLimit(InputStream in, FileOutputStream out, long limit)
      throws ModException, IOException {
    byte[] buffer = new byte[16 * 1024];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > limit) {
        throw new ModException("File exceeds the " + (limit / (1024 * 1024)) + " MB limit");
      }
      out.write(buffer, 0, read);
    }
  }

  private static byte[] readEntry(ZipFile zip, ZipEntry entry, long limit)
      throws ModException, IOException {
    try (InputStream in = zip.getInputStream(entry)) {
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
      return out.toByteArray();
    }
  }

  private static void copyRecursively(File source, File destination) throws IOException {
    if (source.isDirectory()) {
      if (!destination.exists() && !destination.mkdirs() && !destination.isDirectory()) {
        throw new IOException("Cannot create " + destination);
      }
      File[] children = source.listFiles();
      if (children == null) return;
      for (File child : children) {
        copyRecursively(child, new File(destination, child.getName()));
      }
    } else {
      File parent = destination.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      try (InputStream in = new FileInputStream(source);
          FileOutputStream out = new FileOutputStream(destination)) {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
      }
    }
  }
}
