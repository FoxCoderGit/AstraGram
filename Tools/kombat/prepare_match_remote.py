from pathlib import Path
from deploy import connect,run
ROOT=Path(__file__).resolve().parent
with connect() as c:
    with c.open_sftp() as s:s.put(str(ROOT/'prepare_match.py'),'/opt/ghostgram-kombat-build/prepare_match.py')
    print(run(c,'python3 /opt/ghostgram-kombat-build/prepare_match.py >/opt/ghostgram-kombat-build/prepare-match.log 2>&1; result=$?; tail -n 20 /opt/ghostgram-kombat-build/prepare-match.log; exit $result'))
    with c.open_sftp() as s:
        s.get('/opt/ghostgram-kombat-build/core/probe/match.state',str(ROOT/'original/match.state'))
        s.get('/opt/ghostgram-kombat-build/core/probe/frame-9999.png',str(ROOT/'original/match.png'))
