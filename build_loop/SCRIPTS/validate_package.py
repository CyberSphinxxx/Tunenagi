#!/usr/bin/env python3
"""Validate the development harness structure and task graph without Android dependencies."""
import json
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
required=['README.md','START_HERE.md','AGENTS.md','PROMPTS/00_START_SOLAR_PRO_4.md','PROMPTS/01_SESSION_LOOP.md','PROMPTS/02_PHASE_AUDIT.md','SPEC/PRD.md','SPEC/ARCHITECTURE.md','SPEC/PLUGINS.md','SPEC/QUALITY_GATES.md','OPERATIONS/AUTOMATION.md','STATE/tasks.json','STATE/defects.json','SCRIPTS/loop_driver.py','SCRIPTS/taskctl.py','SCRIPTS/run_check.py']
errors=[]
for x in required:
    f=ROOT/x
    if not f.is_file() or f.stat().st_size==0:errors.append('Missing/empty '+x)
try:
    data=json.loads((ROOT/'STATE/tasks.json').read_text('utf8'))
    tasks=data['tasks']; ids={t['id'] for t in tasks}
    if len(ids)!=len(tasks):errors.append('Duplicate task IDs')
    for t in tasks:
        if t['status'] not in {'todo','in_progress','blocked','verified'}:errors.append('Unknown status '+t['id'])
        if t.get('priority')!='P0':errors.append('Non-P0 task in fixed v1 task list '+t['id'])
        for d in t.get('deps',[]):
            if d not in ids:errors.append(f'{t["id"]} has missing dependency {d}')
        if t['status']=='verified' and not t.get('evidence'):errors.append(f'{t["id"]} verified without evidence')
    visits={}; byid={t['id']:t for t in tasks}
    def visit(tid):
        if visits.get(tid)==1:raise ValueError('Dependency cycle at '+tid)
        if visits.get(tid)==2:return
        visits[tid]=1
        for d in byid[tid]['deps']:visit(d)
        visits[tid]=2
    for tid in ids:visit(tid)
    ph={t['phase'] for t in tasks}
    for p in ph:
        if len(list((ROOT/'PHASES').glob(p+'-*.md')))!=1:errors.append('Need exactly one phase doc for '+p)
    if data.get('frozenP0TaskCount')!=len(tasks):errors.append('frozenP0TaskCount mismatch')
    defects=json.loads((ROOT/'STATE/defects.json').read_text('utf8'))
    for d in defects['items']:
        if d['relatedTask'] not in ids:errors.append('Defect points at missing task '+d['id'])
        if d['severity'] not in ('Critical','High','Medium','Low'):errors.append('Unknown severity '+d['id'])
except Exception as exc:errors.append('Invalid task graph: '+str(exc))
if errors:
    print('FAIL: '+str(len(errors))+' issue(s)')
    for e in errors:print('  -',e)
    sys.exit(2)
print(f'OK: all core files present; {len(tasks)} P0 tasks, {len({t["phase"] for t in tasks})} phases; dependency graph valid.')
