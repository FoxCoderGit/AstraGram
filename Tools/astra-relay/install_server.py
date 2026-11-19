"""Root-only installer, run from the reviewed staging directory after Go tests/build.

Preserves any independent changes to the existing admin backend. Does not touch
accounts, license database, releases, firewall, port 443 or unrelated services.
Credentials are supplied by systemd, never by this source or an APK.
"""
import hashlib
import os
from pathlib import Path
import py_compile
import shutil
import subprocess
import time

HERE=Path(__file__).resolve().parent

def run(*args):subprocess.run(args,check=True)
def atomic(path,raw,mode=0o644):
    path=Path(path);tmp=path.with_name(path.name+'.astra-new')
    tmp.write_bytes(raw);tmp.chmod(mode);os.replace(tmp,path)

def main():
    if os.geteuid()!=0:raise SystemExit('Run on VPS as root')
    stamp=time.strftime('%Y%m%d-%H%M%S',time.gmtime())
    service=Path('/opt/ghostgram-license');admin=service/'admin_web.py';index=service/'web/index.html'
    source=admin.read_text();html=index.read_text()
    if "'/admin/api/relay'" not in source:
        baseline=(HERE/'admin_web.baseline.py').read_text()
        updated=(HERE/'admin_web.relay.py').read_text()
        # Apply only the three new route blocks. Refuse ambiguous/outdated input.
        import difflib
        old=baseline.splitlines(keepends=True);new=updated.splitlines(keepends=True)
        for tag,a,b,c,d in difflib.SequenceMatcher(None,old,new,autojunk=False).get_opcodes():
            if tag=='equal':continue
            before=''.join(old[a:b]);after=''.join(new[c:d])
            if not before:
                anchor=''.join(old[max(0,a-1):a]);replacement=anchor+after
            else:anchor=before;replacement=after
            if source.count(anchor)!=1:raise RuntimeError('Admin backend changed; route patch requires review')
            source=source.replace(anchor,replacement,1)
    if '/admin/relay.js' not in html:
        anchor='<footer id="updated"></footer></div></div>'
        if html.count(anchor)!=1:raise RuntimeError('Admin layout changed; requires review')
        html=html.replace(anchor,anchor+'\n<script defer src="/admin/relay.js"></script>',1)
    staged=HERE/'admin_web.install.py';staged.write_text(source);py_compile.compile(str(staged),doraise=True)
    if 'astra-relay:' not in Path('/etc/passwd').read_text():
        run('useradd','--system','--user-group','--home-dir','/var/lib/astra-relay','--shell','/usr/sbin/nologin','astra-relay')
    run('usermod','-a','-G','astra-relay','ghostgram-license')
    run('install','-d','-m','755','/opt/astra-relay','/etc/astra-relay')
    backup=service/'backups'/('astra-relay-'+stamp);backup.mkdir(parents=True,mode=0o700)
    for file in (admin,index,service/'relay_control.py',service/'web/relay.js'):
        if file.exists():shutil.copy2(file,backup/file.name)
    binary=Path('/opt/astra-relay/astra-relay')
    if binary.exists():shutil.copy2(binary,backup/'astra-relay')
    atomic(binary,(HERE/'astra-relay').read_bytes(),0o755)
    config=Path('/etc/astra-relay/config.json')
    if not config.exists():atomic(config,(HERE/'config.example.json').read_bytes())
    atomic('/etc/systemd/system/astra-relay.service',(HERE/'astra-relay.service').read_bytes())
    run('systemctl','daemon-reload');run('systemctl','enable','astra-relay.service');run('systemctl','restart','astra-relay.service')
    run('systemctl','is-active','--quiet','astra-relay.service')
    atomic(admin,source.encode());atomic(index,html.encode())
    atomic(service/'relay_control.py',(HERE/'relay_control.py').read_bytes())
    atomic(service/'web/relay.js',(HERE/'relay.js').read_bytes())
    hook=Path('/etc/letsencrypt/renewal-hooks/deploy/veylgram-services')
    if hook.is_file() and 'try-restart astra-relay.service' not in hook.read_text():
        shutil.copy2(hook,backup/'certificate-renewal-hook')
        atomic(hook,hook.read_bytes()+b'\nsystemctl try-restart astra-relay.service 2>/dev/null || true\n',0o700)
    run('systemctl','restart','ghostgram-license.service');run('systemctl','is-active','--quiet','ghostgram-license.service')
    print('AstraRelay installed; admin routes patched; independent admin changes preserved; backup:',backup)
    print('Binary SHA-256:',hashlib.sha256(binary.read_bytes()).hexdigest())

if __name__=='__main__':main()
