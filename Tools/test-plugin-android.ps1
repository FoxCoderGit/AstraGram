param([string]$DeviceSerial = '', [string]$ScriptPath = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:USERPROFILE 'sdk' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not $DeviceSerial) {
    $devices = @(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device\b') { $Matches[1] } })
    if ($devices.Count -ne 1) { throw 'Connect one authorized phone or pass -DeviceSerial.' }
    $DeviceSerial = $devices[0]
}
$output = Join-Path $env:LOCALAPPDATA 'Temp/ghostgram-contract-tests'
$null = New-Item -ItemType Directory -Force -Path "$output/classes", "$output/dex", "$output/assets"
if (-not $ScriptPath) { $ScriptPath = "$root/tests/plugins/contract_device.py" }
Copy-Item -LiteralPath $ScriptPath -Destination "$output/assets/contract_device.py"
$runtimeJar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.chaquo.python.runtime/chaquopy_java/16.1.0" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
if (-not $runtimeJar) { throw 'Build the app first to resolve the Chaquopy runtime.' }
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-36/android.jar'
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0'
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 8 -classpath "$androidJar;$runtimeJar" -d "$output/classes" "$root/tests/plugins/ContractInstrumentation.java"
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation compilation failed' }
& "$env:JAVA_HOME/bin/jar.exe" cf "$output/classes.jar" -C "$output/classes" .
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation packaging failed' }
& "$buildTools/d8.bat" --min-api 24 --lib $androidJar --classpath $runtimeJar --output "$output/dex" "$output/classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation D8 failed' }
Copy-Item -LiteralPath "$root/tests/plugins/AndroidManifest.xml" -Destination "$output/AndroidManifest.xml"
& "$buildTools/aapt2.exe" link --manifest "$output/AndroidManifest.xml" -I $androidJar -A "$output/assets" -o "$output/unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation manifest failed' }
$env:GHOST_CONTRACT_TEST_OUTPUT = $output
@'
import os,zipfile
from pathlib import Path
folder=Path(os.environ['GHOST_CONTRACT_TEST_OUTPUT'])
with zipfile.ZipFile(folder/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as archive:
    archive.write(folder/'dex/classes.dex','classes.dex')
'@ | python -
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation dex copy failed' }
& "$buildTools/zipalign.exe" -f 4 "$output/unsigned.apk" "$output/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation alignment failed' }
& "$buildTools/apksigner.bat" sign --ks "$root/../Private/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/contract-tests.apk" "$output/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation signing failed' }
& $adb -s $DeviceSerial install -r -t "$output/contract-tests.apk"
if ($LASTEXITCODE -ne 0) { throw 'Contract instrumentation installation failed' }
try {
    # Same-signature instrumentation uses synthetic fixtures and never sends messages.
    $result = & $adb -s $DeviceSerial shell am instrument -w -r org.ghostgram.contracttests/org.ghostgram.contracttests.ContractInstrumentation
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -match 'FAIL:|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') -or -not ($result -match 'INSTRUMENTATION_CODE: -1')) { throw 'Android plugin contract checks failed' }
} finally {
    # Remove only this task's helper package, never GhostGram or its data.
    & $adb -s $DeviceSerial uninstall org.ghostgram.contracttests
}
