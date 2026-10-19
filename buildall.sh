#!/bin/bash
# 完整构建：daemon jar → 拷进 assets → 编译 APK
cd "$(dirname "$0")" || exit 1
SDK=/root/android-sdk
TOOLS=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar

echo "===== [1] 构建 daemon ====="
mkdir -p build/classes build/dex
javac -source 17 -target 17 -nowarn -cp "$JAR" -d build/classes daemon-src/*.java 2>&1 | grep -E "error" && { echo "DAEMON COMPILE FAIL"; exit 1; }
rm -f build/dex/classes.dex build/nchatd.jar
CLASSES=$(find build/classes -name '*.class' | tr '\n' ' ')
$TOOLS/d8 --min-api 34 --lib "$JAR" --output build/dex $CLASSES 2>&1 | grep -v "^$" | tail -3
(cd build/dex && jar cf ../nchatd.jar classes.dex)
echo "daemon jar: $(stat -c%s build/nchatd.jar) bytes"

echo "===== [2] 拷进 assets ====="
mkdir -p android/app/src/main/assets
cp build/nchatd.jar android/app/src/main/assets/nchatd.jar

echo "===== [3] 构建 APK ====="
export PATH=/opt/gradle/gradle-8.1/bin:$PATH
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
cd android
gradle assembleDebug 2>&1 | grep -E "error:|BUILD|FAILED" | head -20

APK=app/build/outputs/apk/debug/Nchat-1.0.apk
if [ -f "$APK" ]; then
  echo "===== [4] 推送到 Download ====="
  cp "$APK" /sdcard/Download/Nchat-1.0.apk 2>/dev/null && echo "OK -> /sdcard/Download/Nchat-1.0.apk ($(stat -c%s "$APK") bytes)" || echo "复制到 /sdcard 失败（可选）"
fi