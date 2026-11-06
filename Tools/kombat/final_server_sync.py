from pathlib import Path
from deploy import connect,run
root=Path(__file__).resolve().parent
with connect() as c:
    with c.open_sftp() as s:s.put(str(root/'original/match.state'),'/var/lib/ghostgram-kombat/match.state')
    print(run(c,'chmod 644 /var/lib/ghostgram-kombat/match.state; systemctl restart ghostgram-kombat.service; systemctl is-active ghostgram-kombat.service'))
