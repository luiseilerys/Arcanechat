#!/usr/bin/env bash
# Empaqueta una carpeta de mod (con manifest.json en la raíz) en un .acmod válido.
#
# Uso:
#   ./scripts/empaquetar-mod.sh mods/ejemplo-neo-verde [carpeta_de_salida]
#
# El .acmod resultante se valida con scripts/validar-mod.py antes de darlo por bueno.
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Uso: $0 <carpeta-del-mod> [carpeta-de-salida]" >&2
    exit 2
fi

SRC=${1%/}
OUT_DIR=${2:-dist}

if [ ! -f "$SRC/manifest.json" ]; then
    echo "Error: $SRC/manifest.json no existe" >&2
    exit 1
fi

ID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$SRC/manifest.json")
VERSION=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("version","0"))' "$SRC/manifest.json")
NAME="${ID}-${VERSION}.acmod"

mkdir -p "$OUT_DIR"

# ZIP determinista (misma entrada => mismo hash), sin basura del sistema.
python3 - "$SRC" "$OUT_DIR/$NAME" <<'PY'
import os
import sys
import zipfile

source, destination = sys.argv[1], sys.argv[2]
IGNORED = {".DS_Store", "Thumbs.db", "desktop.ini"}

files = []
for dirpath, dirnames, filenames in os.walk(source):
    dirnames[:] = sorted(d for d in dirnames if d != ".git")
    for filename in sorted(filenames):
        if filename in IGNORED:
            continue
        full = os.path.join(dirpath, filename)
        rel = os.path.relpath(full, source).replace(os.sep, "/")
        files.append(rel)

files.sort()
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as zf:
    for rel in files:
        info = zipfile.ZipInfo(rel, date_time=(2024, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(os.path.join(source, rel), "rb") as src:
            zf.writestr(info, src.read())
print(f"  escrito {destination} ({len(files)} archivos)")
PY

if ! python3 "$(dirname "$0")/validar-mod.py" "$OUT_DIR/$NAME"; then
    rm -f "$OUT_DIR/$NAME"
    echo "El paquete no es válido y se ha borrado." >&2
    exit 1
fi

echo "OK -> $OUT_DIR/$NAME"
