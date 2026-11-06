param([switch]$Install = $true, [switch]$CopyToPhone = $true, [string]$DeviceSerial = '')

# Always produce a normally installable APK and reject test-only output before handing it out.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bundleRoot = Split-Path -Parent $projectRoot
$studioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
if (Test-Path $studioJdk) { $env:JAVA_HOME = $studioJdk }
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = @((Join-Path $env:USERPROFILE 'sdk'), (Join-Path $env:LOCALAPPDATA 'Android/Sdk')) |
        Where-Object { Test-Path (Join-Path $_ 'platform-tools/adb.exe') } | Select-Object -First 1
}
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK.' }
$gradleArgs = @(':TMessagesProj_Ghost:assembleDebug', '-PghostOnly=true', '-Pandroid.injected.testOnly=false',
    '--max-workers=2', '--console=plain', '-I', (Join-Path $PSScriptRoot 'ghost-local-build.init.gradle'))
$signingStore = Join-Path $bundleRoot 'Private/debug.keystore'
if (Test-Path $signingStore) { $gradleArgs += "-PghostSigningStore=$signingStore" }
$offlineLsplant = Join-Path $bundleRoot 'Reports/lsplant-6.4-aliucord.4-arm64.aar'
if (Test-Path $offlineLsplant) { $gradleArgs += "-PghostOfflineLsplant=$offlineLsplant" }
$mappedDrive = $null
Push-Location $projectRoot
try {
    if ($projectRoot -match '[^\x00-\x7f]') {
        foreach ($candidate in @('T', 'U', 'V', 'W')) {
            if (-not (Test-Path ($candidate + ':/'))) {
                & subst.exe ($candidate + ':') $projectRoot
                if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary workspace drive.' }
                $mappedDrive = $candidate
                Set-Location ($candidate + ':/')
                break
            }
        }
        if (-not $mappedDrive) { throw 'No free drive letter for the temporary ASCII workspace.' }
    }
    # Windows PowerShell treats compiler warnings on stderr as ErrorRecords.
    # Use the native exit code to decide whether Gradle failed.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat @gradleArgs
    $privateBuildExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($privateBuildExitCode -ne 0) { throw 'APK build failed.' }
    Set-Location -LiteralPath $projectRoot
    & python Tools/license-server/verify_apk.py
    if ($LASTEXITCODE -ne 0) { throw 'APK distribution checks failed.' }
    $metadata = [IO.File]::ReadAllText((Join-Path $projectRoot 'TMessagesProj_Ghost/build/outputs/apk/debug/output-metadata.json')) | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $bundleRoot 'APK')
    $outputApk = Join-Path $bundleRoot "APK/AstraGram-$version.apk"
    Copy-Item -LiteralPath (Join-Path $projectRoot 'TMessagesProj_Ghost/build/outputs/apk/debug/TMessagesProj_Ghost-debug.apk') -Destination $outputApk -Force
    Write-Output "Verified APK: $outputApk"
    if ($CopyToPhone -or $Install) {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        if (-not $DeviceSerial) {
            $devices = @(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device\b') { $Matches[1] } })
            if ($devices.Count -ne 1) { throw 'Connect one authorized phone or pass -DeviceSerial.' }
            $DeviceSerial = $devices[0]
        }
        if ($CopyToPhone) {
            $phonePath = '/sdcard/Download/' + (Split-Path $outputApk -Leaf)
            & $adb -s $DeviceSerial push $outputApk $phonePath
            if ($LASTEXITCODE -ne 0) { throw 'Copy to internal storage failed.' }
            $remoteHash = (& $adb -s $DeviceSerial shell sha256sum $phonePath) -split '\s+' | Select-Object -First 1
            if ($LASTEXITCODE -ne 0 -or $remoteHash -ne (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()) {
                throw 'Phone copy checksum mismatch.'
            }
        }
    }
    if ($Install) {
        # No -t: this exercises the same test-only restriction as the normal Android installer.
        & $adb -s $DeviceSerial install -r $outputApk
        if ($LASTEXITCODE -ne 0) { throw 'Normal APK installation failed.' }
        $properties = [IO.File]::ReadAllText((Join-Path $projectRoot 'gradle.properties'))
        $versionCode = [regex]::Match($properties, '(?m)^APP_VERSION_CODE=(\d+)').Groups[1].Value
        $installed = & $adb -s $DeviceSerial shell dumpsys package org.ghostgram.messenger
        if (-not ($installed -match ('versionCode=' + $versionCode + '\b'))) { throw 'Installed version code does not match.' }
        Write-Output "Installed version code: $versionCode"
    }
} finally {
    Set-Location -LiteralPath $projectRoot
    if ($mappedDrive) { & subst.exe ($mappedDrive + ':') /D }
    Pop-Location
}
