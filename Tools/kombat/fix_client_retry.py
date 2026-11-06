from pathlib import Path
p=Path(__file__).resolve().parents[2]/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatOnline.java'
t=p.read_text(encoding='utf-8')
t=t.replace('connecting=false;backoff=1000;', 'connecting=false;')
t=t.replace('private void disconnected(){authenticated=false;connecting=false;closeDatagram();if(closed)return;', 'private synchronized void disconnected(){if(socket==null&&!connecting)return;WebSocketClient previous=socket;socket=null;authenticated=false;connecting=false;closeDatagram();if(previous!=null)previous.close();if(closed||binding)return;')
t=t.replace('if(op.equals("auth_ok")){authenticated=true;', 'if(op.equals("auth_ok")){backoff=1000;authenticated=true;')
p.write_text(t,encoding='utf-8')
