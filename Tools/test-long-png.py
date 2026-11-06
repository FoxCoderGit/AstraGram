"""Check the actual streaming encoder against independent PNG decoding, under a 16 MB JVM heap."""
from pathlib import Path
import subprocess,tempfile,struct,zlib
from PIL import Image

root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='ghost-png-') as folder:
    work=Path(folder)
    source=work/'PngTest.java'
    source.write_text('''import java.io.*;import org.telegram.messenger.GhostLongPng;
public class PngTest {public static void main(String[] args)throws Exception{
 int width=91,total=25003;boolean rgb=args.length>1;try(GhostLongPng png=new GhostLongPng(new File(args[0]),width,rgb)){
  for(int y=0;y<total;){int count=Math.min(13,total-y);int[] pixels=new int[width*count];
   for(int r=0;r<count;r++)for(int x=0;x<width;x++)pixels[r*width+x]=((x%3==0?127:255)<<24)|((x&255)<<16)|(((y+r)&255)<<8)|((x+y+r)&255);
   png.appendRows(pixels,count);y+=count;}
  if(png.height()!=total)throw new AssertionError("height");png.finish();}
}}''',encoding='utf-8')
    subprocess.run(['javac','-d',str(work),str(root/'TMessagesProj/src/main/java/org/telegram/messenger/GhostLongPng.java'),str(source)],check=True)
    png=work/'long.png'
    subprocess.run(['java','-Xmx16m','-cp',str(work),'PngTest',str(png)],check=True)
    data=png.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n'
    pos=8;idat=[];types=[]
    while pos<len(data):
        size=struct.unpack_from('>I',data,pos)[0];kind=data[pos+4:pos+8];payload=data[pos+8:pos+8+size]
        assert zlib.crc32(kind+payload)&0xffffffff==struct.unpack_from('>I',data,pos+8+size)[0]
        if kind==b'IDAT':idat.append(payload)
        types.append(kind);pos+=12+size
    assert types[0]==b'IHDR' and types[-1]==b'IEND' and types.count(b'IHDR')==1
    assert len(zlib.decompress(b''.join(idat)))==(91*4+1)*25003
    with Image.open(png) as image:
        assert image.size==(91,25003)
        for y in [0,12,8191,8192,20001,25002]:
            for x in [0,1,45,90]:assert image.getpixel((x,y))==(x&255,y&255,(x+y)&255,127 if x%3==0 else 255)
    subprocess.run(['java','-Xmx16m','-cp',str(work),'PngTest',str(png),'rgb'],check=True)
    with Image.open(png) as image:
        assert image.size==(91,25003) and image.mode=='RGB'
        for y in [0,12,8191,8192,20001,25002]:
            for x in [0,1,45,90]:assert image.getpixel((x,y))==(x&255,y&255,(x+y)&255)
print('PASS: ONE 25,003-row PNG, bounded JVM heap, multiple stripes/IDAT chunks, CRCs, dimensions, independent pixel-exact RGBA decode.')
