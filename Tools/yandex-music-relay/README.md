# Private Yandex Music relay

Dedicated authenticated HTTPS forwarding on the owner's RU host. It accepts
only read requests to selected Yandex Music API routes and image/audio CDN
hosts. OAuth headers and audio remain in memory and are never logged or saved.
Both TLS hops validate their peer. The owner's relay can see requests in memory.

`relay.py` uses Python on Linux plus `cryptography` for device enrollment. `test_relay.py` exercises
scope checks, DNS restrictions, atomic single-use pairing, token revocation,
real local TLS, response lengths and rate limits. Production runs as the
`ghostgram-music` system user with a private data directory and bounded memory
and concurrency. No instrumentation or SSH credentials are shipped in Android.

Run on the development computer with Paramiko installed:

```powershell
python Tools/yandex-music-relay/manage.py inspect
python Tools/yandex-music-relay/manage.py deploy
python Tools/yandex-music-relay/manage.py probe
```

Commands prompt for the SSH password without echo. Known SSH host keys are
verified; the first key is recorded in the private project identity directory.
Deployment writes only the dedicated service/data/CA directories, installs its
systemd unit and permits TCP 8443 if UFW is active. It keeps existing servers
and listeners in place. Server CA private keys never leave the host.

ghost.98 connects to `https://music.codebyfox.ru` using system CA trust and
hostname verification. Caddy forwards to the private-CA HTTPS backend after
verifying its certificate. `relay-ca.pem` remains public and supports the
older ghost.97 IP endpoint. Private CA keys remain on the server.

ghost.98 enrolls automatically with a 90-second one-use challenge, a signed
GhostGram lease and a domain-separated ECDSA device proof. The lease public
key is public; the signing/private device keys never leave their owner.
Expired leases, other devices/apps, invalid issuers and replay are rejected.
Each installation receives a separate 30-day gateway token, sealed with
Android Keystore; the server stores its hash only. An expired/revoked gateway
token renews automatically without changing the personal Yandex login.
Every user signs in to their own Yandex account. No music token is embedded
or shared across users. The old pairing endpoint remains for compatibility.
`probe` uses a separate temporary
credential, checks real authenticated forwarding, blocks a private target and
revokes that test credential.

Client/server size limit is 128 MiB. Unknown-length responses use chunked
framing, so an aborted/oversized transfer is rejected instead of becoming a
silently truncated audio attachment. The client retains MP3 and duration checks.
