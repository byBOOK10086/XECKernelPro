/* SPDX-License-Identifier: GPL-2.0 */
#include <assert.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>

static atomic_bool valid;
static atomic_uint scans, lock_calls;
static atomic_bool start;
static unsigned long jiffies;
#define DEFINE_SPINLOCK(name) pthread_mutex_t name = PTHREAD_MUTEX_INITIALIZER
#define READ_ONCE(value) __atomic_load_n(&(value), __ATOMIC_RELAXED)
#define WRITE_ONCE(value, next) __atomic_store_n(&(value), (next), __ATOMIC_RELAXED)
#define likely(value) (value)
#define msecs_to_jiffies(value) ((unsigned long)(value))
#define time_before(a, b) ((long)((a) - (b)) < 0)
#define spin_lock_irqsave(lock, flags) do { \
    (flags) = 0; atomic_fetch_add(&lock_calls, 1); pthread_mutex_lock(lock); \
} while (0)
#define spin_unlock_irqrestore(lock, flags) do { (void)(flags); pthread_mutex_unlock(lock); } while (0)
static void pr_info(const char *format, ...) { }
static bool ksu_is_manager_appid_valid(void) { return atomic_load(&valid); }
static void ksu_defer_track_throne(void) { atomic_fetch_add(&scans, 1); }

/* KERNEL_FUNCTIONS */

static void reset(void)
{
    atomic_store(&valid, true);
    ksu_maybe_rescan_manager();
    atomic_store(&valid, false);
    atomic_store(&scans, 0);
}
static void *worker(void *arg)
{
    while (!atomic_load(&start)) { }
    for (int i = 0; i < 100; i++) ksu_maybe_rescan_manager();
    return NULL;
}
int main(void)
{
    reset();
    jiffies = 0;
    ksu_maybe_rescan_manager();
    ksu_maybe_rescan_manager();
    assert(scans == 1); /* zero jiffies is a real timestamp, not 'never tried'. */
    jiffies = 9999;
    ksu_maybe_rescan_manager();
    assert(scans == 1);
    jiffies = 10000;
    ksu_maybe_rescan_manager();
    assert(scans == 2);
    jiffies = 29999;
    ksu_maybe_rescan_manager();
    assert(scans == 2);
    jiffies = 30000;
    ksu_maybe_rescan_manager();
    assert(scans == 3);

    atomic_store(&valid, true);
    ksu_maybe_rescan_manager();
    atomic_store(&lock_calls, 0);
    for (int i = 0; i < 100; i++) ksu_maybe_rescan_manager();
    assert(lock_calls == 0);
    atomic_store(&valid, false);
    ksu_maybe_rescan_manager();
    assert(scans == 4); /* an identity loss after recovery starts a fresh attempt. */

    reset();
    jiffies = ULONG_MAX - 5;
    ksu_maybe_rescan_manager();
    jiffies = 0;
    ksu_maybe_rescan_manager();
    assert(scans == 1);
    jiffies = 9994;
    ksu_maybe_rescan_manager();
    assert(scans == 2);

    reset();
    jiffies = 1;
    pthread_t threads[24];
    for (int i = 0; i < 24; i++) assert(pthread_create(&threads[i], NULL, worker, NULL) == 0);
    atomic_store(&start, true);
    for (int i = 0; i < 24; i++) assert(pthread_join(threads[i], NULL) == 0);
    assert(scans == 1);
    puts("Manager backoff, wraparound, steady-state fast path and concurrent queries passed");
    return 0;
}
