"""Owner-authenticated UI bridge to AstraRelay's permission-protected Unix socket."""
import http.client
import json
import socket

SOCKET = '/run/astra-relay/admin.sock'

class LocalConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(5)
        self.sock.connect(SOCKET)

def request(b, method, path, data=None):
    if path not in ('/policy', '/reconnect'):
        raise b.Rejected('bad_request', 400)
    connection = LocalConnection('localhost', timeout=5)
    try:
        raw = None if data is None else json.dumps(data, separators=(',', ':')).encode()
        connection.request(method, path, body=raw, headers={'Content-Type': 'application/json'})
        response = connection.getresponse()
        body = response.read(65537)
        if len(body) > 65536 or response.status != 200:
            raise b.Rejected('relay_unavailable', 503)
        return json.loads(body)
    except (OSError, ValueError, http.client.HTTPException):
        raise b.Rejected('relay_unavailable', 503)
    finally:
        connection.close()
