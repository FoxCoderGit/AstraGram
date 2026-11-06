$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build/yandex-music-tests'
$null = New-Item -ItemType Directory -Force -Path $output
$sources = Join-Path $root 'TMessagesProj/src/main/java'
& javac -encoding UTF-8 -d $output (Join-Path $sources 'org/telegram/messenger/GhostYandexMusicProtocol.java') (Join-Path $root 'tests/yandex-music/GhostYandexMusicProtocolTest.java') (Join-Path $root 'tests/ghost-mode/ParseJava.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $output GhostYandexMusicProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'Yandex Music protocol tests failed' }
$changedJava = @('messenger/GhostYandexMusicProtocol.java','messenger/GhostYandexMusicSession.java','messenger/GhostYandexMusicClient.java','messenger/GhostYandexMusicNetwork.java','messenger/GhostYandexMusicRelay.java','messenger/ApplicationLoader.java','messenger/SendMessagesHelper.java','ui/GhostYandexMusicActivity.java','ui/GhostYandexMusicAuthActivity.java','ui/Components/ChatAttachAlert.java','ui/Components/glass/GlassTabView.java') | ForEach-Object { Join-Path $sources "org/telegram/$_" }
$changedJava += Join-Path $root 'TMessagesProj_Ghost/src/main/java/org/ghostgram/messenger/GhostApplication.java'
& java -cp $output ParseJava @changedJava
if ($LASTEXITCODE -ne 0) { throw 'Java syntax check failed' }
