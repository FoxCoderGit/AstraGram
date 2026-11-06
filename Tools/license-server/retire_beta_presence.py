"""Remove the retired endpoint, its module and device ACL without altering licenses."""
import json
import shlex
import time
from pathlib import Path
import requests
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from deploy import connect,run,ROOT
import server


def main():
    stamp=time.strftime('%Y%m%d-%H%M%S',time.gmtime())
    query="import sqlite3,json; c=sqlite3.connect('file:/var/lib/ghostgram-license/licenses.sqlite?mode=ro',uri=True); print(json.dumps(c.execute('SELECT count(*),sum(device IS NOT NULL) FROM licenses').fetchone()))"
    with connect() as ssh:
        before=run(ssh,'python3 -c '+shlex.quote(query)).strip()
        archive='/root/ghostgram-license-before-retire-'+stamp+'.tar.gz'
        run(ssh,'tar -czf '+archive+' -C /opt/ghostgram-license .')
        with ssh.open_sftp() as s:
            target='/opt/ghostgram-license/server.py';s.put(str(Path(__file__).parent/'server.py'),target+'.new');s.posix_rename(target+'.new',target)
            cfg_path='/var/lib/ghostgram-license/owner.json'
            with s.open(cfg_path) as f:cfg=json.load(f)
            cfg.pop('beta_owner_devices',None);cfg.pop('beta_presence_enabled',None)
            with s.open(cfg_path+'.new','w') as f:f.write(json.dumps(cfg))
            s.chmod(cfg_path+'.new',0o600);s.chown(cfg_path+'.new',int(run(ssh,'id -u ghostgram-license')),int(run(ssh,'id -g ghostgram-license')));s.posix_rename(cfg_path+'.new',cfg_path)
            try:s.remove('/opt/ghostgram-license/beta_presence.py')
            except FileNotFoundError:pass
            try:
                for name in s.listdir('/opt/ghostgram-license/__pycache__'):
                    if name.startswith('beta_presence.') and name.endswith('.pyc') and '/' not in name:s.remove('/opt/ghostgram-license/__pycache__/'+name)
            except FileNotFoundError:pass
        run(ssh,'python3 -m py_compile /opt/ghostgram-license/server.py && systemctl restart ghostgram-license')
        after=run(ssh,'python3 -c '+shlex.quote(query)).strip();assert before==after
    cert=str(ROOT/'TMessagesProj_Ghost/src/main/assets/license/server.crt');url='https://93.88.204.2:9443'
    for _ in range(10):
        try:r=requests.get(url+'/health',verify=cert,timeout=8);r.raise_for_status();break
        except requests.RequestException:time.sleep(1)
    else:raise RuntimeError('Service health check failed')
    device=ec.generate_private_key(ec.SECP256R1());public=server.b64(device.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo))
    rejected=requests.post(url+'/v1/challenge',json={'public_key':public,'purpose':'beta_presence'},verify=cert,timeout=15)
    assert rejected.json().get('error')=='bad_request'
    rejected=requests.post(url+'/v1/beta_presence',json={'public_key':public},verify=cert,timeout=15)
    assert rejected.status_code==404
    print('PASS: service healthy; retired activity route absent, purpose rejected, module/ACL removed, license rows and bindings unchanged')


if __name__=='__main__':main()
