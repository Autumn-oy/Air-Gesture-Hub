# AirGestureSweep: deploy the built APK and take a measurement, safely.
#
# ASCII-only on purpose (Windows PowerShell decodes BOM-less .ps1 as GBK; any
# non-ASCII literal here would be corrupted).
#
# WHY THIS SCRIPT EXISTS
# Manual deployment went wrong more than once during development:
#   * 'am force-stop' puts the app in the STOPPED state and it will NOT
#     auto-restart -- the accessibility service silently stays dead.
#   * editing enabled_accessibility_services by hand easily drops OTHER
#     services (ChatGPT / hihonor.awareness are also enabled on this phone).
#   * reinstalling silently wipes the app's SharedPreferences.
# This script does those steps with backups, verification and an explicit
# rollback path instead of ad-hoc shell commands.
#
# Usage:
#   .\deploy-and-measure.ps1 -Action status
#   .\deploy-and-measure.ps1 -Action deploy
#   .\deploy-and-measure.ps1 -Action enable
#   .\deploy-and-measure.ps1 -Action measure -Minutes 6
#   .\deploy-and-measure.ps1 -Action rollback
#   .\deploy-and-measure.ps1 -Action restore-a11y
#
# Typical sequence:
#   status -> deploy -> enable -> measure -> (compare with the baseline run)
#   rollback  (restores the frozen v0.16.1 release APK)

param(
  [ValidateSet('status', 'deploy', 'enable', 'disable', 'measure', 'rollback', 'restore-a11y', 'save-a11y')]
  [string]$Action = 'status',

  [string]$Package = 'com.airgesture.sweep',
  [string]$ServiceShort = 'com.airgesture.sweep/com.airgesture.sweep.SweepAccessibilityService',

  # Built APK to install.
  [string]$Apk = '',

  # Frozen release, used by -Action rollback.
  [string]$ReleaseApk = '',

  # Where the a11y service list backup is kept.
  [string]$StateDir = '',

  [int]$Minutes = 6,
  [int]$IntervalSec = 6,
  [string]$Note = 'deployed'
)

$ErrorActionPreference = 'Stop'
$Adb = 'E:\android-toolchain\sdk\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }

$androidDir = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Apk)) {
  $Apk = Join-Path $androidDir 'app\build\outputs\apk\debug\app-debug.apk'
}
if ([string]::IsNullOrWhiteSpace($ReleaseApk)) {
  $ReleaseApk = Join-Path $androidDir 'release\airgesture-v0.16.1-release.apk'
}
if ([string]::IsNullOrWhiteSpace($StateDir)) {
  $StateDir = Join-Path $androidDir 'docs\deploy-state'
}
$MeasureScript = Join-Path $PSScriptRoot 'measure.ps1'
$A11yFile = Join-Path $StateDir 'a11y-services-before.txt'

function Invoke-Adb {
  param([string[]]$AdbArgs, [switch]$Quiet, [switch]$AllowFail)
  $saved = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $Adb @AdbArgs 2>&1
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $saved
  }
  $text = ($out | ForEach-Object { "$_" }) -join "`n"
  if (-not $AllowFail -and $code -ne 0 -and -not $Quiet) {
    Write-Warning ("adb {0} -> exit {1}: {2}" -f ($AdbArgs -join ' '), $code, $text)
  }
  return $text
}

function Assert-Device {
  $out = Invoke-Adb -AdbArgs @('devices') -Quiet
  $lines = $out -split "`n" | Where-Object { $_ -match "\tdevice\b" }
  if (@($lines).Count -lt 1) {
    throw "NO_DEVICE: plug the phone in and enable USB debugging. Nothing here works without a device."
  }
}

function Get-A11yList {
  return (Invoke-Adb -AdbArgs @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') -Quiet).Trim()
}

function Set-A11yList {
  param([string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq 'null') {
    Invoke-Adb -AdbArgs @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') -Quiet | Out-Null
  } else {
    Invoke-Adb -AdbArgs @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $Value) -Quiet | Out-Null
  }
}

function Get-InstalledVersion {
  $d = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'package', $Package) -Quiet
  $m = [regex]::Match($d, 'versionName=([^\s]+)')
  if ($m.Success) { return $m.Groups[1].Value }
  return ''
}

function Get-PkgPid {
  $t = Invoke-Adb -AdbArgs @('shell', 'pidof', $Package) -Quiet
  return ($t -split '\s+' | Select-Object -First 1)
}

function Save-A11yBackup {
  New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
  $cur = Get-A11yList
  $cur | Set-Content -Path $A11yFile -Encoding UTF8
  Write-Output ("a11y list backed up -> {0}" -f $A11yFile)
  Write-Output ("  value: {0}" -f $cur)
}

# ------------------------------------------------------------------ actions

function Action-Status {
  Assert-Device
  Write-Output (Invoke-Adb -AdbArgs @('devices', '-l'))
  $ver = Get-InstalledVersion
  $pidNow = Get-PkgPid
  $a11y = Get-A11yList
  Write-Output ''
  Write-Output ("installed version : {0}" -f $(if ($ver) { $ver } else { '(not installed)' }))
  Write-Output ("process pid       : {0}" -f $(if ($pidNow) { $pidNow } else { '(not running)' }))
  Write-Output ("our service on?   : {0}" -f ($a11y -like "*$ServiceShort*"))
  Write-Output ("all a11y services :")
  foreach ($s in ($a11y -split ':')) { if ($s.Trim()) { Write-Output ("    " + $s.Trim()) } }
  $cam = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'package', $Package) -Quiet
  Write-Output ("CAMERA granted?   : {0}" -f ($cam -match 'android\.permission\.CAMERA:\s*granted=true'))
  if (Test-Path $A11yFile) {
    Write-Output ("a11y backup       : {0}" -f ((Get-Content $A11yFile -Encoding UTF8) -join ''))
  } else {
    Write-Output 'a11y backup       : (none yet - run -Action save-a11y)'
  }
  Write-Output ''
  Write-Output 'NOTE: the app is NOT auto-started after a reboot or after being'
  Write-Output '      force-stopped; re-enable it in system settings, or use'
  Write-Output '      -Action enable (which keeps the other services intact).'
}

function Action-SaveA11y {
  Assert-Device
  Save-A11yBackup
}

function Action-RestoreA11y {
  Assert-Device
  if (-not (Test-Path $A11yFile)) { throw "no backup at $A11yFile - run -Action save-a11y first" }
  $val = (Get-Content $A11yFile -Encoding UTF8) -join ''
  Set-A11yList $val
  Start-Sleep -Seconds 2
  Write-Output 'restored a11y service list from backup:'
  Write-Output ("  now: {0}" -f (Get-A11yList))
}

function Action-Enable {
  Assert-Device
  $cur = Get-A11yList
  if ($cur -like "*$ServiceShort*") {
    Write-Output 'our service is already in the list - nothing to do.'
    return
  }
  # Back up before the first modification so it can always be undone.
  if (-not (Test-Path $A11yFile)) { Save-A11yBackup | Out-Null }
  $new = if ([string]::IsNullOrWhiteSpace($cur) -or $cur -eq 'null') { $ServiceShort } else { "$cur`:$ServiceShort" }
  Write-Output 'ADDING our service (existing entries preserved):'
  Write-Output ("  before: {0}" -f $cur)
  Write-Output ("  after : {0}" -f $new)
  Set-A11yList $new
  Start-Sleep -Seconds 3
  $now = Get-A11yList
  if ($now -notlike "*$ServiceShort*") {
    throw "failed to enable the service - the list did not take. Current: $now"
  }
  Write-Output 'enabled. waiting up to 20s for the process + model...'
  for ($i = 1; $i -le 10; $i++) {
    Start-Sleep -Seconds 2
    $p = Get-PkgPid
    if ($p) { Write-Output ("  process up: pid=$p"); break }
    if ($i -eq 10) { Write-Output '  WARNING: process did not appear within 20s' }
  }
}

function Action-Disable {
  Assert-Device
  if (-not (Test-Path $A11yFile)) { Save-A11yBackup | Out-Null }
  $cur = Get-A11yList
  $kept = ($cur -split ':' | Where-Object { $_.Trim() -ne '' -and $_ -ne $ServiceShort }) -join ':'
  Write-Output 'REMOVING our service (other entries preserved):'
  Write-Output ("  before: {0}" -f $cur)
  Write-Output ("  after : {0}" -f $kept)
  Set-A11yList $kept
  Start-Sleep -Seconds 2
  Write-Output ("  now: {0}" -f (Get-A11yList))
}

function Action-Deploy {
  Assert-Device
  if (-not (Test-Path $Apk)) { throw "APK not found: $Apk  (run android\tools\build.ps1 first)" }
  $apkItem = Get-Item $Apk
  $hash = (Get-FileHash $Apk -Algorithm SHA256).Hash
  Write-Output ("installing {0}  ({1:N1} MB)" -f $apkItem.Name, ($apkItem.Length / 1MB))
  Write-Output ("  sha256: {0}" -f $hash)
  $before = Get-InstalledVersion
  Write-Output ("  version before: {0}" -f $(if ($before) { $before } else { '(none)' }))
  Write-Output ''
  Write-Output 'WARNING: this replaces the installed app. App data (SharedPreferences,'
  Write-Output '         including the calibrated angle offset) will be LOST because the'
  Write-Output '         debug build is signed differently from the release build.'
  Write-Output '         Re-check the up/down/left/right orientation after installing.'
  Write-Output ''

  # -r keeps the data if signatures match; -d allows a version downgrade.
  # No force-stop: that would leave the app STOPPED and the service dead.
  $r = Invoke-Adb -AdbArgs @('install', '-r', '-d', $Apk) -AllowFail
  Write-Output $r
  if ($r -notmatch 'Success') {
    throw "install did not report Success - do NOT assume it worked. Output above."
  }
  Write-Output ''
  Write-Output ("  version after: {0}" -f (Get-InstalledVersion))
  Write-Output ''
  Write-Output 'NEXT (do these in order):'
  Write-Output '  1) .\deploy-and-measure.ps1 -Action enable'
  Write-Output '  2) grant camera if needed:  adb shell pm grant com.airgesture.sweep android.permission.CAMERA'
  Write-Output '  3) verify orientation with your hand (angle offset may have reset to +90)'
  Write-Output '  4) .\deploy-and-measure.ps1 -Action measure -Minutes 6'
}

function Action-Measure {
  Assert-Device
  if (-not (Test-Path $MeasureScript)) { throw "measure.ps1 not found at $MeasureScript" }
  $ver = Get-InstalledVersion
  $cond = "deployed v$ver via deploy-and-measure.ps1; hand NOT in frame; screen ON; note that USB power confounds battery figures"
  Write-Output '--- snapshot ---'
  & $MeasureScript -Action snapshot -Note $Note -Condition $cond
  Write-Output ''
  Write-Output '--- record ---'
  & $MeasureScript -Action record -Minutes $Minutes -IntervalSec $IntervalSec -Note $Note -Condition $cond
  Write-Output ''
  Write-Output '--- thread attribution ---'
  & $MeasureScript -Action threads -Samples 4 -Note $Note -Condition $cond
  Write-Output ''
  Write-Output 'Interpretation reminder:'
  Write-Output '  * with the downshift active, process CPU should show a SAWTOOTH (mostly idle,'
  Write-Output '    periodic short spikes), not a steady ~97%.'
  Write-Output '  * -Action threads reports PEAK per thread, so drishti_gl_runn / HeapTaskDaemon'
  Write-Output '    peaks may stay high while the AVERAGE (from record) drops sharply.'
  Write-Output '  * battery/power figures measured while USB-powered are NOT valid.'
}

function Action-Rollback {
  Assert-Device
  if (-not (Test-Path $ReleaseApk)) { throw "frozen release APK not found: $ReleaseApk" }
  Write-Output 'Rolling back to the frozen v0.16.1 release build.'
  Write-Output '  This restores the app, but NOT any saved settings or the orientation value.'
  $r = Invoke-Adb -AdbArgs @('install', '-r', '-d', $ReleaseApk) -AllowFail
  Write-Output $r
  if ($r -notmatch 'Success') {
    Write-Output 'install -r failed (expected: signature mismatch between debug and release).'
    Write-Output 'Falling back to uninstall + install (app data will be lost).'
    Invoke-Adb -AdbArgs @('uninstall', $Package) -AllowFail | Out-Null
    $r2 = Invoke-Adb -AdbArgs @('install', $ReleaseApk) -AllowFail
    Write-Output $r2
    if ($r2 -notmatch 'Success') { throw 'rollback failed - investigate manually' }
  }
  Write-Output ''
  Write-Output ("  version now: {0}" -f (Get-InstalledVersion))
  Write-Output 'Re-enable the accessibility service in system settings, and re-check orientation.'
}

switch ($Action) {
  'status'       { Action-Status }
  'save-a11y'    { Action-SaveA11y }
  'restore-a11y' { Action-RestoreA11y }
  'enable'       { Action-Enable }
  'disable'      { Action-Disable }
  'deploy'       { Action-Deploy }
  'measure'      { Action-Measure }
  'rollback'     { Action-Rollback }
}
