from pathlib import Path
from deploy import connect,run
ROOT=Path(__file__).resolve().parent
with connect() as c:
    with c.open_sftp() as s:
        s.put(str(ROOT/'arcade/bridge.c'),'/opt/ghostgram-kombat-build/core/arcade/bridge.c')
        s.put(str(ROOT/'original/mkla4.zip'),'/opt/ghostgram-kombat-build/core/mkla4.zip')
        s.put(str(ROOT/'probe_original.py'),'/opt/ghostgram-kombat-build/probe_original.py')
    print(run(c,'cmake --build /opt/ghostgram-kombat-build/core/build -j 2 >/opt/ghostgram-kombat-build/native-build.log 2>&1; python3 /opt/ghostgram-kombat-build/probe_original.py >/opt/ghostgram-kombat-build/probe.log 2>&1; result=$?; tail -n 18 /opt/ghostgram-kombat-build/probe.log; exit $result'))
    with c.open_sftp() as s:
        for frame in (180,300,400,500,600,800,999,1500,2000,2500,3000,3999):s.get('/opt/ghostgram-kombat-build/core/probe/frame-'+str(frame)+'.png',str(ROOT/('frame-'+str(frame)+'.png')))
