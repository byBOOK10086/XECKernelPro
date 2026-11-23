#!/usr/bin/env python3
"""Run host regressions against selected C functions extracted from the kernel.

Kernel services are mocked; these tests check authorization and conversion
semantics, not device latency, LSM integration, or real SELinux policy loading.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def function(path: str, name: str) -> str:
    source = (ROOT / path).read_text(encoding="utf-8")
    # A prototype cannot match: declarations end in ';' before the opening '{'.
    pattern = rf"^[\w \t*]+\b{re.escape(name)}\s*\([^;{{]*\)\s*\{{"
    match = re.search(pattern, source, re.MULTILINE)
    if not match:
        raise ValueError(f"Cannot find definition of {name} in {path}")
    start = source.index("{", match.start())
    # Ignore braces in comments and string/character literals.
    tokens = re.compile(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', re.S)
    depth = 0
    for token in tokens.finditer(source, start):
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
            if depth == 0:
                line = source.count("\n", 0, match.start()) + 1
                return f'#line {line} "{path}"\n' + source[match.start():token.end()] + "\n"
    raise ValueError(f"Unclosed definition of {name}")


CASES = {
    "selinux_audit": [
        ("kernel/selinux/sepolicy.c", "ksu_exists"),
        ("kernel/selinux/sepolicy.c", "ksu_dontaudit"),
        ("kernel/selinux/sepolicy.c", "ksu_set_original_types"),
        ("kernel/selinux/sepolicy.c", "ksu_type_is_original"),
        ("kernel/selinux/rules.c", "apply_app_audit_rules"),
    ],
    "selinux_context": [
        ("kernel/selinux/sepolicy.c", "ksu_set_original_types"),
        ("kernel/selinux/sepolicy.c", "ksu_advance_original_types"),
        ("kernel/selinux/sepolicy.c", "ksu_type_is_original"),
        ("kernel/feature/selinux_hide.c", "string_to_context_struct"),
        ("kernel/feature/selinux_hide.c", "security_context_to_sid_with_policy"),
        ("kernel/feature/selinux_hide.c", "backup_string_to_context_struct"),
        ("kernel/feature/selinux_hide.c", "security_context_to_sid_live"),
    ],
    "legacy_supercalls": [
        ("kernel/supercall/supercall.c", "reboot_handler_pre"),
        ("kernel/hook/prctl_compat.c", "ksu_hook_prctl"),
        ("kernel/supercall/dispatch.c", "ksu_handle_sys_reboot"),
    ],
    "apatch_auth": [
        ("kernel/apatch/auth.c", "sc_key_equal"),
        ("kernel/apatch/auth.c", "sc_auth_set_key"),
        ("kernel/apatch/auth.c", "sc_cred_of"),
    ],
    "susfs_supercalls": [
        ("third_party/susfs4ksu-lkm/kernel/susfs_supercall.c", "reboot_wrapper"),
        ("third_party/susfs4ksu-lkm/kernel/susfs_supercall.c", "reboot_pre"),
    ],
    "manager_rescan": [
        ("kernel/manager/pkg_observer.c", "ksu_maybe_rescan_manager"),
    ],
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sanitize", action="store_true", help="Enable ASan and UBSan (Linux CI)")
    args = parser.parse_args()
    cc = os.environ.get("CC") or shutil.which("gcc") or shutil.which("clang")
    if not cc:
        raise SystemExit("A host C compiler (gcc or clang) is required")
    with tempfile.TemporaryDirectory(prefix="xec-kernel-regressions-") as directory:
        out = Path(directory)
        for case, functions in CASES.items():
            fixture = (ROOT / "tests/kernel" / f"{case}_test.c").read_text(encoding="utf-8")
            extracted = "\n".join(function(path, name) for path, name in functions)
            assert fixture.count("/* KERNEL_FUNCTIONS */") == 1
            source = fixture.replace("/* KERNEL_FUNCTIONS */", extracted + f'\n#line 1 "{case}_fixture"\n')
            cfile = out / f"{case}.c"
            cfile.write_text(source, encoding="utf-8", newline="\n")
            variants = [False, True] if case == "legacy_supercalls" else [False]
            for open_abi in variants:
                name = case + ("_open_abi" if open_abi else "")
                executable = out / (name + (".exe" if os.name == "nt" else ""))
                flags = ["-std=gnu11", "-O2", "-Wall", "-Wextra", "-Werror",
                         "-Wno-unused-parameter", "-Wno-sign-compare"]
                if open_abi:
                    flags.append("-DCONFIG_KSU_OPEN_LEGACY_ABI=1")
                if os.name == "nt" and case == "legacy_supercalls":
                    # Windows is LLP64. These entry tests never dereference the
                    # mocked user pointer; ABI/layout checks belong to KMI builds.
                    flags.append("-Wno-int-to-pointer-cast")
                if args.sanitize:
                    flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
                if case == "manager_rescan":
                    flags.append("-pthread")
                subprocess.run([cc, *flags, str(cfile), "-o", str(executable)], check=True, timeout=60)
                subprocess.run([str(executable)], check=True, timeout=30)
                print(f"PASS: {name}", flush=True)


if __name__ == "__main__":
    main()
