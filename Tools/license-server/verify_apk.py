"""Check the distributable APK manifest and ensure no private license files are packed."""
from pathlib import Path
import os
import subprocess
import zipfile
import re
import hashlib
import argparse

ROOT = Path(__file__).resolve().parents[2]
APK = ROOT / 'TMessagesProj_Ghost/build/outputs/apk/debug/TMessagesProj_Ghost-debug.apk'
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
           or str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'))
BUILD_TOOLS = SDK / 'build-tools/36.0.0'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', nargs='?', type=Path, default=APK)
    args = parser.parse_args()
    package = args.apk
    xml = subprocess.check_output([str(BUILD_TOOLS / 'aapt2.exe'), 'dump', 'xmltree', str(package), '--file', 'AndroidManifest.xml']).decode('utf-8')
    if package == APK:
        source = (ROOT / 'TMessagesProj_Ghost/build.gradle').read_text(encoding='utf-8')
        suffix = re.search(r"versionName APP_VERSION_NAME \+ '([^']+)'", source).group(1)
        assert suffix in xml, 'Stale APK: package version does not match the current build'
    assert 'E: instrumentation' not in xml, 'Diagnostic instrumentation in distributable APK'
    debug = [line for line in xml.splitlines() if 'android:debuggable' in line]
    assert not debug or all(re.search(r'=\s*(false|0x0)\s*$', line) for line in debug), 'APK is debuggable'
    test = [line for line in xml.splitlines() if 'android:testOnly' in line]
    assert not test or all(re.search(r'=\s*(false|0x0)\s*$', line) for line in test), 'APK is test-only: cannot be installed through the normal installer (-15)'
    backup = [line for line in xml.splitlines() if 'android:allowBackup' in line]
    assert backup and all(re.search(r'=\s*(false|0x0)\s*$', line) for line in backup), 'Android backup is not disabled'
    assert 'org.ghostgram.messenger.license.LicenseActivity' in xml
    with zipfile.ZipFile(package) as apk:
        files = set(apk.namelist())
        assert 'assets/license/server.crt' in files and 'assets/license/lease-public.der' in files
        assert not any(name.endswith(('lease-signing.pem', 'server.key', 'owner-license.json', 'deploy_id')) or name.startswith('assets/issued-keys/') for name in files)
        assert b'PRIVATE KEY' not in apk.read('assets/license/server.crt')
        build = re.search(r'(?:ghost|veyl|astra)\.(\d+)', xml)
        if build and int(build.group(1)) >= 100:
            assert not any(n.startswith('assets/kombat/') or n.endswith('/libghostkombat.so') for n in files), 'Removed game assets/native core remain'
            for name in files:
                if re.fullmatch(r'classes\d*\.dex', name):
                    assert b'Lorg/telegram/messenger/GhostKombat' not in apk.read(name) and b'Lorg/telegram/ui/GhostKombat' not in apk.read(name), 'Removed game classes remain'
        light = re.search(r'(?:ghost|veyl|astra)\.(\d+)', xml)
        light = bool(light and int(light.group(1)) >= 85)
        if light:
            assert package.stat().st_size <= 200000000, 'APK exceeds 200 MB'
            assert not any(n.startswith(('assets/whisper/', 'assets/vosk/')) for n in files), 'Speech model bundled in light APK'
            assert 'lib/arm64-v8a/libvosk.so' in files, 'Optional Vosk runtime missing'
            current = re.search(r'(?:ghost|veyl|astra)\.(\d+)', xml)
            if current and int(current.group(1)) >= 87:
                for name in files:
                    if re.fullmatch(r'classes\d*\.dex', name):
                        assert b'Lorg/telegram/messenger/GhostBetaPresence;' not in apk.read(name), 'Retired activity monitor remains in APK'
                native = apk.read('lib/arm64-v8a/libtmessages.49.so')
                for method in (b'native_setSessionDisabled', b'native_exportSessionAuth', b'native_installSessionAuth', b'native_eraseImportedSession'):
                    assert method in native, 'Authorization JNI implementation missing: '+method.decode()
        else:
            turbo = 'assets/whisper/ggml-large-v3-turbo-q5_0.bin' in files
            if 'optional-turbo' in xml:
                assert not turbo, 'Optional Turbo model must not be bundled'
            model_name = 'assets/whisper/ggml-large-v3-turbo-q5_0.bin' if turbo else 'assets/whisper/ggml-small-q5_0.bin'
            model = apk.getinfo(model_name)
            assert model.file_size == (574041195 if turbo else 175209663) and model.compress_type == zipfile.ZIP_STORED, 'Embedded Whisper model unavailable'
            digest = hashlib.sha256()
            with apk.open(model_name) as data:
                for block in iter(lambda: data.read(1024 * 1024), b''): digest.update(block)
            assert digest.hexdigest() == ('394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2' if turbo else '23ad5215d37677f8eb8cbc41b4d0d4e451229f811be5206ff116dd9337eba748'), 'Wrong Whisper model hash'
            if 'assets/vosk/vosk-model-small-ru-0.22.zip' in files:
                for path, size, sha in [
                    ('assets/whisper/ggml-small-q5_0.bin',175209663,'23ad5215d37677f8eb8cbc41b4d0d4e451229f811be5206ff116dd9337eba748'),
                    ('assets/vosk/vosk-model-small-ru-0.22.zip',46236750,'961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4'),
                ]:
                    info=apk.getinfo(path)
                    assert info.file_size==size and info.compress_type==zipfile.ZIP_STORED, 'Missing offline model: '+path
                    digest=hashlib.sha256()
                    with apk.open(path) as stream:
                        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
                    assert digest.hexdigest()==sha, 'Wrong model hash: '+path
                assert 'lib/arm64-v8a/libvosk.so' in files, 'Vosk native runtime missing'
                assert 'assets/licenses/vosk-api-Apache-2.0.txt' in files, 'Vosk license missing'
    subprocess.run([str(BUILD_TOOLS / 'apksigner.bat'), 'verify', str(package)], check=True, capture_output=True)
    print('PASS: signed APK, not test-only, debugging disabled, no instrumentation, backup disabled, only public license assets, speech packaging policy verified.')


if __name__ == '__main__': main()
