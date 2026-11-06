"""Authenticated HTTPS forwarding for the owner's Yandex Music client. License-authenticated device enrollment.

OAuth headers and audio are forwarded in memory; requests, tokens and payloads are never logged.
"""
import argparse
import base64
import contextlib
import fcntl
import hashlib
import http.server
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import socketserver
import ssl
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa, padding
from cryptography.exceptions import InvalidSignature

MAX_RESPONSE = 128 * 1024 * 1024
MAX_BODY = 16 * 1024


def validate_target(value, method):
    target = urllib.parse.urlsplit(value)
    if (target.scheme != "https" or not target.hostname or target.username is not None
            or target.password is not None or target.port not in (None, 443) or target.fragment
            or any(ord(c) < 32 for c in value)):
        raise ValueError("target")
    host = target.hostname.lower()
    if host == "api.music.yandex.net":
        allowed = method == "GET" and re.fullmatch(
            r"/(?:account/status|search|tracks/[0-9]{1,24}/download-info|users/[0-9]{1,24}/likes/tracks)", target.path)
        if not allowed and not (method == "POST" and target.path == "/tracks"):
            raise ValueError("api_scope")
    elif method != "GET" or not (
            host in ("avatars.yandex.net", "avatars.mds.yandex.net")
            or any(host.endswith(suffix) for suffix in (".storage.yandex.net", ".strm.yandex.net", ".mds.yandex.net"))):
        raise ValueError("host_scope")
    return target


def verify_public_dns(host):
    addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        raise ValueError("address_scope")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None  # Android validates and forwards each redirect as a new authenticated request.


class State:
    def __init__(self, filename):
        self.path = Path(filename)
        self.lock = threading.RLock()

    @contextlib.contextmanager
    def transaction(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.lock, open(str(self.path) + ".lock", "a+", encoding="utf-8") as lock:
            os.chmod(lock.name, 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX)
            data = json.loads(self.path.read_text()) if self.path.exists() else {"tokens": {}, "codes": {}}
            yield data
            with tempfile.NamedTemporaryFile("w", dir=self.path.parent, delete=False, encoding="utf-8") as output:
                json.dump(data, output)
                output.flush()
                os.fsync(output.fileno())
                temp = output.name
            os.chmod(temp, 0o600)
            os.replace(temp, self.path)

    def issue_code(self):
        code = base64.b32encode(secrets.token_bytes(8)).decode().rstrip("=")
        expires = int(time.time()) + 86400
        with self.transaction() as data:
            data["codes"] = {key: exp for key, exp in data["codes"].items() if exp > time.time()}
            data["codes"][hashlib.sha256(code.encode()).hexdigest()] = expires
        return {"code": code, "expires": expires}

    def pair(self, code):
        if not isinstance(code, str):
            return None
        code = code.replace("-", "").replace(" ", "").upper()
        if not re.fullmatch(r"[A-Z2-7]{13}", code):
            return None
        digest = hashlib.sha256(code.encode()).hexdigest()
        with self.transaction() as data:
            expires = data["codes"].pop(digest, 0)
            if expires < time.time():
                return None
            token = secrets.token_urlsafe(32)
            data["tokens"][hashlib.sha256(token.encode()).hexdigest()] = int(time.time()) + 365 * 86400
            return token

    def challenge(self):
        now = int(time.time())
        nonce = secrets.token_hex(32)
        with self.transaction() as data:
            pending = {k: v for k, v in data.get("challenges", {}).items() if v > now}
            if len(pending) >= 2048:
                raise ValueError("challenge_limit")
            pending[nonce] = now + 90
            data["challenges"] = pending
        return nonce

    def enroll(self, request, trusted_key):
        try:
            nonce = request["nonce"]
            if not isinstance(nonce, str) or not re.fullmatch(r"[0-9a-f]{64}", nonce):
                return None
            with self.transaction() as data:
                deadline = data.get("challenges", {}).pop(nonce, 0)
            if deadline <= time.time() or trusted_key is None:
                return None
            def decode(value):
                if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,8192}", value):
                    raise ValueError("base64")
                return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
            lease = request["lease"]
            payload = decode(lease["payload"])
            trusted_key.verify(decode(lease["signature"]), payload, padding.PKCS1v15(), hashes.SHA256())
            claims = json.loads(payload)
            public_der = decode(request["public_key"])
            issued, until, now = claims["issued_at"], claims["until"], int(time.time())
            if (claims["v"] != 1 or claims["app"] != "org.ghostgram.messenger"
                    or claims["device"] != hashlib.sha256(public_der).hexdigest()
                    or not re.fullmatch(r"[a-f0-9]{32}", claims["license_id"])
                    or not isinstance(issued, int) or not isinstance(until, int)
                    or issued > now + 60 or until <= now or not 0 < until - issued <= 86400):
                return None
            public = serialization.load_der_public_key(public_der)
            if not isinstance(public, ec.EllipticCurvePublicKey) or not isinstance(public.curve, ec.SECP256R1):
                return None
            public.verify(decode(request["proof"]), ("ghostgram-music-v1\n" + nonce).encode(), ec.ECDSA(hashes.SHA256()))
            token = secrets.token_urlsafe(32)
            with self.transaction() as data:
                data["tokens"] = {k: v for k, v in data["tokens"].items() if v > now}
                data["tokens"][hashlib.sha256(token.encode()).hexdigest()] = now + 30 * 86400
            return token
        except (KeyError, ValueError, TypeError, InvalidSignature, AttributeError):
            return None

    def authorized(self, token):
        if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{40,100}", token):
            return False
        digest = hashlib.sha256(token.encode()).hexdigest()
        with self.lock, open(str(self.path) + ".lock", "a+", encoding="utf-8") as lock:
            fcntl.flock(lock, fcntl.LOCK_SH)
            data = json.loads(self.path.read_text()) if self.path.exists() else {"tokens": {}}
            return data["tokens"].get(digest, 0) > time.time()

    def revoke(self, token):
        with self.transaction() as data:
            data["tokens"].pop(hashlib.sha256(token.encode()).hexdigest(), None)


class RelayHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "GhostMusicRelay/1"
    sys_version = ""

    def log_message(self, *args):
        pass

    def reply(self, status, body, error=None):
        content = json.dumps(body, separators=(",", ":")).encode()
        self.responded = True
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Connection", "close")
        if error:
            self.send_header("X-Ghost-Music-Error", error)
        self.end_headers()
        self.wfile.write(content)
        self.close_connection = True

    def body(self, maximum):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("body")
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > maximum:
            raise ValueError("body")
        data = self.rfile.read(length)
        if len(data) != length:
            raise ValueError("body")
        return data

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        self.responded = False
        try:
            client_ip = self.client_address[0]
            if client_ip == "127.0.0.1" and self.headers.get("X-Ghost-Client-IP"):
                client_ip = str(ipaddress.ip_address(self.headers["X-Ghost-Client-IP"]))
            self.payload = self.body(MAX_BODY)
            if self.path == "/health" and self.command == "GET":
                self.reply(200, {"ok": True, "service": "ghostgram-music-relay"})
            elif self.path == "/enroll/challenge" and self.command == "GET":
                if not self.server.allow_pairing_attempt(client_ip):
                    self.reply(429, {"error": "rate"})
                    return
                self.reply(200, {"nonce": self.server.state.challenge()})
            elif self.path == "/enroll" and self.command == "POST":
                if not self.server.allow_pairing_attempt(client_ip):
                    self.reply(429, {"error": "rate"})
                    return
                token = self.server.state.enroll(json.loads(self.payload), self.server.lease_key)
                self.reply(200 if token else 401, {"token": token} if token else {"error": "license"})
            elif self.path == "/pair" and self.command == "POST":
                if not self.server.allow_pairing_attempt(client_ip):
                    self.reply(429, {"error": "pairing_rate"}, "pairing-rate")
                    return
                if len(self.payload) > 1024:
                    raise ValueError("pairing_body")
                code = json.loads(self.payload).get("code")
                token = self.server.state.pair(code)
                self.reply(200 if token else 401, {"token": token} if token else {"error": "pairing"}, None if token else "pairing")
            elif self.path == "/relay":
                if not self.server.state.authorized(self.headers.get("X-Ghost-Music-Token")):
                    self.reply(401, {"error": "relay_auth"}, "relay-auth")
                    return
                self.forward()
            else:
                self.reply(404, {"error": "path"}, "path")
        except (ValueError, UnicodeError, json.JSONDecodeError):
            self.reply(400, {"error": "invalid_request"}, "invalid-request")
        except (OSError, TimeoutError, urllib.error.URLError):
            try:
                if not self.responded:
                    self.reply(502, {"error": "upstream"}, "upstream")
            except OSError:
                pass

    def forward(self):
        value = self.headers.get("X-Ghost-Upstream-Url", "")
        target = validate_target(value, self.command)
        verify_public_dns(target.hostname)
        body = self.payload
        if self.command == "GET" and body:
            raise ValueError("get_body")
        headers = {"User-Agent": "GhostGram-YandexMusic/1.0", "Accept-Language": "ru", "Accept-Encoding": "identity"}
        if target.hostname == "api.music.yandex.net":
            auth = self.headers.get("Authorization", "")
            if auth:
                if not re.fullmatch(r"OAuth [A-Za-z0-9._~+/=-]{16,4096}", auth):
                    raise ValueError("authorization")
                headers["Authorization"] = auth
        if self.command == "POST":
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        request = urllib.request.Request(value, data=body if self.command == "POST" else None, headers=headers, method=self.command)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        try:
            response = opener.open(request, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            length = response.headers.get("Content-Length")
            if length and (not length.isdigit() or int(length) > MAX_RESPONSE):
                self.reply(413, {"error": "size"}, "size")
                return
            self.send_response(response.status)
            self.responded = True
            for name in ("Content-Type", "Content-Length", "Location", "Retry-After"):
                if response.headers.get(name):
                    self.send_header(name, response.headers[name])
            self.send_header("Connection", "close")
            if not length:
                self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            self.close_connection = True
            count = 0
            while data := response.read(65536):
                count += len(data)
                if count > MAX_RESPONSE:
                    return  # No final chunk: the client must reject an incomplete/oversized stream.
                if not length:
                    self.wfile.write(f"{len(data):x}\r\n".encode())
                self.wfile.write(data)
                if not length:
                    self.wfile.write(b"\r\n")
            if not length:
                self.wfile.write(b"0\r\n\r\n")


class RelayServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, state, context):
        super().__init__(address, RelayHandler)
        self.state, self.context = state, context
        public = Path(__file__).with_name("lease-public.der")
        self.lease_key = serialization.load_der_public_key(public.read_bytes()) if public.exists() else None
        self.slots = threading.BoundedSemaphore(24)
        self.attempts = {}
        self.attempt_lock = threading.Lock()

    def allow_pairing_attempt(self, address):
        now = time.monotonic()
        with self.attempt_lock:
            self.attempts = {ip: values for ip, values in self.attempts.items() if values[0] > now - 60}
            start, count = self.attempts.get(address, (now, 0))
            if count >= 8:
                return False
            self.attempts[address] = (start, count + 1)
            return True

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        super().process_request(request, address)

    def process_request_thread(self, request, address):
        try:
            request.settimeout(20)
            with self.context.wrap_socket(request, server_side=True) as secure:
                self.finish_request(secure, address)
        except (OSError, ssl.SSLError):
            pass
        finally:
            self.shutdown_request(request)
            self.slots.release()

    def handle_error(self, request, address):
        pass  # Never print headers, URLs, request bodies or credentials.


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", required=True)
    parser.add_argument("--cert")
    parser.add_argument("--key")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--issue-pairing", action="store_true")
    arguments = parser.parse_args()
    state = State(arguments.state)
    if arguments.issue_pairing:
        print(json.dumps(state.issue_code()))
        return
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(arguments.cert, arguments.key)
    RelayServer(("0.0.0.0", arguments.port), state, context).serve_forever()


if __name__ == "__main__":
    main()
