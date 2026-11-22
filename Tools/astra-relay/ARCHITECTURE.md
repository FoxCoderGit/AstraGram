# AstraRelay

Build 139 labels tgnet tunnels as control, file transfer, or voice-message transfer. A server policy restricting media closes existing media and unclassified tunnels immediately. The relay rejects new media and unclassified handshakes before dialing Telegram. Updated clients route rejected media directly and recheck eligibility after a minute. Older clients fall back to direct Telegram for all traffic. The authenticated client supplies the category because MTProto remains opaque to the relay.

Build 132 counts accepted WSS streams with a scoped lifetime in the native transport. A failed auxiliary connection cannot start the global technical recovery timer while an established stream remains. After the last established stream closes, failed attempts again use the 30-second recovery and one-hour cooldown. Administrative denials and permission expiry still override active streams. A one-time upgrade migration removes cooldowns produced by the previous accounting bug without shortening administrative denials.

Build 131 deduplicates network callbacks by active network identity, connectivity and VPN state. A failed Country.is lookup falls back to `https://ipwho.is/` (`success` and `country_code`), with the same one-hour cache. An unknown exit remains ineligible. Operational logcat diagnostics use the AstraRelay tag and contain state and exception class names, never tokens, IP addresses or account identifiers.

Scope: native tgnet TCP connections, including generic RPC, media/CDN uploads and downloads, and push connections. Voice/video WebRTC media is excluded by the owner's choice. No device-wide network interception, VPN, SOCKS or MTProto-proxy protocol is used.

Build 130 adds a separate network eligibility gate: active Android VPN transports use direct Telegram, as do public IP addresses outside Russia. Country is looked up via `https://api.country.is/` over verified HTTPS, without account identifiers or explicit IP parameters. The response is cached for one hour and invalidated on network changes. Lookup failure/unknown country also uses direct Telegram, and is retried. This gate does not change administrative denial or technical cooldown deadlines. Native workers are woken when eligibility changes, and existing MTProto sessions reconnect through tgnet's request retry mechanism. IP country is an estimate of the network exit, not a claim about the user's physical location.

The native ConnectionSocket opens a bounded local socket-pair transport. Its worker encapsulates the unchanged MTProto byte stream in authenticated binary WebSocket frames over TLS. Go verifies device authorization and forwards bytes to an allowlisted Telegram endpoint without interpreting or decrypting MTProto. A broken stream is closed and re-established by tgnet's existing reconnection/request retry logic; encrypted stream bytes must never be replayed into a new transport session.

Routing has separate states for initial access lookup, active relay, technical recovery, technical cooldown, and administrative denial. Technical recovery lasts 30 seconds, followed by direct Telegram and a retry in one hour. A verified administrative denial immediately closes relay streams and suppresses relay attempts until the next access check in 24 hours. Permission checks and authentication renewal use an independent HTTPS control plane. Timing uses monotonic clocks while running; persistent deadlines are revalidated after reboot.

Clients prove possession of their existing Android Keystore P-256 device key. The server verifies the application's signed device lease and a single-use policy challenge. Connection proofs bind a nonce, timestamp, destination and token digest; replayed proofs are rejected. There is no shared client password or VPS credential in the APK.

Access replies and endpoint/pin updates are signed with the established project policy signing key, verified against the public project key shipped with the application, bound to the requesting device and challenge, and checked for rollback and expiry. The server signing key, TLS key and token MAC key stay on the VPS.

Production destination addresses must be IP literals within the official Telegram CIDRs (https://core.telegram.org/resources/cidr.txt), on the permitted Telegram transport ports. Private, loopback, link-local, multicast and arbitrary Internet destinations are refused. Limits apply to authentication, frame sizes, queued bytes and simultaneous device/IP connections. Tokens, proofs and traffic bodies are not logged.

The service uses an isolated systemd unit and a dedicated TLS port; existing HTTPS, update, verification and unrelated services remain in place. Administrative access uses a local Unix socket, never an unauthenticated public endpoint.

## Deployment and operation

The current endpoint is `vpn.codebyfox.ru:9446`; port 443 remains assigned to the existing service. `config.example.json` and `astra-relay.service` show the production paths. The unit reads TLS and signing material through systemd credentials, runs as `astra-relay`, and stores only its token MAC key and administrative policy in `/var/lib/astra-relay`. The `ghostgram-license` service user belongs to the relay group for access to `/run/astra-relay/admin.sock` (0660).

Build with Go 1.22 or later: `go test -race ./...`, then `go build -trimpath -ldflags="-s -w" -o astra-relay .`. Keep VPS credentials and signing keys outside staging source exports.

Global and per-device access restrictions close affected tunnels with authenticated WebSocket code 4003. A reconnect uses code 1012 and preserves the Telegram session. After access is restored, the client checks again when its current 24-hour denial period expires. Access restrictions are keyed to installation identity and cover all Telegram accounts on that installation.

Certificate renewal restarts the relay so systemd reloads the new TLS credentials and signed SPKI configuration. A client experiencing a TLS handshake failure requests a fresh signed policy, with bounded retries and the same technical fallback. Unauthenticated HTTP/TLS connections, authenticated streams, frame sizes and queued bytes all have limits.

`test-native.cmd` checks the pure C++ routing policy and WebSocket framing on Windows. `go test -race` also exercises an actual TLS WebSocket tunnel against a byte-for-byte test upstream. `live_probe.py` runs on the VPS with a synthetic device lease and verifies a real unauthenticated Telegram `req_pq_multi`/`resPQ` exchange plus immediate per-device denial. It uses no user's Telegram session or messages and restores its own ACL entry afterwards. These checks supplement, rather than replace, tests on an Android device during background operation and network changes.
