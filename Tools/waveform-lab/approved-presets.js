(function(root) {
  'use strict';
  // Exactly the six compact profiles selected by the user in the test gallery.
  const specs = [
    {id:'DIAMOND',name:'Бриллиант',span:18,points:[0,0,.5,31,1,0],note:'Ромб без внутренних граней.'},
    {id:'BUTTERFLY',name:'Бабочка',span:36,points:[0,0,.08,18,.19,31,.29,28,.4,9,.47,4,.5,23,.53,4,.6,9,.71,28,.81,31,.92,18,1,0],note:'Симметричные крылья и тело в середине.'},
    {id:'BOW',name:'Бантик',span:28,points:[0,23,.05,31,.2,24,.4,7,.46,5,.47,13,.53,13,.54,5,.6,7,.8,24,.95,31,1,23],note:'Две петли и центральный узел.'},
    {id:'ARROW',name:'Стрела',span:55,points:[0,4,.04,22,.07,4,.11,26,.14,4,.18,20,.21,4,.69,4,.7,31,1,0],note:'Оперение слева, наконечник справа.'},
    {id:'FISH',name:'Рыба',span:32,points:[0,28,.06,24,.22,4,.32,16,.48,27,.6,31,.75,25,.9,13,1,0],note:'Горизонтальный силуэт, направленный вправо.'},
    {id:'HOURGLASS',name:'Песочные часы',span:22,points:[0,31,.04,31,.46,3,.54,3,.96,31,1,31],note:'Поворот на 90°, две чаши с узкой перемычкой.'}
  ];
  function create(index,spanOverride) {
    const spec=specs[index];if(!spec)throw Error('Unknown preset');
    const span=(spanOverride===undefined?spec.span:spanOverride)/100;
    if(!Number.isFinite(span)||span<=0||span>1)throw Error('Invalid span');
    return Array.from({length:100},(_,i)=>{
      const x=((i+.5)/100-.5)/span+.5;if(x<0||x>1)return 0;
      let p=0;while(p+4<spec.points.length&&x>spec.points[p+2])p+=2;
      const t=(x-spec.points[p])/(spec.points[p+2]-spec.points[p]);
      return Math.max(0,Math.min(31,Math.round(spec.points[p+1]+t*(spec.points[p+3]-spec.points[p+1]))));
    });
  }
  const api={specs,create};
  if(typeof module!=='undefined')module.exports=api;else root.WaveShapes=api;
})(typeof window!=='undefined'?window:globalThis);
