package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Local game state only: no Telegram objects, controllers, storage or network. */
public final class GhostDoomWorld {
    public static final int SIZE=29;
    public final boolean[][] walls=new boolean[SIZE][SIZE];
    public final int[][] panels=new int[SIZE][SIZE];
    public final ArrayList<Enemy> enemies=new ArrayList<>();
    public final ArrayList<Bolt> bolts=new ArrayList<>();
    public final ArrayList<Pickup> pickups=new ArrayList<>();
    public final long seed;public final int messageCount;
    public float x=2.5f,z=2.5f,yaw,time,damageFlash,muzzle,shotCooldown;
    public int health=100,score,kills,weapon;public boolean dead,won;
    public float exitX,exitZ;
    private final Random random;private float pathTimer;
    private final int[][] distance=new int[SIZE][SIZE];
    private final int[] bfs=new int[SIZE*SIZE];
    public static final class Enemy {
        public float x,z,hurt,attack,death;public int hp,avatar;public boolean alive=true,alerted;
        Enemy(float x,float z,int avatar){this.x=x;this.z=z;this.avatar=avatar;hp=75+(avatar%3)*15;}
    }
    public static final class Bolt {public float x,z,dx,dz,life=4;Bolt(float x,float z,float dx,float dz){this.x=x;this.z=z;this.dx=dx;this.dz=dz;}}
    public static final class Pickup {public float x,z;public boolean used;Pickup(float x,float z){this.x=x;this.z=z;}}
    public GhostDoomWorld(long seed,int messageCount,int avatars){
        this.seed=seed;this.messageCount=messageCount;random=new Random(seed);
        for(boolean[] row:walls)Arrays.fill(row,true);
        for(int rz=0;rz<7;rz++)for(int rx=0;rx<7;rx++)carveRoom(rx,rz);
        boolean[][] seen=new boolean[7][7];int[] stack=new int[49];int top=0;stack[0]=0;seen[0][0]=true;
        while(top>=0){int cell=stack[top],cx=cell%7,cz=cell/7;int[] choices=new int[4];int n=0;
            for(int d=0;d<4;d++){int nx=cx+DX[d],nz=cz+DZ[d];if(nx>=0&&nz>=0&&nx<7&&nz<7&&!seen[nz][nx])choices[n++]=d;}
            if(n==0){top--;continue;}int d=choices[random.nextInt(n)],nx=cx+DX[d],nz=cz+DZ[d];
            connect(cx,cz,nx,nz);seen[nz][nx]=true;stack[++top]=nz*7+nx;
        }
        for(int i=0;i<15;i++){int rx=random.nextInt(6),rz=random.nextInt(6);if(random.nextBoolean())connect(rx,rz,rx+1,rz);else connect(rx,rz,rx,rz+1);}
        for(int iz=0;iz<SIZE;iz++)for(int ix=0;ix<SIZE;ix++)panels[iz][ix]=random.nextInt(Math.max(1,messageCount));
        paths();int far=0;
        for(int rz=0;rz<7;rz++)for(int rx=0;rx<7;rx++){int ix=2+rx*4,iz=2+rz*4;if(distance[iz][ix]>far){far=distance[iz][ix];exitX=ix+.5f;exitZ=iz+.5f;}}
        int count=Math.min(44,24+messageCount/3);boolean[] occupied=new boolean[49];occupied[0]=true;
        while(enemies.size()<count){int room=1+random.nextInt(48);if(occupied[room])continue;occupied[room]=true;enemies.add(new Enemy(2.5f+(room%7)*4,2.5f+(room/7)*4,enemies.size()%Math.max(1,avatars)));}
        for(int i=0;i<10;i++){int room=1+random.nextInt(48);pickups.add(new Pickup(2.5f+(room%7)*4,2.5f+(room/7)*4));}
        // Start looking into a connected corridor, rather than a close featureless wall.
        yaw=!solid(4.5f,2.5f)?(float)(Math.PI/2):(float)Math.PI;
    }
    private static final int[] DX={1,-1,0,0},DZ={0,0,1,-1};
    private void carveRoom(int rx,int rz){for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++)walls[2+rz*4+j][2+rx*4+i]=false;}
    private void connect(int rx,int rz,int tx,int tz){int ix=2+rx*4,iz=2+rz*4;while(ix!=2+tx*4||iz!=2+tz*4){walls[iz][ix]=false;ix+=Integer.signum(2+tx*4-ix);iz+=Integer.signum(2+tz*4-iz);}walls[iz][ix]=false;}
    public boolean solid(float px,float pz){int ix=(int)Math.floor(px),iz=(int)Math.floor(pz);return ix<0||iz<0||ix>=SIZE||iz>=SIZE||walls[iz][ix];}
    public boolean free(float px,float pz,float r){return !solid(px-r,pz-r)&&!solid(px+r,pz-r)&&!solid(px-r,pz+r)&&!solid(px+r,pz+r);}
    public boolean visible(float ax,float az,float bx,float bz){float distance=(float)Math.hypot(bx-ax,bz-az);int steps=Math.max(1,(int)(distance*16));for(int i=1;i<=steps;i++)if(solid(ax+(bx-ax)*i/steps,az+(bz-az)*i/steps))return false;return true;}
    private void paths(){
        for(int[] row:distance)Arrays.fill(row,-1);int ix=(int)x,iz=(int)z;if(solid(x,z))return;
        int head=0,tail=0;bfs[tail++]=iz*SIZE+ix;distance[iz][ix]=0;
        while(head<tail){int cell=bfs[head++],cx=cell%SIZE,cz=cell/SIZE;for(int d=0;d<4;d++){int nx=cx+DX[d],nz=cz+DZ[d];if(nx<0||nz<0||nx>=SIZE||nz>=SIZE||walls[nz][nx]||distance[nz][nx]>=0)continue;distance[nz][nx]=distance[cz][cx]+1;bfs[tail++]=nz*SIZE+nx;}}
    }
    public void tick(float dt,float strafe,float forward,float turn){
        dt=Math.max(0,Math.min(.04f,dt));time+=dt;muzzle=Math.max(0,muzzle-dt);damageFlash=Math.max(0,damageFlash-dt);shotCooldown=Math.max(0,shotCooldown-dt);
        if(dead||won)return;yaw+=turn;if(!Float.isFinite(yaw))yaw=0;
        float magnitude=Math.max(1,(float)Math.hypot(strafe,forward));strafe/=magnitude;forward/=magnitude;
        float dx=((float)Math.cos(yaw)*strafe+(float)Math.sin(yaw)*forward)*dt*2.8f;
        float dz=((float)Math.sin(yaw)*strafe-(float)Math.cos(yaw)*forward)*dt*2.8f;
        if(free(x+dx,z,.20f))x+=dx;if(free(x,z+dz,.20f))z+=dz;
        if((pathTimer-=dt)<=0){paths();pathTimer=.4f;}
        for(Enemy enemy:enemies){enemy.hurt=Math.max(0,enemy.hurt-dt);if(!enemy.alive){enemy.death+=dt;continue;}enemy.attack-=dt;
            float distance=(float)Math.hypot(x-enemy.x,z-enemy.z);boolean sees=distance<10&&visible(enemy.x,enemy.z,x,z);
            if(distance>.65f&&(distance<16||enemy.alerted)){
                float tx=x,tz=z;
                if(!sees){int cx=(int)enemy.x,cz=(int)enemy.z,best=distance(cz,cx);tx=cx+.5f;tz=cz+.5f;
                    for(int d=0;d<4;d++){int nx=cx+DX[d],nz=cz+DZ[d],cost=distance(nz,nx);if(cost>=0&&(best<0||cost<best)){best=cost;tx=nx+.5f;tz=nz+.5f;}}}
                float length=Math.max(.01f,(float)Math.hypot(tx-enemy.x,tz-enemy.z));float speed=dt*(.85f+(enemy.avatar%3)*.15f);
                float ex=(tx-enemy.x)/length*speed,ez=(tz-enemy.z)/length*speed;
                if(free(enemy.x+ex,enemy.z,.18f))enemy.x+=ex;if(free(enemy.x,enemy.z+ez,.18f))enemy.z+=ez;
            }
            if(sees&&enemy.attack<=0){enemy.attack=1.05f+random.nextFloat()*.85f;if(distance<.85f)hurt(12);else{float length=Math.max(.01f,distance);bolts.add(new Bolt(enemy.x,enemy.z,(x-enemy.x)/length*3.3f,(z-enemy.z)/length*3.3f));}}
        }
        for(int i=bolts.size()-1;i>=0;i--){Bolt b=bolts.get(i);b.x+=b.dx*dt;b.z+=b.dz*dt;b.life-=dt;if(Math.hypot(b.x-x,b.z-z)<.27f){hurt(9);b.life=0;}if(b.life<=0||solid(b.x,b.z))bolts.remove(i);}
        for(Pickup p:pickups)if(!dead&&!p.used&&health<100&&Math.hypot(p.x-x,p.z-z)<.55f){health=Math.min(100,health+30);p.used=true;score+=20;}
        if(!dead&&kills==enemies.size()&&Math.hypot(exitX-x,exitZ-z)<.65f){won=true;score+=1000;}
    }
    private int distance(int z,int x){return x<0||z<0||x>=SIZE||z>=SIZE?-1:distance[z][x];}
    private void hurt(int amount){health=Math.max(0,health-amount);damageFlash=.28f;dead=health==0;}
    /** Return true only when a new shot was fired; ray hits cannot pass through walls. */
    public boolean fire(){
        if(dead||won||shotCooldown>0)return false;shotCooldown=weapon==0?.25f:.78f;muzzle=.10f;
        int pellets=weapon==0?1:7;for(int p=0;p<pellets;p++){
            float angle=yaw+(weapon==0?0:(random.nextFloat()-.5f)*.15f);float dx=(float)Math.sin(angle),dz=-(float)Math.cos(angle);
            Enemy hit=null;float nearest=24;
            for(Enemy e:enemies){if(!e.alive)continue;float ex=e.x-x,ez=e.z-z,along=ex*dx+ez*dz,across=Math.abs(ex*dz-ez*dx);
                if(along>0&&along<nearest&&across<.30f&&visible(x,z,e.x,e.z)){nearest=along;hit=e;}}
            if(hit!=null){hit.alerted=true;hit.hp-=weapon==0?30:16;hit.hurt=.20f;if(hit.hp<=0){hit.alive=false;kills++;score+=100;}}
        }return true;
    }
}
