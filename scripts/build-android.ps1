param(
    [string]$SdkRoot = (Join-Path $PSScriptRoot '../.android-tools/sdk'),
    [string]$JdkRoot = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JdkRoot) { $JdkRoot = (Get-ChildItem (Join-Path $projectRoot '.android-tools/jdk') -Directory | Select-Object -First 1).FullName }
$env:JAVA_HOME = $JdkRoot
$androidRoot = Join-Path $projectRoot 'android'
$buildRoot = Join-Path $androidRoot 'build'
$buildTools = Join-Path $SdkRoot 'build-tools/35.0.0'
$platform = Join-Path $SdkRoot 'platforms/android-35/android.jar'
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
foreach ($folder in @($buildRoot, "$buildRoot/classes", "$buildRoot/dex")) { New-Item -ItemType Directory -Force $folder | Out-Null }
Invoke-Checked "$buildTools/aapt2.exe" @('compile', '--dir', "$androidRoot/res", '-o', "$buildRoot/resources.zip")
Invoke-Checked "$buildTools/aapt2.exe" @('link', '-o', "$buildRoot/base.apk", '-I', $platform, '--manifest', "$androidRoot/AndroidManifest.xml", '--version-code', '1', '--version-name', '0.6.1-android.1', '-R', "$buildRoot/resources.zip")
$sources = @(Get-ChildItem "$androidRoot/src" -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Checked "$JdkRoot/bin/javac.exe" (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $platform, '-d', "$buildRoot/classes") + $sources)
Invoke-Checked "$JdkRoot/bin/jar.exe" @('cf', "$buildRoot/classes.jar", '-C', "$buildRoot/classes", '.')
Invoke-Checked "$JdkRoot/bin/java.exe" @('-cp', "$buildTools/lib/d8.jar", 'com.android.tools.r8.D8', '--lib', $platform, '--min-api', '26', '--output', "$buildRoot/dex", "$buildRoot/classes.jar")
Copy-Item -LiteralPath "$buildRoot/base.apk" -Destination "$buildRoot/unsigned.apk" -Force
Invoke-Checked "$JdkRoot/bin/jar.exe" @('uf', "$buildRoot/unsigned.apk", '-C', "$buildRoot/dex", 'classes.dex')
Invoke-Checked "$buildTools/zipalign.exe" @('-f', '4', "$buildRoot/unsigned.apk", "$buildRoot/aligned.apk")
$keyRoot = Join-Path $projectRoot '.android-tools'
$keyStore = Join-Path $keyRoot 'android-debug.keystore'
if (-not (Test-Path $keyStore)) {
    New-Item -ItemType Directory -Force $keyRoot | Out-Null
    Invoke-Checked "$JdkRoot/bin/keytool.exe" @('-genkeypair', '-keystore', $keyStore, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=LearnHarness local development')
}
Invoke-Checked "$JdkRoot/bin/java.exe" @('-jar', "$buildTools/lib/apksigner.jar", 'sign', '--ks', $keyStore, '--ks-pass', 'pass:android', '--out', "$buildRoot/LearnHarness.apk", "$buildRoot/aligned.apk")
Invoke-Checked "$JdkRoot/bin/java.exe" @('-jar', "$buildTools/lib/apksigner.jar", 'verify', '--verbose', "$buildRoot/LearnHarness.apk")
Write-Output "APK: $buildRoot/LearnHarness.apk"
