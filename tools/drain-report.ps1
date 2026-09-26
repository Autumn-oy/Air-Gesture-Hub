# Drain-run report: turn the raw evidence captured by usb-return-watch.ps1 into
# the numbers a power report needs.
#
# ASCII-only on purpose: Windows PowerShell 5.1 decodes BOM-less .ps1 files as
# GBK, so non-ASCII literals here would be corrupted (same reason build.ps1,
# measure.ps1 and usb-return-watch.ps1 stay ASCII-only).
#
# Inputs (all produced by the run; nothing here is invented):
#   battery_before.txt / battery_after.txt
#   batterystats_before_charged.txt / batterystats_after_charged.txt
#   media_camera_before.txt / media_camera_after.txt
#
# Why "--charged" and not "-v": on the HUAWEI ELS-AN00 test device
# `dumpsys batterystats -v` answers "Unknown option: -v", so the per-UID DETAIL
# block has to be read out of the plain --charged dump (section `u0aNNN:`).
#
# Usage:
#   .\drain-report.ps1 -Dir <run dir>
#   .\drain-report.ps1 -Dir <run dir> -Uid u0a582 -Package com.airgesture.sweep

param(
  [Parameter(Mandatory = $true)][string]$Dir,
  [string]$Uid = 'u0a582',
  [string]$Package = 'com.airgesture.sweep'
)

$ErrorActionPreference = 'Stop'

function Read-Raw {
  param([string]$Name)
  $p = Join-Path $Dir $Name
  if (-not (Test-Path $p)) { return $null }
  return [IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)
}

function Get-Match {
  param([string]$Text, [string]$Pattern)
  if ([string]::IsNullOrEmpty($Text)) { return '' }
  $m = [regex]::Match($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)
  if ($m.Success) { return $m.Groups[1].Value.Trim() }
  return ''
}

# Android's formatDuration output ("5m31s955ms", "40m 0s 454ms", "1h2m3s456ms",
# "13s 39ms"). Parsed unit by unit: a single alternation regex is easy to get
# wrong because "ms" and "m" overlap ("955ms" must not be read as 955 minutes).
function ConvertTo-Seconds {
  param([string]$Duration)
  if ([string]::IsNullOrWhiteSpace($Duration)) { return $null }
  $s = $Duration -replace '\s', ''
  $d = 0.0; $h = 0.0; $mi = 0.0; $se = 0.0; $ms = 0.0
  $m = [regex]::Match($s, '(\d+)d');      if ($m.Success) { $d  = [double]$m.Groups[1].Value }
  $m = [regex]::Match($s, '(\d+)h');      if ($m.Success) { $h  = [double]$m.Groups[1].Value }
  $m = [regex]::Match($s, '(\d+)m(?!s)'); if ($m.Success) { $mi = [double]$m.Groups[1].Value }
  $m = [regex]::Match($s, '(\d+)s');      if ($m.Success) { $se = [double]$m.Groups[1].Value }
  $m = [regex]::Match($s, '(\d+)ms');     if ($m.Success) { $ms = [double]$m.Groups[1].Value }
  return $d * 86400 + $h * 3600 + $mi * 60 + $se + $ms / 1000.0
}

# Adaptive units. A fixed "0.00 h" hides a real 13-second camera time as "0",
# which reads like "never" - so switch units instead of rounding to zero.
function Format-Dur {
  param($Seconds)
  if ($null -eq $Seconds) { return 'n/a' }
  $s = [double]$Seconds
  if ($s -eq 0) { return '0' }
  if ($s -lt 60) { return ("{0:0.0} s" -f $s) }
  if ($s -lt 3600) { return ("{0:0.0} min" -f ($s / 60.0)) }
  return ("{0:0.00} h ({1:0.0} min)" -f ($s / 3600.0), ($s / 60.0))
}

function Format-Hours {
  param($Seconds)
  if ($null -eq $Seconds) { return 'n/a' }
  return ("{0:0.00} h" -f ([double]$Seconds / 3600.0))
}

$after  = Read-Raw 'batterystats_after_charged.txt'
$before = Read-Raw 'batterystats_before_charged.txt'
$batBefore = Read-Raw 'battery_before.txt'
$batAfter  = Read-Raw 'battery_after.txt'
$camBefore = Read-Raw 'media_camera_before.txt'
$camAfter  = Read-Raw 'media_camera_after.txt'

Write-Output ('=' * 78)
Write-Output ("DRAIN REPORT  dir = {0}" -f $Dir)
Write-Output ('=' * 78)

$missing = @()
foreach ($pair in @(@('batterystats_after_charged.txt', $after), @('battery_after.txt', $batAfter), @('media_camera_after.txt', $camAfter))) {
  if ($null -eq $pair[1]) { $missing += $pair[0] }
}
if ($missing.Count -gt 0) {
  Write-Output ''
  Write-Output ("MISSING AFTER-FILES: {0}" -f ($missing -join ', '))
  Write-Output 'The run has not finished (or the after-evidence was not captured). Nothing to report yet.'
  return
}

# ------------------------------------------------------------------ 1. window
Write-Output ''
Write-Output '--- 1. WINDOW (authoritative: batterystats "Statistics since last charge") ---'
$onBattery     = Get-Match $after 'Time on battery:\s*([0-9dhm s\.]+?)\s*\('
$onBatteryOff  = Get-Match $after 'Time on battery screen off:\s*([0-9dhm s\.]+?)\s*\('
$onBatteryDoze = Get-Match $after 'Time on battery screen doze:\s*([0-9dhm s\.]+?)\s*\('
$screenOn      = Get-Match $after 'Screen on:\s*([0-9dhm s\.]+?)\s*\('
$currentlyOnBattery = Get-Match $after 'currently on battery:\s*(\w+)'
$disLower     = Get-Match $after 'Amount discharged \(lower bound\):\s*(\d+)'
$disUpper     = Get-Match $after 'Amount discharged \(upper bound\):\s*(\d+)'
$disScreenOn  = Get-Match $after 'Amount discharged while screen on:\s*(\d+)'
$disScreenOff = Get-Match $after 'Amount discharged while screen off:\s*(\d+)'

$winSec = ConvertTo-Seconds $onBattery
$offSec = ConvertTo-Seconds $onBatteryOff
$onSec  = ConvertTo-Seconds $screenOn
Write-Output ("window length        : {0}" -f (Format-Dur $winSec))
Write-Output ("  screen off         : {0}" -f (Format-Dur $offSec))
Write-Output ("  screen doze        : {0}" -f (Format-Dur (ConvertTo-Seconds $onBatteryDoze)))
Write-Output ("screen on time       : {0}" -f (Format-Dur $onSec))
Write-Output ("still on battery?    : {0}  (false = phone was plugged back in when captured)" -f $currentlyOnBattery)
# ★ A window that is not 100% on battery is NOT one continuous discharge: the phone
#   was on a charger for part of it. On 2026-09-25 this silently hid a ~10 minute
#   wall-charge (Time on battery was only 97.7% of run time), which topped the level
#   up mid-run - the net endpoint drop then UNDERSTATES the real discharge rate.
$totalRun = Get-Match $after 'Total run time:\s*([0-9dhm s\.]+?)\s*realtime'
$startClock = Get-Match $after 'Start clock time:\s*(\S+)'
$onBatPct = Get-Match $after 'Time on battery:\s*[0-9dhm s\.]+?\s*\(([0-9.]+)%\)\s*realtime'
$runSec = ConvertTo-Seconds $totalRun
if ($runSec) {
  Write-Output ("total run time       : {0}   (start clock: {1})" -f (Format-Dur $runSec), $startClock)
  Write-Output ("on-battery share     : {0}% of run time" -f $onBatPct)
  if ($winSec) {
    $offBat = $runSec - $winSec
    if ($offBat -gt 120) {
      Write-Output ("  WARNING CHARGE INTERRUPTION: {0} of this window was spent NOT on battery." -f (Format-Dur $offBat))
      Write-Output '    The window is not one continuous discharge: the level was topped up mid-run, so'
      Write-Output '    the net 96%->6% drop understates the true discharge. See section 2.'
    }
  }
}
Write-Output ("amount discharged    : lower={0} upper={1} points (screen on={2}, screen off={3})" -f $disLower, $disUpper, $disScreenOn, $disScreenOff)

$lvB = Get-Match $batBefore '(?m)^\s*level:\s*(-?\d+)'
$lvA = Get-Match $batAfter  '(?m)^\s*level:\s*(-?\d+)'
$usbA = Get-Match $batAfter 'USB powered:\s*(true|false)'
if ($lvB -ne '' -and $lvA -ne '') {
  Write-Output ("battery level        : {0}% -> {1}%  (endpoint drop {2} points; 1% granularity, so +-1)" -f $lvB, $lvA, ([int]$lvB - [int]$lvA))
}
Write-Output ("usb powered at end   : {0}" -f $usbA)
if ($usbA -eq 'false') { Write-Output 'WARNING: still on battery at capture time - the window is not closed.' }

# --------------------------------------------------------- 2. discharge steps
Write-Output ''
Write-Output '--- 2. DISCHARGE STEPS (system-recorded time to fall each 1%) ---'
$stepLines = @()
if ($after -match '(?s)Discharge step durations:(.*?)(\r?\n\s*\r?\n|Estimated screen on time)') {
  $stepLines = ($Matches[1] -split "`n") | Where-Object { $_ -match '^\s*#\d+:' }
}
$steps = @()
foreach ($l in $stepLines) {
  $m = [regex]::Match($l, '^\s*#(\d+):\s*\+([0-9dhm s\.]+?)\s+to\s+(\d+)\s*(?:\((.*)\))?\s*$')
  if (-not $m.Success) { continue }
  $tags = $m.Groups[4].Value
  $kind = 'no-screen-tag'
  if ($tags -match 'screen-on') { $kind = 'screen-on' }
  elseif ($tags -match 'screen-off') { $kind = 'screen-off' }
  $steps += [pscustomobject]@{ N = [int]$m.Groups[1].Value; Sec = (ConvertTo-Seconds $m.Groups[2].Value); Level = [int]$m.Groups[3].Value; Tags = $tags; Kind = $kind }
}
if ($steps.Count -eq 0) {
  Write-Output 'no discharge steps recorded (never discharged, or stats were reset at the end)'
} else {
  $total = ($steps | Measure-Object -Property Sec -Sum).Sum
  $lvlMin = ($steps | Measure-Object -Property Level -Minimum).Minimum
  $lvlMax = ($steps | Measure-Object -Property Level -Maximum).Maximum
  $distinct = @($steps | Select-Object -ExpandProperty Level | Sort-Object -Unique)
  $dupes = @($steps | Group-Object Level | Where-Object { $_.Count -gt 1 })

  # ★ The raw step count is NOT the number of percentage points dropped. When the
  #   level estimate wobbles under load, the same level is recorded more than
  #   once - on 2026-09-25 there were 101 steps for a 90-point drop (levels 50..60
  #   recorded twice). Dividing total time by the raw step count therefore
  #   OVERSTATES the drain rate (it gave 14.59 %/h where the truth is ~12.5 %/h).
  #   The rate is computed from the distinct levels actually traversed.
  Write-Output ("steps recorded       : {0}" -f $steps.Count)
  Write-Output ("distinct levels      : {0}   (levels {1}..{2})" -f $distinct.Count, $lvlMin, $lvlMax)
  if ($dupes.Count -gt 0) {
    Write-Output ("  WARNING {0} level(s) re-recorded: {1}" -f $dupes.Count, (($dupes | Sort-Object { [int]$_.Name } | ForEach-Object { "{0}x{1}" -f $_.Name, $_.Count }) -join ' '))
    Write-Output '    -> usually a mid-run TOP-UP (see CHARGE INTERRUPTION below), not voltage wobble.'
    Write-Output '       The raw step count overstates points dropped; the rate below uses distinct levels.'
  }
  Write-Output ("total step duration  : {0}" -f (Format-Dur $total))
  $stepRate = ($lvlMax - $lvlMin) / ($total / 3600.0)
  Write-Output ("=> device drain rate : {0:0.000} %/h   (step-based: {1} levels over {2:0.00} h)" -f $stepRate, ($lvlMax - $lvlMin), ($total / 3600.0))
  if ($lvB -ne '' -and $lvA -ne '' -and $winSec -gt 0) {
    $endRate = ([int]$lvB - [int]$lvA) / ($winSec / 3600.0)
    Write-Output ("=> endpoint rate     : {0:0.000} %/h   ({1}% -> {2}% across the whole window)" -f $endRate, $lvB, $lvA)
    $gap = 100.0 * [Math]::Abs($stepRate - $endRate) / $endRate
    Write-Output ("=> agreement         : {0:0.0}% between the two independent methods" -f $gap)
  }
  Write-Output ("=> 8h extrapolation  : {0:0.00} %   (step-based)" -f (8.0 * $stepRate))
  Write-Output '  by screen tag (tags are printed verbatim below; AOSP does not always emit'
  Write-Output '  a screen-off tag, so "no-screen-tag" is NOT assumed to be screen-off):'
  foreach ($k in @('screen-on', 'screen-off', 'no-screen-tag')) {
    $sub = @($steps | Where-Object { $_.Kind -eq $k })
    if ($sub.Count -gt 0) {
      $st = ($sub | Measure-Object -Property Sec -Sum).Sum
      $d = @($sub | Select-Object -ExpandProperty Level | Sort-Object -Unique).Count
      Write-Output ("    {0,-14}: {1,3} steps / {2,3} distinct levels, {3} -> {4:0.000} %/h" -f $k, $sub.Count, $d, (Format-Dur $st), ($d / ($st / 3600.0)))
    }
  }
  $show = @($steps | Sort-Object N)
  if ($show.Count -gt 24) {
    Write-Output ("    (showing first 12 and last 12 of {0} steps)" -f $show.Count)
    $show = @($show[0..11]) + @($show[($show.Count - 12)..($show.Count - 1)])
  }
  foreach ($s in $show) { Write-Output ("    #L{0,-4} {1,7:0.0} min   tags=[{2}]" -f $s.Level, ($s.Sec / 60.0), $s.Tags) }

  # ★ Charge interruptions. Discharge steps only record time spent DISCHARGING, so
  #   when the phone is charged mid-window the level jumps UP between two adjacent
  #   steps and the levels in between get recorded twice (2026-09-25: levels 50..60
  #   each twice, from a ~10 minute wall-charge). This is NOT voltage wobble - the
  #   independent proof is "Time on battery" being only a fraction of "Total run time".
  #   Wall clock is estimated by walking back from the capture moment.
  $anchor = (Get-Item (Join-Path $Dir 'battery_after.txt')).LastWriteTime
  $chrono = @(); $cum = 0.0
  foreach ($s in ($steps | Sort-Object N)) { $cum += $s.Sec; $chrono += [pscustomobject]@{ Wall = $anchor.AddSeconds(-$cum); L = $s.Level } }
  $chrono = @($chrono | Sort-Object Wall)
  $revs = @()
  for ($i = 1; $i -lt $chrono.Count; $i++) {
    if ($chrono[$i].L -gt $chrono[$i - 1].L) { $revs += [pscustomobject]@{ Wall = $chrono[$i].Wall; From = $chrono[$i - 1].L; To = $chrono[$i].L } }
  }
  if ($revs.Count -gt 0) {
    Write-Output ("CHARGE INTERRUPTION  : {0} level reversal(s) inside the discharge sequence" -f $revs.Count)
    foreach ($r in $revs) { Write-Output ("    ~{0}  level {1} -> {2}   (wall clock walked back from the capture time - approximate)" -f $r.Wall.ToString('HH:mm:ss'), $r.From, $r.To) }
    Write-Output '    -> duplicated levels and inflated "amount discharged" come from HERE, not from load wobble.'
    Write-Output '       The net endpoint drop therefore UNDERSTATES the true discharge rate.'
    $topUp = 0
    foreach ($r in $revs) { $topUp += ($r.To - $r.From) }
    if ($topUp -gt 0 -and $winSec -gt 0 -and $lvB -ne '' -and $lvA -ne '') {
      $netPts = [int]$lvB - [int]$lvA
      Write-Output ("    => CORRECTED device rate: {0:0.000} %/h  = (net {1} + top-up {2}) points over {3:0.00} h on battery" -f (($netPts + $topUp) / ($winSec / 3600.0)), $netPts, $topUp, ($winSec / 3600.0))
      Write-Output '       Use this one: the top-up was charged for free and then consumed again.'
    }
  } else {
    Write-Output 'CHARGE INTERRUPTION  : none detected (no level reversal in the step sequence)'
  }
}

# ------------------------------------------------- 3. device totals and our app
Write-Output ''
Write-Output '--- 3. ESTIMATED POWER (mAh, from the power profile) ---'
$globalBlock = ''
if ($after -match '(?s)Estimated power use \(mAh\):(.*?)(?=^\s*UID\s+u0a\d+:|\z)') { $globalBlock = $Matches[1] }
$globalItems = @()
foreach ($m in [regex]::Matches($globalBlock, '(?m)^\s{4,}([A-Za-z_]+):\s*([0-9.]+)\s+apps:\s*([0-9.]+)')) {
  $globalItems += [pscustomobject]@{ Name = $m.Groups[1].Value; Total = [double]$m.Groups[2].Value; Apps = [double]$m.Groups[3].Value }
}
$devTotal = 0.0; $devApps = 0.0
foreach ($g in $globalItems) { $devTotal += $g.Total; $devApps += $g.Apps }
Write-Output ("device total         : {0:0.00} mAh   (sum of Global subsystems)" -f $devTotal)
Write-Output ("  of which apps      : {0:0.00} mAh   (the 'apps:' column - the honest denominator for an app share)" -f $devApps)
foreach ($g in $globalItems) { Write-Output ("    {0,-14}: total {1,8:0.000}  apps {2,8:0.000}" -f $g.Name, $g.Total, $g.Apps) }

Write-Output ''
Write-Output ("--- 4. OUR APP ({0}) ---" -f $Uid)
$uidLine = Get-Match $after ('(?m)^\s*UID\s+' + [regex]::Escape($Uid) + ':\s*(.+)$')
if ($uidLine -eq '') {
  Write-Output ("UID {0} has no attribution line in this window - the app never ran on battery." -f $Uid)
} else {
  Write-Output ("summary              : {0}" -f $uidLine)
  $ourMah = 0.0
  $mm = [regex]::Match($uidLine, '^([0-9.]+)')
  if ($mm.Success) { $ourMah = [double]$mm.Groups[1].Value }
  Write-Output ("our power            : {0:0.000} mAh" -f $ourMah)
  foreach ($comp in @('cpu', 'sensors', 'wifi', 'wakelock', 'camera', 'mobile_radio', 'gnss')) {
    $cm = [regex]::Match($uidLine, ($comp + '=([0-9.]+)'))
    if ($cm.Success) {
      $v = [double]$cm.Groups[1].Value
      Write-Output ("    {0,-13}: {1,8:0.000} mAh  ({2,5:0.0}% of our own power)" -f $comp, $v, $(if ($ourMah -gt 0) { 100.0 * $v / $ourMah } else { 0 }))
    }
  }
  # NOTE: the per-UID lines in this dump are a top-N list, NOT a complete
  # partition of device power - summing them understates the device. Use the
  # Global 'apps:' column as the denominator.
  if ($devApps -gt 0) { Write-Output ("  => share of app-attributable device power : {0:0.00}%" -f (100.0 * $ourMah / $devApps)) }
  if ($devTotal -gt 0) { Write-Output ("  => share of total estimated device power  : {0:0.00}%" -f (100.0 * $ourMah / $devTotal)) }
  if ($winSec -gt 0) {
    $perHour = $ourMah / ($winSec / 3600.0)
    Write-Output ("  => our power per hour                     : {0:0.00} mAh/h" -f $perHour)
    Write-Output ("  => our power extrapolated to 8 h          : {0:0.00} mAh" -f ($perHour * 8.0))
  }
}

# per-UID DETAIL block: camera seconds, sensor seconds, cpu ms - the real evidence
$detail = ''
if ($after -match ('(?ms)^\s{2}' + [regex]::Escape($Uid) + ':\s*\r?\n(.*?)(?=^\s{2}u0a\d+:|\z)')) { $detail = $Matches[1] }
Write-Output ''
if ($detail -eq '') {
  Write-Output ("no detail block for {0} in this window" -f $Uid)
} else {
  Write-Output ("--- 5. OUR APP, per-UID detail block ({0}) ---" -f $Uid)
  $cam = Get-Match $detail 'Camera:\s*([0-9dhm s\.]+?)\s+realtime'
  # batterystats counts camera sessions for the whole window; unlike the kernel
  # event log it does NOT roll over, so this count is the one to trust.
  $camTimes = Get-Match $detail 'Camera:\s*[0-9dhm s\.]+?\s+realtime\s*\((\d+) times\)'
  $sen = Get-Match $detail 'Sensor \d+:\s*([0-9dhm s\.]+?)\s+blamed realtime'
  $fg  = Get-Match $detail 'Foreground for:\s*([0-9dhm s\.]+)'
  $run = Get-Match $detail 'Total running:\s*([0-9dhm s\.]+)'
  $camSec = if ($cam) { ConvertTo-Seconds $cam } else { $null }
  $senSec = if ($sen) { ConvertTo-Seconds $sen } else { $null }
  Write-Output ("camera held          : {0}{1}" -f $(if ($cam) { Format-Dur $camSec } else { 'never' }), $(if ($camTimes) { "   ($camTimes sessions, per batterystats)" } else { '' }))
  Write-Output ("sensor held          : {0}" -f $(if ($sen) { Format-Dur $senSec } else { 'never' }))
  Write-Output ("foreground for       : {0}" -f $(if ($fg) { Format-Dur (ConvertTo-Seconds $fg) } else { 'n/a' }))
  Write-Output ("total running        : {0}" -f $(if ($run) { Format-Dur (ConvertTo-Seconds $run) } else { 'n/a' }))
  if ($camSec -and $winSec) { Write-Output ("camera duty cycle    : {0:0.000}% of the window" -f (100.0 * $camSec / $winSec)) }
  if ($senSec -and $winSec) { Write-Output ("sensor duty cycle    : {0:0.0}% of the window" -f (100.0 * $senSec / $winSec)) }

  # "Proc <pkg>:" is a header; its CPU numbers are on the following line
  $dl = $detail -split "`n"
  for ($i = 0; $i -lt $dl.Count; $i++) {
    if ($dl[$i] -match ('Proc\s+' + [regex]::Escape($Package) + ':')) {
      $cpuLine = if ($i + 1 -lt $dl.Count) { $dl[$i + 1].Trim() } else { '' }
      Write-Output ("proc cpu             : {0}" -f $cpuLine)
      # device format: "CPU: 9s 720ms usr + 5s 460ms krn ; 0ms fg"
      $u = Get-Match $cpuLine '([0-9dhm s\.]+?)\s+usr'
      $s = Get-Match $cpuLine 'usr\s*\+\s*([0-9dhm s\.]+?)\s+krn'
      if ($u -and $s) {
        $cpuSec = (ConvertTo-Seconds $u) + (ConvertTo-Seconds $s)
        Write-Output ("proc cpu total       : {0}  (usr {1} + krn {2})" -f (Format-Dur $cpuSec), (Format-Dur (ConvertTo-Seconds $u)), (Format-Dur (ConvertTo-Seconds $s)))
        if ($winSec -gt 0) { Write-Output ("  => cpu duty cycle  : {0:0.000}% of one core over the window" -f (100.0 * $cpuSec / $winSec)) }
      }
    }
  }
  foreach ($w in ($detail -split "`n" | Where-Object { ($_ -match 'Wake lock' -or $_ -match 'TOTAL wake') -and $_ -match '\d+m|\d+s' })) { Write-Output ("wakelock             : {0}" -f $w.Trim()) }
  foreach ($j in ($detail -split "`n" | Where-Object { $_ -match '^\s+Job\s+com\.airgesture' })) { Write-Output ("job                  : {0}" -f $j.Trim()) }
}

# ----------------------------------------------------------- 6. camera events
Write-Output ''
Write-Output '--- 6. CAMERA OPEN/CLOSE EVENTS (dumpsys media.camera event log) ---'
function Get-CameraEvents {
  param([string]$Text)
  $out = @()
  if ([string]::IsNullOrEmpty($Text)) { return $out }
  foreach ($m in [regex]::Matches($Text, '(?m)^\s*(\d\d-\d\d \d\d:\d\d:\d\d) : (CONNECT|DISCONNECT) device \d+ client for package (\S+) \(PID (\d+)\)')) {
    $out += [pscustomobject]@{ Ts = $m.Groups[1].Value; Kind = $m.Groups[2].Value; Pkg = $m.Groups[3].Value }
  }
  return $out
}
$evBefore = Get-CameraEvents $camBefore
$evAfter  = Get-CameraEvents $camAfter
Write-Output ("events in before-log : {0} (ours: {1})" -f $evBefore.Count, @($evBefore | Where-Object { $_.Pkg -eq $Package }).Count)
Write-Output ("events in after-log  : {0} (ours: {1})" -f $evAfter.Count, @($evAfter | Where-Object { $_.Pkg -eq $Package }).Count)

# The before-log was captured at the start of the window, so anything strictly
# newer than its newest timestamp happened inside the window.
$cut = ''
if ($evBefore.Count -gt 0) {
  $cut = (($evBefore | ForEach-Object { $_.Ts }) | Sort-Object)[-1]
  Write-Output ("window cut (newest before-log entry): {0}" -f $cut)
}
$ours = @($evAfter | Where-Object { $_.Pkg -eq $Package })
if ($cut -ne '') { $ours = @($ours | Where-Object { $_.Ts -gt $cut }) }
$connects = @($ours | Where-Object { $_.Kind -eq 'CONNECT' })
$discs    = @($ours | Where-Object { $_.Kind -eq 'DISCONNECT' })
Write-Output ("OUR events in window : {0} CONNECT / {1} DISCONNECT" -f $connects.Count, $discs.Count)

$chrono = @($ours | Sort-Object Ts)
$open = $null; $sessions = @()
foreach ($e in $chrono) {
  if ($e.Kind -eq 'CONNECT' -and $null -eq $open) { $open = $e.Ts }
  elseif ($e.Kind -eq 'DISCONNECT' -and $null -ne $open) { $sessions += [pscustomobject]@{ Open = $open; Close = $e.Ts }; $open = $null }
}
if ($sessions.Count -gt 0) {
  $durs = @()
  foreach ($s in $sessions) {
    $t0 = [datetime]::ParseExact($s.Open, 'MM-dd HH:mm:ss', $null)
    $t1 = [datetime]::ParseExact($s.Close, 'MM-dd HH:mm:ss', $null)
    $durs += ($t1 - $t0).TotalSeconds
  }
  $sorted = @($durs | Sort-Object)
  $median = $sorted[[int]($sorted.Count / 2)]
  $sum = ($durs | Measure-Object -Sum).Sum
  Write-Output ("camera sessions      : {0} paired, total camera-on {1}" -f $sessions.Count, (Format-Dur $sum))
  Write-Output ("session length       : median {0:0.0}s  min {1:0.0}s  max {2:0.0}s" -f $median, $sorted[0], $sorted[-1])
  Write-Output ("first / last session : {0} -> {1}" -f $sessions[0].Open, $sessions[-1].Close)
  if ($winSec -gt 0) { Write-Output ("camera duty cycle    : {0:0.000}% of the window" -f (100.0 * $sum / $winSec)) }
  Write-Output ("sessions per hour    : {0:0.0}" -f ($sessions.Count / ($winSec / 3600.0)))
}
if ($open) { Write-Output ("NOTE: a CONNECT at {0} has no matching DISCONNECT inside the log." -f $open) }

if ($evAfter.Count -gt 0) {
  $oldest = (($evAfter | ForEach-Object { $_.Ts }) | Sort-Object)[0]
  Write-Output ("oldest entry in after-log: {0}" -f $oldest)
  if ($cut -ne '' -and $oldest -gt $cut) {
    Write-Output 'ROLLOVER: the event log rotated during the run -> counts and camera-on time above are a'
    Write-Output '          LOWER BOUND, and the per-session list is incomplete. Report it that way.'
  } else {
    Write-Output 'no rollover: the after-log still reaches back past the window start, so counts are complete.'
  }
}

Write-Output ''
Write-Output '--- 7. CROSS-CHECK ---'
Write-Output 'Two independent camera figures must agree:'
Write-Output '  * "camera held" in section 5 (batterystats per-UID realtime)'
Write-Output '  * "total camera-on" in section 6 (kernel camera service event log)'
Write-Output 'A large disagreement means one was truncated (log rollover) - report the discrepancy,'
Write-Output 'do not silently pick the friendlier one.'
