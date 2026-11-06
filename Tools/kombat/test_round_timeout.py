from pathlib import Path
from deploy import connect,run
code='''import subprocess,json
p=subprocess.Popen(['/opt/ghostgram-kombat/server','--worker'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
assert json.loads(p.stdout.readline())['op']=='ready'
try:
 health=[161,161]
 for frame in range(6500):
  # Real cabinet input only: approach, land a few punches, then wait for time-out.
  buttons=128 if frame<400 else (2 if health[1]==161 and frame%24<3 else 0)
  p.stdin.write(json.dumps(dict(op='step',a=buttons,b=0))+'\\n');p.stdin.flush();v=json.loads(p.stdout.readline())
  health=v['health']
  if v['rounds']!=[0,0]:
   assert v['rounds']==[1,0],v
   assert all(h>0 for h in v['health']),('KO rather than time-out',v)
   print('PASS: original timer awards round at nonzero health',v['frame'],v['health'],v['rounds']);break
 else:raise AssertionError('No native round time-out detected')
finally:p.terminate();p.wait(timeout=5)
'''
with connect() as c:
    with c.open_sftp() as s:
        with s.open('/opt/ghostgram-kombat-build/test-round-timeout.py','w') as f:f.write(code)
    print(run(c,'python3 /opt/ghostgram-kombat-build/test-round-timeout.py'))
