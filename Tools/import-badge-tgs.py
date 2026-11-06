"""Import the supplied one-frame TGS artwork as native Android vectors, without redrawing."""
from pathlib import Path
import gzip
import hashlib
import json
import math
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
ns='http://schemas.android.com/apk/res/android';ET.register_namespace('android',ns)
def attr(**values):return {f'{{{ns}}}{key}':str(value) for key,value in values.items()}
def value(item,default):
    if item is None:return default
    if item.get('a',0)!=0:raise ValueError('Animated input requires RLottie, not static conversion')
    return item['k']
def group(parent,transform):
    position=value(transform.get('p'),[0,0]);anchor=value(transform.get('a'),[0,0]);scale=value(transform.get('s'),[100,100])
    if value(transform.get('sk'),0)!=0:raise ValueError('Skew not supported')
    if value(transform.get('o'),100)!=100:raise ValueError('Layer opacity not supported')
    return ET.SubElement(parent,'group',attr(translateX=position[0]-anchor[0],translateY=position[1]-anchor[1],pivotX=anchor[0],pivotY=anchor[1],scaleX=scale[0]/100,scaleY=scale[1]/100,rotation=value(transform.get('r'),0)))
def path_data(shape):
    points=shape['v'];incoming=shape['i'];outgoing=shape['o']
    def point(p):return ','.join(format(float(n),'.9g') for n in p)
    commands=['M'+point(points[0])]
    for index in range(1,len(points)+(1 if shape.get('c') else 0)):
        previous=(index-1)%len(points);current=index%len(points)
        first=[points[previous][i]+outgoing[previous][i] for i in range(2)]
        second=[points[current][i]+incoming[current][i] for i in range(2)]
        commands.append('C'+point(first)+' '+point(second)+' '+point(points[current]))
    if shape.get('c'):commands.append('Z')
    return ' '.join(commands)
def shapes(parent,items):
    transforms=[item for item in items if item['ty']=='tr']
    target=group(parent,transforms[0]) if transforms else parent
    paths=[]
    for item in items:
        kind=item['ty']
        if kind=='gr':shapes(target,item['it'])
        elif kind=='sh':paths.append(path_data(value(item['ks'],None)))
        elif kind=='fl':
            color=value(item.get('c'),[1,1,1,1]);hexcolor='#'+''.join(f'{round(c*255):02x}' for c in color[:3])
            ET.SubElement(target,'path',attr(pathData=' '.join(paths),fillColor=hexcolor,fillAlpha=value(item.get('o'),100)/100,fillType='evenOdd' if item.get('r',1)==2 else 'nonZero'));paths=[]
        elif kind!='tr':raise ValueError('Unsupported shape '+kind)
    if paths:raise ValueError('Unfilled paths')
def convert(source,name):
    data=json.loads(gzip.decompress(source.read_bytes()))
    assert data['op']-data['ip']==1,'Expected supplied single-frame artwork'
    assets={a['id']:a for a in data.get('assets',[])}
    vector=ET.Element('vector',attr(width='24dp',height='24dp',viewportWidth=data['w'],viewportHeight=data['h']))
    def layers(parent,entries):
        for layer in entries:
            node=group(parent,layer.get('ks',{}))
            if layer['ty']==0:layers(node,assets[layer['refId']]['layers'])
            elif layer['ty']==4:shapes(node,layer['shapes'])
            else:raise ValueError('Unsupported layer')
    layers(vector,data['layers']);ET.indent(vector)
    output=root/'TMessagesProj/src/main/res/drawable'/f'veyl_badge_{name}.xml'
    ET.ElementTree(vector).write(output,encoding='utf-8',xml_declaration=True)
    original=root/'TMessagesProj/src/main/res/raw'/f'veyl_badge_{name}.tgs';original.write_bytes(source.read_bytes())
    return {'role':name,'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'frames':1,'native_vector':str(output.relative_to(root))}
desktop=Path.home()/'Desktop'
proof=[convert(desktop/'для разработчика/file_001.tgs','creator'),convert(desktop/'обычная/file_001.tgs','supporter')]
(root.parent/'Reports/veyl110-badge-artwork.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(proof,ensure_ascii=True))
