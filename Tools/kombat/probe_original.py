"""Headless original-ROM boot/input probe. Capture only arcade framebuffer, never Telegram."""
import ctypes as C,pathlib,json,time,zlib,struct
ROOT=pathlib.Path('/opt/ghostgram-kombat-build/core')
lib=C.CDLL(str(ROOT/'build/libghostkombat.so'))
lib.gg_open.argtypes=[C.c_char_p,C.c_char_p];lib.gg_fps.restype=C.c_double;lib.gg_video_hash.restype=C.c_uint64
lib.gg_video.argtypes=[C.c_void_p,C.c_size_t];lib.gg_save_size.restype=C.c_size_t
store=ROOT/'probe';store.mkdir(exist_ok=True)
assert lib.gg_open(str(ROOT/'mkla4.zip').encode(),str(store).encode())==1
print('FPS',lib.gg_fps(),flush=True)
buffer=C.create_string_buffer(1024*1024*4)
def image(frame):
    packed=lib.gg_video(buffer,len(buffer));w,h=packed>>16,packed&65535
    if not w or not h:return
    raw=buffer.raw[:w*h*4];data=b''.join(b'\0'+raw[y*w*4:(y+1)*w*4] for y in range(h))
    def chunk(name,content):return struct.pack('!I',len(content))+name+content+struct.pack('!I',zlib.crc32(name+content)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(data))+chunk(b'IEND',b'')
    (store/('frame-'+str(frame)+'.png')).write_bytes(png)
start=time.monotonic()
for frame in range(4000):
    a=b=0
    if 260<=frame<264:a=b=4
    if 310<=frame<314:a=b=8
    if 500<=frame<504:a=2;b=2
    if frame>1000 and frame%200<4:a=b=8
    if frame>1100 and frame%200>=100 and frame%200<104:a=b=4
    if frame in (180,300,400,500,600,800,999,1500,2000,2500,3000,3999):image(frame)
    lib.gg_step(a,b,1)
    if frame%300==0:print(frame,'health',lib.gg_read_byte(0x209a04),lib.gg_read_byte(0x209a60),'fighters',lib.gg_read_word(0x2099f6),lib.gg_read_word(0x209a52),'hash',lib.gg_video_hash(),flush=True)
size=lib.gg_save_size();print('state bytes',size,'1000 frames seconds',time.monotonic()-start,flush=True)
if size:
    data=C.create_string_buffer(size);lib.gg_save.argtypes=[C.c_void_p,C.c_size_t];lib.gg_load.argtypes=[C.c_void_p,C.c_size_t]
    assert lib.gg_save(data,size)==1
    hashes=[]
    for i in range(60):lib.gg_step(128 if i<20 else 2,64 if i<20 else 512,1);hashes.append(lib.gg_video_hash())
    assert lib.gg_load(data,size)==1
    replay=[]
    for i in range(60):lib.gg_step(128 if i<20 else 2,64 if i<20 else 512,1);replay.append(lib.gg_video_hash())
    assert replay==hashes,'Original core rollback differs'
    print('ROLLBACK PASS: restored original machine and reproduced all 60 framebuffer hashes.',flush=True)
lib.gg_close()
