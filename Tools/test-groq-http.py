"""Exercise the actual Groq HTTP transport using synthetic audio and a local server only."""
from pathlib import Path
from email.parser import BytesParser
from email.policy import default
import http.server, json, subprocess, tempfile, threading

root=Path(__file__).resolve().parents[1]
audio=b'OggS'+bytes(range(256))*1024
seen=[]
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):self.handle_request()
    def do_POST(self):self.handle_request()
    def handle_request(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        seen.append((self.command,self.path))
        assert self.headers.get('Authorization')=='Bearer gsk_fake_test_key_not_a_real_secret'
        if self.command=='POST':
            message=BytesParser(policy=default).parsebytes(('Content-Type: '+self.headers['Content-Type']+'\r\n\r\n').encode()+body)
            parts={part.get_param('name',header='content-disposition'):part for part in message.iter_parts()}
            assert parts['file'].get_payload(decode=True)==audio
            assert parts['file'].get_filename()=='audio.ogg'
            assert parts['model'].get_payload(decode=True)==b'whisper-large-v3-turbo'
            assert parts['language'].get_payload(decode=True)==b'ru'
            assert parts['response_format'].get_payload(decode=True)==b'json'
        status=429 if self.path=='/limited' else 401 if self.path=='/invalid' else 302 if self.path=='/redirect' else 200
        self.send_response(status)
        self.send_header('x-ratelimit-limit-requests','2000')
        self.send_header('X-RateLimit-Remaining-Requests','1934' if status!=429 else '0')
        self.send_header('x-ratelimit-reset-requests','1h2m3.5s')
        if status==429:self.send_header('Retry-After','17')
        if status==302:self.send_header('Location','/must-not-receive-key')
        data=json.dumps({'text':'Привет, проверка.'} if self.command=='POST' else {'id':'whisper-large-v3-turbo'},ensure_ascii=False).encode()
        if status!=200:data=b'error body must not be exposed; may contain sensitive input'
        if self.path=='/oversized':data=b'x'*(1024*1024+1)
        self.send_header('Content-Length',str(len(data)));self.end_headers()
        try:self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass

server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
try:
    with tempfile.TemporaryDirectory(prefix='ghost-groq-http-') as folder:
        work=Path(folder);(work/'audio.ogg').write_bytes(audio)
        (work/'GroqTest.java').write_text('''import java.net.*;import java.io.*;import org.telegram.messenger.GhostGroqHttp;
public class GroqTest {
 static void check(boolean v)throws Exception{if(!v)throw new Exception("Assertion failed");}
 public static void main(String[] a)throws Exception{
  String root=a[0],key="gsk_fake_test_key_not_a_real_secret",model="whisper-large-v3-turbo";
  File audio=new File(a[1]);
  GhostGroqHttp.Response r=GhostGroqHttp.request(new URL(root+"/model"),Proxy.NO_PROXY,key,model,null,null);
  check(r.status==200&&r.body.contains(model));
  r=GhostGroqHttp.request(new URL(root+"/audio"),Proxy.NO_PROXY,key,model,audio,"audio.ogg");
  check(r.status==200&&r.body.contains("Привет"));check(r.headers.get("x-ratelimit-remaining-requests").equals("1934"));
  for(String path:new String[]{"limited","invalid","redirect"}){
   r=GhostGroqHttp.request(new URL(root+"/"+path),Proxy.NO_PROXY,key,model,audio,"audio.ogg");check(r.body.isEmpty());
   if(path.equals("limited"))check(r.status==429&&r.headers.get("retry-after").equals("17"));
   if(path.equals("redirect"))check(r.status==302);
  }
  boolean failed=false;try{GhostGroqHttp.request(new URL(root+"/oversized"),Proxy.NO_PROXY,key,model,null,null);}catch(IOException expected){failed=true;}check(failed);
  failed=false;try{GhostGroqHttp.request(new URL(root+"/never"),Proxy.NO_PROXY,key+"\\r\\nBAD",model,audio,"audio.ogg");}catch(IOException expected){failed=true;}check(failed);
  try(RandomAccessFile file=new RandomAccessFile(a[2],"rw")){file.setLength(25_000_001);}
  failed=false;try{GhostGroqHttp.request(new URL(root+"/never"),Proxy.NO_PROXY,key,model,new File(a[2]),"audio.ogg");}catch(IOException expected){failed=true;}check(failed);
 }
}''',encoding='utf-8')
        subprocess.run(['javac','-encoding','UTF-8','-d',str(work),str(root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostGroqHttp.java'),str(work/'GroqTest.java')],check=True)
        subprocess.run(['java','-Xmx16m','-cp',str(work),'GroqTest',f'http://127.0.0.1:{server.server_port}',str(work/'audio.ogg'),str(work/'large.ogg')],check=True)
        assert len(seen)==6 and not any(path in ('/never','/must-not-receive-key') for _,path in seen)
finally:
    server.shutdown();server.server_close()
print('PASS: exact streaming multipart audio/model/Russian fields, JSON/UTF-8, quota and Retry-After headers, 401/429, no redirect or duplicate requests, redacted errors, bounded responses/uploads, header-injection rejection. Local fixture only; no Groq key or private audio used.')
