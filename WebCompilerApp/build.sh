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
    echo "For CI builds (GitHub Actions), template.apk is built and bundled automatically."
    echo "For local builds, place template.apk in app/src/main/assets/ before building."
else
    echo "=== BUILD FAILED ==="
fi
