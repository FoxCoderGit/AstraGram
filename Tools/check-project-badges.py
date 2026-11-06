"""Run the production snapshot parser on the JVM without accessing any app/user data."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram/messenger'
source = (java / 'GhostBadges.java').read_text(encoding='utf-8')
start = source.index('public static GhostBadgeRegistry parseSnapshot(')
brace = source.index('{', start)
depth = 1
end = brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
method = source[start:end]
jar = next((Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar'))
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Android/Android Studio/jbr')) / 'bin'
with tempfile.TemporaryDirectory(prefix='ghost-badge-tests-') as temp:
    folder = Path(temp)
    parser = folder / 'GhostBadges.java'
    parser.write_text('package org.telegram.messenger; import org.json.*; import java.util.*; public final class GhostBadges {' + method + '}', encoding='utf-8')
    subprocess.run([str(jdk / 'javac.exe'), '-cp', str(jar), '-d', temp, str(java / 'GhostBadgeRegistry.java'), str(parser), str(root / 'tests/badges/GhostBadgeRegistrySmoke.java'), str(root / 'tests/badges/GhostBadgeSnapshotSmoke.java')], check=True)
    for test in ('GhostBadgeRegistrySmoke', 'GhostBadgeSnapshotSmoke'):
        subprocess.run([str(jdk / 'java.exe'), '-cp', temp + os.pathsep + str(jar), test], check=True)
