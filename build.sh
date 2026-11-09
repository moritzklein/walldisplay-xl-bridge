#!/usr/bin/env bash
# Baut WallKeys.apk ohne Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# Voraussetzungen: JDK 11+ (getestet mit JDK 25), Android build-tools r34 und Plattform android-30.
#
# Umgebungsvariablen (alle optional):
#   ANDROID_SDK        Basisordner             (Standard: C:/android-sdk bzw. ~/android-sdk)
#   BUILD_TOOLS        Ordner mit aapt2, d8 …  (Standard: $ANDROID_SDK/android-14 bzw. build-tools/34.0.0)
#   ANDROID_JAR        android.jar (API 30)    (Standard: erstes $ANDROID_SDK/android-*/android.jar)
#   WALLKEYS_KEYSTORE  Signatur-Keystore       (Standard: wallkeys.keystore, wird bei Bedarf erzeugt)
#   WALLKEYS_KS_PASS   Keystore-Passwort       (Standard: wallkeys)
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe"; SEP=";"; DEF_SDK="C:/android-sdk" ;;
    *)                    EXE="";     SEP=":"; DEF_SDK="$HOME/android-sdk" ;;
esac
SDK="${ANDROID_SDK:-$DEF_SDK}"
if [ -z "${BUILD_TOOLS:-}" ]; then
    if [ -d "$SDK/build-tools/34.0.0" ]; then BUILD_TOOLS="$SDK/build-tools/34.0.0"; else BUILD_TOOLS="$SDK/android-14"; fi
fi
BT="$BUILD_TOOLS"
if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR="$(ls "$SDK"/platforms/android-30/android.jar "$SDK"/android-*/android.jar 2>/dev/null | head -1 || true)"
fi
[ -f "$ANDROID_JAR" ] || { echo "android.jar (API 30) nicht gefunden – ANDROID_SDK/ANDROID_JAR setzen"; exit 1; }
[ -f "$BT/aapt2$EXE" ] || { echo "build-tools nicht gefunden unter $BT – BUILD_TOOLS setzen"; exit 1; }

KS="${WALLKEYS_KEYSTORE:-wallkeys.keystore}"
KS_PASS="${WALLKEYS_KS_PASS:-wallkeys}"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "== Ressourcen"
"$BT/aapt2$EXE" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2$EXE" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 30 --target-sdk-version 30 \
    --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"

echo "== Java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# Hinweis: keine anonymen/inneren Klassen mit outer-this verwenden – d8 r34 stürzt an dem
# namenlosen MethodParameters-Attribut ab, das neuere JDKs dafür erzeugen.
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR$SEP$BT/core-lambda-stubs.jar" \
    -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v '^warning\|^Note\|^1 warning' || true
[ -f "$OUT/classes/de/wallkeys/KeyService.class" ] || { echo "javac fehlgeschlagen"; exit 1; }

echo "== Dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$ANDROID_JAR" --output "$OUT" @"$OUT/classes.txt"
( cd "$OUT" && "$BT/aapt$EXE" add unsigned.apk classes.dex >/dev/null )

echo "== Signieren"
if [ ! -f "$KS" ]; then
    echo "Erzeuge neuen Signatur-Keystore $KS (gut aufheben – Updates brauchen denselben Schlüssel!)"
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias wallkeys \
        -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=WallKeys" >/dev/null
fi
"$BT/zipalign$EXE" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias wallkeys \
    --out WallKeys.apk "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify WallKeys.apk
ls -l WallKeys.apk
