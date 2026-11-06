use std::{path::Path,time::{SystemTime,UNIX_EPOCH}};
use base64::{Engine,engine::general_purpose::URL_SAFE_NO_PAD as B64};
use p256::ecdsa::{VerifyingKey,Signature,signature::Verifier};
use rsa::{RsaPublicKey,pkcs8::DecodePublicKey,pkcs1v15};
use sha2::{Sha256,Digest};
use serde_json::Value;
use rusqlite::{Connection,params};
pub fn now()->u64 {SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()}
pub fn hex(bytes:&[u8])->String{bytes.iter().map(|b|format!("{b:02x}")).collect()}
pub fn random()->String {use rand::RngCore;let mut bytes=[0;32];rand::thread_rng().fill_bytes(&mut bytes);hex(&bytes)}
pub fn db(root:&Path)->Result<Connection,String>{let db=Connection::open(root.join("kombat.sqlite")).map_err(|e|e.to_string())?;db.busy_timeout(std::time::Duration::from_secs(5)).map_err(|e|e.to_string())?;Ok(db)}
pub fn init(root:&Path)->Result<(),String>{std::fs::create_dir_all(root).map_err(|e|e.to_string())?;db(root)?.execute_batch("PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS accounts(telegram INTEGER PRIMARY KEY,license TEXT NOT NULL,created INTEGER NOT NULL); CREATE TABLE IF NOT EXISTS requests(license TEXT NOT NULL,telegram INTEGER NOT NULL,seen INTEGER NOT NULL,PRIMARY KEY(license,telegram)); CREATE TABLE IF NOT EXISTS results(id TEXT PRIMARY KEY,p0 INTEGER,p1 INTEGER,winner INTEGER,reason TEXT,at INTEGER);").map_err(|e|e.to_string())?;Ok(())}
fn text<'a>(value:&'a Value,key:&str,max:usize)->Result<&'a str,String>{let s=value[key].as_str().ok_or("bad_auth")?;if s.len()>max{return Err("bad_auth".into())}Ok(s)}
pub fn verify(value:&Value,nonce:&str,public:&[u8])->Result<(u64,String,u64),String>{
 let uid=value["user_id"].as_u64().filter(|id|*id>0&&*id<i64::MAX as u64).ok_or("bad_user")?;
 let key=B64.decode(text(value,"public_key",1024)?).map_err(|_|"bad_device")?;
 let lease=&value["lease"];let payload=B64.decode(text(lease,"payload",4096)?).map_err(|_|"bad_lease")?;let signature=B64.decode(text(lease,"signature",1024)?).map_err(|_|"bad_lease")?;
 let rsa=RsaPublicKey::from_public_key_der(public).map_err(|_|"bad_server_key")?;let rsa=pkcs1v15::VerifyingKey::<Sha256>::new(rsa);let sig=pkcs1v15::Signature::try_from(signature.as_slice()).map_err(|_|"bad_lease")?;
 rsa.verify(&payload,&sig).map_err(|_|"bad_lease")?;let claims:Value=serde_json::from_slice(&payload).map_err(|_|"bad_lease")?;
 let until=claims["until"].as_u64().ok_or("expired_license")?;let issued=claims["issued_at"].as_u64().ok_or("expired_license")?;
 if claims["v"]!=1||claims["app"]!="org.ghostgram.messenger"||until<=now()||issued>now()+30||until.saturating_sub(issued)>86400||claims["device"]!=hex(&Sha256::digest(&key)){return Err("expired_license".into())}
 let license=text(&claims,"license_id",32)?.to_string();if license.len()!=32||!license.bytes().all(|b|b.is_ascii_hexdigit()){return Err("bad_license".into())}
 let ec=VerifyingKey::from_public_key_der(&key).map_err(|_|"bad_device")?;let proof=B64.decode(text(value,"proof",256)?).map_err(|_|"bad_proof")?;let proof=Signature::from_der(&proof).map_err(|_|"bad_proof")?;
 ec.verify(format!("ghostgram-kombat-v1\n{nonce}\n{uid}").as_bytes(),&proof).map_err(|_|"bad_proof")?;
 Ok((uid,license,until))
}
pub fn approved(root:&Path,uid:u64,license:&str)->Result<bool,String>{let db=db(root)?;let ok=db.query_row("SELECT count(*) FROM accounts WHERE telegram=? AND license=?",params![uid as i64,license],|r|r.get::<_,i64>(0)).map_err(|e|e.to_string())?!=0;if !ok {let count=db.query_row("SELECT count(*) FROM requests WHERE license=?",[license],|r|r.get::<_,i64>(0)).unwrap_or(100);if count<12{db.execute("INSERT INTO requests VALUES(?,?,?) ON CONFLICT(license,telegram) DO UPDATE SET seen=excluded.seen",params![license,uid as i64,now() as i64]).map_err(|e|e.to_string())?;}}Ok(ok)}
pub fn approve(root:&Path,license:&str,uid:u64)->Result<(),String>{if license.len()!=32||!license.bytes().all(|b|b.is_ascii_hexdigit())||uid==0||uid>i64::MAX as u64{return Err("invalid binding".into())}db(root)?.execute("INSERT INTO accounts VALUES(?,?,?) ON CONFLICT(telegram) DO UPDATE SET license=excluded.license,created=excluded.created",params![uid as i64,license,now() as i64]).map_err(|e|e.to_string())?;Ok(())}
pub fn result(root:&Path,id:&str,users:[u64;2],winner:i32,reason:&str){if let Ok(db)=db(root){let _=db.execute("INSERT OR IGNORE INTO results VALUES(?,?,?,?,?,?)",params![id,users[0] as i64,users[1] as i64,if winner>=0{users[winner as usize] as i64}else{0},reason,now() as i64]);}}

pub fn game_identity(license:&str,account:u64)->u64{let digest=Sha256::digest(format!("ghostgram-kombat-code-v1:{license}:{account}").as_bytes());u64::from_le_bytes(digest[..8].try_into().unwrap())&0x7fff_ffff_ffff_ffff}
