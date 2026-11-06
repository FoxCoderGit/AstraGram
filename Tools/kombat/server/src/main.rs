mod auth;mod worker;mod udp;
use std::{collections::{HashMap,BTreeMap},path::{Path,PathBuf},sync::Arc,time::{Duration,Instant},io::BufReader};
use futures_util::{SinkExt,StreamExt};use serde_json::{Value,json};
use tokio::{net::TcpListener,sync::{Mutex,mpsc,Semaphore},io::{AsyncBufReadExt,AsyncWriteExt},process::{Command,ChildStdin,ChildStdout}};
use tokio_tungstenite::{accept_async_with_config,tungstenite::{Message,protocol::WebSocketConfig}};
use tokio_rustls::{TlsAcceptor,rustls};
struct User {tx:mpsc::Sender<Value>,session:String,udp:udp::Peer}
#[derive(Clone)]struct Invite {from:u64,to:u64,expires:Instant}
#[derive(Clone)]struct Room {players:[u64;2],tx:mpsc::Sender<RoomCommand>}
enum RoomCommand {Input(u64,i32,i32),Pause(u64),Resume(u64),Reconnect(u64,bool),Leave(u64)}
struct Hub {users:HashMap<u64,User>,invites:HashMap<String,Invite>,rooms:HashMap<String,Room>,last_invite:HashMap<u64,Instant>,root:PathBuf,udp:Arc<tokio::net::UdpSocket>}
impl Hub {
 fn send(&self,uid:u64,value:Value){if let Some(user)=self.users.get(&uid){if value["op"]=="frame"{if let Some(address)=user.udp.address{let bytes=udp::packet(&user.session,&user.udp,&value);let _=self.udp.try_send_to(&bytes,address);}}let _=user.tx.try_send(value);}}
 fn both(&self,users:[u64;2],value:Value){self.send(users[0],value.clone());self.send(users[1],value);}
 fn room_of(&self,uid:u64)->Option<String>{self.rooms.iter().find(|(_,r)|r.players.contains(&uid)).map(|(id,_)|id.clone())}
 fn error(&self,uid:u64,reason:&str){self.send(uid,json!({"op":"error","error":reason}));}
}
type Shared=Arc<Mutex<Hub>>;
async fn command(hub:&Shared,uid:u64,value:Value){let op=if value["op"]=="join"{"accept"}else{value["op"].as_str().unwrap_or("")};let mut h=hub.lock().await;
 if op=="create" {
  if h.room_of(uid).is_some(){h.error(uid,"busy");return;}
  if h.last_invite.get(&uid).is_some_and(|t|t.elapsed()<Duration::from_secs(10)){h.error(uid,"rate_limited");return;}
  h.invites.retain(|_,i|i.expires>Instant::now());if h.invites.len()>=256{h.error(uid,"busy");return;}
  h.invites.retain(|_,i|i.from!=uid);let id=loop{let id=auth::random()[..10].to_string();if !h.invites.contains_key(&id){break id;}};
  h.invites.insert(id.clone(),Invite{from:uid,to:0,expires:Instant::now()+Duration::from_secs(300)});h.last_invite.insert(uid,Instant::now());h.send(uid,json!({"op":"code","code":id.to_uppercase(),"expires":300}));return;
 }
 if op=="invite" {let target=value["target"].as_u64().unwrap_or(0);if target==0||target==uid||h.room_of(uid).is_some()||h.room_of(target).is_some(){h.error(uid,"busy");return;}
  if !h.users.contains_key(&target){h.error(uid,"opponent_offline");return;}if h.last_invite.get(&uid).is_some_and(|last|last.elapsed()<Duration::from_secs(10)){h.error(uid,"rate_limited");return;}
  h.invites.retain(|_,i|i.expires>Instant::now());if h.invites.len()>256{h.error(uid,"busy");return;}let id=auth::random();h.invites.insert(id.clone(),Invite{from:uid,to:target,expires:Instant::now()+Duration::from_secs(60)});h.last_invite.insert(uid,Instant::now());h.send(uid,json!({"op":"inviting","id":id,"target":target,"expires":60}));h.send(target,json!({"op":"invite","id":id,"from":uid,"expires":60}));return;
 }
 if matches!(op,"accept"|"decline"|"cancel") {let id=value["id"].as_str().unwrap_or("");let Some(mut invite)=h.invites.get(id).cloned() else{h.error(uid,"invitation_expired");return;};if invite.expires<=Instant::now(){h.invites.remove(id);h.error(uid,"invitation_expired");return;}
  if value["op"]=="join"{if invite.to!=0||invite.from==uid{h.error(uid,"invalid_code");return;}invite.to=uid;}if(op=="cancel"&&uid!=invite.from)||(op!="cancel"&&uid!=invite.to){h.error(uid,"not_your_invitation");return;}h.invites.remove(id);if op!="accept"{h.both([invite.from,invite.to],json!({"op":"cancelled","id":id,"reason":op}));return;}
  if h.room_of(invite.from).is_some()||h.room_of(invite.to).is_some()||h.rooms.len()>=4{h.error(uid,"busy");return;}if !h.root.join("match.state").is_file(){h.both([invite.from,invite.to],json!({"op":"error","error":"game_not_ready"}));return;}
  let players=if rand::random::<bool>(){[invite.from,invite.to]}else{[invite.to,invite.from]};let room=auth::random();let(tx,rx)=mpsc::channel(512);h.rooms.insert(room.clone(),Room{players,tx});for slot in 0..2{h.send(players[slot],json!({"op":"room","room":room,"slot":slot,"opponent":players[1-slot],"character":if slot==0{"Scorpion"}else{"Sub-Zero"},"input_delay":4}));}let root=h.root.clone();drop(h);tokio::spawn(run_room(hub.clone(),root,room,players,rx));return;
 }
 if op=="ping"{h.send(uid,json!({"op":"pong","echo":value["echo"],"time":auth::now()*1000}));return;}
 let Some(id)=h.room_of(uid)else{h.error(uid,"no_match");return;};let Some(room)=h.rooms.get(&id)else{return;};let event=match op {
  "input"=>{let f=value["frame"].as_i64().unwrap_or(-1);let b=value["bits"].as_i64().unwrap_or(-1);if !(0..=10_000_000).contains(&f)||!(0..=2047).contains(&b){h.error(uid,"bad_input");return;}RoomCommand::Input(uid,f as i32,b as i32&!12)},
  "pause"=>RoomCommand::Pause(uid),"resume"=>RoomCommand::Resume(uid),"resync"=>RoomCommand::Reconnect(uid,false),"leave"=>RoomCommand::Leave(uid),_=>{h.error(uid,"unknown_operation");return;}
 };let _=room.tx.try_send(event);
}
struct Machine {child:tokio::process::Child,input:ChildStdin,output:tokio::io::Lines<tokio::io::BufReader<ChildStdout>>}
impl Machine {
 async fn new(root:&Path)->Result<Self,String>{let mut child=Command::new(std::env::current_exe().map_err(|e|e.to_string())?).arg("--worker").env("KOMBAT_DATA",root).stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null()).kill_on_drop(true).spawn().map_err(|e|e.to_string())?;let input=child.stdin.take().ok_or("worker stdin")?;let output=tokio::io::BufReader::new(child.stdout.take().ok_or("worker stdout")?).lines();let mut m=Self{child,input,output};if m.read().await?["op"]!="ready"{return Err("worker_start_failed".into());}Ok(m)}
 async fn read(&mut self)->Result<Value,String>{let line=tokio::time::timeout(Duration::from_secs(15),self.output.next_line()).await.map_err(|_|"worker_timeout")?.map_err(|e|e.to_string())?.ok_or("worker_closed")?;serde_json::from_str(&line).map_err(|_|"worker_protocol".into())}
 async fn ask(&mut self,value:Value)->Result<Value,String>{self.input.write_all((value.to_string()+"\n").as_bytes()).await.map_err(|e|e.to_string())?;self.input.flush().await.map_err(|e|e.to_string())?;self.read().await}
}
async fn run_room(hub:Shared,root:PathBuf,id:String,players:[u64;2],mut commands:mpsc::Receiver<RoomCommand>){
 let result=async {let mut machine=Machine::new(&root).await?;let state=machine.ask(json!({"op":"state"})).await?;hub.lock().await.both(players,state);hub.lock().await.both(players,json!({"op":"countdown","ms":2000,"time":auth::now()*1000}));tokio::time::sleep(Duration::from_secs(2)).await;
  let mut tick=tokio::time::interval(Duration::from_secs_f64(1./53.20494842529297));tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);let mut frame=0;let mut inputs:BTreeMap<i32,[Option<i32>;2]>=BTreeMap::new();let mut last=[0,0];let mut paused:[Option<Instant>;2]=[None,None];let mut resync_at=[Instant::now()-Duration::from_secs(10);2];
  loop{tokio::select!{
   message=commands.recv()=>{let Some(message)=message else{break;};match message{
    RoomCommand::Input(uid,f,b)=>{if f>=frame&&f<=frame+24{let slot=if uid==players[0]{0}else{1};inputs.entry(f).or_insert([None,None])[slot]=Some(b);}},
    RoomCommand::Pause(uid)=>{let slot=if uid==players[0]{0}else{1};if paused[slot].is_none(){paused[slot]=Some(Instant::now());}hub.lock().await.both(players,json!({"op":"paused","user":uid,"seconds":30}));},
    RoomCommand::Resume(uid)=>{let slot=if uid==players[0]{0}else{1};paused[slot]=None;if paused.iter().all(Option::is_none){hub.lock().await.both(players,json!({"op":"resumed","frame":frame}));}},
    RoomCommand::Reconnect(uid,forced)=>{let slot=if uid==players[0]{0}else{1};if forced||resync_at[slot].elapsed()>Duration::from_secs(3){let state=machine.ask(json!({"op":"state"})).await?;hub.lock().await.send(uid,state);resync_at[slot]=Instant::now();}paused[slot]=None;hub.lock().await.both(players,json!({"op":"resumed","frame":frame}));},
    RoomCommand::Leave(uid)=>return Ok::<_,String>((if uid==players[0]{1}else{0},"left".to_string())),
   }},
   _=tick.tick()=>{for slot in 0..2{if paused[slot].is_some_and(|p|p.elapsed()>Duration::from_secs(30)){return Ok((1-slot as i32,"disconnect".into()));}}if paused.iter().any(Option::is_some){continue;}
    let next=inputs.remove(&frame).unwrap_or([None,None]);for i in 0..2{last[i]=next[i].unwrap_or(last[i]&(16|32|64|128|1024));}let value=machine.ask(json!({"op":"step","a":last[0],"b":last[1]})).await?;frame=value["frame"].as_i64().ok_or("worker_frame")? as i32;hub.lock().await.both(players,value.clone());let winner=value["winner"].as_i64().unwrap_or(-1);if winner>=0{return Ok((winner as i32,"match".into()));}if frame>53*60*15{return Ok((-1,"timeout".into()));}
   }
  }}Ok((-1,"closed".into()))
 }.await;
 let(winner,reason)=result.unwrap_or((-1,"server_error".into()));auth::result(&root,&id,players,winner,&reason);let mut h=hub.lock().await;h.rooms.remove(&id);h.both(players,json!({"op":"result","room":id,"winner":if winner>=0{players[winner as usize]}else{0},"reason":reason,"rematch":true}));
}
async fn connection(stream:tokio::net::TcpStream,tls:TlsAcceptor,hub:Shared,public:Arc<Vec<u8>>)->Result<(),String>{
 let _=stream.set_nodelay(true);let stream=tokio::time::timeout(Duration::from_secs(7),tls.accept(stream)).await.map_err(|_|"tls_timeout")?.map_err(|_|"tls")?;let mut config=WebSocketConfig::default();config.max_message_size=Some(16384);config.max_frame_size=Some(16384);let mut socket=accept_async_with_config(stream,Some(config)).await.map_err(|_|"websocket")?;let nonce=auth::random();socket.send(Message::Text(json!({"op":"hello","nonce":nonce,"version":1,"time":auth::now()*1000}).to_string().into())).await.map_err(|_|"closed")?;
 let proof=tokio::time::timeout(Duration::from_secs(10),socket.next()).await.map_err(|_|"auth_timeout")?.ok_or("closed")?.map_err(|_|"closed")?;let proof:Value=serde_json::from_str(proof.to_text().map_err(|_|"auth")?).map_err(|_|"auth")?;let(claimed_uid,license,expires)=auth::verify(&proof,&nonce,&public)?;let uid=auth::game_identity(&license,claimed_uid);
 let session=auth::random();let peer=udp::Peer::new();let key=peer.key.clone();let(tx,mut rx)=mpsc::channel(256);{let mut h=hub.lock().await;if let Some(old)=h.users.insert(uid,User{tx,session:session.clone(),udp:peer}){let _=old.tx.try_send(json!({"op":"replaced"}));}if let Some(id)=h.room_of(uid){if let Some(room)=h.rooms.get(&id){let slot=if room.players[0]==uid{0}else{1};h.send(uid,json!({"op":"room","room":id,"slot":slot,"opponent":room.players[1-slot],"character":if slot==0{"Scorpion"}else{"Sub-Zero"},"input_delay":4}));let _=room.tx.try_send(RoomCommand::Reconnect(uid,true));}}}
 socket.send(Message::Text(json!({"op":"auth_ok","user_id":uid,"sid":session,"udp_key":key,"udp_port":9445,"time":auth::now()*1000}).to_string().into())).await.map_err(|_|"closed")?;let mut heartbeat=tokio::time::interval(Duration::from_secs(15));let mut activity=Instant::now();let mut window=Instant::now();let mut packets=0;
 loop{tokio::select!{
  incoming=socket.next()=>{let Some(Ok(message))=incoming else{break;};activity=Instant::now();match message{Message::Text(text)=>{if window.elapsed()>Duration::from_secs(1){packets=0;window=Instant::now();}packets+=1;if packets>160{break;}if let Ok(value)=serde_json::from_str::<Value>(&text){command(&hub,uid,value).await;}else{break;}},Message::Ping(p)=>{if socket.send(Message::Pong(p)).await.is_err(){break;}},Message::Close(_)=>break,_=>{}}},
  value=rx.recv()=>{let Some(value)=value else{break;};let replaced=value["op"]=="replaced";if socket.send(Message::Text(value.to_string().into())).await.is_err()||replaced{break;}},
  _=heartbeat.tick()=>{if auth::now()>=expires||activity.elapsed()>Duration::from_secs(75){break;}if socket.send(Message::Ping(Vec::new().into())).await.is_err(){break;}},
 }}let mut h=hub.lock().await;if h.users.get(&uid).is_some_and(|u|u.session==session){h.users.remove(&uid);if let Some(id)=h.room_of(uid){if let Some(room)=h.rooms.get(&id){let _=room.tx.try_send(RoomCommand::Pause(uid));}}}Ok(())
}
#[tokio::main]async fn main()->Result<(),Box<dyn std::error::Error>>{
 let root=PathBuf::from(std::env::var("KOMBAT_DATA").unwrap_or("/var/lib/ghostgram-kombat".into()));let args:Vec<String>=std::env::args().collect();if args.get(1).is_some_and(|s|s=="--worker"){return worker::run(&root);}auth::init(&root).map_err(std::io::Error::other)?;
 if args.get(1).is_some_and(|s|s=="approve"){auth::approve(&root,args.get(2).ok_or("license id required")?,args.get(3).ok_or("telegram id required")?.parse()?).map_err(std::io::Error::other)?;println!("Binding approved");return Ok(());}
 if args.get(1).is_some_and(|s|s=="pending"){let db=auth::db(&root).map_err(std::io::Error::other)?;let mut query=db.prepare("SELECT license,telegram,seen FROM requests ORDER BY seen DESC LIMIT 100")?;for row in query.query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,i64>(1)?,r.get::<_,i64>(2)?)))?{let(license,uid,time)=row?;println!("{license} {uid} {time}");}return Ok(());}
 let _=rustls::crypto::ring::default_provider().install_default();let certs=rustls_pemfile::certs(&mut BufReader::new(std::fs::File::open(root.join("server.crt"))?)).collect::<Result<Vec<_>,_>>()?;let key=rustls_pemfile::private_key(&mut BufReader::new(std::fs::File::open(root.join("server.key"))?))?.ok_or("TLS key")?;let config=rustls::ServerConfig::builder().with_no_client_auth().with_single_cert(certs,key)?;let tls=TlsAcceptor::from(Arc::new(config));let public=Arc::new(std::fs::read(root.join("license-public.der"))?);let udp=Arc::new(tokio::net::UdpSocket::bind(std::env::var("KOMBAT_UDP").unwrap_or("0.0.0.0:9445".into())).await?);let hub=Arc::new(Mutex::new(Hub{users:HashMap::new(),invites:HashMap::new(),rooms:HashMap::new(),last_invite:HashMap::new(),root,udp:udp.clone()}));tokio::spawn(udp::run(udp,hub.clone()));let connections=Arc::new(Semaphore::new(128));let listener=TcpListener::bind(std::env::var("KOMBAT_LISTEN").unwrap_or("0.0.0.0:9444".into())).await?;println!("GhostGram Kombat WSS/UDP ready");
 loop{let(stream,_)=listener.accept().await?;let Ok(permit)=connections.clone().try_acquire_owned()else{continue;};let(tls,hub,key)=(tls.clone(),hub.clone(),public.clone());tokio::spawn(async move{let _permit=permit;let _=connection(stream,tls,hub,key).await;});}
}
