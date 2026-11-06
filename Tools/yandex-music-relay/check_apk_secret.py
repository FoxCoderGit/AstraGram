"""Local check: read the SSH secret without echo; never write it or print file contents."""
import getpass
from pathlib import Path
import sys
import zipfile

secret = getpass.getpass('SSH password for exclusion check: ')
if not secret:
    raise RuntimeError('Secret required for exclusion check')
needles = [secret.encode(), secret.encode('utf-16le')]
apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    for entry in archive.infolist():
        if not entry.is_dir() and any(value in archive.read(entry) for value in needles):
            raise RuntimeError('Private credential detected in APK')
root = Path(__file__).resolve().parents[2]
checked = 0
for folder in ('TMessagesProj/src/main/java', 'TMessagesProj_Ghost/src/main', 'Tools/yandex-music-relay'):
    for path in (root / folder).rglob('*'):
        if path.is_file() and path.suffix in ('.java', '.xml', '.json', '.py', '.gradle', '.properties', '.md'):
            if any(value in path.read_bytes() for value in needles):
                raise RuntimeError('Private credential detected in application source')
            checked += 1
print('SSH credential absent from all APK entries and %d source files' % checked)
