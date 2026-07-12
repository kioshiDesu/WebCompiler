#!/bin/bash
# Build the blank WebView template APK (run on desktop with Android SDK)
# Requires: Android SDK, JAVA_HOME set

echo "Building blank WebView APK template..."

# If gradle wrapper doesn't exist, generate it
if [ ! -f "gradlew" ]; then
    which gradle > /dev/null 2>&1 || { echo "Install Gradle or Android Studio first"; exit 1; }
    gradle wrapper --gradle-version 8.5
fi

chmod +x gradlew

# Build release APK
./gradlew assembleRelease

echo ""
echo "Done! APK at: app/build/outputs/apk/release/app-release.apk"
echo ""
echo "Next steps for phone-based compilation:"
echo "1. Copy app-release.apk to your phone"
echo "2. Use APKTool M or MT Manager to decompile: apktool d app-release.apk"
echo "3. Replace assets/index.html with your web app's HTML/CSS/JS"
echo "4. Edit AndroidManifest.xml to add permissions"
echo "5. Recompile: apktool b app-release -o output.apk"
echo "6. Sign with uber-apk-signer or MT Manager"
