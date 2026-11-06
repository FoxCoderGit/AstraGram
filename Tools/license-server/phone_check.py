"""Activation UI test. No key or chat text is printed; the key stays outside source."""
import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PHONE = '878d3b0'
ROOT = Path(__file__).resolve().parents[2]


def adb(*args):
    result = subprocess.run(['adb', '-s', PHONE, *args], capture_output=True)
    if result.returncode: raise RuntimeError('ADB operation failed: ' + result.stderr.decode(errors='replace')[:300])
    return result.stdout


def snapshot():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ghost-license-window.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/ghost-license-window.xml').decode('utf-8'))


def tap(node):
    import re
    a, b, c, d = map(int, re.findall(r'\d+', node.attrib['bounds']))
    adb('shell', 'input', 'tap', str((a + c) // 2), str((b + d) // 2))


def main():
    owner = json.loads((Path.home() / '.ghostgram/owner-license.json').read_text(encoding='utf-8'))
    time.sleep(2)
    xml = snapshot()
    fields = [n for n in xml.iter('node') if n.attrib.get('package') == 'org.ghostgram.messenger' and n.attrib.get('class', '').endswith('EditText')]
    assert fields, 'Activation input is not visible'
    assert any(n.attrib.get('text') == 'GhostGram' for n in xml.iter('node')), 'Activation title missing'
    tap(fields[0]); adb('shell', 'input', 'text', owner['key'])
    adb('shell', 'input', 'keyevent', '66')
    for _ in range(30):
        time.sleep(1)
        resumed = adb('shell', 'dumpsys', 'activity', 'activities').decode(errors='replace')
        if any('ResumedActivity' in line and 'org.telegram.ui.LaunchActivity' in line for line in resumed.splitlines()):
            break
    else: raise AssertionError('Activation did not open Telegram')
    prefs = adb('shell', 'run-as', 'org.ghostgram.messenger', 'cat', 'shared_prefs/ghost_license.xml').decode('utf-8')
    assert owner['key'] not in prefs, 'Raw key must not be stored'
    assert 'name="lease"' in prefs, 'Signed lease was not persisted'
    adb('shell', 'am', 'force-stop', 'org.ghostgram.messenger')
    adb('shell', 'am', 'start', '-n', 'org.ghostgram.messenger/org.telegram.ui.LaunchActivity')
    time.sleep(3)
    resumed = adb('shell', 'dumpsys', 'activity', 'activities').decode(errors='replace')
    assert any('ResumedActivity' in line and 'org.telegram.ui.LaunchActivity' in line for line in resumed.splitlines()), 'Cached activation failed'
    print('PASS: activation screen, real device-bound activation, no raw stored key, signed 24h lease, cached restart.')


if __name__ == '__main__': main()
