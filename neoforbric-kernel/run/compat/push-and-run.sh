#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec ${PYTHON:-python3} "$HERE/push-and-run.py" "$@"
