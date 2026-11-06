"""Build debugpy's supported pure Python mode from the pinned upstream sdist."""
import base64
import csv
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile

VERSION = '1.8.14'
EXPECTED = '7cd287184318416850aa8b60ac90105837bb1e59531898c07569d197d2ed5322'
root = Path(__file__).resolve().parents[1]
metadata = json.load(urllib.request.urlopen('https://pypi.org/pypi/debugpy/' + VERSION + '/json', timeout=30))
source = next(item for item in metadata['urls'] if item['packagetype'] == 'sdist')
body = urllib.request.urlopen(source['url'], timeout=60).read()
if hashlib.sha256(body).hexdigest() != EXPECTED: raise RuntimeError('Upstream source checksum mismatch')
files = {}
prefix = 'debugpy-' + VERSION + '/'
with tarfile.open(fileobj=io.BytesIO(body), mode='r:gz') as archive:
    for entry in archive.getmembers():
        if not entry.isfile(): continue
        relative = entry.name[len(prefix):] if entry.name.startswith(prefix) else ''
        if relative.startswith('src/debugpy/'):
            name = relative[len('src/'):]
            if '..' in Path(name).parts: raise ValueError(name)
            if name.endswith(('.py', '.pyi', '.json', '.txt', '.xml', '.html', '.js', '.css')) or 'LICENSE' in name or 'NOTICE' in name:
                files[name] = archive.extractfile(entry).read()
        elif relative == 'PKG-INFO': files['debugpy-' + VERSION + '.dist-info/METADATA'] = archive.extractfile(entry).read()
        elif relative in ('LICENSE', 'NOTICE'):
            files['debugpy-' + VERSION + '.dist-info/licenses/' + relative] = archive.extractfile(entry).read()
files['debugpy/_version.py'] = b'def get_versions():\n    return {"version": "1.8.14", "full-revisionid": "", "dirty": False, "error": None, "date": None}\n'
info = 'debugpy-' + VERSION + '.dist-info/'
files[info + 'WHEEL'] = b'Wheel-Version: 1.0\nGenerator: GhostGram pure Python source packager\nRoot-Is-Purelib: true\nTag: py3-none-any\n'
record = io.StringIO(newline='')
writer = csv.writer(record)
for name, value in sorted(files.items()):
    digest = base64.urlsafe_b64encode(hashlib.sha256(value).digest()).decode().rstrip('=')
    writer.writerow((name, 'sha256=' + digest, len(value)))
writer.writerow((info + 'RECORD', '', ''))
files[info + 'RECORD'] = record.getvalue().encode()
out = root / 'Tools/python-vendor'
out.mkdir(exist_ok=True)
wheel = out / ('debugpy-' + VERSION + '-py3-none-any.whl')
with zipfile.ZipFile(wheel, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, value in sorted(files.items()): archive.writestr(name, value)
print('Pure Python debugpy wheel:', wheel.name, len(files), 'files', wheel.stat().st_size, 'bytes')
