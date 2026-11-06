from pathlib import Path
from deploy import connect,run
root=Path(__file__).resolve().parent
code=(root/'probe_original.py').read_text().split('start=time.monotonic()')[0]+'''
lib.gg_step(0,0,0)
data=(ROOT/'probe/match.state').read_bytes()
lib.gg_load.argtypes=[C.c_void_p,C.c_size_t]
assert lib.gg_load(data,len(data))==1
lib.gg_logic_hash.restype=C.c_uint64
hashes=[]
for i in range(60):
    lib.gg_step(128 if i<30 else 2,64 if i<20 else 256,1)
    hashes.append(str(lib.gg_logic_hash()))
image(8888)
print('COLDSTATE',lib.gg_read_word(0x2099f6),lib.gg_read_byte(0x209a04),len(set(buffer.raw)),flush=True)
assert len(set(buffer.raw))>20,'Blank cold-load framebuffer'
(ROOT/'probe/reference.json').write_text(json.dumps(hashes))
lib.gg_close()
'''
with connect() as c:
    with c.open_sftp() as s:
        with s.open('/opt/ghostgram-kombat-build/core/cold.py','w') as f:f.write(code)
    print(run(c,'python3 /opt/ghostgram-kombat-build/core/cold.py'))
    with c.open_sftp() as s:
        s.get('/opt/ghostgram-kombat-build/core/probe/frame-8888.png',str(root/'cold.png'))
        s.get('/opt/ghostgram-kombat-build/core/probe/reference.json',str(root.parents[1]/'TMessagesProj/src/main/assets/kombat/reference.json'))
