"""Exercise relay owner controls in a browser using isolated fixture data."""
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
from unittest.mock import patch
from cryptography.hazmat.primitives.asymmetric import rsa
from playwright.sync_api import sync_playwright
import server
import admin_web
import relay_control

def main():
    with tempfile.TemporaryDirectory(prefix='astra-relay-ui-') as tmp:
        server.DATA=Path(tmp);server.initialize();salt=secrets.token_hex(16)
        (server.DATA/'owner.json').write_text(json.dumps({'salt':salt,'password_hash':admin_web.password_hash('fixture-password-only',salt),'apk_signer_sha256':'a'*64}))
        device='b'*64
        with server.db() as c:
            c.execute('INSERT INTO licenses(id,key_hash,label,device,created_at,last_seen) VALUES(?,?,?,?,?,?)',('a'*32,'c'*64,'<Fixture>',device,int(time.time()),0))
        snapshot={'policy':{'revision':1,'enabled':True,'denied_devices':{}},'active_connections':2,'active_devices':{device:2},'bytes_up':1234,'bytes_down':123456,'started_at':int(time.time()),'device_limit':48,'connection_limit':512}
        calls=[]
        original_origin=admin_web.origin
        def local_origin(h,b):
            if h.headers.get('Origin')=='http://'+h.headers.get('Host','') and h.headers.get('Host','').startswith('127.0.0.1:'):return
            original_origin(h,b)
        def control(b,method,path,data=None):
            if method=='GET':return snapshot
            calls.append((path,data))
            if path=='/policy':
                if 'enabled' in data:snapshot['policy']['enabled']=data['enabled']
                if 'device' in data:
                    if data['denied']:snapshot['policy']['denied_devices'][data['device']]=True
                    else:snapshot['policy']['denied_devices'].pop(data['device'],None)
            return {'status':'ok'}
        http=server.Server(('127.0.0.1',0),server.Handler);http.signing_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        threading.Thread(target=http.serve_forever,daemon=True).start()
        try:
            with patch.object(admin_web,'origin',side_effect=local_origin),patch.object(relay_control,'request',side_effect=control),sync_playwright() as p:
                browser=p.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless=True)
                page=browser.new_page(viewport={'width':1440,'height':1000});errors=[]
                page.on('pageerror',lambda e:errors.append(str(e)));page.on('dialog',lambda d:d.accept())
                page.goto(f'http://127.0.0.1:{http.server_port}/admin/')
                page.locator('#password').fill('fixture-password-only');page.locator('#loginForm button').click()
                page.locator('#app').wait_for(state='visible')
                page.get_by_role('button',name='AstraRelay',exact=True).click()
                page.locator('#relayStatus').get_by_text('Сервис включён',exact=False).wait_for()
                assert page.locator('#relayDeviceRows tr').count()==1
                assert page.locator('#relayDeviceRows').inner_text().count('<Fixture>')==1
                assert page.locator('#relayDeviceRows script').count()==0
                page.locator('#relayEnabled').uncheck();page.locator('#relaySave').click()
                page.locator('#relayStatus').get_by_text('Сервис отключён',exact=False).wait_for()
                page.locator('#relayDeviceRows button').click()
                page.locator('#relayDeviceRows button').get_by_text('Разрешить',exact=True).wait_for()
                page.locator('#relayReconnect').click()
                page.get_by_text('Туннели переподключаются.',exact=True).wait_for()
                assert ('/policy',{'enabled':False}) in calls
                assert ('/policy',{'device':device,'denied':True}) in calls
                assert ('/reconnect',{}) in calls
                page.screenshot(path=str(Path(__file__).resolve().parents[3]/'Reports/astra129-relay-admin-desktop.png'),full_page=True)
                page.set_viewport_size({'width':390,'height':844})
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'),'Mobile page overflows'
                page.screenshot(path=str(Path(__file__).resolve().parents[3]/'Reports/astra129-relay-admin-mobile.png'),full_page=True)
                assert not errors,errors
                browser.close()
        finally:http.shutdown();http.server_close()
    print('PASS: relay admin browser controls, device policy, reconnect, escaping and mobile layout')

if __name__=='__main__':main()
