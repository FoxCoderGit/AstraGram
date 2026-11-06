"""Capture only SDK diagnostics and redact public API-key values, bounded to three minutes."""
from pathlib import Path
import re
import subprocess
import threading

root=Path(__file__).resolve().parents[1]
adb=str(Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe')
process=subprocess.Popen([adb,'-s','878d3b0','logcat','-v','time'],stdout=subprocess.PIPE,encoding='utf-8',errors='replace')
timer=threading.Timer(180,process.terminate)
timer.start()
try:
    with (root/'Tools/ghost53-maps-live.txt').open('w',encoding='utf-8') as output:
        for line in process.stdout:
            if re.search(r'Google Maps Android API|androidmapsapi|Authorization failure|API key not found|MapsInitializer|maps_core_dynamite',line):
                output.write(re.sub(r'AIza[\w-]+','<redacted-api-key>',line));output.flush()
finally:
    timer.cancel()
    if process.poll() is None:process.terminate()
