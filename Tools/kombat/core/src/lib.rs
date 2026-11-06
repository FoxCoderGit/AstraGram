//! The only fighting simulation: integer-only, 60 Hz, identical on server and Android.
pub const VERSION:i32=1;
pub const WORDS:usize=96;
pub const LEFT:i32=1;pub const RIGHT:i32=2;pub const UP:i32=4;pub const DOWN:i32=8;
pub const PUNCH:i32=16;pub const KICK:i32=32;pub const BLOCK:i32=64;pub const SPECIAL:i32=128;pub const FATAL:i32=256;
pub const COUNTDOWN:i32=0;pub const FIGHT:i32=1;pub const ROUND_END:i32=2;pub const FINISH:i32=3;pub const FATALITY:i32=4;pub const RESULT:i32=5;
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct Fighter {pub x:i32,pub y:i32,pub vy:i32,pub hp:i32,pub action:i32,pub age:i32,pub stun:i32,pub frozen:i32,pub cooldown:i32,pub special_cd:i32,pub facing:i32,pub crouch:i32,pub block:i32,pub character:i32,pub combo:i32,pub combo_window:i32,pub last:i32}
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct Projectile {pub active:i32,pub owner:i32,pub x:i32,pub y:i32,pub velocity:i32,pub kind:i32,pub life:i32}
#[derive(Clone,Debug,PartialEq,Eq)]
pub struct State {pub frame:i32,pub phase:i32,pub phase_left:i32,pub clock:i32,pub rounds:[i32;2],pub winner:i32,pub fighters:[Fighter;2],pub projectiles:[Projectile;4],pub event:i32,pub event_frame:i32,pub round:i32}
impl State {
 pub fn new(seed:u64)->Self {let swap=(seed&1) as i32;let mut s=Self{frame:0,phase:COUNTDOWN,phase_left:180,clock:99*60,rounds:[0,0],winner:-1,fighters:[Fighter::default();2],projectiles:[Projectile::default();4],event:0,event_frame:0,round:1};s.fighters[0].character=swap;s.fighters[1].character=1-swap;s.reset_fighters();s}
 fn reset_fighters(&mut self){for i in 0..2{let character=self.fighters[i].character;self.fighters[i]=Fighter{x:if i==0{2400}else{7600},hp:1000,facing:if i==0{1}else{-1},character,..Fighter::default()};}self.projectiles=[Projectile::default();4];self.clock=99*60;}
 pub fn encode(&self)->[i32;WORDS] {let mut out=[0;WORDS];out[..12].copy_from_slice(&[VERSION,self.frame,self.phase,self.phase_left,self.clock,self.rounds[0],self.rounds[1],self.winner,self.event,self.event_frame,self.round,0]);for i in 0..2{let f=self.fighters[i];out[12+i*20..29+i*20].copy_from_slice(&[f.x,f.y,f.vy,f.hp,f.action,f.age,f.stun,f.frozen,f.cooldown,f.special_cd,f.facing,f.crouch,f.block,f.character,f.combo,f.combo_window,f.last]);}for i in 0..4{let p=self.projectiles[i];out[52+i*8..59+i*8].copy_from_slice(&[p.active,p.owner,p.x,p.y,p.velocity,p.kind,p.life]);}out}
 pub fn decode(v:&[i32])->Option<Self>{if v.len()!=WORDS||v[0]!=VERSION{return None}let mut s=Self::new(0);s.frame=v[1];s.phase=v[2];s.phase_left=v[3];s.clock=v[4];s.rounds=[v[5],v[6]];s.winner=v[7];s.event=v[8];s.event_frame=v[9];s.round=v[10];for i in 0..2{let a=&v[12+i*20..];s.fighters[i]=Fighter{x:a[0],y:a[1],vy:a[2],hp:a[3],action:a[4],age:a[5],stun:a[6],frozen:a[7],cooldown:a[8],special_cd:a[9],facing:a[10],crouch:a[11],block:a[12],character:a[13],combo:a[14],combo_window:a[15],last:a[16]};}for i in 0..4{let a=&v[52+i*8..];s.projectiles[i]=Projectile{active:a[0],owner:a[1],x:a[2],y:a[3],velocity:a[4],kind:a[5],life:a[6]};}Some(s)}
 pub fn hash(&self)->u64 {let mut hash=0xcbf29ce484222325u64;for v in self.encode(){for b in v.to_le_bytes(){hash^=b as u64;hash=hash.wrapping_mul(0x100000001b3);}}hash}
 fn event(&mut self,event:i32){self.event=event;self.event_frame=self.frame;}
 fn end_round(&mut self,winner:i32){self.winner=winner;if winner>=0{self.rounds[winner as usize]+=1;}self.projectiles=[Projectile::default();4];self.phase=if winner>=0&&self.rounds[winner as usize]>=2{FINISH}else{ROUND_END};self.phase_left=if self.phase==FINISH{240}else{150};self.event(4);}
 pub fn step(&mut self,inputs:[i32;2]){
  self.frame+=1;
  if self.phase!=FIGHT {self.phase_left-=1;match self.phase {
   COUNTDOWN=>if self.phase_left<=0{self.phase=FIGHT;self.event(1);},
   ROUND_END=>if self.phase_left<=0{self.round+=1;self.reset_fighters();self.phase=COUNTDOWN;self.phase_left=120;},
   FINISH=>{let win=self.winner as usize;if inputs[win]&FATAL!=0&&(self.fighters[0].x-self.fighters[1].x).abs()<2300 {self.phase=FATALITY;self.phase_left=180;self.fighters[win].action=7;self.event(5);}else if self.phase_left<=0{self.phase=RESULT;self.phase_left=0;}else {let f=&mut self.fighters[win];if inputs[win]&LEFT!=0{f.x=(f.x-70).max(600);}if inputs[win]&RIGHT!=0{f.x=(f.x+70).min(9400);}}},
   FATALITY=>if self.phase_left<=0{self.phase=RESULT;self.phase_left=0;},_=>{self.phase_left=0;}
  }return;}
  self.clock-=1;let before=self.fighters;let mut hits=[(0,0,0);2];
  for i in 0..2 {let bits=inputs[i]&511;let target=1-i;let f=&mut self.fighters[i];
   f.facing=if before[target].x>=f.x{1}else{-1};f.age+=1;f.cooldown=(f.cooldown-1).max(0);f.special_cd=(f.special_cd-1).max(0);f.combo_window=(f.combo_window-1).max(0);if f.combo_window==0{f.combo=0;}
   if f.y>0||f.vy>0 {f.y+=f.vy;f.vy-=24;if f.y<0{f.y=0;f.vy=0;}}
   if f.frozen>0{f.frozen-=1;f.action=6;f.last=bits;continue;}if f.stun>0{f.stun-=1;f.last=bits;continue;}
   f.crouch=if bits&DOWN!=0&&f.y==0{1}else{0};f.block=if bits&BLOCK!=0&&f.y==0{1}else{0};
   if f.cooldown==0 {if f.block==0 {let mut direction=0;if bits&LEFT!=0{direction-=1;}if bits&RIGHT!=0{direction+=1;}if f.crouch==0{f.x=(f.x+direction*if f.y>0{62}else{76}).clamp(600,9400);}if bits&UP!=0&&f.last&UP==0&&f.y==0{f.vy=360;f.crouch=0;}}
    let mut attack=0;if f.block==0{if bits&SPECIAL!=0&&f.special_cd==0{attack=if f.character==0{4}else{5};}else if bits&KICK!=0{attack=2;}else if bits&PUNCH!=0{attack=if f.crouch!=0{3}else{1};}}
    if attack>0{f.action=attack;f.age=0;f.cooldown=match attack{1=>18,2=>27,3=>34,_=>42};if attack>=4{f.special_cd=135;}}
    else if f.age>f.cooldown{f.action=0;}
   }
   if (1..=3).contains(&f.action)&&f.age==match f.action{1=>5,2=>9,_=>10}{let range=if f.action==2{1750}else{1250};let airborne=(f.y-before[target].y).abs()<1300;let target_crouched=before[target].crouch!=0;
    if (before[target].x-f.x).abs()<range&&airborne&&!(f.action==1&&target_crouched&&f.y==0){let damage=match f.action{1=>75,2=>115,_=>155}+(f.combo.min(3)*12);hits[target]=(damage,if f.action==3{30}else{15},f.facing*180);f.combo+=1;f.combo_window=70;}
   }
   if (f.action==4||f.action==5)&&f.age==13{if let Some(p)=self.projectiles.iter_mut().find(|p|p.active==0){*p=Projectile{active:1,owner:i as i32,x:f.x+f.facing*650,y:f.y+800,velocity:f.facing*175,kind:f.character,life:70};}}
   f.last=bits;
  }
  // Both attacks are evaluated before applying damage; frame order never advantages player zero.
  for i in 0..2{let (damage,stun,push)=hits[i];if damage>0{let blocked=self.fighters[i].block!=0;self.fighters[i].hp-=if blocked{damage/9}else{damage};self.fighters[i].stun=if blocked{4}else{stun};self.fighters[i].action=6;self.fighters[i].age=0;self.fighters[i].x=(self.fighters[i].x+push).clamp(600,9400);self.event(if blocked{3}else{2});}}
  for index in 0..4 {let mut p=self.projectiles[index];if p.active==0{continue;}p.x+=p.velocity;p.life-=1;let target=1-p.owner as usize;let f=&mut self.fighters[target];
   if (f.x-p.x).abs()<600&&(f.y+700-p.y).abs()<1000{if f.block!=0{f.hp-=12;f.stun=5;}else if p.kind==1{f.hp-=55;f.frozen=110;f.action=6;}else{f.hp-=100;f.stun=42;let owner=self.fighters[p.owner as usize];self.fighters[target].x=(owner.x+owner.facing*1250).clamp(600,9400);}p.active=0;self.event(2);}
   if p.life<=0||p.x<0||p.x>10000{p.active=0;}self.projectiles[index]=p;
  }
  for f in &mut self.fighters{f.hp=f.hp.max(0);}
  if self.fighters[0].hp==0||self.fighters[1].hp==0 {let win=if self.fighters[0].hp==self.fighters[1].hp{-1}else if self.fighters[0].hp>self.fighters[1].hp{0}else{1};self.end_round(win);}
  else if self.clock<=0 {let win=if self.fighters[0].hp==self.fighters[1].hp{-1}else if self.fighters[0].hp>self.fighters[1].hp{0}else{1};self.end_round(win);}
 }
 pub fn bot_input(&self,player:usize)->i32 {let f=self.fighters[player];let other=self.fighters[1-player];let distance=(f.x-other.x).abs();let towards=if other.x>f.x{RIGHT}else{LEFT};let away=if towards==RIGHT{LEFT}else{RIGHT};let t=self.frame+player as i32*17;
  if self.phase==FINISH{return if distance>1600{towards}else{FATAL}}if self.phase!=FIGHT{return 0;}if t%170<18&&other.action>0&&distance<2200{return BLOCK;}if t%181<12{return SPECIAL;}if t%139<3{return UP|towards;}if distance>1450{towards}else if distance<650{away|KICK}else if t%50<22{PUNCH}else{KICK|if t%103<35{DOWN}else{0}}
 }
}

#[cfg(test)]mod tests {use super::*;
 #[test]fn deterministic_and_rollback(){let mut a=State::new(11);let mut b=a.clone();let mut saved=None;for frame in 0..15000{if frame==300{saved=Some(a.encode());}let input=[a.bot_input(0),a.bot_input(1)];a.step(input);b.step(input);assert_eq!(a.hash(),b.hash());assert_eq!(State::decode(&a.encode()).unwrap(),a);}let mut restored=State::decode(&saved.unwrap()).unwrap();let mut original=State::new(11);for _ in 0..300{let i=[original.bot_input(0),original.bot_input(1)];original.step(i);}assert_eq!(restored,original);for _ in 0..1200{let i=[original.bot_input(0),original.bot_input(1)];original.step(i);restored.step(i);assert_eq!(original,restored);}}
 #[test]fn combat_block_and_rounds(){let mut s=State::new(0);s.phase=FIGHT;s.fighters[0].x=4000;s.fighters[1].x=5000;for _ in 0..8{s.step([PUNCH,BLOCK]);}assert!(s.fighters[1].hp<1000&&s.fighters[1].hp>980);s.fighters[1].hp=1;s.fighters[0].cooldown=0;for _ in 0..10{s.step([KICK,0]);}assert_eq!(s.phase,ROUND_END);assert_eq!(s.rounds[0],1);s.phase=FIGHT;s.fighters[1].hp=1;s.fighters[0].cooldown=0;for _ in 0..12{s.step([KICK,0]);}assert_eq!(s.phase,FINISH);s.step([FATAL,0]);assert_eq!(s.phase,FATALITY);for _ in 0..180{s.step([0,0]);}assert_eq!(s.phase,RESULT);}
 #[test]fn all_values_remain_bounded(){let mut s=State::new(0);for frame in 0..200000{let a=((frame*1664525u64+1013904223)>>13) as i32&511;let b=((frame*22695477u64+1)>>15) as i32&511;s.step([a,b]);for f in s.fighters{assert!((600..=9400).contains(&f.x));assert!((0..=1000).contains(&f.hp));}if s.phase==RESULT{s=State::new(frame);}}}
}
