#!/usr/bin/env python3
"""Compile and run the actual Rust SUSFS host harness."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts" / "test_susfs_probe_host.rs"

with tempfile.TemporaryDirectory() as tmp:
    binary = Path(tmp) / "test_susfs_probe_host"
    subprocess.run(
        ["rustc", "--edition=2021", str(SOURCE), "-o", str(binary)],
        cwd=ROOT,
        check=True,
    )
    subprocess.run([str(binary)], cwd=ROOT, check=True)

print("SUSFS Rust host harness passed")
