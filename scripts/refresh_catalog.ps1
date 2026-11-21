$ErrorActionPreference = "Stop"

$sourceUrl = "https://helldivers.wiki.gg/api.php?action=parse&page=Stratagems&prop=text&format=json"
$temporaryFile = Join-Path $env:TEMP "stratagem-caller-wiki-catalog.json"
$catalogPath = Join-Path $PSScriptRoot "..\app\src\main\assets\stratagems\catalog.json"

& curl.exe --fail --silent --show-error --compressed `
    --retry 2 `
    --user-agent "StratagemCallerCatalogUpdater/1.0 (offline catalog maintenance)" `
    --output $temporaryFile `
    $sourceUrl
if ($LASTEXITCODE -ne 0) {
    throw "Could not retrieve the Helldivers Wiki catalog (curl exit code $LASTEXITCODE)."
}

$response = Get-Content $temporaryFile -Raw | ConvertFrom-Json
Remove-Item -LiteralPath $temporaryFile -Force
$html = $response.parse.text.'*'
if ([string]::IsNullOrWhiteSpace($html)) {
    throw "The wiki response did not contain the rendered Stratagems page."
}

$tableMatches = [regex]::Matches($html, '(?is)<table\b[^>]*class="[^"]*wikitable[^"]*"[^>]*>(.*?)</table>')
$sections = @(
    @{ Category = "Offensive"; Subcategory = "Orbital"; Personal = $true; Mission = $true },
    @{ Category = "Offensive"; Subcategory = "Eagle"; Personal = $true; Mission = $true },
    @{ Category = "Supply"; Subcategory = "Support Weapon"; Personal = $true; Mission = $true },
    @{ Category = "Supply"; Subcategory = "Backpack"; Personal = $true; Mission = $true },
    @{ Category = "Supply"; Subcategory = "Vehicle"; Personal = $true; Mission = $true },
    @{ Category = "Defensive"; Subcategory = "Sentry"; Personal = $true; Mission = $true },
    @{ Category = "Defensive"; Subcategory = "Emplacement"; Personal = $true; Mission = $true },
    @{ Category = "Mission"; Subcategory = "Ship"; Personal = $false; Mission = $true },
    @{ Category = "Mission"; Subcategory = "Objective"; Personal = $false; Mission = $true }
)

if ($tableMatches.Count -lt $sections.Count + 1) {
    throw "Expected at least $($sections.Count + 1) wiki stratagem tables; found $($tableMatches.Count). Catalog layout may have changed."
}

function Get-CellText([string] $value) {
    $plain = [regex]::Replace($value, '(?is)<[^>]+>', ' ')
    return ([System.Net.WebUtility]::HtmlDecode($plain) -replace '\s+', ' ').Trim()
}

function Get-UniqueId([string] $name, [string] $sequence, [hashtable] $usedIds) {
    $id = ($name.ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_')
    if ($usedIds.ContainsKey($id)) {
        $id = "${id}_$($sequence.ToLowerInvariant())"
    }
    if ($usedIds.ContainsKey($id)) {
        throw "Duplicate catalog entry ID for '$name'."
    }
    $usedIds[$id] = $true
    return $id
}

$records = [System.Collections.Generic.List[object]]::new()
$usedIds = @{}
$rowCount = 0
$missingSequences = [System.Collections.Generic.List[string]]::new()

for ($tableIndex = 0; $tableIndex -lt $sections.Count; $tableIndex++) {
    $section = $sections[$tableIndex]
    $rows = [regex]::Matches($tableMatches[$tableIndex].Groups[1].Value, '(?is)<tr\b[^>]*>(.*?)</tr>')
    foreach ($row in $rows | Select-Object -Skip 1) {
        $cells = [regex]::Matches($row.Groups[1].Value, '(?is)<td\b[^>]*>(.*?)</td>')
        if ($cells.Count -lt 4) {
            continue
        }

        $nameLink = [regex]::Match($cells[1].Groups[1].Value, '(?is)<a\b[^>]*>(.*?)</a>')
        $name = Get-CellText $nameLink.Groups[1].Value
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }

        $directions = [System.Collections.Generic.List[string]]::new()
        foreach ($arrow in [regex]::Matches($cells[2].Groups[1].Value, '(?is)<img\b[^>]*alt="Stratagem Arrow (Up|Down|Left|Right)\.svg"')) {
            $letter = switch ($arrow.Groups[1].Value) {
                "Up" { "U" }
                "Down" { "D" }
                "Left" { "L" }
                "Right" { "R" }
            }
            $directions.Add($letter)
        }

        if ($directions.Count -eq 0) {
            $missingSequences.Add($name)
            continue
        }

        $source = if ($cells.Count -ge 7) { Get-CellText $cells[6].Groups[1].Value } else { "Mission-provided" }
        $sequence = $directions -join ""
        $id = Get-UniqueId $name $sequence $usedIds
        $missionSelectable = $section.Category -eq "Mission" -and $name -notin @("Resupply", "Reinforce")
        $verification = "Sequence transcribed from the Helldivers Wiki Stratagems table."
        $records.Add([ordered]@{
            id = $id
            name = $name
            category = $section.Category
            subcategory = $section.Subcategory
            directions = $sequence
            description = "Official $($section.Subcategory.ToLowerInvariant()) stratagem. See the linked source for its tactical description."
            unlock = $source
            personalSelectable = $section.Personal
            missionSelectable = $missionSelectable
            source = "https://helldivers.wiki.gg/wiki/Stratagems"
            verification = $verification
        })
        $rowCount++
    }
}

if ($missingSequences.Count -gt 0) {
    throw "Refusing to publish entries without verified directional codes: $($missingSequences -join ', ')."
}
if ($rowCount -lt 100) {
    throw "Only $rowCount entries were parsed; refusing to replace the bundled catalog."
}

$json = ConvertTo-Json -InputObject @($records) -Depth 6
[System.IO.File]::WriteAllText($catalogPath, $json, [System.Text.UTF8Encoding]::new($false))
Write-Output "Wrote $rowCount stratagem entries from the current wiki tables to $catalogPath."
