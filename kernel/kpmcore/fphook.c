/* SPDX-License-Identifier: GPL-2.0-or-later */
/* 
 * Copyright (C) 2023 bmax121. All Rights Reserved.
 */

#include "include/hook.h"
#include <asm/cacheflush.h>
#include "include/symbol.h"
#include "include/pgtable.h"
#include "include/hotpatch.h"

/* LKM backend (hook_runtime.c). */
extern void flush_icache_all(void);
#include "hmem.h"

// transit0
typedef uint64_t (*transit0_func_t)();

#define current_fp_hook_chain() ({ \
    uint64_t chain_va; \
    asm volatile("mov %0, x16" : "=r"(chain_va)); \
    (fp_hook_chain_t *)chain_va; \
})

uint64_t __attribute__((section(".fp.transit0.text"))) __attribute__((__noinline__)) _fp_transit0()
{
    fp_hook_chain_t *hook_chain = current_fp_hook_chain();
    if (!hook_chain) return 0;
    hook_fargs0_t fargs;
    fargs.skip_origin = 0;
    fargs.chain = hook_chain;
    for (int32_t i = 0; i < hook_chain->chain_items_max; i++) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain0_callback func = hook_chain->befores[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    if (!fargs.skip_origin) {
        transit0_func_t origin_func = (transit0_func_t)hook_chain->hook.origin_fp;
        fargs.ret = origin_func();
    }
    for (int32_t i = hook_chain->chain_items_max - 1; i >= 0; i--) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain0_callback func = hook_chain->afters[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    return fargs.ret;
}

// transit4
typedef uint64_t (*transit4_func_t)(uint64_t, uint64_t, uint64_t, uint64_t);

uint64_t __attribute__((section(".fp.transit4.text"))) __attribute__((__noinline__))
_fp_transit4(uint64_t arg0, uint64_t arg1, uint64_t arg2, uint64_t arg3)
{
    fp_hook_chain_t *hook_chain = current_fp_hook_chain();
    if (!hook_chain) return 0;
    hook_fargs4_t fargs;
    fargs.skip_origin = 0;
    fargs.arg0 = arg0;
    fargs.arg1 = arg1;
    fargs.arg2 = arg2;
    fargs.arg3 = arg3;
    fargs.chain = hook_chain;
    for (int32_t i = 0; i < hook_chain->chain_items_max; i++) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain4_callback func = hook_chain->befores[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    if (!fargs.skip_origin) {
        transit4_func_t origin_func = (transit4_func_t)hook_chain->hook.origin_fp;
        fargs.ret = origin_func(fargs.arg0, fargs.arg1, fargs.arg2, fargs.arg3);
    }
    for (int32_t i = hook_chain->chain_items_max - 1; i >= 0; i--) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain4_callback func = hook_chain->afters[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    return fargs.ret;
}


// transit8:
typedef uint64_t (*transit8_func_t)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

uint64_t __attribute__((section(".fp.transit8.text"))) __attribute__((__noinline__))
_fp_transit8(uint64_t arg0, uint64_t arg1, uint64_t arg2, uint64_t arg3, uint64_t arg4, uint64_t arg5, uint64_t arg6,
             uint64_t arg7)
{
    fp_hook_chain_t *hook_chain = current_fp_hook_chain();
    if (!hook_chain) return 0;
    hook_fargs8_t fargs;
    fargs.skip_origin = 0;
    fargs.arg0 = arg0;
    fargs.arg1 = arg1;
    fargs.arg2 = arg2;
    fargs.arg3 = arg3;
    fargs.arg4 = arg4;
    fargs.arg5 = arg5;
    fargs.arg6 = arg6;
    fargs.arg7 = arg7;
    fargs.chain = hook_chain;
    for (int32_t i = 0; i < hook_chain->chain_items_max; i++) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain8_callback func = hook_chain->befores[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    if (!fargs.skip_origin) {
        transit8_func_t origin_func = (transit8_func_t)hook_chain->hook.origin_fp;
        fargs.ret =
            origin_func(fargs.arg0, fargs.arg1, fargs.arg2, fargs.arg3, fargs.arg4, fargs.arg5, fargs.arg6, fargs.arg7);
    }
    for (int32_t i = hook_chain->chain_items_max - 1; i >= 0; i--) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain8_callback func = hook_chain->afters[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    return fargs.ret;
}


// transit12:
typedef uint64_t (*transit12_func_t)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
                                     uint64_t, uint64_t, uint64_t, uint64_t);

uint64_t __attribute__((section(".fp.transit12.text"))) __attribute__((__noinline__))
_fp_transit12(uint64_t arg0, uint64_t arg1, uint64_t arg2, uint64_t arg3, uint64_t arg4, uint64_t arg5, uint64_t arg6,
              uint64_t arg7, uint64_t arg8, uint64_t arg9, uint64_t arg10, uint64_t arg11)
{
    fp_hook_chain_t *hook_chain = current_fp_hook_chain();
    if (!hook_chain) return 0;
    hook_fargs12_t fargs;
    fargs.skip_origin = 0;
    fargs.arg0 = arg0;
    fargs.arg1 = arg1;
    fargs.arg2 = arg2;
    fargs.arg3 = arg3;
    fargs.arg4 = arg4;
    fargs.arg5 = arg5;
    fargs.arg6 = arg6;
    fargs.arg7 = arg7;
    fargs.arg8 = arg8;
    fargs.arg9 = arg9;
    fargs.arg10 = arg10;
    fargs.arg11 = arg11;
    fargs.chain = hook_chain;
    for (int32_t i = 0; i < hook_chain->chain_items_max; i++) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain12_callback func = hook_chain->befores[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    if (!fargs.skip_origin) {
        transit12_func_t origin_func = (transit12_func_t)hook_chain->hook.origin_fp;
        fargs.ret = origin_func(fargs.arg0, fargs.arg1, fargs.arg2, fargs.arg3, fargs.arg4, fargs.arg5, fargs.arg6,
                                fargs.arg7, fargs.arg8, fargs.arg9, fargs.arg10, fargs.arg11);
    }
    for (int32_t i = hook_chain->chain_items_max - 1; i >= 0; i--) {
        if (hook_chain->states[i] != CHAIN_ITEM_STATE_READY) continue;
        hook_chain12_callback func = hook_chain->afters[i];
        if (func) func(&fargs, hook_chain->udata[i]);
    }
    return fargs.ret;
}


/*
 * LKM adaptation (mirrors hook_engine.c): kpimg's linker provides
 * _fp_transitN_end markers to size the transit copy; Linux modules have no
 * such markers, so emit the 6-instruction header and an absolute branch to
 * the resident _fp_transitN function instead.
 */
static hook_err_t hook_chain_prepare(uint32_t *transit, int32_t argno)
{
    uint64_t target;
    fp_hook_chain_t *chain = local_container_of(transit, fp_hook_chain_t, transit);

    if (argno < 0 || argno > 12) return -HOOK_BAD_ADDRESS;
    if (argno == 0)
        target = (uint64_t)_fp_transit0;
    else if (argno <= 4)
        target = (uint64_t)_fp_transit4;
    else if (argno <= 8)
        target = (uint64_t)_fp_transit8;
    else
        target = (uint64_t)_fp_transit12;

    transit[0] = ARM64_BTI_JC;
    transit[1] = 0x58000070; /* LDR X16, #12 */
    transit[2] = 0x14000004; /* B #16 */
    transit[3] = ARM64_NOP;
    transit[4] = ((uint64_t)chain) & 0xFFFFFFFF;
    transit[5] = ((uint64_t)chain) >> 32u;
    return branch_absolute(&transit[6], target) ? HOOK_NO_ERR : -HOOK_TRANSIT_NO_MEM;
}

void fp_hook(uintptr_t fp_addr, void *replace, void **backup)
{
    *(uintptr_t *)backup = *(uintptr_t *)fp_addr;
    uintptr_t addrs[2];
    addrs[0] = fp_addr;
    addrs[1] = fp_addr + 4;
    hotpatch((void **)addrs, (uint32_t *)&replace, 2);
}
KP_EXPORT_SYMBOL(fp_hook);

void fp_unhook(uintptr_t fp_addr, void *backup)
{
    uintptr_t addrs[2];
    addrs[0] = fp_addr;
    addrs[1] = fp_addr + 4;
    hotpatch((void **)addrs, (uint32_t *)&backup, 2);
}
KP_EXPORT_SYMBOL(fp_unhook);

hook_err_t fp_hook_wrap(uintptr_t fp_addr, int32_t argno, void *before, void *after, void *udata)
{
    hook_err_t err = HOOK_NO_ERR;
    if (is_bad_address((void *)fp_addr)) return -HOOK_BAD_ADDRESS;
    fp_hook_chain_t *chain = hook_get_mem_from_origin(fp_addr);
    if (!chain) {
        chain = (fp_hook_chain_t *)hook_mem_zalloc(fp_addr, FUNCTION_POINTER_CHAIN);
        if (!chain) return -HOOK_NO_MEM;
        chain->hook.fp_addr = fp_addr;
        chain->hook.replace_addr = (uint64_t)chain->transit;
        err = hook_chain_prepare(chain->transit, argno);
        if (err) return err;
        flush_icache_all();
        fp_hook(chain->hook.fp_addr, (void *)chain->hook.replace_addr, (void **)&chain->hook.origin_fp);
    }

    for (int i = 0; i < FP_HOOK_CHAIN_NUM; i++) {
        if ((before && chain->befores[i] == before) || (after && chain->afters[i] == after)) return -HOOK_DUPLICATED;

        // todo: atomic or lock
        if (chain->states[i] == CHAIN_ITEM_STATE_EMPTY) {
            chain->states[i] = CHAIN_ITEM_STATE_BUSY;
            dsb(ish);
            chain->udata[i] = udata;
            chain->befores[i] = before;
            chain->afters[i] = after;
            if (i + 1 > chain->chain_items_max) {
                chain->chain_items_max = i + 1;
            }
            dsb(ish);
            chain->states[i] = CHAIN_ITEM_STATE_READY;
            logkv("Wrap func pointer add: %llx, %llx, %llx successed\n", chain->hook.fp_addr, before, after);
            return HOOK_NO_ERR;
        }
    }
    logkv("Wrap func pointer add: %llx, %llx, %llx failed\n", chain->hook.fp_addr, before, after);
    return -HOOK_CHAIN_FULL;
}
KP_EXPORT_SYMBOL(fp_hook_wrap);

void fp_hook_unwrap(uintptr_t fp_addr, void *before, void *after)
{
    if (is_bad_address((void *)fp_addr)) return;
    fp_hook_chain_t *chain = (fp_hook_chain_t *)hook_get_mem_from_origin(fp_addr);
    if (!chain) return;
    for (int i = 0; i < FP_HOOK_CHAIN_NUM; i++) {
        if (chain->states[i] == CHAIN_ITEM_STATE_READY)
            if ((before && chain->befores[i] == before) || (after && chain->afters[i] == after)) {
                chain->states[i] = CHAIN_ITEM_STATE_BUSY;
                dsb(ish);
                chain->udata[i] = 0;
                chain->befores[i] = 0;
                chain->afters[i] = 0;
                dsb(ish);
                chain->states[i] = CHAIN_ITEM_STATE_EMPTY;
                break;
            }
    }
    logkv("Wrap func pointer remove: %llx, %llx, %llx\n", chain->hook.fp_addr, before, after);

    for (int i = 0; i < FP_HOOK_CHAIN_NUM; i++) {
        if (chain->states[i] != CHAIN_ITEM_STATE_EMPTY) return;
    }
    fp_unhook(chain->hook.fp_addr, (void *)chain->hook.origin_fp);
    // todo: unsafe
    hook_mem_free(chain);
    logkv("Unwrap func pointer: %llx, %llx, %llx\n", fp_addr, before, after);
}
KP_EXPORT_SYMBOL(fp_hook_unwrap);
