"""Package the complete working source tree, excluding local/generated artifacts and Git history."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCAL_DIRS = {'.git', '.gradle', '.idea', '.cxx', '__pycache__', 'node_modules'}
GENERATED = {'build', 'buildSrc/build', 'TMessagesProj/build', 'TMessagesProj_Ghost/build',
             'Tools/models', 'Tools/model-backups', 'Tools/.panel-test-env',
             'Tools/ghost-native-check', 'Tools/kombat/target',
             'Tools/tests/ghost88', 'Tools/tests/ghost89'}

def files():
    for base, dirs, names in os.walk(ROOT):
        parent = Path(base)
        for name in list(dirs):
            child = parent / name
            relative = child.relative_to(ROOT).as_posix()
            gradle_build = name == 'build' and any((parent / f).is_file() for f in ('build.gradle', 'build.gradle.kts'))
            reparse = bool(getattr(child.lstat(), 'st_file_attributes', 0) & 0x400)
            if name in LOCAL_DIRS or relative in GENERATED or gradle_build or child.is_symlink() or reparse:
                dirs.remove(name)
        for name in sorted(names):
            path = parent / name
            relative = path.relative_to(ROOT).as_posix()
            if path.is_symlink() or name in ('.git', 'local.properties', '.DS_Store'):
                continue
            if path.suffix.lower() == '.pyc':
                continue
            if parent == ROOT / 'Tools' and path.suffix.lower() in ('.apk', '.log'):
                continue
            if parent == ROOT and path.suffix.lower() == '.log':
                continue
            if relative.startswith('Tools/tests/') and path.suffix.lower() in ('.class', '.obj', '.exe'):
                continue
            yield path, relative

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(ROOT):
        parser.error('Choose an output location outside the project.')
    if output.exists():
        parser.error('Output already exists; choose a new filename.')
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = []
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=3, allowZip64=True) as archive:
        for path, relative in files():
            info = zipfile.ZipInfo.from_file(path, 'GhostGram-source/' + relative)
            info.compress_type = zipfile.ZIP_DEFLATED
            info._compresslevel = 3
            digest = hashlib.sha256()
            size = 0
            with path.open('rb') as source, archive.open(info, 'w', force_zip64=True) as target:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    target.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
            if size != info.file_size:
                raise RuntimeError('File changed during packaging: ' + relative)
            manifest.append({'path': relative, 'bytes': size, 'sha256': digest.hexdigest()})
            if len(manifest) % 5000 == 0:
                print(f'Packaged {len(manifest)} files...', flush=True)
    with zipfile.ZipFile(output) as archive:
        damaged = archive.testzip()
        if damaged:
            raise RuntimeError('Archive integrity check failed: ' + damaged)
    report = output.with_suffix('.manifest.json')
    report.write_text(json.dumps({'files': manifest, 'source_bytes': sum(f['bytes'] for f in manifest),
                                  'zip_bytes': output.stat().st_size}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS: {len(manifest)} source files, {output.stat().st_size} archive bytes; ZIP integrity verified.')

if __name__ == '__main__':
    main()
