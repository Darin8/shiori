# Downloads the MeikiOCR ONNX models into app\src\main\assets\meiki (they are committed; use this to restore or update).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dir = "$root\app\src\main\assets\meiki"
New-Item -ItemType Directory -Force $dir | Out-Null
$files = @{
    "meiki.text.detect.v0.1.960x544.onnx"    = "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/main/meiki.text.detect.v0.1.960x544.onnx"
    "meiki.text.rec.v0.960x32.onnx"          = "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/main/meiki.text.rec.v0.960x32.onnx"
    "meiki.text.rec.v0.vertical.32x480.onnx" = "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/main/meiki.text.rec.v0.vertical.32x480.onnx"
}
foreach ($name in $files.Keys) {
    Invoke-WebRequest -Uri $files[$name] -OutFile "$dir\$name"
    Write-Host "$name  $((Get-FileHash "$dir\$name" -Algorithm SHA256).Hash)"
}
