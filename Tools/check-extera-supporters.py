"""Synthetic filter tests and optional official-endpoint validation, without production cache writes."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import urllib.request

root=Path(__file__).resolve().parents[1]
jar=next((Path(os.environ['USERPROFILE'])/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar'))
jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))/'bin'
with tempfile.TemporaryDirectory(prefix='veyl-extera-check-') as folder:
    subprocess.run([str(jdk/'javac.exe'),'-cp',str(jar),'-d',folder,str(root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostExteraSupporterRegistry.java'),str(root/'tests/badges/GhostExteraSupportersSmoke.java')],check=True)
    args=[]
    if '--live' in sys.argv:
        path=Path(folder)/'profiles.json'
        with urllib.request.urlopen('https://api.exteragram.app/api/v1/profiles',timeout=25) as response:data=response.read(4*1024*1024+1)
        assert len(data)<=4*1024*1024
        path.write_bytes(data);args.append(str(path))
    subprocess.run([str(jdk/'java.exe'),'-cp',folder+os.pathsep+str(jar),'GhostExteraSupportersSmoke',*args],check=True)
