# Installs into the Android SDK what the build needs, by the SDK's own sdkmanager. The rest (Zig, SDL and the libraries)
# the first build fetches at the versions cmake/ pins.
. "$PSScriptRoot\env.ps1"
$sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) { throw "no sdkmanager at ${sdkmanager}: install Android Studio and its command-line tools" }
& $sdkmanager --install "ndk;$NdkVersion" "cmake;$CMakeVersion" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
if ($LASTEXITCODE) { throw "sdkmanager failed ($LASTEXITCODE); licences not accepted? Run: `"$sdkmanager`" --licenses" }
& "$PSScriptRoot\check_environment.ps1"
