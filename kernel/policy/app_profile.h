#ifndef __KSU_H_APP_PROFILE
#define __KSU_H_APP_PROFILE

#include "uapi/app_profile.h"
#include "linux/init.h"

#define TIF_KSU_DISABLE_ESCAPE_WITH_ROOT 63

// Escalate current process to root with the appropriate profile
int escape_with_root_profile(void);

void escape_to_root_for_init(void);

#ifdef CONFIG_KSU_SUSFS
// setuid_hook 的 SUSFS 分支（zygote fork 出的 manager/允许应用）直接调用
void disable_seccomp(void);
#endif

void __init ksu_app_profile_init(void);

#endif
