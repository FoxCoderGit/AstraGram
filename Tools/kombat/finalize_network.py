from pathlib import Path
root=Path(__file__).resolve().parents[2]
path=root/'Tools/kombat/server/src/main.rs'
text=path.read_text(encoding='utf-8')
text=text.replace('let(uid,license)=auth::verify','let(uid,license,expires)=auth::verify')
text=text.replace('if activity.elapsed()>Duration::from_secs(75){break;}', 'if auth::now()>=expires||activity.elapsed()>Duration::from_secs(75){break;}')
path.write_text(text,encoding='utf-8')
path=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatSession.java'
text=path.read_text(encoding='utf-8')
text=text.replace('index=after-1;int[] bits=', 'index=after-1;if(actual.containsKey(index)||index<frame-128)continue;int[] bits=')
path.write_text(text,encoding='utf-8')
