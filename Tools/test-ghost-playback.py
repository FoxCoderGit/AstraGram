"""Exercise the actual gain limiter and the bundled Media3 silence processor on PCM."""
from pathlib import Path
import subprocess, tempfile, os
ROOT=Path(__file__).resolve().parents[1]
COMMON=ROOT / "TMessagesProj_Modules/media/libraries/common/build/intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug"
EXO=ROOT / "TMessagesProj_Modules/media/libraries/exoplayer/build/intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug"
STUBS={
"org/telegram/messenger/GhostPlayback.java": "package org.telegram.messenger; public class GhostPlayback { public static volatile boolean boost; public static short amplify(short sample){return GhostAudioGain.apply(sample);} }",
"androidx/media3/common/util/Util.java": "package androidx.media3.common.util; public class Util { public static final byte[] EMPTY_BYTE_ARRAY=new byte[0]; public static boolean isEncodingLinearPcm(int e){return e==2;} public static int getPcmFrameSize(int e,int c){return 2*c;} }",
"androidx/media3/common/util/Assertions.java": "package androidx.media3.common.util; public class Assertions { public static void checkArgument(boolean b){if(!b)throw new IllegalArgumentException();} public static void checkArgument(boolean b,Object reason){checkArgument(b);} public static void checkState(boolean b){if(!b)throw new IllegalStateException();} public static void checkState(boolean b,Object reason){checkState(b);} }",
"PlaybackCheck.java": r'''import java.nio.*; import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor; import androidx.media3.common.audio.AudioProcessor; import org.telegram.messenger.GhostAudioGain;
public class PlaybackCheck {
 static int process(int channels,int gapMs,boolean enabled) throws Exception {
  SilenceSkippingAudioProcessor p=new SilenceSkippingAudioProcessor(700000L,.15f,800000L,10,(short)240);
  p.setEnabled(enabled); p.configure(new AudioProcessor.AudioFormat(48000,channels,2)); p.flush();
  if(!p.isActive()) return (1000+gapMs)*48;
  int totalFrames=(1000+gapMs)*48; int output=0;
  for(int start=0;start<totalFrames;start+=480){
   int count=Math.min(480,totalFrames-start);
   ByteBuffer b=ByteBuffer.allocateDirect(count*channels*2).order(ByteOrder.nativeOrder());
   for(int j=0;j<count;j++){int frame=start+j;short value=(short)((frame<24000||frame>=(500+gapMs)*48)?4000:0);for(int c=0;c<channels;c++)b.putShort(value);}
   b.flip();int guard=0;while(b.hasRemaining()){p.queueInput(b);output+=p.getOutput().remaining();if(++guard>1000)throw new AssertionError("PCM processor stalled");}
  }
  p.queueEndOfStream(); while(!p.isEnded())output+=p.getOutput().remaining();
  return output/(channels*2);
 }
 public static void main(String[] args) throws Exception {
  org.telegram.ui.Components.GhostBoostAudioProcessor gain=new org.telegram.ui.Components.GhostBoostAudioProcessor();
  gain.configure(new AudioProcessor.AudioFormat(48000,2,2)); gain.flush();
  gain.queueInput(AudioProcessor.EMPTY_BUFFER);
  if(gain.getOutput().hasRemaining())throw new AssertionError("Empty input emitted audio");
  for(boolean enabled:new boolean[]{false,true,false}){
   org.telegram.messenger.GhostPlayback.boost=enabled;
   ByteBuffer input=ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
   for(short sample:new short[]{0,3000,-3000,32000})input.putShort(sample); input.flip();
   gain.queueInput(input); ByteBuffer output=gain.getOutput();
   for(short sample:new short[]{0,3000,-3000,32000})if(output.getShort()!=(enabled?GhostAudioGain.apply(sample):sample))throw new AssertionError("Gain or passthrough changed PCM");
   gain.queueInput(output); gain.queueInput(AudioProcessor.EMPTY_BUFFER);
  }
  gain.queueEndOfStream(); if(!gain.isEnded())throw new AssertionError("Gain processor failed to end");
  System.out.println("PASS: actual gain processor handles empty/drained buffers, PCM passthrough, toggling and end of stream");
  if(GhostAudioGain.apply((short)0)!=0)throw new AssertionError("Silent audio became noisy");
  if(GhostAudioGain.apply((short)3000)<=5000)throw new AssertionError("Quiet audio not boosted");
  int previous=-32768;for(int x=-32768;x<=32767;x++){int y=GhostAudioGain.apply((short)x);if(y<previous)throw new AssertionError("Limiter wrapped or lost monotonicity");previous=y;}
  for(int channels:new int[]{1,2}){
   int shortPause=process(channels,200,true),longPause=process(channels,3000,true),original=process(channels,3000,false);
   if(shortPause!=1200*48)throw new AssertionError("Short pause cut: "+shortPause);
   if(longPause>=2000*48||longPause<1000*48)throw new AssertionError("Long silence not shortened safely: "+longPause);
   if(original!=4000*48)throw new AssertionError("Disabled skipping altered duration");
   System.out.println("PASS: "+channels+" channel PCM; 200 ms pause retained; 3000 ms pause shortened; disabled mode unchanged");
  }
  System.out.println("PASS: all 65536 PCM samples checked for limiter overflow and monotonicity; silence stays silent; quiet signal amplified");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix="ghost-playback-test-") as temporary:
 work=Path(temporary)
 for file,text in STUBS.items():
  p=work/file;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding="utf-8")
 cp=os.pathsep.join(map(str,[work/"classes",COMMON,EXO]))
 subprocess.run(["javac","-cp",cp,"-d",str(work/"classes"),*map(str,work.rglob("*.java")),str(ROOT/"TMessagesProj/src/main/java/org/telegram/messenger/GhostAudioGain.java"),str(ROOT/"TMessagesProj/src/main/java/org/telegram/ui/Components/GhostBoostAudioProcessor.java")],check=True)
 subprocess.run(["java","-cp",cp,"PlaybackCheck"],check=True)
