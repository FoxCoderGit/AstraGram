package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.*;
import android.widget.FrameLayout;
import java.io.*;
import java.nio.*;
import java.util.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;

/** An original, local DOOM-style GLES2 game. It does not embed or redistribute DOOM assets. */
public final class GhostDoomActivity extends BaseFragment {
    public static boolean isCommand(CharSequence text){return text!=null&&"doom123".equalsIgnoreCase(text.toString().trim());}
    @Override public boolean isSwipeBackEnabled(MotionEvent event){return false;}
    @Override public boolean canBeginSlide(){return false;}
    @Override public boolean onBackPressed(boolean invoked){return false;}
    public static void open(ChatActivity chat){
        if(chat.getParentActivity()==null)return;
        chat.presentFragment(new GhostDoomActivity(chat.getCurrentAccount(),chat.getDialogId(),new ArrayList<>(chat.messages)));
    }
    private static final class Panel {String name,text;long sender;boolean outgoing;int date;}
    private static final class Face {String name,path;}
    private final ArrayList<Panel> panels=new ArrayList<>();
    private final ArrayList<Face> faces=new ArrayList<>();
    private long seed;private GLSurfaceView surface;private Engine engine;private Hud hud;
    private final Input input=new Input();private final Sounds sounds=new Sounds();
    private volatile boolean running;private int originalOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private Activity orientationOwner;
    private GhostDoomActivity(int account,long dialog,ArrayList<MessageObject> history){
        currentAccount=account;seed=dialog^0x4348414f534cL;
        history.sort(Comparator.<MessageObject>comparingInt(m->m.messageOwner==null?0:m.messageOwner.date).thenComparingInt(MessageObject::getId));
        HashSet<Long> ids=new HashSet<>();
        for(int i=Math.max(0,history.size()-96);i<history.size();i++){
            MessageObject m=history.get(i);if(m.messageOwner==null||m.getDialogId()!=dialog||m.isSponsored()||m.isEphemeral()||m.isVoiceOnce())continue;
            Panel p=new Panel();p.sender=m.getFromChatId();p.outgoing=m.isOutOwner();p.date=m.messageOwner.date;
            p.text=m.messageText==null?"":m.messageText.toString();if(p.text.isEmpty())p.text="Медиа · "+(m.getDocument()!=null?FileLoader.getDocumentFileName(m.getDocument()):"Сообщение");
            TLRPC.User u=p.sender>0?getMessagesController().getUser(p.sender):null;TLRPC.Chat c=p.sender<0?getMessagesController().getChat(-p.sender):null;
            p.name=u!=null?UserObject.getUserName(u):c!=null?c.title:"Участник";panels.add(p);
            seed=seed*31+m.getId();seed=seed*31+p.text.hashCode();
            if(ids.add(p.sender)&&faces.size()<12){Face f=new Face();f.name=p.name;
                org.telegram.tgnet.TLRPC.FileLocation photo=u!=null&&u.photo!=null?u.photo.photo_small:c!=null&&c.photo!=null?c.photo.photo_small:null;
                if(photo!=null)f.path=FileLoader.getInstance(account).getPathToAttach(photo,true).getAbsolutePath();faces.add(f);}
        }
        if(panels.isEmpty()){Panel p=new Panel();p.name="AstraGram";p.text="CHAOS MODE\nЛокальная арена\nПереписка остаётся неизменной";panels.add(p);}
        if(faces.isEmpty()){Face f=new Face();f.name="Ghost";faces.add(f);}
    }
    @Override public View createView(Context context){
        actionBar.setTitle("CHAOS MODE · DOOM");actionBar.setBackButtonImage(R.drawable.msg_close);actionBar.getBackButton().setContentDescription("Закрыть игру");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();else if(id==1)rotate();else if(id==2&&surface!=null)surface.queueEvent(()->engine.restart());}});
        actionBar.createMenu().addItem(1,R.drawable.msg_photo_rotate).setContentDescription("Повернуть экран");
        actionBar.createMenu().addItem(2,R.drawable.msg_retry).setContentDescription("Заново");
        FrameLayout root=new FrameLayout(context);root.setBackgroundColor(Color.BLACK);
        surface=new GLSurfaceView(context);surface.setEGLContextClientVersion(2);surface.setEGLConfigChooser(8,8,8,0,16,0);surface.setPreserveEGLContextOnPause(true);
        engine=new Engine();surface.setRenderer(engine);root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        hud=new Hud(context);root.addView(hud,new FrameLayout.LayoutParams(-1,-1));sounds.init(context);
        orientationOwner=getParentActivity();if(orientationOwner!=null)originalOrientation=orientationOwner.getRequestedOrientation();
        return fragmentView=root;
    }
    private void rotate(){Activity activity=getParentActivity();if(activity==null)return;boolean landscape=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;try{activity.setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);}catch(RuntimeException ignored){}}
    @Override public void onResume(){super.onResume();running=true;input.clear();if(surface!=null){surface.setKeepScreenOn(true);surface.onResume();}if(hud!=null)hud.postInvalidateOnAnimation();}
    @Override public void onPause(){running=false;input.clear();if(hud!=null)hud.clear();if(surface!=null){surface.setKeepScreenOn(false);surface.onPause();}sounds.pause();super.onPause();}
    @Override public void onFragmentDestroy(){running=false;input.clear();sounds.close();if(orientationOwner!=null)try{orientationOwner.setRequestedOrientation(originalOrientation);}catch(RuntimeException ignored){}orientationOwner=null;super.onFragmentDestroy();}
    private static final class Input {
        volatile float strafe,forward;volatile boolean shooting;private float turn;volatile boolean restart;
        synchronized void turn(float amount){turn+=amount;}synchronized float takeTurn(){float value=turn;turn=0;return value;}
        synchronized void clear(){strafe=forward=turn=0;shooting=false;restart=false;}
    }
    private final class Engine implements GLSurfaceView.Renderer {
        GhostDoomWorld world;int program,pos,uv,matrix,eye,tint,texUniform;int[] textures;
        final float[] projection=new float[16],view=new float[16],mvp=new float[16];
        final ArrayList<Batch> batches=new ArrayList<>();final FloatBuffer sprite=buffer(30);
        int panelCount,enemyStart,boltTexture,medTexture,exitTexture;long last;
        volatile int health=100,score,kills,total,weapon;volatile boolean dead,won,ready,failed;volatile float muzzle,damage,time;
        void restart(){world=new GhostDoomWorld(seed,panels.size(),faces.size());last=0;input.clear();sync();}
        @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
            try{
                program=GLES20.glCreateProgram();int vs=shader(GLES20.GL_VERTEX_SHADER,"uniform mat4 uMatrix;uniform vec3 uEye;attribute vec3 aPos;attribute vec2 aUv;varying vec2 vUv;varying float vDist;void main(){gl_Position=uMatrix*vec4(aPos,1.0);vUv=aUv;vDist=length(aPos.xz-uEye.xz);}");
                int fs=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float;uniform sampler2D uTexture;uniform vec4 uTint;varying vec2 vUv;varying float vDist;void main(){vec4 c=texture2D(uTexture,vUv)*uTint;if(c.a<0.08)discard;float f=clamp(vDist/22.0,0.0,0.90);gl_FragColor=vec4(mix(c.rgb,vec3(0.025,0.03,0.04),f),c.a);}");
                GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);if(ok[0]==0)throw new IllegalStateException("GL link");
                pos=GLES20.glGetAttribLocation(program,"aPos");uv=GLES20.glGetAttribLocation(program,"aUv");matrix=GLES20.glGetUniformLocation(program,"uMatrix");eye=GLES20.glGetUniformLocation(program,"uEye");tint=GLES20.glGetUniformLocation(program,"uTint");texUniform=GLES20.glGetUniformLocation(program,"uTexture");
                GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
                panelCount=Math.min(32,panels.size());enemyStart=panelCount+1;boltTexture=enemyStart+faces.size()*2;medTexture=boltTexture+1;exitTexture=medTexture+1;
                textures=new int[exitTexture+1];GLES20.glGenTextures(textures.length,textures,0);
                upload(0,tile());for(int i=0;i<panelCount;i++)upload(i+1,panel(panels.get(i*panels.size()/panelCount)));
                for(int i=0;i<faces.size();i++)for(int frame=0;frame<2;frame++)upload(enemyStart+i*2+frame,monster(faces.get(i),frame));
                upload(boltTexture,symbol(0));upload(medTexture,symbol(1));upload(exitTexture,symbol(2));
                if(world==null)restart();buildLevel();last=0;ready=true;failed=false;
            }catch(RuntimeException|OutOfMemoryError error){failed=true;ready=false;FileLog.e(error);}
        }
        @Override public void onSurfaceChanged(GL10 gl,int width,int height){GLES20.glViewport(0,0,width,height);Matrix.perspectiveM(projection,0,68,(float)width/Math.max(1,height),.06f,45);}
        @Override public void onDrawFrame(GL10 gl){
            GLES20.glClearColor(.025f,.03f,.04f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);if(!ready)return;
            long now=SystemClock.elapsedRealtimeNanos();float dt=last==0?0:Math.min(.04f,(now-last)/1e9f);last=now;
            if(!running){input.clear();return;}if(input.restart){input.restart=false;restart();}
            int previousHealth=world.health;world.tick(dt,input.strafe,input.forward,input.takeTurn());if(input.shooting&&world.fire())sounds.play(world.weapon==0?0:1);
            if(world.health<previousHealth)sounds.play(2);sync();
            float cameraY=.68f+(float)Math.sin(world.time*10)*.018f*Math.min(1,Math.abs(input.forward)+Math.abs(input.strafe));
            Matrix.setLookAtM(view,0,world.x,cameraY,world.z,world.x+(float)Math.sin(world.yaw),cameraY,world.z-(float)Math.cos(world.yaw),0,1,0);Matrix.multiplyMM(mvp,0,projection,0,view,0);
            GLES20.glUseProgram(program);GLES20.glUniformMatrix4fv(matrix,1,false,mvp,0);GLES20.glUniform3f(eye,world.x,cameraY,world.z);GLES20.glUniform1i(texUniform,0);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glUniform4f(tint,1,1,1,1);
            for(Batch batch:batches)draw(batch.data,batch.count,batch.texture);
            // Cut-out sprites write depth, so avatar enemies occlude each other and never show through walls.
            for(GhostDoomWorld.Enemy e:world.enemies){if(!e.alive&&e.death>1.0f)continue;float scale=e.alive?1:Math.max(.02f,1-e.death);if(e.hurt>0)GLES20.glUniform4f(tint,1,.45f,.4f,1);else GLES20.glUniform4f(tint,1,1,1,1);billboard(e.x,e.z,.68f,.98f*scale,enemyStart+e.avatar*2+((int)(world.time*5+e.avatar)&1),.01f);}
            GLES20.glUniform4f(tint,1,1,1,1);
            for(GhostDoomWorld.Bolt b:world.bolts)billboard(b.x,b.z,.19f,.19f,boltTexture,.56f);
            for(GhostDoomWorld.Pickup p:world.pickups)if(!p.used)billboard(p.x,p.z,.28f,.28f,medTexture,.08f);
            if(world.kills==world.enemies.size())billboard(world.exitX,world.exitZ,.9f,1.25f,exitTexture,.01f);
        }
        void sync(){health=world.health;score=world.score;kills=world.kills;total=world.enemies.size();weapon=world.weapon;dead=world.dead;won=world.won;muzzle=world.muzzle;damage=world.damageFlash;time=world.time;}
        int shader(int type,String text){int id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,text);GLES20.glCompileShader(id);int[] result=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,result,0);if(result[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(id));return id;}
        void upload(int index,Bitmap bitmap){GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[index]);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);bitmap.recycle();}
        void draw(FloatBuffer data,int count,int texture){GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[texture]);data.position(0);GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,20,data);GLES20.glEnableVertexAttribArray(pos);data.position(3);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,20,data);GLES20.glEnableVertexAttribArray(uv);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,count);}
        void billboard(float x,float z,float width,float height,int texture,float bottom){float rx=(float)Math.cos(world.yaw)*width/2,rz=(float)Math.sin(world.yaw)*width/2;sprite.clear();quad(sprite,x-rx,bottom,z-rz,x+rx,bottom,z+rz,x+rx,bottom+height,z+rz,x-rx,bottom+height,z-rz);sprite.flip();draw(sprite,6,texture);}
        void buildLevel(){
            batches.clear();ArrayList<ArrayList<float[]>> groups=new ArrayList<>();for(int i=0;i<=panelCount;i++)groups.add(new ArrayList<>());
            groups.get(0).add(new float[]{0,0,0,GhostDoomWorld.SIZE,0,0,GhostDoomWorld.SIZE,0,GhostDoomWorld.SIZE,0,0,GhostDoomWorld.SIZE});
            groups.get(0).add(new float[]{0,1.65f,0,0,1.65f,GhostDoomWorld.SIZE,GhostDoomWorld.SIZE,1.65f,GhostDoomWorld.SIZE,GhostDoomWorld.SIZE,1.65f,0});
            for(int z=0;z<GhostDoomWorld.SIZE;z++)for(int x=0;x<GhostDoomWorld.SIZE;x++)if(world.walls[z][x]){
                ArrayList<float[]> group=groups.get(1+world.panels[z][x]%panelCount);float h=1.65f;
                if(!world.solid(x-.5f,z+.5f))group.add(new float[]{x,0,z+1,x,0,z,x,h,z,x,h,z+1});
                if(!world.solid(x+1.5f,z+.5f))group.add(new float[]{x+1,0,z,x+1,0,z+1,x+1,h,z+1,x+1,h,z});
                if(!world.solid(x+.5f,z-.5f))group.add(new float[]{x,0,z,x+1,0,z,x+1,h,z,x,h,z});
                if(!world.solid(x+.5f,z+1.5f))group.add(new float[]{x+1,0,z+1,x,0,z+1,x,h,z+1,x+1,h,z+1});
            }
            for(int i=0;i<groups.size();i++){ArrayList<float[]> faces=groups.get(i);FloatBuffer data=buffer(faces.size()*30);for(float[] f:faces){if(i==0)quad(data,f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],f[9],f[10],f[11]);else quad(data,f[3],f[4],f[5],f[0],f[1],f[2],f[9],f[10],f[11],f[6],f[7],f[8]);}data.flip();batches.add(new Batch(data,faces.size()*6,i));}
        }
    }
    private static final class Batch {final FloatBuffer data;final int count,texture;Batch(FloatBuffer d,int c,int t){data=d;count=c;texture=t;}}
    private static FloatBuffer buffer(int floats){return ByteBuffer.allocateDirect(floats*4).order(ByteOrder.nativeOrder()).asFloatBuffer();}
    private static void vertex(FloatBuffer b,float x,float y,float z,float u,float v){b.put(x).put(y).put(z).put(u).put(v);}
    private static void quad(FloatBuffer b,float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz){vertex(b,ax,ay,az,0,1);vertex(b,bx,by,bz,1,1);vertex(b,cx,cy,cz,1,0);vertex(b,ax,ay,az,0,1);vertex(b,cx,cy,cz,1,0);vertex(b,dx,dy,dz,0,0);}
    private Bitmap panel(Panel panel){
        Bitmap b=Bitmap.createBitmap(256,416,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);c.drawColor(0xff27333b);
        p.setColor(0xff12191f);for(int y=0;y<416;y+=64){c.drawRect(0,y,256,y+3,p);for(int x=(y/64%2)*64;x<256;x+=128)c.drawRect(x,y,x+3,y+64,p);}
        p.setColor(panel.outgoing?0xff315a49:0xff3a4857);c.drawRoundRect(13,13,243,403,13,13,p);
        TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);text.setTypeface(Typeface.DEFAULT_BOLD);text.setTextSize(17);text.setColor(0xff75dec7);c.drawText(android.text.TextUtils.ellipsize(panel.name,text,202,android.text.TextUtils.TruncateAt.END).toString(),27,41,text);
        text.setTypeface(Typeface.DEFAULT);text.setTextSize(17);text.setColor(0xfff0f3f6);String value=panel.text.length()>640?panel.text.substring(0,640):panel.text;
        StaticLayout layout=StaticLayout.Builder.obtain(value,0,value.length(),text,200).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(16).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build();c.save();c.translate(28,56);layout.draw(c);c.restore();
        p.setColor(0xff607b88);c.drawCircle(8,8,3,p);c.drawCircle(248,408,3,p);return b;
    }
    private static Bitmap tile(){Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xff30343a);Paint p=new Paint();p.setColor(0xff24282d);for(int i=0;i<128;i+=16){c.drawLine(i,0,i,128,p);c.drawLine(0,i,128,i,p);}return b;}
    private static Bitmap monster(Face face,int frame){
        Bitmap b=Bitmap.createBitmap(128,192,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int offset=frame==0?-3:3;
        p.setColor(0xff162014);c.drawOval(13,172,115,190,p);p.setColor(0xff8f392b);c.drawRoundRect(30,68,98,150,15,15,p);p.setColor(0xff591d1d);c.drawRect(30,142,54,180+offset,p);c.drawRect(74,142,98,180-offset,p);
        p.setColor(0xffbe5a3d);c.drawRoundRect(9,78+offset,30,145+offset,9,9,p);c.drawRoundRect(98,78-offset,119,145-offset,9,9,p);
        Path horns=new Path();horns.moveTo(27,36);horns.lineTo(19,3);horns.lineTo(50,27);horns.moveTo(101,36);horns.lineTo(109,3);horns.lineTo(78,27);p.setColor(0xffdcba82);c.drawPath(horns,p);
        p.setColor(0xffc07345);c.drawOval(24,14,104,91,p);
        Bitmap avatar=null;try{if(face.path!=null){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(face.path,o);o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>128)o.inSampleSize*=2;o.inJustDecodeBounds=false;avatar=BitmapFactory.decodeFile(face.path,o);}}catch(RuntimeException ignored){}
        if(avatar!=null){c.save();Path clip=new Path();clip.addCircle(64,51,30,Path.Direction.CW);c.clipPath(clip);c.drawBitmap(avatar,null,new Rect(34,21,94,81),p);c.restore();avatar.recycle();}
        else{p.setColor(0xffe8dfc9);p.setTextSize(36);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.CENTER);c.drawText(face.name.isEmpty()?"?":face.name.substring(0,face.name.offsetByCodePoints(0,1)),64,66,p);}
        p.setColor(0xffffcf45);c.drawRect(41,43,56,48,p);c.drawRect(72,43,87,48,p);p.setColor(0xfff7e8d6);c.drawRect(48,72,80,77,p);return b;
    }
    private static Bitmap symbol(int type){Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        if(type==0){p.setColor(0x55ff3300);c.drawCircle(64,64,57,p);p.setColor(0xffff5e16);c.drawCircle(64,64,35,p);p.setColor(0xffffff90);c.drawCircle(64,64,17,p);}
        else if(type==1){p.setColor(0xffeee8d7);c.drawRoundRect(12,24,116,112,10,10,p);p.setColor(0xffc63436);c.drawRect(50,35,78,101,p);c.drawRect(30,54,98,82,p);}
        else{p.setColor(0xff36db83);c.drawRoundRect(8,5,120,123,20,20,p);p.setColor(0xff102e23);c.drawRoundRect(18,14,110,115,14,14,p);p.setColor(0xff9bffbc);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(28);c.drawText("EXIT",64,74,p);}return b;}
    private final class Hud extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF fire=new RectF(),weapon=new RectF();
        int moveId=-1,lookId=-1,fireId=-1;float moveX,moveY,lookX;final float d;
        Hud(Context context){super(context);d=getResources().getDisplayMetrics().density;setContentDescription("CHAOS MODE: слева движение, справа обзор, кнопка огня справа внизу");setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),radius=Math.min(64*d,w*.14f),cx=radius+24*d,cy=h-radius-24*d;
            fire.set(w-104*d,h-116*d,w-18*d,h-30*d);weapon.set(w-190*d,h-80*d,w-116*d,h-24*d);
            if(engine.failed){label(c,"OpenGL недоступен",w/2,h/2,22,Color.WHITE);label(c,"Вернитесь в чат кнопкой назад",w/2,h/2+34*d,14,0xffb6c7d0);return;}
            if(!engine.ready){label(c,"Создаём мир переписки…",w/2,h/2,18,Color.WHITE);if(running)postInvalidateOnAnimation();return;}
            // Original weapon sprite, drawn over the accelerated 3D world.
            float bob=(float)Math.sin(engine.time*8)*3*d*Math.min(1,Math.abs(input.forward)+Math.abs(input.strafe)),gunX=w*.5f,gunY=h+20*d+bob;
            paint.setColor(0xff232a30);c.drawRoundRect(gunX-30*d,gunY-135*d,gunX+30*d,gunY+2*d,8*d,8*d,paint);paint.setColor(0xff76848c);c.drawRect(gunX-15*d,gunY-142*d,gunX+15*d,gunY-52*d,paint);paint.setColor(0xff30373d);c.drawRect(gunX-10*d,gunY-150*d,gunX+10*d,gunY-135*d,paint);
            if(engine.muzzle>0){paint.setColor(0xffffcf52);Path flash=new Path();flash.moveTo(gunX,gunY-200*d);flash.lineTo(gunX+23*d,gunY-153*d);flash.lineTo(gunX+5*d,gunY-164*d);flash.lineTo(gunX-24*d,gunY-154*d);flash.close();c.drawPath(flash,paint);}
            if(engine.damage>0)c.drawColor(((int)(Math.min(.32f,engine.damage)*255)<<24)|0x00cc2020);
            paint.setColor(0xaa0d1820);c.drawRoundRect(10*d,10*d,w-10*d,58*d,10*d,10*d,paint);
            label(c,"♥ "+engine.health,w*.16f,41*d,19,engine.health<30?0xffff6868:0xffa8f3ce);label(c,"ОЧКИ "+engine.score,w*.50f,41*d,16,Color.WHITE);label(c,engine.kills+" / "+engine.total,w*.83f,41*d,17,0xfff3d98b);
            paint.setColor(0x66ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);c.drawCircle(cx,cy,radius,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(0x779adbd7);c.drawCircle(cx+input.strafe*radius*.65f,cy-input.forward*radius*.65f,radius*.34f,paint);
            paint.setColor(input.shooting?0xaaff7d5c:0x88762c2c);c.drawOval(fire,paint);label(c,"ОГОНЬ",fire.centerX(),fire.centerY()+5*d,14,Color.WHITE);
            paint.setColor(0xaa17232c);c.drawRoundRect(weapon,10*d,10*d,paint);label(c,engine.weapon==0?"ПИСТОЛЕТ":"ДРОБОВИК",weapon.centerX(),weapon.centerY()+4*d,10,0xffc3d9e1);
            paint.setColor(0xffe0e8dd);paint.setStrokeWidth(1.5f*d);c.drawLine(w/2-8*d,h/2,w/2-3*d,h/2,paint);c.drawLine(w/2+3*d,h/2,w/2+8*d,h/2,paint);c.drawLine(w/2,h/2-8*d,w/2,h/2-3*d,paint);c.drawLine(w/2,h/2+3*d,w/2,h/2+8*d,paint);
            if(engine.kills==engine.total&&!engine.won)label(c,"Выход открыт — найдите зелёный портал",w/2,81*d,13,0xffa8f3ce);
            if(engine.time<7)label(c,"Слева — идти · справа — обзор · уничтожьте монстров",w/2,h*.67f,12,0xffe2e8ec);
            if(engine.dead||engine.won){paint.setColor(0xcc0b1117);c.drawRect(0,h*.30f,w,h*.64f,paint);label(c,engine.won?"ПЕРЕПИСКА ЗАЧИЩЕНА":"ВЫ ПОГИБЛИ",w/2,h*.43f,23,engine.won?0xff97e7b3:0xffff8177);label(c,"Очки: "+engine.score+" · нажмите для новой игры",w/2,h*.53f,14,Color.WHITE);}
            if(running)postInvalidateOnAnimation();
        }
        void label(Canvas c,String value,float x,float y,int size,int color){paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(size*d);paint.setColor(color);c.drawText(value,x,y,paint);}
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked(),index=event.getActionIndex();float x=event.getX(index),y=event.getY(index);int id=event.getPointerId(index);
            if(action==MotionEvent.ACTION_CANCEL){clear();return true;}
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
                if(engine.dead||engine.won){input.restart=true;clear();input.restart=true;return true;}
                if(fire.contains(x,y)){fireId=id;input.shooting=true;}
                else if(weapon.contains(x,y)){surface.queueEvent(()->{if(engine.world!=null)engine.world.weapon=1-engine.world.weapon;});}
                else if(x<getWidth()*.42f&&moveId==-1){moveId=id;moveX=x;moveY=y;}
                else if(lookId==-1){lookId=id;lookX=x;}
            }else if(action==MotionEvent.ACTION_MOVE){
                for(int i=0;i<event.getPointerCount();i++){int pointer=event.getPointerId(i);if(pointer==moveId){float r=Math.min(64*d,getWidth()*.14f);input.strafe=Math.max(-1,Math.min(1,(event.getX(i)-moveX)/r));input.forward=Math.max(-1,Math.min(1,(moveY-event.getY(i))/r));}else if(pointer==lookId){float now=event.getX(i);input.turn((now-lookX)/Math.max(1,getWidth())*3.6f);lookX=now;}}
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){if(id==moveId){moveId=-1;input.strafe=input.forward=0;}if(id==lookId)lookId=-1;if(id==fireId){fireId=-1;input.shooting=false;}}
            return true;
        }
        void clear(){moveId=lookId=fireId=-1;input.clear();}
    }
    /** Small synthesized, original PCM effects, asynchronously loaded by Android SoundPool. */
    private static final class Sounds {
        SoundPool pool;final int[] ids=new int[3];boolean closed;final HashSet<Integer> loaded=new HashSet<>();final HashMap<Integer,File> files=new HashMap<>();
        void init(Context context){Utilities.globalQueue.postRunnable(()->{synchronized(this){if(closed)return;try{
            pool=new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((p,id,status)->{synchronized(Sounds.this){File file=files.remove(id);if(file!=null)file.delete();if(!closed&&status==0)loaded.add(id);}});
            for(int type=0;type<3;type++){File file=File.createTempFile("ghost-doom-",".wav",context.getCacheDir());writeSound(file,type);ids[type]=pool.load(file.getAbsolutePath(),1);if(ids[type]==0)file.delete();else files.put(ids[type],file);}
        }catch(Exception failure){if(pool!=null)pool.release();pool=null;}}});}
        synchronized void play(int which){if(!closed&&pool!=null&&loaded.contains(ids[which]))pool.play(ids[which],.42f,.42f,1,0,1);}
        synchronized void pause(){if(pool!=null)pool.autoPause();}
        synchronized void close(){closed=true;if(pool!=null)pool.release();pool=null;loaded.clear();for(File file:files.values())file.delete();files.clear();}
        static void writeSound(File file,int type)throws IOException{
            int rate=22050,count=type==1?6500:3500;ByteBuffer wav=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN);wav.put(new byte[]{'R','I','F','F'}).putInt(36+count*2).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(count*2);
            Random random=new Random(123+type);for(int i=0;i<count;i++){double t=(double)i/rate,envelope=Math.exp(-t*(type==1?17:28));double noise=(random.nextDouble()*2-1)*(type==2?.2:.7),tone=Math.sin(2*Math.PI*(type==2?310:90)*t)*(type==2?.6:.3);wav.putShort((short)((noise+tone)*envelope*26000));}try(FileOutputStream out=new FileOutputStream(file)){out.write(wav.array());}
        }
    }
}
