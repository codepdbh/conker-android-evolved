# The tools every script here uses, found the same way. Dot-source it: . "$PSScriptRoot\env.ps1"
# not Stop: Windows PowerShell takes any line a tool writes to stderr, as CMake's warnings, for a failure. Each script reads
# $LASTEXITCODE and throws
$ErrorActionPreference = 'Continue'
$script:Root = Split-Path -Parent $PSScriptRoot
$NdkVersion = '28.2.13676358'
$CMakeVersion = '3.31.6'

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME "ndk\$NdkVersion"
# Gradle wants a JDK 17 to 21: Android Studio's own, not whatever else is on the machine
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
$cmakeBin = Join-Path $env:ANDROID_HOME "cmake\$CMakeVersion\bin"
$env:Path = "$cmakeBin;$(Join-Path $env:ANDROID_HOME 'platform-tools');$env:Path"
$script:Adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$script:AppId = 'com.conker.androidevolved'
$script:ApkDebug = Join-Path $Root 'out\build\android\app\outputs\apk\debug\app-debug.apk'
