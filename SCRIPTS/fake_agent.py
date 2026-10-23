#!/usr/bin/env python3
"""Safe dummy CLI used ONLY to test the loop driver. NEVER modifies project state."""
import sys
from pathlib import Path
print('FAKE AGENT: read-only invocation; prompt:',sys.argv[1] if len(sys.argv)>1 else 'none')
print('FAKE AGENT: I did not code, test, audit or verify any task.')
