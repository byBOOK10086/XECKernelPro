/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * antiwipe.kpm — XECKernelPro anti-wipe guard (KPM form)
 *
 * A thin loader module: the guard implementation lives in XECKernelPro's kernel
 * (feature/antiwipe.c) and stays inert until this module calls
 * ksu_antiwipe_arm(). Unloading the module (or never loading it) leaves the
 * kernel completely unguarded — no boot-time hooks, no reboot loops.
 *
 * Only XECKernelPro kernels expose ksu_antiwipe_* (through the KSU KPM engine),
 * so this module cannot do anything on other kernels.
 *
 * Note: this module deliberately avoids the KernelPatch lib_* / kf_* helper
 * table (our KPM engine resolves kernel symbols instead), so every string
 * operation is hand written and only kernel symbols are imported.
 */

#include <compiler.h>
#include <kpmodule.h>
#include <ktypes.h>
#include <linux/printk.h>

KPM_NAME("antiwipe");
KPM_VERSION("1.0.0");
KPM_LICENSE("GPL v2");
KPM_AUTHOR("XECKernelPro");
KPM_DESCRIPTION("Anti-wipe guard: blocks raw partition writes and protected path removal by uid 0 (XECKernelPro only)");

/* Implemented by the XECKernelPro kernel (feature/antiwipe.c). */
extern int ksu_antiwipe_arm(void);
extern void ksu_antiwipe_disarm(void);
extern int ksu_antiwipe_active(void);
extern void ksu_antiwipe_trust(unsigned int seconds);
extern unsigned long long ksu_antiwipe_blocked(void);

/* Kernel symbol: copies to a user buffer, returns the number of bytes left. */
extern unsigned long _copy_to_user(void *to, const void *from, unsigned long n);

static int s_prefix(const char *s, const char *prefix)
{
    while (*prefix) {
        if (*s++ != *prefix++)
            return 0;
    }
    return 1;
}

static void s_append(char *buf, int cap, const char *s)
{
    int len = 0;

    while (len < cap && buf[len] != '\0')
        len++;
    while (s && *s && len < cap - 1)
        buf[len++] = *s++;
    buf[len] = '\0';
}

static void s_append_dec(char *buf, int cap, unsigned long long v)
{
    char tmp[24];
    int n = 0;
    int len = 0;

    while (len < cap && buf[len] != '\0')
        len++;

    if (v == 0) {
        tmp[n++] = '0';
    } else {
        while (v > 0 && n < (int)sizeof(tmp)) {
            tmp[n++] = (char)('0' + (int)(v % 10));
            v /= 10;
        }
    }

    while (n > 0 && len < cap - 1)
        buf[len++] = tmp[--n];
    buf[len] = '\0';
}

static unsigned int parse_uint(const char *s)
{
    unsigned int v = 0;

    if (!s)
        return 0;

    while (*s == ' ')
        s++;
    while (*s >= '0' && *s <= '9') {
        v = v * 10 + (unsigned int)(*s - '0');
        s++;
    }

    return v;
}

static long antiwipe_init(const char *args, const char *event, void *reserved)
{
    int ret;

    (void)reserved;

    ret = ksu_antiwipe_arm();
    if (ret) {
        pr_err("antiwipe: arm failed: %d\n", ret);
        return ret;
    }

    pr_info("antiwipe: armed (event=%s args=%s)\n", event ? event : "-", args ? args : "-");
    return 0;
}

static long antiwipe_ctl0(const char *ctl_args, char *out_msg, int outlen)
{
    char buf[128];

    buf[0] = '\0';

    if (ctl_args && s_prefix(ctl_args, "trust")) {
        unsigned int sec = parse_uint(ctl_args + 5);

        if (sec > 3600)
            sec = 3600;
        ksu_antiwipe_trust(sec);
        s_append(buf, (int)sizeof(buf), "trust=");
        s_append_dec(buf, (int)sizeof(buf), (unsigned long long)sec);
    } else if (ctl_args && s_prefix(ctl_args, "off")) {
        ksu_antiwipe_disarm();
        s_append(buf, (int)sizeof(buf), "disarmed");
    } else {
        s_append(buf, (int)sizeof(buf), "armed=");
        s_append_dec(buf, (int)sizeof(buf), (unsigned long long)ksu_antiwipe_active());
        s_append(buf, (int)sizeof(buf), " blocked=");
        s_append_dec(buf, (int)sizeof(buf), ksu_antiwipe_blocked());
    }

    if (out_msg && outlen > 0)
        _copy_to_user(out_msg, buf, sizeof(buf));

    return 0;
}

static long antiwipe_exit(void *reserved)
{
    (void)reserved;
    ksu_antiwipe_disarm();
    pr_info("antiwipe: disarmed\n");
    return 0;
}

KPM_INIT(antiwipe_init);
KPM_CTL0(antiwipe_ctl0);
KPM_EXIT(antiwipe_exit);
