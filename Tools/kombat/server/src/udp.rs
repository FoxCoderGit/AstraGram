use std::{sync::{Arc,atomic::{AtomicU64,Ordering}},net::SocketAddr,time::{Instant,Duration}};
use tokio::net::UdpSocket;use serde_json::{Value,json};use hmac::{Hmac,Mac};use sha2::Sha256;
use crate::{Shared,command,auth};
pub struct Peer {pub key:String,pub last:u64,pub sent:AtomicU64,pub address:Option<SocketAddr>,pub window:Instant,pub packets:u32}
impl Peer {pub fn new()->Self{Self{key:auth::random(),last:0,sent:AtomicU64::new(0),address:None,window:Instant::now(),packets:0}}}
pub fn sign(session:&str,key:&str,seq:u64,body:&str)->String{let mut mac=Hmac::<Sha256>::new_from_slice(key.as_bytes()).unwrap();mac.update(format!("{session}\n{seq}\n{body}").as_bytes());auth::hex(&mac.finalize().into_bytes())}
pub fn packet(session:&str,peer:&Peer,event:&Value)->Vec<u8>{let seq=peer.sent.fetch_add(1,Ordering::Relaxed)+1;let body=event.to_string();json!({"sid":session,"seq":seq,"body":body,"mac":sign(session,&peer.key,seq,&body)}).to_string().into_bytes()}
pub async fn run(socket:Arc<UdpSocket>,hub:Shared){let mut data=[0;1024];loop{let Ok((n,address))=socket.recv_from(&mut data).await else{continue;};let Ok(value)=serde_json::from_slice::<Value>(&data[..n])else{continue;};let sid=value["sid"].as_str().unwrap_or("");let body=value["body"].as_str().unwrap_or("");let seq=value["seq"].as_u64().unwrap_or(0);if sid.len()!=64||body.len()>400||seq==0{continue;}let supplied=value["mac"].as_str().unwrap_or("");let mut h=hub.lock().await;
 let Some((&uid,user))=h.users.iter_mut().find(|(_,u)|u.session==sid)else{continue;};if seq<=user.udp.last{continue;}let expected=sign(sid,&user.udp.key,seq,body);if supplied.len()!=expected.len()||!supplied.bytes().zip(expected.bytes()).fold(true,|ok,(a,b)|ok&(a==b)){continue;}
 if user.udp.window.elapsed()>Duration::from_secs(1){user.udp.window=Instant::now();user.udp.packets=0;}user.udp.packets+=1;if user.udp.packets>160{continue;}user.udp.last=seq;user.udp.address=Some(address);
 let Ok(event)=serde_json::from_str::<Value>(body)else{continue;};if event["op"]!="input"{continue;}drop(h);command(&hub,uid,event).await;
}}
