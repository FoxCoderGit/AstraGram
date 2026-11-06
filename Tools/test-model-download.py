"""Run the actual Java downloader against a local HTTP fixture (no model download)."""
from pathlib import Path
import hashlib, http.server, subprocess, tempfile, threading

root=Path(__file__).resolve().parents[1]
data=bytes(range(256))*1024
sha=hashlib.sha256(data).hexdigest()
requests=[]
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        requests.append((self.path,self.headers.get('Range')))
        if self.path=='/redirect':
            self.send_response(302);self.send_header('Location','/range');self.end_headers();return
        if self.path=='/error':
            self.send_response(503);self.end_headers();return
        offset=int(self.headers.get('Range','bytes=0-')[6:-1])
        if self.path=='/ignore':offset=0
        self.send_response(206 if offset else 200)
        if offset:self.send_header('Content-Range',f'bytes {offset}-{len(data)-1}/{len(data)}')
        payload=data[offset:] if self.path!='/bad' else b'x'*len(data)
        self.send_header('Content-Length',str(len(payload)));self.end_headers()
        try:self.wfile.write(payload)
        except (ConnectionResetError,BrokenPipeError):pass

server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='ghost-model-download-') as folder:
        work=Path(folder);source=work/'DownloadTest.java'
        source.write_text('''import java.io.*;import java.net.*;import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.GhostModelDownload;
public class DownloadTest {
 public static void main(String[] a)throws Exception{
  AtomicBoolean canceled=new AtomicBoolean(a[5].equals("pre"));
  GhostModelDownload.download(new URL(a[0]),Proxy.NO_PROXY,new File(a[1]),new File(a[2]),Long.parseLong(a[3]),a[4],canceled::get,n->{if(a[5].equals("mid")&&n>0)canceled.set(true);});
 }
}''',encoding='utf-8')
        bridge=work/'org/telegram/messenger/GhostLicenseBridge.java';bridge.parent.mkdir(parents=True)
        bridge.write_text('package org.telegram.messenger; public final class GhostLicenseBridge {public static void configureLicenseConnection(javax.net.ssl.HttpsURLConnection c) throws java.io.IOException {throw new java.io.IOException("No pinned test connection");}}',encoding='utf-8')
        subprocess.run(['javac','-d',str(work),str(root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostModelDownload.java'),str(source),str(bridge)],check=True)
        stage,target=work/'model.part',work/'model.bin'
        def run(path,mode='ok',success=True):
            result=subprocess.run(['java','-Xmx16m','-cp',str(work),'DownloadTest',f'http://127.0.0.1:{server.server_port}/{path}',str(stage),str(target),str(len(data)),sha,mode],capture_output=True,text=True)
            assert (result.returncode==0)==success,result.stderr
        for path,partial in [('range',0),('range',17000),('ignore',13000),('redirect',12345)]:
            if target.exists():target.unlink()
            stage.write_bytes(data[:partial]);run(path)
            assert target.read_bytes()==data and not stage.exists()
        target.write_bytes(b'previous installed model')
        stage.write_bytes(b'');run('bad',success=False)
        assert target.read_bytes()==b'previous installed model' and not stage.exists()
        count=len(requests);run('range','pre',False)
        assert len(requests)==count,'Canceled operation must not open a connection'
        run('range','mid',False)
        assert target.read_bytes()==b'previous installed model'
        stage.write_bytes(b'');run('error',success=False)
        assert target.read_bytes()==b'previous installed model'
        stage.write_bytes(data);count=len(requests);target.unlink();run('range')
        assert target.read_bytes()==data and len(requests)==count,'Complete partial file should verify without another network request'
finally:
    server.shutdown();server.server_close()
print('PASS: full download, resume, ignored Range, redirect, hash rejection, cancellation before/during transfer, HTTP failure, existing-model preservation, completed partial activation; bounded heap, local server only.')
