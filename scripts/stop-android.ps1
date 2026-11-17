$ErrorActionPreference = 'Stop'
$stateFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.android-pairing/processes.json'
if (-not (Test-Path $stateFile)) { Write-Output 'No saved companion process IDs.'; exit 0 }
foreach ($record in @(Get-Content $stateFile -Raw | ConvertFrom-Json)) {
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'node' -and $process.StartTime.ToUniversalTime().ToString('o') -eq $record.started) {
        Stop-Process -Id $process.Id
    }
}
Write-Output 'Stopped the recorded Android companion processes.'
