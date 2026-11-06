from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=root/'Tools/kombat/server/src/main.rs';t=p.read_text(encoding='utf-8')
t=t.replace('let op=value["op"].as_str().unwrap_or("");let mut h=hub.lock().await;', 'let op=if value["op"]=="join"{"accept"}else{value["op"].as_str().unwrap_or("")};let mut h=hub.lock().await;')
anchor=' if op=="invite" {'
create=''' if op=="create" {
  if h.room_of(uid).is_some(){h.error(uid,"busy");return;}
  if h.last_invite.get(&uid).is_some_and(|t|t.elapsed()<Duration::from_secs(10)){h.error(uid,"rate_limited");return;}
  h.invites.retain(|_,i|i.expires>Instant::now());if h.invites.len()>=256{h.error(uid,"busy");return;}
  h.invites.retain(|_,i|i.from!=uid);let id=loop{let id=auth::random()[..10].to_string();if !h.invites.contains_key(&id){break id;}};
  h.invites.insert(id.clone(),Invite{from:uid,to:0,expires:Instant::now()+Duration::from_secs(300)});h.last_invite.insert(uid,Instant::now());h.send(uid,json!({"op":"code","code":id.to_uppercase(),"expires":300}));return;
 }
'''
t=t.replace(anchor,create+anchor,1)
t=t.replace('let Some(invite)=h.invites.get(id).cloned()', 'let Some(mut invite)=h.invites.get(id).cloned()')
anchor='if(op=="cancel"&&uid!=invite.from)'
t=t.replace(anchor,'if value["op"]=="join"{if invite.to!=0||invite.from==uid{h.error(uid,"invalid_code");return;}invite.to=uid;}'+anchor)
old='let(uid,license,expires)=auth::verify(&proof,&nonce,&public)?;let root=hub.lock().await.root.clone();if !auth::approved(&root,uid,&license)?{let _=socket.send(Message::Text(json!({"op":"needs_binding","license_id":license,"user_id":uid}).to_string().into())).await;return Ok(());}'
new='let(claimed_uid,license,expires)=auth::verify(&proof,&nonce,&public)?;let uid=auth::game_identity(&license,claimed_uid);'
assert old in t;t=t.replace(old,new)
p.write_text(t,encoding='utf-8')
p=root/'Tools/kombat/server/src/auth.rs';t=p.read_text(encoding='utf-8')
t+='\npub fn game_identity(license:&str,account:u64)->u64{let digest=Sha256::digest(format!("ghostgram-kombat-code-v1:{license}:{account}").as_bytes());u64::from_le_bytes(digest[..8].try_into().unwrap())&0x7fff_ffff_ffff_ffff}\n'
p.write_text(t,encoding='utf-8')
p=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatOnline.java';t=p.read_text(encoding='utf-8')
start=t.index('    public static void invite(ChatActivity chat)');end=t.index('\n    private static void message',start)
t=t[:start]+'''    public static void invite(ChatActivity chat){Client c=get(chat.getCurrentAccount());c.connect();new AlertDialog.Builder(chat.getParentActivity(),chat.getResourceProvider()).setTitle("GhostGram Kombat").setItems(new CharSequence[]{"Создать код боя","Ввести код боя"},(d,which)->{if(which==0)c.codeOperation("create",null);else{android.widget.EditText input=new android.widget.EditText(chat.getParentActivity());input.setSingleLine(true);input.setHint("Код приглашения");input.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(12),AndroidUtilities.dp(24),AndroidUtilities.dp(12));chat.showDialog(new AlertDialog.Builder(chat.getParentActivity(),chat.getResourceProvider()).setTitle("Войти в бой").setView(input).setPositiveButton("Войти",(dialog,w)->c.codeOperation("join",input.getText().toString())).setNegativeButton("Отмена",null).create());}}).show();}
'''+t[end:]
t=t.replace('public volatile boolean authenticated,binding;', 'public volatile long gameId;public volatile boolean authenticated,binding;')
t=t.replace('private long invite;', 'private long invite;private volatile JSONObject pendingCode;')
t=t.replace('authenticated=true;binding=false;closeDatagram();', 'authenticated=true;binding=false;gameId=event.optLong("user_id");closeDatagram();')
t=t.replace('sendInvite();return;}\n            if(op.equals("needs_binding"))', 'sendInvite();JSONObject queued=pendingCode;pendingCode=null;if(queued!=null)send(queued);return;}\n            if(op.equals("needs_binding"))')
anchor='        public void requestInvite(long target)'
t=t.replace(anchor,'''        public void codeOperation(String op,String code){try{JSONObject request=new JSONObject().put("op",op);if(code!=null)request.put("id",code.replaceAll("[^A-Fa-f0-9]","").toLowerCase(java.util.Locale.ROOT));if(authenticated)send(request);else{pendingCode=request;connect();}}catch(Exception ignored){}}
'''+anchor)
anchor='            if(op.equals("room"))'
t=t.replace(anchor,'''            if(op.equals("code")){String code=event.optString("code");AndroidUtilities.runOnUIThread(()->{BaseFragment f=LaunchActivity.getLastFragment();if(f!=null&&f.getParentActivity()!=null)f.showDialog(new AlertDialog.Builder(f.getParentActivity(),f.getResourceProvider()).setTitle("Код боя: "+code).setMessage("Передай код сопернику. Он действует 5 минут и используется один раз. Оба участника должны оставаться в GhostGram.").setPositiveButton("Копировать",(d,w)->AndroidUtilities.addToClipboard(code)).setNegativeButton("Закрыть",null).create());});return;}
'''+anchor)
p.write_text(t,encoding='utf-8')
p=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatSession.java';t=p.read_text(encoding='utf-8').replace('winner==UserConfig.getInstance(client.account).getClientUserId()', 'winner==client.gameId');p.write_text(t,encoding='utf-8')
print('Enabled single-use invitation codes; license-derived game identities replace Telegram ID routing.')
