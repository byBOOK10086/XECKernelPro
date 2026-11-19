#!/usr/bin/env python3
"""Stage pinned module releases or verify the same bytes in a manager APK."""

import argparse
import hashlib
import io
from pathlib import Path
from time import sleep
from urllib.error import URLError
from urllib.request import Request, urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
PAYLOAD_DIR = ROOT / "userspace" / "ksud" / "bundled-modules"
PAYLOADS = (
    (
        "rezygisk",
        "PerformanC/ReZygisk/releases/download/v1.0.0/ReZygisk-v1.0.0-release.zip",
        506334,
        "7904649b8dcaf2b060c3432df4fee302aeb1258da199d2b425335e82d49510e6",
        ("customize.sh", "bin/arm64-v8a/zygiskd", "bin/x86_64/zygiskd"),
    ),
    (
        "hma_oss_zygisk",
        "frknkrc44/HMA-OSS/releases/download/oss-177/HMA-OSS-ZYGISK-oss-177-release.zip",
        2597526,
        "fad6d3224398f09ad2fbbd52ee5328b9b38fc6ad65d751cb6a8e8acfec1fd770",
        ("customize.sh", "manager.apk", "customize.d/22-check-zygisk.sh"),
    ),
)


def fetch(path, size):
    url = f"https://github.com/{path}"
    for attempt in range(3):
        try:
            request = Request(url, headers={"User-Agent": "XECKernel-KMI-build"})
            with urlopen(request, timeout=30) as response:
                return response.read(size + 1)
        except (URLError, TimeoutError):
            if attempt == 2:
                raise
            sleep(2 ** attempt)


def verify_zip(module, data, size, digest, expected_entries):
    if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"{module}: pinned ZIP size or SHA-256 did not match")
    with ZipFile(io.BytesIO(data)) as archive:
        ids = [
            line.partition("=")[2].strip()
            for line in archive.read("module.prop").decode("utf-8").splitlines()
            if line.startswith("id=")
        ]
        if ids != [module]:
            raise ValueError(f"{module}: unexpected module.prop ID: {ids}")
        for entry in expected_entries:
            if not archive.getinfo(entry).file_size:
                raise ValueError(f"{module}: empty {entry}")
        bad_entry = archive.testzip()
        if bad_entry:
            raise ValueError(f"{module}: corrupt ZIP entry: {bad_entry}")


def stage(local_paths):
    staged = {}
    for payload, local in zip(PAYLOADS, local_paths):
        module, path, size, digest, entries = payload
        data = local.read_bytes() if local else fetch(path, size)
        verify_zip(module, data, size, digest, entries)
        staged[PAYLOAD_DIR / f"{module}.zip"] = data

    for path, data in staged.items():
        path.write_bytes(data)
        print(f"Staged {path.name}: {len(data)} bytes, SHA-256 verified")


def verify_apk(apk, abis):
    with ZipFile(apk) as archive:
        for abi in abis:
            binary = archive.read(f"lib/{abi}/libxeccli.so")
            for module, _, size, digest, _ in PAYLOADS:
                # The Rust build stores the upstream archives without recompressing them.
                start = binary.find(b"PK\x03\x04")
                while start != -1 and start + size <= len(binary):
                    data = memoryview(binary)[start:start + size]
                    if hashlib.sha256(data).hexdigest() == digest:
                        break
                    start = binary.find(b"PK\x03\x04", start + 4)
                else:
                    raise ValueError(f"{abi}: exact {module} ZIP is missing from {apk.name}")
                print(f"{abi}: verified {module} in {apk.name}")
        for name in ("NOTICE.md", "HMA-OSS-AGPL-3.0.txt", "ReZygisk-LICENSE.txt"):
            if not archive.getinfo(f"assets/kmi-modules/{name}").file_size:
                raise ValueError(f"{apk.name}: empty {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rezygisk", type=Path, help="use a local copy of the pinned ReZygisk ZIP")
    parser.add_argument("--hma-oss", type=Path, help="use a local copy of the pinned HMA-OSS ZIP")
    parser.add_argument("--verify-apk", type=Path, nargs="+", help="check repacked manager APKs")
    parser.add_argument(
        "--abi", action="append", choices=("arm64-v8a", "x86_64"),
        help="ABI required in the APK; repeat for multiple ABIs (default: both)",
    )
    args = parser.parse_args()
    if args.verify_apk:
        for apk in args.verify_apk:
            verify_apk(apk, args.abi or ("arm64-v8a", "x86_64"))
    else:
        stage((args.rezygisk, args.hma_oss))


if __name__ == "__main__":
    main()
