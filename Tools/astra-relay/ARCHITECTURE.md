# AstraRelay

Scope: native tgnet TCP connections, including generic RPC, media/CDN uploads and downloads, and push connections. Voice/video WebRTC media is excluded by the owner's choice. No device-wide network interception, VPN, SOCKS or MTProto-proxy protocol is used.

The native ConnectionSocket opens a bounded local socket-pair transport. Its worker encapsulates the unchanged MTProto byte stream in authenticated binary WebSocket frames over TLS. Go verifies device authorization and forwards bytes to an allowlisted Telegram endpoint without interpreting or decrypting MTProto. A broken stream is closed and re-established by tgnet's existing reconnection/request retry logic; encrypted stream bytes must never be replayed into a new transport session.

Routing has separate states for initial access lookup, active relay, technical recovery, technical cooldown, and administrative denial. Technical recovery lasts 30 seconds, followed by direct Telegram and a retry in one hour. A verified administrative denial immediately closes relay streams and suppresses relay attempts until the next access check in 24 hours. Permission checks and authentication renewal use an independent HTTPS control plane. Timing uses monotonic clocks while running; persistent deadlines are revalidated after reboot.

Clients prove possession of their existing Android Keystore P-256 device key. The server verifies the application's signed device lease and a single-use policy challenge. Connection proofs bind a nonce, timestamp, destination and token digest; replayed proofs are rejected. There is no shared client password or VPS credential in the APK.

Access replies and endpoint/pin updates are signed with the established project policy signing key, verified against the public project key shipped with the application, bound to the requesting device and challenge, and checked for rollback and expiry. The server signing key, TLS key and token MAC key stay on the VPS.

Production destination addresses must be IP literals within the official Telegram CIDRs (https://core.telegram.org/resources/cidr.txt), on the permitted Telegram transport ports. Private, loopback, link-local, multicast and arbitrary Internet destinations are refused. Limits apply to authentication, frame sizes, queued bytes and simultaneous device/IP connections. Tokens, proofs and traffic bodies are not logged.

The service uses an isolated systemd unit and a dedicated TLS port; existing HTTPS, update, verification and unrelated services remain in place. Administrative access uses a local Unix socket, never an unauthenticated public endpoint.
