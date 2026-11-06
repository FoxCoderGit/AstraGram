"""Owner console, public signed policy, releases and minimal device statistics."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import time
import urllib.parse
import zipfile
import subprocess
import re
import threading
from struct import error as struct_error
from apk_manifest import identity as apk_identity
from collections import defaultdict, deque
from cryptography.hazmat.primitives import hashes

MAX_APK = 200_000_000
WEB = Path(__file__).parent / 'web'
LOGIN_LOCK = threading.Lock()
LOGIN_ATTEMPTS = defaultdict(deque)


def initialize(b):
    with b.db() as c:
        c.executescript('''
        CREATE TABLE IF NOT EXISTS owner_settings (id INTEGER PRIMARY KEY CHECK(id=1), require_key INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1);
        INSERT OR IGNORE INTO owner_settings(id) VALUES(1);
        CREATE TABLE IF NOT EXISTS owner_sessions (token TEXT PRIMARY KEY, csrf TEXT NOT NULL, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS owner_audit (id INTEGER PRIMARY KEY, at INTEGER NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS app_devices (id TEXT PRIMARY KEY, version TEXT NOT NULL, build INTEGER NOT NULL, sdk INTEGER NOT NULL, manufacturer TEXT NOT NULL, model TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, errors TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS app_days (day TEXT NOT NULL, device TEXT NOT NULL, PRIMARY KEY(day,device));
        CREATE TABLE IF NOT EXISTS app_releases (id TEXT PRIMARY KEY, version TEXT NOT NULL, build INTEGER NOT NULL, notes TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'draft', created_at INTEGER NOT NULL, published_at INTEGER);
        CREATE TABLE IF NOT EXISTS project_badges (user_id TEXT PRIMARY KEY, type TEXT NOT NULL CHECK(type IN ('supporter','creator')), label TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL);
        ''')
        columns = {row[1] for row in c.execute('PRAGMA table_info(app_releases)')}
        if 'version_code' not in columns: c.execute('ALTER TABLE app_releases ADD COLUMN version_code INTEGER NOT NULL DEFAULT 0')
        if 'mandatory' not in columns: c.execute('ALTER TABLE app_releases ADD COLUMN mandatory INTEGER NOT NULL DEFAULT 0')
        # Existing releases are migrated from their own APK, never guessed from ghost.*.
        for row in c.execute('SELECT id FROM app_releases WHERE version_code=0').fetchall():
            try:
                info = apk_identity(b.DATA / 'releases' / (row['id'] + '.apk'))
                if info['package'] == b.APP: c.execute('UPDATE app_releases SET version_code=? WHERE id=?', (info['version_code'], row['id']))
            except (OSError, ValueError, KeyError, IndexError, struct_error, zipfile.BadZipFile): pass
    (b.DATA / 'releases').mkdir(mode=0o700, exist_ok=True)


def audit(c, action, detail=''):
    c.execute('INSERT INTO owner_audit(at,action,detail) VALUES(?,?,?)', (int(time.time()), action, str(detail)[:500]))


def configuration(b):
    try:
        return json.loads((b.DATA / 'owner.json').read_text())
    except FileNotFoundError:
        raise b.Rejected('owner_not_configured', 503)


def password_hash(password, salt):
    return hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()


def public_policy(b, signer):
    with b.db() as c:
        row = c.execute('SELECT require_key,revision FROM owner_settings WHERE id=1').fetchone()
        release = c.execute("SELECT id,version,build,notes,size,sha256,version_code,mandatory FROM app_releases WHERE state='published' ORDER BY version_code DESC,build DESC LIMIT 1").fetchone()
    now = int(time.time())
    payload = dict(v=1, kind='policy', app=b.APP, require_key=bool(row['require_key']), revision=row['revision'], issued_at=now, until=now + 86400)
    if release:
        payload['update'] = dict(release)
        payload['update']['mandatory'] = bool(release['mandatory'])
        payload['update']['path'] = '/downloads/' + release['id'] + '.apk'
    raw = json.dumps(payload, separators=(',', ':'), sort_keys=True).encode()
    return {'payload': b.b64(raw), 'signature': b.b64(signer.sign(raw, b.padding.PKCS1v15(), b.hashes.SHA256()))}


def public_badges(b, signer):
    with b.db() as c:
        revision = c.execute('SELECT revision FROM owner_settings WHERE id=1').fetchone()[0]
        badges = [dict(r) for r in c.execute('SELECT user_id,type FROM project_badges ORDER BY user_id')]
    now = int(time.time())
    payload = dict(version=1, kind='badges', app=b.APP, revision=revision, issued_at=now, until=now+86400, badges=badges)
    raw = json.dumps(payload, separators=(',', ':'), sort_keys=True).encode()
    return {'payload': b.b64(raw), 'signature': b.b64(signer.sign(raw, b.padding.PKCS1v15(), b.hashes.SHA256()))}


def telemetry(b, device, value):
    data = json.loads(value)
    if not isinstance(data, dict): raise b.Rejected('bad_request', 400)
    def text(name, length):
        v = data.get(name, '')
        if not isinstance(v, str) or len(v) > length: raise b.Rejected('bad_request', 400)
        return ''.join(ch for ch in v if ord(ch) >= 32)
    version, manufacturer, model = text('version', 100), text('manufacturer', 64), text('model', 80)
    build, sdk = data.get('build', 0), data.get('sdk', 0)
    if type(build) is not int or not 0 <= build <= 1000000 or type(sdk) is not int or not 21 <= sdk <= 100: raise b.Rejected('bad_request', 400)
    errors = data.get('errors', {})
    if not isinstance(errors, dict): raise b.Rejected('bad_request', 400)
    clean = {key: val for key, val in errors.items() if key in ('auth_network', 'speech', 'playback') and type(val) is int and 0 <= val <= 100000000}
    now = int(time.time())
    with b.db() as c:
        c.execute('INSERT INTO app_devices VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,build=excluded.build,sdk=excluded.sdk,manufacturer=excluded.manufacturer,model=excluded.model,last_seen=excluded.last_seen,errors=excluded.errors', (device, version, build, sdk, manufacturer, model, now, now, json.dumps(clean)))
        c.execute("INSERT OR IGNORE INTO app_days VALUES(date(?,'unixepoch'),?)", (now, device))
        c.execute("DELETE FROM app_days WHERE day < date(?,'unixepoch','-90 days')", (now,))
        c.execute('DELETE FROM owner_sessions WHERE expires<?', (now,))
    return {'status': 'ok'}


def session(h, b):
    from http.cookies import SimpleCookie
    cookie = SimpleCookie()
    try: cookie.load(h.headers.get('Cookie', ''))
    except Exception: raise b.Rejected('login_required', 401)
    token = cookie.get('gg_owner')
    if token is None: raise b.Rejected('login_required', 401)
    digest = hashlib.sha256(token.value.encode()).hexdigest()
    with b.db() as c:
        row = c.execute('SELECT * FROM owner_sessions WHERE token=? AND expires>?', (digest, int(time.time()))).fetchone()
    if row is None: raise b.Rejected('login_required', 401)
    return row


def origin(h, b):
    parsed = urllib.parse.urlsplit(h.headers.get('Origin', ''))
    if parsed.scheme != 'https' or parsed.netloc != h.headers.get('Host'): raise b.Rejected('bad_origin', 403)


def response(h, status, data, content_type='application/json; charset=utf-8', cookie=None):
    if not isinstance(data, bytes): data = json.dumps(data, ensure_ascii=False).encode()
    h.send_response(status)
    h.send_header('Content-Type', content_type)
    h.send_header('Content-Length', str(len(data)))
    h.send_header('Cache-Control', 'no-store')
    h.send_header('X-Content-Type-Options', 'nosniff')
    h.send_header('X-Frame-Options', 'DENY')
    h.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'")
    if cookie: h.send_header('Set-Cookie', cookie)
    h.send_header('Connection', 'close'); h.end_headers(); h.wfile.write(data); h.close_connection = True


def body(h, b):
    try: length = int(h.headers.get('Content-Length', '0'))
    except ValueError: raise b.Rejected('bad_request', 400)
    if not 0 < length <= 16384 or h.headers.get('Transfer-Encoding'): raise b.Rejected('bad_request', 400)
    result = json.loads(h.rfile.read(length))
    if not isinstance(result, dict): raise b.Rejected('bad_request', 400)
    return result


def license_action(b, data):
    action = data.get('action')
    now = int(time.time())
    with b.db() as c:
        c.execute('BEGIN IMMEDIATE')
        if action == 'create':
            count, days = data.get('count', 1), data.get('days', 0)
            if type(count) is not int or not 1 <= count <= 100 or type(days) is not int or not 0 <= days <= 36500: raise b.Rejected('bad_request', 400)
            label = str(data.get('label', '')).strip()[:120]
            result = []
            for i in range(count):
                lid = secrets.token_hex(16)
                key = 'GG-' + '-'.join(secrets.token_hex(4).upper() for _ in range(5))
                c.execute('INSERT INTO licenses(id,key_hash,label,expires_at,created_at) VALUES(?,?,?,?,?)', (lid, b.fingerprint(key.encode()), label, now + days * 86400 if days else None, now))
                result.append({'id': lid, 'key': key})
            audit(c, 'license.create', str(count)); c.commit(); return {'created': result}
        ids = data.get('ids', [])
        if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(not isinstance(x, str) or len(x) != 32 or any(ch not in 'abcdef0123456789' for ch in x) for x in ids): raise b.Rejected('bad_request', 400)
        for lid in ids:
            if c.execute('SELECT id FROM licenses WHERE id=?', (lid,)).fetchone() is None: raise b.Rejected('not_found', 404)
            if action in ('revoke', 'restore'):
                c.execute('UPDATE licenses SET status=? WHERE id=?', ('revoked' if action == 'revoke' else 'active', lid))
            elif action == 'reset': c.execute('UPDATE licenses SET device=NULL,bound_at=NULL WHERE id=?', (lid,))
            elif action == 'delete': c.execute('DELETE FROM licenses WHERE id=?', (lid,))
            elif action == 'edit':
                days = data.get('days', 0)
                if type(days) is not int or not 0 <= days <= 36500: raise b.Rejected('bad_request', 400)
                c.execute('UPDATE licenses SET label=?,expires_at=? WHERE id=?', (str(data.get('label', ''))[:120], now + days * 86400 if days else None, lid))
            else: raise b.Rejected('bad_request', 400)
            audit(c, 'license.' + action, lid)
        c.commit()
    return {'status': 'ok'}


def upload(h, b):
    cfg = configuration(b)
    expected = cfg.get('apk_signer_sha256', '')
    if len(expected) != 64: raise b.Rejected('apk_signer_not_configured', 503)
    meta = json.loads(base64.b64decode(h.headers.get('X-Release-Metadata', ''), validate=True))
    version, build, notes = meta.get('version'), meta.get('build'), meta.get('notes', '')
    mandatory = meta.get('mandatory', False)
    if type(mandatory) is not bool: raise b.Rejected('bad_request', 400)
    if not isinstance(version, str) or not 1 <= len(version) <= 100 or type(build) is not int or not 1 <= build <= 1000000 or not isinstance(notes, str) or len(notes) > 4000: raise b.Rejected('bad_request', 400)
    length = int(h.headers.get('Content-Length', '0'))
    if not 0 < length <= MAX_APK or h.headers.get('Transfer-Encoding'): raise b.Rejected('apk_too_large', 413)
    rid = secrets.token_hex(16); path = b.DATA / 'releases' / (rid + '.apk'); digest = hashlib.sha256()
    success = False
    try:
        h.connection.settimeout(60)
        remaining = length
        with path.open('xb') as f:
            while remaining:
                chunk = h.rfile.read(min(1024 * 1024, remaining))
                if not chunk: raise b.Rejected('incomplete_upload', 400)
                f.write(chunk); digest.update(chunk); remaining -= len(chunk)
            f.flush(); os.fsync(f.fileno())
        with zipfile.ZipFile(path) as z:
            if 'AndroidManifest.xml' not in z.namelist() or not any(n.startswith('classes') and n.endswith('.dex') for n in z.namelist()): raise b.Rejected('invalid_apk', 400)
        verify_apk(b, path, expected)
        try: manifest = apk_identity(path)
        except (ValueError, KeyError, IndexError, struct_error, zipfile.BadZipFile): raise b.Rejected('invalid_apk', 400)
        if manifest['package'] != b.APP or manifest['version'] != version: raise b.Rejected('invalid_apk', 400)
        with b.db() as c:
            c.execute('INSERT INTO app_releases(id,version,build,notes,size,sha256,created_at,version_code,mandatory) VALUES(?,?,?,?,?,?,?,?,?)', (rid, version, build, notes, length, digest.hexdigest(), int(time.time()), manifest['version_code'], int(mandatory)))
            audit(c, 'release.upload', rid)
        success = True
        return {'status': 'ok', 'id': rid}
    finally:
        if not success: path.unlink(missing_ok=True)


def verify_apk(b, path, expected):
    jar = Path(__file__).parent / 'apksigner.jar'
    try:
        result = subprocess.run(['java', '-Xmx64m', '-jar', str(jar), 'verify', '--print-certs', str(path)], capture_output=True, timeout=120, check=True)
        digests = re.findall(r'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', result.stdout.decode(errors='replace'))
        if not any(hmac.compare_digest(value.lower(), expected) for value in digests): raise ValueError()
    except (subprocess.SubprocessError, OSError, ValueError): raise b.Rejected('wrong_apk_signer', 400)


def route(h, b):
    path = urllib.parse.urlsplit(h.path).path
    if path == '/models/whisper-small-q5_0.bin' and h.command in ('GET', 'HEAD'):
        file = b.DATA / 'models' / 'whisper-small-q5_0.bin'
        if not file.is_file(): raise b.Rejected('not_found', 404)
        send_file(h, b, file, 'application/octet-stream')
        return True
    if path == '/v1/policy' and h.command == 'GET':
        response(h, 200, public_policy(b, h.server.signing_key)); return True
    if path == '/v1/badges' and h.command == 'GET':
        response(h, 200, public_badges(b, h.server.signing_key)); return True
    if path.startswith('/downloads/') and h.command in ('GET', 'HEAD'):
        name = path.removeprefix('/downloads/')
        rid = name[:-4]
        if not name.endswith('.apk') or len(rid) != 32 or any(ch not in '0123456789abcdef' for ch in rid): raise b.Rejected('not_found', 404)
        with b.db() as c: row = c.execute("SELECT size FROM app_releases WHERE id=? AND state='published'", (rid,)).fetchone()
        file = b.DATA / 'releases' / name
        if row is None or not file.is_file(): raise b.Rejected('not_found', 404)
        send_file(h, b, file, 'application/vnd.android.package-archive'); return True
    if path in ('/admin', '/admin/', '/admin/app.js', '/admin/style.css', '/admin/badges.js') and h.command == 'GET':
        name, mime = ('index.html', 'text/html; charset=utf-8') if path in ('/admin', '/admin/') else (path.rsplit('/', 1)[1], 'text/javascript; charset=utf-8' if path.endswith('.js') else 'text/css; charset=utf-8')
        response(h, 200, (WEB / name).read_bytes(), mime); return True
    if not path.startswith('/admin/api/'): return False
    if path == '/admin/api/login' and h.command == 'POST':
        with LOGIN_LOCK:
            now = time.monotonic()
            for ip in list(LOGIN_ATTEMPTS):
                while LOGIN_ATTEMPTS[ip] and LOGIN_ATTEMPTS[ip][0] < now - 900: LOGIN_ATTEMPTS[ip].popleft()
                if not LOGIN_ATTEMPTS[ip]: del LOGIN_ATTEMPTS[ip]
            attempts = LOGIN_ATTEMPTS[h.client_address[0]]
            if len(attempts) >= 8: raise b.Rejected('login_rate_limited', 429)
            attempts.append(now)
        origin(h, b); data = body(h, b); cfg = configuration(b)
        password = data.get('password', '')
        if not isinstance(password, str) or len(password) > 256 or not hmac.compare_digest(password_hash(password, cfg['salt']), cfg['password_hash']): raise b.Rejected('wrong_password', 403)
        with LOGIN_LOCK: LOGIN_ATTEMPTS.pop(h.client_address[0], None)
        token, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        with b.db() as c:
            c.execute('DELETE FROM owner_sessions WHERE expires<?', (int(time.time()),))
            c.execute('INSERT INTO owner_sessions VALUES(?,?,?)', (hashlib.sha256(token.encode()).hexdigest(), csrf, int(time.time()) + 43200)); audit(c, 'owner.login')
        response(h, 200, {'csrf': csrf}, cookie='gg_owner=' + token + '; Path=/admin; Secure; HttpOnly; SameSite=Strict; Max-Age=43200'); return True
    current = session(h, b)
    if h.command == 'POST':
        origin(h, b)
        if not hmac.compare_digest(h.headers.get('X-CSRF-Token', ''), current['csrf']): raise b.Rejected('csrf', 403)
    if path == '/admin/api/session' and h.command == 'GET': response(h, 200, {'csrf': current['csrf']}); return True
    if path == '/admin/api/state' and h.command == 'GET':
        with b.db() as c:
            settings = dict(c.execute('SELECT * FROM owner_settings WHERE id=1').fetchone())
            licenses = [dict(r) for r in c.execute('SELECT id,label,status,expires_at,device,created_at,bound_at,last_seen FROM licenses ORDER BY created_at DESC LIMIT 10000')]
            devices = [dict(r) for r in c.execute('SELECT * FROM app_devices ORDER BY last_seen DESC LIMIT 2000')]
            days = [dict(r) for r in c.execute('SELECT day,count(*) AS active FROM app_days GROUP BY day ORDER BY day DESC LIMIT 30')]
            versions = [dict(r) for r in c.execute('SELECT version,count(*) AS count FROM app_devices GROUP BY version ORDER BY count(*) DESC')]
            releases = [dict(r) for r in c.execute('SELECT * FROM app_releases ORDER BY created_at DESC LIMIT 200')]
            badges = [dict(r) for r in c.execute('SELECT * FROM project_badges ORDER BY updated_at DESC')]
            history = [dict(r) for r in c.execute('SELECT at,action,detail FROM owner_audit ORDER BY id DESC LIMIT 100')]
        response(h, 200, dict(settings=settings, licenses=licenses, devices=devices, days=days, versions=versions, releases=releases, badges=badges, audit=history, now=int(time.time()))); return True
    if h.command != 'POST': raise b.Rejected('not_found', 404)
    if path == '/admin/api/releases/upload': response(h, 200, upload(h, b)); return True
    data = body(h, b)
    if path == '/admin/api/licenses': result = license_action(b, data)
    elif path == '/admin/api/policy':
        require = data.get('require_key')
        if type(require) is not bool: raise b.Rejected('bad_request', 400)
        with b.db() as c:
            c.execute('UPDATE owner_settings SET require_key=?,revision=revision+1 WHERE id=1', (int(require),)); audit(c, 'policy.require_key', str(require))
        result = {'status': 'ok'}
    elif path == '/admin/api/badges':
        user_id, action = data.get('user_id'), data.get('action', 'save')
        if not isinstance(user_id, str) or not re.fullmatch(r'[1-9][0-9]{0,18}', user_id) or int(user_id) > 9223372036854775807: raise b.Rejected('bad_request', 400)
        role, label = data.get('type', 'supporter'), data.get('label', '')
        if role not in ('supporter', 'creator') or not isinstance(label, str) or len(label) > 120 or action not in ('save', 'delete'): raise b.Rejected('bad_request', 400)
        with b.db() as c:
            c.execute('BEGIN IMMEDIATE')
            if action == 'delete': c.execute('DELETE FROM project_badges WHERE user_id=?', (user_id,))
            else:
                exists = c.execute('SELECT 1 FROM project_badges WHERE user_id=?', (user_id,)).fetchone()
                if not exists and c.execute('SELECT count(*) FROM project_badges').fetchone()[0] >= 20000: raise b.Rejected('badge_limit', 409)
                c.execute('INSERT INTO project_badges(user_id,type,label,updated_at) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET type=excluded.type,label=excluded.label,updated_at=excluded.updated_at', (user_id, role, label, int(time.time())))
            c.execute('UPDATE owner_settings SET revision=revision+1 WHERE id=1')
            audit(c, 'badge.'+action, user_id); c.commit()
        result = {'status': 'ok'}
    elif path == '/admin/api/releases/action':
        rid, action = data.get('id'), data.get('action')
        with b.db() as c:
            c.execute('BEGIN IMMEDIATE')
            row = c.execute('SELECT * FROM app_releases WHERE id=?', (rid,)).fetchone()
            if row is None: raise b.Rejected('not_found', 404)
            if action == 'publish':
                if row['version_code'] <= 0: raise b.Rejected('invalid_apk', 400)
                latest = c.execute("SELECT max(version_code) FROM app_releases WHERE state='published'").fetchone()[0]
                if latest is not None and row['version_code'] < latest: raise b.Rejected('older_release', 409)
                c.execute("UPDATE app_releases SET state='published',published_at=? WHERE id=?", (int(time.time()), rid))
            elif action == 'withdraw': c.execute("UPDATE app_releases SET state='withdrawn' WHERE id=?", (rid,))
            elif action == 'delete' and row['state'] != 'published': c.execute('DELETE FROM app_releases WHERE id=?', (rid,))
            else: raise b.Rejected('bad_request', 400)
            audit(c, 'release.' + action, rid); c.execute('UPDATE owner_settings SET revision=revision+1 WHERE id=1'); c.commit()
        if action == 'delete': (b.DATA / 'releases' / (rid + '.apk')).unlink(missing_ok=True)
        result = {'status': 'ok'}
    elif path == '/admin/api/password':
        cfg = configuration(b); password = data.get('password', '')
        if not isinstance(password, str) or not 16 <= len(password) <= 256: raise b.Rejected('weak_password', 400)
        cfg['salt'] = secrets.token_hex(16); cfg['password_hash'] = password_hash(password, cfg['salt'])
        stage = b.DATA / 'owner.json.tmp'; stage.write_text(json.dumps(cfg)); stage.chmod(0o600); stage.replace(b.DATA / 'owner.json')
        with b.db() as c: c.execute('DELETE FROM owner_sessions'); audit(c, 'owner.password_changed')
        result = {'status': 'ok'}
    elif path == '/admin/api/logout':
        with b.db() as c: c.execute('DELETE FROM owner_sessions WHERE token=?', (current['token'],))
        response(h, 200, {'status': 'ok'}, cookie='gg_owner=; Path=/admin; Secure; HttpOnly; SameSite=Strict; Max-Age=0'); return True
    else: raise b.Rejected('not_found', 404)
    response(h, 200, result); return True


def send_file(h, b, file, mime):
    size = file.stat().st_size; start = 0; end = size - 1; partial = False
    requested = h.headers.get('Range')
    if requested:
        match = re.fullmatch(r'bytes=(\d+)-(\d*)', requested)
        if not match: raise b.Rejected('range_not_satisfiable', 416)
        start = int(match.group(1)); end = min(size - 1, int(match.group(2)) if match.group(2) else size - 1)
        if not 0 <= start <= end < size: raise b.Rejected('range_not_satisfiable', 416)
        partial = True
    h.send_response(206 if partial else 200)
    h.send_header('Content-Type', mime); h.send_header('Content-Length', str(end - start + 1)); h.send_header('Accept-Ranges', 'bytes'); h.send_header('X-Content-Type-Options', 'nosniff')
    if partial: h.send_header('Content-Range', 'bytes '+str(start)+'-'+str(end)+'/'+str(size))
    h.send_header('Connection', 'close'); h.end_headers(); h.close_connection = True
    if h.command == 'HEAD': return
    h.connection.settimeout(60)
    with file.open('rb') as f:
        f.seek(start); remaining = end - start + 1
        while remaining:
            chunk = f.read(min(1024 * 1024, remaining))
            if not chunk: break
            h.wfile.write(chunk); remaining -= len(chunk)
