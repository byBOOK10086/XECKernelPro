/* SPDX-License-Identifier: GPL-2.0-only */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define __user
#define SC_KEY_MAX_LEN 0x40
#define pr_info(...) ((void)0)
typedef uint64_t u64;
enum sc_cred { SC_CRED_NONE, SC_CRED_SU, SC_CRED_ADMIN };
static char sc_key[SC_KEY_MAX_LEN + 1];
static size_t sc_key_len;
static bool sc_key_file_tried, manager, allowed;
static int sc_key_lock;
static struct { unsigned int val; } uid;
#define current_uid() (uid)
static bool is_manager(void) { return manager; }
static bool ksu_is_allow_uid_for_current(unsigned int value) { return allowed; }
static void mutex_lock(int *lock) { assert((*lock)++ == 0); }
static void mutex_unlock(int *lock) { assert(--*lock == 0); }
static void sc_key_try_load_file(void) { }
static void memzero_explicit(void *buffer, size_t length) { memset(buffer, 0, length); }
static long strncpy_from_user(char *dest, const char *source, size_t count)
{
    if ((uintptr_t)source == 1) return -EFAULT;
    size_t length = strnlen(source, count);
    memcpy(dest, source, length < count ? length + 1 : count);
    return (long)length; /* Linux: excludes the NUL, returns count on truncation. */
}

/* KERNEL_FUNCTIONS */

static enum sc_cred credential(const char *key) { return sc_cred_of((u64)(uintptr_t)key); }
int main(void)
{
    uid.val = 10000;
    assert(sc_cred_of(0) == SC_CRED_NONE);
    assert(sc_cred_of(1) == SC_CRED_NONE);
    assert(credential("su") == SC_CRED_NONE);
    allowed = true;
    assert(credential("su") == SC_CRED_SU);
    allowed = false;
    manager = true;
    assert(credential("su") == SC_CRED_ADMIN);
    manager = false;

    assert(sc_auth_set_key("long-test-secret") == 0);
    assert(sc_key_file_tried);
    assert(credential("long-test-secret") == SC_CRED_ADMIN);
    assert(credential("long-test-secreu") == SC_CRED_NONE);
    assert(credential("long") == SC_CRED_NONE);
    assert(credential("") == SC_CRED_NONE);
    assert(sc_auth_set_key("short") == 0);
    assert(sc_key_len == 5);
    for (size_t i = 5; i < sizeof(sc_key); i++) assert(sc_key[i] == 0);
    assert(credential("short") == SC_CRED_ADMIN);
    assert(credential("long-test-secret") == SC_CRED_NONE);

    char maximum[SC_KEY_MAX_LEN + 1];
    memset(maximum, 'x', SC_KEY_MAX_LEN);
    maximum[SC_KEY_MAX_LEN] = 0;
    assert(sc_auth_set_key(maximum) == 0);
    assert(credential(maximum) == SC_CRED_ADMIN);
    char oversized[SC_KEY_MAX_LEN + 2];
    memset(oversized, 'x', SC_KEY_MAX_LEN + 1);
    oversized[SC_KEY_MAX_LEN + 1] = 0;
    assert(sc_auth_set_key(oversized) == -EINVAL);
    assert(credential(oversized) == SC_CRED_NONE);
    assert(credential(maximum) == SC_CRED_ADMIN);
    assert(sc_auth_set_key(NULL) == -EINVAL);
    assert(sc_auth_set_key("") == -EINVAL);

    uid.val = 0;
    assert(sc_cred_of(0) == SC_CRED_ADMIN);
    assert(sc_cred_of(1) == SC_CRED_ADMIN);
    assert(credential("wrong") == SC_CRED_ADMIN);
    assert(credential(oversized) == SC_CRED_ADMIN);
    assert(sc_key_lock == 0);
    puts("APatch key boundaries, replacement, and public-literal authorization passed");
    return 0;
}
