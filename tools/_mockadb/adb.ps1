# A fake `adb` used ONLY to exercise measure.ps1 / deploy-and-measure.ps1
# end-to-end on a machine with no phone attached.
#
# It replays canned responses shaped like the REAL outputs captured from the
# HONOR AMG-AN00 during this project, so the parsing code in those scripts runs
# for real. It is not a simulator: it does not model behaviour, it only makes the
# scripts' read paths execute so their bugs surface here instead of on-device.
#
# Outputs deliberately reproduce the awkward real-world details:
#   * 'dumpsys charge' does NOT exist (HONOR) and must not break anything
#   * batterystats uses UPPER-case 'UID' in the summary block
#   * 'top' prints RES with a unit suffix ('230M')
#   * 'top -H' includes the process's own row plus a '%CPU TID CMD' header
#   * 'dumpsys meminfo' has 'Graphics:' but NO 'Gfx dev:'
#   * the process is launched by a DIFFERENT pid after "restart"
#
# ASCII-only on purpose (BOM-less .ps1 files are decoded as GBK by Windows
# PowerShell, which would corrupt any non-ASCII literal here).

# NOTE: deliberately NOT declared with `param(...)`. A parameter block would make
# PowerShell try to BIND the forwarded flags, and adb's `-o` collides with the
# common parameter `-OutVariable`/`-OutBuffer` -> "Parameter name 'o' is
# ambiguous". The automatic $args array receives everything verbatim.
$Rest = $args

$ErrorActionPreference = 'Continue'

# --- a tiny bit of state so restarts look plausible ---
$stateFile = Join-Path $PSScriptRoot 'state.json'
$pidNow = 13334
if (Test-Path $stateFile) {
  try { $pidNow = [int]((Get-Content $stateFile -Raw | ConvertFrom-Json).pid) } catch { $pidNow = 13334 }
}

function Out-Lines { param([string[]]$L) ; $L | ForEach-Object { Write-Output $_ } }

$joined = ($Rest -join ' ')
$joined = $joined.Trim()

# ---------------------------------------------------------------- adb devices
if ($joined -eq 'devices') {
  Out-Lines @('List of devices attached', "AG8HUT5516006120`tdevice", '')
  exit 0
}
if ($joined -like 'devices*') {
  Out-Lines @('List of devices attached', "AG8HUT5516006120`tdevice product:AMG-AN00 model:AMG_AN00 device:HNAMG transport_id:2", '')
  exit 0
}

# ---------------------------------------------------------------- adb install
if ($joined -like 'install*') {
  Write-Output 'Performing Streamed Install'
  Write-Output 'Success'
  exit 0
}
if ($joined -like 'uninstall*') {
  Write-Output 'Success'
  exit 0
}

# ---------------------------------------------------------------- adb shell ...
if ($joined -like 'shell *') {
  $cmd = $joined.Substring(6).Trim()

  switch -Regex ($cmd) {

    '^getprop ro\.product\.model$'        { Write-Output 'AMG-AN00'; exit 0 }
    '^getprop ro\.product\.device$'       { Write-Output 'HNAMG'; exit 0 }
    '^getprop ro\.build\.version\.release$' { Write-Output '16'; exit 0 }
    '^getprop ro\.build\.version\.sdk$'   { Write-Output '36'; exit 0 }
    '^getprop ro\.product\.cpu\.abi$'     { Write-Output 'arm64-v8a'; exit 0 }
    '^getprop ro\.soc\.model$'            { Write-Output 'SM8650'; exit 0 }
    '^getprop ro\.soc\.manufacturer$'     { Write-Output 'QTI'; exit 0 }
    '^getprop ro\.build\.fingerprint$'    { Write-Output 'HONOR/AMG-AN00/HNAMG:16/HONORAMG-AN00/10DLDLD175C00E170:user/release-keys'; exit 0 }

    '^wm size$'                           { Write-Output 'Physical size: 1200x2664'; exit 0 }
    '^wm density$'                        { Write-Output 'Physical density: 520'; exit 0 }

    '^pidof ' {
      # alternate pid per invocation so "restart" produces a new value
      $pidNow = 13334 + (Get-Random -Minimum 0 -Maximum 500)
      ([ordered]@{ pid = $pidNow } | ConvertTo-Json -Compress) | Set-Content -Path $stateFile -Encoding UTF8
      Write-Output $pidNow
      exit 0
    }

    '^cmd package list packages -U ' {
      Write-Output 'package:com.airgesture.sweep uid:10340'
      exit 0
    }

    '^dumpsys battery$' {
      # Level drains at a fixed rate from the FIRST time this mock was asked, so
      # a drain run produces a real monotone staircase for the fit to work on.
      # plugged=1 (USB) is reported on purpose: the analysis must then refuse to
      # return PASS/FAIL, and that refusal is part of what is being verified.
      $bstate = Join-Path $PSScriptRoot 'battery.json'
      # Use Unix epoch SECONDS, not an ISO string. An ISO string written with
      # ToString('o') is UTC ('Z'), but [datetime]::Parse() interprets it as LOCAL
      # unless AdjustToUniversal is passed -- that mismatch produced a -480 minute
      # elapsed time (8h timezone offset) and therefore a nonsense level. Epoch
      # seconds have no timezone interpretation at all.
      $nowS = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
      $startS = $nowS
      if (Test-Path $bstate) {
        try { $startS = [long]((Get-Content $bstate -Raw | ConvertFrom-Json).t0s) } catch { $startS = $nowS }
      } else {
        ([ordered]@{ t0s = $nowS } | ConvertTo-Json -Compress) | Set-Content -Path $bstate -Encoding UTF8
      }
      $mins = ($nowS - $startS) / 60.0
      # Default ~0.6 %/h (realistic). Override with MOCK_BATT_PCT_PER_MIN to make
      # the staircase visible inside a short end-to-end run -- at the realistic
      # rate a two-minute test moves the integer level by 0.02%, i.e. not at all.
      $ratePerMin = 0.01
      if ($env:MOCK_BATT_PCT_PER_MIN) {
        try { $ratePerMin = [double]$env:MOCK_BATT_PCT_PER_MIN } catch { $ratePerMin = 0.01 }
      }
      $lvl = [int][Math]::Floor(80.0 - $ratePerMin * $mins)
      # Defensive: a nonsense elapsed time must never produce a nonsense level.
      if ($lvl -gt 100) { $lvl = 100 }
      if ($lvl -lt 1) { $lvl = 1 }
      Out-Lines @(
        'Current Battery Service state:',
        '  AC powered: false',
        '  USB powered: true',
        '  Wireless powered: false',
        '  Dock powered: false',
        '  Max charging current: 500000',
        '  Max charging voltage: 5000000',
        '  Charge counter: 523',
        '  status: 2',
        '  health: 2',
        '  present: true',
        "  level: $lvl",
        '  scale: 100',
        '  voltage: 4012',
        '  temperature: 410',
        '  technology: Li-ion',
        '  Charging state: 0',
        '  Charging policy: 0',
        '  Capacity level: 5',
        ''
      )
      exit 0
    }

    '^dumpsys charge$' {
      # HONOR has no charge service - must be tolerated
      Write-Error "Can't find service: charge"
      exit 1
    }

    '^dumpsys meminfo ' {
      # Reproduces the REAL two-section layout captured from the device:
      # per-process table rows have NO colon, App Summary rows DO have one, and
      # 'Gfx dev' / 'GL mtrack' appear ONLY in the per-process table.
      Out-Lines @(
        'Applications Memory Usage (in Kilobytes):',
        'Uptime: 1234567 Realtime: 1234567',
        '',
        '** MEMINFO in pid 13334 [com.airgesture.sweep] **',
        '                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap',
        '                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free',
        '                ------   ------   ------   ------   ------   ------   ------   ------',
        '  Native Heap    31236    27212     4000    32835    32696   102628    53170    44429',
        '  Dalvik Heap    20784    18600     2000     3000    24000    45000    30000    15000',
        '        Stack     1368     1220      148      908     1376',
        '       Gfx dev    10320    10320        0        0    10324',
        '      GL mtrack    76480    76480        0        0    76480',
        '          TOTAL   218023   150064    19764    36821   315572   114066    58464    50573',
        '',
        ' App Summary',
        '               Pss(KB)                        Rss(KB)',
        '                ------                       ------',
        '             Java Heap:    20784                          34972',
        '           Native Heap:    27212                          32696',
        '                  Code:    25628                         151968',
        '                 Stack:     1220                           1376',
        '              Graphics:    86800                          86804',
        '             TOTAL PSS:   218023            TOTAL RSS:   315572       TOTAL SWAP PSS:    36821',
        '',
        ' Objects',
        '               Views:        0         ViewRootImpl:        0',
        '         AppContexts:        3           Activities:        0',
        '              Assets:        2        AssetManagers:        2',
        '       Local Binders:       12        Proxy Binders:       30',
        '    Parcel memory:        5          Parcel count:       22',
        ' Death Recipients:        0      OpenSSL Sockets:        0',
        '            WebViews:        0',
        '',
        ' SQL',
        '         MEMORY_USED:        0',
        '  PAGECACHE_OVERFLOW:        0          MALLOC_SIZE:        0',
        '',
        '                           Count                       Total(kB)',
        ' Native Allocations',
        ''
      )
      exit 0
    }

    '^dumpsys batterystats --reset$' { Write-Output 'Battery stats reset.'; exit 0 }

    '^dumpsys batterystats -v ' {
      # Reproduces the DETAIL block that only appears with the (undocumented)
      # verbose flag: the header uses mixed-case 'Uid u0aNNN:' and a colon, while
      # the SUMMARY section uses upper-case 'UID u0aNNN:' with no colon.
      Out-Lines @(
        'Battery History:',
        '',
        'Per-PID Stats:',
        '',
        'Statistics since last charge:',
        '',
        '  UID u0a340: 554 fg: 140 (1h 16m 20s 23ms) bg: 35.1 fgs: 234 (2h 2m 34s 725ms)',
        '      screen=141 cpu=413 cpu:fg=140 cpu:bg=35.1 cpu:fgs=234',
        '      (on battery, screen on) screen=126 cpu=292 cpu:fg=112 cpu:bg=29.5',
        '      (on battery, screen off/doze) cpu=1.53 cpu:fg=1.46 cpu:bg=0.0698',
        '      (not on battery, screen on) screen=15.2 cpu=109 cpu:fg=17.4',
        '      (not on battery, screen off/doze) cpu=10.5 cpu:fg=9.62',
        '    UID 1047: 325 bg: 325',
        '        cpu=325 cpu:bg=325',
        '',
        'Per-UID Stats:',
        '',
        '  Uid u0a340:',
        '    Estimated power use (mAh):',
        '      Capacity: 5200, Computed drain: 156, actual drain: 161',
        '      Uid u0a340: 88.0 ( cpu=88.0 )',
        '    Cpu time: 1240000 (ms)',
        '    CPU: 88.0 ( cpu=88.0 )',
        '    Sensor: 0.0',
        '    Camera: 12.4',
        '    GPS: 0.0',
        '    Wifi: 1.2',
        '    Audio: 0.0',
        '    Foreground activities: 140000 (ms)',
        '    Proc state: Foreground service',
        '',
        '  Uid u0a235:',
        '    CPU: 30.0',
        ''
      )
      exit 0
    }

    '^dumpsys batterystats$' {
      Out-Lines @(
        'Battery History:',
        '  UID u0a340: 554 fg: 140 bg: 35.1',
        '    cpu=413',
        ''
      )
      exit 0
    }

    '^dumpsys batterystats ' {
      Out-Lines @(
        'Battery History:',
        '  UID u0a340: 554 fg: 140 bg: 35.1',
        '    cpu=413',
        ''
      )
      exit 0
    }

    '^dumpsys package ' {
      Out-Lines @(
        'Packages:',
        '  Package [com.airgesture.sweep] (abc123):',
        '    versionCode=23 minSdk=29 targetSdk=36',
        '    versionName=0.17.0',
        '    granted=true',
        '    runtime permissions:',
        '      android.permission.CAMERA: granted=true',
        ''
      )
      exit 0
    }

    '^dumpsys power$' {
      Out-Lines @('POWER MANAGER (dumpsys power)', '  mWakefulness=Awake', '')
      exit 0
    }

    '^settings get secure enabled_accessibility_services$' {
      Write-Output 'com.openai.chatgpt/com.openai.feature.conversations.screencontext.ConversationScreenAccessibilityService:com.airgesture.sweep/com.airgesture.sweep.SweepAccessibilityService:com.hihonor.awareness/com.hihonor.awareness.server.pageanalysis.AccessibleInfoStuck'
      exit 0
    }

    '^logcat ' {
      # HONOR hides logcat from shell - reproduce that so the scripts must cope
      Write-Output '--------- beginning of main'
      exit 0
    }

    '^cat /proc/[0-9]+/status$' {
      Out-Lines @(
        'Name:   irgesture.sweep',
        'Uid:    10340   10340   10340   10340',
        'Threads:    52',
        'VmPeak: 18189688 kB',
        'VmHWM:    371080 kB',
        'VmRSS:    236972 kB',
        ''
      )
      exit 0
    }

    '^cat /proc/[0-9]+/cmdline$' {
      # cmdline is NUL separated; cmd.exe style echo cannot emit NUL, so emit the
      # name followed by nothing. Consumers split on NUL and take the first part.
      Write-Output 'com.airgesture.sweep'
      exit 0
    }

    '^top -H -b -n 1 -o %CPU,TID,CMD -p [0-9]+$' {
      $p = 13334
      if (Test-Path $stateFile) { try { $p = [int]((Get-Content $stateFile -Raw | ConvertFrom-Json).pid) } catch {} }
      Out-Lines @(
        'Threads: 53 total,   0 running,  53 sleeping,   0 stopped,   0 zombie',
        '  Mem:    11264M total,    10458M used,      805M free,        3M buffers',
        ' Swap:    12287M total,     4234M used,     8053M free,     2728M cached',
        '800%cpu 104%user  21%nice 100%sys 536%idle   0%iow  32%irq   7%sirq   0%host',
        '%CPU   TID CMD',
        "25.0 13383 drishti_gl_runn",
        '21.4 13339 HeapTaskDaemon',
        ' 7.1 13387 CXCP-Camera-H',
        ' 4.3 13359 pool-5-thread-1',
        ' 3.5 13378 Thread-9',
        ' 3.5 13381 Thread-8',
        " 0.0 $p irgesture.sweep",
        ' 0.0 13335 Signal Catcher',
        ''
      )
      exit 0
    }

    '^top ' {
      # RES carries a unit suffix on purpose ('230M'), as on the real device
      Out-Lines @(
        'Tasks: 1 total,   0 running,   1 sleeping,   0 stopped,   0 zombie',
        '  Mem:    11264M total,    10439M used,      825M free,        3M buffers',
        '800%cpu  93%user  21%nice 114%sys 536%idle   0%iow  32%irq   4%sirq   0%host',
        '%CPU  %MEM  RES[ARGS]',
        '96.6   2.0 230M com.airgesture.sweep',
        ''
      )
      exit 0
    }

    default {
      Write-Error "mock adb: unhandled shell command: $cmd"
      exit 1
    }
  }
}

Write-Error "mock adb: unhandled invocation: $joined"
exit 1
