#!/bin/bash
# Build the WebCompiler app (the tool itself)
# Requires: Android SDK, JAVA_HOME set

echo "=== Building WebCompiler App ==="

# Create gradle wrapper if missing
if [ ! -f "gradlew" ]; then
    which gradle > /dev/null 2>&1 || { echo "Error: Install Gradle or Android Studio first"; exit 1; }
    gradle wrapper --gradle-version 8.5
fi

chmod +x gradlew

# Build
./gradlew assembleRelease

echo ""
if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
    echo "=== SUCCESS ==="
    echo "APK: app/build/outputs/apk/release/app-release.apk"
    echo ""
    echo "NOTE: For local builds, place these files in app/src/main/assets/"
    echo "before building (or the app will prompt on first run):"
    echo "  - apktool.jar  (download from GitHub)"
    echo "  - template.apk (build from ../WebViewTemplate/)"
    echo ""
    echo "For CI builds (GitHub Actions), assets are bundled automatically."
else
    echo "=== BUILD FAILED ==="
fi
