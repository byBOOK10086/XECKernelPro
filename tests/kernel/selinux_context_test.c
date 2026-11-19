/* SPDX-License-Identifier: GPL-2.0 */
/* Exercise the production converters with distinct backup/live symbol IDs. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef ESTALE
#define ESTALE 116 /* Linux errno; not supplied by the Windows host CRT. */
#endif

typedef uint32_t u32;
typedef int gfp_t;
#define KSU_SUSFS_LINKAGE static
#define SECSID_NULL 0
struct user_datum { u32 value; };
struct role_datum { u32 value; };
struct type_datum { u32 value; bool attribute; };
struct symtab { const char *name; void *datum; };
struct policydb {
    struct symtab p_users, p_roles, p_types;
    struct user_datum user;
    struct role_datum role;
    struct type_datum type;
    int mls_error;
};
struct context { u32 user, role, type; void *mls; };
struct selinux_policy;
struct sidtab {
    int calls, error_once;
    u32 last_type;
    struct selinux_policy *replacement;
};
struct selinux_policy { struct policydb policydb; struct sidtab *sidtab; };
static struct { struct selinux_policy *policy; } selinux_state;
static int contexts, buffers, rcu_depth;

static void *symtab_search(struct symtab *table, const char *name)
{
    return table->datum && strcmp(table->name, name) == 0 ? table->datum : NULL;
}
static void context_init(struct context *context) { memset(context, 0, sizeof(*context)); }
static void context_destroy(struct context *context)
{
    if (context->mls) { free(context->mls); context->mls = NULL; contexts--; }
}
static int mls_context_to_sid(struct policydb *policy, char delimiter, char *suffix,
                             struct context *context, struct sidtab *sidtab, u32 def_sid)
{
    context->mls = malloc(1);
    assert(context->mls);
    contexts++;
    if (!delimiter || strcmp(suffix, "s0:c0") != 0)
        return -EINVAL;
    /* MLS parsing mutates the suffix; the live parse must receive it restored. */
    *strchr(suffix, ':') = '\0';
    return policy->mls_error;
}
static bool policydb_context_isvalid(struct policydb *policy, struct context *context)
{
    return context->user == policy->user.value && context->role == policy->role.value &&
           context->type == policy->type.value;
}
static int sidtab_context_to_sid(struct sidtab *table, struct context *context, u32 *sid)
{
    assert(rcu_depth == 1);
    table->calls++;
    table->last_type = context->type;
    if (table->error_once) {
        int error = table->error_once;
        table->error_once = 0;
        if (table->replacement) selinux_state.policy = table->replacement;
        return error;
    }
    *sid = context->type * 10;
    return 0;
}
static void *kmemdup_nul(const char *source, u32 length, gfp_t flags)
{
    char *copy = malloc((size_t)length + 1);
    assert(copy);
    memcpy(copy, source, length);
    copy[length] = '\0';
    buffers++;
    return copy;
}
static void kfree(void *buffer) { if (buffer) { free(buffer); buffers--; } }
static void rcu_read_lock(void) { assert(rcu_depth++ == 0); }
static void rcu_read_unlock(void) { assert(--rcu_depth == 0); }
#define rcu_dereference(pointer) (pointer)

/* KERNEL_FUNCTIONS */

static struct selinux_policy backup, live, reloaded;
static struct sidtab backup_sids, live_sids, reloaded_sids;
static void init_policy(struct selinux_policy *policy, struct sidtab *table, u32 offset)
{
    memset(policy, 0, sizeof(*policy));
    memset(table, 0, sizeof(*table));
    struct policydb *db = &policy->policydb;
    db->user.value = offset + 1;
    db->role.value = offset + 2;
    db->type.value = offset + 3;
    db->p_users = (struct symtab){"user", &db->user};
    db->p_roles = (struct symtab){"role", &db->role};
    db->p_types = (struct symtab){"type", &db->type};
    policy->sidtab = table;
}
static void reset(void)
{
    assert(contexts == 0 && buffers == 0 && rcu_depth == 0);
    init_policy(&backup, &backup_sids, 0);
    init_policy(&live, &live_sids, 100);
    init_policy(&reloaded, &reloaded_sids, 200);
    selinux_state.policy = &live;
}
static void convert(const char *input, int expected, int expected_live, u32 expected_sid, u32 expected_live_sid)
{
    u32 sid = 999, live_sid = 999;
    int live_error = 999;
    char *original = strdup(input);
    assert(original);
    int result = security_context_to_sid_with_policy(&backup, input, (u32)strlen(input),
                                                     &sid, 0, 0, &live_sid, &live_error);
    assert(result == expected && live_error == expected_live);
    assert(sid == expected_sid && live_sid == expected_live_sid);
    assert(strcmp(input, original) == 0);
    free(original);
    assert(contexts == 0 && buffers == 0 && rcu_depth == 0);
}
int main(void)
{
    const char *input = "user:role:type:s0:c0";
    reset();
    convert(input, 0, 0, 30, 1030);
    assert(backup_sids.calls == 1 && live_sids.calls == 1);
    assert(live_sids.last_type == 103);

    reset();
    convert("user:role:missing:s0:c0", -EINVAL, -EINVAL, 0, 0);
    assert(backup_sids.calls == 0 && live_sids.calls == 0);

    reset();
    live.policydb.p_users.datum = NULL;
    convert(input, 0, -EINVAL, 30, 0);
    assert(live_sids.calls == 0);

    reset();
    live.policydb.type.attribute = true;
    convert(input, 0, -EINVAL, 30, 0);
    assert(live_sids.calls == 0);

    reset();
    live.policydb.mls_error = -EINVAL;
    convert(input, 0, -EINVAL, 30, 0);
    assert(live_sids.calls == 0);

    reset();
    backup.policydb.mls_error = -ENOMEM;
    convert(input, -ENOMEM, -EINVAL, 0, 0);
    assert(backup_sids.calls == 0 && live_sids.calls == 0);

    reset();
    backup_sids.error_once = -ENOMEM;
    convert(input, -ENOMEM, -ENOMEM, 0, 0);
    assert(live_sids.calls == 0);

    reset();
    live_sids.error_once = -ESTALE;
    live_sids.replacement = &reloaded;
    convert(input, 0, 0, 30, 2030);
    assert(backup_sids.calls == 2 && live_sids.calls == 1 && reloaded_sids.calls == 1);

    reset();
    convert("not-a-context", -EINVAL, -EINVAL, 0, 0);
    reset();
    convert("user:role:type:bad", -EINVAL, -EINVAL, 0, 0);
    puts("10 SELinux conversion scenarios passed");
    return 0;
}
