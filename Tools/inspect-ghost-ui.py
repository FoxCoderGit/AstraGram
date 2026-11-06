"""Print only navigation controls and error text; never print chat messages or drafts."""
import os
import xml.etree.ElementTree as ET
path=os.environ['GHOST_UI_XML']
allowed={'Плагины','Триггер','yt-dlp','Настройки','Избранное','Меню','Назад','Открыть меню','Еще','Ещё','OK','ОК','Выбрать приложение','Показывать триггер','Показывать триггер в меню','Встроенные возможности','Отправить как видео (360–720p)','Видео с YouTube','Видео по ссылке'}
allowed.update({'Бейджи exteraGram','Показывать бейджи exteraGram','Обновить списки','Импортировать кэш exteraGram'})
allowed.update({'Поддержавшие exteraGram','Поддержавшие exteraGram · включено','Поддержавшие exteraGram · выключено'})
try:
    for node in ET.parse(path).iter('node'):
        text=node.get('text',''); description=node.get('content-desc','')
        if text in allowed or description in allowed or text.startswith(('Не удалось загрузить','Сервис требует авторизацию','Нет подходящего качества','Не удалось подготовить','Поддержавшие:')):
            print(text if text in allowed or text.startswith(('Не удалось','Сервис требует','Нет подходящего','Поддержавшие:')) else description,node.get('bounds'),node.get('checked',''))
        elif node.get('class','').endswith('EditText'):
            print('EDIT_FIELD',node.get('bounds'),'nonempty='+str(bool(text) and text not in ('Сообщение','Message')))
            import re,urllib.parse
            for url in re.findall(r'https?://[^\s<>]+',text):
                parsed=urllib.parse.urlsplit(url)
                if parsed.hostname in ('www.youtube.com','youtube.com','youtu.be','www.tiktok.com','www.instagram.com','x.com','www.reddit.com','vimeo.com'):
                    query=urllib.parse.parse_qs(parsed.query)
                    safe_query=urllib.parse.urlencode({'v':query['v'][0]}) if 'v' in query else ''
                    print('VIDEO_URL',urllib.parse.urlunsplit((parsed.scheme,parsed.netloc,parsed.path,safe_query,'')))
        elif node.get('clickable')=='true':
            import re
            box=[int(value) for value in re.findall(r'\d+',node.get('bounds',''))]
            if len(box)==4 and box[3]<=280:
                print('HEADER_CONTROL',node.get('class'),node.get('bounds'))
finally:
    if os.path.exists(path): os.remove(path)
