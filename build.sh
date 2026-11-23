#!/usr/bin/env bash
# Builds Cube Home and its screensaver without Gradle.
# Output: build/cubehome.apk and build/screensaver.apk (same version, same signing key).
#
# The screensaver is a separate app on purpose: when the TV wakes from a screensaver, Fire OS
# force-stops the app that provided it. Kept apart, that never stops Cube Home's Home button.
set -euo pipefail
cd "$(dirname "$0")"
DEV="$HOME/android-dev"
export JAVA_HOME="$DEV/jdk" PATH="$DEV/jdk/bin:$PATH"
BT="$DEV/sdk/build-tools/34.0.0"
JAR="$DEV/sdk/platforms/android-30/android.jar"
VERSION=$(cat VERSION)   # one number: versionCode N, versionName "N", release tag "vN"
KS="$DEV/cubehome.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias cubehome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Cube Home" >/dev/null 2>&1
rm -rf build && mkdir -p build

# apk <name> <manifest> <source dir> <resource files or dirs...>
apk() {
  local name=$1 manifest=$2 src=$3; shift 3
  local w="build/$name"
  mkdir -p "$w/res" "$w/gen" "$w/obj" "$w/dex"
  for r in "$@"; do
    if [ -d "$r" ]; then "$BT/aapt2" compile --dir "$r" -o "$w/res/res.zip"
    else "$BT/aapt2" compile "$r" -o "$w/res/"; fi
  done
  "$BT/aapt2" link -o "$w/unsigned.apk" -I "$JAR" --manifest "$manifest" \
    --java "$w/gen" --min-sdk-version 22 --target-sdk-version 28 \
    --version-code "$VERSION" --version-name "$VERSION" "$w"/res/*
  javac -nowarn --release 8 -classpath "$JAR" -d "$w/obj" $(find "$src" "$w/gen" -name '*.java')
  "$BT/d8" --release --lib "$JAR" --min-api 22 --output "$w/dex" $(find "$w/obj" -name '*.class')
  python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" \
    "$w/unsigned.apk" "$w/dex/classes.dex"
  "$BT/zipalign" -f 4 "$w/unsigned.apk" "$w/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "build/$name.apk" "$w/aligned.apk"
  echo "Built: $(pwd)/build/$name.apk"
}

apk cubehome AndroidManifest.xml src res
apk screensaver screensaver/AndroidManifest.xml screensaver/src \
  res/raw/amazon_aerials.txt res/drawable/ic_launcher.xml screensaver/res
