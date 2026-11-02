// SPDX-License-Identifier: GPL-2.0-or-later
/* Test the actual sample against an intentionally opaque fake native runtime.
 * No kernel headers required. Any new private kernel import breaks the link.
 */
#include "../susfs_lite.c"
static unsigned int abi = 1, registered, removed, fail_at;
static kpm_path_filter_v1 callback;
static void *callback_context;
unsigned int kpm_path_hook_abi_v1(void) { return abi; }
int kpm_path_hook_register_v1(const char *symbol, unsigned int arg,
                             kpm_path_filter_v1 fn, void *context, void **handle)
{
    if (arg != 1 || !symbol || !fn || *handle)
        return -99;
    if (!starts_with(symbol, "vfs_fstatat") && !starts_with(symbol, "do_faccessat"))
        return -99;
    registered++;
    if (fail_at == registered)
        return -38;
    callback = fn;
    callback_context = context;
    *handle = (void *)(__UINTPTR_TYPE__)registered;
    return 0;
}
void kpm_path_hook_unregister_v1(void *handle)
{
    if (handle)
        removed++;
}
#define CHECK(x) do { if (!(x)) return __LINE__; } while (0)
int test_main(void)
{
    unsigned int n;
    struct hide_state parsed;
    abi = 2;
    CHECK(susfs_lite_init(0, 0, 0) == -EOPNOTSUPP && registered == 0);
    abi = 1;
    CHECK(parse_args("prefix=/", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/a,", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/a;prefix=/b", &parsed) == -EINVAL);
    CHECK(parse_args("allow_uids=4294967296", &parsed) == -EINVAL);
    CHECK(parse_args("allow_uids=1,", &parsed) == -EINVAL);
    CHECK(parse_args("bogus=1", &parsed) == -EINVAL);
    CHECK(parse_args("allow_uids=1,2,3,4,5,6,7,8,9", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/a,/b,/c,/d,/e,/f,/g,/h,/i", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/a;", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/a;allow_uids=1;allow_uids=2", &parsed) == -EINVAL);
    CHECK(parse_args("prefix=/a;allow_uids=4294967295", &parsed) == 0);
    fail_at = 2;
    CHECK(susfs_lite_init(0, 0, 0) == -38);
    CHECK(registered == 2 && removed == 1 && !handles[0] && !handles[1]);
    fail_at = 0;
    CHECK(susfs_lite_init("allow_uids=10236;prefix=/data/adb/,/debug_ramdisk", 0, 0) == 0);
    CHECK(callback("/data/adb/a", 2000, 0, callback_context) == -ENOENT);
    CHECK(callback("/data/adb", 2000, 0, callback_context) == -ENOENT);
    CHECK(callback("/data/adb2", 2000, 0, callback_context) == 0);
    CHECK(callback("/data/adb", 0, 0, callback_context) == 0);
    CHECK(callback("/data/adb", 10236, 0, callback_context) == 0);
    CHECK(callback("/data/adb", 2000, -13, callback_context) == -13);
    CHECK(callback("/debug_ramdisk/a", 2000, 0, callback_context) == -ENOENT);
    CHECK(callback(0, 2000, 0, callback_context) == 0);
    n = registered;
    CHECK(susfs_lite_ctl0("reload prefix=/other", 0, 0) == -EBUSY);
    CHECK(susfs_lite_init(0, 0, 0) == -EBUSY && registered == n);
    CHECK(callback("/data/adb", 2000, 0, callback_context) == -ENOENT);
    CHECK(susfs_lite_exit(0) == 0 && removed == 3);
    CHECK(susfs_lite_exit(0) == 0 && removed == 3);
    CHECK(susfs_lite_init(0, 0, 0) == 0);
    CHECK(callback("/data/adb", 2000, 0, callback_context) == -ENOENT);
    CHECK(susfs_lite_exit(0) == 0 && removed == 5);
    return 0;
}
#ifndef SUSFS_WINDOWS_FREESTANDING
int main(void) { return test_main(); }
#endif
