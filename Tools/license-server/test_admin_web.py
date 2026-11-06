"""Owner console security/actions against an isolated database; no real keys."""
import base64
import io
import json
from pathlib import Path
import secrets
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa, ec, padding
from cryptography.hazmat.primitives import serialization
import server
import admin_web as web


class Request:
    def __init__(self, path, method, data, signer, cookie='', csrf='', raw=None, extra=None):
        self.path, self.command, self.client_address = path, method, ('127.0.0.1', 1234)
        self.server = types.SimpleNamespace(signing_key=signer)
        self.headers = {'Host': 'localhost:9443', 'Origin': 'https://localhost:9443', 'Cookie': cookie, 'X-CSRF-Token': csrf}
        encoded = raw if raw is not None else json.dumps(data or {}).encode()
        self.headers['Content-Length'] = str(len(encoded))
        if extra: self.headers.update(extra)
        self.rfile, self.wfile = io.BytesIO(encoded), io.BytesIO()
        self.connection = types.SimpleNamespace(settimeout=lambda value: None)
        self.sent = {}
    def send_response(self, status): self.status = status
    def send_header(self, key, value): self.sent[key] = value
    def end_headers(self): pass


class OwnerConsole(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.signer = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); server.DATA = Path(self.temp.name); server.initialize()
        salt = secrets.token_hex(16)
        (server.DATA / 'owner.json').write_text(json.dumps({'salt': salt, 'password_hash': web.password_hash('test-owner-password-only', salt), 'apk_signer_sha256': 'a'*64}))
        web.LOGIN_ATTEMPTS.clear(); self.cookie = self.csrf = ''
    def tearDown(self): self.temp.cleanup()
    def call(self, path, data=None, method=None, **kwargs):
        h = Request(path, method or ('POST' if data is not None else 'GET'), data, self.signer, self.cookie, self.csrf, **kwargs)
        self.assertTrue(web.route(h, server))
        return h, json.loads(h.wfile.getvalue()) if h.sent.get('Content-Type', '').startswith('application/json') else h.wfile.getvalue()
    def login(self):
        h, result = self.call('/admin/api/login', {'password': 'test-owner-password-only'})
        self.cookie = h.sent['Set-Cookie'].split(';')[0]; self.csrf = result['csrf']
        self.assertIn('HttpOnly', h.sent['Set-Cookie']); self.assertIn('SameSite=Strict', h.sent['Set-Cookie']); self.assertIn('Secure', h.sent['Set-Cookie'])
    def rejected(self, code, operation):
        with self.assertRaises(server.Rejected) as caught: operation()
        self.assertEqual(code, caught.exception.code)
    def test_auth_csrf_origin_and_logout(self):
        self.rejected('login_required', lambda: self.call('/admin/api/state'))
        self.rejected('wrong_password', lambda: self.call('/admin/api/login', {'password': 'wrong'}))
        self.login(); self.call('/admin/api/state')
        self.rejected('bad_origin', lambda: self.call('/admin/api/policy', {'require_key': False}, extra={'Origin': 'https://attacker.example'}))
        self.rejected('csrf', lambda: self.call('/admin/api/policy', {'require_key': False}, extra={'X-CSRF-Token': 'wrong'}))
        self.call('/admin/api/logout', {}); self.rejected('login_required', lambda: self.call('/admin/api/state'))
    def test_key_lifecycle_and_transaction(self):
        self.login(); _, result = self.call('/admin/api/licenses', {'action':'create', 'count':2, 'label':'<test>', 'days':0})
        ids = [r['id'] for r in result['created']]
        with server.db() as c:
            rows = c.execute('SELECT * FROM licenses').fetchall()
            self.assertTrue(all(r['expires_at'] is None for r in rows))
            self.assertTrue(all(r['key_hash'] not in [x['key'] for x in result['created']] for r in rows))
        self.call('/admin/api/licenses', {'action':'revoke','ids':ids})
        self.call('/admin/api/licenses', {'action':'restore','ids':ids})
        self.call('/admin/api/licenses', {'action':'edit','ids':[ids[0]],'label':'Updated','days':7})
        self.call('/admin/api/licenses', {'action':'reset','ids':ids})
        self.rejected('not_found',lambda:self.call('/admin/api/licenses',{'action':'delete','ids':[ids[0],'0'*32]}))
        with server.db() as c: self.assertEqual(2,c.execute('SELECT count(*) FROM licenses').fetchone()[0])
        self.call('/admin/api/licenses', {'action':'delete','ids':ids})
        with server.db() as c: self.assertEqual(0,c.execute('SELECT count(*) FROM licenses').fetchone()[0])
    def test_signed_policy_and_telemetry_privacy(self):
        self.login(); self.call('/admin/api/policy', {'require_key':False})
        _, signed = self.call('/v1/policy')
        raw=server.unb64(signed['payload']); self.signer.public_key().verify(server.unb64(signed['signature']),raw,padding.PKCS1v15(),hashes.SHA256())
        self.assertFalse(json.loads(raw)['require_key']); self.assertEqual('policy',json.loads(raw)['kind'])
        device=ec.generate_private_key(ec.SECP256R1()); public=server.b64(device.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
        value=json.dumps({'version':'ghost.85','build':85,'sdk':36,'manufacturer':'Test','model':'Fixture','errors':{'playback':2,'private_text':'never stored'},'chat':'never stored'})
        nonce=server.handle('/v1/challenge',{'public_key':public,'purpose':'telemetry'},self.signer)['nonce']
        proof=server.b64(device.sign(f'ghostgram-v1\ntelemetry\n{nonce}\n{value}'.encode(),ec.ECDSA(hashes.SHA256())))
        server.handle('/v1/telemetry',dict(public_key=public,nonce=nonce,value=value,signature=proof),self.signer)
        _, result=self.call('/admin/api/state'); self.assertEqual(1,len(result['devices'])); self.assertEqual({'playback':2},json.loads(result['devices'][0]['errors']))
        self.assertNotIn('never stored',json.dumps(result)); self.assertEqual(1,result['days'][0]['active'])
    def test_releases_draft_publish_withdraw_and_bad_signature(self):
        self.login(); apk=io.BytesIO()
        with zipfile.ZipFile(apk,'w') as z: z.writestr('AndroidManifest.xml',b'fixture'); z.writestr('classes.dex',b'fixture')
        meta=base64.b64encode(json.dumps({'version':'test','build':85,'notes':'test only'}).encode()).decode()
        kwargs=dict(raw=apk.getvalue(),extra={'X-Release-Metadata':meta},method='POST')
        self.rejected('wrong_apk_signer',lambda:self.call('/admin/api/releases/upload',{},**kwargs))
        with patch.object(web,'verify_apk'), patch.object(web,'apk_identity',return_value={'package':server.APP,'version':'test','version_code':7085}):
            _,result=self.call('/admin/api/releases/upload',{},**kwargs)
        rid=result['id']; self.rejected('not_found',lambda:self.call('/downloads/'+rid+'.apk'))
        self.call('/admin/api/releases/action',{'id':rid,'action':'publish'})
        _,policy=self.call('/v1/policy');self.assertEqual(rid,json.loads(server.unb64(policy['payload']))['update']['id'])
        self.call('/admin/api/releases/action',{'id':rid,'action':'withdraw'})
        self.rejected('not_found',lambda:self.call('/downloads/'+rid+'.apk'))
        self.call('/admin/api/releases/action',{'id':rid,'action':'delete'});self.assertFalse((server.DATA/'releases'/(rid+'.apk')).exists())
    def test_password_rotation_invalidates_sessions(self):
        self.login();self.rejected('weak_password',lambda:self.call('/admin/api/password',{'password':'short'}))
        self.call('/admin/api/password',{'password':'new-long-test-password-only'})
        self.rejected('login_required',lambda:self.call('/admin/api/state'))
    def test_login_rate_limit(self):
        for _ in range(8): self.rejected('wrong_password',lambda:self.call('/admin/api/login',{'password':'wrong'}))
        self.rejected('login_rate_limited',lambda:self.call('/admin/api/login',{'password':'wrong'}))
    def test_free_device_lease_requires_owner_policy(self):
        key=ec.generate_private_key(ec.SECP256R1())
        public=server.b64(key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
        def request():
            nonce=server.handle('/v1/challenge',dict(public_key=public,purpose='free'),self.signer)['nonce']
            proof=server.b64(key.sign(('ghostgram-v1\nfree\n'+nonce+'\n').encode(),ec.ECDSA(hashes.SHA256())))
            return server.handle('/v1/free',dict(public_key=public,nonce=nonce,value='',signature=proof),self.signer)
        self.rejected('key_required',request)
        self.login();self.call('/admin/api/policy',dict(require_key=False))
        lease=request();raw=server.unb64(lease['payload'])
        self.signer.public_key().verify(server.unb64(lease['signature']),raw,padding.PKCS1v15(),hashes.SHA256())
        claims=json.loads(raw);self.assertEqual('free',claims['kind']);self.assertEqual(86400,claims['until']-claims['issued_at'])
        with server.db() as c:self.assertEqual(0,c.execute('SELECT count(*) FROM licenses').fetchone()[0])
    def test_model_byte_ranges_and_private_path_guard(self):
        (server.DATA/'models').mkdir();(server.DATA/'models/whisper-small-q5_0.bin').write_bytes(b'0123456789')
        h,data=self.call('/models/whisper-small-q5_0.bin',extra={'Range':'bytes=2-5'})
        self.assertEqual(206,h.status);self.assertEqual(b'2345',data);self.assertEqual('bytes 2-5/10',h.sent['Content-Range'])
        self.rejected('range_not_satisfiable',lambda:self.call('/models/whisper-small-q5_0.bin',extra={'Range':'bytes=20-'}))
        h,data=self.call('/models/whisper-small-q5_0.bin',method='HEAD');self.assertEqual(b'',data)


if __name__=='__main__': unittest.main(verbosity=2)
