# Third-party components

| Component | Use | Licence |
|---|---|---|
| MeikiOCR (github.com/rtr46/meikiocr, commit 52aa607) | Pipeline logic ported to Kotlin in `ocr/MeikiOcrEngine.kt` | Apache-2.0 |
| Meiki OCR ONNX models: meiki.text.detect.v0, meiki.txt.recognition.v0 (Hugging Face rtr46) | Bundled in `app/src/main/assets/meiki/` | LGPL-3.0 per the model cards |
| ONNX Runtime Android 1.22.0 | Runs the Meiki models | MIT |
| ML Kit text-recognition-japanese 16.0.1 | ML Kit OCR engine | ML Kit terms |
| IBM Plex Sans JP | Bundled UI fonts | SIL Open Font License 1.1 |
| Yomitan japanese-transforms data (github.com/yomidevs/yomitan, commit ee81f8dcf13e985bb4d7961009fb49a764b4d924) | `ext/js/language/ja/japanese-transforms.js` converted to `app/src/main/assets/japanese-transforms.json` by `scripts/gen-transforms.mjs`; `language-transformer.js` logic ported to `dict/Deinflector.kt` | GPL-3.0 (repo `LICENSE`) |

Model files and SHA-256:

- `meiki.text.detect.v0.1.960x544.onnx` 40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b
- `meiki.text.rec.v0.960x32.onnx` 3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000
- `meiki.text.rec.v0.vertical.32x480.onnx` 2c2a83a23bc3b7e6c63962175f507ecc6c5e85cc174f17bdec37d9bbd0bf895a

Total about 46 MB, committed to git (each under 20 MB). `scripts/fetch-models.ps1` re-downloads them.
The LGPL models are unmodified separate files in the app assets. If the app is distributed with Meiki as the
shipped engine, include the licence text and source links.
