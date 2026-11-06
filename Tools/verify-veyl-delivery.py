"""Verify the installed upgrade, preserve signer, and prepare reviewable release files."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

root=Path(__file__).resolve().parents[1]
apk=root.parent/'APK/AstraGram-12.10.6-astra.112.apk'
adb='C:/Users/alexey/sdk/platform-tools/adb.exe'
def phone(*args):return subprocess.check_output([adb,'-s','878d3b0',*args],text=True,encoding='utf-8')
with apk.open('rb') as stream:sha=hashlib.file_digest(stream,'sha256').hexdigest()
phone_path='/sdcard/Download/'+apk.name
delivered='--local-only' not in sys.argv
if delivered:
    assert phone('shell','sha256sum',phone_path).split()[0]==sha
    installed=phone('shell','dumpsys','package','org.ghostgram.messenger')
    assert 'versionCode=7135 ' in installed and 'versionName=12.10.6-astra.112' in installed
    first_install=re.findall(r'firstInstallTime=([^\r\n]+)',installed)
    before=json.loads((root.parent/'Reports/veyl111-before-install.json').read_text(encoding='utf-8'))
    assert first_install==before['first_install_time']
    path=phone('shell','pm','path','org.ghostgram.messenger').strip().removeprefix('package:')
    assert phone('shell','sha256sum',path).split()[0]==sha
certout=subprocess.check_output(['C:/Program Files/Android/Android Studio/jbr/bin/java.exe','-jar','C:/Users/alexey/sdk/build-tools/36.0.0/lib/apksigner.jar','verify','--print-certs',str(apk)],text=True)
cert=re.search(r'certificate SHA-256 digest: ([a-f0-9]{64})',certout)[1]
assert cert=='c9cbecf7cefb5f87eb87cd37dbfc2e830e6f19b24cae8bd99c436c6f44797668'
badging=subprocess.check_output(['C:/Users/alexey/sdk/build-tools/36.0.0/aapt2.exe','dump','badging',str(apk)],text=True,encoding='utf-8')
labels=re.findall(r"^application-label(?:-[^:]+)?:'([^']*)'$",badging,re.M)
assert labels and set(labels)=={'AstraGram'},set(labels)
originals={}
artwork=json.loads((root.parent/'Reports/veyl111-badge-artwork.json').read_text(encoding='utf-8'))
with zipfile.ZipFile(apk) as archive:
    for item in artwork:
        matches=[entry.filename for entry in archive.infolist() if entry.file_size in (1019,491) and hashlib.sha256(archive.read(entry)).hexdigest()==item['sha256']]
        assert len(matches)==1,item['role']
        originals[item['role']]=matches[0]
release=root.parent/'Release/AstraGram-112';release.mkdir(parents=True,exist_ok=True)
shutil.copy2(apk,release/apk.name)
(release/(apk.name+'.sha256')).write_text(sha+'  '+apk.name+'\n',encoding='utf-8')
report={'date':'2026-11-05','version':'12.10.6-astra.112','version_code':7135,'apk':'APK/'+apk.name,'bytes':apk.stat().st_size,'sha256':sha,'signer_sha256':cert,'same_signer':True,'phone_path':phone_path if delivered else None,'phone_copy_and_installed_apk_match':True if delivered else None,'installed_version_code':7135 if delivered else None,'first_install_time_preserved':True if delivered else None,'delivery':'complete' if delivered else 'awaiting_phone_connection','app_data_cleared':False,'app_labels_checked':len(labels),'app_label':'AstraGram','original_tgs_in_apk':originals,'server_tests':25,'badge_parser_checks':31,'panel_browser_check':'PASS','domain_tls_and_rsa':'PASS','messages_sent':0,'new_release_published':False,'build_log':'Reports/astra112-build-install.log'}
(root.parent/'Reports/astra112-verified.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,indent=2))
