"""Host checks of the actual waveform gesture method; no device or playback."""
from pathlib import Path
import tempfile
import subprocess

root=Path(__file__).resolve().parents[1]
source=(root/'TMessagesProj/src/main/java/org/telegram/ui/Components/SeekBarWaveform.java').read_text(encoding='utf-8')
start=source.index('    private boolean onVoiceTouch(')
end=source.index('\n    public float getProgress()',start)
method=source[start:end]
harness='''public class VoiceSeekTest {
static class MotionEvent {static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;}
static class Message {float forceSeekTo=-1;}
static class MediaController {static boolean playing;static MediaController getInstance(){return new MediaController();}boolean isPlayingMessage(Message m){return playing;}}
static class Delegate {int committed;float target,preview;void onSeekBarPressed(){}void onSeekBarReleased(){}void onSeekBarDrag(float p){committed++;target=p;}void onSeekBarContinuousDrag(float p){preview=p;}}
int width=200,height=30,thumbX=40;boolean pressed,startDraging;float startX,voiceStartY,voiceLastX,voiceOriginalProgress,voicePosition,progress=.2f;
Message messageObject=new Message();Delegate delegate=new Delegate();
static int dp(int x){return x;}void invalidate(){}float getProgress(){return thumbX/(float)width;}void setProgress(float p){progress=p;thumbX=Math.round(p*width);}
''' + method + '''
static void check(boolean b){if(!b)throw new AssertionError();}
public static void main(String[] args){
 VoiceSeekTest v=new VoiceSeekTest();MediaController.playing=true;
 check(v.onVoiceTouch(0,100,15));v.onVoiceTouch(1,100,15);check(v.startDraging&&Math.abs(v.delegate.target-.5f)<.0001&&v.delegate.committed==1);
 v=new VoiceSeekTest();MediaController.playing=false;v.onVoiceTouch(0,100,15);v.onVoiceTouch(1,100,15);check(!v.startDraging&&v.delegate.committed==0);
 v=new VoiceSeekTest();v.onVoiceTouch(0,50,15);check(!v.onVoiceTouch(2,51,35)&&!v.pressed&&v.delegate.committed==0);
 v=new VoiceSeekTest();v.onVoiceTouch(0,50,15);v.onVoiceTouch(2,100,15);v.onVoiceTouch(2,101,80);v.onVoiceTouch(2,102,80);v.onVoiceTouch(1,102,80);check(Math.abs(v.delegate.target-.5025f)<.00001&&Math.abs(v.messageObject.forceSeekTo-.5025f)<.00001);
 v=new VoiceSeekTest();v.onVoiceTouch(0,50,15);v.onVoiceTouch(2,150,15);v.onVoiceTouch(3,150,15);check(v.delegate.committed==0&&Math.abs(v.progress-.2f)<.00001&&!v.pressed);
 v=new VoiceSeekTest();check(v.onVoiceTouch(0,50,-7));v.onVoiceTouch(2,999,15);v.onVoiceTouch(1,999,15);check(v.delegate.target==1f);
 v=new VoiceSeekTest();check(!v.onVoiceTouch(0,-1,10));check(!v.onVoiceTouch(0,50,-9));
 System.out.println("Voice seeking: tap, paused/current distinction, scroll, precision, cancel, bounds passed (host doubles).");
}}
'''
with tempfile.TemporaryDirectory(prefix='voice-seek-') as temp:
    test=Path(temp)/'VoiceSeekTest.java';test.write_text(harness,encoding='utf-8')
    subprocess.run(['javac','-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp,'VoiceSeekTest'],check=True)
