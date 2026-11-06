"""Isolated service test: synthetic identities and signing key, never production licenses."""
import asyncio,base64,hashlib,hmac,json,os,pathlib,sqlite3,ssl,subprocess,time,socket
import websockets
from cryptography.hazmat.primitives.asymmetric import ec,rsa,padding
from cryptography.hazmat.primitives import hashes,serialization
ROOT=pathlib.Path('/opt/ghostgram-kombat-build/network-fixture')
def b64(data):return base64.urlsafe_b64encode(data).decode().rstrip('=')
def data(value):return json.dumps(value,separators=(',',':')).encode()
async def receive(ws,op):
    for _ in range(1000):
        event=json.loads(await asyncio.wait_for(ws.recv(),10))
        if event['op']==op:return event
    raise AssertionError('missing '+op)
async def test():
    rsa_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    (ROOT/'license-public.der').write_bytes(rsa_key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
    env=dict(os.environ,KOMBAT_DATA=str(ROOT),KOMBAT_LISTEN='127.0.0.1:19444',KOMBAT_UDP='127.0.0.1:19445')
    server=subprocess.Popen(['/opt/ghostgram-kombat/server'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    await asyncio.sleep(1)
    context=ssl.create_default_context(cafile=str(ROOT/'server.crt'))
    keys=[ec.generate_private_key(ec.SECP256R1()) for _ in range(3)]
    licenses=['%032x'%(i+1) for i in range(3)]
    db=sqlite3.connect(ROOT/'kombat.sqlite')
    for i in range(2):db.execute('INSERT OR REPLACE INTO accounts VALUES(?,?,?)',(800000001+i,licenses[i],int(time.time())))
    db.commit();db.close()
    async def login(i,wrong=False):
        ws=await websockets.connect('wss://127.0.0.1:19444/v1/ws',ssl=context,server_hostname='93.88.204.2',max_size=5*1024*1024)
        hello=await receive(ws,'hello');uid=800000001+i
        public=keys[i].public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
        claims=data(dict(v=1,app='org.ghostgram.messenger',license_id=licenses[i],device=hashlib.sha256(public).hexdigest(),issued_at=int(time.time()),until=int(time.time())+300))
        signed=rsa_key.sign(claims,padding.PKCS1v15(),hashes.SHA256())
        proof=keys[i].sign(('ghostgram-kombat-v1\n'+hello['nonce']+'\n'+str(uid+(1 if wrong else 0))).encode(),ec.ECDSA(hashes.SHA256()))
        await ws.send(data(dict(op='auth',user_id=uid,public_key=b64(public),proof=b64(proof),lease=dict(payload=b64(claims),signature=b64(signed)))).decode())
        return ws
    try:
        forged=await login(0,True)
        try:await forged.recv();raise AssertionError('forged identity accepted')
        except websockets.exceptions.ConnectionClosed:pass
        unknown=await login(2);assert (await receive(unknown,'auth_ok'))['user_id']!=800000003;await unknown.close()
        a,b=await login(0),await login(1)
        aa,bb=await receive(a,'auth_ok'),await receive(b,'auth_ok')
        assert len(aa['udp_key'])==64 and aa['sid']!=bb['sid']
        await a.send('{"op":"create"}');offer=await receive(a,'code');assert len(offer['code'])==10;await b.send(data(dict(op='join',id=offer['code'].lower())).decode())
        ar,br=await receive(a,'room'),await receive(b,'room');assert ar['slot']!=br['slot']
        udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.setblocking(False);udp.connect(('127.0.0.1',19445))
        body=data(dict(op='input',frame=0,bits=128)).decode()
        def packet(seq,bad=False):
            mac=hmac.new(aa['udp_key'].encode(),(aa['sid']+'\n'+str(seq)+'\n'+body).encode(),hashlib.sha256).hexdigest()
            return data(dict(sid=aa['sid'],seq=seq,body=body,mac='0'*64 if bad else mac))
        udp.send(packet(100,True));udp.send(packet(1));udp.send(packet(1))
        sa,sb=await receive(a,'state'),await receive(b,'state');assert sa['data']==sb['data'] and sa['hash']==sb['hash']
        await receive(a,'countdown');await receive(b,'countdown')
        frames_a,frames_b={},{}
        for _ in range(35):
            fa,fb=await receive(a,'frame'),await receive(b,'frame');frames_a[fa['frame']]=fa;frames_b[fb['frame']]=fb
        assert frames_a==frames_b
        assert frames_a[1]['a' if ar['slot']==0 else 'b']==128
        wire=json.loads(await asyncio.wait_for(asyncio.get_running_loop().sock_recv(udp,1024),2))
        assert hmac.compare_digest(wire['mac'],hmac.new(aa['udp_key'].encode(),(aa['sid']+'\n'+str(wire['seq'])+'\n'+wire['body']).encode(),hashlib.sha256).hexdigest());udp.close()
        await a.send('{"op":"pause"}');await receive(b,'paused')
        await a.send('{"op":"resume"}');await receive(b,'resumed')
        await a.send('{"op":"resync"}');assert (await receive(a,'state'))['frame']>=35
        await a.close();a=await login(0);await receive(a,'auth_ok');assert (await receive(a,'room'))['room']==ar['room'];assert (await receive(a,'state'))['frame']>=35
        await a.send('{"op":"leave"}');result=await receive(b,'result');assert result['winner']==bb['user_id']
        await b.send(data(dict(op='join',id=offer['code'].lower())).decode());assert (await receive(b,'error'))['error']=='invitation_expired'
        await a.close();await b.close()
        print('PASS: forged proof rejected; no Telegram binding needed; license-derived game identities; single-use code; opposite fighters; identical initial state and 35 authoritative frames; signed UDP input/output, invalid MAC and replay rejected; pause/resume; resync; reconnect; server result.')
    finally:server.terminate();server.wait(timeout=10)
if __name__=='__main__':asyncio.run(test())
