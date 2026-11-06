"""Provision domain TLS on port 9443 while keeping the legacy IP certificate intact."""
from pathlib import Path
import shlex
import deploy

deploy.IDENTITY = deploy.ROOT.parent / 'Private/.ghostgram'
with deploy.connect() as ssh:
    check="import socket; s=socket.socket(); s.bind(('0.0.0.0',80)); s.close(); print('ACME port available')"
    print(deploy.run(ssh,'python3 -c '+shlex.quote(check)).strip(),flush=True)
    if not deploy.run(ssh,'command -v certbot || true').strip():
        deploy.run(ssh,'apt-get -qq update && DEBIAN_FRONTEND=noninteractive apt-get -y -qq install --no-install-recommends certbot')
        print('Certbot installed',flush=True)
    print(deploy.run(ssh,'certbot certonly --standalone --non-interactive --agree-tos --register-unsafely-without-email --cert-name veylgram-services -d update.codebyfox.ru -d verification.codebyfox.ru').strip(),flush=True)
    hook='''#!/bin/sh
set -eu
[ "${RENEWED_LINEAGE:-/etc/letsencrypt/live/veylgram-services}" = /etc/letsencrypt/live/veylgram-services ] || exit 0
install -o ghostgram-license -g ghostgram-license -m 600 /etc/letsencrypt/live/veylgram-services/fullchain.pem /var/lib/ghostgram-license/domains.crt
install -o ghostgram-license -g ghostgram-license -m 600 /etc/letsencrypt/live/veylgram-services/privkey.pem /var/lib/ghostgram-license/domains.key
systemctl try-restart ghostgram-license.service
'''
    with ssh.open_sftp() as sftp:
        path='/etc/letsencrypt/renewal-hooks/deploy/veylgram-services'
        with sftp.open(path,'w') as output:output.write(hook)
        sftp.chmod(path,0o700)
    deploy.run(ssh,'/etc/letsencrypt/renewal-hooks/deploy/veylgram-services && systemctl enable --now certbot.timer')
    print('Domain certificate installed; renewal timer enabled; legacy IP certificate preserved',flush=True)
