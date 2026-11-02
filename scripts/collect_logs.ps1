# What a phone over USB can give about the app's last run, into a folder of logs\. The same as the launcher's report, plus
# the system's view. -Symbolize names the frames of libmain.so (it needs the unstripped library of the same build).
param([string]$Device, [switch]$Clear)
. "$PSScriptRoot\env.ps1"
$target = if ($Device) { @('-s', $Device) } else { @() }
if ($Clear) { & $Adb @target logcat -c; return }
$out = Join-Path $Root ("logs\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $out | Out-Null
& $Adb @target logcat -d -v threadtime > "$out\logcat.txt"
& $Adb @target shell getprop > "$out\getprop.txt"
& $Adb @target shell cmd gpu vkjson > "$out\vkjson.json"
& $Adb @target shell dumpsys activity exit-info $AppId > "$out\exit-info.txt"
& $Adb @target shell dumpsys meminfo $AppId > "$out\meminfo.txt"
& $Adb @target pull "/sdcard/Android/data/$AppId/files/logs" "$out\game-logs" 2>$null
Write-Output $out
