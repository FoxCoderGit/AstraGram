"""Temporary owner revocation regression; always restore the same license afterward."""
import json
from pathlib import Path
from deploy import connect, run, IDENTITY, ROOT
from phone_check import adb


def main():
    owner = json.loads((IDENTITY / 'owner-license.json').read_text(encoding='utf-8'))
    with connect() as ssh:
        run(ssh, 'python3 /opt/ghostgram-license/admin.py revoke ' + owner['id'])
        try:
            result = adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'revoked', 'org.ghostgram.messenger/org.ghostgram.messenger.license.LicenseDiagnostics').decode(errors='replace')
            assert 'PASS:' in result, result
            print(result.strip())
            preview = adb('shell', 'am', 'instrument', '-w', '-e', 'mode', 'preview', 'org.ghostgram.messenger/org.ghostgram.messenger.license.LicenseDiagnostics').decode(errors='replace')
            assert 'PASS:' in preview, preview
            (ROOT / 'Tools/ghost40-license-preview.png').write_bytes(adb('exec-out', 'run-as', 'org.ghostgram.messenger', 'cat', 'cache/license-preview.png'))
            print('PASS: activation-only screenshot saved without private chats.')
        finally:
            run(ssh, 'python3 /opt/ghostgram-license/admin.py restore ' + owner['id'])
    adb('shell', 'am', 'start', '-n', 'org.ghostgram.messenger/org.telegram.ui.LaunchActivity')


if __name__ == '__main__': main()
