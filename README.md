# Stratagem Caller for Android

Stratagem Caller is a native Android macro caller for Helldivers 2. It provides an offline bundled catalog, fixed 12-position mission layout, loadout configuration, macro sequencing, Bluetooth capability detection, and a documented USB HID bridge protocol.

## Status

This repository contains the implementation and a buildable Android Studio project. Bluetooth HID and USB hardware interfaces require a compatible Pixel/device and physical controller bridge for end-to-end verification. In-game execution is not claimed unless tested with Helldivers 2.

## Features

- Fixed 4x3 mission grid with permanent Resupply and Reinforce slots
- Exactly four personal selections and up to six mission selections
- Bundled Room database seeded from an offline catalog asset
- Search and category filtering
- Macro engine with held LB behavior, cancellation, and release-all
- Bluetooth HID capability detection
- USB bridge protocol for compatible external HID hardware
- Settings and diagnostic surfaces

## Build

Requirements: JDK 17 and Android Studio / Android SDK Platform 35.

```bat
gradlew.bat assembleDebug
gradlew.bat assembleRelease
gradlew.bat testDebugUnitTest
```

## Install

Enable Install unknown apps for the APK source, then install:

```bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unsigned release builds require a signing key. Create and store a release keystore locally; never commit it or signing passwords.

## Bluetooth setup

1. Keep the app in the foreground.
2. Grant Bluetooth permissions.
3. Pair Windows with the phone only after Bluetooth HID support is confirmed on the device.
4. Use the input test before entering gameplay.
5. Android/HID support varies by device and OEM.

## USB bridge

A phone cannot directly act as a universal USB game controller. Use an Android OTG-capable phone connected to a compatible HID bridge (ESP32-S3 or Raspberry Pi with HID firmware), then connect the bridge to Windows.

Protocol line format:

- `SC1:H;` handshake
- `SC1:I;` controller identification
- `SC1:D:<U|D|L|R>:H;` hold direction
- `SC1:D:<U|D|L|R>:L;` release direction
- `SC1:C;` cancel
- `SC1:R;` release all
- `SC1:HBT;` heartbeat

## Database

The catalog is maintained in `app/src/main/assets/stratagems/catalog.json` and imported into Room. Entries include source and verification metadata. Community, unreleased, and fiction entries are not intentionally included; entries marked `verify` require a live catalog cross-check.

## GitHub release

GitHub Actions builds debug/release APK artifacts. Configure repository release permissions and upload generated APK files to a GitHub Release. Use Android App Bundle signing or a locally maintained release keystore for updates.

## License

MIT. No copyrighted Helldivers 2 artwork is bundled.
