#!/usr/bin/env bash
# Construit l'APK Android (structure à plat : tous les fichiers à la racine du dépôt).
set -e
mkdir -p www
cp index.html www/index.html
npm install
[ -d android ] || npx cap add android
JAVA_DIR=android/app/src/main/java/com/caisse/app
mkdir -p "$JAVA_DIR"
cp NetworkPrinterPlugin.java MainActivity.java "$JAVA_DIR/"
node patch-manifest.js
npx cap sync android
cd android
chmod +x gradlew
./gradlew assembleDebug
echo "APK prêt : android/app/build/outputs/apk/debug/app-debug.apk"
