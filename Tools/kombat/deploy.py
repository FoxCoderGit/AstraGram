"""Isolated Kombat deployment: pinned SSH identity, no secrets in repository or APK."""
import sys,shlex,json,importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('ghost_license_deploy',Path(__file__).resolve().parents[1]/'license-server/deploy.py')
license_deploy=importlib.util.module_from_spec(spec);spec.loader.exec_module(license_deploy)
connect=license_deploy.connect
def run(client,command):
    # Drain both SSH streams concurrently: long native compiler diagnostics can
    # otherwise fill stderr's receive window and stall a successful build.
    from concurrent.futures import ThreadPoolExecutor
    _,out,err=client.exec_command(command)
    with ThreadPoolExecutor(max_workers=2) as pool:
        stdout=pool.submit(out.read);stderr=pool.submit(err.read)
        output=stdout.result().decode(errors='replace');error=stderr.result().decode(errors='replace')
    status=out.channel.recv_exit_status()
    if status:raise RuntimeError(f'Remote operation failed ({status}): '+error[-2500:]+'\n'+output[-2500:])
    return output

def inspect():
    code='import json,shutil,os,subprocess;print(json.dumps(dict(cargo=shutil.which("cargo"),rustc=shutil.which("rustc"),cpu=os.cpu_count(),free_gb=shutil.disk_usage("/").free//1073741824)));print(subprocess.check_output(["ss","-lnt"],text=True))'
    with connect() as c: print(run(c,'python3 -c '+shlex.quote(code)))
if __name__=='__main__':inspect()
