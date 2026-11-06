"""Verify deployed TLS/protocol, then create the owner's key outside the project."""
import json
from pathlib import Path
import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding
import server
from deploy import connect, run, IDENTITY, ROOT

CERT = str(ROOT / 'TMessagesProj_Ghost/src/main/assets/license/server.crt')
URL = 'https://93.88.204.2:9443'


def request(device, purpose, value):
    public = server.b64(device.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    challenge = requests.post(URL + '/v1/challenge', json=dict(public_key=public, purpose=purpose), verify=CERT, timeout=15)
    challenge.raise_for_status()
    nonce = challenge.json()['nonce']
    signature = device.sign(f'ghostgram-v1\n{purpose}\n{nonce}\n{value}'.encode(), ec.ECDSA(hashes.SHA256()))
    return requests.post(URL + '/v1/' + purpose, json=dict(public_key=public, nonce=nonce, value=value, signature=server.b64(signature)), verify=CERT, timeout=15)


def main():
    assert requests.get(URL + '/health', verify=CERT, timeout=15).json()['status'] == 'ok'
    with connect() as ssh:
        record = json.loads(run(ssh, 'python3 /opt/ghostgram-license/admin.py create --label protocol-test --days 1'))
        try:
            a, b = [ec.generate_private_key(ec.SECP256R1()) for _ in range(2)]
            activated = request(a, 'activate', record['key']); activated.raise_for_status()
            response = activated.json()
            public = serialization.load_der_public_key((ROOT / 'TMessagesProj_Ghost/src/main/assets/license/lease-public.der').read_bytes())
            public.verify(server.unb64(response['signature']), server.unb64(response['payload']), padding.PKCS1v15(), hashes.SHA256())
            assert request(b, 'activate', record['key']).json()['error'] == 'device_bound'
            assert request(a, 'validate', record['id']).status_code == 200
            run(ssh, 'python3 /opt/ghostgram-license/admin.py revoke ' + record['id'])
            assert request(a, 'validate', record['id']).json()['error'] == 'revoked'
        finally:
            run(ssh, 'python3 /opt/ghostgram-license/admin.py revoke ' + record['id'])
        owner = IDENTITY / 'owner-license.json'
        if not owner.exists():
            owner.write_text(run(ssh, 'python3 /opt/ghostgram-license/admin.py create --label Owner'), encoding='utf-8')
        assert json.loads(owner.read_text(encoding='utf-8'))['key'].startswith('GG-')
        assert run(ssh, 'systemctl is-active ghostgram-license').strip() == 'active'
        print('PASS: pinned TLS/IP SAN, signed lease, renewal, single-device binding, revocation; owner key saved outside project.')


if __name__ == '__main__': main()
