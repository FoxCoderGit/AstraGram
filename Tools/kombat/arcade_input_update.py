from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=root/'TMessagesProj/src/main/java/org/telegram/ui/GhostKombatActivity.java';t=p.read_text(encoding='utf-8')
t=t.replace('private final java.util.concurrent.atomic.AtomicInteger pendingPresses=new java.util.concurrent.atomic.AtomicInteger();', 'private final GhostKombatInputBuffer arcadeInput=new GhostKombatInputBuffer();')
t=t.replace('game.tick(input|pendingPresses.getAndSet(0))', 'game.tick(arcadeInput.poll(input))')
t=t.replace('int a=input,b=secondInput;int pulse=pendingPresses.getAndSet(0);if(controls.practicePlayer==1)b|=pulse;else a|=pulse;', 'int sampled=arcadeInput.poll(input|secondInput);int a=controls.practicePlayer==0?sampled:0,b=controls.practicePlayer==1?sampled:0;')
t=t.replace('pendingPresses.set(0);', 'arcadeInput.clear();')
t=t.replace('int before=held.get(pointer,0);held.put(pointer,mask);if(mask!=0&&mask!=before)pendingPresses.getAndUpdate(v->v|mask);', 'held.put(pointer,mask);')
t=t.replace('if(length<.17f)', 'if(length<.10f)').replace('if(length>.17f)', 'if(length>.10f)')
t=t.replace('input=bits;secondInput=0;}invalidate();return true;', 'input=bits;secondInput=0;}arcadeInput.offer(bits);invalidate();return true;')
t=t.replace('session=game;resultsShown=false;paused=false;input=secondInput=0;', 'session=game;resultsShown=false;paused=false;input=secondInput=0;arcadeInput.clear();')
# Eight discrete gate marks make the touch zones clear instead of suggesting analog movement.
t=t.replace('for(int i=0;i<4;i++){double angle=i*Math.PI/2;', 'for(int i=0;i<8;i++){double angle=i*Math.PI/4;')
t=t.replace('canvas.drawCircle(px,py,2*d,paint);', 'android.graphics.Path arrow=new android.graphics.Path();float ax=(float)Math.cos(angle),ay=(float)Math.sin(angle),size=5*d;arrow.moveTo(px+ax*size,py+ay*size);arrow.lineTo(px-ax*size-ay*size,py-ay*size+ax*size);arrow.lineTo(px-ax*size+ay*size,py-ay*size-ax*size);arrow.close();canvas.drawPath(arrow,paint);')
p.write_text(t,encoding='utf-8')
p=root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostKombatSession.java';t=p.read_text(encoding='utf-8').replace('private int lastScheduled=-1;', 'private int lastScheduled=-1,effectiveLead;').replace('int lead=GhostKombatTiming.inputLead(client.roundTripMs);', 'int lead=effectiveLead=Math.max(effectiveLead,GhostKombatTiming.inputLead(client.roundTripMs));');p.write_text(t,encoding='utf-8')
p=root/'TMessagesProj/src/debug/java/org/telegram/messenger/GhostKombatDiagnostics.java';t=p.read_text(encoding='utf-8')
t=t.replace('if((((java.util.concurrent.atomic.AtomicInteger)field(game[0],"pendingPresses")).get()&masks[i])==0)throw new AssertionError("Lost short press "+i);', 'GhostKombatInputBuffer buffer=(GhostKombatInputBuffer)field(game[0],"arcadeInput");if(buffer.poll(0)!=masks[i]||buffer.poll(0)!=0)throw new AssertionError("Lost short press or release "+i);')
t=t.replace('short-press latch', 'ordered arcade press/release edges')
anchor='        for(long rtt:'
t=t.replace(anchor,'''        GhostKombatInputBuffer sequence=new GhostKombatInputBuffer();int[] combo={GhostKombatArcade.LEFT,0,GhostKombatArcade.LEFT,0,GhostKombatArcade.DOWN,GhostKombatArcade.DOWN|GhostKombatArcade.RIGHT,GhostKombatArcade.RIGHT,GhostKombatArcade.RIGHT|GhostKombatArcade.HP};for(int edge:combo)sequence.offer(edge);for(int edge:combo)if(sequence.poll(0)!=edge)throw new AssertionError("Lost arcade combo edge");
'''+anchor)
p.write_text(t,encoding='utf-8')
