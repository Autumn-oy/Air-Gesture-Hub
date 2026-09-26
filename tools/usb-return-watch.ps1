# USB return watcher for an unplugged-battery drain run.
#
# ASCII-only on purpose: Windows PowerShell 5.1 decodes BOM-less .ps1 files as
# GBK, so non-ASCII literals in this file would be corrupted (same reason
# build.ps1 / measure.ps1 stay ASCII-only).
#
# Why this exists
# ---------------
# A drain measurement MUST be made unplugged (USB power changes the power
# profile by ~7x), but adb over USB dies the moment the cable comes out. The
# run therefore has two blind spots:
#   1. when exactly the phone was unplugged (the start of the stats window), and
#   2. how long after the phone was plugged back in the "after" evidence got
#      captured (batterystats starts folding in charging right away, and the
#      since-last-charge window is reset ~15 min after the battery hits FULL).
# This watcher removes blind spot 2 entirely: the instant the device reappears
# on adb it captures battery / batterystats --charged / media.camera straight
# into the run directory, before a human or an agent could react.
#
# SERIAL PINNING IS NOT OPTIONAL
# ------------------------------
# The first version of this script counted *any* device in `adb devices`. On
# 2026-09-25 that silently captured the WRONG PHONE: the device under test
# (HUAWEI ELS-AN00 / P40 Pro) was still unplugged, while an unrelated test
# device (HONOR AMG-AN00) turned up over WiFi adb - and its battery/batterystats
# were written into the run directory as if they were the P40's. Every capture
# here is therefore addressed with `-s <Serial>`, and if -Serial is not supplied
# the script REFUSES to run rather than guessing. The captured device identity
# is written to device_after.txt so the evidence identifies itself.
#
# It only runs `adb devices` on the PC side, so while the phone is unplugged it
# costs the phone exactly nothing (there is no device to talk to).
#
# Usage:
#   .\usb-return-watch.ps1 -Serial <device serial from `adb devices`> `
#       -Log <run dir>\usb_watch.log -CaptureDir <run dir>
#
# Exits when either
#   * the pinned device comes back after an absence of at least -MinAbsenceSec
#     (a real unplug/replug cycle; shorter absences are logged and ignored), or
#   * -MaxHours have elapsed (safety net: the phone was never unplugged, was
#     plugged into a different host, or was never plugged back in).
# Whenever the pinned device is present at exit, the raw "after" evidence is
# captured.

param(
  [string]$Adb = 'E:\android-toolchain\sdk\platform-tools\adb.exe',
  # adb serial of the device under test. REQUIRED.
  [string]$Serial = '',
  [string]$Log = '',
  [string]$CaptureDir = '',
  [int]$PollSec = 60,
  [double]$MaxHours = 9,
  [int]$MinAbsenceSec = 1800
)

$ErrorActionPreference = 'Continue'

if ([string]::IsNullOrWhiteSpace($Serial)) {
  throw "NO_SERIAL: -Serial is required. Refusing to auto-detect a device: on 2026-09-25 that captured the wrong phone's battery stats. Pass the adb serial of the device under test."
}
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }
if ([string]::IsNullOrWhiteSpace($Log)) { $Log = Join-Path $env:TEMP 'usb-return-watch.log' }
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Stamp { return (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') }

function Write-Watch {
  param([string]$Message)
  $line = "{0}  {1}" -f (Stamp), $Message
  Write-Output $line
  try { [IO.File]::AppendAllText($Log, $line + "`r`n", $script:utf8NoBom) } catch { }
}

# Raw "serial<TAB>state" lines, so presence can be pinned to one device.
function Get-DeviceLines {
  $out = & $Adb devices 2>&1 | Out-String
  return @(($out -split "`n") | Where-Object { $_ -match "\t(device|offline|unauthorized)\b" })
}

function Test-SerialPresent {
  $lines = Get-DeviceLines
  foreach ($l in $lines) {
    if ($l -match ('^\s*' + [regex]::Escape($Serial) + '\s+device\b')) { return $true }
  }
  return $false
}

function Get-OtherDevices {
  $lines = Get-DeviceLines
  return @($lines | Where-Object { $_ -notmatch ('^\s*' + [regex]::Escape($Serial) + '\s') })
}

function Save-AdbOut {
  param([string]$Name, [string[]]$AdbArgs)
  if ([string]::IsNullOrWhiteSpace($CaptureDir)) { return }
  try {
    $text = (& $Adb -s $Serial @AdbArgs 2>&1 | Out-String)
    $p = Join-Path $CaptureDir $Name
    [IO.File]::WriteAllText($p, $text, $script:utf8NoBom)
    Write-Watch ("  captured {0} ({1} bytes)" -f $Name, $text.Length)
  } catch {
    Write-Watch ("  CAPTURE FAILED {0}: {1}" -f $Name, $_.Exception.Message)
  }
}

function Capture-Now {
  param([string]$Reason)
  if ([string]::IsNullOrWhiteSpace($CaptureDir)) { return }
  Write-Watch ("capturing after-evidence (reason={0}) from serial {1} into {2}" -f $Reason, $Serial, $CaptureDir)
  # identity first: the evidence must identify which phone it came from
  Save-AdbOut 'device_after.txt'               @('shell', 'getprop', 'ro.product.model')
  Save-AdbOut 'device_serial_after.txt'        @('shell', 'getprop', 'ro.serialno')
  Save-AdbOut 'battery_after.txt'              @('shell', 'dumpsys', 'battery')
  Save-AdbOut 'batterystats_after_charged.txt' @('shell', 'dumpsys', 'batterystats', '--charged')
  Save-AdbOut 'media_camera_after.txt'         @('shell', 'dumpsys', 'media.camera')
  Save-AdbOut 'meminfo_after.txt'              @('shell', 'dumpsys', 'meminfo', 'com.airgesture.sweep')
  Save-AdbOut 'a11y_after.txt'                 @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
  Save-AdbOut 'version_after.txt'              @('shell', 'dumpsys', 'package', 'com.airgesture.sweep')
  $b = & $Adb -s $Serial shell dumpsys battery 2>&1 | Out-String
  $lv = [regex]::Match($b, '(?m)^\s*level:\s*(-?\d+)')
  $usb = [regex]::Match($b, 'USB powered:\s*(true|false)')
  $model = (& $Adb -s $Serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
  Write-Watch ("  model={0} level={1} usbPowered={2}" -f $model, $lv.Groups[1].Value, $usb.Groups[1].Value)
}

$start = Get-Date
$present = Test-SerialPresent
Write-Watch ("START serial={0} adb={1} present={2} poll={3}s maxHours={4} minAbsenceSec={5}" -f $Serial, $Adb, $present, $PollSec, $MaxHours, $MinAbsenceSec)
$others = Get-OtherDevices
if ($others.Count -gt 0) {
  Write-Watch ("NOTE: {0} other adb device(s) connected - ignored, this run is pinned to {1}:" -f $others.Count, $Serial)
  foreach ($o in $others) { Write-Watch ("  other: {0}" -f $o.Trim()) }
}

$absentSince = $null
$exitReason = ''

while ($true) {
  Start-Sleep -Seconds $PollSec
  $isPresent = Test-SerialPresent
  $now = Get-Date
  $elapsedH = ($now - $start).TotalHours

  if ($isPresent) {
    if ($null -ne $absentSince) {
      $absentMin = [Math]::Round((($now - $absentSince).TotalMinutes), 1)
      Write-Watch ("PRESENT again after {0} min absence" -f $absentMin)
      if ((($now - $absentSince).TotalSeconds) -ge $MinAbsenceSec) {
        $exitReason = ("returned elapsed={0}h absent={1}min" -f [Math]::Round($elapsedH, 2), $absentMin)
        break
      }
      Write-Watch '  ignored: absence shorter than MinAbsenceSec (quick replug / false start)'
      $absentSince = $null
    }
  } else {
    if ($null -eq $absentSince) {
      $absentSince = $now
      Write-Watch ("ABSENT (unplugged) - the battery drain window starts here")
    }
  }

  if ($elapsedH -ge $MaxHours) {
    $exitReason = ("maxhours elapsed={0}h present={1}" -f [Math]::Round($elapsedH, 2), $isPresent)
    break
  }
}

if ($exitReason -eq '') { $exitReason = 'loop-exit' }
Write-Watch ("EXIT reason={0}" -f $exitReason)

if (Test-SerialPresent) { Capture-Now -Reason $exitReason }
else { Write-Watch ("pinned device {0} is still absent at exit - nothing to capture" -f $Serial) }

Write-Watch 'WATCHER DONE'
