# Android WebView App Wrapper

Turn an existing website into a simple Android app icon.

This project is a small native Android `WebView` wrapper for dashboards, chats, admin panels, self-hosted tools, and other web apps that you want to open like a normal Android app without rebuilding the UI in native code.

## What This Repo Is

- A minimal Android app template built with Kotlin
- One fixed website per build
- Config-driven app name, package name, URL, icon, and refresh behavior
- Useful for personal tools, private services, internal dashboards, or lightweight public web wrappers

## What It Does

- Opens a configured URL on launch
- Keeps a full-screen app-like shell around the site
- Supports JavaScript, DOM storage, cookies, and third-party cookies
- Handles loading, network error, timeout, and SSL failure states
- Maps Android back button to web history when possible
- Lets you enable or disable native pull-to-refresh per app config
- Can hide many common bottom app-promo banners and open-in-app popups

## Quick Start

1. Copy the default config.
2. Change the app name, package name, and target URL.
3. Build the APK.

```bash
mkdir -p configs
cp app-config.properties configs/my-app.properties
./gradlew assembleDebug -PappConfigFile=configs/my-app.properties
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK output:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

If you want an installable release APK, sign it after building.

## Build Configuration

The default config file is `app-config.properties`:

```properties
appName=WebWrapper
applicationId=com.example.webwrapper
baseUrl=https://your-app.example.com/
launcherForegroundResource=ic_foreground_default
launcherBackgroundResource=ic_background_default
enablePullToRefresh=true
enablePromoPopupBlocking=true
```

Field meanings:

- `appName`: Launcher label shown on the phone
- `applicationId`: Android package name
- `baseUrl`: Fixed website loaded by the app
- `launcherForegroundResource`: Foreground icon resource
- `launcherBackgroundResource`: Background icon resource
- `enablePullToRefresh`: Whether native swipe-to-refresh is enabled
- `enablePromoPopupBlocking`: Hide common bottom promo bars and open-in-app popups inside the wrapped site

The Android launcher label comes from `appName` in the properties file, not from `app/src/main/res/values/strings.xml`.

Important:

- Use a real reachable URL, not `localhost`, `127.0.0.1`, or `0.0.0.0`
- Each app config should use a unique `applicationId`

## Example Use Cases

- Wrap a self-hosted chat app
- Wrap a trading dashboard
- Wrap an internal admin panel
- Wrap a monitoring page
- Wrap a private AI frontend

## Current Tech Stack

- Android Gradle Plugin `9.1.0`
- Gradle `9.3.1`
- Kotlin via AGP built-in support
- compile SDK `36`
- min SDK `26`
- target SDK `36`

## Notes

- This is intentionally a thin wrapper, not a full native client
- Websites with complex nested scrolling may prefer `enablePullToRefresh=false`
- Authentication flows depend on how well the target site works inside Android `WebView`
- Third-party asset attributions are listed in `THIRD_PARTY_ASSETS.md`
