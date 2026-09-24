# AirGestureSweep power / memory measurement harness.
#
# ASCII-only content on purpose: Windows PowerShell decodes BOM-less .ps1 files
# as GBK; non-ASCII literals in this file would be corrupted (same reason
# build.ps1 stays ASCII-only).
#
# Every number reported by this script comes from adb output that is ALSO
# written verbatim into a run directory, so any figure can be re-checked by
# hand and any run can be repeated.
#
#   .\measure.ps1 -Action check
#   .\measure.ps1 -Action snapshot  -Note "baseline-idle"
#   .\measure.ps1 -Action record    -Minutes 5  -IntervalSec 10 -Note "baseline-idle"
#   .\measure.ps1 -Action drain     -Minutes 480 -IntervalSec 60 -Note "baseline-overnight"
#   .\measure.ps1 -Action all       -Minutes 5
#   .\measure.ps1 -Action compare   -A <dirA> -B <dirB>

param(
  [ValidateSet('check', 'info', 'snapshot', 'record', 'drain', 'drain-analysis', 'all', 'compare', 'list', 'threads')]
  [string]$Action = 'check',

  # Package under test.
  [string]$Package = 'com.airgesture.sweep',

  # Sampling window.
  [int]$Minutes = 5,
  [int]$IntervalSec = 10,

  # Number of top samples for -Action threads.
  [int]$Samples = 4,

  # Human label for this run; becomes part of the output directory name.
  [string]$Note = 'unlabelled',

  # Free-form description of the device state, stored next to the data.
  [string]$Condition = '',

  # compare: two run directories.
  [string]$A = '',
  [string]$B = '',

  # Where run directories are written (default: <workspace>\android\docs\power-runs).
  [string]$OutRoot = ''
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------------ plumbing

$Adb = 'E:\android-toolchain\sdk\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb" }

if ([string]::IsNullOrWhiteSpace($OutRoot)) {
  # tools\ -> android\ -> docs\power-runs
  $androidDir = Split-Path -Parent $PSScriptRoot
  $OutRoot = Join-Path $androidDir 'docs\power-runs'
}

function Invoke-Adb {
  param([string[]]$AdbArgs, [switch]$Quiet)
  # A failing native command writes to stderr, and with $ErrorActionPreference='Stop'
  # that becomes a fatal error. Command failures here are expected and are handled
  # by the caller (e.g. 'dumpsys charge' does not exist on every OEM build), so the
  # preference is relaxed for the duration of the call and restored afterwards.
  $saved = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $Adb @AdbArgs 2>&1
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $saved
  }
  $text = ($out | ForEach-Object { "$_" }) -join "`n"
  if (-not $Quiet -and $code -ne 0) {
    Write-Warning ("adb {0} -> exit {1}: {2}" -f ($AdbArgs -join ' '), $code, $text)
  }
  return $text
}

function Get-DeviceCount {
  $out = Invoke-Adb -AdbArgs @('devices') -Quiet
  # NOTE: the trailing fields vary by adb version ("device", "device product:.. transport_id:2"),
  # so anchor on the \t separator, not on end-of-line.
  $lines = $out -split "`n" | Where-Object { $_ -match "\tdevice\b" }
  return @($lines).Count
}

function Assert-Device {
  if ((Get-DeviceCount) -lt 1) {
    throw "NO_DEVICE: 'adb devices' shows no authorised device. Plug the phone in and enable USB debugging (accept the RSA prompt on screen). No measurement is possible without it."
  }
}

function Get-Stamp { (Get-Date).ToString('yyyyMMdd-HHmmss') }

function New-RunDir {
  param([string]$Label)
  $safe = ($Label -replace '[^A-Za-z0-9._-]', '_')
  $dir = Join-Path $OutRoot ("{0}_{1}" -f (Get-Stamp), $safe)
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  return $dir
}

# Write raw adb output verbatim. -Append so repeated calls accumulate.
function Save-Raw {
  param([string]$Dir, [string]$Name, [string]$Text)
  $p = Join-Path $Dir $Name
  $Text | Set-Content -Path $p -Encoding UTF8
  return $p
}

function Get-Prop {
  param([string]$Text, [string]$Pattern)
  $m = [regex]::Match($Text, $Pattern)
  if ($m.Success) { return $m.Groups[1].Value.Trim() }
  return ''
}

# ------------------------------------------------------------------ collectors

# Battery level / status. Values here are the ones a human reads off the phone.
#
# 'Plugged' is derived from the powered flags rather than from a 'plugged:' line:
# this device's dumpsys battery prints "AC powered / USB powered / Wireless
# powered" and NOT "plugged:", so matching 'plugged:' silently yields empty and
# the drain analysis would fail to notice the phone was charging -- exactly the
# failure mode it exists to prevent. Any true flag counts as plugged.
function Get-BatterySnapshot {
  Assert-Device
  $d = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'battery')
  # 'dumpsys charge' exists on AOSP but is absent on some OEM builds (HONOR) —
  # advisory only, must never break a measurement.
  $chg = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'charge') -Quiet

  $pluggedFlags = @('AC powered', 'USB powered', 'Wireless powered', 'Dock powered')
  $plugged = '0'
  foreach ($f in $pluggedFlags) {
    if ((Get-Prop $d ($f + ':\s*(true|false)')) -eq 'true') { $plugged = '1'; break }
  }

  # ★ Anchor the field patterns to the start of a line. Unanchored matching is how
  #   'voltage:' silently returns the 'Max charging voltage' line (5000000) instead
  #   of the real pack voltage, and it lets any stray line containing the field
  #   name hijack the value. Both fields are single-line facts in dumpsys battery.
  $level = Get-Prop $d '(?m)^\s*level:\s*(-?\d+)'
  $volt  = Get-Prop $d '(?m)^\s*voltage:\s*(-?\d+)'

  # Sanity gate: a battery level outside 0..100 is not a level, it is a parsing
  # artefact. Recording it silently would poison the drain fit, so refuse it here
  # where the cause is still obvious.
  if ($level -ne '' -and ([int]$level -lt 0 -or [int]$level -gt 100)) {
    Write-Warning ("implausible battery level parsed: '{0}' - treating as unknown (raw length {1})" -f $level, $d.Length)
    $level = ''
  }

  return [pscustomobject]@{
    Time          = (Get-Date).ToString('s')
    Level         = $level
    Status        = Get-Prop $d '(?m)^\s*status:\s*(-?\d+)'
    Plugged       = $plugged
    AcPowered     = Get-Prop $d 'AC powered:\s*(true|false)'
    UsbPowered    = Get-Prop $d 'USB powered:\s*(true|false)'
    Wireless      = Get-Prop $d 'Wireless powered:\s*(true|false)'
    Voltage_mV    = $volt
    Temperature   = Get-Prop $d '(?m)^\s*temperature:\s*(-?\d+)'
    ChargeCounter = Get-Prop $d '(?m)^\s*Charge counter:\s*(-?\d+)'
    Raw           = $d
    RawCharge     = $chg
  }
}

# batterystats is cumulative since boot, so ALWAYS pair with -BatteryReset.
function Reset-BatteryStats {
  Assert-Device
  return Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'batterystats', '--reset')
}

# Everything in the UID block for our package: this is where camera/sensor/cpu
# attribution lives. Returns the raw block plus a few parsed lines.
function Get-UidStats {
  param([string]$Pkg)
  # -v switches batterystats to VERBOSE, which is what actually emits the
  # per-UID DETAIL block (with the CPU:/Sensor:/Camera: attribution). Verified
  # against real device output: without -v that block is simply absent, and the
  # header casing differs between the two sections (summary 'UID u0aNNN:',
  # detail 'Uid u0aNNN:'), so the block slicing matches either case.
  $all = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'batterystats', '-v', $Pkg) -Quiet

  # Resolve the UID authoritatively. 'cmd package list packages -U' prints
  # "package:com.x.y uid:10123", which is stable across Android versions;
  # regex-scraping the batterystats header is only a fallback.
  $uid = ''
  $listTxt = Invoke-Adb -AdbArgs @('shell', 'cmd', 'package', 'list', 'packages', '-U', $Pkg) -Quiet
  $m0 = [regex]::Match($listTxt, ('package:' + [regex]::Escape($Pkg) + '\s+uid:(\d+)'))
  if ($m0.Success) {
    $uid = 'u0a' + ([int]$m0.Groups[1].Value - 10000)
  }

  # NOTE: batterystats writes the header as 'UID u0aNNN:' (upper case) in the
  # summary section but 'Uid u0aNNN:' (mixed case) in the per-uid detail section
  # — match case-insensitively or the whole block silently comes back empty.
  if ([string]::IsNullOrEmpty($uid)) {
    $m1 = [regex]::Match($all, '(?im)^\s*Uid\s+(u0a\d+):')
    if ($m1.Success) { $uid = $m1.Groups[1].Value }
  }

  $block = ''
  if (-not [string]::IsNullOrEmpty($uid)) {
    # Prefer the DETAILED block ('Uid u0aNNN:' with per-subsystem mA); fall back
    # to the SUMMARY block ('UID u0aNNN:') when the detail section is absent.
    $lines = $all -split "`n"
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
      if ($lines[$i] -match ('^\s*Uid\s+' + [regex]::Escape($uid) + ':')) { $start = $i; break }
    }
    if ($start -lt 0) {
      for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match ('^\s*UID\s+' + [regex]::Escape($uid) + ':')) { $start = $i; break }
      }
    }
    if ($start -ge 0) {
      $end = [Math]::Min($start + 120, $lines.Count - 1)
      for ($j = $start + 1; $j -le $end; $j++) {
        if ($lines[$j] -match '^\s+Uid\s+u0a\d+:' -or $lines[$j] -match '^\s+UID\s+u0a\d+:') { $end = $j - 1; break }
      }
      $block = ($lines[$start..$end]) -join "`n"
    }
  }

  function G([string]$p) { return (Get-Prop $block $p) }

  return [pscustomobject]@{
    Uid              = $uid
    EstimatedPower   = G 'Estimated power use \(mAh\):\s*([0-9.]+)'
    Capacity         = G 'Capacity:\s*([0-9.]+)'
    ComputedDrain    = G 'Computed drain:\s*([0-9.]+)'
    ActualDrain      = G 'Actual drain:\s*([0-9.]+)'
    CpuTimeMs        = G 'Cpu time:\s*([0-9.]+)'
    CpuPower         = G 'CPU:\s*([0-9.]+)'
    SensorPower      = G 'Sensor:\s*([0-9.]+)'
    CameraPower      = G 'Camera:\s*([0-9.]+)'
    GpsPower         = G 'GPS:\s*([0-9.]+)'
    WifiPower        = G 'Wifi:\s*([0-9.]+)'
    AudioPower       = G 'Audio:\s*([0-9.]+)'
    ForegroundMs     = G 'Foreground activities:\s*([0-9.]+)'
    ProcState        = G 'Proc state:\s*(.+)'
    RawBlock         = $block
    RawAll           = $all
  }
}

# RSS / native heap / graphics memory. Called repeatedly to detect a leak
# (monotonic rise) versus a stable high-water mark.
#
# IMPORTANT - 'dumpsys meminfo' has TWO sections with overlapping names, and only
# ONE of them uses a colon:
#
#     Native Heap    31236 ...   <- per-process table, NO colon
#         Gfx dev    10320 ...   <- per-process table, NO colon
#       GL mtrack    76480 ...   <- per-process table, NO colon
#    App Summary
#              Java Heap:    20784 ...   <- App Summary, WITH colon
#            Native Heap:    27212 ...
#               Graphics:    86800 ...
#
# 'Gfx dev' and 'GL mtrack' exist ONLY in the per-process table, so a pattern
# looking for 'Gfx dev:' never matches and silently returns empty. That bug was
# live in this script and was caught by an end-to-end mock run -- the captured
# real device output DOES contain 'Gfx dev    10320' and 'GL mtrack    76480'.
# Each value is now read from the section that actually carries it.
function Get-MemInfo {
  param([string]$Pkg)
  $t = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'meminfo', $Pkg)
  function G([string]$p) { return (Get-Prop $t $p) }
  # per-process table row: label, then >=6 numeric columns (no colon)
  function GT([string]$label) { return (Get-Prop $t ('(?m)^\s*' + [regex]::Escape($label) + '\s+(\d+)\s+\d+')) }
  # App Summary row: label, optional colon, then the Pss value
  function GS([string]$label) { return (Get-Prop $t ('(?m)^\s*' + [regex]::Escape($label) + ':?\s+(\d+)')) }

  return [pscustomobject]@{
    Time         = (Get-Date).ToString('s')
    TotalPss_kB  = G 'TOTAL PSS:\s*(\d+)'
    TotalRss_kB  = G 'TOTAL RSS:\s*(\d+)'
    JavaHeap_kB  = GS 'Java Heap'
    NativeHeap_kB= GS 'Native Heap'
    Code_kB      = GS 'Code'
    Stack_kB     = GS 'Stack'
    Graphics_kB  = GS 'Graphics'
    GfxDev_kB    = GT 'Gfx dev'
    GlMtrack_kB  = GT 'GL mtrack'
    EglMtrack_kB = GT 'EGL mtrack'
    Activities_kB= GS 'Activities'
    Views_kB     = GS 'Views'
    Raw          = $t
  }
}

# top gives short-interval CPU% for the whole process (all threads summed).
function Get-TopCpu {
  param([string]$Pkg)
  $t = Invoke-Adb -AdbArgs @('shell', 'top', '-b', '-n', '1', '-o', '%CPU,%MEM,RES,ARGS')
  $line = ($t -split "`n" | Where-Object { $_ -match [regex]::Escape($Pkg) } | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($line)) { return $null }
  $parts = ($line.Trim() -split '\s+')

  # top prints RES with a unit suffix ('220M', '3.7M', '1024K'); normalise to kB
  # so downstream rows are always comparable.
  $resKb = ''
  $rawRes = $parts[2]
  $mm = [regex]::Match($rawRes, '^([0-9.]+)([KMG]?)$')
  if ($mm.Success) {
    $v = [double]$mm.Groups[1].Value
    switch ($mm.Groups[2].Value) {
      'G'     { $resKb = [Math]::Round($v * 1024 * 1024, 0) }
      'M'     { $resKb = [Math]::Round($v * 1024, 0) }
      'K'     { $resKb = [Math]::Round($v, 0) }
      default { $resKb = [Math]::Round($v, 0) }
    }
  }

  return [pscustomobject]@{
    Time    = (Get-Date).ToString('s')
    CpuPct  = $parts[0]
    MemPct  = $parts[1]
    ResKB   = $resKb
    RawRes  = $rawRes
    Line    = $line.Trim()
  }
}

function Get-ProcStatus {
  param([string]$Pkg)
  $pidTxt = Invoke-Adb -AdbArgs @('shell', 'pidof', $Pkg) -Quiet
  $pidNum = ($pidTxt -split '\s+' | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($pidNum)) { return $null }
  $st = Invoke-Adb -AdbArgs @('shell', 'cat', "/proc/$pidNum/status") -Quiet
  return [pscustomobject]@{
    Pid       = $pidNum
    Threads   = Get-Prop $st 'Threads:\s*(\d+)'
    VmRSS_kB  = Get-Prop $st 'VmRSS:\s*(\d+)'
    VmHWM_kB  = Get-Prop $st 'VmHWM:\s*(\d+)'
    Raw       = $st
  }
}

function Get-FrameStats {
  param([string]$Pkg)
  # CameraX analysis frames + our own diag counters both live in logcat.
  $diag = Invoke-Adb -AdbArgs @('shell', 'logcat', '-d', '-s', 'SweepDiag:*') -Quiet
  return [pscustomobject]@{
    Raw = $diag
  }
}

function Get-DeviceInfo {
  Assert-Device
  return [pscustomobject]@{
    Model      = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.product.model') -Quiet
    Device     = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.product.device') -Quiet
    AndroidRel = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.build.version.release') -Quiet
    SdkInt     = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.build.version.sdk') -Quiet
    Abi        = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.product.cpu.abi') -Quiet
    SocModel   = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.soc.model') -Quiet
    SocManuf   = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.soc.manufacturer') -Quiet
    BuildFp    = Invoke-Adb -AdbArgs @('shell', 'getprop', 'ro.build.fingerprint') -Quiet
    Screen     = Invoke-Adb -AdbArgs @('shell', 'wm', 'size') -Quiet
    Density    = Invoke-Adb -AdbArgs @('shell', 'wm', 'density') -Quiet
    PkgVersion = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'package', $Package) -Quiet |
                   ForEach-Object { Get-Prop $_ 'versionName=([^\s]+)' }
    ServiceOn  = Invoke-Adb -AdbArgs @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') -Quiet
    ScreenState= Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'power') -Quiet |
                   ForEach-Object { Get-Prop $_ 'mWakefulness=(\w+)' }
    BatteryCap = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'battery') -Quiet |
                   ForEach-Object { Get-Prop $_ 'Charge counter:\s*(-?\d+)' }
  }
}

# ------------------------------------------------------------------ report

# Least-squares fit y = a + b*x. Returns slope b, intercept a and R^2 so a caller
# can tell a real trend from noise (R^2 near 0 = the fit explains nothing).
function Get-LinearFit {
  param([double[]]$X, [double[]]$Y)
  $n = $X.Count
  if ($n -lt 2 -or $Y.Count -ne $n) {
    return [pscustomobject]@{ N = $n; Slope = ''; Intercept = ''; RSq = '' }
  }
  $sx = 0.0; $sy = 0.0
  for ($i = 0; $i -lt $n; $i++) { $sx += $X[$i]; $sy += $Y[$i] }
  $mx = $sx / $n; $my = $sy / $n
  $sxx = 0.0; $sxy = 0.0; $syy = 0.0
  for ($i = 0; $i -lt $n; $i++) {
    $dx = $X[$i] - $mx; $dy = $Y[$i] - $my
    $sxx += $dx * $dx; $sxy += $dx * $dy; $syy += $dy * $dy
  }
  if ($sxx -le 0.0) { return [pscustomobject]@{ N = $n; Slope = ''; Intercept = ''; RSq = '' } }
  $slope = $sxy / $sxx
  $intercept = $my - $slope * $mx
  $rsq = if ($syy -le 0.0) { 0.0 } else { ($sxy * $sxy) / ($sxx * $syy) }
  return [pscustomobject]@{ N = $n; Slope = $slope; Intercept = $intercept; RSq = $rsq }
}

<#
.SYNOPSIS
Fit the battery drain rate of a run and predict the 8-hour figure.

.DESCRIPTION
Why fitting instead of end-minus-start: this device reports battery level as an
integer percent, so a single 8-hour run under 5% moves the level by only ~4
steps. A first/last difference therefore has ~1 percentage point of quantisation
error -- useless when the pass/fail line is exactly 5%.

Fitting level = a + b*t across all samples uses every step of the staircase and
gives a slope with a usable confidence signal (R^2). The script additionally
reports the observed total drop and the fitted drop, so a large disagreement
between them is visible rather than hidden.

IMPORTANT CAVEATS printed by this function:
  * draining must happen UNPLUGGED (USB power changes the power profile by ~7x)
  * do not charge or reboot during the window
  * screen state must be constant
#>
function Get-DrainAnalysis {
  param([string]$Dir)
  $rows = Join-Path $Dir 'samples.jsonl'
  if (-not (Test-Path $rows)) { throw "no samples.jsonl in $Dir" }
  $data = Get-Content $rows | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_ | ConvertFrom-Json }

  $pts = @()
  foreach ($r in $data) {
    if ($null -eq $r.level) { continue }
    if ("$($r.level)".Trim() -eq '') { continue }
    $pts += [pscustomobject]@{ t = [double]$r.t; level = [double]$r.level }
  }

  $batStart = Get-Content (Join-Path $Dir 'battery_start.json') -ErrorAction SilentlyContinue | ConvertFrom-Json
  $batEnd   = Get-Content (Join-Path $Dir 'battery_end.json') -ErrorAction SilentlyContinue | ConvertFrom-Json

  if ($pts.Count -lt 2) {
    return [pscustomobject]@{
      Dir = $Dir; Points = $pts.Count; Hours = ''; SlopePerHour = ''
      RSq = ''; ObservedDrop = ''; FittedDrop = ''; EightHourPct = ''; Verdict = 'INSUFFICIENT DATA'
      Plugged = if ($batStart) { $batStart.Plugged } else { '' }
    }
  }

  $t = @($pts | ForEach-Object { $_.t })
  $lv = @($pts | ForEach-Object { $_.level })
  $fit = Get-LinearFit -X $t -Y $lv

  $hours = ($t[-1] - $t[0]) / 3600.0
  $slopePerHour = if ($fit.Slope -eq '') { '' } else { [double]$fit.Slope * 3600.0 }
  $observed = $lv[0] - $lv[-1]
  $fitted   = if ($slopePerHour -eq '') { '' } else { -1.0 * $slopePerHour * $hours }
  $eight    = if ($slopePerHour -eq '') { '' } else { -1.0 * $slopePerHour * 8.0 }

  $plugged = if ($batStart) { $batStart.Plugged } else { '' }
  $verdict = 'UNKNOWN'
  if ($plugged -eq '1' -or $plugged -eq '2' -or $plugged -eq '4' -or $plugged -eq '8') {
    $verdict = 'INVALID (device was charging - power figures are not usable)'
  } elseif ($eight -ne '') {
    $verdict = if ($eight -lt 5.0) { 'PASS (<5% per 8h, fitted)' } else { 'FAIL (>=5% per 8h, fitted)' }
  }

  return [pscustomobject]@{
    Dir            = $Dir
    Points         = $pts.Count
    Hours          = [Math]::Round($hours, 3)
    SlopePerHour   = if ($slopePerHour -eq '') { '' } else { [Math]::Round($slopePerHour, 5) }
    RSq            = if ($fit.RSq -eq '') { '' } else { [Math]::Round($fit.RSq, 4) }
    ObservedDrop   = [Math]::Round($observed, 1)
    FittedDrop     = if ($fitted -eq '') { '' } else { [Math]::Round($fitted, 2) }
    EightHourPct   = if ($eight -eq '') { '' } else { [Math]::Round($eight, 2) }
    Verdict        = $verdict
    Plugged        = $plugged
  }
}

function Show-DrainAnalysis {
  param([string]$Dir)
  $d = Get-DrainAnalysis -Dir $Dir
  Write-Output ''
  Write-Output '--- DRAIN ANALYSIS (fitted, not endpoint-differenced) ---'
  Write-Output ("  run dir            : {0}" -f $d.Dir)
  Write-Output ("  battery samples    : {0} over {1} h" -f $d.Points, $d.Hours)
  Write-Output ("  fitted slope       : {0} %/h" -f $d.SlopePerHour)
  Write-Output ("  fit R^2            : {0}" -f $d.RSq)
  Write-Output ("  observed drop      : {0} %   (endpoint difference)" -f $d.ObservedDrop)
  Write-Output ("  fitted drop        : {0} %   (over the same window)" -f $d.FittedDrop)
  Write-Output ("  => extrapolated 8h : {0} %" -f $d.EightHourPct)
  Write-Output ("  plugged            : {0}" -f $d.Plugged)
  Write-Output ("  VERDICT            : {0}" -f $d.Verdict)
  if ($d.RSq -ne '' -and [double]$d.RSq -lt 0.5) {
    Write-Output '  WARNING: R^2 < 0.5 - the drain is not a clean line; treat the extrapolation as weak.'
    Write-Output '           Usual causes: screen turned on/off, another app woke up, or the phone was moved.'
  }
  Write-Output '  Reminder: valid only if the phone was UNPLUGGED, not rebooted, and the screen state was constant.'
}

# Pull every *.json row file back and summarize.
function Get-RunSummary {
  param([string]$Dir)
  $rows = Join-Path $Dir 'samples.jsonl'
  if (-not (Test-Path $rows)) { throw "no samples.jsonl in $Dir" }
  $data = Get-Content $rows | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_ | ConvertFrom-Json }

  $cpu = @($data | Where-Object { $null -ne $_.cpu -and $_.cpu -ne '' } | ForEach-Object { [double]$_.cpu })
  $rss = @($data | Where-Object { $null -ne $_.rssKb -and $_.rssKb -ne '' } | ForEach-Object { [double]$_.rssKb })
  $nat = @($data | Where-Object { $null -ne $_.nativeKb -and $_.nativeKb -ne '' } | ForEach-Object { [double]$_.nativeKb })
  $gfx = @($data | Where-Object { $null -ne $_.gfxKb -and $_.gfxKb -ne '' } | ForEach-Object { [double]$_.gfxKb })

  function Stat($arr) {
    if ($arr.Count -eq 0) { return [pscustomobject]@{ N = 0; Min = ''; Max = ''; Avg = ''; First = ''; Last = ''; Delta = '' } }
    $sorted = $arr | Sort-Object
    return [pscustomobject]@{
      N     = $arr.Count
      Min   = [Math]::Round($sorted[0], 1)
      Max   = [Math]::Round($sorted[-1], 1)
      Avg   = [Math]::Round((($arr | Measure-Object -Average).Average), 1)
      First = [Math]::Round($arr[0], 1)
      Last  = [Math]::Round($arr[-1], 1)
      Delta = [Math]::Round($arr[-1] - $arr[0], 1)
    }
  }

  $batStart = Get-Content (Join-Path $Dir 'battery_start.json') -ErrorAction SilentlyContinue | ConvertFrom-Json
  $batEnd   = Get-Content (Join-Path $Dir 'battery_end.json')   -ErrorAction SilentlyContinue | ConvertFrom-Json

  return [pscustomobject]@{
    Dir        = $Dir
    Note       = (Get-Content (Join-Path $Dir 'note.txt') -ErrorAction SilentlyContinue) -join ' '
    Samples    = $data.Count
    CpuPct     = Stat $cpu
    RssKb      = Stat $rss
    NativeKb   = Stat $nat
    GfxKb      = Stat $gfx
    BatteryStart = $batStart
    BatteryEnd   = $batEnd
    LevelDrop    = if ($batStart -and $batEnd) { [int]$batStart.Level - [int]$batEnd.Level } else { '' }
  }
}

function Show-Summary {
  param($S)
  Write-Output ("=" * 78)
  Write-Output ("RUN   : {0}" -f $S.Dir)
  Write-Output ("NOTE  : {0}" -f $S.Note)
  Write-Output ("SAMPLES: {0}" -f $S.Samples)
  Write-Output ("CPU %   : avg {0}  min {1}  max {2}" -f $S.CpuPct.Avg, $S.CpuPct.Min, $S.CpuPct.Max)
  Write-Output ("RSS kB  : avg {0}  min {1}  max {2}  first {3} -> last {4} (delta {5})" -f `
    $S.RssKb.Avg, $S.RssKb.Min, $S.RssKb.Max, $S.RssKb.First, $S.RssKb.Last, $S.RssKb.Delta)
  Write-Output ("Native kB: avg {0}  min {1}  max {2}  first {3} -> last {4} (delta {5})" -f `
    $S.NativeKb.Avg, $S.NativeKb.Min, $S.NativeKb.Max, $S.NativeKb.First, $S.NativeKb.Last, $S.NativeKb.Delta)
  # Graphics memory is reported two different ways depending on OS version/OEM:
  # newer builds print 'Graphics:' (a Graphics row) and no longer emit 'Gfx dev:'.
  # When a series is empty the Stat() values are empty strings, and printing them
  # yields a useless 'avg   min   max' line — say 'n/a' instead so the absence is
  # visible rather than looking like a formatting bug.
  $gfxTxt = if ($S.GfxKb.N -eq 0) { 'n/a (this device reports Graphics, not Gfx dev)' }
             else { "avg {0}  min {1}  max {2}" -f $S.GfxKb.Avg, $S.GfxKb.Min, $S.GfxKb.Max }
  Write-Output ("Gfx kB  : {0}" -f $gfxTxt)
  if ($S.BatteryStart -and $S.BatteryEnd) {
    Write-Output ("BATTERY : {0}% -> {1}%  (drop {2} points), charge counter {3} -> {4}" -f `
      $S.BatteryStart.Level, $S.BatteryEnd.Level, $S.LevelDrop,
      $S.BatteryStart.ChargeCounter, $S.BatteryEnd.ChargeCounter)
  }
  Write-Output ("=" * 78)
}

# ------------------------------------------------------------------ actions

function Action-Check {
  Assert-Device
  Write-Output (Invoke-Adb -AdbArgs @('devices', '-l'))
  $info = Get-DeviceInfo
  $info | Format-List | Out-String | Write-Output
}

function Action-Snapshot {
  param([string]$Dir)
  Assert-Device
  $info = Get-DeviceInfo
  $bat  = Get-BatterySnapshot
  $mem  = Get-MemInfo $Package
  $top  = Get-TopCpu $Package
  $proc = Get-ProcStatus $Package
  $uid  = Get-UidStats $Package

  Save-Raw $Dir 'device.json'  ($info | ConvertTo-Json -Depth 4)
  Save-Raw $Dir 'battery.json' ($bat  | ConvertTo-Json -Depth 3)
  Save-Raw $Dir 'meminfo.txt'  $mem.Raw
  Save-Raw $Dir 'uidstats.txt' $uid.RawBlock
  Save-Raw $Dir 'batterystats_full.txt' $uid.RawAll
  if ($proc) { Save-Raw $Dir 'procstatus.txt' $proc.Raw }
  Save-Raw $Dir 'logcat_diag.txt' (Invoke-Adb -AdbArgs @('shell', 'logcat', '-d', '-s', 'SweepDiag:*', 'SweepService:*') -Quiet)

  Get-BatterySnapshot | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $Dir 'battery_start.json') -Encoding UTF8

  Write-Output '--- device ---'
  $info | Format-List | Out-String | Write-Output
  Write-Output '--- battery ---'
  $bat | Select-Object Level, Status, Plugged, Voltage_mV, Temperature, ChargeCounter | Format-List | Out-String | Write-Output
  Write-Output '--- meminfo (key lines) ---'
  Write-Output ("TOTAL PSS   = {0} kB" -f $mem.TotalPss_kB)
  Write-Output ("TOTAL RSS   = {0} kB" -f $mem.TotalRss_kB)
  Write-Output ("Java Heap   = {0} kB" -f $mem.JavaHeap_kB)
  Write-Output ("Native Heap = {0} kB" -f $mem.NativeHeap_kB)
  Write-Output ("Graphics    = {0} kB" -f $mem.Graphics_kB)
  Write-Output ("Gfx dev     = {0} kB" -f $mem.GfxDev_kB)
  Write-Output ("EGL mtrack  = {0} kB" -f $mem.EglMtrack_kB)
  if ($top) { Write-Output ("top: {0}" -f $top.Line) } else { Write-Output 'top: package not running' }
  if ($proc) { Write-Output ("pid={0} threads={1} VmRSS={2} kB VmHWM={3} kB" -f $proc.Pid, $proc.Threads, $proc.VmRSS_kB, $proc.VmHWM_kB) }
  Write-Output '--- uid attribution ---'
  Write-Output ("uid={0} estimatedPower={1} mAh  cpu={2} mAh  sensor={3} mAh  camera={4} mAh  cpuTime={5} ms" -f `
    $uid.Uid, $uid.EstimatedPower, $uid.CpuPower, $uid.SensorPower, $uid.CameraPower, $uid.CpuTimeMs)
  Write-Output '--- service state ---'
  Write-Output ("enabled_accessibility_services = {0}" -f $info.ServiceOn)
}

function Action-Record {
  param([string]$Dir, [int]$Minutes, [int]$IntervalSec, [switch]$TrackBattery)

  Assert-Device
  $seconds = $Minutes * 60
  $total   = [Math]::Max(1, [int]($seconds / $IntervalSec))

  Write-Output ("recording {0} min, {1} samples every {2}s -> {3}" -f $Minutes, $total, $IntervalSec, $Dir)

  Get-BatterySnapshot | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $Dir 'battery_start.json') -Encoding UTF8
  Get-UidStats $Package | Select-Object -ExpandProperty RawAll |
    Set-Content (Join-Path $Dir 'batterystats_before.txt') -Encoding UTF8

  $rows = Join-Path $Dir 'samples.jsonl'
  if (Test-Path $rows) { Remove-Item $rows -Force }

  $sw = [Diagnostics.Stopwatch]::StartNew()
  # For long drain runs the battery level must be sampled ALONG WITH the timeline,
  # not just at the endpoints: with 1%-granularity a first/last difference is far
  # too noisy to resolve "under 5% in 8 hours" (see Get-DrainAnalysis).
  # The battery dump costs an extra adb round-trip, so sample it sparsely and
  # carry the last value forward.
  $lastLevel = ''; $lastVolt = ''; $lastTemp = ''; $lastCounter = ''
  for ($i = 1; $i -le $total; $i++) {
    $mem  = Get-MemInfo $Package
    $top  = Get-TopCpu $Package
    $proc = Get-ProcStatus $Package

    if ($TrackBattery -and ($i -eq 1 -or ($i % 10) -eq 0)) {
      $b = Get-BatterySnapshot
      $lastLevel = $b.Level; $lastVolt = $b.Voltage_mV
      $lastTemp = $b.Temperature; $lastCounter = $b.ChargeCounter
    }

    $row = [ordered]@{
      t         = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
      wall      = (Get-Date).ToString('HH:mm:ss')
      cpu       = if ($top) { $top.CpuPct } else { '' }
      memPct    = if ($top) { $top.MemPct } else { '' }
      rssKb     = if ($proc) { $proc.VmRSS_kB } else { $mem.TotalRss_kB }
      pssKb     = $mem.TotalPss_kB
      javaKb    = $mem.JavaHeap_kB
      nativeKb  = $mem.NativeHeap_kB
      gfxKb     = $mem.GfxDev_kB
      eglKb     = $mem.EglMtrack_kB
      graphicsKb= $mem.Graphics_kB
      codeKb    = $mem.Code_kB
      threads   = if ($proc) { $proc.Threads } else { '' }
      vmHwmKb   = if ($proc) { $proc.VmHWM_kB } else { '' }
      topLine   = if ($top) { $top.Line } else { '' }
    }
    if ($TrackBattery) {
      $row['level'] = $lastLevel
      $row['voltMv'] = $lastVolt
      $row['tempDeciC'] = $lastTemp
      $row['chargeCounter'] = $lastCounter
    }
    ($row | ConvertTo-Json -Compress) | Add-Content -Path $rows -Encoding UTF8

    if ($i % 6 -eq 0 -or $i -eq $total) {
      Write-Output ("  [{0}/{1}] t={2}s cpu={3}% rss={4}kB native={5}kB gfx={6}kB" -f `
        $i, $total, $row.t, $row.cpu, $row.rssKb, $row.nativeKb, $row.gfxKb)
    }
    Start-Sleep -Seconds $IntervalSec
  }

  $end = Get-BatterySnapshot
  $end | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $Dir 'battery_end.json') -Encoding UTF8
  Get-UidStats $Package | Select-Object -ExpandProperty RawAll |
    Set-Content (Join-Path $Dir 'batterystats_after.txt') -Encoding UTF8
  Get-MemInfo $Package | Select-Object -ExpandProperty Raw |
    Set-Content (Join-Path $Dir 'meminfo_end.txt') -Encoding UTF8
  Invoke-Adb -AdbArgs @('shell', 'logcat', '-d', '-s', 'SweepDiag:*', 'SweepService:*') -Quiet |
    Set-Content (Join-Path $Dir 'logcat_diag.txt') -Encoding UTF8

  Show-Summary (Get-RunSummary $Dir)
}

function Action-Drain {
  param([string]$Dir, [int]$Minutes, [int]$IntervalSec)
  # Same as record, but: resets batterystats first so the UID counters describe
  # exactly this window, and tracks the battery level per sample so the drain
  # rate can be fitted rather than differenced (see Get-DrainAnalysis).
  Assert-Device
  Write-Output 'resetting batterystats...'
  Reset-BatteryStats | Out-Null
  Start-Sleep -Seconds 2
  Action-Record -Dir $Dir -Minutes $Minutes -IntervalSec $IntervalSec -TrackBattery
  Write-Output ''
  Show-DrainAnalysis -Dir $Dir
}

function Action-All {
  Assert-Device
  Write-Output '--- quick look ---'
  Action-Check
  Write-Output ''
  Write-Output '--- snapshot ---'
  $d = New-RunDir $Note
  Action-Snapshot -Dir $d
  Write-Output ''
  Write-Output '--- record ---'
  Action-Record -Dir $d -Minutes $Minutes -IntervalSec $IntervalSec
  Write-Output ("RUN_DIR={0}" -f $d)
}

function Action-List {
  if (-not (Test-Path $OutRoot)) { Write-Output "no runs yet at $OutRoot"; return }
  Get-ChildItem $OutRoot -Directory | Sort-Object Name | ForEach-Object {
    $note = (Get-Content (Join-Path $_.FullName 'note.txt') -ErrorAction SilentlyContinue) -join ' '
    "{0}  {1}" -f $_.Name, $note
  }
}

function Action-Compare {
  if ([string]::IsNullOrWhiteSpace($A) -or [string]::IsNullOrWhiteSpace($B)) {
    throw 'compare needs -A <dir> -B <dir>'
  }
  $sa = Get-RunSummary $A
  $sb = Get-RunSummary $B
  Show-Summary $sa
  Show-Summary $sb
  Write-Output '--- DELTA (B - A) ---'
  function D($x, $y) { if ($x -eq '' -or $y -eq '') { return '' } else { return [Math]::Round(([double]$y - [double]$x), 1) } }
  Write-Output ("CPU avg %      : {0} -> {1}   delta {2}" -f $sa.CpuPct.Avg, $sb.CpuPct.Avg, (D $sa.CpuPct.Avg $sb.CpuPct.Avg))
  Write-Output ("RSS avg kB     : {0} -> {1}   delta {2}" -f $sa.RssKb.Avg, $sb.RssKb.Avg, (D $sa.RssKb.Avg $sb.RssKb.Avg))
  Write-Output ("Native avg kB  : {0} -> {1}   delta {2}" -f $sa.NativeKb.Avg, $sb.NativeKb.Avg, (D $sa.NativeKb.Avg $sb.NativeKb.Avg))
  Write-Output ("Gfx avg kB     : {0} -> {1}   delta {2}" -f $sa.GfxKb.Avg, $sb.GfxKb.Avg, (D $sa.GfxKb.Avg $sb.GfxKb.Avg))
  if ($sa.CpuPct.Avg -and $sb.CpuPct.Avg -and [double]$sa.CpuPct.Avg -ne 0) {
    $pct = (([double]$sa.CpuPct.Avg - [double]$sb.CpuPct.Avg) / [double]$sa.CpuPct.Avg) * 100
    Write-Output ("CPU reduction  : {0:N1}%" -f $pct)
  }
}

# ------------------------------------------------------------------ thread view

<#
.SYNOPSIS
Per-thread CPU attribution for the app process.

.DESCRIPTION
This is the single most informative measurement for this project. Sampled
process-level CPU told us "~100% of one core" but not WHICH subsystem is
responsible. The thread names answer that directly, because the attribution
falls out of the names:
  * drishti_gl_runn / Thread-N  -> MediaPipe inference + graph workers
  * HeapTaskDaemon              -> ART garbage collection
  * CXCP-* / CameraX-*          -> CameraX + camera HAL
  * pool-5-thread-1             -> our own ImageAnalysis executor
  * binder:*                    -> IPC
Because they are sibling threads, their %CPU values are additive, so they map
directly onto the process total.
#>
function Action-Threads {
  param([string]$Dir, [int]$Samples, [int]$IntervalSec)
  Assert-Device
  $pidTxt = Invoke-Adb -AdbArgs @('shell', 'pidof', $Package) -Quiet
  $procId = ($pidTxt -split '\s+' | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($procId)) {
    throw "package $Package is not running - cannot attribute threads"
  }
  Write-Output ("thread attribution for pid=$procId, {0} samples every {1}s" -f $Samples, $IntervalSec)

  # `top -H` prints the PROCESS's own row (PID column == $procId) alongside the
  # thread rows. Its CMD is the process name, not a thread, so it must be
  # filtered out or it shows up as a bogus 0.0% "thread".
  $procName = ''
  $pn = Invoke-Adb -AdbArgs @('shell', 'cat', "/proc/$procId/cmdline") -Quiet
  if (-not [string]::IsNullOrWhiteSpace($pn)) {
    $procName = ($pn -split "`0" | Select-Object -First 1).Trim()
  }

  $best = @{}
  $procRows = @()
  for ($i = 1; $i -le $Samples; $i++) {
    $raw = Invoke-Adb -AdbArgs @('shell', 'top', '-H', '-b', '-n', '1', '-o', '%CPU,TID,CMD', '-p', $procId) -Quiet
    foreach ($line in ($raw -split "`n")) {
      $m = [regex]::Match($line, '^\s*([0-9.]+)\s+(\d+)\s+(\S.*?)\s*$')
      if ($m.Success) {
        $cpu = [double]$m.Groups[1].Value
        $tid = $m.Groups[2].Value
        $name = $m.Groups[3].Value.Trim()
        # skip: the column header, the process's own row (TID == PID), and
        # anything whose name is the package/process name rather than a thread
        if ($name -eq 'CMD') { continue }
        if ($tid -eq $procId) { continue }
        if ($procName -ne '' -and ($name -eq $procName -or $Package.EndsWith($name))) { continue }
        if (-not $best.ContainsKey($name) -or $best[$name] -lt $cpu) { $best[$name] = $cpu }
      }
    }
    $praw = Invoke-Adb -AdbArgs @('shell', 'top', '-b', '-n', '1', '-o', '%CPU,%MEM,RES,ARGS') -Quiet
    $pl = ($praw -split "`n" | Where-Object { $_ -match [regex]::Escape($Package) } | Select-Object -First 1)
    if ($pl) { $procRows += $pl.Trim() }
    Start-Sleep -Seconds $IntervalSec
  }

  # Group the Thread-N family (MediaPipe graph workers share this generic name)
  $threadN = 0.0; $threadNMax = 0.0
  $others = @{}
  foreach ($k in $best.Keys) {
    if ($k -match '^Thread-\d+') {
      $threadN += $best[$k]
      if ($best[$k] -gt $threadNMax) { $threadNMax = $best[$k] }
    } else {
      $others[$k] = $best[$k]
    }
  }

  Write-Output ''
  Write-Output '--- per-thread peak %CPU (one core = 100%) ---'
  $sorted = $others.GetEnumerator() | Sort-Object -Property Value -Descending
  foreach ($e in $sorted) {
    Write-Output ("  {0,7:N1}  {1}" -f $e.Value, $e.Key)
  }
  if ($threadN -gt 0) {
    Write-Output ("  {0,7:N1}  Thread-N x{1} (MediaPipe graph workers, peak each {2:N1})" -f $threadN, (($best.Keys | Where-Object { $_ -match '^Thread-\d+' }).Count), $threadNMax)
  }
  Write-Output ''
  Write-Output '--- process rows ---'
  $procRows | Select-Object -Unique | ForEach-Object { Write-Output ("  " + $_) }

  if ($Dir) {
    $out = @()
    $out += "pid=$procId samples=$Samples intervalSec=$IntervalSec"
    $out += "package=$Package"
    $out += ''
    $out += 'peak %CPU per thread (one core = 100%)'
    foreach ($e in $sorted) { $out += ("{0}`t{1}" -f $e.Value, $e.Key) }
    if ($threadN -gt 0) { $out += ("{0}`tThread-N (grouped, count={1})" -f $threadN, (($best.Keys | Where-Object { $_ -match '^Thread-\d+' }).Count)) }
    $out += ''
    $out += 'process rows'
    $out += $procRows
    Save-Raw $Dir 'threads.txt' ($out -join "`n") | Out-Null
    Write-Output ("saved -> {0}" -f (Join-Path $Dir 'threads.txt'))
  }
}

# ------------------------------------------------------------------ dispatch
#
# ★ This switch MUST stay at the very end of the file. PowerShell executes a
#   script top-to-bottom while defining functions as it goes, so a dispatch that
#   runs before a definition fails with "The term 'Action-X' is not recognized".
#   That is exactly what happened to -Action threads: Action-Threads was appended
#   below the switch, so the action was dead on arrival. Offline single-function
#   tests could never catch it; the end-to-end mock run did.

switch ($Action) {
  'check'    { Action-Check }
  'info'     { Assert-Device; Get-DeviceInfo | Format-List | Out-String | Write-Output }
  'snapshot' { $d = New-RunDir $Note; Set-Content (Join-Path $d 'note.txt') $Note -Encoding UTF8; if ($Condition) { Set-Content (Join-Path $d 'condition.txt') $Condition -Encoding UTF8 }; Action-Snapshot -Dir $d; Write-Output ("RUN_DIR={0}" -f $d) }
  'record'   { $d = New-RunDir $Note; Set-Content (Join-Path $d 'note.txt') $Note -Encoding UTF8; if ($Condition) { Set-Content (Join-Path $d 'condition.txt') $Condition -Encoding UTF8 }; Action-Record -Dir $d -Minutes $Minutes -IntervalSec $IntervalSec; Write-Output ("RUN_DIR={0}" -f $d) }
  'drain'    { $d = New-RunDir $Note; Set-Content (Join-Path $d 'note.txt') $Note -Encoding UTF8; if ($Condition) { Set-Content (Join-Path $d 'condition.txt') $Condition -Encoding UTF8 }; Action-Drain -Dir $d -Minutes $Minutes -IntervalSec $IntervalSec; Write-Output ("RUN_DIR={0}" -f $d) }
  'all'      { Action-All }
  'compare'  { Action-Compare }
  'list'     { Action-List }
  'threads'  { $d = New-RunDir $Note; Set-Content (Join-Path $d 'note.txt') $Note -Encoding UTF8; if ($Condition) { Set-Content (Join-Path $d 'condition.txt') $Condition -Encoding UTF8 }; Action-Threads -Dir $d -Samples $Samples -IntervalSec $IntervalSec; Write-Output ("RUN_DIR={0}" -f $d) }
  # re-analyze an EXISTING run dir (no device needed) - pass it via -A
  'drain-analysis' { if ([string]::IsNullOrWhiteSpace($A)) { throw 'drain-analysis needs -A <run dir>' }; Show-DrainAnalysis -Dir $A }
}

