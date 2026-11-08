#ifndef __KSU_H_MANAGER_OBSERVER
#define __KSU_H_MANAGER_OBSERVER

#ifdef CONFIG_KSU_DISABLE_MANAGER
static inline int ksu_observer_init(void)
{
    return 0;
}

static inline void ksu_observer_exit(void)
{
}

static inline void ksu_maybe_rescan_manager(void)
{
}
#else
int ksu_observer_init(void);
void ksu_observer_exit(void);
/*
 * 管理器身份没认出来时排一次延迟重扫；身份正常时是空转。
 * 由 GET_INFO 这个 always_allow 的 supercall 调用，节流在实现里。
 */
void ksu_maybe_rescan_manager(void);
#endif

#endif // __KSU_H_MANAGER_OBSERVER
