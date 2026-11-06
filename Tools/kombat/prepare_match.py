"""Prepare Scorpion vs Sub-Zero through original cabinet inputs, without ROM/RAM cheats."""
exec(open('/opt/ghostgram-kombat-build/probe_original.py').read().split('start=time.monotonic()')[0])
def tick(a=0,b=0,count=1):
    for _ in range(count):lib.gg_step(a,b,1)
def press(a=0,b=0):tick(a,b,2);tick(count=12)
def fighters():return [lib.gg_read_word(0x2099f6),lib.gg_read_word(0x209a52)]
tick(count=2350)
for _ in range(10):press(4,0)
press(8,0);tick(count=40);press(0,8);tick(count=40)
print('Selection starts',fighters(),flush=True)
policy0={0:128,1:32,2:128,3:128,5:32,6:64}
policy1={0:128,1:128,2:128,3:128,4:16,6:64}
for step in range(14):
    ids=fighters();print('Selection',step,ids,flush=True)
    if ids==[4,5]:break
    if ids[0] not in range(7) or ids[1] not in range(7):raise ValueError('Not on selection screen')
    press(policy0.get(ids[0],0),policy1.get(ids[1],0))
else:raise ValueError('Could not select original fighters')
press(2,2)
for frame in range(1600):
    tick()
    if frame>30 and [lib.gg_read_byte(0x209a04),lib.gg_read_byte(0x209a60)]==[161,161] and fighters()==[4,5]:
        tick(count=12)
        size=lib.gg_save_size();data=C.create_string_buffer(size);lib.gg_save.argtypes=[C.c_void_p,C.c_size_t]
        if lib.gg_save(data,size)!=1:raise ValueError('Snapshot failed')
        (store/'match.state').write_bytes(data.raw);image(9999)
        print('Prepared original match',size,'bytes',fighters(),flush=True)
        break
else:raise ValueError('Match did not start')
lib.gg_close()
