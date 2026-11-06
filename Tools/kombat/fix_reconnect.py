from pathlib import Path
p=Path(__file__).resolve().parent/'server/src/main.rs'
t=p.read_text(encoding='utf-8').replace('Reconnect(u64),','Reconnect(u64,bool),')
t=t.replace('"resync"=>RoomCommand::Reconnect(uid)', '"resync"=>RoomCommand::Reconnect(uid,false)')
t=t.replace('RoomCommand::Reconnect(uid)=>','RoomCommand::Reconnect(uid,forced)=>')
t=t.replace('if resync_at[slot].elapsed()>Duration::from_secs(3)', 'if forced||resync_at[slot].elapsed()>Duration::from_secs(3)')
t=t.replace('room.tx.try_send(RoomCommand::Reconnect(uid))','room.tx.try_send(RoomCommand::Reconnect(uid,true))')
p.write_text(t,encoding='utf-8')
