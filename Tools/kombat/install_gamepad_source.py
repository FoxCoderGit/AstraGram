from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=root/'TMessagesProj/src/main/java/org/telegram/ui/GhostKombatActivity.java';t=p.read_text(encoding='utf-8')
start=t.index('    private final class Controls extends View {')
t=t[:start]+(root/'Tools/kombat/gamepad_controls.java.txt').read_text(encoding='utf-8')+'}\n'
t=t.replace('private volatile int input,secondInput;', 'private volatile int input,secondInput;')
anchor='    private AudioTrack audio;'
t=t.replace(anchor,'    private final java.util.concurrent.atomic.AtomicInteger pendingPresses=new java.util.concurrent.atomic.AtomicInteger();\n'+anchor)
t=t.replace('game.tick(input);frames=game.frame;', 'game.tick(input|pendingPresses.getAndSet(0));frames=game.frame;')
t=t.replace('int a=input,b=secondInput;', 'int a=input,b=secondInput;int pulse=pendingPresses.getAndSet(0);if(controls.practicePlayer==1)b|=pulse;else a|=pulse;')
t=t.replace('int program,texture,positionAttribute,uvAttribute,scaleUniform;', 'int program,texture,positionAttribute,uvAttribute,scaleUniform,textureWidth,textureHeight;')
t=t.replace('texture=textures[0];', 'texture=textures[0];textureWidth=textureHeight=0;')
t=t.replace('GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);', 'if(textureWidth!=width||textureHeight!=height){GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);textureWidth=width;textureHeight=height;}else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);')
p.write_text(t,encoding='utf-8')
p=root/'TMessagesProj_Ghost/build.gradle';t=p.read_text(encoding='utf-8').replace('-ghost.71-kombat','-ghost.72-kombat');p.write_text(t,encoding='utf-8')
