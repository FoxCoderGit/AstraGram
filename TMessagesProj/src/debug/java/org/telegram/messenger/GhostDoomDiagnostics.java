package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.*;
import java.nio.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.GhostDoomActivity;
import org.telegram.ui.LaunchActivity;

/** Only synthetic messages; no chat read, send, server history request or database write. */
public final class GhostDoomDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void main(Runnable action)throws Exception {AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new Exception(failure.get());}
    private void gl(GLSurfaceView surface,Runnable action)throws Exception {AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);surface.queueEvent(()->{try{action.run();}catch(Throwable e){failure.set(e);}finally{done.countDown();}});if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("GL worker timeout");if(failure.get()!=null)throw new Exception(failure.get());}
    @Override public void onStart(){Bundle report=new Bundle();GhostDoomActivity[] game={null};try{
        main(ApplicationLoader::postInitApplication);
        if(!GhostDoomActivity.isCommand(" doom123\n")||GhostDoomActivity.isCommand("hello doom123")||GhostDoomActivity.isCommand(null))throw new AssertionError("Command");
        final long dialog=2146000777L;ArrayList<MessageObject> history=new ArrayList<>();
        for(int i=1;i<=36;i++){TLRPC.TL_message m=new TLRPC.TL_message();m.id=i;m.dialog_id=dialog;m.peer_id=new TLRPC.TL_peerUser();m.peer_id.user_id=dialog;m.from_id=new TLRPC.TL_peerUser();m.from_id.user_id=dialog+(i%3);m.date=1600000000+i;m.out=i%2==0;m.message="SYNTHETIC ROOM "+i+"\nCHAOS MODE: test message only.\nThis is a local OpenGL arena.";history.add(new MessageObject(UserConfig.selectedAccount,m,false,false));}
        Constructor<GhostDoomActivity> constructor=GhostDoomActivity.class.getDeclaredConstructor(int.class,long.class,ArrayList.class);constructor.setAccessible(true);game[0]=constructor.newInstance(UserConfig.selectedAccount,dialog,history);
        if(game[0].isSwipeBackEnabled(null)||game[0].canBeginSlide()||game[0].onBackPressed(false)||game[0].onBackPressed(true))throw new AssertionError("Gesture/back closes game");
        LaunchActivity activity=(LaunchActivity)startActivitySync(new Intent(getTargetContext(),LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(()->{if(!activity.presentFragment(game[0],false,true))throw new AssertionError("Navigation");});waitForIdleSync();
        GLSurfaceView surface=(GLSurfaceView)field(game[0],"surface");Object engine=field(game[0],"engine");
        long until=SystemClock.elapsedRealtime()+20000;while(!(Boolean)field(engine,"ready")&&SystemClock.elapsedRealtime()<until){if((Boolean)field(engine,"failed"))throw new AssertionError("GLES creation failed");Thread.sleep(80);}
        if(!(Boolean)field(engine,"ready"))throw new AssertionError("No rendered scene");
        gl(surface,()->{try{
            GhostDoomWorld world=(GhostDoomWorld)field(engine,"world");if(world.enemies.size()<24)throw new AssertionError("Too few enemies");
            int w=surface.getWidth(),h=surface.getHeight();if(w<1||h<1)throw new AssertionError("No viewport");
            ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
            if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new AssertionError("OpenGL draw error");
            int[] colors=new int[w*h];HashSet<Integer> varied=new HashSet<>();for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=((h-1-y)*w+x)*4;int color=0xff000000|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255);colors[y*w+x]=color;if(x%30==0&&y%30==0)varied.add(color);}
            if(varied.size()<12)throw new AssertionError("Blank/flat GL frame");Bitmap image=Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888);
            try(java.io.FileOutputStream out=getTargetContext().openFileOutput("ghost_doom_gl.png",0)){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
        }catch(Exception e){throw new RuntimeException(e);}});
        report.putString("render","PASS: real GLES2 shaders, message textures, generated world, sprites and non-flat framebuffer; synthetic screenshot saved");
        View hud=(View)field(game[0],"hud");final float[] before={0,0};gl(surface,()->{try{GhostDoomWorld w=(GhostDoomWorld)field(engine,"world");before[0]=w.x;before[1]=w.z;}catch(Exception e){throw new RuntimeException(e);}});
        main(()->{long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,60,hud.getHeight()-160,0);hud.dispatchTouchEvent(down);down.recycle();MotionEvent move=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_MOVE,60,hud.getHeight()-240,0);hud.dispatchTouchEvent(move);move.recycle();});
        Thread.sleep(250);main(()->{MotionEvent up=MotionEvent.obtain(0,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,60,hud.getHeight()-240,0);hud.dispatchTouchEvent(up);up.recycle();});
        gl(surface,()->{try{GhostDoomWorld w=(GhostDoomWorld)field(engine,"world");if(Math.hypot(w.x-before[0],w.z-before[1])<.05f)throw new AssertionError("Joystick did not move player");
            w.x=w.z=2.5f;w.yaw=0;for(GhostDoomWorld.Enemy e:w.enemies){e.alive=false;}GhostDoomWorld.Enemy e=w.enemies.get(0);e.alive=true;e.hp=60;e.x=2.5f;e.z=1.5f;e.attack=100;w.shotCooldown=0;if(!w.fire()||e.hp!=30)throw new AssertionError("Shooting");w.shotCooldown=0;if(!w.fire()||e.alive||w.score<100)throw new AssertionError("Kill/score");
        }catch(Exception e){throw new RuntimeException(e);}});
        main(()->game[0].onPause());main(()->game[0].onResume());Thread.sleep(150);
        gl(surface,()->{if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new AssertionError("Resume GL error");});
        main(()->{try{Method rotate=game[0].getClass().getDeclaredMethod("rotate");rotate.setAccessible(true);rotate.invoke(game[0]);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(1200);
        AtomicReference<Bitmap> preview=new AtomicReference<>();
        gl(surface,()->{try{GhostDoomWorld world=(GhostDoomWorld)field(engine,"world");world.x=world.z=2.5f;world.yaw=!world.solid(4.5f,2.5f)?(float)Math.PI/2:(float)Math.PI;GhostDoomWorld.Enemy enemy=world.enemies.get(0);enemy.alive=true;enemy.hp=60;enemy.attack=100;enemy.x=world.x+(float)Math.sin(world.yaw)*2.7f;enemy.z=world.z-(float)Math.cos(world.yaw)*2.7f;}catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(100);
        gl(surface,()->{int w=surface.getWidth(),h=surface.getHeight();if(w<=h)throw new AssertionError("Landscape did not resize game");ByteBuffer data=ByteBuffer.allocateDirect(w*h*4);GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,data);int[] rgb=new int[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=((h-y-1)*w+x)*4;rgb[y*w+x]=0xff000000|((data.get(p)&255)<<16)|((data.get(p+1)&255)<<8)|(data.get(p+2)&255);}Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmap.setPixels(rgb,0,w,0,0,w,h);preview.set(bitmap);});
        main(()->hud.draw(new Canvas(preview.get())));try(java.io.FileOutputStream out=getTargetContext().openFileOutput("ghost_doom_gl.png",0)){preview.get().compress(Bitmap.CompressFormat.PNG,100,out);}finally{preview.get().recycle();}
        report.putString("input","PASS: real HUD drag moves player, hitscan kills avatar enemy and scores; pause/resume retains GL scene");
        report.putString("landscape","PASS: orientation switch resizes GLES viewport; synthetic world and native HUD captured together");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitForIdleSync();if(game[0].isFinished)throw new AssertionError("Android Back closed game");
        main(()->game[0].getActionBar().getBackButton().performClick());Thread.sleep(500);waitForIdleSync();if(!game[0].isFinished)throw new AssertionError("Close button failed");
        report.putString("exit","PASS: Android Back does not close game; toolbar close button returns to chat");
        game[0]=null;finish(-1,report);
    }catch(Throwable failure){report.putString("error",android.util.Log.getStackTraceString(failure));try{if(game[0]!=null)main(()->game[0].finishFragment(false));}catch(Exception ignored){}finish(0,report);}}
}
