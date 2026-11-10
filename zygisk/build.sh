#!/usr/bin/env bash
# Builds the Zygisk module zip: hook dex -> library with the dex embedded -> flashable zip.
# Run by .github/workflows/zygisk.yml. Needs ANDROID_HOME, ANDROID_NDK_HOME, a JDK, CMake 3.28+
# and Ninja 1.11+ (C++ modules in LSPlant).
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
VERSION="$VERSION_NAME-zygisk-$COMMIT"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== hook dex"
find "$ROOT/zygisk/java" -name '*.java' > "$OUT/sources.txt"
# Plain string concatenation: no invokedynamic for d8 to rewrite.
javac -source 17 -target 17 -Xlint:-options -XDstringConcat=inline -encoding UTF-8 \
  -classpath "$PLATFORM_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
jar --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$BUILD_TOOLS/d8" --release --min-api 29 --lib "$PLATFORM_JAR" --output "$OUT/dex" "$OUT/classes.jar"

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

echo "== module zip"
STAGE=$OUT/module
cp -r "$ROOT/zygisk/module" "$STAGE"
mkdir -p "$STAGE/zygisk"
cp "$LIB" "$STAGE/zygisk/$ABI.so"
sed -i "s/@VERSION@/$VERSION/; s/@VERSION_CODE@/$VERSION_CODE/" "$STAGE/module.prop"
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

ZIP=$OUT/fcmfix-coloros-zygisk-$VERSION.zip
(cd "$STAGE" && zip -qr9 "$ZIP" .)
(cd "$OUT" && sha256sum "$(basename "$ZIP")" > SHA256SUMS)
unzip -l "$ZIP"
cat "$OUT/SHA256SUMS"
