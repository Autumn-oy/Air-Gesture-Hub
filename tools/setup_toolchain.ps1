# Prepare Android build toolchain under an ASCII-only path.
# (Chinese characters in a BOM-less .ps1 get mis-decoded by Windows PowerShell
#  and can break string terminators, so this script stays ASCII-only.)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = 'E:\android-toolchain'
$dl   = Join-Path $root 'downloads'
New-Item -ItemType Directory -Force -Path $dl, (Join-Path $root 'sdk') | Out-Null

$items = @(
  @{ name = 'jdk17.zip';   url = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' },
  @{ name = 'cmdline.zip'; url = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' },
  @{ name = 'gradle.zip';  url = 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' }
)

foreach ($it in $items) {
  $out = Join-Path $dl $it.name
  if (Test-Path $out) {
    $sz = (Get-Item $out).Length / 1MB
    Write-Output ("[skip] {0} already present ({1:N1} MB)" -f $it.name, $sz)
    continue
  }
  Write-Output ("[down] {0}" -f $it.name)
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Invoke-WebRequest -Uri $it.url -OutFile $out -UseBasicParsing -TimeoutSec 900
  $sw.Stop()
  Write-Output ("[done] {0}  {1:N1} MB  {2:N0}s" -f $it.name, ((Get-Item $out).Length / 1MB), $sw.Elapsed.TotalSeconds)
}

function Unzip($zip, $dest) {
  if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
}

if (-not (Test-Path (Join-Path $root 'jdk'))) {
  Write-Output '[unzip] jdk'
  Unzip (Join-Path $dl 'jdk17.zip') (Join-Path $root 'jdk_tmp')
  $inner = Get-ChildItem (Join-Path $root 'jdk_tmp') -Directory | Select-Object -First 1
  Move-Item $inner.FullName (Join-Path $root 'jdk')
  Remove-Item (Join-Path $root 'jdk_tmp') -Recurse -Force
}

if (-not (Test-Path (Join-Path $root 'gradle'))) {
  Write-Output '[unzip] gradle'
  Unzip (Join-Path $dl 'gradle.zip') (Join-Path $root 'gradle_tmp')
  $inner = Get-ChildItem (Join-Path $root 'gradle_tmp') -Directory | Select-Object -First 1
  Move-Item $inner.FullName (Join-Path $root 'gradle')
  Remove-Item (Join-Path $root 'gradle_tmp') -Recurse -Force
}

$sm = Join-Path $root 'sdk\cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sm)) {
  Write-Output '[unzip] cmdline-tools'
  Unzip (Join-Path $dl 'cmdline.zip') (Join-Path $root 'cmd_tmp')
  New-Item -ItemType Directory -Force -Path (Join-Path $root 'sdk\cmdline-tools') | Out-Null
  Move-Item (Join-Path $root 'cmd_tmp\cmdline-tools') (Join-Path $root 'sdk\cmdline-tools\latest')
  Remove-Item (Join-Path $root 'cmd_tmp') -Recurse -Force
}

Write-Output '===== result ====='
Write-Output ("JAVA_HOME  = {0}" -f (Join-Path $root 'jdk'))
Write-Output ("GRADLE     = {0}" -f (Join-Path $root 'gradle\bin\gradle.bat'))
Write-Output ("SDKMANAGER = {0}" -f $sm)
$java = Join-Path $root 'jdk\bin\java.exe'
if (Test-Path $java) { & $java -version 2>&1 | ForEach-Object { Write-Output ("  " + $_) } }
Write-Output 'TOOLCHAIN_READY'
