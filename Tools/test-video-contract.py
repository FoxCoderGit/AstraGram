"""Offline checks against the exact official yt-dlp binary packaged in GhostGram."""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parents[1]
bundle = root / 'TMessagesProj/src/main/res/raw/ghost_ytdlp.zip'
expected = 'e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3'
assert hashlib.sha256(bundle.read_bytes()).hexdigest() == expected
with zipfile.ZipFile(bundle) as archive:
    assert any(name.startswith('yt_dlp_ejs/') for name in archive.namelist())
sys.path.insert(0, str(bundle))
sys.path.insert(0, str(root / 'TMessagesProj_Ghost/src/main/python'))
from ghost_video import supported
from yt_dlp import YoutubeDL
from yt_dlp.version import __version__
assert __version__ == '2026.10.26'

cases = [
    ('https://www.youtube.com/watch?v=BaW_jenozKc', True),
    ('https://www.youtube.com/shorts/BaW_jenozKc', True),
    ('https://www.tiktok.com/@scout2015/video/6718335390845095173', True),
    ('https://www.instagram.com/reel/C1234567890/', True),
    ('https://x.com/SpaceX/status/1', True),
    ('https://www.reddit.com/r/videos/comments/abc123/test/', True),
    ('https://vimeo.com/22439234', True),
    ('https://example.com/normal-page', False),
    ('file:///sdcard/Download/private.mp4', False),
]
for url, expected_result in cases:
    assert supported(url) == expected_result, url

java = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/GhostVideoDownloader.java').read_text(encoding='utf-8')
expression = re.search(r'public static final String FORMAT="([^"]+)"', java).group(1)
def video(height, separate=False):
    return {'format_id': str(height), 'url': 'https://example.com/video', 'height': height,
            'width': height * 16 // 9, 'vcodec': 'avc1', 'acodec': 'none' if separate else 'mp4a', 'ext': 'mp4'}
audio = {'format_id': 'audio', 'url': 'https://example.com/audio', 'vcodec': 'none', 'acodec': 'mp4a', 'ext': 'm4a'}
quality_checks = 0
with YoutubeDL({'quiet': True, 'no_warnings': True}) as downloader:
    selector = downloader.build_format_selector(expression)
    for heights, expected_height in [([240, 360, 480, 720, 1080], 720), ([240, 360, 480], 480), ([360], 360), ([240, 1080], None)]:
        result = downloader._select_formats([video(height) for height in heights], selector)
        assert (result[0]['height'] if result else None) == expected_height, (heights, result)
        quality_checks += 1
    result = downloader._select_formats([audio, video(360, True), video(720, True), video(1080, True)], selector)
    assert result[0]['height'] == 720 and len(result[0]['requested_formats']) == 2
    quality_checks += 1
print(json.dumps({'official_bundle_sha256': expected, 'yt_dlp': __version__, 'ejs_bundled': True,
                  'extractor_cases': len(cases), 'quality_cases': quality_checks, 'status': 'PASS'}))
