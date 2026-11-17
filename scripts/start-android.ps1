param(
    [string]$PcAddress,
    [switch]$UseDesktopProfile,
    [int]$BackendPort = 3000,
    [int]$GatewayPort = 3443
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateRoot = Join-Path $projectRoot '.android-pairing'
if (-not (Test-Path "$projectRoot/.next/BUILD_ID")) { throw 'Run npm run build first.' }
if (-not $PcAddress) {
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi' -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '169.254.*' })
    if ($addresses.Count -ne 1) { throw 'Pass -PcAddress with the PC address reachable from the tablet.' }
    $PcAddress = $addresses[0].IPAddress
}
if ($UseDesktopProfile) {
    $profileRoot = Join-Path $env:APPDATA 'LearnHarness'
    if (-not (Test-Path "$profileRoot/data")) { throw 'The desktop LearnHarness profile was not found.' }
    if (Get-Process -Name LearnHarness -ErrorAction SilentlyContinue) { throw 'Close the LearnHarness desktop app before sharing its profile.' }
    $env:LH_DATA_DIR = Join-Path $profileRoot 'data'
    $env:LH_AUTH_FILE = Join-Path $profileRoot 'auth.json'
} else {
    $env:LH_DATA_DIR = Join-Path $projectRoot 'data'
    $env:LH_AUTH_FILE = Join-Path $projectRoot 'auth.json'
}
foreach ($port in @($BackendPort, $GatewayPort)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use. Stop the earlier companion before starting another." }
}
New-Item -ItemType Directory -Force $stateRoot | Out-Null
$env:LH_ANDROID_HOST = $PcAddress
$env:LH_ANDROID_BIND = '0.0.0.0'
$env:LH_ANDROID_PORT = "$GatewayPort"
$env:LH_ANDROID_BACKEND_PORT = "$BackendPort"
$node = (Get-Command node.exe).Source
$server = $null
$gateway = $null
try {
    $server = Start-Process -FilePath $node -ArgumentList @('node_modules/next/dist/bin/next', 'start', '-H', '127.0.0.1', '-p', "$BackendPort") -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$stateRoot/server.stdout.log" -RedirectStandardError "$stateRoot/server.stderr.log" -PassThru
    $ready = $false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        $server.Refresh()
        if ($server.HasExited) { throw 'PC backend exited. See .android-pairing/server.stderr.log.' }
        try { $response = Invoke-WebRequest "http://127.0.0.1:$BackendPort/api/chats" -UseBasicParsing -TimeoutSec 2; if ($response.StatusCode -eq 200) { $ready=$true; break } } catch { Start-Sleep -Milliseconds 300 }
    }
    if (-not $ready) { throw 'PC backend did not become ready.' }
    $gateway = Start-Process -FilePath $node -ArgumentList @('scripts/android-gateway.mjs') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput "$stateRoot/gateway.stdout.log" -RedirectStandardError "$stateRoot/gateway.stderr.log" -PassThru
    Start-Sleep -Milliseconds 800
    $gateway.Refresh()
    if ($gateway.HasExited) { throw 'Gateway exited. See .android-pairing/gateway.stderr.log.' }
    @($server,$gateway) | ForEach-Object { @{ id=$_.Id; started=$_.StartTime.ToUniversalTime().ToString('o') } } | ConvertTo-Json | Set-Content -Encoding utf8 "$stateRoot/processes.json"
    Write-Output "PC companion running at https://${PcAddress}:$GatewayPort"
    Write-Output "Pairing string: $stateRoot/pairing.txt (keep private)"
    Write-Output 'Keep this PC awake and on the same Wi-Fi. Stop with scripts/stop-android.ps1.'
} catch {
    foreach ($process in @($gateway,$server)) { if ($null -ne $process -and -not $process.HasExited) { $process.Kill() } }
    throw
}
