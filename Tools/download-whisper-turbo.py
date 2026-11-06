from pathlib import Path
import hashlib
import requests

root = Path(__file__).resolve().parents[1]
target = root / 'Tools/models/ggml-large-v3-turbo-q5_0.bin'
size = 574041195
digest = '394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2'
target.parent.mkdir(parents=True, exist_ok=True)
stage = target.with_suffix('.download')
if not target.exists():
    with requests.get('https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin', stream=True, timeout=(30, 120)) as response:
        response.raise_for_status()
        with stage.open('wb') as output:
            for chunk in response.iter_content(1024 * 1024):
                output.write(chunk)
    assert stage.stat().st_size == size and hashlib.file_digest(stage.open('rb'), 'sha256').hexdigest() == digest, 'Model integrity failed'
    stage.rename(target)
assert target.stat().st_size == size
with target.open('rb') as model:
    assert hashlib.file_digest(model, 'sha256').hexdigest() == digest
print('PASS: verified optional Whisper Large model stored outside APK assets.')
