"""Security and transport checks; run on Linux before enabling the relay service."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from unittest import mock
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location("music_relay", Path(__file__).with_name("relay.py"))
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa, padding
import base64
import hashlib

class EnrollmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.issuer = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.device = ec.generate_private_key(ec.SECP256R1())

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = relay.State(Path(self.directory.name) / "state.json")

    def proof(self, *, until=None, device=None, app="org.ghostgram.messenger", purpose="ghostgram-music-v1", issuer=None):
        nonce = self.state.challenge()
        public = self.device.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        claims = dict(v=1, app=app, device=device or hashlib.sha256(public).hexdigest(), license_id="a"*32,
                      issued_at=int(time.time())-60, until=until or int(time.time())+3600)
        payload = json.dumps(claims).encode()
        encode = lambda b: base64.urlsafe_b64encode(b).decode().rstrip("=")
        return dict(nonce=nonce, public_key=encode(public),
                    proof=encode(self.device.sign((purpose+"\n"+nonce).encode(), ec.ECDSA(hashes.SHA256()))),
                    lease=dict(payload=encode(payload), signature=encode((issuer or self.issuer).sign(payload, padding.PKCS1v15(), hashes.SHA256()))))

    def test_valid_proof_single_use_and_hash_storage(self):
        proof = self.proof()
        token = self.state.enroll(proof, self.issuer.public_key())
        self.assertTrue(self.state.authorized(token))
        self.assertNotIn(token, self.state.path.read_text())
        self.assertIsNone(self.state.enroll(proof, self.issuer.public_key()))

    def test_invalid_proofs(self):
        outsider = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cases = [dict(until=int(time.time())-1), dict(device="b"*64), dict(app="other.app"),
                 dict(purpose="ghostgram-kombat-v1"), dict(issuer=outsider)]
        for args in cases:
            with self.subTest(args=list(args)):
                self.assertIsNone(self.state.enroll(self.proof(**args), self.issuer.public_key()))
        self.assertIsNone(self.state.enroll({}, self.issuer.public_key()))
        self.assertIsNone(self.state.enroll(self.proof(), None))

    def test_challenge_expiry(self):
        proof = self.proof()
        with self.state.transaction() as data:
            data["challenges"][proof["nonce"]] = int(time.time()) - 1
        self.assertIsNone(self.state.enroll(proof, self.issuer.public_key()))


class ScopeTests(unittest.TestCase):
    def test_allowed_read_endpoints(self):
        for path in ("/account/status", "/search?type=track&text=test", "/tracks/123/download-info", "/users/456/likes/tracks"):
            with self.subTest(path=path):
                self.assertEqual(relay.validate_target("https://api.music.yandex.net" + path, "GET").hostname, "api.music.yandex.net")
        relay.validate_target("https://api.music.yandex.net/tracks", "POST")
        relay.validate_target("https://s1.storage.yandex.net/get-mp3/test", "GET")
        relay.validate_target("https://avatars.yandex.net/get-music-content/test", "GET")

    def test_no_mutations_or_open_proxy(self):
        cases = [
            ("https://api.music.yandex.net/users/1/likes/tracks/add", "POST"),
            ("https://api.music.yandex.net/play-audio", "POST"),
            ("https://api.music.yandex.net/account/status", "DELETE"),
            ("https://s1.storage.yandex.net/file", "POST"),
            ("http://api.music.yandex.net/account/status", "GET"),
            ("https://api.music.yandex.net.attacker.test/account/status", "GET"),
            ("https://user@api.music.yandex.net/account/status", "GET"),
            ("https://api.music.yandex.net:8443/account/status", "GET"),
            ("https://127.0.0.1/", "GET"),
            ("file:///etc/passwd", "GET"),
            ("https://api.music.yandex.net/search#fragment", "GET"),
        ]
        for value, method in cases:
            with self.subTest(value=value, method=method), self.assertRaises(ValueError):
                relay.validate_target(value, method)

    def test_private_dns_rejected(self):
        for address in ("127.0.0.1", "10.0.0.1", "169.254.169.254", "::1"):
            with self.subTest(address=address), mock.patch.object(relay.socket, "getaddrinfo", return_value=[(None, None, None, None, (address, 443))]), self.assertRaises(ValueError):
                relay.verify_public_dns("api.music.yandex.net")


class StateTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.state = relay.State(Path(self.directory.name) / "state.json")

    def tearDown(self):
        self.directory.cleanup()

    def test_pair_once_store_hashes_and_revoke(self):
        code = self.state.issue_code()["code"]
        token = self.state.pair(code)
        self.assertTrue(self.state.authorized(token))
        self.assertIsNone(self.state.pair(code))
        stored = self.state.path.read_text()
        self.assertNotIn(token, stored)
        self.assertNotIn(code, stored)
        self.state.revoke(token)
        self.assertFalse(self.state.authorized(token))

    def test_wrong_and_expired_pairing(self):
        self.assertIsNone(self.state.pair("not-a-code"))
        self.assertFalse(self.state.authorized("not-a-token"))
        code = self.state.issue_code()["code"]
        with mock.patch.object(relay.time, "time", return_value=time.time() + 86401):
            self.assertIsNone(self.state.pair(code))

    def test_pairing_is_atomic(self):
        code = self.state.issue_code()["code"]
        values = []
        threads = [threading.Thread(target=lambda: values.append(self.state.pair(code))) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(sum(value is not None for value in values), 1)


class Response(io.BytesIO):
    def __init__(self, content=b'{"result":true}', length=True, status=200):
        super().__init__(content)
        self.headers = {"Content-Type": "application/json"}
        if length:
            self.headers["Content-Length"] = str(len(content))
        self.status = status


class TransportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        root = Path(cls.directory.name)
        cls.certificate, key = root / "cert.pem", root / "key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-keyout", str(key), "-out", str(cls.certificate), "-days", "1", "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(cls.certificate), str(key))
        cls.state = relay.State(root / "state.json")
        cls.token = cls.state.pair(cls.state.issue_code()["code"])
        cls.server = relay.RelayServer(("127.0.0.1", 0), cls.state, context)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"https://127.0.0.1:{cls.server.server_port}"
        cls.context = ssl.create_default_context(cafile=str(cls.certificate))

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.directory.cleanup()

    def request(self, path, *, token=True, target=None, body=None):
        headers = {}
        if token:
            headers["X-Ghost-Music-Token"] = self.token
        if target:
            headers["X-Ghost-Upstream-Url"] = target
        request = urllib.request.Request(self.url + path, data=body, headers=headers)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=self.context))
        try:
            return opener.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            return error

    def test_health_tls_and_no_open_proxy(self):
        with self.request("/health", token=False) as response:
            self.assertEqual(response.status, 200)
        with self.request("/relay", token=False, target="https://api.music.yandex.net/account/status") as response:
            self.assertEqual(response.status, 401)
            self.assertEqual(response.headers["X-Ghost-Music-Error"], "relay-auth")
        with self.request("/relay", token=False, target="https://api.music.yandex.net/tracks", body=b"track-ids=123") as response:
            self.assertEqual(response.status, 401)
        with self.request("/relay", target="https://127.0.0.1/") as response:
            self.assertEqual(response.status, 400)

    def test_enrollment_over_https_and_replay_rejection(self):
        self.server.attempts.clear()
        EnrollmentTests.setUpClass()
        helper = EnrollmentTests()
        helper.state = self.state
        previous_key = self.server.lease_key
        self.server.lease_key = helper.issuer.public_key()
        try:
            with self.request("/enroll/challenge", token=False) as response:
                self.assertEqual(response.status, 200)
                self.assertRegex(json.load(response)["nonce"], r"^[0-9a-f]{64}$")
            proof = helper.proof()
            body = json.dumps(proof).encode()
            with self.request("/enroll", token=False, body=body) as response:
                self.assertEqual(response.status, 200)
                token = json.load(response)["token"]
                self.assertTrue(self.state.authorized(token))
            with self.request("/enroll", token=False, body=body) as response:
                self.assertEqual(response.status, 401)
            self.state.revoke(token)
        finally:
            self.server.lease_key = previous_key

    def test_forward_known_and_unknown_lengths(self):
        for length in (True, False):
            with self.subTest(length=length), mock.patch.object(relay, "verify_public_dns"), mock.patch.object(relay.urllib.request, "build_opener") as build:
                # Keep the real client opener separate from the patched upstream factory.
                fake = mock.Mock()
                fake.open.return_value = Response(b"test-audio-bytes", length=length)
                build.return_value = fake
                import http.client
                connection = http.client.HTTPSConnection("127.0.0.1", self.server.server_port, context=self.context, timeout=5)
                connection.request("GET", "/relay", headers={"X-Ghost-Music-Token": self.token, "X-Ghost-Upstream-Url": "https://s1.storage.yandex.net/get-mp3/test"})
                response = connection.getresponse()
                self.assertEqual(response.read(), b"test-audio-bytes")
                connection.close()
                self.assertNotIn("Authorization", fake.open.call_args.args[0].headers)

    def test_invalid_code_and_pairing_rate(self):
        self.server.attempts.clear()
        for _ in range(8):
            with self.request("/pair", token=False, body=b'{"code":"invalid"}') as response:
                self.assertEqual(response.status, 401)
        with self.request("/pair", token=False, body=b'{"code":"invalid"}') as response:
            self.assertEqual(response.status, 429)


if __name__ == "__main__":
    unittest.main(verbosity=2)
