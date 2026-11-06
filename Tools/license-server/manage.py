"""Local owner CLI: SSH identity outside repo; newly issued keys go to private files."""
import argparse
import json
import shlex
from deploy import connect, run, IDENTITY


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['create', 'list', 'revoke', 'restore', 'reset-binding'])
    parser.add_argument('id', nargs='?')
    parser.add_argument('--label', default='')
    parser.add_argument('--days', type=int)
    args = parser.parse_args()
    command = ['python3', '/opt/ghostgram-license/admin.py', args.command]
    if args.command == 'create':
        command += ['--label', args.label]
        if args.days is not None:
            if args.days <= 0: parser.error('--days must be positive')
            command += ['--days', str(args.days)]
    elif args.command != 'list':
        if not args.id or len(args.id) != 32 or any(c not in 'abcdef0123456789' for c in args.id): parser.error('32-character license id required')
        command += [args.id]
    with connect() as ssh: output = run(ssh, shlex.join(command))
    if args.command == 'create':
        record = json.loads(output)
        folder = IDENTITY / 'issued-keys'; folder.mkdir(exist_ok=True)
        target = folder / (record['id'] + '.json')
        target.write_text(output, encoding='utf-8')
        print('Created license ' + record['id'] + '; key saved to ' + str(target))
    else: print(output.strip())


if __name__ == '__main__': main()
