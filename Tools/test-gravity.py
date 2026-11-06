"""Exercise the production Java rigid-body solver without Android or Telegram."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
physics = root / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostGravityPhysics.java'
check = r'''import org.telegram.messenger.GhostGravityPhysics;
import org.telegram.messenger.GhostGravityPhysics.Body;
import java.util.Random;
public class GravityCheck {
 static void yes(boolean value,String text){if(!value)throw new AssertionError(text);}
 static void frames(GhostGravityPhysics world,int n){for(int i=0;i<n;i++)world.step(1f/60);}
 static void finite(GhostGravityPhysics world){for(Body b:world.bodies){yes(Float.isFinite(b.x)&&Float.isFinite(b.y)&&Float.isFinite(b.angle)&&Float.isFinite(b.vx)&&Float.isFinite(b.vy),"Non-finite body");}}
 public static void main(String[] args){
  GhostGravityPhysics falling=new GhostGravityPhysics(360,640,new Random(1));Body box=falling.add(180,80,100,40);
  falling.step(.05f);yes(box.y>80&&box.vy>0,"Gravity did not accelerate");frames(falling,600);
  yes(Math.abs(box.y-620)<1&&Math.abs(box.vy)<4,"Floor settling y="+box.y+" vy="+box.vy);
  Body large=falling.add(180,200,200,180);yes(large.mass>box.mass&&large.inverseInertia<box.inverseInertia,"Area-based mass/inertia");
  GhostGravityPhysics stack=new GhostGravityPhysics(360,640);for(int i=0;i<5;i++)stack.add(180,60+i*90,160,40);frames(stack,900);
  for(Body a:stack.bodies)for(Body b:stack.bodies)if(a!=b)yes(Math.abs(a.y-b.y)>39.1,"Stack overlap");finite(stack);
  GhostGravityPhysics impact=new GhostGravityPhysics(900,900);Body a=impact.add(250,150,70,60),b=impact.add(390,165,90,70);a.vx=900;
  frames(impact,12);yes(Math.abs(b.vx)>5,"Momentum not transferred");yes(Math.abs(a.omega)+Math.abs(b.omega)>.01,"Off-center impact did not rotate");
  GhostGravityPhysics held=new GhostGravityPhysics(360,640);Body drag=held.add(140,120,100,50);held.grab(drag,150,130);held.move(240,250,.016f);frames(held,20);
  yes(Math.abs(drag.x-230)<.1&&Math.abs(drag.y-240)<.1,"Grab offset changed");held.release(1000,-700);yes(drag.vx==1000&&drag.vy==-700&&Math.abs(drag.omega)>0,"Flick velocity/inertia");
  GhostGravityPhysics wall=new GhostGravityPhysics(360,640);Body thrown=wall.add(40,200,40,40);thrown.vx=-1000;frames(wall,3);yes(thrown.x>=20&&thrown.vx>0,"Wall collision");
  GhostGravityPhysics open=new GhostGravityPhysics(360,640);Body up=open.add(180,30,40,30);up.vy=-2400;frames(open,20);yes(!up.alive&&!open.bodies.contains(up),"Open top did not remove escaped object");
  GhostGravityPhysics broken=new GhostGravityPhysics(360,640,new Random(4));Body original=broken.add(180,180,150,100);Object texture=new Object();original.asset=texture;
  java.util.ArrayList<Body> pieces=broken.shatter(original);yes(pieces.size()>=4&&pieces.size()<=8&&!original.alive,"Fragment count");float area=0;
  for(Body p:pieces){area+=p.width*p.height;yes(p.asset==texture&&p.fragment&&p.life>=1.4&&p.life<=1.9&&p.u0>=0&&p.u1<=1&&p.v0>=0&&p.v1<=1,"Fragment texture/lifetime");}
  yes(Math.abs(area-15000)<1,"Fragments do not cover original");frames(broken,150);yes(broken.bodies.isEmpty(),"Fragments did not expire");
  GhostGravityPhysics stress=new GhostGravityPhysics(360,700,new Random(99));Random rng=new Random(44);
  for(int i=0;i<24;i++){Body p=stress.add(40+rng.nextFloat()*280,40+rng.nextFloat()*560,20+rng.nextFloat()*100,20+rng.nextFloat()*90);p.vx=(rng.nextFloat()-.5f)*3000;p.vy=(rng.nextFloat()-.5f)*3000;p.omega=(rng.nextFloat()-.5f)*10;}
  for(int i=0;i<600;i++){stress.step(i%50==0?.8f:1f/60);finite(stress);for(Body p:stress.bodies)yes(p.x>-1&&p.x<361&&p.y+p.radiusY<=701,"Escaped floor/wall");}
  System.out.println("PASS: gravity and settling; five-box stack; mass/inertia; momentum and rotation; grab/throw; wall bounce; open top; 4-8 texture fragments and expiry; 24-body long-frame stress");
 }
}'''
# Radius is intentionally internal: the stress test derives rotated bounds independently.
check = check.replace('p.y+p.radiusY', 'p.y+(float)(Math.abs(Math.sin(p.angle))*p.width/2+Math.abs(Math.cos(p.angle))*p.height/2)')
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    (temporary / 'GravityCheck.java').write_text(check, encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(temporary), str(physics), str(temporary / 'GravityCheck.java')], check=True)
    subprocess.run(['java', '-cp', str(temporary), 'GravityCheck'], check=True)
composer = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java').read_text(encoding='utf-8')
entry = composer[composer.index('protected boolean sendMessageInternal'):]
assert entry.index('GhostGravityActivity.isCommand') < entry.index('allowConfirmFinal')
activity = (root / 'TMessagesProj/src/main/java/org/telegram/ui/GhostGravityActivity.java').read_text(encoding='utf-8')
assert all(api not in activity for api in ['sendRequest(', 'sendMessage(', 'deleteMessages(', 'loadMessages(', 'NotificationCenter'])
assert 'getChatListView()' in activity and 'getBackgroundDrawableLeft()' in activity
print('PASS: command consumed before sending; visible native cells only; game has no message, history, notification or transport APIs')
