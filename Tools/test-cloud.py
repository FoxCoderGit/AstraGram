"""Exercise production schedule and retry policy on the host JVM."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / "TMessagesProj/src/main/java/org/telegram/messenger/GhostCloudPolicy.java"
test = r'''
import org.telegram.messenger.GhostCloudPolicy;
public class CloudPolicyCheck {
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
 public static void main(String[] args){
  for(int day=0;day<7;day++)for(int minute=0;minute<1440;minute++){
   check(GhostCloudPolicy.inWindow(day,minute,false,0,0,0),"disabled schedule");
   check(GhostCloudPolicy.inWindow(day,minute,true,127,0,0),"all-day daily");
   boolean daytime=day==0&&minute>=9*60&&minute<18*60;
   check(GhostCloudPolicy.inWindow(day,minute,true,1,9*60,18*60)==daytime,"Monday daytime boundary");
   boolean overnight=day==6&&minute>=23*60||day==0&&minute<5*60;
   check(GhostCloudPolicy.inWindow(day,minute,true,64,23*60,5*60)==overnight,"Sunday to Monday boundary");
   boolean daily=minute>=22*60||minute<7*60;
   check(GhostCloudPolicy.inWindow(day,minute,true,127,22*60,7*60)==daily,"overnight daily");
  }
  check(!GhostCloudPolicy.inWindow(0,0,true,0,0,0),"no days");
  check(!GhostCloudPolicy.inWindow(7,0,true,127,0,0),"invalid day");
  check(!GhostCloudPolicy.inWindow(0,1440,true,127,0,0),"invalid minute");
  check(!GhostCloudPolicy.inWindow(0,10,true,127,-1,120),"invalid start");
  check(GhostCloudPolicy.retryDelay(0,"FLOOD_WAIT_120")==121000,"server flood");
  check(GhostCloudPolicy.retryDelay(100,"SLOWMODE_WAIT_17")==18000,"server slow mode overrides attempt count");
  check(GhostCloudPolicy.retryDelay(0,"FLOOD_PREMIUM_WAIT_20000")==20001000,"long server wait is not capped");
  check(GhostCloudPolicy.retryDelay(0,"FLOOD_WAIT_bad")>=86400000,"malformed flood is conservative");
  check(GhostCloudPolicy.retryDelay(0,null)==15000,"initial backoff");
  check(GhostCloudPolicy.retryDelay(1,"TIMEOUT")==30000,"exponential backoff");
  check(GhostCloudPolicy.retryDelay(Integer.MAX_VALUE,"TIMEOUT")>0,"retry overflow");
  System.out.println("PASS: 50,400 schedule cases, midnight/week wrap, exact end boundary, invalid inputs and Telegram backoff");
 }
}'''
with tempfile.TemporaryDirectory(prefix="ghost-cloud-policy-") as directory:
    harness = Path(directory) / "CloudPolicyCheck.java"
    harness.write_text(test, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", directory, str(source), str(harness)], check=True)
    subprocess.run(["java", "-cp", directory, "CloudPolicyCheck"], check=True)
