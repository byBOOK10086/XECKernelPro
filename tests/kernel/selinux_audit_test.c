/* Host regression: the real app audit-rule selector and dontaudit wrapper. */
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define ALL NULL
#define ARRAY_SIZE(array) (sizeof(array) / sizeof((array)[0]))
#define KERNEL_SU_FILE "xecpro_file"
#define AVTAB_AUDITDENY 4
#define SOURCES 5
#define TARGETS 6
#define READ_ONCE(value) (value)
#define WRITE_ONCE(target, value) ((target) = (value))

typedef uint32_t u32;

static const char * const source_names[SOURCES] = {
    "untrusted_app_all", "isolated_app_all", "sdk_sandbox_all", "system_server", "shell",
};
static const char * const target_names[TARGETS] = {
    "domain", "selinuxfs", KERNEL_SU_FILE, "adb_data_file", "binderfs_logs", "ksu_file",
};

struct type_datum {
    const char *name;
    bool present;
};
struct symtab {
    struct type_datum entries[SOURCES + TARGETS];
    u32 nprim;
};
struct policydb {
    struct symtab p_types;
    uint32_t allowed[SOURCES][TARGETS];
    uint32_t auditdeny[SOURCES][TARGETS];
    uint32_t auditallow[SOURCES][TARGETS];
    bool permissive[SOURCES];
    unsigned int calls[SOURCES][TARGETS];
    unsigned int attempts;
    unsigned int fail_at;
};

static const struct policydb *ksu_original_types_policy;
static u32 ksu_original_types_limit;
static u32 ksu_original_types_generation;

static struct type_datum *symtab_search(struct symtab *table, const char *name)
{
    for (size_t i = 0; i < ARRAY_SIZE(table->entries); i++) {
        if (table->entries[i].present && !strcmp(table->entries[i].name, name))
            return &table->entries[i];
    }
    return NULL;
}

static size_t index_of(const char *name, const char * const *names, size_t count)
{
    for (size_t i = 0; i < count; i++) {
        if (!strcmp(name, names[i]))
            return i;
    }
    assert(!"unexpected policy symbol");
    return count;
}

static bool add_rule(struct policydb *db, const char *source, const char *target,
                     const char *cls, const char *perm, int effect, bool invert)
{
    /* The actual ksu_dontaudit wrapper must select only inverted AUDITDENY. */
    assert(effect == AVTAB_AUDITDENY && invert && cls == NULL && perm == NULL);
    assert(symtab_search(&db->p_types, source) && symtab_search(&db->p_types, target));
    size_t s = index_of(source, source_names, SOURCES);
    size_t t = index_of(target, target_names, TARGETS);
    db->attempts++;
    if (db->fail_at && db->attempts == db->fail_at)
        return false;
    db->calls[s][t]++;
    db->auditdeny[s][t] = 0;
    return true;
}

/* KERNEL_FUNCTIONS */

static void reset(struct policydb *db)
{
    memset(db, 0, sizeof(*db));
    for (size_t s = 0; s < SOURCES; s++) {
        db->p_types.entries[s] = (struct type_datum){source_names[s], true};
        for (size_t t = 0; t < TARGETS; t++) {
            db->allowed[s][t] = 0x13579bdfU;
            db->auditdeny[s][t] = UINT32_MAX;
            db->auditallow[s][t] = 0x2468ace0U;
        }
    }
    for (size_t t = 0; t < TARGETS; t++)
        db->p_types.entries[SOURCES + t] = (struct type_datum){target_names[t], true};
}

static void check_unaffected(const struct policydb *db)
{
    for (size_t s = 0; s < SOURCES; s++) {
        assert(!db->permissive[s]);
        for (size_t t = 0; t < TARGETS; t++) {
            assert(db->allowed[s][t] == 0x13579bdfU);
            assert(db->auditallow[s][t] == 0x2468ace0U);
            if (s >= 3 || t >= 5) {
                assert(db->auditdeny[s][t] == UINT32_MAX);
                assert(db->calls[s][t] == 0);
            }
        }
    }
}

static void check_original_types_after_appending(void)
{
    struct policydb original = {0};
    struct policydb expanded = {0};

    original.p_types.nprim = 100;
    /* The clone already contains newly appended KERNEL_SU_DOMAIN/FILE. */
    expanded.p_types.nprim = original.p_types.nprim + 2;
    assert(ksu_set_original_types(&expanded, &original, 42));
    assert(ksu_original_types_limit == original.p_types.nprim);
    for (u32 type = 1; type <= original.p_types.nprim; type++)
        assert(ksu_type_is_original(&expanded, 42, type) == 1);
    assert(ksu_type_is_original(&expanded, 42, 101) == 0);
    assert(ksu_type_is_original(&expanded, 42, 102) == 0);
    assert(ksu_type_is_original(&expanded, 41, 1) == -1);
    assert(ksu_type_is_original(&original, 42, 1) == -1);
    assert(original.p_types.nprim == 100 && expanded.p_types.nprim == 102);
}

int main(void)
{
    struct policydb db;
    check_original_types_after_appending();
    reset(&db);
    assert(apply_app_audit_rules(&db));
    assert(db.attempts == 15);
    for (size_t s = 0; s < 3; s++) {
        for (size_t t = 0; t < 5; t++) {
            assert(db.calls[s][t] == 1 && db.auditdeny[s][t] == 0);
        }
    }
    check_unaffected(&db);

    /* Older Android policies may lack each app attribute independently. */
    for (size_t missing = 0; missing < 3; missing++) {
        reset(&db);
        db.p_types.entries[missing].present = false;
        assert(apply_app_audit_rules(&db));
        assert(db.attempts == 10);
        assert(!db.p_types.entries[missing].present);
        for (size_t t = 0; t < 5; t++) {
            assert(db.calls[missing][t] == 0);
            assert(db.auditdeny[missing][t] == UINT32_MAX);
        }
        check_unaffected(&db);
    }

    /* File types and the domain attribute are optional, never synthesized. */
    for (size_t missing = 0; missing < 5; missing++) {
        reset(&db);
        db.p_types.entries[SOURCES + missing].present = false;
        assert(apply_app_audit_rules(&db));
        assert(db.attempts == 12);
        assert(!db.p_types.entries[SOURCES + missing].present);
        for (size_t s = 0; s < 3; s++) {
            assert(db.calls[s][missing] == 0);
            assert(db.auditdeny[s][missing] == UINT32_MAX);
        }
        check_unaffected(&db);
    }

    reset(&db);
    for (size_t s = 0; s < 3; s++)
        db.p_types.entries[s].present = false;
    assert(apply_app_audit_rules(&db) && db.attempts == 0);
    check_unaffected(&db);

    reset(&db);
    for (size_t t = 0; t < 5; t++)
        db.p_types.entries[SOURCES + t].present = false;
    assert(apply_app_audit_rules(&db) && db.attempts == 0);
    check_unaffected(&db);

    /* A failed insertion must be reported so the unpublished clone is dropped. */
    reset(&db);
    db.fail_at = 7;
    assert(!apply_app_audit_rules(&db) && db.attempts == 7);
    check_unaffected(&db);

    puts("SELinux audit rules: 15 pairs, optional symbols, file mapping and access invariants passed");
    return 0;
}
