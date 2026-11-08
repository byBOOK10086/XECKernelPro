// SPDX-License-Identifier: GPL-2.0
#include <linux/module.h>
#include <linux/fs.h>
#include <linux/namei.h>
#include <linux/fsnotify_backend.h>
#include <linux/sched.h>
#include <linux/slab.h>
#include <linux/rculist.h>
#include <linux/task_work.h>
#include <linux/version.h>
#include <linux/jiffies.h>
#include "klog.h" // IWYU pragma: keep
#include "manager/manager_identity.h"
#include "manager/manager_observer.h"
#include "manager/throne_tracker.h"

#define MASK_SYSTEM (FS_CREATE | FS_MOVE | FS_EVENT_ON_CHILD)

struct watch_dir {
    const char *path;
    u32 mask;
    struct path kpath;
    struct inode *inode;
    struct fsnotify_mark *mark;
};

static struct fsnotify_group *g;

static void ksu_track_throne_tw_func(struct callback_head *cb)
{
    kfree(cb);
    // Runs after exit_fs() if the task is exiting; path lookups would oops
    if (current->flags & PF_EXITING)
        return;
    track_throne(false);
}

// Never scan from the fsnotify handler: fsnotify_mark_srcu and the rename's dir
// locks would be held for the whole scan. Defer with task_work, not a kthread
// or kworker: the scan must finish before the rename returns, or the manager
// can be opened before it is crowned, and it must run in the renamer's
// (Android's) namespaces, which differ from init_task's on WSA/Waydroid.
static void ksu_defer_track_throne(void)
{
    struct callback_head *cb;

    if (!(current->flags & PF_KTHREAD)) {
        cb = kzalloc(sizeof(*cb), GFP_KERNEL);
        if (cb) {
            cb->func = ksu_track_throne_tw_func;
            if (!task_work_add(current, cb, TWA_RESUME))
                return;
            kfree(cb);
        }
    }
    pr_warn("defer track_throne failed, run it inline\n");
    track_throne(false);
}

/*
 * 开机丢 root 的最后一环：`ksu_manager_appid` 一旦在开机早期没被认出来，
 * `is_manager()` 就一直是假 —— 管理器的特权 supercall 被 perm.c 拒掉，界面表现
 * 就是"root 没了"，首页退回原版 KernelSU 的「未安装 / 安装」页。
 *
 * 本来有两道自愈，但都不够：
 *   1) `on_boot_completed()` 里那次 track_throne(false) —— **late load 永远等不到**。
 *      模块在 boot_completed 之后才 insmod 时，init 里那次扫描是唯一一次机会，
 *      而那一刻 /data 可能还没起全，或者 packages.list 还是上一轮留下的。
 *   2) /data/system/packages.list 的 fsnotify —— 装完模块后系统如果长时间不写
 *      这个文件（不装也不卸任何应用），它同样不会响。
 *
 * 这里补第三道：管理器每次取 GET_INFO 都会走到这里（GET_INFO 是 always_allow，
 * 所以身份坏掉时管理器自己仍然读得到），只有身份无效时才真的排一次重扫。
 * 身份正常时第一个分支就返回，零开销；坏掉时一秒内自愈。
 *
 * 节流用 jiffies 而不是加锁：最坏情况只是两个 CPU 各排一次扫描，而重复扫描
 * 本身是幂等的（track_throne 自己会判 need_rescan），不值得为它引入锁。
 * 重试间隔按次数退避（5s 起、320s 封顶），恢复后计数清零。
 */
void ksu_maybe_rescan_manager(void)
{
    static unsigned long last_rescan;
    static unsigned int misses;
    unsigned long now = jiffies;
    unsigned long wait;

    if (likely(ksu_is_manager_appid_valid())) {
        misses = 0;
        last_rescan = 0;
        return;
    }

    /*
     * 退避而不是固定节流：正常的一次性故障（开机早期扫描太早）第二、三次就恢复，
     * 5s/10s/20s 几轮足够；而真配错包名那种永久无效的情况会退到 320s 一次，
     * 不会让任何一个刷 ioctl 的应用把 /data/app 扫描变成 12 次/分钟的常驻负载。
     */
    wait = msecs_to_jiffies(5000u << (misses < 6 ? misses : 6));
    if (last_rescan && time_before(now, last_rescan + wait))
        return;
    last_rescan = now;
    if (misses < 8)
        misses++;

    pr_info("manager identity invalid on get_info, rescan scheduled (%u)\n", misses);
    ksu_defer_track_throne();
}

static int ksu_handle_inode_event(struct fsnotify_mark *mark, u32 mask, struct inode *inode, struct inode *dir,
                                  const struct qstr *file_name, u32 cookie)
{
    if (!file_name)
        return 0;
    if (mask & FS_ISDIR)
        return 0;
    if (file_name->len == 13 && !memcmp(file_name->name, "packages.list", 13)) {
        pr_info("packages.list detected: %d\n", mask);
        ksu_defer_track_throne();
    }
    return 0;
}

static const struct fsnotify_ops ksu_ops = {
    .handle_inode_event = ksu_handle_inode_event,
};

static int add_mark_on_inode(struct inode *inode, u32 mask, struct fsnotify_mark **out)
{
    struct fsnotify_mark *m;

    m = kzalloc(sizeof(*m), GFP_KERNEL);
    if (!m)
        return -ENOMEM;

    fsnotify_init_mark(m, g);
    m->mask = mask;

    if (fsnotify_add_inode_mark(m, inode, 0)) {
        fsnotify_put_mark(m);
        return -EINVAL;
    }
    *out = m;
    return 0;
}

static int watch_one_dir(struct watch_dir *wd)
{
    int ret = kern_path(wd->path, LOOKUP_FOLLOW, &wd->kpath);
    if (ret) {
        pr_info("path not ready: %s (%d)\n", wd->path, ret);
        return ret;
    }
    wd->inode = d_inode(wd->kpath.dentry);
    ihold(wd->inode);

    ret = add_mark_on_inode(wd->inode, wd->mask, &wd->mark);
    if (ret) {
        pr_err("Add mark failed for %s (%d)\n", wd->path, ret);
        path_put(&wd->kpath);
        iput(wd->inode);
        wd->inode = NULL;
        return ret;
    }
    pr_info("watching %s\n", wd->path);
    return 0;
}

static void unwatch_one_dir(struct watch_dir *wd)
{
    if (wd->mark) {
        fsnotify_destroy_mark(wd->mark, g);
        fsnotify_put_mark(wd->mark);
        wd->mark = NULL;
    }
    if (wd->inode) {
        iput(wd->inode);
        wd->inode = NULL;
    }
    if (wd->kpath.dentry) {
        path_put(&wd->kpath);
        memset(&wd->kpath, 0, sizeof(wd->kpath));
    }
}

static struct watch_dir g_watch = { .path = "/data/system", .mask = MASK_SYSTEM };

int ksu_observer_init(void)
{
    int ret = 0;

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 0, 0)
    g = fsnotify_alloc_group(&ksu_ops, 0);
#else
    g = fsnotify_alloc_group(&ksu_ops);
#endif
    if (IS_ERR(g))
        return PTR_ERR(g);

    ret = watch_one_dir(&g_watch);
    pr_info("observer init done\n");
    return 0;
}

void __exit ksu_observer_exit(void)
{
    unwatch_one_dir(&g_watch);
    fsnotify_put_group(g);
    pr_info("observer exit done\n");
}