# WebCompiler — Complete Plan

Turn any web app (HTML/CSS/JS) into a native Android APK, entirely on your phone.

---

## Architecture

```
User's Web Code (HTML/CSS/JS)
          │
          ▼
WebCompiler App (Android tool)
  ├── apktool.jar ← bundled inside APK (downloaded in CI)
  ├── template.apk ← bundled inside APK (built in CI from WebViewTemplate/)
  │
  ├── [0] Extract bundled assets from APK (first run only)
  ├── [1] apktool d template.apk -o decoded/
  ├── [2] Replace decoded/assets/index.html with user's code
  ├── [3] Edit decoded/AndroidManifest.xml (permissions, app name)
  ├── [4] apktool b decoded/ -o output.apk
  └── [5] Sign APK with debug keystore
         │
         ▼
     Final APK → Ready to install/sideload
```

---

## Project Structure

```
WebCompiler/
├── PLAN.md                          ← This file
├── .github/workflows/build.yml      ← CI: builds template + app together
├── .gitignore
│
├── WebViewTemplate/                 ← Blank WebView APK source
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/webview/blank/MainActivity.kt
│   │       └── assets/index.html    ← SWAP with user's web code
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── build.sh
│
└── WebCompilerApp/                  ← The tool app (build ONCE)
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/com/webcompiler/app/
    │       │   ├── MainActivity.kt  ← UI
    │       │   └── CompilerEngine.kt ← apktool pipeline
    │       ├── res/layout/activity_main.xml
    │       ├── res/values/
    │       └── assets/              ← template.apk + apktool.jar (bundled by CI)
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── build.sh
```

---

## Step 1: Build the Blank WebView Template

The `WebViewTemplate` is a minimal Android app with:
- **Full-screen WebView** — no title bar, no action bar, no status bar, no nav bar
- **Immersive mode** — content is truly edge-to-edge
- **Back button** — navigates WebView history, doesn't exit app
- **Loads from** `assets/index.html`

### Build

This is now done automatically by **GitHub Actions CI** when you push to `main`.
The CI workflow builds `WebViewTemplate`, downloads `apktool.jar`, and bundles
both inside `WebCompilerApp` before building the final APK.

To build manually (desktop development):
```bash
cd WebViewTemplate
bash build.sh
```
Output: `app/build/outputs/apk/release/app-release.apk`

---

## Step 2: Build the WebCompiler App (the tool)

The `WebCompilerApp` is the Android tool that automates APK compilation.

### Features

- **Code input** — multi-line editor OR file picker for HTML
- **Permission toggles** — Camera, Location, Notifications, Storage (Chip UI)
- **App metadata** — custom app name + package name
- **Build pipeline** — decompile → swap HTML → edit manifest → recompile → sign
- **Build log** — real-time output in a terminal-style view
- **Save APK** — outputs to `Downloads/` folder

### Build options

#### Option A: GitHub Actions (recommended — push to main)

```yaml
name: Build WebCompiler  # Already at .github/workflows/build.yml
```

Push → CI builds WebViewTemplate, downloads apktool.jar, bundles both into WebCompilerApp, then builds the final APK → Download from Actions tab.

#### Option B: Desktop (Android Studio)
```bash
cd WebCompilerApp
bash build.sh
```
Note: For desktop builds, you must manually copy `template.apk` and `apktool.jar` to `WebCompilerApp/app/src/main/assets/` before building, or the app will prompt you to place them on first run.

#### Option C: Termux (on phone, slow)
```bash
pkg install gradle android-sdk
export ANDROID_HOME=$PREFIX/share/android-sdk
cd WebCompilerApp && bash build.sh
```

---

## Step 3: Setup on Phone

**No manual setup required.** Both `template.apk` and `apktool.jar` are bundled inside the WebCompiler APK by CI.

On first launch, `CompilerEngine.extractBundledAssets()` automatically extracts them from the APK's assets to the app's files directory. Subsequent builds reuse them.

If you built manually (desktop/development), place these files in `Android/data/com.webcompiler.app/files/`:

| File | Source |
|------|--------|
| `apktool.jar` | Download from [bitbucket.org/iBotPeaches/apktool/downloads](https://bitbucket.org/iBotPeaches/apktool/downloads) |
| `template.apk` | Built from `WebViewTemplate/` |

---

## Step 4: Compile Web Apps (daily use)

1. Open WebCompiler on phone
2. Enter app name + package name
3. Toggle desired permissions (Camera, Location, Notifications, Storage)
4. Paste HTML code or tap "Select File" to pick a `.html`/`.zip`
5. Tap **Build APK**
6. Wait ~30-60 seconds (apktool is fast on phones)
7. Tap **Save APK** — it lands in `Downloads/YourApp.apk`

---

## How apktool Replaces Gradle

| Tool | Size | Build Time | Works on Phone |
|------|------|-----------|----------------|
| Gradle + SDK | ~2GB | 10-20 min | ❌ Painful |
| apktool | ~5MB | 5-30 sec | ✅ Yes |

apktool works with **already-compiled DEX bytecode** — it just decompiles to smali, lets you swap assets/manifest, and reassembles. No Java compilation needed. This is the same engine used by MT Manager and Apktool M.

---

## Permissions Map

| Toggle | Android Permission | When Needed |
|--------|-------------------|-------------|
| Camera | `android.permission.CAMERA` | Photo/video capture |
| Location | `android.permission.ACCESS_FINE_LOCATION` | GPS/maps |
| Notifications | `android.permission.POST_NOTIFICATIONS` | Android 13+ alerts |
| Storage | `android.permission.READ_EXTERNAL_STORAGE` | File access |

Note: The template APK already declares `INTERNET` permission (required for WebView).

---

## WebViewTemplate Details

`MainActivity.kt` key behaviors:

```kotlin
// Immersive full-screen (hides status + nav bars)
hideSystemUI()

// Pure WebView, no Android chrome whatsoever
setContentView(webView)

// Back button navigates web history
override fun onKeyDown(keyCode, event)

// Re-hides system UI if user swipes to reveal
setOnSystemUiVisibilityChangeListener { hideSystemUI() }
```

---

## Extending

- **Add more permissions** — Add new `Chip` to `activity_main.xml`, map in `CompilerEngine.kt`
- **JavaScript bridge** — Add `@JavascriptInterface` methods in `MainActivity.kt` for native API access
- **Custom splash screen** — Add a `splash.xml` drawable in the template
- **Deep linking** — Add `<intent-filter>` to template's `AndroidManifest.xml`
- **Multiple file support** — Extend WebCompiler to accept `.zip` of entire site assets

---

## Summary

```
Web code (HTML/CSS/JS)
    │
    ▼
[WebCompiler app on phone]
    │
    ├── apktool decompile (5 sec)
    ├── inject code + permissions (instant)
    ├── apktool rebuild (10 sec)
    └── sign (2 sec)
    │
    ▼
Native APK ready to install
    │
    ├── Sideload directly
    ├── Share via file manager
    └── Or publish on Play Store (needs keystore)
```

## Files Created

| File | Purpose |
|------|---------|
| `.github/workflows/build.yml` | CI workflow: builds template, bundles assets, builds app |
| `.gitignore` | Ignore build artifacts |
| `WebViewTemplate/app/src/main/java/com/webview/blank/MainActivity.kt` | Full-screen WebView activity |
| `WebViewTemplate/app/src/main/AndroidManifest.xml` | Template manifest (INTERNET only) |
| `WebViewTemplate/app/src/main/assets/index.html` | Placeholder (swap with your app) |
| `WebCompilerApp/app/src/main/java/com/webcompiler/app/MainActivity.kt` | Tool UI |
| `WebCompilerApp/app/src/main/java/com/webcompiler/app/CompilerEngine.kt` | apktool pipeline + asset extraction |
| `WebCompilerApp/app/src/main/res/layout/activity_main.xml` | Tool layout |
| `WebCompilerApp/app/src/main/AndroidManifest.xml` | Tool manifest |
| `WebCompilerApp/app/src/main/assets/` | Template + apktool (bundled by CI) |

---

**CI build**: Push `main` → GitHub Actions builds everything, bundles assets, and produces the final APK as a downloadable artifact. No desktop needed. After installing, all Web→APK compilation happens on the phone.
