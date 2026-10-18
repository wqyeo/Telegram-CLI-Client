#!/usr/bin/env python3
"""Opt-in PUBLIC explicit NEW encrypted-binlog component and cumulative regressions."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('g1_binlog_runner', Path(__file__).with_name('test-g1-binlog.py'))
if not spec or not spec.loader:
    raise SystemExit('PUBLIC binlog runner unavailable; no fallback.')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

if __name__ == '__main__':
    raise SystemExit(runner.main('creation'))
