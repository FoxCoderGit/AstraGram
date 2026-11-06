"""Regression checks of real production playback methods with host player doubles."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
def method(source,signature):
    a=source.index(signature);b=source.index('{',a);depth=1;i=b+1
    while depth:
        depth+=(source[i]=='{')-(source[i]=='}');i+=1
    return source[a:i]
video=(root/'TMessagesProj/src/main/java/org/telegram/ui/Components/VideoPlayer.java').read_text(encoding='utf-8')
media=(root/'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java').read_text(encoding='utf-8')
code='''public class PlaybackIsolationTest {
 static class GhostPlayback {static boolean skipSilence;}
 static class Player {boolean skip;float speed=1;int calls;void setSkipSilenceEnabled(boolean b){skip=b;}void setPlaybackSpeed(float s){speed=s;calls++;}void pause(){}void play(){}}
 static class Video {
  boolean ghostSpeechAudio;Player player=new Player(),audioPlayer=new Player();
 '''+method(video,'public void setGhostSpeechAudio(')+method(video,'public void updateGhostPlayback(')+'''
 }
 static class MessageObject {boolean music;float audioProgress;MessageObject(boolean m){music=m;}boolean isMusic(){return music;}}
 static class Settings {Settings edit(){return this;}Settings putFloat(String key,float v){return this;}void commit(){}}
 static class MessagesController {static Settings getGlobalMainSettings(){return new Settings();}}
 static class NotificationCenter {static int messagePlayingSpeedChanged;static NotificationCenter getGlobalInstance(){return new NotificationCenter();}void postNotificationName(int n){}}
 static class CastSync {static int calls;static void setSpeed(float f){calls++;}}
 static class AndroidUtilities {static void runOnUIThread(Runnable r,int ms){}}
 Player audioPlayer=new Player(),videoPlayer;MessageObject playingMessageObject;
 float currentMusicPlaybackSpeed=1,currentPlaybackSpeed=1,fastMusicPlaybackSpeed,fastPlaybackSpeed;
 boolean ignorePlayerUpdate,isPaused;
 boolean isSamePlayingMessage(MessageObject m){return m==playingMessageObject;}void seekToProgress(MessageObject m,float p){}
 '''+method(media,'public void setPlaybackSpeed(boolean music, float speed)')+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Video ordinary=new Video(),voice=new Video();voice.setGhostSpeechAudio(true);
  GhostPlayback.skipSilence=true;ordinary.updateGhostPlayback();voice.updateGhostPlayback();
  check(!ordinary.player.skip&&!ordinary.audioPlayer.skip&&voice.player.skip&&voice.audioPlayer.skip);
  voice.setGhostSpeechAudio(false);check(!voice.player.skip&&!voice.audioPlayer.skip);
  voice.setGhostSpeechAudio(true);GhostPlayback.skipSilence=false;voice.updateGhostPlayback();check(!voice.player.skip&&!voice.audioPlayer.skip);
  PlaybackIsolationTest c=new PlaybackIsolationTest();c.playingMessageObject=new MessageObject(true);
  c.setPlaybackSpeed(false,2f);check(c.currentPlaybackSpeed==2&&c.audioPlayer.calls==0&&CastSync.calls==0);
  c.setPlaybackSpeed(true,1.5f);check(c.audioPlayer.speed==1.5f&&c.audioPlayer.calls==1&&CastSync.calls==1);
  c.playingMessageObject=new MessageObject(false);c.setPlaybackSpeed(true,2f);check(c.audioPlayer.speed==1.5f&&c.audioPlayer.calls==1&&CastSync.calls==1);
  c.setPlaybackSpeed(false,1f);check(c.audioPlayer.speed==1&&c.audioPlayer.calls==2&&CastSync.calls==2);
  c.audioPlayer=null;c.videoPlayer=new Player();c.currentMusicPlaybackSpeed=13;c.setPlaybackSpeed(true,1f);check(c.videoPlayer.calls==0);
  c.setPlaybackSpeed(false,1.5f);check(c.videoPlayer.speed==1.5f&&c.videoPlayer.calls==1);
  c.playingMessageObject=null;c.setPlaybackSpeed(false,2f);check(c.videoPlayer.calls==1);
  System.out.println("Playback isolation passed: silence only for speech, live disable, independent music/voice controls, no active player, video target and null-safe rewind reset (production methods, host doubles).");
 }
}'''
with tempfile.TemporaryDirectory(prefix='ghost-playback-isolation-') as directory:
    file=Path(directory)/'PlaybackIsolationTest.java';file.write_text(code,encoding='utf-8')
    subprocess.run(['javac','-d',directory,str(file)],check=True)
    subprocess.run(['java','-cp',directory,'PlaybackIsolationTest'],check=True)
