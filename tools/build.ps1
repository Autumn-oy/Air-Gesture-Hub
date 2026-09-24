# Build AirGestureSweep in an ASCII-only mirror directory.
#
# Why a mirror: AGP refuses non-ASCII project paths on Windows, and even with
# android.overridePathCheck=true the *unit test worker* fails with
# ClassNotFoundException because its classpath is round-tripped through a text
# file with the wrong charset. Compiling in E:\airgesture (ASCII) avoids both.
#
# ASCII-only content on purpose: Windows PowerShell decodes BOM-less .ps1 files
# as GBK and would corrupt any non-ASCII literal in this file. The source path
# is derived from $PSScriptRoot instead of being hard-coded.
param(
  [string]$Mirror = 'E:\airgesture',
  [switch]$RunTests,
  [switch]$SkipBuild,
  [switch]$Release,
  [switch]$Debuggable
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$src = Split-Path -Parent $PSScriptRoot          # ...\<workspace>\android
if (-not (Test-Path (Join-Path $src 'settings.gradle.kts'))) {
  throw "Cannot find settings.gradle.kts next to $PSScriptRoot"
}

$root = 'E:\android-toolchain'
$env:JAVA_HOME = Join-Path $root 'jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME = Join-Path $root 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $root 'gradle-home'
$env:JAVA_TOOL_OPTIONS = ''

$gradle = Join-Path $root 'gradle-8.13\bin\gradle.bat'

# v2.0.0: only the release variant is shipped, signed with the project's own
# keystore (see app/build.gradle.kts). The default stays debug so the existing
# test flow keeps working; -Release switches the whole pipeline.
$variant = if ($Release) { 'Release' } else { 'Debug' }
$lower = $variant.ToLower()
# -Debuggable keeps the SAME signing key but sets android:debuggable=true, so
# `run-as` keeps working for diagnostics. The two APKs can overwrite each other
# (same signature) without losing prefs.
$suffix = if ($Debuggable) { '-debuggable' } else { '' }

Write-Output "source = $src"
Write-Output "mirror = $Mirror"

# ---- 1. mirror the sources (no build outputs) ----
New-Item -ItemType Directory -Force -Path $Mirror | Out-Null
& robocopy $src $Mirror /MIR /XD build .gradle .idea /NFL /NDL /NJH /NJS /NP | Out-Null
$rc = $LASTEXITCODE
if ($rc -ge 8) { throw "robocopy failed with code $rc" }
Write-Output "mirror synced (robocopy code $rc)"

# local.properties must point at the SDK on this machine
"sdk.dir=E:/android-toolchain/sdk" | Set-Content -Path (Join-Path $Mirror 'local.properties') -Encoding ASCII

# ---- 2. build ----
if (-not $SkipBuild) {
  Push-Location $Mirror
  try {
    $gradleArgs = @('--no-daemon', "assemble$variant")
    if ($Debuggable) { $gradleArgs += '-Pdebuggable' }
    & $gradle $gradleArgs 2>&1 | Select-String -Pattern `
      'BUILD SUCCESSFUL|BUILD FAILED|^e: |error:|What went wrong|Execution failed|FAILURE|APK|Unable to strip' |
      ForEach-Object { $_.Line }
  } finally {
    Pop-Location
  }
}

# ---- 3. unit tests (cross-language vector replay) ----
if ($RunTests) {
  Push-Location $Mirror
  try {
    & $gradle --no-daemon testDebugUnitTest 2>&1 | Select-String -Pattern `
      'tests? completed|FAILED|BUILD SUCCESSFUL|BUILD FAILED|expected:|What went wrong' |
      ForEach-Object { $_.Line }
  } finally {
    Pop-Location
  }
}

# ---- 4. copy the APK back into the workspace ----
$built = Join-Path $Mirror "app\build\outputs\apk\$lower\app-$lower.apk"
$destDir = Join-Path $src "app\build\outputs\apk\$lower"
$destName = "app-$lower$suffix.apk"
if (Test-Path $built) {
  New-Item -ItemType Directory -Force -Path $destDir | Out-Null
  Copy-Item $built (Join-Path $destDir $destName) -Force
  $item = Get-Item (Join-Path $destDir $destName)
  Write-Output ("APK OK: {0}  ({1:N1} MB)" -f $item.FullName, ($item.Length / 1MB))
  # Always print the signing certificate: a mis-signed build must not go unnoticed.
  $signer = Join-Path $root 'sdk\build-tools\36.0.0\apksigner.bat'
  if (Test-Path $signer) {
    & $signer verify --print-certs $item.FullName 2>&1 |
      Select-String -Pattern 'Signer #1 certificate SHA-256 digest|DOES NOT VERIFY' |
      ForEach-Object { $_.Line.Trim() }
  }
} else {
  Write-Output 'APK NOT FOUND'
}
Write-Output 'DONE'
