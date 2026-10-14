"""Conservative offline release-tree check; prints paths, never secret values."""
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
try:
    top = subprocess.check_output(['git', '-C', str(root), 'rev-parse', '--show-toplevel'], stderr=subprocess.DEVNULL).decode().strip()
    if Path(top).resolve() != root: raise subprocess.CalledProcessError(1, 'git')
    tracked = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z'], stderr=subprocess.DEVNULL)
    paths = [root / p.decode() for p in tracked.split(b'\0') if p]
except subprocess.CalledProcessError:
    paths = [p for p in root.rglob('*') if p.is_file() and '.git' not in p.parts]
issues = []
for p in paths:
    rel = p.relative_to(root)
    if any(part in {'private-auth', 'recordings', 'stream-inbox', 'build', '.gradle', '__pycache__'} for part in rel.parts):
        issues.append((rel, 'private/generated directory'))
    if p.name.startswith('private-') or p.suffix in {'.pem', '.wav', '.pcm', '.bin', '.apk', '.zip', '.log', '.partial', '.sqlite3', '.jks', '.p12'}:
        issues.append((rel, 'private/generated file type'))
    if p.name == 'gradle-wrapper.jar':
        continue
    data = p.read_bytes()
    if re.search(rb'-----BEGIN (?:RSA )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=]{30}', data):
        issues.append((rel, 'embedded private key'))
    if re.search(rb'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}', data):
        issues.append((rel, 'JWT-like value'))
    if re.search(rb'(?:ghp_|github_pat_)[A-Za-z0-9_]{30,}', data):
        issues.append((rel, 'GitHub-token-like value'))
    if re.search(rb'/home/' + rb'[A-Za-z0-9_-]+/', data):
        issues.append((rel, 'private host path'))
for path, reason in issues:
    print(f'{path}: {reason}')
print(f'Checked {len(paths)} files; {len(issues)} findings. Manual review still required.')
raise SystemExit(bool(issues))
