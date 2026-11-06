"""Administer the user's dedicated music relay; SSH password is read from stdin only."""
from pathlib import Path
import base64
import hashlib
import json
import getpass
import ssl
import urllib.request
import urllib.error
import sys
import paramiko

HOST = "77.232.40.63"
PRIVATE = Path(__file__).resolve().parents[3] / "Private" / ".ghostgram"
KNOWN_HOSTS = PRIVATE / "music-relay-known-hosts"


class FirstConnection(paramiko.MissingHostKeyPolicy):
    def missing_host_key(self, client, hostname, key):
        fingerprint = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")
        client.get_host_keys().add(hostname, key.get_name(), key)
        PRIVATE.mkdir(parents=True, exist_ok=True)
        client.save_host_keys(str(KNOWN_HOSTS))
        print("Recorded first SSH host key: SHA256:" + fingerprint, flush=True)


def connect():
    print("SSH credential input pending", flush=True)
    password = getpass.getpass("SSH password: ") if sys.stdin.isatty() else sys.stdin.readline().rstrip("\r\n")
    if not password:
        raise RuntimeError("SSH credential input missing")
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    if KNOWN_HOSTS.exists():
        client.load_host_keys(str(KNOWN_HOSTS))
    client.set_missing_host_key_policy(FirstConnection())
    client.connect(HOST, username="root", password=password, timeout=15,
                   banner_timeout=15, auth_timeout=15, look_for_keys=False, allow_agent=False)
    return client


def run(client, command, timeout=45):
    _, output, error = client.exec_command(command, timeout=timeout)
    result = output.read().decode("utf-8", "replace")
    error.read()
    status = output.channel.recv_exit_status()
    if status:
        raise RuntimeError("Remote command failed: " + str(status))
    return result


def inspect(client):
    script = """import json,os,shutil,socket,subprocess,sys,urllib.request,urllib.error
result={'python':sys.version.split()[0],'platform':sys.platform,'tools':{x:bool(shutil.which(x)) for x in ['openssl','systemctl','nginx','caddy','ufw']}}
ports=subprocess.run(['ss','-H','-ltn'],capture_output=True,text=True,check=True).stdout
result['tcp_listen_ports']=sorted(set(int(x.split()[3].rsplit(':',1)[1]) for x in ports.splitlines()))
try:
 r=urllib.request.urlopen('https://api.music.yandex.net/account/status',timeout=20)
 result['yandex_api_status']=r.status
 r.close()
except urllib.error.HTTPError as e:result['yandex_api_status']=e.code
except Exception as e:result['yandex_api_error']=type(e).__name__
result['existing_relay']=os.path.exists('/opt/ghostgram-music-relay')
print(json.dumps(result))
"""
    print(run(client, "python3 - <<'GHOST_MUSIC_INSPECT'\n" + script + "GHOST_MUSIC_INSPECT"), flush=True)


def deploy(client):
    print("Preparing the dedicated service directory", flush=True)
    check = run(client, "test ! -d /opt/ghostgram-music-relay || test -f /opt/ghostgram-music-relay/.ghostgram-owned")
    run(client, "id -u ghostgram-music >/dev/null 2>&1 || useradd --system --home /var/lib/ghostgram-music-relay --shell /usr/sbin/nologin ghostgram-music")
    run(client, "install -d -m 755 /opt/ghostgram-music-relay")
    run(client, "install -d -m 700 -o ghostgram-music -g ghostgram-music /var/lib/ghostgram-music-relay")
    run(client, "install -d -m 700 /etc/ghostgram-music-relay")
    source = Path(__file__).parent
    with client.open_sftp() as sftp:
        for name in ("relay.py", "test_relay.py"):
            sftp.put(str(source / name), "/opt/ghostgram-music-relay/" + name)
        sftp.put(str(source.parents[1] / "TMessagesProj_Ghost/src/main/assets/license/lease-public.der"), "/opt/ghostgram-music-relay/lease-public.der")
        with sftp.open("/opt/ghostgram-music-relay/.ghostgram-owned", "w") as output:
            output.write("GhostGram dedicated music relay\n")
    print("Running relay security and HTTPS tests before enabling the service", flush=True)
    try:
        run(client, "/usr/bin/python3 -I /opt/ghostgram-music-relay/test_relay.py > /opt/ghostgram-music-relay/test-report.txt 2>&1")
    except RuntimeError:
        print(run(client, "tail -60 /opt/ghostgram-music-relay/test-report.txt"), flush=True)
        raise
    print(run(client, "tail -4 /opt/ghostgram-music-relay/test-report.txt"), flush=True)
    print("Preparing a private CA and an IP-address TLS certificate", flush=True)
    script = r'''import os,pathlib,subprocess
ca=pathlib.Path('/etc/ghostgram-music-relay')
data=pathlib.Path('/var/lib/ghostgram-music-relay')
def run(*args):subprocess.run(args,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
if not (ca/'ca.key').exists():
 run('openssl','req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-keyout',str(ca/'ca.key'),'-out',str(ca/'ca.pem'),'-days','3650','-subj','/CN=GhostGram Music Relay CA','-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign')
 os.chmod(ca/'ca.key',0o600)
if not (data/'server.key').exists():
 run('openssl','req','-new','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-keyout',str(data/'server.key'),'-out',str(data/'server.csr'),'-subj','/CN=77.232.40.63')
 (ca/'server.ext').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=IP:77.232.40.63,IP:127.0.0.1\n')
 run('openssl','x509','-req','-in',str(data/'server.csr'),'-CA',str(ca/'ca.pem'),'-CAkey',str(ca/'ca.key'),'-CAcreateserial','-out',str(data/'server-leaf.pem'),'-days','825','-sha256','-extfile',str(ca/'server.ext'))
 (data/'server.pem').write_bytes((data/'server-leaf.pem').read_bytes()+(ca/'ca.pem').read_bytes())
 os.chmod(data/'server.key',0o600)
print('TLS identity ready; private keys remain on the server')
'''
    print(run(client, "python3 - <<'GHOST_MUSIC_CERT'\n" + script + "GHOST_MUSIC_CERT"), flush=True)
    run(client, "chown -R ghostgram-music:ghostgram-music /var/lib/ghostgram-music-relay")
    with client.open_sftp() as sftp:
        sftp.put(str(source / "ghostgram-music-relay.service"), "/etc/systemd/system/ghostgram-music-relay.service")
        sftp.get("/etc/ghostgram-music-relay/ca.pem", str(source / "relay-ca.pem"))
    pairing = json.loads(run(client, "runuser -u ghostgram-music -- /usr/bin/python3 -I /opt/ghostgram-music-relay/relay.py --state /var/lib/ghostgram-music-relay/state.json --issue-pairing"))
    PRIVATE.mkdir(parents=True, exist_ok=True)
    (PRIVATE / "music-relay-pairing.json").write_text(json.dumps({"server": HOST, "port": 8443, **pairing}, indent=2), encoding="utf-8")
    run(client, "systemctl daemon-reload")
    run(client, "systemctl enable ghostgram-music-relay.service")
    run(client, "systemctl restart ghostgram-music-relay.service")
    status = run(client, "systemctl is-active ghostgram-music-relay.service").strip()
    if status != "active":
        raise RuntimeError("Relay service not active")
    if "Status: active" in run(client, "ufw status"):
        run(client, "ufw allow 8443/tcp comment 'GhostGram Music Relay'")
    context = ssl.create_default_context(cafile=str(source / "relay-ca.pem"))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    with opener.open("https://" + HOST + ":8443/health", timeout=20) as response:
        if response.status != 200:
            raise RuntimeError("Public HTTPS check failed")
    print("Dedicated relay active; authenticated HTTPS endpoint on port 8443 verified", flush=True)
    print("One-time pairing code saved privately; no credential was printed or embedded in the app", flush=True)


def probe(client):
    source=Path(__file__).parent
    pairing=json.loads(run(client,"runuser -u ghostgram-music -- /usr/bin/python3 -I /opt/ghostgram-music-relay/relay.py --state /var/lib/ghostgram-music-relay/state.json --issue-pairing"))
    context=ssl.create_default_context(cafile=str(source/"relay-ca.pem"))
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
    base="https://"+HOST+":8443"
    with opener.open(urllib.request.Request(base+"/pair",data=json.dumps({"code":pairing["code"]}).encode(),headers={"Content-Type":"application/json"}),timeout=20) as response:
        token=json.load(response)["token"]
    try:
        request=urllib.request.Request(base+"/relay",headers={"X-Ghost-Music-Token":token,"X-Ghost-Upstream-Url":"https://api.music.yandex.net/account/status"})
        with opener.open(request,timeout=40) as response:
            if response.status!=200 or "result" not in json.load(response):raise RuntimeError("Upstream API check failed")
        print("Authenticated public HTTPS forwarding to Yandex Music: passed",flush=True)
        try:
            opener.open(urllib.request.Request(base+"/relay",headers={"X-Ghost-Music-Token":token,"X-Ghost-Upstream-Url":"https://127.0.0.1/"}),timeout=10)
            raise RuntimeError("Private target was not blocked")
        except urllib.error.HTTPError as response:
            if response.code!=400:raise
        print("Private-address forwarding blocked: passed",flush=True)
    finally:
        digest=hashlib.sha256(token.encode()).hexdigest()
        script="""import importlib.util
spec=importlib.util.spec_from_file_location('relay','/opt/ghostgram-music-relay/relay.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
state=module.State('/var/lib/ghostgram-music-relay/state.json')
with state.transaction() as data:data['tokens'].pop('"""+digest+"""',None)
print('Temporary test credential revoked')
"""
        print(run(client,"runuser -u ghostgram-music -- python3 - <<'GHOST_MUSIC_REVOKE'\n"+script+"GHOST_MUSIC_REVOKE"),flush=True)
    with client.open_sftp() as sftp:
        sftp.get('/opt/ghostgram-music-relay/test-report.txt',str(source.parents[2]/'Reports'/'music-relay-server-tests.log'))


def domain_inspect(client):
    script=r'''import importlib.util,json,pathlib,re,subprocess
result={'cryptography':bool(importlib.util.find_spec('cryptography'))}
unit=subprocess.run(['systemctl','show','caddy','--property=ExecStart','--value'],capture_output=True,text=True,check=True).stdout
match=re.search(r'--config[ =]+([^ ;}]+)',unit)
result['caddy_config']=match.group(1) if match else None
if match:
 p=pathlib.Path(match.group(1));text=p.read_text()
 result['config_format']='json' if text.lstrip().startswith('{') else 'caddyfile'
 result['has_music_domain']='music.codebyfox.ru' in text
 result['has_import']=bool(re.search(r'(?m)^\s*import\s',text))
result['unit_flags']=re.findall(r'(?:--?)[a-z-]+',unit)
result['unit_paths']=re.findall(r'/[A-Za-z0-9_./-]+',unit)
result['main_pid']=subprocess.run(['systemctl','show','caddy','--property=MainPID','--value'],capture_output=True,text=True).stdout.strip()
ports=subprocess.run(['ss','-Hltnp'],capture_output=True,text=True).stdout
result['web_listeners']=[line for line in ports.splitlines() if re.search(r':(?:80|443|2019)\s',line)]
result['caddy_processes']=[]
for proc in pathlib.Path('/proc').iterdir():
 if not proc.name.isdigit():continue
 try:
  args=(proc/'cmdline').read_bytes().decode().split('\0')
  if args and pathlib.Path(args[0]).name=='caddy':result['caddy_processes'].append({'pid':proc.name,'args':args,'cwd':str((proc/'cwd').resolve())})
 except (OSError,UnicodeError):pass
cp=pathlib.Path('/etc/v380-caddy/Caddyfile')
if cp.exists():
 text=cp.read_text();result['config_format']='json' if text.lstrip().startswith('{') else 'caddyfile';result['has_music_domain']='music.codebyfox.ru' in text;result['admin_off']=bool(re.search(r'admin\s+off',text));result['imports']=re.findall(r'(?m)^\s*import\s+([^\n]+)',text)
result['caddyfile_exists']=pathlib.Path('/etc/caddy/Caddyfile').exists()
result['caddy_version']=subprocess.run(['caddy','version'],capture_output=True,text=True).stdout.split()[0]
print(json.dumps(result))
'''
    print(run(client,"python3 - <<'GHOST_MUSIC_DOMAIN_INSPECT'\n"+script+"GHOST_MUSIC_DOMAIN_INSPECT"),flush=True)


def main():
    with connect() as client:
        if len(sys.argv) == 2 and sys.argv[1] == "inspect":
            inspect(client)
        elif len(sys.argv) == 2 and sys.argv[1] == "deploy":
            deploy(client)
        elif len(sys.argv) == 2 and sys.argv[1] == "probe":
            probe(client)
        elif len(sys.argv) == 2 and sys.argv[1] == "domain-inspect":
            domain_inspect(client)
        elif len(sys.argv) == 2 and sys.argv[1] == "domain-deploy":
            with client.open_sftp() as sftp:
                sftp.put(str(Path(__file__).with_name("configure_domain.py")), "/opt/ghostgram-music-relay/configure_domain.py")
            print(run(client, "python3 -I /opt/ghostgram-music-relay/configure_domain.py", timeout=240), flush=True)
        elif len(sys.argv) == 2 and sys.argv[1] == "test":
            with client.open_sftp() as sftp:
                sftp.put(str(Path(__file__).with_name("test_relay.py")), "/opt/ghostgram-music-relay/test_relay.py")
            print(run(client, "python3 -I /opt/ghostgram-music-relay/test_relay.py > /opt/ghostgram-music-relay/test-report.txt 2>&1 && tail -4 /opt/ghostgram-music-relay/test-report.txt"), flush=True)
            with client.open_sftp() as sftp:
                sftp.get('/opt/ghostgram-music-relay/test-report.txt', str(Path(__file__).resolve().parents[3] / 'Reports/ghost98-server-tests.log'))
        elif len(sys.argv) == 2 and sys.argv[1] == "status":
            script = """import json,pathlib,subprocess,time
data=json.loads(pathlib.Path('/var/lib/ghostgram-music-relay/state.json').read_text())
print(json.dumps({'active_tokens':sum(v>time.time() for v in data['tokens'].values()),'service_active':subprocess.run(['systemctl','is-active','ghostgram-music-relay'],capture_output=True,text=True).stdout.strip()=='active'}))
"""
            print(run(client, "python3 - <<'GHOST_MUSIC_STATUS'\n" + script + "GHOST_MUSIC_STATUS"), flush=True)
        else:
            raise RuntimeError("Unknown operation")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Relay operation failed: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
