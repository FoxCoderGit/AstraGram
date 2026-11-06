$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'build/reaction-notification-tests'
$null = New-Item -ItemType Directory -Force -Path $output
$utf8 = New-Object System.Text.UTF8Encoding($false)
# Minimal protocol doubles; the selection logic is the actual production class.
$protocol = @'
package org.telegram.tgnet;
import java.util.ArrayList;
public class TLRPC {
    public static class Peer { public long user_id, chat_id, channel_id; }
    public static class Message { public int id; public boolean out; public Peer peer_id; }
    public static class Reaction {}
    public static class TL_reactionEmoji extends Reaction { public String emoticon; }
    public static class TL_reactionCustomEmoji extends Reaction { public long document_id; }
    public static class MessagePeerReaction { public Peer peer_id; public Reaction reaction; public boolean unread; public int date; }
    public static class TL_messageReactions { public boolean min; public ArrayList<MessagePeerReaction> recent_reactions = new ArrayList<>(); }
}
'@
$messageObject = @'
package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
    public static long getPeerId(TLRPC.Peer peer) {
        return peer == null ? 0 : peer.user_id != 0 ? peer.user_id : peer.chat_id != 0 ? -peer.chat_id : -peer.channel_id;
    }
}
'@
$reactionsUtils = @'
package org.telegram.ui.Components.Reactions;
import org.telegram.tgnet.TLRPC;
public class ReactionsUtils {
    public static boolean compare(TLRPC.Reaction a, TLRPC.Reaction b) {
        if (a instanceof TLRPC.TL_reactionEmoji && b instanceof TLRPC.TL_reactionEmoji)
            return java.util.Objects.equals(((TLRPC.TL_reactionEmoji)a).emoticon, ((TLRPC.TL_reactionEmoji)b).emoticon);
        if (a instanceof TLRPC.TL_reactionCustomEmoji && b instanceof TLRPC.TL_reactionCustomEmoji)
            return ((TLRPC.TL_reactionCustomEmoji)a).document_id == ((TLRPC.TL_reactionCustomEmoji)b).document_id;
        return false;
    }
}
'@
[IO.File]::WriteAllText((Join-Path $output 'TLRPC.java'), $protocol, $utf8)
[IO.File]::WriteAllText((Join-Path $output 'MessageObject.java'), $messageObject, $utf8)
[IO.File]::WriteAllText((Join-Path $output 'ReactionsUtils.java'), $reactionsUtils, $utf8)
$sources = Join-Path $root 'TMessagesProj/src/main/java/org/telegram/messenger'
& javac -encoding UTF-8 -d $output (Join-Path $output 'TLRPC.java') (Join-Path $output 'MessageObject.java') (Join-Path $output 'ReactionsUtils.java') (Join-Path $sources 'GhostReactionPolicy.java') (Join-Path $sources 'GhostReactionEvents.java') (Join-Path $root 'tests/reaction-notifications/GhostReactionPolicyTest.java') (Join-Path $root 'tests/ghost-mode/ParseJava.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -cp $output GhostReactionPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Reaction policy tests failed' }
$changedJava = @('MessagesController.java', 'MessagesStorage.java', 'NotificationsController.java', 'GhostReactionPolicy.java', 'GhostReactionEvents.java') | ForEach-Object { Join-Path $sources $_ }
& java -cp $output ParseJava @changedJava
if ($LASTEXITCODE -ne 0) { throw 'Java syntax check failed' }
