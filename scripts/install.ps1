# Builds the debug APK, installs it over the existing app (data kept) and opens it.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }

Push-Location $root
try {
    & "$root\gradlew.bat" assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }
    & $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
    & $adb shell am start -n app.retromine/app.shiori.MainActivity
    if ($LASTEXITCODE -ne 0) { throw "launch failed" }
} finally {
    Pop-Location
}
