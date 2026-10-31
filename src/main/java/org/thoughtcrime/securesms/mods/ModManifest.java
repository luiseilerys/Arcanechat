package org.thoughtcrime.securesms.mods;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Declarative description of a community mod, parsed from {@code manifest.json}.
 *
 * <p>Mods are intentionally <b>data-only</b>: a mod can select one of the built-in colour themes,
 * provide a chat background image, a notification sound and/or bundled WebXDC apps. There is no
 * executable code inside a mod (no dex, no native libraries, no JavaScript), which keeps the attack
 * surface small while still allowing the community to customise the app.
 *
 * <p>Example manifest:
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "id": "org.ejemplo.neo-verde",
 *   "name": "Neo Verde",
 *   "version": "1.0.0",
 *   "author": "Comunidad ArcaneChat",
 *   "description": "Tema verde con fondo propio y sonido de notificación.",
 *   "theme": "green",
 *   "background": "background.png",
 *   "notificationSound": "sounds/notify.ogg",
 *   "webxdc": ["apps/mapa.xdc"],
 *   "homepage": "https://example.org/mi-mod"
 * }
 * }</pre>
 */
public final class ModManifest {

  /** Highest manifest format understood by this build. */
  public static final int FORMAT = 1;

  /** Hard limit for the manifest itself, to avoid zip bombs with a huge JSON file. */
  public static final int MAX_MANIFEST_BYTES = 64 * 1024;

  public static final String CAP_THEME = "theme";
  public static final String CAP_BACKGROUND = "background";
  public static final String CAP_SOUND = "sound";
  public static final String CAP_WEBXDC = "webxdc";

  /** Values accepted for {@code theme}; they match {@code R.array.pref_theme_values}. */
  private static final List<String> THEMES =
      Collections.unmodifiableList(
          Arrays.asList("light", "dark", "blue", "gray", "green", "pink", "purple", "red", "system"));

  private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
  private static final Pattern VERSION_PATTERN =
      Pattern.compile("^[0-9]+(\\.[0-9]+){0,3}([-+][0-9A-Za-z.-]{1,32})?$");

  private static final int MAX_NAME = 80;
  private static final int MAX_AUTHOR = 80;
  private static final int MAX_DESCRIPTION = 500;
  private static final int MAX_HOMEPAGE = 200;
  private static final int MAX_PATH = 200;

  public final String id;
  public final String name;
  public final String version;
  public final String author;
  public final String description;
  /** Empty when the mod does not change the theme. */
  public final String theme;
  /** Relative path inside the mod package, empty when unused. */
  public final String background;
  /** Relative path inside the mod package, empty when unused. */
  public final String sound;
  /** Relative paths of bundled {@code .xdc} files, never {@code null}. */
  public final List<String> webxdc;
  /** Optional project/homepage URL. */
  public final String homepage;

  private ModManifest(
      String id,
      String name,
      String version,
      String author,
      String description,
      String theme,
      String background,
      String sound,
      List<String> webxdc,
      String homepage) {
    this.id = id;
    this.name = name;
    this.version = version;
    this.author = author;
    this.description = description;
    this.theme = theme;
    this.background = background;
    this.sound = sound;
    this.webxdc = webxdc;
    this.homepage = homepage;
  }

  /**
   * Parses and validates a manifest.
   *
   * @throws ModException when the manifest is malformed, contains unsupported values or does not
   *     declare any capability.
   */
  public static @NonNull ModManifest fromJson(@NonNull String json) throws ModException {
    final JSONObject o;
    try {
      o = new JSONObject(json);
    } catch (JSONException e) {
      throw new ModException("manifest.json is not valid JSON", e);
    }

    int format = o.optInt("format", FORMAT);
    if (format < 1 || format > FORMAT) {
      throw new ModException("Unsupported mod format: " + format);
    }

    String id = str(o, "id");
    if (!ID_PATTERN.matcher(id).matches()) {
      throw new ModException(
          "Invalid mod id \"" + id + "\": use 3-64 chars of a-z, 0-9, '.', '_' or '-'");
    }

    String name = limit(str(o, "name"), MAX_NAME, "name");
    if (name.isEmpty()) {
      name = id;
    }

    String version = str(o, "version");
    if (version.isEmpty()) {
      version = "0";
    }
    if (!VERSION_PATTERN.matcher(version).matches()) {
      throw new ModException("Invalid mod version \"" + version + "\"");
    }

    String theme = str(o, "theme").toLowerCase();
    if (!theme.isEmpty() && !THEMES.contains(theme)) {
      throw new ModException(
          "Unknown theme \"" + theme + "\"; use one of: " + join(THEMES, ", "));
    }

    String background = normalizePath(o, "background");
    String sound = normalizePath(o, "notificationSound");

    List<String> webxdc = new ArrayList<>();
    JSONArray webxdcArray = o.optJSONArray("webxdc");
    if (webxdcArray != null) {
      for (int i = 0; i < webxdcArray.length(); i++) {
        String path = webxdcArray.optString(i, "").trim();
        path = sanitizeRelativePath(path);
        if (path.isEmpty()) {
          throw new ModException("Invalid path in \"webxdc\" entry #" + (i + 1));
        }
        webxdc.add(path);
      }
    }

    String homepage = str(o, "homepage");
    if (!homepage.isEmpty() && !homepage.startsWith("https://")) {
      throw new ModException("homepage must start with https://");
    }
    limit(homepage, MAX_HOMEPAGE, "homepage");

    ModManifest manifest =
        new ModManifest(
            id,
            name,
            version,
            limit(str(o, "author"), MAX_AUTHOR, "author"),
            limit(str(o, "description"), MAX_DESCRIPTION, "description"),
            theme,
            background,
            sound,
            Collections.unmodifiableList(webxdc),
            homepage);

    if (manifest.capabilities().isEmpty()) {
      throw new ModException(
          "The mod does nothing: declare \"theme\", \"background\", \"notificationSound\" or \"webxdc\"");
    }

    return manifest;
  }

  /** Capability keys in a stable display order. */
  public @NonNull List<String> capabilities() {
    List<String> result = new ArrayList<>(4);
    if (!theme.isEmpty()) result.add(CAP_THEME);
    if (!background.isEmpty()) result.add(CAP_BACKGROUND);
    if (!sound.isEmpty()) result.add(CAP_SOUND);
    if (!webxdc.isEmpty()) result.add(CAP_WEBXDC);
    return result;
  }

  /** Files that must be present inside the package. */
  public @NonNull List<String> referencedFiles() {
    List<String> result = new ArrayList<>(2 + webxdc.size());
    if (!background.isEmpty()) result.add(background);
    if (!sound.isEmpty()) result.add(sound);
    result.addAll(webxdc);
    return result;
  }

  public boolean hasTheme() {
    return !theme.isEmpty();
  }

  public boolean hasBackground() {
    return !background.isEmpty();
  }

  public boolean hasSound() {
    return !sound.isEmpty();
  }

  public boolean hasWebxdc() {
    return !webxdc.isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private static String str(JSONObject o, String key) {
    return o.optString(key, "").trim();
  }

  private static String limit(String value, int max, String field) throws ModException {
    if (value.length() > max) {
      throw new ModException("\"" + field + "\" is longer than " + max + " characters");
    }
    return value;
  }

  private static String normalizePath(JSONObject o, String key) throws ModException {
    String raw = str(o, key);
    if (raw.isEmpty()) {
      return "";
    }
    String path = sanitizeRelativePath(raw);
    if (path.isEmpty()) {
      throw new ModException("Invalid path for \"" + key + "\": " + raw);
    }
    return path;
  }

  /**
   * Walks a user supplied path the way {@code ModInstaller} will store it: forward slashes, relative,
   * no empty/'.'/'..' segments, bounded length.
   *
   * @return the normalised path, or the empty string if it is not acceptable.
   */
  static @Nullable String sanitizeRelativePath(String raw) {
    if (raw == null) return "";
    String path = raw.trim().replace('\\', '/');
    if (path.isEmpty() || path.length() > MAX_PATH) return "";
    if (path.startsWith("/")) return "";
    String[] segments = path.split("/");
    for (String segment : segments) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return "";
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        if (c < 0x20 || c == '\u007f' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
            || c == '>' || c == '|') {
          return "";
        }
      }
    }
    return path;
  }

  static String join(List<String> values, String separator) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(separator);
      sb.append(values.get(i));
    }
    return sb.toString();
  }
}
