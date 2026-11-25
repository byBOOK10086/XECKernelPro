#!/usr/bin/env python3
"""Release gate for the bundled software-KeyMint keybox.

Background (2026-11-25): 31452/31453 shipped the Yurikey generation whose leaf
ran 2026-11-11..2026-11-25. After that leaf expired the engine carrier
(`userspace/ksud/builtin/tricky_store/engineconf.sh`) downgraded its detector
routing to "off": detector packages were stripped from target.txt and received
the real TEE attestation, while the boot-state spoof still told every reader
that the device was locked and verified. Maple 2.8.2 reported four hard
contradictions from that state (attestation vs. boot command line, TEE state,
TEE boot-state side channel, boot-lock grading).

The bundled keybox is the one source both the engine and the manager pool ship,
so a release must always carry a keybox whose leaf still has several days of
head-room. This gate makes that a build failure instead of something someone
has to remember:

  * userspace/ksud/builtin/tricky_store/keybox.xml -- engine carrier keybox;
    *every* keybox in the document must be fresh.
  * manager/app/src/main/assets/xec_keybox.xml -- manager pool asset; it also
    carries the long-lived legacy box, so *at least one* box must be fresh and
    every certificate in every box must still be currently valid.

Refresh procedure lives in docs/tee-reliability-plan.md; the daily mirror
(`_scratch/keybox_mirror.py`) is the source of the current generation.
"""

import argparse
import datetime
import os
import pathlib
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILTIN = ROOT / "userspace/ksud/builtin/tricky_store/keybox.xml"
POOL = ROOT / "manager/app/src/main/assets/xec_keybox.xml"

_OPENSSL = None


def openssl() -> str:
    global _OPENSSL
    if _OPENSSL:
        return _OPENSSL
    candidates = [
        os.environ.get("OPENSSL"),
        shutil.which("openssl"),
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        "/usr/bin/openssl",
        "/usr/local/bin/openssl",
    ]
    for candidate in candidates:
        if candidate and pathlib.Path(candidate).is_file():
            _OPENSSL = str(candidate)
            return _OPENSSL
    raise SystemExit("openssl not found on PATH; install it or set OPENSSL")


def run_openssl(args, data=None):
    proc = subprocess.run([openssl(), *args], input=data, capture_output=True)
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).decode("utf-8", "replace").strip()
        raise RuntimeError("openssl %s failed: %s" % (" ".join(args), detail[:300]))
    return proc.stdout


def clean_pem(node_text):
    return "\n".join(line.strip() for line in (node_text or "").splitlines()) + "\n"


def cert_dates(pem):
    out = run_openssl(["x509", "-noout", "-startdate", "-enddate"], pem.encode()).decode(
        "utf-8", "replace"
    )
    start = re.search(r"notBefore=(.+)", out)
    end = re.search(r"notAfter=(.+)", out)
    if not start or not end:
        raise RuntimeError("openssl did not report certificate dates")
    parse = lambda value: datetime.datetime.strptime(
        value.strip(), "%b %d %H:%M:%S %Y %Z"
    ).replace(tzinfo=datetime.timezone.utc)
    return parse(start.group(1)), parse(end.group(1))


def cert_names(pem):
    out = run_openssl(
        ["x509", "-noout", "-subject", "-issuer", "-nameopt", "RFC2253"], pem.encode()
    ).decode("utf-8", "replace")
    issuer = subject = None
    for line in out.splitlines():
        if line.startswith("subject="):
            subject = line.split("=", 1)[1].strip()
        elif line.startswith("issuer="):
            issuer = line.split("=", 1)[1].strip()
    if issuer is None or subject is None:
        raise RuntimeError("openssl did not report certificate names")
    return subject, issuer


def cert_public_key(pem):
    return run_openssl(["x509", "-noout", "-pubkey"], pem.encode())


def private_public_key(pem):
    return run_openssl(["pkey", "-pubout"], pem.encode())


def inspect_key(box_index, key_index, key, now, min_days):
    """Validate one <Key>; returns (errors, leaf_days_or_none)."""
    errors = []
    label = "box[%d].key[%d]" % (box_index, key_index)
    chain = key.find("CertificateChain")
    if chain is None:
        return ["%s has no CertificateChain" % label], None
    declared = chain.find("NumberOfCertificates")
    cert_nodes = chain.findall("Certificate")
    if declared is None:
        errors.append("%s has no NumberOfCertificates" % label)
    elif (declared.text or "").strip() != str(len(cert_nodes)):
        errors.append(
            "%s declares %s certificates but carries %d"
            % (label, (declared.text or "").strip(), len(cert_nodes))
        )
    if not 2 <= len(cert_nodes) <= 8:
        return errors + ["%s carries %d certificates (expected 2..8)" % (label, len(cert_nodes))], None

    pems = [clean_pem(node.text) for node in cert_nodes]
    dates = []
    names = []
    for index, pem in enumerate(pems):
        try:
            dates.append(cert_dates(pem))
            names.append(cert_names(pem))
        except RuntimeError as error:
            return errors + [
                "%s certificate[%d] cannot be parsed: %s" % (label, index, error)
            ], None

    for index, (not_before, not_after) in enumerate(dates):
        if now > not_after:
            errors.append(
                "%s certificate[%d] already expired on %s"
                % (label, index, not_after.strftime("%Y-%m-%d"))
            )
        if now < not_before:
            errors.append(
                "%s certificate[%d] is not valid before %s"
                % (label, index, not_before.strftime("%Y-%m-%d"))
            )
    for index in range(len(pems) - 1):
        child_issuer = names[index][1]
        parent_subject = names[index + 1][0]
        if child_issuer != parent_subject:
            errors.append(
                "%s certificate[%d] issuer does not match certificate[%d] subject"
                % (label, index, index + 1)
            )
    if names[-1][0] != names[-1][1]:
        errors.append("%s root certificate is not self-signed" % label)

    try:
        private = key.find("PrivateKey")
        if private is None or not (private.text or "").strip():
            errors.append("%s has no PrivateKey" % label)
        elif private_public_key(clean_pem(private.text)) != cert_public_key(pems[0]):
            errors.append("%s private key does not match the leaf certificate" % label)
    except RuntimeError as error:
        errors.append("%s private key cannot be validated: %s" % (label, error))

    days = (dates[0][1] - now).total_seconds() / 86400.0
    if days < min_days:
        errors.append(
            "%s leaf has only %.1f days left (need >= %.1f)"
            % (label, days, min_days)
        )
    return errors, days


def check_document(path, require_every_box_fresh, min_days):
    now = datetime.datetime.now(datetime.timezone.utc)
    if not path.is_file():
        return ["%s is missing" % path], []
    errors = []
    notes = []
    try:
        root = ET.fromstring(path.read_bytes())
    except ET.ParseError as error:
        return ["%s is not valid XML: %s" % (path, error)], []
    if root.tag != "AndroidAttestation":
        errors.append("%s root element is %r, expected AndroidAttestation" % (path, root.tag))
    boxes = root.findall("Keybox")
    if not boxes:
        return errors + ["%s carries no <Keybox>" % path], notes
    declared = root.find("NumberOfKeyboxes")
    if declared is None or (declared.text or "").strip() != str(len(boxes)):
        errors.append(
            "%s NumberOfKeyboxes does not match the %d boxes it carries" % (path, len(boxes))
        )

    fresh_boxes = 0
    for box_index, box in enumerate(boxes):
        keys = box.findall("Key")
        if not keys:
            errors.append("%s box[%d] has no <Key>" % (path, box_index))
            continue
        box_fresh = False
        for key_index, key in enumerate(keys):
            key_errors, days = inspect_key(box_index, key_index, key, now, min_days)
            errors.extend(key_errors)
            fresh = days is not None and days >= min_days
            box_fresh = box_fresh or fresh
            if days is not None:
                notes.append(
                    "  box[%d].key[%d] alg=%-5s leaf days left=%6.1f fresh=%s"
                    % (box_index, key_index, key.get("algorithm"), days, fresh)
                )
        if box_fresh:
            fresh_boxes += 1
        elif require_every_box_fresh:
            errors.append("%s: box[%d] has no fresh key" % (path, box_index))

    if require_every_box_fresh:
        if fresh_boxes != len(boxes):
            errors.append("%s: every keybox must be fresh" % path)
    elif fresh_boxes == 0:
        errors.append("%s: no keybox has a leaf with at least %.1f days left" % (path, min_days))
    return errors, notes


def main():
    parser = argparse.ArgumentParser(description="Bundled keybox freshness gate")
    parser.add_argument("--min-days", type=float, default=3.0)
    parser.add_argument("--files", nargs="*", default=None)
    args = parser.parse_args()

    if args.files:
        targets = [(pathlib.Path(item), False) for item in args.files]
    else:
        targets = [(BUILTIN, True), (POOL, False)]

    all_errors = []
    for path, require_every in targets:
        errors, notes = check_document(path, require_every, args.min_days)
        print("== %s" % path)
        for note in notes:
            print(note)
        for error in errors:
            print("ERROR: " + error)
        all_errors.extend(errors)

    if all_errors:
        print(
            "\nThe bundled keybox is not usable for a release. Refresh it first:\n"
            "  run the daily mirror (_scratch/keybox_mirror.py all) to fetch the\n"
            "  current yurikey generation, then decode _scratch/keybox-mirror/yurikey\n"
            "  into userspace/ksud/builtin/tricky_store/keybox.xml and rebuild\n"
            "  manager/app/src/main/assets/xec_keybox.xml from the merged feed.\n"
            "See docs/tee-reliability-plan.md for the full procedure."
        )
        return 1
    print("\nBundled keybox is fresh enough (>= %.1f leaf days)." % args.min_days)
    return 0


if __name__ == "__main__":
    sys.exit(main())
