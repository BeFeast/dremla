#!/usr/bin/env bash
# Shipping endpoints and resolved dependency allowlist. No arguments; run after the Gradle report.
set -euo pipefail
[[ $# -eq 0 ]] || { echo 'usage: scripts/check-privacy.sh (no arguments)' >&2; exit 2; }
cd "$(dirname "${BASH_SOURCE[0]}")/.."
python3 - <<'PY'
from pathlib import Path
import re
from urllib.parse import urlsplit
errors = []
report = Path('app/build/reports/privacy/runtime.txt')
allow = Path('docs/runtime-dependencies.txt')
if not report.is_file() or not allow.is_file():
    errors.append('missing resolved runtime graph or reviewed allowlist')
else:
    actual = set(report.read_text().splitlines())
    approved = set(allow.read_text().splitlines())
    if not actual or actual != approved:
        errors.append('runtime graph differs from reviewed allowlist: ' + ', '.join(sorted(actual ^ approved)))
    for coordinate in actual:
        if re.search(r'analytics|telemetry|crashlytics|firebase|sentry|bugsnag|appcenter|amplitude|mixpanel', coordinate, re.I):
            errors.append('forbidden dependency: ' + coordinate)
for path in Path('app/src').rglob('*'):
    source_set = path.relative_to('app/src').parts[0]
    if source_set.startswith(('test', 'androidTest')):
        continue
    if path.suffix not in {'.kt', '.java', '.xml'}:
        continue
    for number, line in enumerate(path.read_text().splitlines(), 1):
        for raw in re.findall(r'(?:https?|wss?|ftp)://[^\s"<>\x27)]+', line):
            if raw in {'http://schemas.android.com/apk/res/android', 'http://schemas.android.com/tools', 'http://schemas.android.com/apk/res-auto'} and path.suffix == '.xml':
                continue
            # Only the canonical reference builder in the resolver may contain this literal.
            if (path.as_posix() == 'app/src/main/java/com/befeast/sleepmusic/online/NewPipeResolver.kt'
                    and raw == 'https://www.youtube.com/watch?v=$videoId'):
                continue
            url = urlsplit(raw)
            if url.scheme != 'https' or url.netloc != 'html.me.uk':
                errors.append(f'{path}:{number}: non-collection endpoint')
for error in errors:
    print('FAIL  ' + error)
print('check-privacy: ' + ('FAIL' if errors else 'PASS'))
raise SystemExit(bool(errors))
PY
