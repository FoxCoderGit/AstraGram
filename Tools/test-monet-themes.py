"""Host-side check of the actual Monet resolver against all extracted theme values.
Android API calls are small adapters; this does not replace visual device checks.
Also exports fixed Telegram-blue palettes as ordinary, portable .attheme files.
"""
from pathlib import Path
import subprocess, tempfile, zipfile
ROOT = Path(__file__).resolve().parents[1]
BASE = "TMessagesProj/src/main/java/org/telegram/ui/ActionBar"
STUBS = {
"android/graphics/Color.java": "package android.graphics; public class Color { public static final int WHITE=-1, BLACK=0xff000000; }",
"android/os/Build.java": "package android.os; public class Build { public static class VERSION { public static int SDK_INT=24; } }",
"android/content/Context.java": "package android.content; public class Context { public static final int RECEIVER_EXPORTED=2; public android.content.res.Resources getResources(){return new android.content.res.Resources();} public int getColor(int id){return id;} public void registerReceiver(BroadcastReceiver r,IntentFilter f){} public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){} }",
"android/content/res/Resources.java": "package android.content.res; public class Resources { public int getIdentifier(String n,String t,String p){return 0xff123456;} }",
"android/content/BroadcastReceiver.java": "package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c, Intent i); }",
"android/content/Intent.java": "package android.content; public class Intent {}",
"android/content/IntentFilter.java": "package android.content; public class IntentFilter { public IntentFilter(String a){} public void addDataScheme(String s){} public void addDataSchemeSpecificPart(String s,int i){} }",
"org/telegram/messenger/ApplicationLoader.java": "package org.telegram.messenger; public class ApplicationLoader { public static android.content.Context applicationContext=new android.content.Context(); }",
"org/telegram/messenger/AndroidUtilities.java": "package org.telegram.messenger; public class AndroidUtilities { public static void runOnUIThread(Runnable r){r.run();} }",
"org/telegram/messenger/FileLog.java": "package org.telegram.messenger; public class FileLog { public static void e(Exception e){throw new RuntimeException(e);} }",
"org/telegram/ui/ActionBar/Theme.java": "package org.telegram.ui.ActionBar; public class Theme { public static class ThemeInfo{public String assetName;} public static void refreshGhostMonetPreviews(){} public static ThemeInfo getActiveTheme(){return null;} public static boolean isCurrentThemeNight(){return false;} public static void applyTheme(ThemeInfo t,boolean n){} }",
"androidx/core/graphics/ColorUtils.java": "package androidx.core.graphics; public class ColorUtils { public static int setAlphaComponent(int c,int a){return (c & 0xffffff) | (a<<24);} public static int blendARGB(int a,int b,float t){int c=0;for(int shift=0;shift<=24;shift+=8)c|=((int)(((a>>>shift)&255)*(1-t)+((b>>>shift)&255)*t))<<shift;return c;} }",
"MonetCheck.java": r'''import java.nio.file.*; import java.util.*; import org.telegram.ui.ActionBar.GhostMonet;
public class MonetCheck {
 public static void main(String[] args) throws Exception {
  int total=0;
  for(String mode:new String[]{"light","dark","black"}) {
   Path asset=Paths.get(args[0],"TMessagesProj_Ghost/src/main/assets/ghost_monet_"+mode+".attheme");
   List<String> exported=new ArrayList<>(); Map<String,Integer> colors=new HashMap<>();
   for(String line:Files.readAllLines(asset)) {
    int eq=line.indexOf('='); if(eq<0) continue;
    String key=line.substring(0,eq), value=line.substring(eq+1);
    int color=GhostMonet.color(value); colors.put(key,color);
    exported.add(key+"="+color); total++;
   }
   if(colors.size()<700) throw new AssertionError("Truncated theme");
   if(mode.equals("black") && colors.get("chat_wallpaper")!=0xff000000) throw new AssertionError("Black background lost");
   if(colors.get("chat_inBubble").equals(colors.get("chat_messageTextIn"))) throw new AssertionError("Invisible incoming text");
   if(colors.get("chat_outBubble").equals(colors.get("chat_messageTextOut"))) throw new AssertionError("Invisible outgoing text");
   Files.write(Paths.get(args[1],"exteraGram-Monet-"+mode+"-blue.attheme"),exported);
  }
  if((GhostMonet.color("n1_200 (a=80)")>>>24)!=204) throw new AssertionError("Opacity lost");
  android.os.Build.VERSION.SDK_INT=31;
  if(GhostMonet.color("a1_600")!=0xff123456) throw new AssertionError("System palette not used");
  if(GhostMonet.color("mWhite")!=0xffffffff || GhostMonet.color("mBlack")!=0xff000000) throw new AssertionError("Solid palette changed");
  System.out.println("PASS: "+total+" real theme expressions; light/dark/black backgrounds and text; alpha; system palette adapter; portable exports");
 }
}'''
}
output = ROOT / "Tools/exteragram-themes-import/portable"
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="ghost-monet-check-") as directory:
    work = Path(directory)
    for name, text in STUBS.items():
        file = work / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")
    sources = list(work.rglob("*.java")) + list((ROOT / BASE / "monet").rglob("*.java")) + [ROOT / BASE / "GhostMonet.java"]
    subprocess.run(["javac", "-d", str(work / "classes"), *map(str, sources)], check=True)
    subprocess.run(["java", "-cp", str(work / "classes"), "MonetCheck", str(ROOT), str(output)], check=True)
with zipfile.ZipFile(ROOT / "Tools/exteragram-monet-portable.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(output.glob("*.attheme")):
        archive.write(file, file.name)
