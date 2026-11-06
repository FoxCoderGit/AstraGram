from pathlib import Path
from deploy import connect,run
root=Path(__file__).resolve().parent
with connect() as c:
    print(run(c,"install -d -m 700 /opt/ghostgram-kombat-build/network-fixture; cp /var/lib/ghostgram-kombat/server.crt /var/lib/ghostgram-kombat/server.key /var/lib/ghostgram-kombat/mkla4.zip /var/lib/ghostgram-kombat/match.state /var/lib/ghostgram-kombat/libghostkombat.so /opt/ghostgram-kombat-build/network-fixture/; python3 -m venv /opt/ghostgram-kombat-build/test-python; /opt/ghostgram-kombat-build/test-python/bin/pip -q install websockets cryptography"))
    with c.open_sftp() as s:s.put(str(root/'network_fixture_test.py'),'/opt/ghostgram-kombat-build/network-fixture/test.py')
    print(run(c,'/opt/ghostgram-kombat-build/test-python/bin/python /opt/ghostgram-kombat-build/network-fixture/test.py'))
