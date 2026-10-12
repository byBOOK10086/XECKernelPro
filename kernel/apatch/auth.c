// SPDX-License-Identifier: GPL-2.0-only
/*
 * APatch/KernelPatch 兼容层的凭据判定。
 *
 * 兼容层必须同时接受三种凭据（内核侧 ABI 规格 §2）：
 *   1. arg0 == NULL：KPatch-Next 的 kpmd 全部调用都传 NULL，只要求 uid 0；
 *   2. arg0 == "su"：APatch 0.13.9 的默认路径（管理器 / 白名单）；
 *   3. arg0 == 真 superkey：常量时间比较（Kconfig 编译期 key 或
 *      /data/adb/ap/superkey，运行期还可用 SKEY_SET 更新）。
 * 另外 uid 0 本身按 ADMIN 处理，与上游 KPatch-Next 的"仅 root"模型一致。
 */
#include <linux/cred.h>
#include <linux/errno.h>
#include <linux/file.h>
#include <linux/fs.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <linux/uio.h>
#include <linux/version.h>

#include "klog.h" // IWYU pragma: keep
#include "manager/manager_identity.h"
#include "apatch/auth.h"
#include "apatch/scdefs.h"

/* ITER_DEST 只在 v6.2 起存在（同 kpm.c 的处理） */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 2, 0)
#define SC_ITER_DEST ITER_DEST
#else
#define SC_ITER_DEST READ
#endif

#define SC_SUPERKEY_PATH "/data/adb/ap/superkey"

/* 运行时 superkey（最多 SC_KEY_MAX_LEN 字节 + NUL）。空 = 未设置。 */
static char sc_key[SC_KEY_MAX_LEN + 1];
static bool sc_key_file_tried;
static DEFINE_MUTEX(sc_key_lock);

/* 常量时间比较：长度不同直接判否，长度相同则全程不提前返回 */
static bool sc_key_equal(const char *a, size_t alen, const char *b, size_t blen)
{
    unsigned char diff = 0;
    size_t i;

    if (alen != blen || alen == 0 || alen > SC_KEY_MAX_LEN)
        return false;

    for (i = 0; i < alen; i++)
        diff |= (unsigned char)(a[i] ^ b[i]);

    return diff == 0;
}

/*
 * 读文件的小工具。kernel_read() 在新内核里没有了，所以按 f_op->read /
 * read_iter 两条路走（与 kpm.c 的 kpm_kernel_read 同一套兼容写法）。
 */
static ssize_t sc_read_file(const char *path, void *buf, size_t count)
{
    struct file *filp;
    ssize_t ret = -EINVAL;
    loff_t pos = 0;

    filp = filp_open(path, O_RDONLY, 0);
    if (IS_ERR(filp))
        return PTR_ERR(filp);

    if (filp->f_op->read)
        ret = filp->f_op->read(filp, (char __user *)buf, count, &pos);
    else if (filp->f_op->read_iter) {
        struct kvec iov = { .iov_base = buf, .iov_len = count };
        struct kiocb kiocb = { .ki_filp = filp };
        struct iov_iter iter;

        kiocb.ki_pos = 0;
        iov_iter_kvec(&iter, SC_ITER_DEST, &iov, 1, count);
        ret = filp->f_op->read_iter(&kiocb, &iter);
    }

    filp_close(filp, NULL);
    return ret;
}

/*
 * 读取 /data/adb/ap/superkey。只在第一次需要时读，读到就缓存；读不到就保持
 * "未设置"。绝不在 dmesg 里打印 key 内容。
 */
static void sc_key_try_load_file(void)
{
    char *buf;
    ssize_t rd;

    if (sc_key_file_tried)
        return;
    sc_key_file_tried = true;

    buf = kzalloc(SC_KEY_MAX_LEN + 2, GFP_KERNEL);
    if (!buf)
        return;

    rd = sc_read_file(SC_SUPERKEY_PATH, buf, SC_KEY_MAX_LEN);
    if (rd <= 0)
        goto out;

    /* 去掉尾随空白：用户在文件里多敲一个回车不该让整个 key 失配 */
    while (rd > 0 && (buf[rd - 1] == '\n' || buf[rd - 1] == '\r' || buf[rd - 1] == ' ' || buf[rd - 1] == '\t'))
        rd--;

    if (rd > 0) {
        memcpy(sc_key, buf, rd);
        sc_key[rd] = '\0';
        pr_info("apatch-compat: superkey loaded from %s (%zd bytes)\n", SC_SUPERKEY_PATH, rd);
    }
out:
    memzero_explicit(buf, SC_KEY_MAX_LEN + 2);
    kfree(buf);
}

void sc_auth_init(void)
{
    mutex_lock(&sc_key_lock);
#ifdef CONFIG_KSU_APATCH_SUPERKEY
    /*
	 * 编译期 key（Kconfig 字符串）。它会以明文出现在 .rodata 里，介意就别配，
	 * 改用 /data/adb/ap/superkey 或运行期 SKEY_SET。
	 */
    if (strnlen(CONFIG_KSU_APATCH_SUPERKEY, SC_KEY_MAX_LEN) > 0) {
        size_t n = strnlen(CONFIG_KSU_APATCH_SUPERKEY, SC_KEY_MAX_LEN);

        memcpy(sc_key, CONFIG_KSU_APATCH_SUPERKEY, n);
        sc_key[n] = '\0';
        pr_info("apatch-compat: superkey from build config (%zu bytes)\n", n);
    }
#endif
    mutex_unlock(&sc_key_lock);
}

int sc_auth_set_key(const char __user *ukey)
{
    char buf[SC_KEY_MAX_LEN + 1] = { 0 };
    long n;

    if (!ukey)
        return -EINVAL;

    n = strncpy_from_user(buf, ukey, SC_KEY_MAX_LEN);
    if (n <= 0)
        return -EINVAL;

    mutex_lock(&sc_key_lock);
    memcpy(sc_key, buf, n);
    sc_key[n] = '\0';
    sc_key_file_tried = true;
    mutex_unlock(&sc_key_lock);

    memzero_explicit(buf, sizeof(buf));
    pr_info("apatch-compat: superkey updated (%ld bytes)\n", n);
    return 0;
}

long sc_auth_get_key(char __user *ubuf, int buflen)
{
    size_t n;
    int rc = 0;

    if (!ubuf || buflen <= 0)
        return -EINVAL;

    mutex_lock(&sc_key_lock);
    sc_key_try_load_file();
    n = strnlen(sc_key, SC_KEY_MAX_LEN);
    if (n == 0) {
        rc = -ENOENT;
    } else if ((size_t)buflen <= n) {
        rc = -ENOBUFS;
    } else if (copy_to_user(ubuf, sc_key, n + 1)) {
        rc = -EFAULT;
    }
    mutex_unlock(&sc_key_lock);

    return rc ? rc : (long)n;
}

enum sc_cred sc_cred_of(u64 key_ptr)
{
    char key[SC_KEY_MAX_LEN + 2] = { 0 };
    bool is_root = (current_uid().val == 0);
    enum sc_cred cred = SC_CRED_NONE;
    long n;

    /* 1) kpmd / KPatch-Next：arg0 == NULL，只看 uid */
    if (!key_ptr)
        return is_root ? SC_CRED_ADMIN : SC_CRED_NONE;

    /* 坏指针按"无凭据"处理（不要返回 -EFAULT，那会变成探测面） */
    n = strncpy_from_user(key, (const char __user *)key_ptr, SC_KEY_MAX_LEN);
    if (n <= 0)
        return is_root ? SC_CRED_ADMIN : SC_CRED_NONE;

    /* 2) 真 superkey */
    mutex_lock(&sc_key_lock);
    sc_key_try_load_file();
    if (sc_key_equal(sc_key, strnlen(sc_key, SC_KEY_MAX_LEN), key, (size_t)n))
        cred = SC_CRED_ADMIN;
    mutex_unlock(&sc_key_lock);

    /* 3) 字面量 "su"：管理器算 ADMIN，否则只给 SU 档（只读命令） */
    if (cred == SC_CRED_NONE && n == 3 && key[0] == 's' && key[1] == 'u' && key[2] == '\0') {
        if (is_manager())
            cred = SC_CRED_ADMIN;
        else if (is_root)
            cred = SC_CRED_ADMIN;
        else
            cred = SC_CRED_SU;
    }

    if (cred == SC_CRED_NONE && is_root)
        cred = SC_CRED_ADMIN;

    memzero_explicit(key, sizeof(key));
    return cred;
}
