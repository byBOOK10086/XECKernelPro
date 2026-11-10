# server/ — 官网与发布流水线（开源副本）

这是 **XECKernel Pro 官网 `http://42.193.123.248/` 的服务器端代码**，与主仓同许可 **AGPL-3.0-or-later**（见根目录 `LICENSE`；AGPL §13 在这里的意思就是：我们对外提供网站/更新接口，就必须把这份服务器代码给你）。

生产机上跑的脚本原先带着硬编码的绝对路径与 OpenList 管理员口令；**这个目录里的版本已全部剥离**，凭据只从环境变量读。仓库里不存在任何明文密钥，`scripts/check_server_secrets.py` 会在 CI 里断言这一点。

## 文件

| 文件 | 作用 |
|---|---|
| `gen_site.py` | 单文件官网生成器：读 `_scratch/releases_all.json`（Release 全量）+ OpenList `/gki` 实时列表，把数据内嵌进一个 HTML |
| `publish_site.py` | 发布流水线，五个幂等步骤：`refresh` → `apk` → `build` → `deploy` → `verify` |
| `write_version_json.py` | 只重写 `/apk/version.json`（管理器更新检查读的就是它），不动 APK |
| `webserver_v2.ps1` | 端口 80 的静态站 + `/apk/*` 302 到 OpenList 的极简 Web 服务器（Windows Server 2025 / PowerShell） |
| `www/` | 生成产物落在这里（`index.html`），不入库 |

旧的一次性脚本 `deploy_site_live.py` / `check_site.py` 没有放进来：它们把当时的 tag、APK 文件名写死（`v31323-…`），功能已被 `publish_site.py deploy/verify` 覆盖。

## 环境变量

复制 `.env.example` 后填值，或者直接在进程环境里注入（生产机推荐后者）。

| 变量 | 必填 | 说明 |
|---|---|---|
| `XEC_ROOT` | 否 | 仓库根目录；默认取本目录的上一级 |
| `XEC_OPENLIST_USER` / `XEC_OPENLIST_PASS` | **是** | OpenList（`http://<host>:5244`）管理员账号，用于上传 APK/页面/清单 |
| `XEC_GH_TOKEN` | 是 | 读 GitHub Releases 用；没有也行但会被限速 |
| `XEC_RELEASES_JSON` | 否 | Release 缓存文件路径，默认 `_scratch/releases_all.json` |
| `XEC_SITE_OUT` | 否 | 生成的 HTML 路径，默认 `server/www/index.html` |
| `XEC_HTML` | 否 | `check_site` 类检查读取的 HTML 路径 |

站点地址 `SITE` / `OPENLIST` 在 `publish_site.py` 顶部，自建时改成自己的域名即可（不要写进密钥）。

## 用法

```bash
export XEC_OPENLIST_USER=admin XEC_OPENLIST_PASS='***' XEC_GH_TOKEN='***'
python server/publish_site.py refresh   # 拉 GitHub Releases → _scratch/releases_all.json
python server/publish_site.py apk       # 下载最新 Release APK 并上传到 OpenList /apk/
python server/publish_site.py build     # 重新生成 server/www/index.html
python server/publish_site.py deploy    # 上传并替换线上的 /www/index.html
python server/publish_site.py verify    # 只读校验：页面、version.json、APK 头
```

每一步都可单独重跑，失败不会把线上页面留在半成品状态（`deploy` 先备份再替换）。

## 自建一套需要什么

1. 一台能跑 PowerShell 的 Windows（或把 `publish_site.py` 换成你的上传后端）；
2. OpenList（或任意支持 `/api/fs/put`、`/api/fs/list` 的网盘后端）；
3. 一个指向 `server/www/` 的静态服务器（`webserver_v2.ps1` 是最小实现）；
4. 环境变量里的两个口令 + 一个 GitHub token。

## 许可

`AGPL-3.0-or-later`。你可以直接拿走自建；改完再对外提供服务时，按 §13 把你的改动也开源即可。
