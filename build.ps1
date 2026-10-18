# Reconstruit l'APK signé de CamAPS Guardian.
# Usage :  .\build.ps1                 (release signée)
#          .\build.ps1 -DebugOutput    (variante debug, identifiant .debug)
param(
    [switch]$DebugOutput
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
    if ($DebugOutput) {
        & .\gradlew.bat :app:assembleDebug
    } else {
        & .\gradlew.bat :app:assembleRelease
    }
    if ($LASTEXITCODE -ne 0) {
        throw "La compilation a échoué (code $LASTEXITCODE)."
    }
    Get-ChildItem "$root\app\build\outputs\apk" -Recurse -Filter *.apk |
        Sort-Object LastWriteTime -Descending |
        Select-Object FullName, @{ Name = "Mo"; Expression = { [math]::Round($_.Length / 1MB, 2) } }, LastWriteTime
}
finally {
    Pop-Location
}
