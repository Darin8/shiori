# OCR engine

Status: pending measurement. Both engines are built and compile into the app; real device frames are needed (run `scripts/bench.ps1`).

Rule: lowest mean CER among engines with p90 under 1000 ms per full frame. If both CERs are above 0.10, consider a cloud OCR engine such as Google Lens.

| Engine | Mean CER | Worst 3 frames | Median ms | p90 ms | Furigana merged |
|---|---|---|---|---|---|
| ML Kit Japanese 16.0.1 | | | | | |
| MeikiOCR (det 960x544 + rec 960x32) | | | | | |

Procedure: play the three games, press the hotkey on 15-20 screens (dialogue, menus, item names, furigana, vertical text), run `scripts/bench.ps1` once to pull the frames, fill `bench/truth.json` as `{ "file.png": ["line 1", "line 2"] }` (name the furigana frame with "furigana" in the file name), run it again.
