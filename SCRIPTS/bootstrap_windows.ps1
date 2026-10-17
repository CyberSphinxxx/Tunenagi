# Safe preflight only. Does not install SDK, edit machine settings or launch AI agent.
$ErrorActionPreference = 'Continue'
Write-Host 'Tunenagi development kit: preflight'
foreach ($command in @('python', 'git', 'java', 'adb')) {
  $found = Get-Command $command -ErrorAction SilentlyContinue
  if ($found) { Write-Host "FOUND $command -> $($found.Source)" } else { Write-Warning "MISSING: $command" }
}
python SCRIPTS/validate_package.py
python SCRIPTS/taskctl.py status
Write-Host 'Read START_HERE.md, OPERATIONS/ENVIRONMENT.md and PROMPTS/00_START_SOLAR_PRO_4.md.'
