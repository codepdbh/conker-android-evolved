# What this machine has of what the build needs, and the command for what it lacks. Exit 1 when something is missing.
. "$PSScriptRoot\env.ps1"
$missing = 0
function Check($name, $ok, $detail, $fix) {
    if ($ok) { Write-Output ("ok       {0,-14} {1}" -f $name, $detail) }
    else { Write-Output ("MISSING  {0,-14} {1}" -f $name, $fix); $script:missing++ }
}
$sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
Check 'Android SDK' (Test-Path $env:ANDROID_HOME) $env:ANDROID_HOME 'install Android Studio (https://developer.android.com/studio)'
Check 'NDK' (Test-Path $env:ANDROID_NDK_HOME) $NdkVersion "`"$sdkmanager`" `"ndk;$NdkVersion`""
Check 'CMake' (Test-Path (Join-Path $cmakeBin 'cmake.exe')) $CMakeVersion "`"$sdkmanager`" `"cmake;$CMakeVersion`""
Check 'Ninja' (Test-Path (Join-Path $cmakeBin 'ninja.exe')) 'with the SDK''s CMake' "`"$sdkmanager`" `"cmake;$CMakeVersion`""
Check 'Platform 36' (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-36')) 'android-36' "`"$sdkmanager`" `"platforms;android-36`""
Check 'adb' (Test-Path $Adb) 'platform-tools' "`"$sdkmanager`" platform-tools"
Check 'JDK 17-21' ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) $env:JAVA_HOME 'install Android Studio, or a JDK 21 and set JAVA_HOME'
Check 'Git' ([bool](Get-Command git -ErrorAction SilentlyContinue)) 'on PATH' 'winget install --id Git.Git -e'
Check 'Python' ([bool](Get-Command python -ErrorAction SilentlyContinue)) 'on PATH' 'winget install --id Python.Python.3.12 -e'
$zig = Get-ChildItem (Join-Path $Root 'toolchain') -Filter 'zig-*' -ErrorAction SilentlyContinue | Select-Object -First 1
Check 'Zig' $true $(if ($zig) { $zig.Name } else { 'downloaded by the first build (pinned, SHA-256 checked)' }) ''
$xbe = Join-Path $Root 'assets\default.xbe'
Check 'Game' (Test-Path $xbe) 'assets\default.xbe' "python scripts\extract_game.py <your.iso> --out assets --only default.xbe"
if ($Root -match '\s') { Write-Output "WARNING  this folder's path has a space: the build is only tested without one" }
if ($Root -match 'OneDrive') { Write-Output 'WARNING  this folder syncs to OneDrive: generated\ holds code derived from your game' }
exit $(if ($missing) { 1 } else { 0 })
