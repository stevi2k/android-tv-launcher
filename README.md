# android-tv-launcher

A clean, Apple TV-style home screen for Android TV and Google TV, with a
one-install setup wizard, a phone remote, per-user docks and hidden apps,
debloat, and backups.

**Based on [kulwantnagi/android-tv-makeover](https://github.com/kulwantnagi/android-tv-makeover), MIT licensed (Copyright 2026 Kulwant Nagi).** The original project is credited above and in LICENSE. This version is a clean rewrite of its home screen: no preset users, a first-launch wizard that runs on the TV itself, and a Settings screen that covers users, docks, hidden apps, debloat, backups, and phone pairing.

## What is in this repo

- `java/` - the launcher source (package `com.kulwant.atvhome`)
- `res/` - layouts, drawables (including exact Material Design Icon paths),
  and values
- `assets/` - the phone remote and first-launch wizard web pages served by
  the TV on its local network
- `AndroidManifest.xml`

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
