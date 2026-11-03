# Installs the debug APK on the connected phone and opens the launcher. Keeps the app's data; -Clean deletes it first,
# which deletes the installed game and the saves too.
param([switch]$Clean, [string]$Device)
. "$PSScriptRoot\env.ps1"
$target = if ($Device) { @('-s', $Device) } else { @() }
if (-not (Test-Path $ApkDebug)) { throw "no APK at ${ApkDebug}: run scripts\build_android.ps1" }
if ($Clean) { & $Adb @target uninstall $AppId }
& $Adb @target install -r -t $ApkDebug
if ($LASTEXITCODE) { throw 'adb install failed. A phone may ask to allow installing over USB' }
& $Adb @target shell am start -n "$AppId/org.liveandrecompiled.app.SetupActivity"
