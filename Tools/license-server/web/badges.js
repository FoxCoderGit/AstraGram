'use strict';
function renderBadges(){
    const query=$('badgeSearch').value.trim().toLowerCase();
    const matches=(state.badges||[]).filter(b=>(b.user_id+' '+b.label).toLowerCase().includes(query));
    $('badgeCount').textContent=`Выдано: ${(state.badges||[]).length}. Найдено: ${matches.length}.`;
    $('badgeRows').innerHTML=matches.slice(0,500).map(b=>`<tr><td>${esc(b.user_id)}<small>${esc(b.label)}</small></td><td>${b.type==='creator'?'Создатель AstraGram':'Поддержал проект'}</td><td>${date(b.updated_at)}</td><td><button data-edit-badge="${esc(b.user_id)}" class="secondary">Изменить</button> <button data-delete-badge="${esc(b.user_id)}" class="danger">Отозвать</button></td></tr>`).join('');
}
$('badgeSearch').oninput=renderBadges;
$('badgeForm').onsubmit=async e=>{
    e.preventDefault();const form=e.target,button=e.submitter;button.disabled=true;
    try{await api('badges',{user_id:form.elements.user_id.value.trim(),type:form.elements.type.value,label:form.elements.label.value});form.reset();await load();notify('Галочка сохранена. Клиенты получат её при следующей проверке.');}
    catch(error){notify(error.message,true);}finally{button.disabled=false;}
};
$('badgeRows').onclick=async e=>{
    const target=e.target.closest('button');if(!target)return;
    const id=target.dataset.editBadge||target.dataset.deleteBadge;
    const badge=(state.badges||[]).find(b=>b.user_id===id);if(!badge)return;
    if(target.dataset.editBadge){
        const form=$('badgeForm');form.elements.user_id.value=badge.user_id;form.elements.type.value=badge.type;form.elements.label.value=badge.label;
        form.scrollIntoView({behavior:'smooth',block:'center'});form.elements.type.focus();return;
    }
    if(!confirm('Отозвать галочку у пользователя '+id+'?'))return;
    try{await api('badges',{action:'delete',user_id:id});await load();notify('Галочка отозвана.');}catch(error){notify(error.message,true);}
};
if(state)renderBadges();
