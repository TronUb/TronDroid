#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Downloading EasyTDLib source bindings and native Android libraries..."
curl -fL --retry 3 "https://github.com/kcvabeysinghe/EasyTDlib/archive/refs/heads/main.zip" -o "$TMP/easytdlib.zip"
unzip -q "$TMP/easytdlib.zip" -d "$TMP"
SRC="$TMP/EasyTDlib-main"
mkdir -p "$ROOT/app/src/main/java/org/drinkless" "$ROOT/app/src/main/jniLibs" "$ROOT/third_party/licenses" "$ROOT/gradle/wrapper"
rm -rf "$ROOT/app/src/main/java/org/drinkless/tdlib" "$ROOT/app/src/main/jniLibs"
cp -R "$SRC/app/src/main/java/org/drinkless/tdlib" "$ROOT/app/src/main/java/org/drinkless/"
cp -R "$SRC/app/src/main/jniLibs/." "$ROOT/app/src/main/jniLibs/"
cp "$SRC/THIRD_PARTY_LICENSES.txt" "$ROOT/third_party/licenses/EasyTDlib-THIRD_PARTY_LICENSES.txt"
cp "$SRC/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/gradle-wrapper.jar"
cp "$SRC/gradlew" "$ROOT/gradlew"
cp "$SRC/gradlew.bat" "$ROOT/gradlew.bat"
chmod +x "$ROOT/gradlew"
echo "EasyTDLib sources, JNI libraries and wrapper ready."
