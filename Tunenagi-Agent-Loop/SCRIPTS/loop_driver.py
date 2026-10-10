#!/usr/bin/env python3
"""Bounded, resumable CLI agent invoker. Requires a real external CLI agent; does not contain an LLM."""
import argparse
import datetime as dt
import hashlib
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TASKS=ROOT/'STATE/tasks.json'
AUDITS=ROOT/'STATE/audits'
RUNS=ROOT/'STATE/driver_runs'

def snapshot():
    data=json.loads(TASKS.read_text(encoding='utf8'))
    verified=tuple(sorted(t['id'] for t in data['tasks'] if t['status']=='verified'))
    audited=tuple(sorted(p.name for p in AUDITS.glob('phase-??-pass.json')))
    all_done=all(t['status']=='verified' for t in data['tasks'])
    all_audited=len(audited)==len({t['phase'] for t in data['tasks']})
    return (verified,audited,all_done and all_audited)

def candidate():
    data=json.loads(TASKS.read_text(encoding='utf8'))
    phases=sorted({t['phase'] for t in data['tasks']})
    verified={t['id'] for t in data['tasks'] if t['status']=='verified'}
    for t in data['tasks']:
        if t['status'] in ('todo','in_progress') and all(d in verified for d in t['deps']):
            if all((AUDITS/f'phase-{p}-pass.json').exists() for p in phases if p<t['phase']):return t['id']
    for p in phases:
        these=[t for t in data['tasks'] if t['phase']==p]
        if all(t['status']=='verified' for t in these) and not (AUDITS/f'phase-{p}-pass.json').exists():return f'AUDIT-{p}'
    return None

def stamp():return dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')

def argv_for(template,prompt):
    if '{prompt_file}' not in template:raise ValueError('--agent-command must contain {prompt_file} placeholder')
    # Parse template, THEN substitute placeholders: paths containing spaces remain single args.
    parts=shlex.split(template,posix=(os.name!='nt'))
    return [p.replace('{prompt_file}',str(prompt)).replace('{repo}',str(ROOT)) for p in parts]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--agent-command',default=None,help='e.g. my-agent --prompt-file {prompt_file} ; must be a real installed CLI')
    p.add_argument('--dry-run',action='store_true')
    p.add_argument('--max-invocations',type=int,default=10000)
    p.add_argument('--timeout-seconds',type=int,default=3600)
    p.add_argument('--max-wall-seconds',type=int,default=604800,help='Maximum total runner wall-clock seconds')
    p.add_argument('--max-no-progress',type=int,default=4)
    p.add_argument('--delay-seconds',type=int,default=10)
    a=p.parse_args()
    if min(a.max_invocations,a.timeout_seconds,a.max_wall_seconds,a.max_no_progress)<1:p.error('Bounds must be positive')
    if a.dry_run:
        prompt=ROOT/'PROMPTS/00_START_SOLAR_PRO_4.md'
        print('DRY RUN. Prompt:',prompt)
        print('Next ready item:',candidate())
        if a.agent_command:print('Would invoke:',argv_for(a.agent_command,prompt))
        print('No coding or task state changed.')
        return
    if not a.agent_command:p.error('--agent-command required without --dry-run')
    RUNS.mkdir(parents=True,exist_ok=True)
    lock=RUNS/'driver.lock'
    try:
        fd=os.open(str(lock),os.O_CREAT|os.O_EXCL|os.O_WRONLY)
        with os.fdopen(fd,'w') as f:f.write(f'pid={os.getpid()} created={stamp()}\n')
    except FileExistsError:
        print('Runner lock exists. Do NOT run concurrent mutating agents. Check/clear stale lock manually.',file=sys.stderr);sys.exit(2)
    started=time.monotonic();no_progress=0
    try:
        for idx in range(1,a.max_invocations+1):
            before=snapshot()
            if before[2]:print('All P0 tasks and phase metadata audited. Run final independent release review.');break
            todo=candidate()
            if todo is None:print('No dependency-ready task or audit. See STATE/blockers.md. Exiting safely.');break
            if time.monotonic()-started>=a.max_wall_seconds:print('Wall-time budget reached. Exiting for resume.');break
            stfile=ROOT/'STATE/status.json'
            st=json.loads(stfile.read_text(encoding='utf8'))
            starter=not st.get('initialized',False) and idx==1
            prompt=ROOT/'PROMPTS'/('00_START_SOLAR_PRO_4.md' if starter else ('02_PHASE_AUDIT.md' if todo.startswith('AUDIT-') else '01_SESSION_LOOP.md'))
            command=argv_for(a.agent_command,prompt)
            stem=f'{stamp()}-{idx:04d}';log=RUNS/(stem+'.log');meta=RUNS/(stem+'.json')
            print(f'Invocation {idx}: candidate={todo}; CLI={command[0]}; prompt={prompt.name}',flush=True)
            start_utc=dt.datetime.now(dt.timezone.utc).isoformat()
            with log.open('wb') as output_file:
                try:
                    remaining=max(1,int(a.max_wall_seconds-(time.monotonic()-started)))
                    result=subprocess.run(command,cwd=ROOT,stdout=output_file,stderr=subprocess.STDOUT,
                                          timeout=min(a.timeout_seconds,remaining),check=False)
                    rc=result.returncode
                except subprocess.TimeoutExpired:
                    rc=124;output_file.write(b'\nAGENT_INVOCATION_TIMEOUT\n')
                except OSError as e:
                    rc=127;output_file.write(('Agent executable unavailable: '+str(e)+'\n').encode())
            digest=hashlib.sha256()
            with log.open('rb') as output_file:
                for chunk in iter(lambda:output_file.read(1024*1024),b''):digest.update(chunk)
            meta.write_text(json.dumps({'invocation':idx,'candidate':todo,'prompt':str(prompt.relative_to(ROOT)),
                                        'startedUtc':start_utc,'finishedUtc':dt.datetime.now(dt.timezone.utc).isoformat(),
                                        'exitCode':rc,'command':command,'logPath':str(log.relative_to(ROOT)),
                                        'logSha256':digest.hexdigest()},indent=2)+'\n',encoding='utf8')
            print(f'Agent exit={rc}; log={log.relative_to(ROOT)}',flush=True)
            after=snapshot()
            progressed=(after[0]!=before[0] and len(after[0])>len(before[0])) or (after[1]!=before[1] and len(after[1])>len(before[1]))
            if progressed and rc==0:no_progress=0
            else:no_progress+=1
            if no_progress>=a.max_no_progress:
                print(f'CIRCUIT BREAKER: {no_progress} invocations without verified progress; inspect logs/blockers.');break
            if rc in (124,127):
                print('Infrastructure error/timeout. Exiting to avoid repeated destructive loops.');break
            if idx<a.max_invocations and a.delay_seconds:time.sleep(a.delay_seconds)
        else:print('Invocation budget reached; re-run after reviewing progress.')
    finally:
        try:lock.unlink()
        except FileNotFoundError:pass
if __name__=='__main__':main()
