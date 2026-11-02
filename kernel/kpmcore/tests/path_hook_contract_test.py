"""Cloud host contract regression checks; native compile matrix remains required."""
import pathlib, re
root=pathlib.Path(__file__).resolve().parents[3]
adapter=(root/'kernel/kpmcore/path_hook.c').read_text()
symbols=(root/'kernel/kpmcore/symbols.c').read_text()
build=(root/'kernel/Kbuild').read_text()
loader=(root/'kernel/kpmcore/module.c').read_text()
for symbol in ('kpm_path_hook_abi_v1','kpm_path_hook_register_v1','kpm_path_hook_unregister_v1'):
    assert '{ "'+symbol+'", (unsigned long)'+symbol+' }' in symbols, symbol
assert 'kpmcore/path_hook.o' in build
assert 'char path[96]' in adapter
assert 'filename_arg != 1' in adapter
assert '"vfs_fstatat"' in adapter and '"do_faccessat"' in adapter
assert 'strncpy_from_user_nofault' in adapter
assert 'no_sanitize("cfi"), __noinline__' in adapter
remove=adapter[adapter.index('static void kp_path_remove'):adapter.index('void kpm_path_hook_unregister_v1')]
assert remove.index('unregister_kretprobe') < remove.index('wait_event') < remove.index('kfree')
assert 'synchronize_rcu()' in remove
unload=loader[loader.index('long kp_unload_module'):loader.index('long kp_load_module_path')]
assert unload.index('if (mod->legacy_hooks)') < unload.index('kp_call_exit')
assert unload.index('kp_path_hooks_drain_owner') < unload.index('kp_call_exit') < unload.index('kp_free_exec')
assert 'get_kretprobe(ri)' in adapter and 'ri->rp' in adapter
print('native path adapter export, argument, nofault, CFI and lifetime contract checks passed')
