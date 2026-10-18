#!/usr/bin/env python3
"""Opt-in cumulative B1b+B1c PUBLIC native maintenance test; no SDK activation."""
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('public_binlog_runner', Path(__file__).with_name('test-g1-binlog.py'))
if SPEC is None or SPEC.loader is None:
    raise SystemExit('PUBLIC native runner unavailable; no fallback.')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

if __name__ == '__main__':
    raise SystemExit(runner.main('maintenance'))
