"""Run the actual supplied plugin ports and attachment reference ordering on the host."""
from pathlib import Path
import subprocess
import sys
import tempfile
import types

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'TMessagesProj_Ghost/src/main/python'))

import plugin_runtime as runtime
for name, plugin_id in (('no_forward_limit.plugin', 'zwyNoForwardLimit'), ('round_client_detector.plugin', 'round_client_detector')):
    path = Path('C:/Users/vrfox/Downloads/Telegram Desktop') / name
    assert runtime._builtin_feature_plugin_id(str(path)) == plugin_id
with tempfile.TemporaryDirectory(prefix='ghost-native-plugin-test-') as temp:
    path=Path(temp)/'other.plugin'
    path.write_text('__id__ = "other"\nraise Exception("must not execute during metadata scan")\n',encoding='utf-8')
    assert runtime._builtin_feature_plugin_id(str(path)) is None

# Compile the production comparator directly, with adversarial history loading order.
source = (ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostAttachmentHistory.java').read_text(encoding='utf-8')
start = source.index('    static final class Reference {')
reference = source[start:source.rfind('\n}')]
java = '''public class ReferenceTest {
%s
static void check(boolean ok) { if (!ok) throw new AssertionError(); }
public static void main(String[] args) {
Reference older = new Reference(10, 1, 100);
Reference later = new Reference(20, 2, 200);
check(older.before(later)); check(!later.before(older));
check(older.same(new Reference(10, 1, 100)));
check(!older.same(new Reference(20, 1, 100)));
check(older.before(new Reference(20, 1, 100)));
check(older.before(new Reference(10, 2, 100)));
System.out.println("PASS: reference identity, chronology and equal timestamp ordering");
}}
''' % reference
with tempfile.TemporaryDirectory(prefix='ghost-message-tools-') as directory:
    path = Path(directory) / 'ReferenceTest.java'
    path.write_text(java, encoding='utf-8')
    subprocess.run(['javac', str(path)], check=True)
    subprocess.run(['java', '-cp', directory, 'ReferenceTest'], check=True)
    metadata=ROOT/'TMessagesProj/src/main/java/org/telegram/messenger/GhostRoundMetadata.java'
    probe=Path(directory)/'RoundMetadataTest.java'
    probe.write_text("""import org.telegram.messenger.GhostRoundMetadata;
public class RoundMetadataTest {
static void check(boolean ok) { if (!ok) throw new AssertionError(); }
public static void main(String[] args) {
check(GhostRoundMetadata.tag(null).equals("Android"));
check(GhostRoundMetadata.tag("video.mp4").equals("iOS"));
check(GhostRoundMetadata.tag("ROUND_2026-10-09.mp4").equals("Win"));
check(GhostRoundMetadata.tag("other.mp4").equals("custom"));
byte[] apple = new byte[]{0,0,0,24,'f','t','y','p','q','t',' ',' '};
check(GhostRoundMetadata.tag(null, apple).equals("iOS"));
byte[] ffmpeg = new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m','L','a','v','f'};
check(GhostRoundMetadata.tag(null, ffmpeg).equals("Win"));
check(GhostRoundMetadata.tag(null, new byte[]{1,2,3}).equals("Android"));
System.out.println("PASS: native metadata heuristic, MP4 header and incomplete input");
}}""",encoding='utf-8')
    subprocess.run(['javac','-d',directory,str(metadata),str(probe)],check=True)
    subprocess.run(['java','-cp',directory,'RoundMetadataTest'],check=True)

actions = (ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostMessageActions.java').read_text(encoding='utf-8')
remote_only = actions[actions.index('public static void deleteRemoteKeepLocal'):actions.index('/** A detached')]
assert 'request.revoke = true' in remote_only
assert 'forgetMessages(' not in remote_only and 'markMessagesAsDeleted(' not in remote_only
assert 'error != null' in remote_only and 'processNewDifferenceParams' in remote_only
assert 'data.reuse()' in actions and 'TLdeserialize' in actions
chat = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text(encoding='utf-8')
forward = (ROOT / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostForwardTools.java').read_text(encoding='utf-8')
cell = (ROOT / 'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text(encoding='utf-8')
assert 'return Integer.MAX_VALUE' in forward and 'setEnabled(' not in forward
assert 'lazilyAddSubItem(24051' in chat and 'lazilyAddSubItem(24052' in chat
assert 'case OPTION_GHOST_ROUND_INFO' in chat and 'GhostRoundMetadata.tag' in cell
print('PASS: native feature entry points; no plugin switches; retired plugin IDs recognized without execution; remote-only deletion contract')
