# BTF parser host tests

Run from the repository root on a Linux host with Python 3 and GCC or Clang:

```sh
python3 scripts/test_kernel_btf_host.py
python3 scripts/test_kernel_btf_host.py --cc clang --sanitize
```

`CC` or `--cc` selects the host compiler. Windows requires a host C SDK and
runtime in addition to a compiler; an Android NDK compiler alone may not have
those prerequisites. The test uses a temporary directory and cleans it up on
success or failure. It performs no kernel build, network request, or device
operation. Sanitizers must be supported by the selected host toolchain.

The runner compiles the repository's actual `kernel/infra/btf_parser.c` as a
separate translation unit with MODULE and CONFIG_DEBUG_INFO_BTF enabled. Shim
headers provide only kernel types, UAPI BTF encodings, logging and error-pointer
macros. Fixture BTF API functions return synthetic structures and INT metadata;
they do not reimplement the parser's offset/type validation algorithm.

The 28 cases cover:

- Old and new `lsm_blob_sizes` layouts, including an inserted backing-file field.
- Exact end-of-structure success; missing metadata, structure, member, type and name.
- Error pointers, wrong kinds, bitfields and kflag without a bitfield.
- Bit/byte alignment, at-end and beyond-end offsets, truncated members and large offsets.
- Integer size, bit offset, partial width, char/bool encoding and zero requested size.
- Pointer success, pointer-size rejection and unsupported member kinds.

Every rejection also checks that the output structure is unchanged. The C test
exits nonzero at the first mismatch; compile and test failures propagate through
the Python runner.

These tests exercise the parser contract against synthetic BTF API responses.
They do not validate the real kernel's BTF implementation/exports, inode blob
allocation, module initialization rollback, KMI compatibility, or device boot.
Initialization ordering and failure cleanup require separate source review;
real GKI/Oplus behavior still requires target-specific validation.
