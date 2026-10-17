#!/usr/bin/env python3
"""Exercise the harness on an isolated copy, without marking baseline tasks complete."""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def run(copy,*args,expected=0):
    proc=subprocess.run([sys.executable,*args],cwd=copy,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    if proc.returncode!=expected:
        raise RuntimeError(f'Expected exit {expected}, got {proc.returncode} for {args}:\n{proc.stdout[-3000:]}')
    return proc.stdout

def main():
    with tempfile.TemporaryDirectory(prefix='tunenagi-harness-') as tmp:
        copy=Path(tmp)/'kit';shutil.copytree(ROOT,copy,ignore=shutil.ignore_patterns('.git','driver_runs'))
        assert 'TUN-0001' in run(copy,'SCRIPTS/taskctl.py','next')
        assert 'ERROR:' in run(copy,'SCRIPTS/taskctl.py','claim','TUN-0101',expected=2)
        run(copy,'SCRIPTS/validate_package.py')
        run(copy,'SCRIPTS/taskctl.py','claim','TUN-0001')
        run(copy,'SCRIPTS/run_check.py','--task','TUN-0001','--name','baseline-test','--',sys.executable,'SCRIPTS/validate_package.py')
        manifests=list((copy/'STATE/evidence/TUN-0001').glob('*.json'))
        assert len(manifests)==1
        run(copy,'SCRIPTS/taskctl.py','verify','TUN-0001','--evidence',str(manifests[0]))
        assert 'TUN-0002' in run(copy,'SCRIPTS/taskctl.py','next')
        assert 'PHASE NOT READY' in run(copy,'SCRIPTS/taskctl.py','gate','00',expected=2)
        assert 'ERROR:' in run(copy,'SCRIPTS/taskctl.py','audit','00','--report','STATE/audits/no-report.md',expected=2)
        run(copy,'SCRIPTS/loop_driver.py','--dry-run')
        assert json.loads((ROOT/'STATE/tasks.json').read_text())['tasks'][0]['status']=='todo'
    print('PASS: harness graph, prerequisite rejection, real check evidence, verify, audit gate, and clean baseline tested.')
if __name__=='__main__':main()
