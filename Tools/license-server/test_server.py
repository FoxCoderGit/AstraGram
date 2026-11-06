"""Offline protocol/security regression tests: no owner keys or phone data."""
import concurrent.futures
import json
import secrets
import tempfile
import time
import unittest
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa, padding
import server


class Protocol(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.signer = rsa.generate_private_key(public_exponent=65537, key_size=3072)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        server.DATA = Path(self.directory.name)
        server.initialize()
        self.id = secrets.token_hex(16)
        self.key = 'GG-' + secrets.token_hex(20).upper()
        self.a, self.b = [ec.generate_private_key(ec.SECP256R1()) for _ in range(2)]
        with server.db() as db:
            db.execute('INSERT INTO licenses(id,key_hash,label,created_at) VALUES(?,?,?,?)',
                       (self.id, server.fingerprint(self.key.encode()), 'test', int(time.time())))

    def tearDown(self):
        self.directory.cleanup()

    def request(self, device, purpose, value):
        public = server.b64(device.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
        nonce = server.handle('/v1/challenge', dict(public_key=public, purpose=purpose), self.signer)['nonce']
        signature = device.sign(f'ghostgram-v1\n{purpose}\n{nonce}\n{value}'.encode(), ec.ECDSA(hashes.SHA256()))
        return dict(public_key=public, nonce=nonce, value=value, signature=server.b64(signature))

    def call(self, device, purpose, value):
        return server.handle('/v1/' + purpose, self.request(device, purpose, value), self.signer)

    def rejected(self, code, operation):
        with self.assertRaises(server.Rejected) as caught:
            operation()
        self.assertEqual(caught.exception.code, code)

    def test_signed_lease_and_renewal(self):
        lease = self.call(self.a, 'activate', self.key.lower())
        payload = server.unb64(lease['payload'])
        self.signer.public_key().verify(server.unb64(lease['signature']), payload, padding.PKCS1v15(), hashes.SHA256())
        claims = json.loads(payload)
        self.assertEqual(claims['until'] - claims['issued_at'], 86400)
        self.assertEqual(claims['license_id'], self.id)
        self.call(self.a, 'activate', self.key)
        self.call(self.a, 'validate', self.id)
        self.rejected('device_bound', lambda: self.call(self.b, 'activate', self.key))
        self.rejected('device_bound', lambda: self.call(self.b, 'validate', self.id))
        with self.assertRaises(Exception):
            self.signer.public_key().verify(server.unb64(lease['signature']), payload + b' ', padding.PKCS1v15(), hashes.SHA256())

    def test_replay_and_wrong_proof(self):
        body = self.request(self.a, 'activate', self.key)
        server.handle('/v1/activate', body, self.signer)
        self.rejected('challenge_expired', lambda: server.handle('/v1/activate', body, self.signer))
        body = self.request(self.a, 'activate', self.key)
        body['value'] = 'changed'
        self.rejected('bad_proof', lambda: server.handle('/v1/activate', body, self.signer))
        self.rejected('challenge_expired', lambda: server.handle('/v1/activate', body, self.signer))
        body = self.request(self.a, 'activate', self.key)
        with server.db() as db: db.execute('UPDATE challenges SET until=0')
        self.rejected('challenge_expired', lambda: server.handle('/v1/activate', body, self.signer))

    def test_revocation_expiry_and_binding_reset(self):
        self.call(self.a, 'activate', self.key)
        with server.db() as db: db.execute("UPDATE licenses SET status='revoked'")
        self.rejected('revoked', lambda: self.call(self.a, 'validate', self.id))
        with server.db() as db: db.execute("UPDATE licenses SET status='active',expires_at=1")
        self.rejected('expired', lambda: self.call(self.a, 'validate', self.id))
        with server.db() as db: db.execute("UPDATE licenses SET expires_at=NULL,device=NULL")
        self.rejected('binding_reset', lambda: self.call(self.a, 'validate', self.id))
        self.call(self.b, 'activate', self.key)
        self.rejected('device_bound', lambda: self.call(self.a, 'validate', self.id))

    def test_atomic_binding(self):
        bodies = [self.request(d, 'activate', self.key) for d in (self.a, self.b)]
        def activate(body):
            try: server.handle('/v1/activate', body, self.signer); return 'ok'
            except server.Rejected as error: return error.code
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(sorted(pool.map(activate, bodies)), ['device_bound', 'ok'])

    def test_invalid_key_and_truncated_expiry(self):
        self.rejected('invalid_key', lambda: self.call(self.a, 'activate', 'not-an-owner-key'))
        with server.db() as db: db.execute('UPDATE licenses SET expires_at=?', (int(time.time()) + 300,))
        claims = json.loads(server.unb64(self.call(self.a, 'activate', self.key)['payload']))
        self.assertLessEqual(claims['until'] - claims['issued_at'], 300)


if __name__ == '__main__': unittest.main(verbosity=2)
