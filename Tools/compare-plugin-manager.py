"""Run the supplied 31 lifecycle/strategy assertions against oracle and source."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--reference', required=True)
parser.add_argument('--python38', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
reference = Path(args.reference)
source = (reference / 'reference/probe_original.py').read_text(encoding='utf-8')
source = source.split('# Reference outputs from the unmodified parser')[0]
source = source.replace('import sys\n', 'import sys\nsys.dont_write_bytecode = True\n')
source = source.replace('ROOT = Path(__file__).resolve().parent', 'ROOT = Path(' + repr(str(reference / 'reference')) + ')')
source = source.replace('def getBoolean(self, key, default):', 'def edit(self): return self\n    def getBoolean(self, key, default):')
reports = root.parent / 'Reports'
outputs = []
with tempfile.TemporaryDirectory() as directory:
    for name, executable in [('reference', args.python38), ('source', sys.executable)]:
        program = source
        output = reports / ('ghost102-' + name + '-probe.json')
        program = program.replace("(ROOT / 'probe-results.json')", 'Path(' + repr(str(output)) + ')')
        if name == 'source':
            program = program.replace('Original Python 3.8 bytecode, fake Java host; no Android/ART validation', 'Reconstructed source, fake Java host; no Android/ART validation')
            program = program.replace('checks against original bytecode', 'checks against reconstructed source')
            program = program.replace("sys.path.insert(0, str(ROOT / 'python'))", 'sys.path.insert(0, ' + repr(str(root / 'TMessagesProj_Ghost/src/main/python')) + ')')
            program = program.replace("sys.path.insert(0, str(ROOT / 'assets/chaquopy/requirements-common.imy'))", '')
        script = Path(directory) / (name + '.py')
        script.write_text(program, encoding='utf-8')
        result = subprocess.run([executable, str(script)], text=True, capture_output=True)
        if result.returncode:
            print(result.stdout, result.stderr)
            raise SystemExit(result.returncode)
        outputs.append(json.loads(output.read_text(encoding='utf-8')))
        print(name + ':', result.stdout.strip())
assert outputs[0]['checks'] == outputs[1]['checks'], 'Reference/source assertions differ'
print('PASS: same 31 assertions against original bytecode and reconstructed source')
