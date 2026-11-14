# Saves reference JP Mining Note cards from a local Anki (AnkiConnect, read-only actions) for the
# CardBuilder test. Words are cards made with Jitendex.org [2025-02-11]; change -Words to refresh.
param(
    [string[]]$Words = @('拾う', '恐怖', '避ける'),
    [string]$Url = 'http://127.0.0.1:8765',
    [string]$Out = (Join-Path $PSScriptRoot '..\app\src\test\resources\reference-notes')
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Invoke-Anki($action, $params) {
    $body = @{ action = $action; version = 6; params = $params } | ConvertTo-Json -Depth 8 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod -Uri $Url -Method Post -Body $bytes -ContentType 'application/json; charset=utf-8'
    if ($r.error) { throw "AnkiConnect $action failed: $($r.error)" }
    $r.result
}

# Notes added on or after the Jitendex date (note id = epoch milliseconds).
$since = [DateTimeOffset]::new(2025, 2, 11, 0, 0, 0, [TimeSpan]::Zero).ToUnixTimeMilliseconds()
$ids = Invoke-Anki 'findNotes' @{ query = '"note:JP Mining Note" deck:Default tag:yomitan' }
$ids = @($ids | Where-Object { $_ -ge $since })
$order = @(Invoke-Anki 'modelFieldNames' @{ modelName = 'JP Mining Note' })
$notes = @(Invoke-Anki 'notesInfo' @{ notes = $ids })

New-Item -ItemType Directory -Force $Out | Out-Null
foreach ($word in $Words) {
    $note = $notes | Where-Object {
        $_.fields.Word.value -eq $word -and $_.fields.PrimaryDefinition.value -like '*Jitendex.org [[]2025-02-11]*'
    } | Select-Object -First 1
    if (-not $note) { throw "No JP Mining Note card for $word made with Jitendex.org [2025-02-11]" }
    $fields = [ordered]@{}
    foreach ($name in $order) { $fields[$name] = $note.fields.$name.value }
    $ref = [ordered]@{
        noteId = $note.noteId
        modelName = $note.modelName
        fieldNames = $order
        tags = @($note.tags)
        fields = $fields
    }
    $path = Join-Path $Out "$($note.noteId).json"
    [IO.File]::WriteAllText($path, ($ref | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    Write-Host "saved $path"
}
