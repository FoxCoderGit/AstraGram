"""Stage the checked AstraGram APK in the existing owner panel; never publish it."""
import base64
import hashlib
import json
from pathlib import Path
import urllib.request
import http.cookiejar
import urllib.error
import time

root=Path(__file__).resolve().parents[2]
apk=root.parent/'APK/AstraGram-12.10.6-astra.112.apk'
access=json.loads((root.parent/'Private/.ghostgram/owner-panel.json').read_text(encoding='utf-8'))
base='https://update.codebyfox.ru:9443'
class ProgressReader:
    def __init__(self,source):self.source=source;self.sent=0;self.next=16*1024*1024
    def read(self,size=-1):
        data=self.source.read(1024*1024 if size>0 else size);self.sent+=len(data)
        if self.sent>=self.next:print('Uploaded '+str(self.sent*100//apk.stat().st_size)+'%',flush=True);self.next=self.sent+16*1024*1024
        return data
session=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
common={'Origin':base}
def api(path,data=None,raw=None,headers=None,timeout=20):
    options=dict(common);options.update(headers or {})
    if data is not None:raw=json.dumps(data).encode();options['Content-Type']='application/json'
    request=urllib.request.Request(base+path,data=raw,headers=options)
    attempts=2 if raw is None or isinstance(raw,bytes) else 1
    for attempt in range(attempts):
        try:
            with session.open(request,timeout=timeout) as response:return json.load(response)
        except (urllib.error.URLError,TimeoutError,ConnectionError) as error:
            if isinstance(error,urllib.error.HTTPError) and error.code<500:raise
            if attempt+1==attempts:raise
            time.sleep(1)
common['X-CSRF-Token']=api('/admin/api/login',{'password':access['password']})['csrf']
state=api('/admin/api/state')
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
report_path=root.parent/'Reports/veyl111-panel-draft.json'
previous_reports=[report_path,root.parent/'Reports/veyl109-panel-draft.json',root.parent/'Reports/veyl110-panel-draft.json']
previous_ids={json.loads(p.read_text(encoding='utf-8'))['id'] for p in previous_reports if p.is_file()}
existing=next((r for r in state['releases'] if r['sha256']==sha),None)
if existing:rid=existing['id']
else:
    metadata={'version':'12.10.6-astra.112','build':111,'mandatory':False,
        'notes':'AstraGram: новое название проекта. Домены обновлений и верификации. Выбранные владельцем значки создателя и поддержки проекта. Управление галочками из панели. Совместимое обновление предыдущей версии с сохранением данных.'}
    headers={'Content-Type':'application/vnd.android.package-archive','Content-Length':str(apk.stat().st_size),'X-Release-Metadata':base64.b64encode(json.dumps(metadata,ensure_ascii=False).encode()).decode()}
    with apk.open('rb') as data:response=api('/admin/api/releases/upload',raw=ProgressReader(data),headers=headers,timeout=240)
    rid=response['id']
for previous_id in previous_ids-{rid}:
    previous=next((r for r in state['releases'] if r['id']==previous_id),None)
    if previous and previous['state']=='draft':api('/admin/api/releases/action',{'id':previous_id,'action':'delete'})
result={'id':rid,'version_code':7134,'state':'draft' if existing is None else existing['state'],'sha256':sha,'published_by_this_script':False}
(root.parent/'Reports/veyl111-panel-draft.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result))
