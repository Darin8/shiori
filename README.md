# Shiori

Shiori reads Japanese text in games running on an Android handheld and lets you look words up and mine them into Anki, without pausing the game.

It captures the screen, runs OCR, and lays invisible tap targets over the recognized words. Tap a word to open a Yomitan-style dictionary popup over the game, then add a card to AnkiDroid with the game frame as the picture.

Built for and tested on the Retroid Pocket Nova (1280x960).

## Features

- Hotkey mode: press a controller button to read the screen once.
- Live mode: reads your chosen screen regions every few seconds.
- Per-game OCR regions you can draw, move and resize.
- Yomitan dictionaries (term and frequency), imported from zip files.
- Cards for the JP Mining Note type, sent to AnkiDroid.
- OCR with Meiki OCR (ONNX, on device), falling back to ML Kit.

## Requirements

- Android 13 or newer, arm64.
- AnkiDroid with the JP Mining Note type, for card export.
- Yomitan dictionary zips, for example Jitendex and a frequency list.

## Setup on the device

1. Install the APK and open Shiori.
2. Grant the accessibility service (it catches the hotkey and shows the overlay) and allow screen capture when asked.
3. Import your dictionaries, pick an OCR mode and a hotkey.
4. Start a session, open your game and press the hotkey.

## Building

Needs JDK 17 (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The OCR models are committed under `app/src/main/assets/meiki`; `scripts/fetch-models.ps1` restores or updates them. `scripts/install.ps1` builds, installs over the existing app and opens it.

## License

GPL-3.0. See [LICENSE](LICENSE) and [docs/third-party.md](docs/third-party.md) for bundled fonts, models and data.
