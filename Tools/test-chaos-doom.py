"""Compile and exercise the real native-game model, without Telegram or Android."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
code=r'''import java.util.*;
import org.telegram.messenger.GhostDoomWorld;
public class DoomWorldTest {
 static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
 static void reachable(GhostDoomWorld w){boolean[][] seen=new boolean[w.SIZE][w.SIZE];ArrayDeque<Integer> q=new ArrayDeque<>();q.add(2*w.SIZE+2);seen[2][2]=true;int[] dx={1,-1,0,0},dz={0,0,1,-1};
  while(!q.isEmpty()){int c=q.removeFirst(),x=c%w.SIZE,z=c/w.SIZE;for(int d=0;d<4;d++){int nx=x+dx[d],nz=z+dz[d];if(nx<0||nz<0||nx>=w.SIZE||nz>=w.SIZE||seen[nz][nx]||w.walls[nz][nx])continue;seen[nz][nx]=true;q.add(nz*w.SIZE+nx);}}
  for(int z=0;z<w.SIZE;z++)for(int x=0;x<w.SIZE;x++){if(!w.walls[z][x])check(seen[z][x],"Disconnected room");if(x==0||z==0||x==w.SIZE-1||z==w.SIZE-1)check(w.walls[z][x],"Open boundary");}
  check(w.enemies.size()>=24&&w.enemies.size()<=44,"Difficulty: enemy count");
  for(GhostDoomWorld.Enemy e:w.enemies)check(seen[(int)e.z][(int)e.x]&&Math.hypot(e.x-w.x,e.z-w.z)>2,"Enemy spawn");
  check(seen[(int)w.exitZ][(int)w.exitX],"Exit inaccessible");
 }
 public static void main(String[] args){
  for(int seed=0;seed<300;seed++){GhostDoomWorld w=new GhostDoomWorld(seed,seed%97,seed%13);reachable(w);GhostDoomWorld same=new GhostDoomWorld(seed,seed%97,seed%13);check(Arrays.deepEquals(w.walls,same.walls)&&Arrays.deepEquals(w.panels,same.panels),"Non-deterministic history world");}
  GhostDoomWorld w=new GhostDoomWorld(9,50,3);for(GhostDoomWorld.Enemy e:w.enemies)e.alive=false;
  w.enemies.clear();w.yaw=0;for(int i=0;i<500;i++)w.tick(.016f,0,1,0);check(w.z>1.19f&&w.free(w.x,w.z,.19f),"Player passed through wall");
  w=new GhostDoomWorld(7,30,2);GhostDoomWorld.Enemy target=w.enemies.get(0);for(int i=1;i<w.enemies.size();i++)w.enemies.get(i).alive=false;
  w.yaw=0;target.hp=60;target.x=w.x;target.z=w.z-1;target.attack=100;check(w.fire(),"No shot");check(target.hp==30&&target.alerted,"Missed visible target");check(!w.fire(),"Rate limit absent");w.tick(.3f,0,0,0); // dt is intentionally capped
  for(int i=0;i<20;i++)w.tick(.016f,0,0,0);check(w.fire()&&!target.alive&&w.kills==1&&w.score==100,"Kill/score");
  w=new GhostDoomWorld(7,30,2);w.yaw=0;target=w.enemies.get(0);int hp=target.hp;target.x=w.x;target.z=-.5f;check(w.fire()&&target.hp==hp,"Shot passed through solid wall");
  w=new GhostDoomWorld(7,30,2);for(GhostDoomWorld.Enemy e:w.enemies)e.alive=false;target=w.enemies.get(0);target.alive=true;target.x=w.x;target.z=w.z-.5f;target.attack=0;w.health=5;w.tick(.02f,0,0,0);check(w.dead&&w.health==0&&!w.fire(),"Death/game over");
  float oldX=w.x;w.tick(.03f,1,0,1);check(w.x==oldX,"Dead player moved");
  w=new GhostDoomWorld(7,30,2);w.kills=w.enemies.size();w.x=w.exitX;w.z=w.exitZ;w.tick(.02f,0,0,0);check(w.won&&w.score>=1000,"Victory exit");
  w=new GhostDoomWorld(1,0,0);w.enemies.clear();w.health=40;GhostDoomWorld.Pickup pickup=w.pickups.get(0);w.x=pickup.x;w.z=pickup.z;w.tick(.02f,0,0,0);check(pickup.used&&w.health>=70,"Health pickup");
  w=new GhostDoomWorld(1,0,0);pickup=w.pickups.get(0);w.x=pickup.x;w.z=pickup.z;w.health=5;for(GhostDoomWorld.Enemy e:w.enemies)e.alive=false;target=w.enemies.get(0);target.alive=true;target.x=w.x;target.z=w.z-.5f;target.attack=0;w.tick(.02f,0,0,0);check(w.dead&&w.health==0&&!pickup.used,"Pickup revived a dead player");
  Random input=new Random(1);for(int seed=0;seed<20;seed++){w=new GhostDoomWorld(seed,90,12);for(int frame=0;frame<2000;frame++){w.tick(.016f,input.nextFloat()*2-1,input.nextFloat()*2-1,(input.nextFloat()-.5f)*.2f);if(frame%10==0)w.fire();check(Float.isFinite(w.x)&&Float.isFinite(w.z)&&!w.solid(w.x,w.z),"Invalid player state");check(w.health>=0&&w.health<=100,"Health range");}}
  System.out.println("CHAOS world: 300 reproducible connected maps; reachable enemies/exit; wall collision; hitscan occlusion/cooldown; damage, death, health pickups, victory and 40,000 simulation frames passed.");
 }
}'''
with tempfile.TemporaryDirectory(prefix='ghost-doom-') as folder:
    test=Path(folder)/'DoomWorldTest.java';test.write_text(code,encoding='utf-8')
    subprocess.run(['javac','-encoding','UTF-8','-d',folder,str(root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostDoomWorld.java'),str(test)],check=True)
    subprocess.run(['java','-cp',folder,'DoomWorldTest'],check=True)
# Guard the actual command's position before any delivery/plugin/scheduling machinery.
source=(root/'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java').read_text(encoding='utf-8')
section=source[source.index('protected boolean sendMessageInternal('):]
command=section.index('GhostDoomActivity.isCommand')
stop=section.index('return false;',command)
assert command<stop<section.index('ghostLongTextChoice',stop)
activity=(root/'TMessagesProj/src/main/java/org/telegram/ui/GhostDoomActivity.java').read_text(encoding='utf-8')
for forbidden in ('sendMessage(', 'loadMessages(', 'putMessages(', 'deleteMessages(', 'sendRequest('):
    assert forbidden not in activity, forbidden
assert 'boolean isSwipeBackEnabled(MotionEvent event){return false;}' in activity
assert 'boolean canBeginSlide(){return false;}' in activity
assert 'boolean onBackPressed(boolean invoked){return false;}' in activity
print('Command is consumed before sending; game contains no message-delivery/history-writing API calls.')
