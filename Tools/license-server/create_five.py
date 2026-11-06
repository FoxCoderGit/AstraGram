"""Issue exactly five permanent keys; keep the handoff file outside APK sources."""
import json
from deploy import connect, run, IDENTITY


def main():
    target = IDENTITY / 'five-permanent-keys.json'
    if target.exists():
        records = json.loads(target.read_text(encoding='utf-8'))
    else:
        records = []
    with connect() as ssh:
        while len(records) < 5:
            record = json.loads(run(ssh, 'python3 /opt/ghostgram-license/admin.py create --label permanent-handoff-' + str(len(records) + 1)))
            records.append(record)
            target.write_text(json.dumps(records, indent=2), encoding='utf-8')
        licenses = json.loads(run(ssh, 'python3 /opt/ghostgram-license/admin.py list'))
        for record in records:
            matched = next(row for row in licenses if row['id'] == record['id'])
            assert matched['expires_at'] is None and matched['status'] == 'active'
            assert matched['device'] is None, 'A handoff key is already bound'
    text = IDENTITY / 'five-permanent-keys.txt'
    text.write_text('\n'.join(row['key'] for row in records) + '\n', encoding='utf-8')
    print('PASS: five active permanent unbound keys; saved outside source to ' + str(text))


if __name__ == '__main__': main()
