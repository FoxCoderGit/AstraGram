$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build/ghost-mode-tests'
$null = New-Item -ItemType Directory -Force -Path $output
$utf8 = New-Object System.Text.UTF8Encoding($false)
$sources = Join-Path $root 'TMessagesProj/src/main/java'
$test = Join-Path $root 'tests/ghost-mode/GhostModePolicyTest.java'
$testSource = [IO.File]::ReadAllText($test)
$protocolSource = [IO.File]::ReadAllText((Join-Path $sources 'org/telegram/tgnet/TLRPC.java'))
$types = [regex]::Matches($testSource, 'new TLRPC\.(\w+)\(') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
$declarations = foreach ($type in $types) {
    if ($protocolSource -notmatch "class $type\s") { throw "Request type missing from upstream: $type" }
    "public static class $type extends TLObject {}"
}
# Compile the production policy with minimal protocol type doubles. This tests the
# actual policy but does not claim to test serialization, JNI, UI or live Telegram.
[IO.File]::WriteAllText((Join-Path $output 'TLObject.java'), 'package org.telegram.tgnet; public class TLObject {}', $utf8)
[IO.File]::WriteAllText((Join-Path $output 'TLRPC.java'), "package org.telegram.tgnet; public class TLRPC { $($declarations -join [Environment]::NewLine) }", $utf8)
[IO.File]::WriteAllText((Join-Path $output 'TL_account.java'), 'package org.telegram.tgnet.tl; public class TL_account { public static class updateStatus extends org.telegram.tgnet.TLObject { public boolean offline; } }', $utf8)
[IO.File]::WriteAllText((Join-Path $output 'TL_stories.java'), 'package org.telegram.tgnet.tl; public class TL_stories { public static class TL_stories_readStories extends org.telegram.tgnet.TLObject {} public static class TL_stories_incrementStoryViews extends org.telegram.tgnet.TLObject {} }', $utf8)
& javac -encoding UTF-8 -d $output (Join-Path $output 'TLObject.java') (Join-Path $output 'TLRPC.java') (Join-Path $output 'TL_account.java') (Join-Path $output 'TL_stories.java') (Join-Path $sources 'org/telegram/messenger/GhostModePolicy.java') $test (Join-Path $root 'tests/ghost-mode/ParseJava.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $output GhostModePolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Policy tests failed' }
$changedJava = @('messenger/GhostMode.java', 'messenger/GhostModePolicy.java', 'messenger/MessagesController.java', 'messenger/SecretChatHelper.java', 'tgnet/ConnectionsManager.java', 'ui/DialogsActivity.java') | ForEach-Object { Join-Path $sources "org/telegram/$_" }
& java -cp $output ParseJava @changedJava
if ($LASTEXITCODE -ne 0) { throw 'Java syntax check failed' }
foreach ($locale in @('values', 'values-ru')) {
    [xml]$strings = [IO.File]::ReadAllText((Join-Path $root "TMessagesProj/src/main/res/$locale/strings.xml"))
    foreach ($key in @('GhostMode', 'GhostModeEnabled', 'GhostModeDisabled', 'GhostModeInfo')) {
        if (@($strings.resources.string | Where-Object { $_.name -eq $key }).Count -ne 1) { throw "Missing/duplicate $locale/$key" }
    }
}
Write-Output 'English and Russian resources: passed.'
