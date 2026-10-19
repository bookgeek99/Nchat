#!/bin/bash
# Nchat daemon 快速构建脚本
# 用法: ./build.sh   → 产出 build/nchatd.jar 并推送到 /sdcard/Develop/nchat/
cd "$(dirname "$0")" || exit 1
SDK=/root/android-sdk
TOOLS=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar

mkdir -p build/classes build/dex
echo "[1/4] javac"
javac -source 17 -target 17 -nowarn -cp "$JAR" -d build/classes daemon-src/*.java 2>&1 | grep -E "error|错误" && echo "COMPILE FAIL" && exit 1
echo "[2/4] d8"
rm -f build/dex/classes.dex build/nchatd.jar
CLASSES=$(find build/classes -name '*.class' | tr '\n' ' ')
$TOOLS/d8 --min-api 34 --lib "$JAR" --output build/dex $CLASSES 2>&1 | tail -3
echo "[3/4] package"
cd build/dex && jar cf ../nchatd.jar classes.dex && cd ..
echo "[4/4] push to /sdcard/Develop/nchat/"
cp build/nchatd.jar /sdcard/Develop/nchat/nchatd.jar && echo "OK -> build/nchatd.jar"
