#include "feature/selinux_hide.h"
#include <linux/err.h>
#include <linux/fs.h>
#include <linux/namei.h>
#include <linux/printk.h>

#include "policy/allowlist.h"
#include "policy/feature.h"
#include "klog.h" // IWYU pragma: keep
#include "runtime/ksud_boot.h"
#include "runtime/ksud.h"
#include "manager/manager_observer.h"
#include "manager/throne_tracker.h"

bool ksu_module_mounted __read_mostly = false;
bool ksu_boot_completed __read_mostly = false;

void on_post_fs_data(void)
{
    static bool done = false;

    if (done) {
        pr_info("on_post_fs_data already done\n");
        return;
    }

    done = true;
    pr_info("on_post_fs_data!\n");

    ksu_load_allow_list();
    ksu_observer_init();
    // Sanity check for safe mode only needs early-boot input samples.
    ksu_stop_input_hook_runtime();
    ksu_selinux_hide_handle_post_fs_data();
    // retry forced features which failed at second stage
    ksu_feature_apply_forced();
}

extern void ext4_unregister_sysfs(struct super_block *sb);

int nuke_ext4_sysfs(const char *mnt)
{
    struct path path;
    int err = kern_path(mnt, 0, &path);

    if (err) {
        pr_err("nuke path err: %d\n", err);
        return err;
    }

    if (strcmp(path.dentry->d_inode->i_sb->s_type->name, "ext4") != 0) {
        pr_info("nuke but module aren't mounted\n");
        path_put(&path);
        return -EINVAL;
    }

    ext4_unregister_sysfs(path.dentry->d_inode->i_sb);
    path_put(&path);
    return 0;
}

void on_module_mounted(void)
{
    pr_info("on_module_mounted!\n");
    ksu_module_mounted = true;
}

void on_boot_completed(void)
{
    ksu_boot_completed = true;
    pr_info("on_boot_completed!\n");
    // prune_only=false：开机完成是"管理器身份"最后一次可靠的自我修复机会。
    //
    // 之前这里是 true（只裁剪 allowlist、不重新扫管理器），于是管理器只在 LKM 被
    // 载入的那一刻被扫一次；那一次如果因为 /data 还没起全、或管理器 base.apk 那一刻
    // 读不到而验签失败，`ksu_invalidate_manager_uid()` 就一直生效 —— `is_manager()`
    // 为假，管理器的特权 supercall 被 perm.c 拒掉，界面看起来就是"root 没了"，
    // 要等下一次安装/卸载应用触发 pkg_observer 或者重启碰运气才好。
    //
    // 重扫是廉价的：pkg 未变时 track_throne() 自己在 316-327 行判定 need_rescan=false，
    // 只做一次裁剪就返回。这里不引入新的定时器，只在已有的开机完成事件上补这一次。
    track_throne(false);
    ksu_selinux_hide_drop_backup_if_unused();
}
