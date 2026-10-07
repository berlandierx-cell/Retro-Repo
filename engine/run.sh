#!/usr/bin/env bash
# Lance le moteur PC (Linux). Prérequis : python3, wine.
cd "$(dirname "$0")"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
exec .venv/bin/python retro_engine.py "$@"
