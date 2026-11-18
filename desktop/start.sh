#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
if [ ! -x .venv/bin/python ]; then
    printf '%s\n' 'Set up Python 3.12 and .venv as described in desktop/README.md.' >&2
    exit 2
fi
exec .venv/bin/python -B -X utf8 run.py --browser "$@"
