"""GhostGram license service. Key hashes and device public keys only; no chat data."""
import base64
from contextlib import contextmanager
from collections import defaultdict, deque
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import sqlite3
import ssl
import threading
import time
import sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding

DATA = Path(os.environ.get("GHOST_LICENSE_DATA", "/var/lib/ghostgram-license"))
LEASE_SECONDS = 86400
APP = "org.ghostgram.messenger"


def b64(data): return base64.urlsafe_b64encode(data).decode().rstrip("=")
def unb64(value): return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
def fingerprint(data): return hashlib.sha256(data).hexdigest()
def normalized(key): return key.strip().upper()


@contextmanager
def db():
    connection = sqlite3.connect(DATA / "licenses.sqlite", timeout=15, isolation_level=None)
    connection.row_factory = sqlite3.Row
    try: yield connection
    finally: connection.close()


def initialize():
    DATA.mkdir(parents=True, exist_ok=True)
    with db() as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("""CREATE TABLE IF NOT EXISTS licenses (
            id TEXT PRIMARY KEY, key_hash TEXT NOT NULL UNIQUE, label TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'active', expires_at INTEGER,
            device TEXT, created_at INTEGER NOT NULL, bound_at INTEGER, last_seen INTEGER)""")
        connection.execute("CREATE TABLE IF NOT EXISTS challenges (nonce TEXT PRIMARY KEY, device TEXT NOT NULL, purpose TEXT NOT NULL, until INTEGER NOT NULL)")
    import admin_web
    admin_web.initialize(sys.modules[__name__])


class Rejected(Exception):
    def __init__(self, code, status=403): self.code, self.status = code, status


def device_key(value):
    if not isinstance(value, str) or len(value) > 512: raise Rejected("bad_request", 400)
    try:
        raw = unb64(value)
        key = serialization.load_der_public_key(raw)
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1): raise ValueError()
        return key, fingerprint(raw)
    except Exception: raise Rejected("bad_device", 400)


def handle(path, body, signing_key):
    now = int(time.time())
    key, device = device_key(body.get("public_key"))
    if path == "/v1/challenge":
        purpose = body.get("purpose")
        if purpose not in ("activate", "validate", "telemetry", "free"): raise Rejected("bad_request", 400)
        nonce = secrets.token_urlsafe(32)
        with db() as connection:
            connection.execute("DELETE FROM challenges WHERE until < ?", (now,))
            connection.execute("INSERT INTO challenges VALUES(?,?,?,?)", (nonce, device, purpose, now + 60))
        return {"nonce": nonce, "server_time": now}
    purpose = {"/v1/activate": "activate", "/v1/validate": "validate", "/v1/telemetry": "telemetry", "/v1/free": "free"}.get(path)
    if not purpose: raise Rejected("not_found", 404)
    nonce, value, proof = body.get("nonce"), body.get("value"), body.get("signature")
    if not all(isinstance(v, str) for v in (nonce, value, proof)) or len(nonce) > 128 or len(value) > (2048 if purpose == 'telemetry' else 128) or len(proof) > 256:
        raise Rejected("bad_request", 400)
    # Consume the challenge atomically even when the submitted proof is wrong.
    with db() as connection:
        connection.execute("BEGIN IMMEDIATE")
        challenge = connection.execute("SELECT * FROM challenges WHERE nonce=?", (nonce,)).fetchone()
        connection.execute("DELETE FROM challenges WHERE nonce=?", (nonce,))
        connection.commit()
    if not challenge or challenge["until"] < now or challenge["device"] != device or challenge["purpose"] != purpose:
        raise Rejected("challenge_expired", 409)
    try:
        key.verify(unb64(proof), f"ghostgram-v1\n{purpose}\n{nonce}\n{value}".encode(), ec.ECDSA(hashes.SHA256()))
    except Exception: raise Rejected("bad_proof", 403)
    if purpose == 'telemetry':
        import admin_web
        return admin_web.telemetry(sys.modules[__name__], device, value)
    if purpose == 'free':
        with db() as connection:
            require_key = connection.execute('SELECT require_key FROM owner_settings WHERE id=1').fetchone()[0]
        if require_key: raise Rejected('key_required')
        payload = json.dumps(dict(v=1, kind='free', app=APP, license_id=fingerprint(('free:'+device).encode())[:32], device=device, issued_at=now, until=now+LEASE_SECONDS, nonce=nonce), separators=(',', ':'), sort_keys=True).encode()
        return {'payload':b64(payload),'signature':b64(signing_key.sign(payload,padding.PKCS1v15(),hashes.SHA256()))}
    with db() as connection:
        connection.execute("BEGIN IMMEDIATE")
        if purpose == "activate":
            record = connection.execute("SELECT * FROM licenses WHERE key_hash=?", (fingerprint(normalized(value).encode()),)).fetchone()
        else:
            record = connection.execute("SELECT * FROM licenses WHERE id=?", (value,)).fetchone()
        if not record: raise Rejected("invalid_key")
        if record["status"] != "active": raise Rejected("revoked")
        if record["expires_at"] is not None and record["expires_at"] <= now: raise Rejected("expired")
        if record["device"] is not None and record["device"] != device: raise Rejected("device_bound")
        if purpose == "validate" and record["device"] is None: raise Rejected("binding_reset")
        connection.execute("UPDATE licenses SET device=?, bound_at=COALESCE(bound_at,?), last_seen=? WHERE id=?", (device, now, now, record["id"]))
        connection.commit()
    until = min(now + LEASE_SECONDS, record["expires_at"] or now + LEASE_SECONDS)
    payload = json.dumps(dict(v=1, app=APP, license_id=record["id"], device=device, issued_at=now, until=until, nonce=nonce), separators=(",", ":"), sort_keys=True).encode()
    signature = signing_key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
    return {"payload": b64(payload), "signature": b64(signature)}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    rates = defaultdict(deque)
    rate_lock = threading.Lock()
    def log_message(self, *args): pass  # Never log license keys or request bodies.
    def response(self, status, value):
        data = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close"); self.end_headers(); self.wfile.write(data)
        self.close_connection = True
    def do_GET(self):
        try:
            import admin_web
            if admin_web.route(self, sys.modules[__name__]): return
            self.response(200 if self.path == "/health" else 404, {"status": "ok"} if self.path == "/health" else {"error": "not_found"})
        except Rejected as error: self.response(error.status, {"error": error.code})
        except (BrokenPipeError, ConnectionResetError, TimeoutError): pass
        except Exception: self.response(503, {"error": "unavailable"})
    def do_HEAD(self):
        self.do_GET()
    def do_POST(self):
        try:
            self.connection.settimeout(10)
            if self.path.startswith('/admin/'):
                import admin_web
                if admin_web.route(self, sys.modules[__name__]): return
            now = time.monotonic()
            with self.rate_lock:
                if len(self.rates) > 10000:
                    for ip in list(self.rates):
                        if not self.rates[ip] or self.rates[ip][-1] < now - 60: del self.rates[ip]
                bucket = self.rates[self.client_address[0]]
                while bucket and bucket[0] < now - 60: bucket.popleft()
                if len(bucket) >= 120: raise Rejected("rate_limited", 429)
                bucket.append(now)
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4096 or self.headers.get("Transfer-Encoding"): raise Rejected("bad_request", 400)
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict): raise Rejected("bad_request", 400)
            self.response(200, handle(self.path, body, self.server.signing_key))
        except Rejected as error: self.response(error.status, {"error": error.code})
        except (ValueError, TypeError, UnicodeError): self.response(400, {"error": "bad_request"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError): pass
        except Exception: self.response(503, {"error": "unavailable"})


class Server(ThreadingHTTPServer):
    request_queue_size = 64
    daemon_threads = True
    workers = threading.BoundedSemaphore(32)
    def get_request(self):
        request, address = super().get_request()
        request.settimeout(10)
        return request, address
    def process_request(self, request, address):
        if not self.workers.acquire(False): request.close(); return
        try: super().process_request(request, address)
        except BaseException: self.workers.release(); raise
    def process_request_thread(self, request, address):
        try: super().process_request_thread(request, address)
        finally: self.workers.release()


if __name__ == "__main__":
    initialize()
    signer = serialization.load_pem_private_key((DATA / "lease-signing.pem").read_bytes(), password=None)
    server = Server(("0.0.0.0", 9443), Handler); server.signing_key = signer
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(DATA / "server.crt", DATA / "server.key")
    if (DATA / 'domains.crt').is_file() and (DATA / 'domains.key').is_file():
        domains = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        domains.minimum_version = ssl.TLSVersion.TLSv1_2
        domains.load_cert_chain(DATA / 'domains.crt', DATA / 'domains.key')
        def select_domain(socket, name, context):
            if name in ('update.codebyfox.ru', 'verification.codebyfox.ru'): socket.context = domains
        tls.sni_callback = select_domain
    server.socket = tls.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    server.serve_forever()
