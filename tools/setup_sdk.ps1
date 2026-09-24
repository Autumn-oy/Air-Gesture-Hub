# Install Gradle 8.13 (AGP 8.13.2 needs >= 8.13) and the Android SDK packages.
# ASCII-only on purpose (BOM-less UTF-8 .ps1 with Chinese confuses Windows PowerShell).
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = 'E:\android-toolchain'
$dl   = Join-Path $root 'downloads'
$sdk  = Join-Path $root 'sdk'

# ---- 1. Gradle 8.13 ----
$gdir = Join-Path $root 'gradle-8.13'
if (-not (Test-Path (Join-Path $gdir 'bin\gradle.bat'))) {
  $zip = Join-Path $dl 'gradle-8.13-bin.zip'
  if (-not (Test-Path $zip)) {
    foreach ($ver in @('8.13', '8.14.3', '8.14.2', '8.13')) {
      try {
        Write-Output "[down] gradle-$ver"
        Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$ver-bin.zip" -OutFile $zip -UseBasicParsing -TimeoutSec 900
        break
      } catch { Write-Output "  failed: $($_.Exception.Message)"; if (Test-Path $zip) { Remove-Item $zip -Force } }
    }
  }
  if (Test-Path $zip) {
    Write-Output '[unzip] gradle'
    $tmp = Join-Path $root 'g_tmp'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Move-Item $inner.FullName $gdir
    Remove-Item $tmp -Recurse -Force
  }
}
Write-Output ("GRADLE813 = {0}" -f (Join-Path $gdir 'bin\gradle.bat'))

# ---- 2. Android SDK packages ----
$env:JAVA_HOME = Join-Path $root 'jdk'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$sm = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'

Write-Output '--- accepting licenses ---'
$yes = ("y`r`n" * 40)
$yes | & $sm --sdk_root="$sdk" --licenses 2>&1 | Select-Object -Last 5

Write-Output '--- installing packages ---'
& $sm --sdk_root="$sdk" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 2>&1 |
  Select-Object -Last 25

Write-Output '--- installed ---'
Get-ChildItem (Join-Path $sdk 'platforms') -ErrorAction SilentlyContinue | ForEach-Object { "platform: $($_.Name)" }
Get-ChildItem (Join-Path $sdk 'build-tools') -ErrorAction SilentlyContinue | ForEach-Object { "build-tools: $($_.Name)" }
Get-ChildItem (Join-Path $sdk 'platform-tools') -ErrorAction SilentlyContinue | Select-Object -First 3 | ForEach-Object { "platform-tools: $($_.Name)" }
Write-Output 'SDK_READY'
