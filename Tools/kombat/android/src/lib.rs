use ghost_kombat_core::{State,WORDS};
use jni::{JNIEnv,objects::{JClass,JIntArray},sys::{jint,jintArray,jlong}};
fn output(env:&mut JNIEnv,s:State)->jintArray{match env.new_int_array(WORDS as i32){Ok(a)=>{if env.set_int_array_region(&a,0,&s.encode()).is_err(){return std::ptr::null_mut();}a.into_raw()},Err(_)=>std::ptr::null_mut()}}
fn read(env:&mut JNIEnv,array:&JIntArray)->Option<State>{if env.get_array_length(array).ok()?!=WORDS as i32{return None}let mut v=[0;WORDS];env.get_int_array_region(array,0,&mut v).ok()?;State::decode(&v)}
#[no_mangle]pub extern "system" fn Java_org_telegram_messenger_GhostKombatCore_newGame(mut env:JNIEnv,_class:JClass,seed:jlong)->jintArray {output(&mut env,State::new(seed as u64))}
#[no_mangle]pub extern "system" fn Java_org_telegram_messenger_GhostKombatCore_step(mut env:JNIEnv,_class:JClass,array:JIntArray,a:jint,b:jint)->jintArray {match read(&mut env,&array){Some(mut state)=>{state.step([a&511,b&511]);output(&mut env,state)},None=>std::ptr::null_mut()}}
#[no_mangle]pub extern "system" fn Java_org_telegram_messenger_GhostKombatCore_botInput(mut env:JNIEnv,_class:JClass,array:JIntArray,player:jint)->jint {read(&mut env,&array).map(|s|s.bot_input((player&1) as usize)).unwrap_or(0)}
