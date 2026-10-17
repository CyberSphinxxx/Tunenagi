#!/usr/bin/env python3
"""Capture actual tool execution evidence to disk without retaining large log in RAM."""
import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def filehash(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--task',required=True,help='Existing TUN-XXXX')
    ap.add_argument('--name',required=True,help='Short check label')
    ap.add_argument('--timeout',type=int,default=1800)
    ap.add_argument('cmd',nargs=argparse.REMAINDER)
    a=ap.parse_args()
    if not re.fullmatch(r'TUN-\d{4}',a.task):ap.error('Bad task id')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,60}',a.name):ap.error('Short alphanumeric name required')
    cmd=a.cmd[1:] if a.cmd and a.cmd[0]=='--' else a.cmd
    if not cmd:ap.error('Missing real command after --')
    tdir=ROOT/'STATE/evidence'/a.task;tdir.mkdir(parents=True,exist_ok=True)
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    base=f'{stamp}-{a.name}'
    # Avoid overwriting previous attempts within same second.
    suffix=0
    while (tdir/f'{base}.json').exists():
        suffix+=1;base=f'{stamp}-{a.name}-{suffix}'
    log=tdir/f'{base}.log';meta=tdir/f'{base}.json'
    start=dt.datetime.now(dt.timezone.utc).isoformat();began=time.monotonic()
    with log.open('wb') as f:
        try:
            res=subprocess.run(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,timeout=a.timeout,check=False)
            code=res.returncode
        except subprocess.TimeoutExpired:
            code=124;f.write(b'\nRUN_CHECK_TIMEOUT\n')
        except OSError as exc:
            code=127;f.write(('COMMAND_ERROR: '+str(exc)+'\n').encode())
    metadata=dict(runner='tunenagi-run-check-v1',task=a.task,name=a.name,argv=cmd,cwd=str(ROOT),startedUtc=start,finishedUtc=dt.datetime.now(dt.timezone.utc).isoformat(),elapsedSeconds=round(time.monotonic()-began,3),exitCode=code,logPath=str(log.relative_to(ROOT)),logSha256=filehash(log))
    meta.write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf8')
    with log.open('rb') as f:
        f.seek(max(0,log.stat().st_size-3000));tail=f.read().decode('utf8',errors='replace')
    print(tail)
    print(f'CHECK EXIT={code} EVIDENCE={meta.relative_to(ROOT)}')
    sys.exit(code if 0<=code<=125 else 1)
if __name__=='__main__':main()
