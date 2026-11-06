"""Device smoke: genuine runtimes, extractor registry, theme-safe opt-in and saved order."""
import os
import json
from java import jclass
from java.util import ArrayList
from org.telegram.messenger import GhostVideoDownloader, GhostAttachmentOrder, GhostAppTrigger
from org.telegram.messenger import MessagesController
from ghost_video import supported
from com.yausername.youtubedl_android import YoutubeDL, YoutubeDLRequest
from java.lang import ProcessBuilder
count = 0
def check(actual, expected):
    global count
    assert actual == expected, (actual, expected)
    count += 1

# Never send messages, launch another application, or change the user's opt-in flags.
for url in (
    'https://www.youtube.com/watch?v=BaW_jenozKc',
    'https://www.youtube.com/shorts/BaW_jenozKc',
    'https://www.tiktok.com/@scout2015/video/6718335390845095173',
    'https://www.instagram.com/reel/C1234567890/',
    'https://x.com/SpaceX/status/1',
    'https://www.reddit.com/r/videos/comments/abc123/test/',
):
    check(supported(url), True)
check(supported('https://example.com/normal-page'), False)
check(supported('file:///sdcard/Download/private.mp4'), False)

host = jclass('org.telegram.messenger.GhostVideoDownloader')
initialize = host.class_.getDeclaredMethod('initialize')
initialize.setAccessible(True)
initialize.invoke(None)
request = YoutubeDLRequest('')
request.addOption('--version')
check('2026.10.26' in YoutubeDL.getInstance().execute(request, 'ghost_video_version', None).getOut(), True)

# The official signed bundle must include EJS; Android must have an executable JS runtime.
import zipfile
native = context.getApplicationInfo().nativeLibraryDir
check(os.path.isfile(os.path.join(native, 'libqjs.so')), True)
bundle = os.path.join(str(context.getNoBackupFilesDir()), 'youtubedl-android', 'yt-dlp', 'yt-dlp')
with zipfile.ZipFile(bundle) as archive:
    check(any(name.startswith('yt_dlp_ejs/') for name in archive.namelist()), True)

# Verify FFmpeg can execute on Android 16 with its packaged dependencies.
args = ArrayList()
args.add(os.path.join(native, 'libffmpeg.so'))
args.add('-version')
builder = ProcessBuilder(args)
builder.redirectErrorStream(True)
packages = os.path.join(str(context.getNoBackupFilesDir()), 'youtubedl-android', 'packages')
builder.environment().put('LD_LIBRARY_PATH', os.path.join(packages, 'ffmpeg/usr/lib') + ':' + os.path.join(packages, 'python/usr/lib'))
process = builder.start()
from java.io import BufferedReader, InputStreamReader
reader = BufferedReader(InputStreamReader(process.getInputStream()))
lines = []
while True:
    line = reader.readLine()
    if line is None: break
    lines.append(str(line))
check(process.waitFor(), 0)
check(any('ffmpeg version' in line for line in lines), True)

# Stable-order merge must preserve temporarily hidden actions and future additions.
prefs = MessagesController.getGlobalMainSettings()
key = 'ghost_attachment_order_v1'
original = prefs.getString(key, None)
try:
    prefs.edit().putString(key, '["gallery","file","yandex_music","location"]').commit()
    visible = ArrayList()
    for name in ('location', 'gallery', 'file', 'article'): visible.add(name)
    GhostAttachmentOrder.save(visible)
    check(list(GhostAttachmentOrder.read()), ['location', 'gallery', 'yandex_music', 'file', 'article'])
finally:
    editor = prefs.edit()
    if original is None: editor.remove(key)
    else: editor.putString(key, original)
    editor.commit()

report = json.dumps({'checks': count, 'runtime': 'original yt-dlp 2026.10.26 + EJS + QuickJS + FFmpeg', 'messages_sent': 0})
