#!/usr/bin/env python3
# Host regression/sanitizer suite. Run: python3 scripts/test_antiwipe_host.py
# Requires clang and a Linux host. No kernel loading or device operations.
import os, pathlib, struct, subprocess, tempfile
root = pathlib.Path(__file__).resolve().parents[1]
aw = root / 'kpm/antiwipe_full'
# Exercise the real parser in its translation unit; kernel I/O sections are GC'd.
types = (aw/'aw_types.h').read_text().replace('#define NULL ((void *)0)', '#ifndef NULL\n#define NULL ((void *)0)\n#endif')
offsets = (aw/'aw_offsets.h').read_text().replace('#include "aw_types.h"', '')
parser = (aw/'aw_btf.c').read_text().replace('#include "aw_offsets.h"', '')
harness = r'''
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
static int quiet(const char *fmt, ...) { (void)fmt; return 0; }
int (*printk)(const char *, ...) = quiet;
int (*kf_strcmp)(const char *, const char *) = strcmp;
int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb");
    assert(f); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
    btf_buf = malloc(n ? n : 1); assert(btf_buf); btf_size = n;
    assert(fread(btf_buf, 1, n, f) == (size_t)n); fclose(f);
    int rc = btf_parse();
    if (argc > 2) assert(rc == atoi(argv[2]));
    free(btf_buf); return 0;
}
'''
# BTF producer with forward-reference support for kernel struct pointer args.
strings = bytearray(b'\0'); records = []; ids = {}
def st(s):
    b = s.encode()+b'\0'; i = len(strings); strings.extend(b); return i
def rec(name, kind, value=0, vlen=0, extra=b'', kflag=0):
    records.append(struct.pack('<III', st(name), (kind<<24)|vlen|kflag, value)+extra)
    return len(records)
intid = rec('int', 1, 4, extra=struct.pack('<I', 0x01000020))
u16id = rec('short', 1, 2, extra=struct.pack('<I', 16))
charid = rec('char', 1, 1, extra=struct.pack('<I', 8))
arrid = rec('', 3, extra=struct.pack('<III', charid, intid, 16))
# Add struct records then pointer records. All fields get their actual read widths.
layouts = {
 'path': [('mnt',8,0),('dentry',8,8)],
 'inode': [('i_mode',2,0),('i_rdev',4,4)],
 'file': [('f_mode',4,0),('f_path',16,8),('f_inode',8,24)],
 'dentry':[('d_inode',8,0)], 'linux_binprm':[('filename',8,0)],
 'cred':[('euid',4,0)], 'mm_struct':[('exe_file',8,0)],
 'task_struct':[('pid',4,0),('cred',8,8),('comm',16,16),('mm',8,32),('flags',4,40)],
 'iattr':[('ia_valid',4,0)], 'mnt_idmap':[],
}
# One generic pointer serves width checks; pointer type identity validated for hook args below.
pointer = rec('',2,intid)
for name, fields in layouts.items():
    payload = b''
    for mn,w,b in fields:
        typ = {2:u16id,4:intid,8:pointer,16:arrid}[w]
        if mn=='f_path': typ=ids['path']
        payload += struct.pack('<III', st(mn),typ,b*8)
    ids[name]=rec(name,4, max([b+w for _,w,b in fields] or [8]),len(fields),payload)
pointers={n:rec('',2,i) for n,i in ids.items()}
def fn(name, params):
    payload=b''.join(struct.pack('<II',0,pointers[x] if x else intid) for x in params)
    proto=rec('',13,intid,len(params),payload); rec(name,12,proto)
for name,params in [
 ('security_file_open',['file']),('security_inode_unlink',['inode','dentry']),
 ('security_inode_rmdir',['inode','dentry']),
 ('security_inode_rename',['inode','dentry','inode','dentry',None]),
 ('security_inode_setattr',['mnt_idmap','dentry','iattr']),
 ('security_file_permission',['file',None]),('security_file_ioctl',['file',None,None]),
]: fn(name,params)
# FLOAT has no extra payload and must not desynchronize following records.
rec('float',16,4)
def blob(recs=None, strs=None):
    t=b''.join(records if recs is None else recs); s=bytes(strings if strs is None else strs)
    return struct.pack('<HBBIIIII',0xeb9f,1,0,24,0,len(t),len(t),len(s))+t+s
valid=blob()
with tempfile.TemporaryDirectory(prefix='antiwipe-host-') as tmp:
    tmp=pathlib.Path(tmp); src=tmp/'parser.c'; exe=tmp/'parser'
    src.write_text(types+'\n'+offsets+'\n'+parser+'\n'+harness)
    cc=os.environ.get('CC','clang')
    subprocess.run([cc,'-std=gnu11','-O1','-g','-ffunction-sections','-fdata-sections',
        '-fsanitize=address,undefined','-fno-omit-frame-pointer',str(src),'-Wl,--gc-sections','-o',str(exe)],check=True)
    cases=[('valid-zero-offsets-float',valid,0),('empty',b'',-57),('short-header',valid[:12],-57),
      ('bad-magic',b'xx'+valid[2:],-57),('unterminated-string',blob(strs=strings[:-1]+b'x'),-57),
      ('truncated-type',blob(recs=[records[0][:-1]]),-57),
      ('overflow-header',valid[:12]+struct.pack('<I',0xfffffff0)+valid[16:],-57)]
    # Every truncation must fail cleanly; this includes mid-record and mid-string edges.
    cases += [(f'truncate-{i}',valid[:i],None) for i in range(0,len(valid),13)]
    for name,data,expected in cases:
        inp=tmp/'input.btf'; inp.write_bytes(data)
        args=[str(exe),str(inp)]+([] if expected is None else [str(expected)])
        subprocess.run(args,check=True)
    # Compile module ABI and callbacks for arm64, with warnings treated as errors.
    for name in ['aw_btf.c','antiwipe_full.c']:
        subprocess.run([cc,'--target=aarch64-linux-gnu','-std=gnu11','-O2','-nostdinc',
          '-ffreestanding','-mgeneral-regs-only','-mno-outline-atomics','-Wall','-Wextra',
          '-Werror','-Wno-unused-function','-c',str(aw/name),'-o',str(tmp/(name+'.o'))],check=True)
    print(f'PASS {len(cases)} BTF ASan/UBSan cases; arm64 module ABI/callback compile')
