"""Deploy only the isolated GhostGram service using the local SSH identity."""
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[2]
IDENTITY = Path.home() / ".ghostgram"


def connect():
    c = paramiko.SSHClient(); c.load_host_keys(str(IDENTITY / "known_hosts"))
    c.set_missing_host_key_policy(paramiko.RejectPolicy())
    c.connect("93.88.204.2", username="root", key_filename=str(IDENTITY / "deploy_id"), timeout=20, look_for_keys=False, allow_agent=False)
    return c


def run(c, command):
    _, out, err = c.exec_command(command)
    output = out.read().decode(); error = err.read().decode()
    status = out.channel.recv_exit_status()
    if status: raise RuntimeError(f"Remote operation failed ({status}): {error[:500]}")
    return output


def deploy():
    with connect() as c:
        run(c, "id ghostgram-license >/dev/null 2>&1 || useradd --system --home /var/lib/ghostgram-license --shell /usr/sbin/nologin ghostgram-license")
        run(c, "install -d -m 755 /opt/ghostgram-license; install -d -m 700 -o ghostgram-license -g ghostgram-license /var/lib/ghostgram-license")
        with c.open_sftp() as s:
            for name in ("server.py", "admin.py", "admin_web.py", "apk_manifest.py"):
                s.put(str(Path(__file__).parent / name), "/opt/ghostgram-license/" + name)
            try: s.mkdir('/opt/ghostgram-license/web', 0o755)
            except OSError: pass
            for name in ('index.html','app.js','style.css','badges.js'):
                s.put(str(Path(__file__).parent/'web'/name), '/opt/ghostgram-license/web/'+name)
            s.put(str(Path(__file__).parent / "ghostgram-license.service"), "/etc/systemd/system/ghostgram-license.service")
        run(c, "test -f /var/lib/ghostgram-license/server.key || openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 1825 -keyout /var/lib/ghostgram-license/server.key -out /var/lib/ghostgram-license/server.crt -subj '/CN=GhostGram License' -addext 'subjectAltName=IP:93.88.204.2' >/dev/null 2>&1")
        run(c, "test -f /var/lib/ghostgram-license/lease-signing.pem || openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /var/lib/ghostgram-license/lease-signing.pem >/dev/null 2>&1")
        run(c, "openssl pkey -in /var/lib/ghostgram-license/lease-signing.pem -pubout -outform DER -out /var/lib/ghostgram-license/lease-public.der; chown -R ghostgram-license:ghostgram-license /var/lib/ghostgram-license; chmod 600 /var/lib/ghostgram-license/*; python3 -m py_compile /opt/ghostgram-license/server.py /opt/ghostgram-license/admin.py")
        target = ROOT / "TMessagesProj_Ghost/src/main/assets/license"; target.mkdir(parents=True, exist_ok=True)
        with c.open_sftp() as s:
            for name in ("server.crt", "lease-public.der"): s.get("/var/lib/ghostgram-license/" + name, str(target / name))
        run(c, "systemctl daemon-reload; systemctl enable ghostgram-license.service >/dev/null 2>&1; systemctl restart ghostgram-license.service")
        print("Deployed independent service on port 9443; only public certificate and lease verification key copied to APK assets.")


if __name__ == "__main__": deploy()
