#!/bin/bash
# OpenClaw Android 构建脚本（不依赖 Gradle，纯 build-tools 流水线）
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROJ="$ROOT"
TC="$(cd "$ROOT/.." && pwd)/.toolchain"
SDK="$TC/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA_HOME="$TC/jdk"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAVA="$JAVA_HOME/bin/java.exe"
APP="$ROOT/app"
OUT="$ROOT/build"
KS="$ROOT/keystore/openclaw.jks"
KS_PASS="openclaw2024"

echo "==> 清理"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/6 编译资源 (aapt2 compile)"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "==> 2/6 链接资源 (aapt2 link)"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 3 \
  --version-name "1.1.1" \
  --auto-add-overlay

echo "==> 3/6 编译 Java (javac)"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
sed -i -e 's|^/\([a-zA-Z]\)/|\U\1:/|' "$OUT/sources.txt"
ZXING="$TC/dl/zxing.jar"
winpath() { echo "$1" | sed -e 's|^/\([a-zA-Z]\)/|\U\1:/|'; }
CP="$(winpath "$PLATFORM")"
if [ -f "$ZXING" ]; then CP="$CP;$(winpath "$ZXING")"; fi
"$JAVAC" -encoding UTF-8 -source 11 -target 11 -nowarn \
  -classpath "$CP" \
  -d "$(winpath "$OUT")/classes" \
  @"$OUT/sources.txt"

echo "==> 4/6 dex + 裁剪 (R8)"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
sed -i -e 's|^/\([a-zA-Z]\)/|\U\1:/|' "$OUT/classlist.txt"
if [ -f "$ZXING" ]; then
  "$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
    --release \
    --min-api 29 \
    --lib "$PLATFORM" \
    --pg-conf "$PROJ/proguard-rules.pro" \
    --output "$OUT/dex" \
    $(cat "$OUT/classlist.txt") "$ZXING"
else
  "$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 29 --lib "$PLATFORM" --output "$OUT/dex" \
    $(cat "$OUT/classlist.txt")
fi

echo "==> 5/6 打包 + 对齐"
python - "$OUT" <<'PY'
import sys, os, zipfile, shutil
out = sys.argv[1]
base = os.path.join(out, 'base.apk')
dex = os.path.join(out, 'dex', 'classes.dex')
tmp = os.path.join(out, 'unsigned.apk')
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, 'classes.dex')
print('packed', os.path.getsize(tmp), 'bytes')
PY

"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名 (apksigner)"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  "$JAVA_HOME/bin/keytool.exe" -genkeypair -v \
    -keystore "$KS" -alias openclaw -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=OpenClaw, OU=Android, O=OpenClaw, L=Beijing, ST=Beijing, C=CN" >/dev/null 2>&1
  echo "    已生成签名证书: $KS"
fi

"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/OpenClaw.apk" "$OUT/aligned.apk"

echo "==> 校验"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --verbose "$OUT/OpenClaw.apk" | head -6
"$BT/aapt2.exe" dump badging "$OUT/OpenClaw.apk" | head -4
ls -lh "$OUT/OpenClaw.apk"
echo "==> 完成: $OUT/OpenClaw.apk"
