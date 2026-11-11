#!/usr/bin/env bash
# Builds the Zygisk module zip: hook dex -> library with the dex embedded -> signed app -> zip.
# Run by .github/workflows/zygisk.yml. Needs ANDROID_HOME, ANDROID_NDK_HOME, a JDK, CMake 3.28+
# and Ninja 1.11+ (C++ modules in LSPlant).
#
# The app is signed when SIGNING_KEYSTORE (a file), SIGNING_STORE_PASSWORD and SIGNING_KEY_ALIAS
# are set, and left out otherwise: a build without the key still gives a module that can be
# tested, only without the companion app.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-$ROOT/zygisk/build}
ABI=arm64-v8a
: "${ANDROID_HOME:?}" "${ANDROID_NDK_HOME:?}"

PLATFORM_JAR=$ANDROID_HOME/platforms/android-36/android.jar
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)
[ -f "$PLATFORM_JAR" ] || { echo "missing $PLATFORM_JAR" >&2; exit 1; }
[ -f "$ROOT/zygisk/external/lsplant/lsplant/src/main/jni/external/dex_builder/CMakeLists.txt" ] \
  || { echo "submodules missing: git submodule update --init --recursive" >&2; exit 1; }

VERSION_NAME=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle")
VERSION_CODE=$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$ROOT/app/build.gradle")
COMMIT=$(git -C "$ROOT" rev-parse --short HEAD)
VERSION="$VERSION_NAME"

rm -rf "$OUT"
mkdir -p "$OUT/src" "$OUT/classes" "$OUT/dex"

echo "== hook dex"
# The hook code is the hook module's sources. Only the version constants are filled in, in a
# copy, so the working tree stays as committed.
cp -r "$ROOT/hook/src/main/java/." "$OUT/src/"
sed -i "s/@VERSION@/$VERSION/; s/@COMMIT@/$COMMIT/" "$OUT/src/com/kooritea/fcmfix/zygisk/BuildInfo.java"
find "$OUT/src" -name '*.java' > "$OUT/sources.txt"
# Plain string concatenation: no invokedynamic for d8 to rewrite.
javac -source 17 -target 17 -Xlint:-options -XDstringConcat=inline -encoding UTF-8 \
  -classpath "$PLATFORM_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
jar --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$BUILD_TOOLS/d8" --release --min-api 29 --lib "$PLATFORM_JAR" --output "$OUT/dex" "$OUT/classes.jar"
# The module is built to run on one dex file; a second one would not be loaded.
[ "$(ls "$OUT/dex" | wc -l)" -eq 1 ] || { echo "hook dex is not a single file" >&2; exit 1; }
unzip -p "$OUT/classes.jar" com/kooritea/fcmfix/zygisk/ZygiskEntry.class > /dev/null

echo "== library ($ABI)"
cmake -S "$ROOT/zygisk" -B "$OUT/cmake" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM=android-29 \
  -DANDROID_STL=c++_static \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DFCMFIX_DEX="$OUT/dex/classes.dex"
cmake --build "$OUT/cmake" --target fcmfix_zygisk
LLVM_BIN=$(dirname "$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip)")
LIB=$OUT/cmake/libfcmfix_zygisk.so
cp "$LIB" "$OUT/libfcmfix_zygisk.unstripped.so"
"$LLVM_BIN/llvm-strip" --strip-all "$LIB"

# The whole contract with NeoZygisk is this one exported function.
"$LLVM_BIN/llvm-nm" -D --defined-only "$LIB" | tee "$OUT/exports.txt"
grep -q ' T zygisk_module_entry$' "$OUT/exports.txt"
[ "$(wc -l < "$OUT/exports.txt")" -eq 1 ] || { echo "unexpected exports" >&2; exit 1; }
# NeoZygisk loads the library from a memfd: there is no directory beside it to resolve a
# bundled dependency from, so everything it needs must already be a system library.
"$LLVM_BIN/llvm-readelf" -d "$LIB" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tee "$OUT/needed.txt"
if grep -vxE 'lib(c|m|dl|log|z)\.so' "$OUT/needed.txt"; then
  echo "unexpected shared library dependency" >&2
  exit 1
fi

echo "== module files"
STAGE=$OUT/module
cp -r "$ROOT/zygisk/module" "$STAGE"
mkdir -p "$STAGE/zygisk"
cp "$LIB" "$STAGE/zygisk/$ABI.so"
# The manager may run action.sh and the boot scripts directly; do not rely on the installer alone.
chmod 0755 "$STAGE"/*.sh
REPO=${GITHUB_REPOSITORY:-msnazhao/fcmfix-coloros}
sed -i "s/@VERSION@/$VERSION+$COMMIT/; s/@VERSION_CODE@/$VERSION_CODE/; s|@REPO@|$REPO|" "$STAGE/module.prop"

echo "== app"
if [ -n "${SIGNING_KEYSTORE:-}" ] && [ -f "$SIGNING_KEYSTORE" ]; then
  : "${SIGNING_STORE_PASSWORD:?}" "${SIGNING_KEY_ALIAS:?}"
  (cd "$ROOT" && ./gradlew --no-daemon :hook:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease)
  UNSIGNED=$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk
  [ -f "$UNSIGNED" ] || { echo "missing $UNSIGNED" >&2; exit 1; }
  "$BUILD_TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$OUT/aligned.apk"
  # The key and the store use one password. The v4 signature (.idsig) is only for streaming
  # installs over adb and would just be a stray file in the zip.
  "$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_KEYSTORE" --ks-pass env:SIGNING_STORE_PASSWORD \
    --ks-key-alias "$SIGNING_KEY_ALIAS" --key-pass env:SIGNING_STORE_PASSWORD \
    --v4-signing-enabled false \
    --out "$STAGE/fcmfix.apk" "$OUT/aligned.apk"
  "$BUILD_TOOLS/apksigner" verify --print-certs "$STAGE/fcmfix.apk"
else
  echo "no signing key: the module is built without the app"
fi

echo "== checks"
# One stray character from a Windows checkout would break the installer or the boot scripts.
if grep -rlI $'\r' "$STAGE"; then
  echo "CRLF line endings in the module files above" >&2
  exit 1
fi
if grep -n ';' "$STAGE/sepolicy.rule"; then
  echo "sepolicy.rule must not contain a semicolon" >&2
  exit 1
fi
for script in "$STAGE"/*.sh "$STAGE/META-INF/com/google/android/update-binary"; do
  sh -n "$script"
done

ZIP=$OUT/FCMFix-Zygisk-$VERSION_NAME-$COMMIT.zip
(cd "$STAGE" && zip -qr9 "$ZIP" .)
(cd "$OUT" && sha256sum "$(basename "$ZIP")" > SHA256SUMS)
unzip -l "$ZIP"
cat "$OUT/SHA256SUMS"
