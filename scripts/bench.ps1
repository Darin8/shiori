# Pulls hotkey frames from the Nova, pushes bench/frames + bench/truth.json back, runs the OCR benchmark.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
$dev = "/sdcard/Android/data/app.retromine/files"

Push-Location $root
try {
    & $adb devices
    # Frames saved by the hotkey debug path (HotkeyService, no listener set)
    New-Item -ItemType Directory -Force "bench\frames" | Out-Null
    & $adb pull "$dev/frames/." "bench\frames"
    Write-Host "Frames in bench\frames: $((Get-ChildItem bench\frames -Filter *.png).Count). Fill bench\truth.json before the run."

    # The app must be installed first so its data folder exists
    & "$root\gradlew.bat" installDebug
    if ($LASTEXITCODE -ne 0) { throw "installDebug failed" }
    & $adb shell mkdir -p "$dev/bench/frames"
    & $adb push "bench\frames/." "$dev/bench/frames"
    & $adb push "bench\truth.json" "$dev/bench/truth.json"

    # leaveApksInstalledAfterRun keeps the app (permissions, saved frames) installed afterwards
    & "$root\gradlew.bat" :app:connectedDebugAndroidTest `
        "-Pandroid.testInstrumentationRunnerArguments.class=app.shiori.OcrBenchmark" `
        "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
    $code = $LASTEXITCODE
    & $adb logcat -d -s Shiori:I
    if ($code -ne 0) { throw "benchmark failed" }
} finally {
    Pop-Location
}
