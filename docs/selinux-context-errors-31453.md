# SELinux context conversion error outputs (31453)

A failed conversion could leave caller-provided SID outputs unchanged. Empty
input and failure to allocate the writable context copy returned before output
initialization. During a live-policy reload, the dual converter could insert a
backup SID, retry after live `-ESTALE`, and then fail while retaining that first
SID in the output.

Both converters now initialize SID outputs before early returns and clear the
primary SID on a final error. The dual converter initializes its optional live
status to `-EINVAL` for empty input and reports `-ENOMEM` when allocating the
context copy fails. These early status outputs were previously unspecified.

The primary return and optional live return remain separate: primary success
with live failure still returns the valid backup SID and a null live SID. The
parser, SID lookup order, permission checks and audit behavior are unchanged.
No rejected-context cache or delay was added.

## Verification

The regression fixture extracts the production functions and injects allocation
and SID-table failures. New cases cover empty input, failed context-copy
allocation, all four optional-output combinations, live `-ESTALE` followed by
MLS or backup SID insertion failure, and backup success with live `-ENOMEM`.
Existing cases cover independent symbol IDs, MLS restoration, malformed
contexts, generation metadata and successful policy-reload retries. Every case
checks context/buffer ownership and RCU balance.

The early-output assertion failed against the old implementation. After the
fix, all seven host regression variants pass:

```sh
python scripts/test_kernel_regressions.py
```

Linux CI additionally runs this suite with `--sanitize` (ASan and UBSan), the
private boot-log filesystem tests, and the supported KMI build matrix. Host
mocks and successful builds do not establish physical-device behavior or
constant-time operation. No Android device was connected for this change.
