"""Test real focus selection helpers with local message/group doubles."""
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text(encoding='utf-8')
a=source.index('    private boolean isGhostSelectionMember(')
b=source.index('    private void removeGhostFocusOverlay()',a)
helpers=source[a:b]
code='''public class FocusSelectionTest {
static class MessageObject {
 long dialog;int id;MessageObject(long d,int i){dialog=d;id=i;}long getDialogId(){return dialog;}int getId(){return id;}
 static class GroupedMessages {java.util.ArrayList<MessageObject> messages=new java.util.ArrayList<>();}
}
static class ChatMessageCell {
 MessageObject message;MessageObject.GroupedMessages group;ChatMessageCell(MessageObject m){message=m;}
 MessageObject getMessageObject(){return message;}MessageObject.GroupedMessages getCurrentMessagesGroup(){return group;}
}
static class Selected extends java.util.LinkedHashMap<Integer,MessageObject> {MessageObject valueAt(int i){return new java.util.ArrayList<>(values()).get(i);}}
long dialog_id=100;Selected[] selectedMessagesIds={new Selected(),new Selected()};java.util.HashSet<String> ghostFocusedMessages=new java.util.HashSet<>();
''' + helpers + '''
static void check(boolean condition){if(!condition)throw new AssertionError();}
public static void main(String[] args){
 FocusSelectionTest t=new FocusSelectionTest();MessageObject a=new MessageObject(100,42),b=new MessageObject(100,43),c=new MessageObject(200,42);
 t.selectedMessagesIds[0].put(42,a);t.selectedMessagesIds[0].put(43,b);t.selectedMessagesIds[1].put(42,c);
 check(t.isGhostSelectionMember(a)&&t.isGhostSelectionMember(c));
 t.captureGhostFocusMessages(new ChatMessageCell(a));
 t.selectedMessagesIds[0].clear();t.selectedMessagesIds[1].clear();
 check(t.ghostMessageFocused(new ChatMessageCell(a)));check(t.ghostMessageFocused(new ChatMessageCell(b)));check(t.ghostMessageFocused(new ChatMessageCell(c)));
 check(!t.ghostMessageFocused(new ChatMessageCell(new MessageObject(300,42))));check(!t.ghostMessageFocused(new ChatMessageCell(new MessageObject(100,44))));
 ChatMessageCell album=new ChatMessageCell(new MessageObject(100,50));album.group=new MessageObject.GroupedMessages();album.group.messages.add(b);album.group.messages.add(album.message);
 check(t.ghostMessageFocused(album));t.ghostFocusedMessages.clear();check(!t.ghostMessageFocused(album));check(!t.ghostMessageFocused(null));
 System.out.println("Focus selection: multiple messages, merged dialog IDs, retained snapshot and album membership passed.");
}}
'''
with tempfile.TemporaryDirectory(prefix='focus-selection-') as temp:
    test=Path(temp)/'FocusSelectionTest.java';test.write_text(code,encoding='utf-8')
    subprocess.run(['javac','-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp,'FocusSelectionTest'],check=True)
