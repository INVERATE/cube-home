#!/usr/bin/env bash
# Builds Cube Home without Gradle. Output: build/cubehome.apk
set -euo pipefail
cd "$(dirname "$0")"
DEV="$HOME/android-dev"
export JAVA_HOME="$DEV/jdk" PATH="$DEV/jdk/bin:$PATH"
BT="$DEV/sdk/build-tools/34.0.0"
JAR="$DEV/sdk/platforms/android-30/android.jar"
rm -rf build && mkdir -p build/gen build/obj build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
VERSION=$(cat VERSION)   # one number: versionCode N, versionName "N", release tag "vN"
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 22 --target-sdk-version 28 \
  --version-code "$VERSION" --version-name "$VERSION" build/res.zip
javac -nowarn --release 8 -classpath "$JAR" -d build/obj \
  $(find src build/gen -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 22 --output build/dex $(find build/obj -name '*.class')
python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" build/unsigned.apk build/dex/classes.dex
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
KS="$DEV/cubehome.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias cubehome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Cube Home" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out build/cubehome.apk build/aligned.apk
echo "Built: $(pwd)/build/cubehome.apk"
