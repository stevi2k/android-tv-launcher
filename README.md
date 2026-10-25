# android-tv-launcher

A clean, Apple TV-style home screen for Android TV and Google TV, with a
one-install setup wizard, a phone remote, per-user docks and hidden apps,
debloat, backups, and in-app updates.

**Based on [kulwantnagi/android-tv-makeover](https://github.com/kulwantnagi/android-tv-makeover), MIT licensed (Copyright 2026 Kulwant Nagi).** The original project is credited above and in LICENSE. This version is a clean rewrite of its home screen: no preset users, a first-launch wizard that runs on the TV itself, and a Settings screen that covers users, docks, hidden apps, debloat, backups, phone pairing, and updates.

## Version numbers

26 is the year, the second number is a major change, and the third (when
present) is a small fix. So 26.1 is the first release of 2026, a fix would
be 26.1.1 or 26.1.2, and the next major release would be 26.2. The numeric
versionCode packs them as year * 10000 + major * 100 + fix, so 26.1 is
260100 and 26.1.2 is 260102.

## Updates

Settings > Updates checks `update.json` on this repo's main branch, compares
its versionCode (falling back to the version name) with the installed one,
and shows the changelog. Nothing installs on its own: the user chooses
Update, Skip this version, or Later. An update is handed to the system
installer, so the TV may ask to allow "Install unknown apps" for the
launcher the first time. Profiles, docks, and hidden lists survive, because
the install keeps the app's data. Settings also auto-checks at most once a
day, silently unless an update is waiting.

## What is in this repo

- `java/` - the launcher source (package `com.kulwant.atvhome`)
- `res/` - layouts, drawables (including exact Material Design Icon paths),
  and values
- `assets/` - the phone remote and first-launch wizard web pages served by
  the TV on its local network
- `AndroidManifest.xml` - version 26.1, versionCode 260100
- `update.json` - the version the TV checks against

Two binary files are not in this push: the bundled wallpaper
(`res/drawable-nodpi/wallpaper_1.jpg`) and the Outfit variable font
(`assets/fonts/Outfit.ttf`). The launcher handles both missing files
gracefully: no bundled wallpaper is seeded, and the font falls back to the
system sans-serif. Add your own files at those paths to get the full look.

## Building

This snapshot is built with a manual aapt2 / javac / d8 pipeline, not
Gradle: compile the Java sources against the Android SDK and the
androidx.core / recyclerview classes, package `res/` and `assets/` with
aapt2, then align and sign the APK. The installed build is signed with a
debug key, and install with `adb install -r` keeps the launcher's data.

## Current release

**26.1** - first numbered release. Settings > Updates, Back navigation in
Settings that keeps your place, phone names at pairing (shown in Unpair
phones, or Unknown when no name was set), and hiding an app also removing
it from that user's dock.
