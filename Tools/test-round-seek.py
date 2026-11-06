"""Run the production round gesture and controller seek methods with host doubles."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
cell = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text(encoding='utf-8')
media = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java').read_text(encoding='utf-8')
gesture = cell[cell.index('    private boolean checkRoundSeekbar('):cell.index('    private boolean checkPhotoImageMotionEvent(')]
seeks = media[media.index('    public boolean seekToProgress('):media.index('    public long getProgressMs(')]
java = r'''
public class RoundSeekTest {
static int checks;
static void check(boolean ok) {checks++; if(!ok) throw new AssertionError("check " + checks);}
static class C {static final long TIME_UNSET=-9223372036854775807L;}
static class MessageObject {float audioProgress; int audioProgressSec,audioProgressMs,currentAccount; long duration=60;
 long getDuration(){return duration;} int getId(){return 1;}}
static class VideoPlayer {long duration=60000,position; long getDuration(){return duration;}long getCurrentPosition(){return position;} void seekTo(long p){position=p;}}
static class AndroidUtilities {static void runOnUIThread(Runnable r,int ms){}}
static class GhostRoundSeekTrace {static boolean enabled(){return false;}static void write(String s){}}
static class Utilities {static float clamp01(float v){return Math.max(0,Math.min(1,v));}}
static class CastSync {static void seekTo(long p){}}
static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
static class NotificationCenter {static final int messagePlayingDidSeek=1; static NotificationCenter getInstance(int a){return new NotificationCenter();} void postNotificationName(int n,Object...args){}}
static class MediaController {
 static MediaController instance;
 static MediaController getInstance(){return instance;}
 VideoPlayer audioPlayer,videoPlayer=new VideoPlayer(); MessageObject playingMessageObject;
 float seekToProgressPending;long lastProgress;boolean ignorePlayerUpdate,isPaused,paused=true;int plays;
 boolean isSamePlayingMessage(MessageObject m){return playingMessageObject!=null&&m.getId()==playingMessageObject.getId();}
 boolean isPlayingMessage(MessageObject m){return isSamePlayingMessage(m);}
 boolean isMessagePaused(){return paused;}
 boolean playMessage(MessageObject m){paused=false;plays++;return true;}
''' + seeks + r'''
}
static class MotionEvent {static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;int action;float x,y;
 MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}int getAction(){return action;}}
static class HapticFeedbackConstants {static final int KEYBOARD_TAP=1;}
static class Parent {boolean disallowed;void requestDisallowInterceptTouchEvent(boolean v){disallowed=v;}}
static class Photo {float getCenterX(){return 100;}float getCenterY(){return 100;}int getImageWidth(){return 200;}}
static class Cell {
 boolean isRoundVideo=true;MessageObject currentMessageObject;Parent parent=new Parent();Photo photoImage=new Photo();
 int roundSeekbarTouched;float seekbarRoundX=100,seekbarRoundY=0,roundSeekbarOutAlpha,roundSeekbarOutProgress;long lastSeekUpdateTime;
 Parent getParent(){return parent;}int dp(int x){return x;}float getEventX(MotionEvent e){return e.x;}float getEventY(MotionEvent e){return e.y;}
 void invalidate(){}void cancelCheckLongPress(){}void updatePlayingMessageProgress(){}void performHapticFeedback(int f){}
''' + gesture + r'''
}
static Cell setup(){MediaController.instance=new MediaController();Cell c=new Cell();c.currentMessageObject=new MessageObject();
 MediaController.instance.playingMessageObject=new MessageObject();return c;}
public static void main(String[] args){
 Cell c=setup();MediaController m=MediaController.instance;
 check(c.checkRoundSeekbar(new MotionEvent(0,100,0)));
 check(c.checkRoundSeekbar(new MotionEvent(2,200,100)));
 check(m.videoPlayer.position==15000);
 check(c.checkRoundSeekbar(new MotionEvent(1,100,200)));
 check(m.videoPlayer.position==30000&&m.plays==1&&!m.paused);
 check(c.roundSeekbarTouched==0&&!c.parent.disallowed);
 check(m.playingMessageObject.audioProgress==.5f&&m.playingMessageObject.audioProgressSec==30);
 check(!c.checkRoundSeekbar(new MotionEvent(1,0,100)));
 c=setup();m=MediaController.instance;c.checkRoundSeekbar(new MotionEvent(0,100,0));
 check(c.checkRoundSeekbar(new MotionEvent(3,100,200)));
 check(m.plays==0&&m.paused&&c.roundSeekbarTouched==0&&!c.parent.disallowed);
 c=setup();c.isRoundVideo=false;check(!c.checkRoundSeekbar(new MotionEvent(0,100,0)));
 c=setup();m=MediaController.instance;m.videoPlayer.duration=C.TIME_UNSET;
 check(m.seekToProgress(c.currentMessageObject,.75f)&&m.videoPlayer.position==45000);
 m.playingMessageObject.audioProgressMs=1234;c.currentMessageObject.audioProgressMs=1234;
 check(m.seekToProgress(c.currentMessageObject,.25f)&&m.lastProgress==15000);
 check(m.playingMessageObject.audioProgressMs==0&&c.currentMessageObject.audioProgressMs==0);
 check(m.seekToProgressMs(c.currentMessageObject,42000)&&m.videoPlayer.position==42000);
 check(Math.abs(m.playingMessageObject.audioProgress-.7f)<.0001&&m.playingMessageObject.audioProgressSec==42);
 check(m.seekToProgressMs(c.currentMessageObject,999999)&&m.videoPlayer.position==60000);
 check(m.seekToProgressMs(c.currentMessageObject,-20)&&m.videoPlayer.position==0);
 c=setup();m=MediaController.instance;m.audioPlayer=new VideoPlayer();m.videoPlayer=null;
 check(m.seekToProgress(c.currentMessageObject,.5f)&&m.audioPlayer.position==30000);
 System.out.println("PASS: "+checks+" round seek/release/cancel/duration/state checks (production methods, host doubles)");
}}
'''
jbr = Path('C:/Program Files/Android/Android Studio/jbr/bin')
with tempfile.TemporaryDirectory(prefix='ghost-round-seek-') as tmp:
    path = Path(tmp) / 'RoundSeekTest.java'
    path.write_text(java, encoding='utf-8')
    trace = Path(tmp) / 'org/telegram/messenger/GhostRoundSeekTrace.java'
    trace.parent.mkdir(parents=True)
    trace.write_text('package org.telegram.messenger; public class GhostRoundSeekTrace {public static void write(String s){}}', encoding='utf-8')
    subprocess.run([str(jbr / 'javac.exe'), '-encoding', 'UTF-8', '-d', tmp, str(path), str(trace)], check=True)
    subprocess.run([str(jbr / 'java.exe'), '-cp', tmp, 'RoundSeekTest'], check=True)
