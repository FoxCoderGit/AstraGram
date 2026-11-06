"""Compare source parser to the supplied Python 3.8 bytecode oracle."""
import argparse
import dataclasses
import json
from pathlib import Path
import random
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--reference', required=True)
parser.add_argument('--python38', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(root / 'TMessagesProj_Ghost/src/main/python'))
from markdown_utils import parse_markdown

randomizer = random.Random(102)
samples = ['*abc _def_*', '[a\\]b](123)', '[a](hello\\)world)', '```\nx\n```',
           '``x``', '*a \n*', 'x\\', '__x _y__ z_', 'a [x](url', '[a]b', '\U0001f600 *x*']
alphabet = ['a', 'b', ' ', '\n', '*', '_', '~', '|', '`', '[', ']', '(', ')', '!', '\\', '@', '\U0001f600']
samples += [''.join(randomizer.choice(alphabet) for _ in range(randomizer.randrange(1, 40))) for _ in range(1000)]
oracle = r'''
import sys,json,types,dataclasses
sys.path.insert(0,sys.argv[1])
class Types:
    def __getattr__(self,name): return type(name,(),{})
for name in ('org','org.telegram','org.telegram.tgnet'): sys.modules[name]=types.ModuleType(name)
sys.modules['org.telegram.tgnet'].TLRPC=Types()
from markdown_utils import parse_markdown
def run(text):
    try:
        result=parse_markdown(text)
        return {'text':result.text,'entities':[dict(dataclasses.asdict(e),type=e.type.value) for e in result.entities]}
    except Exception as error: return {'error_type':type(error).__name__,'error':str(error)}
print(json.dumps([run(text) for text in json.load(sys.stdin)]))
'''
with tempfile.TemporaryDirectory() as directory:
    script = Path(directory) / 'oracle.py'
    script.write_text(oracle, encoding='utf-8')
    output = subprocess.run([args.python38, str(script), str(Path(args.reference) / 'reference/python')],
                            input=json.dumps(samples), capture_output=True, text=True, check=True)
expected = json.loads(output.stdout)
failures = []
for text, wanted in zip(samples, expected):
    try:
        result = parse_markdown(text)
        actual = {'text': result.text, 'entities': [dict(dataclasses.asdict(e), type=e.type.value) for e in result.entities]}
    except Exception as error: actual = {'error_type': type(error).__name__, 'error': str(error)}
    if actual != wanted: failures.append(dict(input=text, actual=actual, expected=wanted))
report = root.parent / 'Reports/ghost102-markdown-oracle.json'
report.write_text(json.dumps(dict(cases=len(samples), mismatches=failures), ensure_ascii=False, indent=2), encoding='utf-8')
print('Markdown oracle:', len(samples)-len(failures), '/', len(samples), 'matched; report:', report)
if failures: sys.exit(1)
