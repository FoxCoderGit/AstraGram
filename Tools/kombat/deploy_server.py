from pathlib import Path
from deploy import connect,run
ROOT=Path(__file__).resolve().parent
with connect() as c:
    run(c,'install -d -m 755 /opt/ghostgram-kombat-build/rust/server/src')
    with c.open_sftp() as s:
        with s.open('/opt/ghostgram-kombat-build/rust/Cargo.toml','w') as f:f.write('[workspace]\nmembers=["server"]\nresolver="2"\n[profile.release]\nopt-level=3\nlto=true\ncodegen-units=1\nstrip=true\n')
        for name in ('Cargo.toml','src/main.rs','src/auth.rs','src/worker.rs','src/udp.rs'):s.put(str(ROOT/'server'/name),'/opt/ghostgram-kombat-build/rust/server/'+name)
        s.put(str(ROOT/'Cargo.lock'),'/opt/ghostgram-kombat-build/rust/Cargo.lock')
    print(run(c,'RUSTUP_HOME=/opt/ghostgram-kombat-toolchain/rustup CARGO_HOME=/opt/ghostgram-kombat-toolchain/cargo PATH=/opt/ghostgram-kombat-toolchain/cargo/bin:$PATH cargo build --release --manifest-path /opt/ghostgram-kombat-build/rust/Cargo.toml -p ghost-kombat-server >/opt/ghostgram-kombat-build/server-build.log 2>&1; result=$?; tail -n 12 /opt/ghostgram-kombat-build/server-build.log; exit $result'))
    run(c,'id ghostgram-kombat >/dev/null 2>&1 || useradd --system --home /var/lib/ghostgram-kombat --shell /usr/sbin/nologin ghostgram-kombat')
    run(c,'install -d -m 755 /opt/ghostgram-kombat; install -d -m 750 -o ghostgram-kombat -g ghostgram-kombat /var/lib/ghostgram-kombat; install -m 755 /opt/ghostgram-kombat-build/rust/target/release/ghost-kombat-server /opt/ghostgram-kombat/server; install -m 644 /opt/ghostgram-kombat-build/core/build/libghostkombat.so /var/lib/ghostgram-kombat/libghostkombat.so; install -m 644 /var/lib/ghostgram-license/lease-public.der /var/lib/ghostgram-kombat/license-public.der')
    run(c,"test -f /var/lib/ghostgram-kombat/server.key || openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 1825 -keyout /var/lib/ghostgram-kombat/server.key -out /var/lib/ghostgram-kombat/server.crt -subj '/CN=GhostGram Kombat' -addext 'subjectAltName=IP:93.88.204.2' >/dev/null 2>&1; chown ghostgram-kombat:ghostgram-kombat /var/lib/ghostgram-kombat/server.key /var/lib/ghostgram-kombat/server.crt; chmod 600 /var/lib/ghostgram-kombat/server.key")
    assets=ROOT.parents[1]/'TMessagesProj/src/main/assets/kombat';assets.mkdir(exist_ok=True)
    with c.open_sftp() as s:
        s.put(str(ROOT/'original/mkla4.zip'),'/var/lib/ghostgram-kombat/mkla4.zip')
        s.put(str(ROOT/'original/match.state'),'/var/lib/ghostgram-kombat/match.state')
        s.put(str(ROOT/'ghostgram-kombat.service'),'/etc/systemd/system/ghostgram-kombat.service')
        s.get('/var/lib/ghostgram-kombat/server.crt',str(assets/'server.crt'))
    run(c,'chmod 644 /var/lib/ghostgram-kombat/mkla4.zip /var/lib/ghostgram-kombat/match.state; systemctl daemon-reload; systemctl enable ghostgram-kombat.service >/dev/null 2>&1; systemctl restart ghostgram-kombat.service; systemctl is-active ghostgram-kombat.service')
    print('Independent Rust WSS service deployed on 9444. Public TLS certificate copied to assets.')
