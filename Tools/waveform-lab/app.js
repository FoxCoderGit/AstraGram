'use strict';
const E = WaveLab, $ = id => document.getElementById(id);
OPTIMIZED_PRESETS.forEach((p,i)=>{const option=document.createElement('option');option.value='fit-'+i;option.textContent=p.name;$('preset').appendChild(option);});
let bounds, history = [], stroke = null, latest, lastIndex = null;
const canvas = $('editor'), ctx = canvas.getContext('2d');
function width() { return E.clamp(parseInt($('width').value, 10) || 180, 30, 360); }
function remember() { if (history.length === 40) history.shift(); history.push(bounds.map(b => ({...b}))); }
function seed(save = true) {
  if (save && bounds) remember();
  const record=OPTIMIZED_PRESETS[+$('preset').value.slice(4)];
  const span=+$('span').value/(record.span*100);
  bounds=Array.from({length:100},(_,i)=>{const p=((i+.5)/100-.5)/span+.5;return p<0||p>=1?{top:0,bottom:0}:E.toBounds([record.levels[Math.min(99,Math.floor(p*100))]])[0];});
  render();
}
function roundedRect(c, x, y, w, h, r) { c.beginPath(); c.roundRect(x,y,w,h,r); c.fill(); }
function plotSVG(items, plotWidth, source = false) {
  const center = 14;
  const bars = items.map(b => '<rect x="' + (6+b.x) + '" y="' + (center+b.top) + '" width="' + b.width + '" height="' + (b.bottom-b.top) + '" rx="' + Math.min(1,b.width/2) + '"/>').join('');
  return '<svg xmlns="http://www.w3.org/2000/svg" width="' + (plotWidth+12) + '" height="28" viewBox="0 0 ' + (plotWidth+12) + ' 28" role="img" aria-label="' + (source?'Исходный рисунок':'Отрисовка Telegram') + '"><path d="M6 14H' + (plotWidth+6) + '" stroke="#345066" stroke-width=".2"/><g fill="' + (source?'#f38bb1':'#59d9d1') + '">' + bars + '</g></svg>';
}
function render() {
  const levels = E.project(bounds, $('projection').value), bytes = E.pack(levels, $('codec').value);
  const w = width(), bars = E.geometry(bytes, w), step = canvas.width / 100;
  const source = bounds.flatMap((b,i) => (b.segments || [[b.top,b.bottom]]).map(s => ({x:i*w/100,width:w/100*.66,top:s[0]-1,bottom:s[1]+1})));
  latest = {levels, bytes, bars, source, width:w};
  ctx.clearRect(0,0,canvas.width,canvas.height);
  ctx.strokeStyle = '#2c3b50'; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.moveTo(0,150); ctx.lineTo(1000,150); ctx.stroke();
  for (let i=0;i<100;i++) {
    const h = 2 + 122*levels[i]/31;
    ctx.fillStyle = '#59d9d1'; roundedRect(ctx,i*step+2,150-h,step-4,h*2,2);
  }
  if ($('overlay').checked) {
    ctx.strokeStyle = '#f38bb1'; ctx.lineWidth = 2.5;
    ['top','bottom'].forEach(edge => {
      ctx.beginPath(); bounds.forEach((b,i) => { const x=(i+.5)*step,y=150+b[edge]*17.4; if(!i)ctx.moveTo(x,y);else ctx.lineTo(x,y); }); ctx.stroke();
    });
  }
  $('source').innerHTML = plotSVG(source,w,true);
  $('telegram').innerHTML = plotSVG(bars,w);
  const scale = +$('zoom').value;
  $('chat-wave').innerHTML = plotSVG(bars,w);
  const svg = $('chat-wave').querySelector('svg'); svg.style.width=(w+12)*scale+'px'; svg.style.height=28*scale+'px';
  const offsets = bounds.filter(b => Math.abs(b.top+b.bottom)>.06).length;
  const gaps = bounds.filter(b => b.segments && b.segments.length > 1).length;
  const decoded = E.heights(bytes,100).map(h=>Math.round(h*31/7));
  const altered = decoded.filter((v,i)=>v!==levels[i]).length;
  $('finding').textContent = offsets ? 'У '+offsets+' из 100 столбиков центр смещён. Обычная волна передаёт только высоту: после отправки смещение потеряется. Сравни розовый рисунок и бирюзовый результат. Для точного такого контура потребовалась бы другая отрисовка клиента.' : 'Рисунок симметричен: смещение краёв не требуется. При отправке сохраняется высота, но выбор столбиков и ширина сообщения могут скрыть мелкие детали.';
  if(altered) $('finding').textContent += ' Выбранная нативная упаковка меняет '+altered+' высот при чтении этим Android-клиентом.';
  if(gaps) $('finding').textContent += ' У '+gaps+' столбиков есть внутренний разрыв: обычная волна заполнит его, даже если смещение не нужно.';
  $('stats').textContent = '100 значений · 63 байта · '+bars.length+' полосок в чате · максимум 16 dp · изменено упаковкой: '+altered;
  $('span-value').textContent=$('span').value+'%';
  $('selection').textContent=$('preset').selectedOptions[0].textContent;
  $('data').value=JSON.stringify(levels);
  $('hex').textContent=Array.from(bytes,v=>v.toString(16).padStart(2,'0')).join(' ');
}
$('preset').addEventListener('change',()=>{$('span').value=Math.round(OPTIMIZED_PRESETS[+$('preset').value.slice(4)].span*100);seed();});
['span'].forEach(id=>$(id).addEventListener('change',()=>seed()));
['projection','width','codec','zoom','overlay'].forEach(id=>$(id).addEventListener('input',render));
$('reset').onclick=()=>seed();
$('clear').onclick=()=>{remember();bounds=Array.from({length:100},()=>({top:0,bottom:0}));render();};
$('undo').onclick=()=>{if(history.length){bounds=history.pop();render();}};
function pointer(event) {
  const rect=canvas.getBoundingClientRect(), x=(event.clientX-rect.left)*100/rect.width;
  const y=E.clamp(((event.clientY-rect.top)/rect.height-.5)*300/17.4,-7,7);
  return {index:E.clamp(Math.floor(x),0,99),y};
}
function drawPointer(event) {
  const point=pointer(event), previous=lastIndex||point, count=Math.abs(point.index-previous.index);
  for(let i=0;i<=count;i++) {
    const index=count?previous.index+Math.sign(point.index-previous.index)*i:point.index;
    const y=count?previous.y+(point.y-previous.y)*i/count:point.y;
    if($('offset').checked) {
      const b=bounds[index]; delete b.segments; if(stroke.edge==='top') b.top=Math.min(y,b.bottom); else b.bottom=Math.max(y,b.top);
    } else {const h=E.clamp(Math.round(Math.abs(y)*31/7),0,31)*7/31;bounds[index]={top:-h,bottom:h};}
  }
  lastIndex=point;render();
}
canvas.addEventListener('pointerdown',event=>{remember();const p=pointer(event);stroke={edge:Math.abs(p.y-bounds[p.index].top)<Math.abs(p.y-bounds[p.index].bottom)?'top':'bottom'};lastIndex=null;canvas.setPointerCapture(event.pointerId);drawPointer(event);});
canvas.addEventListener('pointermove',event=>{if(stroke)drawPointer(event);});
canvas.addEventListener('pointerup',()=>{stroke=null;lastIndex=null;});
canvas.addEventListener('pointercancel',()=>{if(stroke&&history.length)bounds=history.pop();stroke=null;lastIndex=null;render();});
function download(name, content, type) { const url=URL.createObjectURL(new Blob([content],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
$('export').onclick=()=>{download('waveform.json',JSON.stringify({version:1,levels:latest.levels,bounds,width:latest.width,codec:$('codec').value,projection:$('projection').value},null,2),'application/json');$('notice').textContent='JSON сохранён: высоты, исходная геометрия и настройки.';};
$('svg-export').onclick=()=>{
  const w=latest.width+12, a=plotSVG(latest.source,latest.width,true),b=plotSVG(latest.bars,latest.width);
  download('waveform-comparison.svg','<svg xmlns="http://www.w3.org/2000/svg" width="'+w*4+'" height="340" viewBox="0 0 '+w+' 85"><rect width="100%" height="100%" fill="#192432"/><text x="6" y="8" fill="#e8eef5" font-size="5">Source geometry</text><g transform="translate(0 12)">'+a+'</g><text x="6" y="49" fill="#e8eef5" font-size="5">Telegram Android</text><g transform="translate(0 53)">'+b+'</g></svg>','image/svg+xml');$('notice').textContent='Сравнение сохранено в SVG.';
};
$('import').addEventListener('change',async event=>{
  try {
    const file=event.target.files[0];if(!file)return;if(file.size>1000000)throw Error('Слишком большой файл');
    const data=JSON.parse(await file.text()), levels=Array.isArray(data)?data:data.levels;E.pack(levels);
    let imported=E.toBounds(levels);
    if(data.bounds) {
      if(data.bounds.length!==100||data.bounds.some(b=>!Number.isFinite(b.top)||!Number.isFinite(b.bottom)||b.top < -7||b.bottom>7||b.top>b.bottom))throw Error('Неверная геометрия');
      for(const b of data.bounds) if(b.segments && (!Array.isArray(b.segments)||b.segments.length>100||b.segments.some(s=>!Array.isArray(s)||s.length!==2||!s.every(v=>Number.isFinite(v)&&v>=-7&&v<=7)||s[0]>s[1])))throw Error('Неверные разрывы в столбике');
      imported=data.bounds;
    }
    remember();bounds=imported.map(b=>({...b}));
    if(data.width)$('width').value=E.clamp(data.width,30,360);
    if(['ghostgram','native'].includes(data.codec))$('codec').value=data.codec;
    if(['envelope','height'].includes(data.projection))$('projection').value=data.projection;
    render();$('notice').textContent='Рисунок открыт. Его можно редактировать.';
  }catch(error){$('notice').textContent='Не удалось открыть JSON: '+error.message;}
  event.target.value='';
});
$('preset').value='fit-0';$('span').value=18;seed(false);
const gallery=document.createElement('article');gallery.className='preset-gallery';
gallery.innerHTML='<h3>Шесть компактных заготовок</h3><p>Бриллиант, бабочка, бантик, стрела, рыба и песочные часы. Нажми на рисунок для редактирования. Предпросмотр при ширине волны 120, 180 и 240 dp.</p><div class="gallery-grid"></div>';
document.querySelector('.finding').before(gallery);
OPTIMIZED_PRESETS.forEach((p,i)=>{
  const card=document.createElement('button');card.className='preset-card';
  const heading=document.createElement('strong');heading.textContent=p.name;card.appendChild(heading);
  const note=document.createElement('p');note.textContent=p.note;card.appendChild(note);
  [120,180,240].forEach(w=>{const plot=document.createElement('div');plot.innerHTML=plotSVG(E.geometry(E.pack(p.levels),w),w);card.appendChild(plot);});
  card.onclick=()=>{$('preset').value='fit-'+i;$('preset').dispatchEvent(new Event('change'));$('editor').scrollIntoView({behavior:'smooth',block:'center'});};
  gallery.querySelector('.gallery-grid').appendChild(card);
});
