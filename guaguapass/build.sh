#!/usr/bin/env bash
# Build de GuaguaPass APK sin Gradle: aapt2 + javac + d8 + zipalign + apksigner
set -e
SDK=${ANDROID_HOME:-/opt/android-sdk}
BT=$SDK/build-tools/34.0.0
AP=$SDK/platforms/android-34/android.jar
OUT=out
rm -rf $OUT && mkdir -p $OUT/obj

# 1) Recursos -> base.apk (genera R.java con IDs estables)
"$BT/aapt2" compile --dir res -o $OUT/res.zip
"$BT/aapt2" link -o $OUT/base.apk -I "$AP" --manifest AndroidManifest.xml \
    $OUT/res.zip --java $OUT/gen --auto-add-overlay

# 2) Compilar Java (incluye el R.java generado)
javac -nowarn -classpath "$AP" -d $OUT/obj $(find src $OUT/gen -name '*.java')

# 3) dex
"$BT/d8" --lib "$AP" --min-api 24 --output $OUT $(find $OUT/obj -name '*.class')

# 4) meter classes.dex dentro del apk (zip via python, portatil)
python3 - "$OUT/base.apk" "$OUT/classes.dex" "$OUT/final.pre.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1:4]
shutil.copy(base, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

# 5) alineado y firma
"$BT/zipalign" -f 4 $OUT/final.pre.apk $OUT/final.apk
KS=$OUT/guagua.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass guagua123 -keypass guagua123 \
   -alias guagua -keyalg RSA -keysize 2048 -validity 10000 \
   -dname "CN=GuaguaPass, OU=Dev, O=Guagua, L=SC, S=VC, C=CU" >/dev/null 2>&1
"$BT/apksigner" sign --ks $KS --ks-pass pass:guagua123 --key-pass pass:guagua123 \
   --out $OUT/GuaguaPass.apk $OUT/final.apk
echo "OK -> $OUT/GuaguaPass.apk"
