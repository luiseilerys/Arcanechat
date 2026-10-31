#!/usr/bin/env python3
"""Valida un paquete .acmod con las MISMAS reglas que ModInstaller.java.

Uso:
    python3 scripts/validar-mod.py dist/mi-mod-1.0.0.acmod [-v]

Salida: resumen del mod y 0 si es válido, 1 si no lo es.
Mantener este script sincronizado con src/main/java/org/thoughtcrime/securesms/mods/ModInstaller.java
y ModManifest.java: es la herramienta que usa `scripts/empaquetar-mod.sh`.
"""

import json
import os
import re
import sys
import zipfile

MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED = 64 * 1024 * 1024
MAX_ENTRIES = 256
MAX_BACKGROUND_BYTES = 8 * 1024 * 1024
MAX_SOUND_BYTES = 3 * 1024 * 1024
MAX_WEBXDC_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_BYTES = 64 * 1024

ALLOWED_EXT = {
    "json", "md", "txt", "png", "jpg", "jpeg", "webp", "gif",
    "ogg", "mp3", "m4a", "opus", "wav", "xdc",
}
THEMES = {"light", "dark", "blue", "gray", "green", "pink", "purple", "red", "system"}
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
VERSION_RE = re.compile(r"^[0-9]+(\.[0-9]+){0,3}([-+][0-9A-Za-z.-]{1,32})?$")
BAD_CHARS = set(': *?"<>|')


class Invalid(Exception):
    pass


def sanitize(path):
    if path is None:
        return ""
    path = path.strip().replace("\\", "/")
    if not path or len(path) > 200 or path.startswith("/"):
        return ""
    for segment in path.split("/"):
        if segment in ("", ".", ".."):
            return ""
        if any(ord(c) < 0x20 or ord(c) == 0x7F or c in BAD_CHARS for c in segment):
            return ""
    return path


def extension(path):
    _, dot, ext = path.rpartition(".")
    return ext.lower() if dot else ""


def validate(path, verbose=False):
    size = os.path.getsize(path)
    if size > MAX_ARCHIVE_BYTES:
        raise Invalid(f"el archivo pesa {size} bytes y el límite es {MAX_ARCHIVE_BYTES}")

    with zipfile.ZipFile(path) as zf:
        infos = [i for i in zf.infolist() if not i.is_dir()]
        if len(infos) > MAX_ENTRIES:
            raise Invalid(f"más de {MAX_ENTRIES} archivos")

        entries = {}
        total = 0
        for info in infos:
            name = sanitize(info.filename)
            if not name:
                raise Invalid(f"ruta insegura: {info.filename!r}")
            if extension(name) not in ALLOWED_EXT:
                raise Invalid(f"tipo de archivo no permitido: {name}")
            entries[name] = info
            total += info.file_size
        if total > MAX_TOTAL_UNCOMPRESSED:
            raise Invalid("el paquete es demasiado grande al descomprimir")

        if "manifest.json" not in entries:
            raise Invalid("falta manifest.json en la raíz del paquete")
        if entries["manifest.json"].file_size > MAX_MANIFEST_BYTES:
            raise Invalid("manifest.json es demasiado grande")

        raw = zf.read("manifest.json").decode("utf-8")
        try:
            manifest = json.loads(raw)
        except ValueError as exc:
            raise Invalid(f"manifest.json no es JSON válido: {exc}")

        fmt = manifest.get("format", 1)
        if not isinstance(fmt, int) or fmt < 1 or fmt > 1:
            raise Invalid(f"formato de mod no soportado: {fmt!r}")

        mod_id = str(manifest.get("id", "")).strip()
        if not ID_RE.match(mod_id):
            raise Invalid(f"id inválido {mod_id!r}: 3-64 caracteres [a-z0-9._-]")

        version = str(manifest.get("version", "")).strip() or "0"
        if not VERSION_RE.match(version):
            raise Invalid(f"versión inválida {version!r}")

        theme = str(manifest.get("theme", "")).strip().lower()
        if theme and theme not in THEMES:
            raise Invalid(f"tema desconocido {theme!r}")

        background = sanitize(str(manifest.get("background", "")))
        sound = sanitize(str(manifest.get("notificationSound", "")))
        webxdc = []
        for item in manifest.get("webxdc", []) or []:
            cleaned = sanitize(str(item))
            if not cleaned:
                raise Invalid(f"ruta WebXDC inválida: {item!r}")
            webxdc.append(cleaned)

        capabilities = []
        if theme:
            capabilities.append("theme")
        if background:
            capabilities.append("background")
        if sound:
            capabilities.append("sound")
        if webxdc:
            capabilities.append("webxdc")
        if not capabilities:
            raise Invalid("el mod no declara ninguna capacidad (theme/background/notificationSound/webxdc)")

        for reference in [p for p in [background, sound] if p] + webxdc:
            if reference not in entries:
                raise Invalid(f"falta el archivo declarado: {reference}")
            if reference == background and entries[reference].file_size > MAX_BACKGROUND_BYTES:
                raise Invalid("la imagen de fondo supera 8 MB")
            if reference == sound and entries[reference].file_size > MAX_SOUND_BYTES:
                raise Invalid("el sonido supera 3 MB")
            if reference in webxdc and entries[reference].file_size > MAX_WEBXDC_BYTES:
                raise Invalid(f"{reference} supera 16 MB")

        summary = {
            "id": mod_id,
            "name": manifest.get("name") or mod_id,
            "version": version,
            "author": manifest.get("author", ""),
            "capabilities": capabilities,
            "files": len(entries),
            "uncompressed_bytes": total,
        }

    print(f"✔ VÁLIDO: {summary['name']} v{summary['version']} ({summary['id']})")
    print(f"  capacidades: {', '.join(summary['capabilities'])}")
    print(f"  archivos: {summary['files']} · descomprimido: {summary['uncompressed_bytes']} bytes")
    if verbose:
        for name in sorted(entries):
            print(f"  - {name} ({entries[name].file_size} bytes)")
    return summary


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip())
        return 2
    path = argv[1]
    verbose = "-v" in argv[2:]
    try:
        validate(path, verbose)
        return 0
    except Invalid as exc:
        print(f"✘ INVÁLIDO: {exc}")
        return 1
    except zipfile.BadZipFile:
        print("✘ INVÁLIDO: el archivo no es un ZIP (¿le falta el empaquetado?)")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
