"""Root-only deployment probe: synthetic device, no Telegram account or messages.

Run on the relay VPS. The existing signing key stays in memory on that VPS.
Only this temporary device's ACL is changed; the global policy is untouched.
"""
import base64
import hashlib
import http.client
import json
from pathlib import Path
import secrets
import socket
import ssl
import struct
import time
import urllib.request
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec,padding

HOST='vpn.codebyfox.ru'
PORT=9446
b64=lambda raw:base64.urlsafe_b64encode(raw).decode().rstrip('=')
unb64=lambda value:base64.urlsafe_b64decode(value+'='*(-len(value)%4))
digest=lambda raw:hashlib.sha256(raw).hexdigest()

def main():
    signer=serialization.load_pem_private_key(Path('/var/lib/ghostgram-license/lease-signing.pem').read_bytes(),None)
    key=ec.generate_private_key(ec.SECP256R1())
    public=b64(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo));device=digest(unb64(public))
    def signed(raw):return b64(key.sign(raw.encode(),ec.ECDSA(hashes.SHA256())))
    def post(path,data):
        request=urllib.request.Request(f'https://{HOST}:{PORT}'+path,json.dumps(data).encode(),{'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=15) as response:return json.load(response)
    def grant():
        challenge=post('/v1/challenge',{'public_key':public});now=int(challenge['server_time'])
        raw=json.dumps({'v':1,'app':'org.ghostgram.messenger','device':device,'license_id':'f'*32,'issued_at':now,'until':now+900},separators=(',',':')).encode()
        lease={'payload':b64(raw),'signature':b64(signer.sign(raw,padding.PKCS1v15(),hashes.SHA256()))}
        envelope=post('/v1/access',{'nonce':challenge['nonce'],'public_key':public,'proof':signed('astrarelay-policy-v1\n'+challenge['nonce']+'\n'+digest(raw)),'lease':lease})
        payload=unb64(envelope['payload']);signer.public_key().verify(unb64(envelope['signature']),payload,padding.PKCS1v15(),hashes.SHA256())
        value=json.loads(payload);assert value['device']==device and value['nonce']==challenge['nonce']
        return value
    class Local(http.client.HTTPConnection):
        def connect(self):
            self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(5);self.sock.connect('/run/astra-relay/admin.sock')
    def acl(denied):
        connection=Local('localhost');connection.request('POST','/policy',json.dumps({'device':device,'denied':denied}),{'Content-Type':'application/json'})
        response=connection.getresponse();assert response.status==200;response.read();connection.close()
    access=grant();assert access['allowed']
    address='149.154.167.50';port=443;token=access['token'];nonce=secrets.token_hex(32);stamp=str(int(time.time()));wskey=base64.b64encode(secrets.token_bytes(16)).decode()
    proof=signed(f'astrarelay-connect-v1\n{nonce}\n{stamp}\n{address}\n{port}\n{digest(token.encode())}')
    transport=ssl.create_default_context().wrap_socket(socket.create_connection((HOST,PORT),timeout=15),server_hostname=HOST)
    try:
        from cryptography import x509
        cert=x509.load_der_x509_certificate(transport.getpeercert(binary_form=True))
        pin=digest(cert.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
        assert pin in access['endpoint']['pins']
        transport.sendall((f'GET /v1/tunnel?ip={address}&port={port} HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: astrarelay.v1\r\nSec-WebSocket-Key: {wskey}\r\nAuthorization: Bearer {token}\r\nX-Astra-Nonce: {nonce}\r\nX-Astra-Time: {stamp}\r\nX-Astra-Proof: {proof}\r\n\r\n').encode())
        reader=transport.makefile('rb');status=reader.readline();assert b' 101 ' in status,status
        headers={}
        while True:
            line=reader.readline()
            if line==b'\r\n':break
            name,value=line.decode().split(':',1);headers[name.lower()]=value.strip()
        assert headers['sec-websocket-accept']==base64.b64encode(hashlib.sha1((wskey+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        def send(opcode,data):
            mask=secrets.token_bytes(4);n=len(data)
            frame=bytes([0x80|opcode,0x80|n]) if n<126 else bytes([0x80|opcode,0xfe])+struct.pack('!H',n)
            transport.sendall(frame+mask+bytes(value^mask[i%4] for i,value in enumerate(data)))
        def read():
            head=reader.read(2);assert len(head)==2 and not head[1]&128
            n=head[1]&127
            if n==126:n=struct.unpack('!H',reader.read(2))[0]
            if n==127:n=struct.unpack('!Q',reader.read(8))[0]
            assert n<=65536
            raw=reader.read(n);assert len(raw)==n
            return head[0]&15,raw
        # Unauthenticated Telegram req_pq_multi (constructors from MTProtoScheme.h).
        telegram_nonce=secrets.token_bytes(16);body=struct.pack('<I',0xbe7e8ef1)+telegram_nonce
        packet=b'\x00'*8+struct.pack('<Q',int(time.time()*2**32)&~3)+struct.pack('<I',len(body))+body
        send(2,b'\xef'+bytes([len(packet)//4])+packet)
        received=bytearray()
        while len(received)<1 or len(received)<1+received[0]*4:
            opcode,data=read()
            if opcode==9:send(10,data);continue
            assert opcode==2;received.extend(data)
        raw=received[1:];assert raw[:8]==b'\x00'*8 and struct.unpack_from('<I',raw,20)[0]==0x05162463 and raw[24:40]==telegram_nonce
        acl(True)
        opcode,data=read();assert opcode==8 and struct.unpack('!H',data[:2])[0]==4003
        assert grant()['allowed'] is False
        print('PASS: public TLS, signed access, SPKI pin, WSS proof, real Telegram resPQ and immediate device denial')
    finally:
        transport.close();acl(False)
    assert grant()['allowed'] is True
    print('PASS: synthetic device access restored; global relay policy unchanged')

if __name__=='__main__':main()
