// SPDX-License-Identifier: GPL-2.0-or-later
/* Built-in stat/access filter. Native kernel probe ABI stays in the engine. */
#define __user
#include "kpmodule.h"
#include "path_hook_v1.h"

KPM_NAME("susfs_lite");
KPM_VERSION("0.2.0");
KPM_AUTHOR("XECKernelPro");
KPM_LICENSE("GPL-2.0-or-later");
KPM_DESCRIPTION("Stat/access hiding via versioned engine path hooks");

#define MAX_PREFIXES 8
#define PREFIX_LEN 96
#define MAX_ALLOW_UIDS 8
#define EINVAL 22
#define EBUSY 16
#define ENOENT 2
#define EOPNOTSUPP 95

struct hide_state {
    char prefixes[MAX_PREFIXES][PREFIX_LEN];
    unsigned int prefix_count;
    unsigned int allow_uids[MAX_ALLOW_UIDS];
    unsigned int allow_count;
};
static struct hide_state state;
static void *handles[2];

static int starts_with(const char *p, const char *key)
{
    while (*key) {
        if (*p++ != *key++)
            return 0;
    }
    return 1;
}

/* Strict bounded parser: never truncate a prefix into a broader rule. */
static int parse_args(const char *p, struct hide_state *s)
{
    unsigned int seen = 0;
    unsigned int z;
    for (z = 0; z < sizeof(*s); z++)
        ((volatile unsigned char *)s)[z] = 0;
    if (!p)
        p = "";
    while (*p) {
        if (starts_with(p, "allow_uids=")) {
            if (seen & 1)
                return -EINVAL;
            seen |= 1;
            p += 11;
            if (*p != ';' && *p) {
                for (;;) {
                    unsigned int n = 0;
                    if (*p < '0' || *p > '9' || s->allow_count == MAX_ALLOW_UIDS)
                        return -EINVAL;
                    do {
                        unsigned int d = (unsigned int)(*p++ - '0');
                        if (n > (4294967295U - d) / 10)
                            return -EINVAL;
                        n = n * 10 + d;
                    } while (*p >= '0' && *p <= '9');
                    s->allow_uids[s->allow_count++] = n;
                    if (*p != ',')
                        break;
                    p++;
                }
            }
        } else if (starts_with(p, "prefix=")) {
            if (seen & 2)
                return -EINVAL;
            seen |= 2;
            p += 7;
            for (;;) {
                unsigned int n = 0;
                if (*p != '/' || s->prefix_count == MAX_PREFIXES)
                    return -EINVAL;
                while (*p && *p != ',' && *p != ';') {
                    if (n == PREFIX_LEN - 1)
                        return -EINVAL;
                    s->prefixes[s->prefix_count][n++] = *p++;
                }
                /* Strip trailing slash; root cannot be a hiding prefix. */
                while (n > 1 && s->prefixes[s->prefix_count][n-1] == '/')
                    n--;
                if (n == 1)
                    return -EINVAL;
                s->prefixes[s->prefix_count++][n] = 0;
                if (*p != ',')
                    break;
                p++;
            }
        } else {
            return -EINVAL;
        }
        if (!*p)
            break;
        if (*p++ != ';' || !*p)
            return -EINVAL;
    }
    if (!(seen & 2)) {
        const char *d = "/data/adb";
        unsigned int i = 0;
        do { s->prefixes[0][i] = d[i]; } while (d[i++]);
        s->prefix_count = 1;
    }
    return 0;
}

static long filter_path(const char *path, unsigned int uid, long result, void *context)
{
    const struct hide_state *s = context;
    unsigned int i;
    if (result || !uid || !path)
        return result;
    for (i = 0; i < s->allow_count; i++)
        if (s->allow_uids[i] == uid)
            return result;
    for (i = 0; i < s->prefix_count; i++) {
        unsigned int n = 0;
        while (s->prefixes[i][n] && path[n] == s->prefixes[i][n])
            n++;
        if (!s->prefixes[i][n] && (!path[n] || path[n] == '/'))
            return -ENOENT;
    }
    return result;
}

static long susfs_lite_init(const char *args, const char *event, void *reserved)
{
    int ret;
    struct hide_state next;
    (void)event;
    (void)reserved;
    if (handles[0] || handles[1])
        return -EBUSY;
    if (kpm_path_hook_abi_v1() != 1)
        return -EOPNOTSUPP;
    ret = parse_args(args, &next);
    if (ret)
        return ret;
    {
        unsigned int z;
        for (z = 0; z < sizeof(state); z++)
            ((volatile unsigned char *)&state)[z] = ((unsigned char *)&next)[z];
    }
    /* Native vfs_fstatat receives the user filename in argument 1.
     * Engine must reject absent/unsupported targets; no guessed syscall ABI.
     */
    ret = kpm_path_hook_register_v1("vfs_fstatat", 1, filter_path, &state, &handles[0]);
    if (ret)
        return ret;
    ret = kpm_path_hook_register_v1("do_faccessat", 1, filter_path, &state, &handles[1]);
    if (ret) {
        kpm_path_hook_unregister_v1(handles[0]);
        handles[0] = 0;
        return ret;
    }
    return 0;
}

static long susfs_lite_ctl0(const char *args, char __user *out, int len)
{
    (void)args;
    (void)out;
    (void)len;
    /* No live state writes while callbacks read it. Unload + load to change. */
    return -EBUSY;
}

static long susfs_lite_exit(void *reserved)
{
    int i;
    (void)reserved;
    for (i = 1; i >= 0; i--) {
        if (handles[i]) {
            kpm_path_hook_unregister_v1(handles[i]);
            handles[i] = 0;
        }
    }
    return 0;
}
KPM_INIT(susfs_lite_init);
KPM_CTL0(susfs_lite_ctl0);
KPM_EXIT(susfs_lite_exit);
