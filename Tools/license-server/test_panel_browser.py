"""Browser QA on isolated localhost data, never on the owner's production records."""
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
from cryptography.hazmat.primitives.asymmetric import rsa
from playwright.sync_api import sync_playwright
import server
import admin_web


def main():
    with tempfile.TemporaryDirectory(prefix='ghost-panel-ui-') as tmp:
        server.DATA=Path(tmp);server.initialize();salt=secrets.token_hex(16)
        (server.DATA/'owner.json').write_text(json.dumps({'salt':salt,'password_hash':admin_web.password_hash('local-fixture-password-only',salt),'apk_signer_sha256':'a'*64}))
        now=int(time.time())
        with server.db() as c:
            c.execute('INSERT INTO licenses(id,key_hash,label,created_at,last_seen) VALUES(?,?,?,?,?)',('a'*32,'b'*64,'Тестовая лицензия',now-86400,now))
            c.execute('INSERT INTO app_devices VALUES(?,?,?,?,?,?,?,?,?)',('c'*64,'12.10.6-ghost.85',85,36,'Test','Fixture',now-86400,now,'{"playback":0}'))
            c.execute("INSERT INTO app_days VALUES(date(?,'unixepoch'),?)",(now,'c'*64))
        # Only this ephemeral HTTP listener permits an HTTP loopback Origin.
        original=admin_web.origin
        def local_origin(h,b):
            if h.headers.get('Origin')=='http://'+h.headers.get('Host','') and h.headers.get('Host','').startswith('127.0.0.1:'):return
            original(h,b)
        admin_web.origin=local_origin
        http=server.Server(('127.0.0.1',0),server.Handler);http.signing_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        thread=threading.Thread(target=http.serve_forever,daemon=True);thread.start()
        try:
            with sync_playwright() as p:
                browser=p.chromium.launch(executable_path='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless=True)
                page=browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                page.goto('http://127.0.0.1:'+str(http.server_port)+'/admin/')
                page.get_by_label('Пароль владельца').fill('local-fixture-password-only');page.get_by_role('button',name='Войти',exact=True).click()
                page.get_by_role('heading',name='Обзор',exact=True).wait_for()
                page.get_by_text('Активны за сутки',exact=True).wait_for()
                page.screenshot(path=str(Path(__file__).resolve().parents[3]/'Reports/veyl109-panel-overview.png'),full_page=True)
                page.get_by_role('button',name='Ключи',exact=True).click();page.get_by_role('cell').filter(has_text='Тестовая лицензия').wait_for()
                page.get_by_role('button',name='Создать ключи',exact=True).click();page.get_by_role('heading',name='Создать ключи',exact=True).wait_for()
                page.get_by_label('Название',exact=True).fill('Проверка интерфейса');page.get_by_role('button',name='Отмена',exact=True).click()
                page.get_by_role('button',name='Устройства',exact=True).click();page.get_by_role('cell').filter(has_text='Test Fixture').wait_for()
                page.get_by_role('button',name='Обновления',exact=True).click();page.get_by_role('heading',name='Новое обновление',exact=True).wait_for()
                page.get_by_role('button',name='Галочки',exact=True).click()
                page.get_by_label('Telegram ID',exact=True).fill('5330087923')
                page.get_by_role('button',name='Сохранить галочку',exact=True).click()
                page.locator('#badgeRows tr').filter(has_text='5330087923').wait_for()
                page.get_by_role('button',name='Изменить',exact=True).click()
                page.locator('#badgeForm select').select_option('creator')
                page.get_by_role('button',name='Сохранить галочку',exact=True).click()
                page.locator('#badgeRows').get_by_text('Создатель AstraGram',exact=True).wait_for()
                page.screenshot(path=str(Path(__file__).resolve().parents[3]/'Reports/veyl109-badges-panel.png'),full_page=True)
                page.once('dialog',lambda dialog:dialog.accept())
                page.get_by_role('button',name='Отозвать',exact=True).click()
                page.locator('#badgeRows tr').wait_for(state='detached')
                page.get_by_role('button',name='Управление',exact=True).click();page.get_by_label('Требовать ключ для доступа к AstraGram',exact=True).wait_for()
                page.set_viewport_size({'width':390,'height':844});page.screenshot(path=str(Path(__file__).resolve().parents[3]/'Reports/veyl109-panel-mobile.png'),full_page=True)
                assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile page overflows'
                assert not errors,errors
                browser.close()
                print('PASS: login, overview, keys dialog, devices, releases, settings, desktop/mobile layout, no JS errors; isolated fixtures only')
        finally:http.shutdown();http.server_close();admin_web.origin=original


if __name__=='__main__':main()
