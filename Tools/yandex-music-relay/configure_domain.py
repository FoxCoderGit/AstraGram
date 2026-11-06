"""Run as administrator on the relay server. Preserve existing Caddy routes and keys."""
import json
import os
import re
from pathlib import Path
import shutil
import signal
import ssl
import subprocess
import time
import urllib.request

DOMAIN = "music.codebyfox.ru"
config = Path('/etc/v380-caddy/Caddyfile')
original = config.read_text()
# The existing Caddy process explicitly uses the caddyfile adapter.
processes = []
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit():
        continue
    try:
        args = (proc / 'cmdline').read_bytes().decode().split('\0')
        if Path(args[0]).name == 'caddy' and '--config' in args and args[args.index('--config')+1] == str(config):
            processes.append(int(proc.name))
    except (OSError, UnicodeError):
        pass
if len(processes) != 1:
    raise RuntimeError('Caddy process/config mismatch')
ca = config.parent / 'ghostgram-music-ca.pem'
shutil.copyfile('/etc/ghostgram-music-relay/ca.pem', ca)
ca.chmod(0o644)
marker = '# BEGIN GhostGram Music managed site'
site = '''
# BEGIN GhostGram Music managed site
music.codebyfox.ru {
    log ghostgram_music {
        output discard
    }
    header Cache-Control "no-store"
    request_body {
        max_size 16KB
    }
    reverse_proxy https://127.0.0.1:8443 {
        header_up X-Ghost-Client-IP {remote_host}
        transport http {
            tls_trust_pool file /etc/v380-caddy/ghostgram-music-ca.pem
            tls_server_name 127.0.0.1
        }
    }
}
# END GhostGram Music managed site
'''
if DOMAIN in original and marker not in original:
    raise RuntimeError('Domain already managed elsewhere')
updated = re.sub(r'(?s)\n?# BEGIN GhostGram Music managed site.*?# END GhostGram Music managed site\n?', '', original) + '\n' + site
if 'log ghostgram_music_errors' not in updated:
    if not updated.lstrip().startswith('{'):
        raise RuntimeError('Expected existing global Caddy block')
    start = updated.index('{') + 1
    updated = updated[:start] + '\n    log ghostgram_music_errors {\n        include http.log.error.ghostgram_music\n        output discard\n    }\n' + updated[start:]
if updated != original:
    backup = config.with_name('Caddyfile.before-ghostgram-music')
    if not backup.exists():
        backup.write_text(original)
        backup.chmod(0o600)
    candidate = config.with_name('GhostGram-music.caddyfile')
    candidate.write_text(updated)
    candidate.chmod(config.stat().st_mode & 0o777)
    checked = subprocess.run(['/usr/local/bin/caddy', 'validate', '--config', str(candidate), '--adapter', 'caddyfile'], capture_output=True)
    if checked.returncode:
        candidate.unlink()
        raise RuntimeError('Candidate Caddy configuration rejected')
    os.replace(candidate, config)
    os.kill(processes[0], signal.SIGUSR1)
    print('Validated music virtual host added; Caddy reload requested without stopping existing services', flush=True)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
for attempt in range(45):
    try:
        with opener.open('https://' + DOMAIN + '/health', timeout=5) as response:
            if response.status == 200 and json.load(response).get('ok'):
                print('Public domain certificate, hostname and HTTPS relay health verified', flush=True)
                break
    except Exception:
        time.sleep(2)
else:
    raise RuntimeError('Public domain HTTPS is not ready; original config backup retained')
