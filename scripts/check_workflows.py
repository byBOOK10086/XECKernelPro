#!/usr/bin/env python3
"""守卫：每个 workflow 都必须能被 GitHub 解析成「有 name、有 jobs」的工作流。

为什么需要它：GitHub 对手写坏的 workflow 是**静默失败**的。2026-10-31 的
`8b491264` 在 `build-susfs-gki.yml` 里把 `printf '%s\\n'` 的反斜杠写成了真正的
换行，单引号字符串因此跨行、整个文件的 YAML 流被撕开。GitHub 的反应不是报错给
提交者，而是照样创建一个 run：名字退化成文件路径 `.github/workflows/xxx.yml`、
**jobs 数量为 0**、结论 failure。于是 GKI 构建整整 6 天（10-31 09:43Z →
11-06 发现）一发即死，期间所有推送都是「红」，但没人看到任何一行 shell 报错，
官网上继续挂着 10-26 的旧包。

单看 shell 是查不出来的：断行后 shell 里写的是
`printf '%s<换行>'`，这在 bash 里语法合法、语义也恰好等价（格式串里多一个字面
换行），`bash -n` 一样绿。唯一的判据是 YAML 层——所以这道守卫只做 YAML 与结构
断言，不重复 shellcheck 的活。

失败条件（任一）：
  * 文件不是合法 YAML（给出 line/column）；
  * 顶层缺 `name`，或 `on` 触发器缺失；
  * `jobs` 不存在/为空/不是映射；
  * 某个 job 缺 `runs-on` 或没有 `steps`，或 step 既没有 `run` 也没有 `uses`；
  * `run` 不是字符串（例如被误写成嵌套映射）。
"""
from __future__ import annotations

import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - CI 里由守卫步骤先 pip 装上
    print('FAIL: PyYAML is required (pip3 install pyyaml)', file=sys.stderr)
    raise SystemExit(1)


def check_file(path: pathlib.Path) -> list[str]:
    problems: list[str] = []
    try:
        doc = yaml.safe_load(path.read_text(encoding='utf-8'))
    except yaml.YAMLError as exc:
        # 把 mark 拆出来，让 CI 日志直接指到行号，不用人工二分。
        mark = getattr(exc, 'problem_mark', None)
        where = ' at line %d column %d' % (mark.line + 1, mark.column + 1) if mark else ''
        return ['invalid YAML%s: %s' % (where, str(exc).replace('\n', ' ')[:300])]

    if not isinstance(doc, dict):
        return ['top level is not a mapping (got %s)' % type(doc).__name__]

    if not isinstance(doc.get('name'), str) or not doc['name'].strip():
        problems.append('missing top-level "name"')

    # 注意 YAML 1.1：裸写的 `on:` 会被解析成布尔键 True，两种都要认。
    if 'on' not in doc and True not in doc:
        problems.append('missing "on" trigger block')

    jobs = doc.get('jobs')
    if not isinstance(jobs, dict) or not jobs:
        problems.append('"jobs" is missing or empty')
        return problems

    for job_id, job in jobs.items():
        prefix = 'job %s' % job_id
        if not isinstance(job, dict):
            problems.append('%s is not a mapping' % prefix)
            continue
        # 可复用工作流用 `uses:` 顶替 steps/runs-on。
        if isinstance(job.get('uses'), str):
            continue
        if 'runs-on' not in job:
            problems.append('%s has no "runs-on"' % prefix)
        steps = job.get('steps')
        if not isinstance(steps, list) or not steps:
            problems.append('%s has no steps' % prefix)
            continue
        for index, step in enumerate(steps):
            where = '%s step #%d' % (prefix, index + 1)
            if not isinstance(step, dict):
                problems.append('%s is not a mapping' % where)
                continue
            if 'run' in step:
                if not isinstance(step['run'], str):
                    problems.append('%s has a non-string "run"' % where)
            elif 'uses' not in step:
                problems.append('%s has neither "run" nor "uses"' % where)

    return problems


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    workflows = sorted((root / '.github' / 'workflows').glob('*.y*ml'))
    if not workflows:
        print('FAIL: no workflow files found under .github/workflows', file=sys.stderr)
        return 1

    failed = 0
    for path in workflows:
        rel = path.relative_to(root).as_posix()
        problems = check_file(path)
        if problems:
            failed += 1
            print('FAIL: %s' % rel, file=sys.stderr)
            for problem in problems:
                print('  %s' % problem, file=sys.stderr)
        else:
            print('OK  : %s' % rel)

    if failed:
        print(
            '\n%d of %d workflow(s) would not run. A workflow that fails here is '
            'rejected by GitHub silently: the run shows 0 jobs and no shell error.'
            % (failed, len(workflows)),
            file=sys.stderr,
        )
        return 1

    print('\nOK: %d workflows parse and keep their jobs' % len(workflows))
    return 0


if __name__ == '__main__':
    sys.exit(main())
