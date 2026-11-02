/* SPDX-License-Identifier: GPL-3.0-only */
#include <stdio.h>
#include <stdlib.h>
#include "btf_host_stubs.h"
#include "infra/btf_parser.h"

static struct btf metadata;
static struct { struct btf_type type; struct btf_member members[4]; } record;
static struct { struct btf_type type; u32 encoding; } integer;
static const struct btf *metadata_result;
static s32 lookup_result;
static int missing_type, missing_member_type, missing_name;
static unsigned int checks;

const struct btf *bpf_get_btf_vmlinux(void) { return metadata_result; }
s32 btf_find_by_name_kind(const struct btf *b, const char *name, u32 kind)
{
    (void)b;
    if (strcmp(name, "lsm_blob_sizes") || kind != BTF_KIND_STRUCT)
        return -ENOENT;
    return lookup_result;
}
const struct btf_type *btf_type_by_id(const struct btf *b, u32 id)
{
    (void)b;
    return missing_type || id != 1 ? NULL : &record.type;
}
const char *btf_name_by_offset(const struct btf *b, u32 offset)
{
    (void)b;
    if (missing_name)
        return NULL;
    return offset == 1 ? "lbs_inode" : "other_field";
}
const struct btf_type *btf_type_skip_modifiers(const struct btf *b, u32 id, u32 *resolved)
{
    (void)b;
    (void)resolved;
    return missing_member_type || id != 2 ? NULL : &integer.type;
}
static void reset(void)
{
    memset(&record, 0, sizeof(record));
    memset(&integer, 0, sizeof(integer));
    metadata_result = &metadata;
    lookup_result = 1;
    missing_type = missing_member_type = missing_name = 0;
    record.type.info = (BTF_KIND_STRUCT << 24) | 3;
    record.type.size = 12;
    record.members[0] = (struct btf_member){ 0, 2, 0 };
    record.members[1] = (struct btf_member){ 0, 2, 32 };
    record.members[2] = (struct btf_member){ 1, 2, 64 };
    integer.type.info = BTF_KIND_INT << 24;
    integer.type.size = 4;
    integer.encoding = (1U << 24) | 32; /* signed full-width int */
}
static void expect(const char *label, u32 kind, u32 size, int want, u32 offset)
{
    struct ksu_btf_field field = { 0xdeadbeef, 0xcafebabe };
    int rc = ksu_btf_field("lsm_blob_sizes", "lbs_inode", kind, size, &field);
    if (rc != want || (!rc && (field.offset != offset || field.struct_size != record.type.size)) ||
        (rc && (field.offset != 0xdeadbeef || field.struct_size != 0xcafebabe))) {
        fprintf(stderr, "FAIL %s: rc=%d expected=%d offset=%u size=%u\n",
                label, rc, want, field.offset, field.struct_size);
        exit(1);
    }
    checks++;
}
#define CHECK(label, setup, result) do { reset(); setup; expect(label, BTF_KIND_INT, 4, result, 8); } while (0)
int main(void)
{
    CHECK("old layout, final member exact boundary", (void)0, 0);
    reset();
    record.type.info = (BTF_KIND_STRUCT << 24) | 4;
    record.type.size = 16;
    record.members[2].name_off = 0; /* inserted lbs_backing_file */
    record.members[3] = (struct btf_member){ 1, 2, 96 };
    expect("new layout with backing file", BTF_KIND_INT, 4, 0, 12);
    CHECK("NULL metadata", metadata_result = NULL, -ENOENT);
    CHECK("ERR metadata", metadata_result = ERR_PTR(-EIO), -EIO);
    CHECK("missing struct", lookup_result = -ENOENT, -ENOENT);
    CHECK("missing type", missing_type = 1, -EINVAL);
    CHECK("missing member", record.members[2].name_off = 0, -ENOENT);
    CHECK("empty struct", record.type.info = BTF_KIND_STRUCT << 24, -ENOENT);
    CHECK("invalid member name", missing_name = 1, -EINVAL);
    CHECK("missing member type", missing_member_type = 1, -EINVAL);
    CHECK("wrong kind", integer.type.info = BTF_KIND_PTR << 24, -EINVAL);
    CHECK("bitfield", record.type.info |= 1U << 31; record.members[2].offset |= 1U << 24, -EINVAL);
    CHECK("kflag without bitfield", record.type.info |= 1U << 31, 0);
    CHECK("unaligned bit offset", record.members[2].offset = 65, -EINVAL);
    CHECK("unaligned byte offset", record.members[2].offset = 72, -EINVAL);
    CHECK("member at struct end", record.members[2].offset = 96, -EINVAL);
    CHECK("member beyond struct", record.members[2].offset = 128, -EINVAL);
    CHECK("member truncated", record.type.size = 11, -EINVAL);
    CHECK("very large offset", record.members[2].offset = UINT32_MAX - 31, -EINVAL);
    CHECK("wrong int size", integer.type.size = 8, -EINVAL);
    CHECK("int bit offset", integer.encoding |= 1U << 16, -EINVAL);
    CHECK("partial int width", integer.encoding = 31, -EINVAL);
    CHECK("char encoding", integer.encoding |= BTF_INT_CHAR << 24, -EINVAL);
    CHECK("bool encoding", integer.encoding |= BTF_INT_BOOL << 24, -EINVAL);
    reset(); expect("zero requested size", BTF_KIND_INT, 0, -EINVAL, 0);
    reset(); integer.type.info = BTF_KIND_PTR << 24;
    record.type.size = 8 + (u32)sizeof(void *);
    expect("pointer", BTF_KIND_PTR, sizeof(void *), 0, 8);
    expect("wrong pointer size", BTF_KIND_PTR, 1, -EINVAL, 0);
    reset(); integer.type.info = 3U << 24;
    expect("unsupported kind", 3, 4, -EINVAL, 0);
    printf("PASS: %u cases compiled against real infra/btf_parser.c\n", checks);
    return 0;
}
