"""Move the relay's public TLS identity to vpn.codebyfox.ru, preserving Xray/443."""
import importlib.util
import shlex
from pathlib import Path

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("deploy", root / "Tools/license-server/deploy.py")
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
deploy.IDENTITY = root.parent / "Private/.ghostgram"

remote = r'''
import json,hashlib,os,shutil,socket,subprocess,time,grp,urllib.request
from pathlib import Path
def run(*args): return subprocess.check_output(args,stderr=subprocess.STDOUT,text=True)
def pin():
    public=subprocess.check_output(['openssl','x509','-in','/var/lib/ghostgram-license/domains.crt','-pubkey','-noout'])
    der=subprocess.check_output(['openssl','pkey','-pubin','-outform','DER'],input=public)
    return hashlib.sha256(der).hexdigest()
assert '93.88.204.2' in {r[4][0] for r in socket.getaddrinfo('vpn.codebyfox.ru',9446,type=socket.SOCK_STREAM)}
before=pin()
config=Path('/etc/astra-relay/config.json')
backup=config.with_name('config.before-domain-'+time.strftime('%Y%m%d-%H%M%S')+'.json')
shutil.copy2(config,backup)
print(run('certbot','certonly','--standalone','--non-interactive','--agree-tos','--register-unsafely-without-email','--expand','--reuse-key','--cert-name','veylgram-services','-d','update.codebyfox.ru','-d','verification.codebyfox.ru','-d','vpn.codebyfox.ru'),flush=True)
run('/etc/letsencrypt/renewal-hooks/deploy/veylgram-services')
assert pin()==before, 'Unexpected TLS public key rotation; keep existing relay pins valid'
data=json.loads(config.read_text());data['endpoint']['host']='vpn.codebyfox.ru'
temporary=config.with_suffix('.domain-new');temporary.write_text(json.dumps(data,indent=2));temporary.chmod(0o640);os.chown(temporary,0,grp.getgrnam('astra-relay').gr_gid);os.replace(temporary,config)
try:
    run('systemctl','restart','astra-relay.service')
    for attempt in range(10):
        try:
            with urllib.request.urlopen('https://vpn.codebyfox.ru:9446/healthz',timeout=3) as response:
                assert response.status==200
            break
        except Exception:
            if attempt==9:raise
            time.sleep(1)
except Exception:
    shutil.copy2(backup,config);os.chown(config,0,grp.getgrnam('astra-relay').gr_gid);config.chmod(0o640)
    run('systemctl','restart','astra-relay.service')
    raise
print('Relay domain configured; certificate key preserved; Xray/443 unchanged',flush=True)
'''

with deploy.connect() as client:
    _, out, err = client.exec_command("python3 -c " + shlex.quote(remote), timeout=180)
    result = out.read().decode()
    status = out.channel.recv_exit_status()
    if status:
        raise RuntimeError("Domain provisioning failed: " + result[-2000:] + err.read().decode()[-500:])
    print(result)
