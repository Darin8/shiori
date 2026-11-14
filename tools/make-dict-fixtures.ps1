# Builds the Yomitan fixture zips used by YomitanImporterTest.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$out = Join-Path $PSScriptRoot "..\app\src\test\resources\dict"
New-Item -ItemType Directory -Force $out | Out-Null
function New-Zip($name, $files) {
    $path = Join-Path (Resolve-Path $out) $name
    if (Test-Path $path) { Remove-Item $path }
    $zip = [System.IO.Compression.ZipFile]::Open($path, 'Create')
    foreach ($k in $files.Keys) {
        $e = $zip.CreateEntry($k)
        $w = New-Object System.IO.StreamWriter($e.Open(), (New-Object System.Text.UTF8Encoding $false))
        $w.Write($files[$k]); $w.Close()
    }
    $zip.Dispose()
}
New-Zip "mini-terms.zip" @{
    "index.json" = '{"title":"MiniJitendex","format":3,"revision":"t"}'
    "term_bank_1.json" = '[["食べる","たべる","v1","v1",100,["to eat"],1,""],["行く","いく","v5","v5k-s",90,[{"type":"structured-content","content":["to go"]}],2,""],["終わる","おわる","v5","v5r",80,["to end","to finish"],3,""]]'
}
New-Zip "mini-freq.zip" @{
    "index.json" = '{"title":"MiniFreq","format":3,"revision":"t"}'
    "term_meta_bank_1.json" = '[["終わる","freq",{"reading":"おわる","frequency":{"value":312,"displayValue":"312"}}],["食べる","freq",55],["行く","freq",{"value":7,"displayValue":"7㋕"}],["行く","pitch",{"reading":"いく","pitches":[]}]]'
}
