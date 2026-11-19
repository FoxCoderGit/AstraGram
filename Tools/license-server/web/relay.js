'use strict';
(() => {
    names.relay='AstraRelay';
    errors.relay_unavailable='AstraRelay сейчас недоступен. Остальные разделы панели работают.';
    const button=document.createElement('button');button.dataset.tab='relay';button.textContent='AstraRelay';
    document.querySelector('aside nav').append(button);
    const section=document.createElement('section');section.id='relay';section.className='tab';section.hidden=true;
    section.innerHTML=`<article><h2>AstraRelay</h2><p class="muted">Защищённая передача Telegram-трафика через VPS. Звонки передаются напрямую. Содержимое сообщений и ключи Telegram сервис не читает.</p><p id="relayStatus" role="status">Загрузка состояния…</p><div class="toolbar"><label class="switch"><input id="relayEnabled" type="checkbox"> Разрешить AstraRelay</label><button id="relaySave">Сохранить</button><button id="relayRefresh" class="secondary">Обновить</button><button id="relayReconnect" class="secondary">Переподключить туннели</button></div><p class="muted">При запрете клиенты сразу переходят на прямое соединение. Следующая проверка разрешения — через 24 часа. Переподключение туннелей не завершает сеансы Telegram.</p></article><div id="relayCards" class="cards"></div><article><h2>Доступ устройств</h2><p class="muted">Настройка действует на все Telegram-аккаунты этого устройства. Разрешение устройства не отменяет общий запрет сервиса.</p><div class="toolbar"><input id="relaySearch" placeholder="Устройство / пометка / идентификатор"></div><div class="tableWrap"><table><thead><tr><th>Устройство</th><th>Идентификатор</th><th>Соединения</th><th>Доступ</th><th>Действия</th></tr></thead><tbody id="relayDeviceRows"></tbody></table></div></article>`;
    document.querySelector('.workspace').insertBefore(section,$('updated'));
    let current=null,loading=false;
    const volume=n=>{n=Number(n)||0;const units=['Б','КБ','МБ','ГБ','ТБ'];let i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return n.toFixed(i?1:0)+' '+units[i];};
    const renderDevices=()=>{
        if(!current)return;
        const devices=new Map();
        for(const device of state?.devices||[])devices.set(device.id,{...device});
        for(const license of state?.licenses||[])if(license.device){const device=devices.get(license.device)||{id:license.device};if(license.label)device.label=license.label;devices.set(device.id,device);}
        for(const id of [...Object.keys(current.active_devices||{}),...Object.keys(current.policy.denied_devices||{})])if(!devices.has(id))devices.set(id,{id});
        const query=$('relaySearch').value.trim().toLowerCase();
        $('relayDeviceRows').innerHTML=[...devices.values()].filter(d=>[d.id,d.label,d.model,d.manufacturer].join(' ').toLowerCase().includes(query)).map(d=>{
            const denied=!!current.policy.denied_devices[d.id],count=Number(current.active_devices[d.id]||0),label=d.label||[d.manufacturer,d.model].filter(Boolean).join(' ')||'Устройство';
            return `<tr><td>${esc(label)}<small>${esc(d.version||'')}</small></td><td title="${esc(d.id)}"><code>${esc(d.id.slice(0,16))}…</code></td><td>${count}</td><td>${denied?'Запрещён':'Разрешён'}</td><td><button data-relay-device="${esc(d.id)}" data-relay-denied="${denied?'0':'1'}" class="${denied?'secondary':'danger'}">${denied?'Разрешить':'Запретить'}</button></td></tr>`;
        }).join('');
    };
    async function refresh(){
        if(loading)return;loading=true;
        try{
            current=await api('relay');$('relayEnabled').checked=!!current.policy.enabled;
            $('relayStatus').textContent=current.policy.enabled?'Сервис включён':'Сервис отключён администратором';$('relayStatus').className='';
            const cards=[['Соединения',Number(current.active_connections)||0],['Активные устройства',Object.keys(current.active_devices||{}).length],['От клиентов',volume(current.bytes_up)],['К клиентам',volume(current.bytes_down)]];
            $('relayCards').innerHTML=cards.map(([label,value])=>`<article><p class="muted">${esc(label)}</p><h2>${esc(value)}</h2></article>`).join('');
            $('relayStatus').textContent+=` · запущен ${date(current.started_at)} · максимум ${current.device_limit} соединений на устройство / ${current.connection_limit} всего`;
            renderDevices();
        }catch(error){$('relayStatus').textContent=error.message;$('relayStatus').className='error';}
        finally{loading=false;}
    }
    button.onclick=()=>{document.querySelectorAll('.tab').forEach(tab=>tab.hidden=tab!==section);document.querySelectorAll('[data-tab]').forEach(item=>item.classList.toggle('active',item===button));$('pageTitle').textContent=names.relay;refresh();};
    $('relaySearch').oninput=renderDevices;$('relayRefresh').onclick=refresh;
    $('relaySave').onclick=async()=>{
        const enabled=$('relayEnabled').checked;
        if(!confirm(enabled?'Разрешить AstraRelay? Клиенты с действующим запретом проверят доступ в свой срок.':'Отключить AstraRelay? Подключённые клиенты сразу перейдут на прямое соединение, повторная проверка — через 24 часа.'))return;
        try{await api('relay',{action:'enabled',enabled});await refresh();notify('Настройка AstraRelay сохранена.');}catch(error){notify(error.message,true);}
    };
    $('relayReconnect').onclick=async()=>{if(!confirm('Переподключить активные туннели? Сеансы Telegram сохранятся.'))return;try{await api('relay',{action:'reconnect'});await refresh();notify('Туннели переподключаются.');}catch(error){notify(error.message,true);}};
    $('relayDeviceRows').onclick=async event=>{
        const target=event.target.closest('[data-relay-device]');if(!target)return;
        const denied=target.dataset.relayDenied==='1';
        if(!confirm(denied?'Запретить AstraRelay на этом устройстве? Оно сразу перейдёт на прямое соединение на срок до следующей проверки через 24 часа.':'Разрешить AstraRelay на этом устройстве при следующей проверке?'))return;
        try{await api('relay',{action:'device',device:target.dataset.relayDevice,denied});await refresh();notify('Доступ устройства обновлён.');}catch(error){notify(error.message,true);}
    };
    setInterval(()=>{if(!section.hidden&&!$('app').hidden&&!document.hidden)refresh();},10000);
})();
