from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatOnline.java';t=p.read_text(encoding='utf-8')
t=t.replace('public volatile long gameId;', 'public volatile long gameId;public volatile long roundTripMs=150;')
t=t.replace('private Client(int account,long uid){', '''private final Runnable heartbeat=new Runnable(){public void run(){if(closed||!authenticated)return;try{send(new JSONObject().put("op","ping").put("echo",android.os.SystemClock.elapsedRealtime()));}catch(Exception ignored){}AndroidUtilities.runOnUIThread(this,2000);}};
        private Client(int account,long uid){''')
t=t.replace('next.setConnectionLostTimeout(30);','next.setConnectionLostTimeout(30);next.setTcpNoDelay(true);')
t=t.replace('authenticated=false;connecting=false;closeDatagram();', 'authenticated=false;connecting=false;AndroidUtilities.cancelRunOnUIThread(heartbeat);closeDatagram();')
t=t.replace('closed=true;authenticated=false;closeDatagram();', 'closed=true;authenticated=false;AndroidUtilities.cancelRunOnUIThread(heartbeat);closeDatagram();')
t=t.replace('if(op.equals("auth_ok")){backoff=1000;', 'if(op.equals("auth_ok")){AndroidUtilities.cancelRunOnUIThread(heartbeat);AndroidUtilities.runOnUIThread(heartbeat,100);backoff=1000;')
t=t.replace('if(op.equals("needs_binding"))', 'if(op.equals("pong")){long elapsed=android.os.SystemClock.elapsedRealtime()-event.optLong("echo");if(elapsed>=0&&elapsed<5000)roundTripMs=(roundTripMs*2+elapsed)/3;return;}\n            if(op.equals("needs_binding"))')
p.write_text(t,encoding='utf-8')
p=root/'Tools/kombat/server/src/main.rs';t=p.read_text(encoding='utf-8').replace(' let stream=tokio::time::timeout(Duration::from_secs(7),tls.accept(stream))', ' let _=stream.set_nodelay(true);let stream=tokio::time::timeout(Duration::from_secs(7),tls.accept(stream))');p.write_text(t,encoding='utf-8')
p=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatSession.java';t=p.read_text(encoding='utf-8').replace('if((frame&3)==0)', 'if(snapshots.isEmpty()||(frame&3)==0)');p.write_text(t,encoding='utf-8')
