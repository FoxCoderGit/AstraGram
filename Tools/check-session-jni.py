"""Check compiled Java/native registration signatures without starting user accounts."""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
clazz=root/'TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/org/telegram/tgnet/ConnectionsManager.class'
javap=Path('C:/Program Files/Android/Android Studio/jbr/bin/javap.exe')
listing=subprocess.check_output([str(javap),'-p','-s',str(clazz)]).decode()
methods={}
for declaration,descriptor in re.findall(r'([^\n]+);\s*descriptor:\s*(\S+)',listing):
    name=re.search(r'([A-Za-z0-9_]+)\(',declaration)
    if name:methods.setdefault(name.group(1),set()).add(descriptor)
source=(root/'TMessagesProj/jni/TgNetWrapper.cpp').read_text(encoding='utf-8')
table=source[source.index('{"native_getCurrentTimeMillis"'):source.index('extern "C"',source.index('{"native_getCurrentTimeMillis"'))]
registered=re.findall(r'\{"(native_[^"]+)",\s*"([^"]+)"',table)
for name,descriptor in registered:
    assert descriptor in methods.get(name,set()),(name,descriptor,'does not match compiled Java')
for name,descriptor in [('onSessionInvalid','(ILjava/lang/String;)V'),('onLogout','(I)V')]:
    assert descriptor in methods.get(name,set()),(name,'callback missing')
print('PASS:',len(registered),'JNI registration descriptors and authorization callbacks match compiled Java')
