use std::{io::{self,BufRead,Write},ffi::CString,path::Path};
use base64::{Engine,engine::general_purpose::STANDARD as B64};
use libloading::Library;
use serde_json::{Value,json};
type Open=unsafe extern "C" fn(*const i8,*const i8)->i32;type Step=unsafe extern "C" fn(i32,i32,i32)->i32;type Size=unsafe extern "C" fn()->usize;type Save=unsafe extern "C" fn(*mut u8,usize)->i32;type Load=unsafe extern "C" fn(*const u8,usize)->i32;type Read=unsafe extern "C" fn(u32)->i32;type Hash=unsafe extern "C" fn()->u64;
pub fn run(root:&Path)->Result<(),Box<dyn std::error::Error>>{unsafe{
 let library=Library::new(root.join("libghostkombat.so"))?;let open=*library.get::<Open>(b"gg_open")?;let step=*library.get::<Step>(b"gg_step")?;let size=*library.get::<Size>(b"gg_save_size")?;let save=*library.get::<Save>(b"gg_save")?;let load=*library.get::<Load>(b"gg_load")?;let read=*library.get::<Read>(b"gg_read_byte")?;let hash=*library.get::<Hash>(b"gg_logic_hash")?;
 let rom=CString::new(root.join("mkla4.zip").to_str().unwrap())?;let dir=CString::new(std::env::temp_dir().join(format!("gg-kombat-{}",std::process::id())).to_str().unwrap())?;std::fs::create_dir_all(dir.to_str()?)?;
 if open(rom.as_ptr(),dir.as_ptr())!=1{return Err("original ROM failed to load".into())}step(0,0,0);let initial=std::fs::read(root.join("match.state"))?;if load(initial.as_ptr(),initial.len())!=1{return Err("match state rejected".into())}
 let mut frame=0;let mut rounds=[0,0];let mut round_over=false;let mut finish=0;let mut winner=-1;let stdout=io::stdout();let mut out=stdout.lock();let mut previous=[read(0x209a04),read(0x209a60)];
 writeln!(out,"{}",json!({"op":"ready","size":size(),"hash":hash().to_string()}))?;out.flush()?;
 for line in io::stdin().lock().lines(){let value:Value=serde_json::from_str(&line?)?;match value["op"].as_str().unwrap_or(""){
  "step"=>{let a=value["a"].as_i64().unwrap_or(0) as i32&2047;let b=value["b"].as_i64().unwrap_or(0) as i32&2047;step(a,b,0);frame+=1;let health=[read(0x209a04),read(0x209a60)];
   let timer=read(0x209abe)*10+read(0x209ac0);
   if !round_over&&health.iter().all(|h|*h>=0&&*h<=161)&&(timer==0||health.iter().any(|h|*h==0)){
    round_over=true;if health[0]!=health[1]{let w=if health[0]>health[1]{0}else{1};rounds[w]+=1;if rounds[w]>=2{winner=w as i32;finish=450;}}
   }
   if round_over&&health==[161,161]&&timer==99{round_over=false;}
   if finish>0{finish-=1;}
   writeln!(out,"{}",json!({"op":"frame","frame":frame,"a":a,"b":b,"hash":hash().to_string(),"health":health,"rounds":rounds,"winner":if finish==0{winner}else{-1}}))?;previous=health;
  },
  "state"=>{let mut data=vec![0;size()];if save(data.as_mut_ptr(),data.len())!=1{return Err("snapshot failed".into())}writeln!(out,"{}",json!({"op":"state","frame":frame,"data":B64.encode(data),"hash":hash().to_string(),"rounds":rounds}))?;},
  _=>break,
 }out.flush()?;}
 Ok(())
}}
