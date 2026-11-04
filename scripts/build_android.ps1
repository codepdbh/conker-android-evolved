# The APK. Incremental: Ninja and Gradle redo only what changed.
#   build_android.ps1 -LauncherOnly   the launcher alone, no game code in it: needs no game, tests the phone
#   build_android.ps1                 with libmain.so, the game recompiled from assets\default.xbe
#   build_android.ps1 -AcceptVariant  the same from a copy that is not a supported release (docs\RECOMPILATION.md)
param([switch]$LauncherOnly, [switch]$AcceptVariant, [int]$Jobs = 0)
. "$PSScriptRoot\env.ps1"
Set-Location $Root
if (-not $Jobs) { $Jobs = [Math]::Max(1, [Environment]::ProcessorCount - 1) }
# a launcher to hand out starts clean: Gradle's incremental packaging leaves the room of a removed libmain.so in the APK
$gradleArgs = if ($LauncherOnly) { @('--no-daemon', '-q', ':app:clean', 'assembleDebug') } else { @('--no-daemon', '-q', 'assembleDebug') }
if (-not $LauncherOnly) {
    & "$PSScriptRoot\recompile_xbe.ps1" -AcceptVariant:$AcceptVariant
    cmake -DWHAT=zig-android -P tools\configure.cmake
    cmake --build out\build\zig-android --target Game -j $Jobs
    if ($LASTEXITCODE) { throw "the game's build failed ($LASTEXITCODE)" }
    $apk = 'out\build\zig-android\apk\arm64-v8a'
    New-Item -ItemType Directory -Force $apk | Out-Null
    Copy-Item out\build\zig-android\libmain.so $apk -Force
    # the launcher accepts the copy this build holds the addresses of, as libmain.so does (kImageSha1)
    $assets = 'out\build\zig-android\extra-assets'
    New-Item -ItemType Directory -Force $assets | Out-Null
    (Get-FileHash assets\default.xbe -Algorithm SHA1).Hash.ToLower() | Set-Content -Encoding ascii "$assets\accepted_xbe.txt"
    $gradleArgs += "-PnativeLibs=$Root\out\build\zig-android\apk", "-PextraAssets=$Root\$assets"
} else {
    # what is handed out: no game, and the tools for the phone to compile it from the player's copy
    cmake -DWHAT=zig-windows -P tools\configure.cmake
    cmake --build out\build\zig-windows --target xbe_recomp
    cmake -DWHAT=zig-android -P tools\configure.cmake
    cmake --build out\build\zig-android --target Game_android_kit xbe_recomp_device -j $Jobs
    if ($LASTEXITCODE) { throw "the kit's build failed ($LASTEXITCODE)" }
    python scripts\make_devkit.py
    if ($LASTEXITCODE) { throw "make_devkit failed ($LASTEXITCODE)" }
    $gradleArgs += "-PnativeLibs=$Root\out\build\zig-android\devkit\native", "-PextraAssets=$Root\out\build\zig-android\devkit\assets"
}
Push-Location src\android
try {
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE) { throw "Gradle failed ($LASTEXITCODE)" }
} finally { Pop-Location }
Get-Item $ApkDebug | Select-Object FullName, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
