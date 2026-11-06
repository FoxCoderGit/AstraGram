"""Deploy owner console with an on-server DB/code backup, retaining all existing keys."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import time
import requests
from deploy import connect, run, ROOT, IDENTITY
import admin_web


def main():
    folder = Path(__file__).parent
    tools = Path.home() / 'AppData/Local/Android/Sdk/build-tools/36.0.0/lib/apksigner.jar'
    java = 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe'
    trusted_apk = Path(os.environ.get('GHOST_TRUSTED_APK', ROOT / 'Tools/GhostGram-distribution.apk'))
    if not trusted_apk.is_file():
        candidates = list((ROOT / 'Tools').glob('GhostGram-*.apk'))
        if not candidates:
            raise FileNotFoundError('Build a signed GhostGram APK first or set GHOST_TRUSTED_APK.')
        trusted_apk = max(candidates, key=lambda path: path.stat().st_mtime)
    output = subprocess.check_output([java, '-jar', str(tools), 'verify', '--print-certs', str(trusted_apk)]).decode()
    signer = re.search(r'certificate SHA-256 digest:\s*([a-f0-9]{64})', output).group(1)
    stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
    access_file = IDENTITY / 'owner-panel.json'
    baseline_code = "import sqlite3,json; c=sqlite3.connect('file:/var/lib/ghostgram-license/licenses.sqlite?mode=ro',uri=True); print(json.dumps(c.execute('SELECT count(*),sum(status=?),sum(device IS NOT NULL) FROM licenses',('active',)).fetchone())); c.close()"
    with connect() as ssh:
        before = run(ssh, 'python3 -c ' + shlex.quote(baseline_code)).strip()
        backup = "from pathlib import Path; import sqlite3; p=Path('/var/lib/ghostgram-license/backups')/" + repr(stamp) + "; p.mkdir(parents=True,mode=0o700); a=sqlite3.connect('/var/lib/ghostgram-license/licenses.sqlite'); b=sqlite3.connect(str(p/'licenses.sqlite')); a.backup(b); b.close(); a.close(); (p/'licenses.sqlite').chmod(0o600)"
        run(ssh, 'runuser -u ghostgram-license -- python3 -c ' + shlex.quote(backup))
        run(ssh, 'tar -czf /root/ghostgram-license-code-' + stamp + '.tar.gz -C /opt/ghostgram-license .')
        print('On-server database/code backups saved; existing license rows:', before)
        if not run(ssh, 'command -v java || true').strip():
            run(ssh, 'apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -y -qq install --no-install-recommends openjdk-21-jre-headless')
            print('APK signature verifier runtime installed')
        with ssh.open_sftp() as s:
            try: s.mkdir('/opt/ghostgram-license/web', 0o755)
            except OSError: pass
            for name in ('server.py', 'admin.py', 'admin_web.py', 'apk_manifest.py'):
                s.put(str(folder / name), '/opt/ghostgram-license/' + name + '.new')
                s.posix_rename('/opt/ghostgram-license/' + name + '.new', '/opt/ghostgram-license/' + name)
            for name in ('index.html', 'app.js', 'style.css', 'badges.js'):
                s.put(str(folder / 'web' / name), '/opt/ghostgram-license/web/' + name)
            s.put(str(tools), '/opt/ghostgram-license/apksigner.jar')
            model = ROOT / 'Tools/optional-speech-models/ggml-small-q5_0.bin'
            model_hash = '23ad5215d37677f8eb8cbc41b4d0d4e451229f811be5206ff116dd9337eba748'
            target = '/var/lib/ghostgram-license/models/whisper-small-q5_0.bin'
            current = run(ssh, 'sha256sum ' + target + ' 2>/dev/null || true').split()
            if not current or current[0] != model_hash:
                if hashlib.sha256(model.read_bytes()).hexdigest() != model_hash: raise RuntimeError('Small model integrity mismatch')
                run(ssh, 'install -d -m 700 -o ghostgram-license -g ghostgram-license /var/lib/ghostgram-license/models')
                s.put(str(model), target + '.new')
                if run(ssh, 'sha256sum ' + target + '.new').split()[0] != model_hash: raise RuntimeError('Uploaded model integrity mismatch')
                s.chmod(target + '.new', 0o600)
                s.chown(target + '.new', int(run(ssh, 'id -u ghostgram-license')), int(run(ssh, 'id -g ghostgram-license')))
                s.posix_rename(target + '.new', target)
                print('Verified on-demand Small model uploaded')
            try:
                with s.open('/var/lib/ghostgram-license/owner.json') as f: cfg = json.load(f)
                if cfg.get('apk_signer_sha256') not in (None, signer): raise RuntimeError('Configured APK signing identity differs; aborting')
            except FileNotFoundError:
                password = secrets.token_urlsafe(30)
                salt = secrets.token_hex(16)
                cfg = dict(salt=salt, password_hash=admin_web.password_hash(password, salt))
                IDENTITY.mkdir(exist_ok=True)
                access_file.write_text(json.dumps(dict(url='https://93.88.204.2:9443/admin/', password=password), indent=2), encoding='utf-8')
            cfg['apk_signer_sha256'] = signer
            with s.open('/var/lib/ghostgram-license/owner.json.new', 'w') as f: f.write(json.dumps(cfg))
            s.chmod('/var/lib/ghostgram-license/owner.json.new', 0o600)
            s.chown('/var/lib/ghostgram-license/owner.json.new', int(run(ssh, 'id -u ghostgram-license')), int(run(ssh, 'id -g ghostgram-license')))
            s.posix_rename('/var/lib/ghostgram-license/owner.json.new', '/var/lib/ghostgram-license/owner.json')
            unit = (folder / 'ghostgram-license.service').read_text().replace('MemoryMax=256M', 'MemoryMax=512M')
            with s.open('/etc/systemd/system/ghostgram-license.service', 'w') as f: f.write(unit)
        run(ssh, 'python3 -m py_compile /opt/ghostgram-license/server.py /opt/ghostgram-license/admin_web.py')
        try:
            run(ssh, 'systemctl daemon-reload && systemctl restart ghostgram-license && systemctl is-active ghostgram-license')
            for _ in range(8):
                try:
                    r = requests.get('https://93.88.204.2:9443/health', verify=str(ROOT / 'TMessagesProj_Ghost/src/main/assets/license/server.crt'), timeout=8)
                    r.raise_for_status(); break
                except requests.RequestException: time.sleep(1)
            else: raise RuntimeError('Health check failed')
        except Exception:
            run(ssh, 'tar -xzf /root/ghostgram-license-code-' + stamp + '.tar.gz -C /opt/ghostgram-license && systemctl restart ghostgram-license')
            raise
        after = run(ssh, 'python3 -c ' + shlex.quote(baseline_code)).strip()
        if before != after: raise RuntimeError('License aggregate changed unexpectedly')
        print('PASS: service healthy; existing licenses and bindings retained')
        print('Owner credentials saved outside project:', access_file)


if __name__ == '__main__': main()
