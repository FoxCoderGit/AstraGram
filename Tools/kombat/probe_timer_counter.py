from pathlib import Path
from deploy import connect,run
root=Path(__file__).resolve().parent
prefix=(root/'probe_original.py').read_text().split('start=time.monotonic()')[0]
suffix='''
lib.gg_step(0,0,0);data=(ROOT/'probe/match.state').read_bytes();lib.gg_load.argtypes=[C.c_void_p,C.c_size_t];assert lib.gg_load(data,len(data))==1
mem=C.create_string_buffer(131072);lib.gg_ram.argtypes=[C.c_void_p,C.c_size_t]
samples=[]
for j in range(5):
    for i in range(180):lib.gg_step(0,0,1)
    lib.gg_ram(mem,len(mem));samples.append(mem.raw)
    image(7000+j)
for offset in range(len(mem)):
    values=[s[offset] for s in samples]
    if values==sorted(values,reverse=True) and len(set(values))>=2 and all(0<=v<=9 for v in values):print(hex(0x200000+offset),values,flush=True)
for offset in range(0x9aba,0x9ace,2):print('TIMER',hex(0x200000+offset),[s[offset]|(s[offset+1]<<8) for s in samples],flush=True)
lib.gg_close()
'''
with connect() as c:
    with c.open_sftp() as s:
        with s.open('/opt/ghostgram-kombat-build/core/counter.py','w') as f:f.write(prefix+suffix)
    print(run(c,'python3 /opt/ghostgram-kombat-build/core/counter.py'))
    with c.open_sftp() as s:s.get('/opt/ghostgram-kombat-build/core/probe/frame-7004.png',str(root/'timer-counter.png'))
