# Tails the Nova's logcat for Shiori cost lines (one per OCR pass, plus a summary every 30 s in Live mode).
# Usage: .\scripts\cost.ps1            (Ctrl+C to stop)
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb logcat -c
& $adb logcat -s Shiori:I | Select-String -SimpleMatch "cost "
