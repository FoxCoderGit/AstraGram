"""Deploy the reviewed update contract only; back up code/DB and roll back code on failure.
Does not upload/publish an APK or change access policy, credentials, or license rows.
"""
import ast
import hashlib
import json
from pathlib import Path
import shlex
import time
import deploy

folder = Path(__file__).resolve().parent
if not (deploy.IDENTITY / 'known_hosts').is_file():
    deploy.IDENTITY = deploy.ROOT.parent / 'Private/.ghostgram'
files = ('server.py', 'admin_web.py', 'apk_manifest.py', 'web/app.js', 'web/index.html', 'web/style.css', 'web/badges.js')
stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
backup = '/opt/ghostgram-license/backups/updates-' + stamp


def definitions(data):
    return {node.name: ast.dump(node, include_attributes=False) for node in ast.parse(data).body if isinstance(node, (ast.FunctionDef, ast.ClassDef))}


def main():
    with deploy.connect() as ssh:
        existing = []
        with ssh.open_sftp() as sftp:
            original = sftp.open('/opt/ghostgram-license/admin_web.py', 'rb').read()
            remote, local = definitions(original), definitions((folder / 'admin_web.py').read_bytes())
            if not remote.keys() <= local.keys() or set(local)-set(remote) not in (set(), {'public_badges'}) or any(remote[name] != local[name] for name in remote if name not in ('initialize','public_policy','upload','route')):
                raise RuntimeError('Server has unrelated code changes; refusing to overwrite')
            for name in files:
                try:sftp.stat('/opt/ghostgram-license/' + name);existing.append(name)
                except FileNotFoundError:pass
        script = """import os, pathlib, shutil, sqlite3, json
os.umask(0o077)
backup=pathlib.Path(BACKUP);backup.mkdir(parents=True)
for name in FILES:
 target=backup/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2('/opt/ghostgram-license/'+name,target)
source=sqlite3.connect('/var/lib/ghostgram-license/licenses.sqlite')
destination=sqlite3.connect(str(backup/'licenses.sqlite'));source.backup(destination);destination.close();source.close()
print('Database and code backup saved')
""".replace('BACKUP',repr(backup)).replace('FILES',repr(existing))
        print(deploy.run(ssh,'python3 -c '+shlex.quote(script)).strip())
        try:
            with ssh.open_sftp() as sftp:
                for name in files:
                    remote='/opt/ghostgram-license/'+name
                    sftp.put(str(folder/name),remote+'.updates-new')
                    sftp.chmod(remote+'.updates-new',0o644)
                    sftp.posix_rename(remote+'.updates-new',remote)
            deploy.run(ssh,'python3 -m py_compile /opt/ghostgram-license/server.py /opt/ghostgram-license/admin_web.py /opt/ghostgram-license/apk_manifest.py')
            deploy.run(ssh,'systemctl restart ghostgram-license.service')
            time.sleep(2)
            deploy.run(ssh,'systemctl is-active --quiet ghostgram-license.service')
            deploy.run(ssh,"curl --silent --fail --cacert /var/lib/ghostgram-license/server.crt https://93.88.204.2:9443/health >/dev/null")
        except Exception:
            rollback="import shutil; backup="+repr(backup)+"; files="+repr(existing)+"\nfor name in files: shutil.copy2(backup+'/'+name,'/opt/ghostgram-license/'+name)"
            deploy.run(ssh,'python3 -c '+shlex.quote(rollback))
            deploy.run(ssh,'systemctl restart ghostgram-license.service')
            raise
        with ssh.open_sftp() as sftp:
            for name in files:
                actual=hashlib.sha256(sftp.open('/opt/ghostgram-license/'+name,'rb').read()).hexdigest()
                assert actual==hashlib.sha256((folder/name).read_bytes()).hexdigest()
        print(json.dumps({'service':'active','backup':backup,'files_verified':len(files),'release_published':False,'license_rows_changed':False}))


if __name__=='__main__':main()
