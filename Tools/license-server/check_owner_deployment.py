"""Read-only live checks; optional upload creates an unpublished APK draft only."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import requests
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import padding
from deploy import ROOT, IDENTITY


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--draft', type=Path)
    opts = args.parse_args()
    access = json.loads((IDENTITY / 'owner-panel.json').read_text())
    base = 'https://93.88.204.2:9443'
    s = requests.Session()
    s.verify = str(ROOT / 'TMessagesProj_Ghost/src/main/assets/license/server.crt')
    s.headers['Origin'] = base
    r = s.post(base + '/admin/api/login', json={'password': access['password']}, timeout=20)
    r.raise_for_status()
    s.headers['X-CSRF-Token'] = r.json()['csrf']
    policy = s.get(base + '/v1/policy', timeout=20)
    policy.raise_for_status()
    signed = policy.json()
    unb64 = lambda v: base64.urlsafe_b64decode(v + '=' * (-len(v) % 4))
    public = serialization.load_der_public_key((ROOT / 'TMessagesProj_Ghost/src/main/assets/license/lease-public.der').read_bytes())
    public.verify(unb64(signed['signature']), unb64(signed['payload']), padding.PKCS1v15(), hashes.SHA256())
    p = json.loads(unb64(signed['payload']))
    assert p['kind'] == 'policy' and p['app'] == 'org.ghostgram.messenger'
    model = base + '/models/whisper-small-q5_0.bin'
    h = s.head(model, timeout=20)
    assert h.status_code == 200 and int(h.headers['Content-Length']) == 175209663
    r = s.get(model, headers={'Range': 'bytes=0-1023'}, timeout=20)
    assert r.status_code == 206 and len(r.content) == 1024
    with (ROOT / 'Tools/optional-speech-models/ggml-small-q5_0.bin').open('rb') as f:
        assert r.content == f.read(1024)
    state = s.get(base + '/admin/api/state', timeout=20)
    state.raise_for_status()
    state = state.json()
    print('PASS: pinned HTTPS, authenticated owner state, signed policy, model HEAD/Range; required key:', p['require_key'], flush=True)
    print('License rows:', len(state['licenses']), '; device versions:', sorted({d['version'] for d in state['devices']}), flush=True)
    if opts.draft:
        apk = opts.draft.resolve()
        digest = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
        existing = next((r for r in state['releases'] if r['sha256'] == digest), None)
        if existing:
            print('APK already present, state:', existing['state'], flush=True)
        else:
            meta = dict(version='12.10.6-ghost.85-owner-panel', build=85,
                        notes='Панель владельца: ключи, статистика, управление проверкой и обновлениями. Облегчённый APK без моделей; штатная расшифровка Telegram по умолчанию, локальные модели скачиваются по подтверждению. Сохранены предыдущие функции и исправления кружков.')
            print('Uploading signed APK as unpublished draft:', apk.stat().st_size, 'bytes', flush=True)
            with apk.open('rb') as f:
                r = s.post(base + '/admin/api/releases/upload', data=f, headers={
                    'Content-Type': 'application/vnd.android.package-archive',
                    'X-Release-Metadata': base64.b64encode(json.dumps(meta, ensure_ascii=False).encode()).decode()}, timeout=(20, 600))
            r.raise_for_status()
            rid = r.json()['id']
            state = s.get(base + '/admin/api/state', timeout=20).json()
            release = next(r for r in state['releases'] if r['id'] == rid)
            assert release['state'] == 'draft' and release['sha256'] == digest and release['size'] == apk.stat().st_size
            assert s.get(base + '/downloads/' + rid + '.apk', timeout=20).status_code == 404
            print('PASS: real APK cryptographic signer accepted; draft SHA-256/size match; draft not publicly downloadable', flush=True)
    s.post(base + '/admin/api/logout', json={}, timeout=20).raise_for_status()


if __name__ == '__main__':
    main()
