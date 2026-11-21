$ErrorActionPreference = "Stop"

$catalogPath = Join-Path $PSScriptRoot "..\app\src\main\assets\stratagems\catalog.json"
$json = Get-Content -LiteralPath $catalogPath -Raw
$entries = ConvertFrom-Json -InputObject $json
if ($entries.Count -lt 100) {
    throw "Expected a full catalog; found $($entries.Count) entries."
}

$duplicateIds = @($entries | Group-Object id | Where-Object Count -gt 1)
if ($duplicateIds.Count -gt 0) {
    throw "Duplicate IDs: $(($duplicateIds | ForEach-Object Name) -join ', ')."
}

$invalid = @($entries | Where-Object {
    [string]::IsNullOrWhiteSpace($_.name) -or
    [string]::IsNullOrWhiteSpace($_.source) -or
    [string]::IsNullOrWhiteSpace($_.verification) -or
    $_.directions -notmatch '^[UDLR]+$'
})
if ($invalid.Count -gt 0) {
    throw "Invalid or unverified entries: $(($invalid | ForEach-Object name) -join ', ')."
}

$invalidMissionEntries = @($entries | Where-Object { $_.missionSelectable -and $_.category -ne "Mission" })
if ($invalidMissionEntries.Count -gt 0) {
    throw "Non-mission stratagems are marked mission-specific: $(($invalidMissionEntries | ForEach-Object name) -join ', ')."
}
$missionEntries = @($entries | Where-Object { $_.category -eq "Mission" -and $_.missionSelectable })
if ($missionEntries.Count -lt 10) {
    throw "Expected mission-specific stratagems; found only $($missionEntries.Count)."
}

$byCategory = $entries | Group-Object category | ForEach-Object { "$($_.Name)=$($_.Count)" }
$resupply = $entries | Where-Object id -eq "resupply" | Select-Object -First 1
$reinforce = $entries | Where-Object id -eq "reinforce" | Select-Object -First 1
if (-not $resupply -or $resupply.directions -ne "DDUR") { throw "Resupply code mismatch." }
if (-not $reinforce -or $reinforce.directions -ne "UDRLU") { throw "Reinforce code mismatch." }

Write-Output "Validated $($entries.Count) entries, including $($missionEntries.Count) mission-specific call-ins. $($byCategory -join ', ')"
