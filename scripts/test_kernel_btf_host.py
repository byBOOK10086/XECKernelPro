#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Compile the real kernel BTF parser with synthetic host BTF API fixtures.

Linux: python3 scripts/test_kernel_btf_host.py --sanitize
Windows: --cc path/to/clang.exe (requires a host C SDK/runtime).
No Android target, kernel build, downloads, or device access are involved.
"""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"),
                        help="host C compiler command (default: CC or cc)")
    parser.add_argument("--sanitize", action="store_true",
                        help="enable host AddressSanitizer and UndefinedBehaviorSanitizer")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    kernel = root / "kernel"
    tests = kernel / "infra" / "tests"
    # Treat an existing path as one argument, including Windows paths with spaces.
    cc = [args.cc] if Path(args.cc).is_file() else shlex.split(args.cc)
    if not cc or not shutil.which(cc[0]):
        parser.error("host compiler unavailable; set --cc or CC to a compiler with a host C SDK")
    with tempfile.TemporaryDirectory(prefix="ksu-btf-host-") as directory:
        build = Path(directory)
        stubs = build / "include"
        (stubs / "linux").mkdir(parents=True)
        # These headers shadow kernel-only includes. Fixtures implement BTF API
        # calls; the actual algorithm is compiled from kernel/infra/btf_parser.c.
        for name in ("types", "bpf", "btf", "err", "errno", "string"):
            (stubs / "linux" / (name + ".h")).write_text(
                '#include "btf_host_stubs.h"\n', encoding="utf-8")
        (stubs / "klog.h").write_text('#include "btf_host_stubs.h"\n', encoding="utf-8")
        executable = build / ("btf_parser_host.exe" if os.name == "nt" else "btf_parser_host")
        command = cc + ["-std=c11", "-Wall", "-Wextra", "-Werror", "-g",
                        "-DMODULE=1", "-DCONFIG_DEBUG_INFO_BTF=1",
                        "-I" + str(stubs), "-I" + str(tests), "-I" + str(kernel),
                        str(kernel / "infra" / "btf_parser.c"),
                        str(tests / "btf_parser_host.c"), "-o", str(executable)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        print("Compiling real kernel/infra/btf_parser.c", flush=True)
        subprocess.run(command, check=True)
        subprocess.run([str(executable)], check=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print("BTF host test failed: " + str(error), file=sys.stderr)
        sys.exit(1)
