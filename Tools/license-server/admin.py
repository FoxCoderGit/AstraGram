"""Root/SSH-only license administration; never exposed through the HTTPS API."""
import argparse
import json
import secrets
import time
import os
from server import db, initialize, fingerprint

def main():
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["create", "list", "revoke", "restore", "reset-binding"])
    p.add_argument("id", nargs="?")
    p.add_argument("--label", default="")
    p.add_argument("--days", type=int)
    args = p.parse_args()
    # Root's CLI must use the same identity as the service, including SQLite WAL
    # files created while an activation request is being processed concurrently.
    if hasattr(os, 'geteuid') and os.geteuid() == 0:
        import pwd
        account = pwd.getpwnam('ghostgram-license')
        os.initgroups(account.pw_name, account.pw_gid)
        os.setgid(account.pw_gid)
        os.setuid(account.pw_uid)
    initialize()
    with db() as connection:
        if args.command == "create":
            if args.days is not None and args.days <= 0: p.error("--days must be positive")
            key = "GG-" + "-".join(secrets.token_hex(4).upper() for _ in range(5))
            lid = secrets.token_hex(16); now = int(time.time())
            connection.execute("INSERT INTO licenses(id,key_hash,label,expires_at,created_at) VALUES(?,?,?,?,?)", (lid, fingerprint(key.encode()), args.label, now + args.days * 86400 if args.days else None, now))
            print(json.dumps(dict(id=lid, key=key)))
        elif args.command == "list":
            rows = connection.execute("SELECT id,label,status,expires_at,substr(device,1,12) AS device,created_at,last_seen FROM licenses ORDER BY created_at DESC").fetchall()
            print(json.dumps([dict(row) for row in rows], ensure_ascii=False, indent=2))
        else:
            if not args.id: p.error("License id required")
            if args.command == "reset-binding":
                cur = connection.execute("UPDATE licenses SET device=NULL,bound_at=NULL WHERE id=?", (args.id,))
            else:
                cur = connection.execute("UPDATE licenses SET status=? WHERE id=?", ("revoked" if args.command == "revoke" else "active", args.id))
            if cur.rowcount != 1: p.error("License id not found")
            print("OK")

if __name__ == "__main__": main()
