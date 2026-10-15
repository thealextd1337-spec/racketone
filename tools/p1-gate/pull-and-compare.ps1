<#
.SYNOPSIS
    P1-Gate: zieht die Advantly-Datenbanken von Handy und Uhr(en) per adb, dazu das Feldtest-Protokoll der Uhren,
    und startet den Integritaetsvergleich ("0 verlorene Punkte") sowie die Akku-Auswertung.

.DESCRIPTION
    Voraussetzungen: Debug-Builds von Phone- und Wear-App (run-as eu.advantly), Uhr per WLAN-adb verbunden
    (adb pair/connect, siehe docs/spikes/s5-wear-workout.md Abschnitt 2), JDK 21 fuer Gradle.
    Ablauf und Bestanden-Kriterien: docs/release/p1-gate-protokoll.md.

    Die Skriptdatei ist bewusst reines ASCII (Windows PowerShell 5.1 liest .ps1 ohne BOM als ANSI).

.PARAMETER Phone
    adb-Seriennummer des Handys (adb devices), z. B. 1A2B3C4D oder 192.168.1.10:37000.
.PARAMETER Watch
    Eine oder mehrere Uhren als label=seriennummer, z. B. pixel=192.168.1.20:41234,galaxy=192.168.1.21:39001.
.PARAMETER OutDir
    Zielordner (Standard H:\p1-gate\<Datum-Uhrzeit>). Rohdaten nach roh\<label>\, Berichte nach berichte\.
.PARAMETER Since
    Nur Matches ab diesem Datum (JJJJ-MM-TT) bewerten.
.PARAMETER NoForceStop
    App vor dem Kopieren NICHT beenden. Standard ist force-stop, damit Datenbank und WAL-Datei zusammenpassen.
    Nur nach Matchende ziehen; ein laufendes Workout wird durch force-stop beendet.
.PARAMETER ClearFieldLog
    Feldtest-Protokoll nach erfolgreichem Kopieren auf der Uhr loeschen.
.PARAMETER SkipCompare
    Nur ziehen, nicht auswerten.
.PARAMETER Strict
    Exit-Code 1 bei NICHT BESTANDEN, 2 bei OFFEN (fuer Automatisierung).

.EXAMPLE
    .\tools\p1-gate\pull-and-compare.ps1 -Phone 1A2B3C4D -Watch pixel=192.168.1.20:41234 -Since 2026-10-20
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Phone,
    [string[]]$Watch = @(),
    [string]$OutDir = ("H:\p1-gate\" + (Get-Date -Format "yyyyMMdd-HHmm")),
    [string]$Adb = "",
    [string]$Since = "",
    [switch]$NoForceStop,
    [switch]$ClearFieldLog,
    [switch]$SkipCompare,
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$Package = "eu.advantly"
$DbFiles = @("advantly.db", "advantly.db-wal", "advantly.db-shm")
$DiagDir = "/sdcard/Android/data/$Package/files/diag"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Find-Adb {
    if ($Adb -ne "") { return $Adb }
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $candidates += "H:\dev\android-sdk\platform-tools\adb.exe"
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb nicht gefunden. Pfad mit -Adb angeben."
}

$AdbExe = Find-Adb

# stderr von adb (z. B. "No such file") darf unter ErrorActionPreference=Stop nicht abbrechen (PowerShell 5.1)
function Invoke-Adb([string]$Serial, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $AdbExe -s $Serial @Arguments 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $previous
    }
    return ($output -join "`n")
}

# Binaere Ausgabe (exec-out) ueber cmd /c in eine Datei: die PowerShell-Umleitung wuerde UTF-16 schreiben
function Save-ExecOut([string]$Serial, [string]$Command, [string]$Target) {
    $line = "`"$AdbExe`" -s $Serial exec-out $Command > `"$Target`""
    cmd /c $line | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Fehlgeschlagen: adb exec-out $Command" }
}

function Assert-Device([string]$Label, [string]$Serial) {
    $state = Invoke-Adb $Serial @("get-state")
    if ($state.Trim() -ne "device") {
        throw "$Label ($Serial) nicht verbunden (adb get-state: $state). Uhr: adb connect IP:PORT."
    }
}

function Save-DeviceInfo([string]$Label, [string]$Serial, [string]$Target) {
    $model = (Invoke-Adb $Serial @("shell", "getprop", "ro.product.model")).Trim()
    $release = (Invoke-Adb $Serial @("shell", "getprop", "ro.build.version.release")).Trim()
    $pkg = Invoke-Adb $Serial @("shell", "dumpsys", "package", $Package)
    $version = ($pkg -split "`n" | Where-Object { $_ -match "versionName=" } | Select-Object -First 1)
    $flags = ($pkg -split "`n" | Where-Object { $_ -match "pkgFlags=" } | Select-Object -First 1)
    @(
        "label=$Label",
        "serial=$Serial",
        "model=$model",
        "android=$release",
        "app=$("$version".Trim())",
        "flags=$("$flags".Trim())",
        "pulled=$(Get-Date -Format o)"
    ) | Set-Content -Encoding ascii (Join-Path $Target "geraet.txt")
    return "$model (Android $release, $("$version".Trim()))"
}

function Pull-Database([string]$Label, [string]$Serial, [string]$Target) {
    $listing = Invoke-Adb $Serial @("shell", "run-as", $Package, "ls", "databases")
    if ($listing -match "not debuggable|unknown package|is unknown") {
        throw "$Label`: run-as $Package nicht moeglich ($listing). Debug-Build installieren."
    }
    $present = $listing -split "\s+" | Where-Object { $_ -ne "" }
    if (-not ($present -contains "advantly.db")) { throw "$Label`: databases/advantly.db fehlt ($listing)." }
    if (-not $NoForceStop) {
        Write-Host "  $Label`: App beenden (force-stop), damit DB und WAL konsistent sind"
        Invoke-Adb $Serial @("shell", "am", "force-stop", $Package) | Out-Null
        Start-Sleep -Milliseconds 500
    }
    foreach ($file in $DbFiles) {
        if ($present -contains $file) {
            $dest = Join-Path $Target $file
            Save-ExecOut $Serial "run-as $Package cat databases/$file" $dest
            Write-Host ("  {0}: {1} ({2:N0} Bytes)" -f $Label, $file, (Get-Item $dest).Length)
        }
    }
    $db = Join-Path $Target "advantly.db"
    $header = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($db), 0, 15)
    if ($header -ne "SQLite format 3") { throw "$Label`: $db ist keine SQLite-Datei (Header '$header')." }
    return $db
}

# Zieht fieldtest*.jsonl (Feldtest-Modus) und s5-diag*.log (Workout-Diagnose, nur Debug); liefert die Feldtest-Dateien
function Pull-FieldLog([string]$Label, [string]$Serial, [string]$Target) {
    $all = (Invoke-Adb $Serial @("shell", "ls", $DiagDir)) -split "\s+" | Where-Object { $_ -like "fieldtest*.jsonl" -or $_ -like "s5-diag*.log" }
    $names = @($all | Where-Object { $_ -like "fieldtest*.jsonl" })
    $pulled = @()
    foreach ($name in $all) {
        $dest = Join-Path $Target $name
        Invoke-Adb $Serial @("pull", "$DiagDir/$name", "$dest") | Out-Null
        if (-not (Test-Path $dest) -or (Get-Item $dest).Length -eq 0) {
            # Manche Android-Versionen sperren Android/data fuer adb pull
            Save-ExecOut $Serial "run-as $Package cat $DiagDir/$name" $dest
        }
        if ($name -like "fieldtest*.jsonl") { $pulled += $dest }
        Write-Host ("  {0}: {1} ({2:N0} Bytes)" -f $Label, $name, (Get-Item $dest).Length)
    }
    if ($pulled.Count -eq 0) { Write-Host "  $Label`: kein Feldtest-Protokoll (Feldtest-Modus aus?)" }
    if ($ClearFieldLog -and $pulled.Count -gt 0) {
        foreach ($name in $names) { Invoke-Adb $Serial @("shell", "rm", "$DiagDir/$name") | Out-Null }
        Write-Host "  $Label`: Feldtest-Protokoll auf der Uhr geloescht"
    }
    return $pulled
}

function Gradle-Path([string]$Path) { return ($Path -replace "\\", "/") }

# --- Geraete einsammeln --------------------------------------------------------------------------
$devices = @(@{ Label = "handy"; Serial = $Phone; IsWatch = $false })
foreach ($spec in $Watch) {
    foreach ($part in ($spec -split ",")) {
        if ($part -notmatch "^([A-Za-z0-9_-]+)=(.+)$") { throw "-Watch erwartet label=seriennummer, nicht '$part'" }
        $devices += @{ Label = $Matches[1]; Serial = $Matches[2]; IsWatch = $true }
    }
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$rawRoot = Join-Path $OutDir "roh"
$reports = Join-Path $OutDir "berichte"
Write-Host "P1-Gate: Ablage $OutDir (adb: $AdbExe)"

$dbArgs = @()
$fieldLogs = @{}
foreach ($d in $devices) {
    $target = Join-Path $rawRoot $d.Label
    New-Item -ItemType Directory -Force $target | Out-Null
    Assert-Device $d.Label $d.Serial
    $info = Save-DeviceInfo $d.Label $d.Serial $target
    Write-Host "$($d.Label): $info"
    $db = Pull-Database $d.Label $d.Serial $target
    $dbArgs += "--db"
    $dbArgs += ("'" + $d.Label + "=" + (Gradle-Path $db) + "'")
    if ($d.IsWatch) { $fieldLogs[$d.Label] = @(Pull-FieldLog $d.Label $d.Serial $target) }
}

if ($SkipCompare) {
    Write-Host "Nur gezogen (-SkipCompare). Rohdaten: $rawRoot"
    exit 0
}

# --- Auswertung ----------------------------------------------------------------------------------
$gradle = Join-Path $RepoRoot "gradlew.bat"
$compareArgs = @("compare") + $dbArgs + @("--out", ("'" + (Gradle-Path $reports) + "'"))
if ($Since -ne "") { $compareArgs += @("--since", $Since) }
if ($Strict) { $compareArgs += "--strict" }
Write-Host "`nIntegritaetsvergleich ..."
& $gradle -p $RepoRoot -q --console=plain ":tools:gate-check:gateCheck" ("--args=" + ($compareArgs -join " "))
$compareExit = $LASTEXITCODE

foreach ($label in $fieldLogs.Keys) {
    $logs = $fieldLogs[$label]
    if ($logs.Count -eq 0) { continue }
    $batteryArgs = @("battery", "--label", $label, "--out", ("'" + (Gradle-Path $reports) + "'"))
    foreach ($log in $logs) { $batteryArgs += @("--log", ("'" + (Gradle-Path $log) + "'")) }
    Write-Host "`nAkku-Auswertung $label ..."
    & $gradle -p $RepoRoot -q --console=plain ":tools:gate-check:gateCheck" ("--args=" + ($batteryArgs -join " "))
}

# Workout-Diagnose (beginnt/endet genau einmal, Prozessneustarts): tools/s5_analyze.py, falls Python vorhanden
$python = Get-Command python -ErrorAction SilentlyContinue
foreach ($label in $fieldLogs.Keys) {
    $diag = @(Get-ChildItem (Join-Path $rawRoot $label) -Filter "s5-diag*.log" -ErrorAction SilentlyContinue)
    if ($diag.Count -eq 0) { continue }
    if (-not $python) { Write-Host "Python fehlt: tools\s5_analyze.py fuer $label manuell ausfuehren."; continue }
    $outFile = Join-Path $reports "workout-$label.txt"
    & $python.Source (Join-Path $RepoRoot "tools\s5_analyze.py") @($diag | ForEach-Object { $_.FullName }) | Set-Content -Encoding utf8 $outFile
    Write-Host "Workout-Diagnose $label`: $outFile"
}

Write-Host "`nBerichte: $reports (index.md, match-*.md, akku-*.md, workout-*.txt)"
Write-Host "Rohdaten enthalten Spielernamen: nicht ins Repo legen, nach Abnahme loeschen."
exit $compareExit
