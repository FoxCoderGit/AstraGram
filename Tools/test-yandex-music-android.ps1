param([string]$DeviceSerial = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$studioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
if (Test-Path $studioJdk) { $env:JAVA_HOME = $studioJdk }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:USERPROFILE 'sdk' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not $DeviceSerial) {
    $devices = @(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device\b') { $Matches[1] } })
    if ($devices.Count -ne 1) { throw 'Connect one authorized phone or pass -DeviceSerial.' }
    $DeviceSerial = $devices[0]
}
$output = Join-Path $root 'build/yandex-music-android-tests'
$null = New-Item -ItemType Directory -Force -Path "$output/classes", "$output/dex"
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 8 -classpath "$env:ANDROID_HOME/platforms/android-36/android.jar" -d "$output/classes" (Join-Path $root 'tests/yandex-music/GhostYandexMusicAndroidSmoke.java') (Join-Path $root 'tests/yandex-music/GhostYandexSessionSmoke.java') (Join-Path $root 'tests/yandex-music/GhostOptionalForwardSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Android smoke compilation failed' }
& "$env:JAVA_HOME/bin/jar.exe" cf "$output/classes.jar" -C "$output/classes" .
if ($LASTEXITCODE -ne 0) { throw 'Android smoke packaging failed' }
& "$env:ANDROID_HOME/build-tools/36.0.0/d8.bat" --min-api 24 --lib "$env:ANDROID_HOME/platforms/android-36/android.jar" --output "$output/dex" "$output/classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'D8 smoke compilation failed' }
& "$env:JAVA_HOME/bin/jar.exe" cf "$output/device-test.jar" -C "$output/dex" classes.dex
& $adb -s $DeviceSerial push "$output/device-test.jar" /data/local/tmp/ghostgram-music-smoke.jar
if ($LASTEXITCODE -ne 0) { throw 'Android smoke copy failed' }
try {
    & $adb -s $DeviceSerial shell chmod 444 /data/local/tmp/ghostgram-music-smoke.jar
    $installedApk = ((& $adb -s $DeviceSerial shell pm path org.ghostgram.messenger) -replace '^package:', '').Trim()
    if ($installedApk -notmatch '^/data/app/[A-Za-z0-9_~+/=.-]+/base\.apk$') { throw 'Unexpected package path' }
    # Runs parsing and missing-network checks from the installed APK under the shell UID. No app data access.
    & $adb -s $DeviceSerial shell "CLASSPATH=/data/local/tmp/ghostgram-music-smoke.jar:$installedApk app_process /system/bin GhostYandexMusicAndroidSmoke"
    if ($LASTEXITCODE -ne 0) { throw 'Android installed APK smoke failed' }
    & $adb -s $DeviceSerial shell "CLASSPATH=/data/local/tmp/ghostgram-music-smoke.jar:$installedApk app_process /system/bin GhostYandexSessionSmoke"
    if ($LASTEXITCODE -ne 0) { throw 'Android shared session smoke failed' }
    & $adb -s $DeviceSerial shell "CLASSPATH=/data/local/tmp/ghostgram-music-smoke.jar:$installedApk app_process /system/bin GhostOptionalForwardSmoke"
    if ($LASTEXITCODE -ne 0) { throw 'Android optional features / protected copy smoke failed' }
} finally {
    & $adb -s $DeviceSerial shell rm -f /data/local/tmp/ghostgram-music-smoke.jar
}
