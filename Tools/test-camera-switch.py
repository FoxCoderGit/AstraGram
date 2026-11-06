"""Exercise the production switch method without opening cameras or recording."""
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java').read_text(encoding='utf-8')
a=source.index('    private void switchCamera() {')
b=source.index('    // Old Camera1 API',a)
method=source[a:b]
code='''public class CameraSwitchTest {
static class Size {Size(int w,int h){}}
static class Camera2Session {
 static int created;Runnable after;boolean destroyed,async,video;
 static Camera2Session create(boolean front,int w,int h){created++;return new Camera2Session();}
 void destroy(boolean async,Runnable after){destroyed=true;this.async=async;this.after=after;}
 void setRecordingVideo(boolean v){video=v;}int getPreviewWidth(){return 512;}int getPreviewHeight(){return 512;}
}
static class CameraGLThread {int flips,reinit,session;void flipSurfaces(){flips++;}void setCurrentSession(Camera2Session s){session++;}void reinitForNewCamera(){reinit++;}}
static class CameraSession {void destroy(){}}
static class CameraController {static CameraController getInstance(){return new CameraController();}void close(CameraSession s,Object a,Object b){}}
static class MessagesController {int roundVideoSize=512;static MessagesController getInstance(int a){return new MessagesController();}}
static class Overlay {void setImageBitmap(Object b){}void setAlpha(float a){}}
boolean useCamera2=true,bothCameras,isFrontface=true,cancelled,cameraReady=true,needDrawFlickerStub=true;int currentAccount,snapshots,initializations;
CameraGLThread cameraThread=new CameraGLThread();Camera2Session camera2SessionCurrent=new Camera2Session();Camera2Session[] camera2Sessions={camera2SessionCurrent,new Camera2Session()};CameraSession cameraSession;Size[] previewSize=new Size[2];Object textureView=new Object(),lastBitmap;Overlay textureOverlayView=new Overlay();
void saveLastCameraBitmap(){snapshots++;}void updateFlash(){}void initCamera(){initializations++;}
''' + method + '''
static void check(boolean b){if(!b)throw new AssertionError();}
public static void main(String[] args){
 CameraSwitchTest t=new CameraSwitchTest();t.bothCameras=true;Camera2Session old=t.camera2SessionCurrent;Camera2Session target=t.camera2Sessions[1];
 t.switchCamera();check(!t.isFrontface&&t.camera2SessionCurrent==target&&t.cameraThread.flips==1&&!old.destroyed&&t.snapshots==0&&Camera2Session.created==0);
 t=new CameraSwitchTest();old=t.camera2SessionCurrent;t.switchCamera();check(old.destroyed&&old.async&&t.camera2SessionCurrent==null&&!t.cameraReady&&Camera2Session.created==0&&t.snapshots==0);
 old.after.run();check(Camera2Session.created==1&&t.camera2SessionCurrent.video&&t.cameraThread.reinit==1&&t.cameraThread.session==1);
 t=new CameraSwitchTest();old=t.camera2SessionCurrent;int before=Camera2Session.created;t.switchCamera();t.cancelled=true;old.after.run();check(Camera2Session.created==before);
 t=new CameraSwitchTest();old=t.camera2SessionCurrent;t.switchCamera();t.cameraThread=new CameraGLThread();old.after.run();check(Camera2Session.created==before);
 System.out.println("Camera switch: warm stream flip, async reopen, no snapshot, cancellation and stale callback passed (host doubles).");
}}
'''
with tempfile.TemporaryDirectory(prefix='camera-switch-') as temp:
    test=Path(temp)/'CameraSwitchTest.java';test.write_text(code,encoding='utf-8')
    subprocess.run(['javac','-d',temp,str(test)],check=True)
    subprocess.run(['java','-cp',temp,'CameraSwitchTest'],check=True)
