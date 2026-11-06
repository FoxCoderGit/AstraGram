$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr/bin'
$output = Join-Path $root 'build/yandex-relay-tests'
$null = New-Item -ItemType Directory -Force -Path $output
& "$jdk/javac.exe" -encoding UTF-8 --release 8 -d $output (Join-Path $root 'tests/yandex-music/GhostYandexRelayTlsTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Relay TLS test compilation failed.' }
& "$jdk/java.exe" -cp $output GhostYandexRelayTlsTest (Join-Path $root 'Tools/yandex-music-relay/relay-ca.pem')
if ($LASTEXITCODE -ne 0) { throw 'Relay Java TLS checks failed.' }
