# Coool TV Lite

A lightweight, fast Android TV client for [coool.tv](https://www.coool.tv), built for old and
underpowered TV boxes. Kotlin + Jetpack Compose, ~2 MB APK.

- Live TV (including 4K HEVC 10-bit), catch-up / timeshift, programme guide
- Favourites, categories, profiles with PIN, audio/subtitle selection
- Bulgarian and English, selectable main colour

## Install on a TV

1. Download the latest `coool-tv-lite-*.apk` from [Releases](../../releases)
   (or from the artifacts of a recent [Actions](../../actions) run).
2. Sideload it, for example with `adb install coool-tv-lite-X.Y.Z.apk`, or with a downloader app on the TV.
3. Open **Coool TV Lite** and pair it at coool.tv/device/add with the code shown on screen.

## Build

```bash
./gradlew :app:assembleDebug     # dev build: dev.bettercoool.tv.debug
./gradlew :app:assembleRelease   # release: needs the signing key, see docs/RELEASE.md
```

Pushing a `v*` tag (e.g. `v0.2.0`) makes GitHub Actions build a signed APK and publish a release.
