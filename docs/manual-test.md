# Manual test on the Nova

Fill the Result column with pass or fail (and a note) after each run.
The game is never paused and no screenshot is shown; OCR boxes are an invisible layer over the live game.

| Check | Result |
|---|---|
| Setup: OCR mode picker shows Hotkey and Live; the "Read every" picker (1, 2, 3, 5 s, default 3) shows only in Live | |
| Setup: Session row shows Start; after consent it shows Stop and a Stop action is on the notification | |
| Hotkey mode, Star Ocean dialogue: press the hotkey, tap a word, popup shows over the running game, highlight sits on the tapped word | |
| Highlight and tap boxes sit on the words, not to the right of them (Twilight Princess dialogue, both lines, furigana line) | |
| Gamepad still reaches the game while the layer is up (move the character, no popup open) | |
| With a popup open: A, B and the d-pad act on the popup; B closes it | |
| Tap outside the popup: popup and highlight close | |
| Tap where there is no word: nothing happens | |
| Hotkey pressed while a popup is open: only the popup closes | |
| Hotkey again (hotkey mode): the layer refreshes with the new text | |
| Menu item name: tap, popup shows | |
| Frame with no text: press the hotkey, taps do nothing | |
| Card added to AnkiDroid from the popup; the picture is the game frame | |
| Live mode: the layer follows the dialogue every N s inside the OCR regions (Start says "Set a region for Live" without one); the hotkey reads the whole screen once | |
| Live mode with a popup open: the highlight does not jump while it is open | |
| Live mode, scripts\cost.ps1: a cost line per pass and a summary every 30 s (capture, OCR, total ms, CPU, battery) | |
| Live mode for 10 minutes at 3 s: note the CPU %, battery % drop and game FPS (RetroArch FPS overlay) | |
| Stop in the setup screen: layer gone, notification gone, hotkey asks for consent again | |
| Stop on the notification: same | |
| Reboot the Nova, then Start: consent prompt, then the hotkey works | |
| Dusklight frame: tap layer works and nothing is paused | |
| 30 minutes of play, session stopped: no frame drops vs. Shiori disabled (RetroArch FPS overlay) | |
