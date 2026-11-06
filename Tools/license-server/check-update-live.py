"""Read and authenticate the public update policy; never publishes a release."""
import base64
import json
from pathlib import Path
import ssl
import urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

root = Path(__file__).resolve().parents[2]
assets = root / 'TMessagesProj_Ghost/src/main/assets/license'
context = ssl.create_default_context(cafile=str(assets / 'server.crt'))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
with opener.open('https://93.88.204.2:9443/v1/policy', timeout=20) as response:
    envelope = json.loads(response.read(32769))
decode = lambda value: base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
raw = decode(envelope['payload'])
key = serialization.load_der_public_key((assets / 'lease-public.der').read_bytes())
key.verify(decode(envelope['signature']), raw, padding.PKCS1v15(), hashes.SHA256())
policy = json.loads(raw)
assert policy['app'] == 'org.ghostgram.messenger' and policy['kind'] == 'policy'
release = policy.get('update')
download = None
if release:
    import re
    assert re.fullmatch('/downloads/[a-f0-9]{32}\\.apk', release['path'])
    url = 'https://93.88.204.2:9443' + release['path']
    with opener.open(urllib.request.Request(url, method='HEAD'), timeout=20) as response:
        assert int(response.headers['Content-Length']) == release['size']
    with opener.open(urllib.request.Request(url, headers={'Range':'bytes=0-15'}), timeout=20) as response:
        assert response.status == 206 and response.read(17).startswith(b'PK')
        assert response.headers['Content-Range'] == 'bytes 0-15/' + str(release['size'])
    download = 'HEAD and byte Range PASS'
print(json.dumps({'tls': 'PASS', 'policy_signature': 'PASS', 'revision': policy['revision'], 'download': download, 'release': None if release is None else {name: release.get(name) for name in ('version', 'build', 'version_code', 'size', 'mandatory')}}, indent=2))
