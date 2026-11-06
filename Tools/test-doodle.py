"""Run the actual platform physics on the host JVM and check the send interception."""
from pathlib import Path
import subprocess, tempfile

root = Path(__file__).resolve().parents[1]
code = r'''import org.telegram.messenger.GhostDoodlePhysics;
public class DoodleCheck {
 public static void main(String[] args) {
  for(float width:new float[]{280,360,600}){
   GhostDoodlePhysics game=new GhostDoodlePhysics(width,720);
   for(int i=0;i<100;i++)game.addPlatform();
   for(int i=1;i<100;i++){
    GhostDoodlePhysics.Platform a=game.platforms.get(i-1),b=game.platforms.get(i);
    if(b.y-a.y!=GhostDoodlePhysics.GAP || b.x<0 || b.x+b.width>width)throw new AssertionError("Invalid spacing or screen bounds");
    if(Math.abs(a.x+a.width/2-b.x-b.width/2)>128)throw new AssertionError("Unreachable horizontal gap");
   }
   for(int frame=0;frame<30000&&game.reached<99&&!game.lost;frame++){
    int target=Math.min(99,game.reached+1);
    GhostDoodlePhysics.Platform platform=game.platforms.get(target);
    game.step(1f/60,platform.x+platform.width/2);
   }
   if(game.lost || game.reached!=99 || game.camera<=0)throw new AssertionError("Could not climb 100 messages at width="+width+" reached="+game.reached);
   game.restart();
   if(game.lost||game.reached!=0||game.camera!=0)throw new AssertionError("Restart failed");
   System.out.println("PASS: 100 reachable platforms, monotonic vertical order, camera and restart; width="+width);
  }
  GhostDoodlePhysics fall=new GhostDoodlePhysics(360,720);fall.addPlatform();
  for(int i=0;i<1000&&!fall.lost;i++)fall.step(1f/60,12f);
  if(!fall.lost)throw new AssertionError("Falling did not end the game");
  System.out.println("PASS: falling ends game");
  for(float width:new float[]{280,360,600}) {
   GhostDoodlePhysics game=new GhostDoodlePhysics(width,720);
   float bubble=width*.42f;
   for(int i=0;i<40;i++)game.addMessagePlatform(i%2==0,bubble,i%7==0?750:70+i%3*30);
   game.restart();
   for(int i=0;i<40;i++){
    GhostDoodlePhysics.Platform p=game.platforms.get(i);
    if(p.x<0||p.x+p.width>width)throw new AssertionError("Message platform left screen");
    if(i>0&&p.y-game.platforms.get(i-1).y-game.platforms.get(i-1).height<GhostDoodlePhysics.GAP-.01)throw new AssertionError("Full messages overlap");
    if((i%2==0 && p.x<width/2f-.01)||(i%2==1 && p.x+p.width>width/2f+.01))throw new AssertionError("Message left its sender side");
   }
   for(int frame=0;frame<60000&&game.reached<39&&!game.lost;frame++){
    GhostDoodlePhysics.Platform next=game.platforms.get(Math.min(39,game.reached+1));
    game.stepTilt(1f/60,(next.x+next.width/2-game.x)/20);
   }
   if(game.lost||game.reached!=39)throw new AssertionError("Tall message is unreachable: "+game.reached);
  System.out.println("PASS: randomized sender-side message platforms, full-height spacing and adaptive jumps; width="+width);
  }
  GhostDoodlePhysics tilt=new GhostDoodlePhysics(360,720);tilt.addPlatform();
  float initial=tilt.x;tilt.stepTilt(1f/60,0);if(tilt.x!=initial)throw new AssertionError("Neutral tilt drift");
  tilt.stepTilt(1f/60,1);if(tilt.x<=initial)throw new AssertionError("Right tilt");
  tilt.stepTilt(1f/60,-1);if(Math.abs(tilt.x-initial)>.001)throw new AssertionError("Left tilt");
  System.out.println("PASS: tilt direction, neutral position and symmetrical movement");
 }
}'''
with tempfile.TemporaryDirectory(prefix="ghost-doodle-") as directory:
    work=Path(directory)
    (work/"DoodleCheck.java").write_text(code,encoding="utf-8")
    subprocess.run(["javac","-d",str(work),str(work/"DoodleCheck.java"),str(root/"TMessagesProj/src/main/java/org/telegram/messenger/GhostDoodlePhysics.java")],check=True)
    subprocess.run(["java","-cp",str(work),"DoodleCheck"],check=True)

source=(root/"TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java").read_text(encoding="utf-8")
start=source.index("protected boolean sendMessageInternal(")
hook=source.index("GhostDoodleActivity.isCommand",start)
consumed=source.index("return false;",hook)
confirm=source.index("final boolean allowConfirmFinal",start)
assert start < hook < consumed < confirm
assert "setFieldText(\"\")" in source[hook:consumed]
print("PASS: command is consumed and cleared before normal send/confirmation path")
