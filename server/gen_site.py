# -*- coding: utf-8 -*-
"""XECKernel Pro 官网生成器
输入: _scratch/releases_all.json (GitHub releases 全量，可用 XEC_RELEASES_JSON 覆盖) + OpenList /gki 实时列表
输出: server/www/index.html (单文件站点, 数据内嵌；可用 XEC_SITE_OUT 覆盖)
"""
import os
import json
import re
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(os.environ.get("XEC_ROOT") or Path(__file__).resolve().parents[1])
OUT = Path(os.environ.get("XEC_SITE_OUT") or ROOT / "server/www/index.html")

OPENLIST = "http://42.193.123.248:5244"
ADMIN = (os.environ.get("XEC_OPENLIST_USER", "admin").strip(),
         os.environ.get("XEC_OPENLIST_PASS", "").strip())
GKI_DIRS = [
    ("android12-5.10", "Android 12", "5.10"),
    ("android13-5.15", "Android 13", "5.15"),
    ("android14-6.1", "Android 14", "6.1"),
    ("android15-6.6", "Android 15", "6.6"),
    ("android16-6.12", "Android 16", "6.12"),
]
KMI_KERNEL = {"android12-5.10": "5.10", "android13-5.15": "5.15",
              "android14-6.1": "6.1", "android15-6.6": "6.6", "android16-6.12": "6.12"}

# 团队成员 QQ（用于从 QQ 头像 CDN 实时取头像；留空 = 继续用本地 assets/avN.jpg）。
# QQ 头像接口只认 QQ 号，不认昵称——要“全部对接”，把每个人的 QQ 号补进这张表即可。
TEAM_QQ = {
    "小陈同学": "3228636402",
    "有希yuki": "3231515355",
    "微微微": "2898395199",
    "凌洛": "3239554101",
    "疑问小猫": "3301237007",
    "🍊": "2947943797",
    "SunRay_Ex": "1414659510",
    "江不辞": "3318621299",
}


def _qq_avatar(m):
    """把团队卡片的本地头像换成 QQ 头像；失败时回落到本地图。"""
    name = m.group("name")
    qq = TEAM_QQ.get(name)
    if not qq:
        return m.group(0)
    local = m.group("src")
    return (
        f'<img class="pava" src="https://q1.qlogo.cn/g?b=qq&nk={qq}&s=640" '
        f'data-local="{local}" alt="{name}" loading="lazy" referrerpolicy="no-referrer" '
        f'onerror="this.onerror=null;this.src=this.dataset.local">'
    )


def apply_team_avatars(html: str) -> str:
    return re.sub(
        r'<img class="pava" src="(?P<src>assets/av\d+\.jpg\?v=2)" alt="(?P<name>[^"]+)">',
        _qq_avatar,
        html,
    )

# ---------- 数据采集 ----------

def ol_token():
    req = urllib.request.Request(
        OPENLIST + "/api/auth/login",
        data=json.dumps({"username": ADMIN[0], "password": ADMIN[1]}).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)["data"]["token"]

def ol_list(token, path):
    req = urllib.request.Request(
        OPENLIST + "/api/fs/list",
        data=json.dumps({"path": path, "page": 1, "per_page": 300, "refresh": True}).encode(),
        headers={"Content-Type": "application/json", "Authorization": token})
    with urllib.request.urlopen(req, timeout=30) as r:
        d = json.load(r)
        if d.get("code") != 200:
            raise RuntimeError(f"fs/list {path}: {d}")
        return d["data"]["content"] or []

def main():
    releases = json.loads((Path(os.environ.get("XEC_RELEASES_JSON")
                                or ROOT / "_scratch/releases_all.json")).read_text(encoding="utf-8"))
    token = ol_token()
    gki = {}
    total_pkgs = 0
    for dirid, label, kernel in GKI_DIRS:
        files = []
        for c in ol_list(token, "/gki/" + dirid):
            n = c["name"]
            if not n.endswith("-AnyKernel3.flash.zip"):
                continue
            if "Build-Logs" in n:
                continue
            m = re.search(r"-(\d+)-AnyKernel3\.flash\.zip$", n)
            sub = int(m.group(1)) if m else 99999
            files.append({"n": n, "s": c["size"], "sub": sub})
        files.sort(key=lambda f: f["sub"])
        gki[dirid] = {"label": label, "kernel": kernel, "files": files}
        total_pkgs += len(files)

    # KPM / antiwipe 已经整线下架（D4 移除 KPM），但老 release 的正文里还在讲它。
    # 官网是面向用户的展示面，历史正文一律按行过滤，别让死功能继续挂在公告里。
    _KPM_PAT = re.compile(r'kpm|antiwipe|防格机', re.I)

    def scrub_body(text):
        kept = [ln for ln in text.splitlines() if not _KPM_PAT.search(ln)]
        out = []
        for ln in kept:
            if ln.strip() == '' and out and out[-1].strip() == '':
                continue
            out.append(ln)
        return chr(10).join(out).strip()

    def rel_entry(r):
        apk = next((a for a in r["assets"] if a["name"].endswith(".apk")), None)
        body = scrub_body((r.get("body") or "").strip())
        summary = None
        for ln in body.splitlines():
            t = ln.strip().lstrip("> ").strip()
            if not t or t.startswith("**Full Changelog**") or t.startswith("Full Changelog"):
                continue
            summary = re.sub(r"\*\*(.+?)\*\*", r"\1", t)
            summary = re.sub(r"^#{1,6}\s*", "", summary)
            break
        return {
            "tag": r["tag_name"],
            "date": (r.get("published_at") or "")[:10],
            "pre": bool(r["prerelease"]),
            "gki": r["tag_name"].startswith("gki-"),
            "body": body,
            "sum": summary,
            "apk": {"n": apk["name"], "u": apk["browser_download_url"]} if apk else None,
            "na": len(r["assets"]),
            "u": "https://github.com/byBOOK10086/XECKernelPro/releases/tag/" + r["tag_name"],
        }

    # 草稿不进站点：它们没发布、也没有发布时间，挂在"历史版本"里会让用户点进 404。
    rel = [rel_entry(r) for r in releases if not r.get("draft")]
    # GitHub 的 /releases 把草稿排在最前面、顺序也不保证按时间，取 [0] 会挑错版本
    # （2026-11-08：v31410 已发布，站点却在推更早的草稿）。判据改成
    # "已发布 + APK 文件名里的 versionCode 最大"。
    live_rel = [r for r in releases if not r.get("draft") and r.get("published_at")]
    if not live_rel:
        raise RuntimeError("no published release in releases_all.json")

    def _apk_code(r):
        a = next((x for x in (r.get("assets") or []) if x["name"].endswith(".apk")), None)
        m = re.search(r"_(\d+)(?:-release|-debug)?\.apk$", a["name"]) if a else None
        return int(m.group(1)) if m else 0

    latest_rel = max(live_rel, key=lambda r: (_apk_code(r), r.get("published_at") or ""))
    latest = next(x for x in rel if x["tag"] == latest_rel["tag_name"])
    normal = [x for x in rel if not x["gki"]]
    apk_latest = latest["apk"]
    if not apk_latest:
        raise RuntimeError(f"latest release {latest['tag']} ships no APK asset")
    # 下载按钮跟着最新 release 的资产名走：以前这里写死成 v31205 的文件名，
    # 导致站点上的"下载最新版"一直发旧包（2026-11-03 修）。
    apk_name = apk_latest["n"]
    apk_bytes = next(
        (a["size"] for a in (latest_rel.get("assets") or []) if a["name"] == apk_name), 0
    ) or next((a["size"] for a in (latest_rel.get("assets") or [])
               if a["name"].endswith(".apk")), 0)
    apk_cn = OPENLIST + "/d/apk/" + apk_name
    n_ann = 5

    data = {
        "rel": rel, "gki": gki,
        "meta": {
            "built": date.today().isoformat(),
            "nRel": len(normal), "nGkiRel": len(rel) - len(normal),
            "nPkgs": total_pkgs, "nKmi": len(gki),
            "apk": {"tag": latest["tag"], "date": latest["date"],
                    "size": apk_latest,
                    "bytes": apk_bytes},
        },
    }
    data_json = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")

    html = TEMPLATE
    for k, v in {
        "__DATA__": data_json,
        "__BUILT__": date.today().isoformat(),
        "__LATEST_TAG__": latest["tag"],
        "__LATEST_DATE__": latest["date"],
        "__APK_CN__": apk_cn,
        "__APK_GH__": "https://github.com/byBOOK10086/XECKernelPro/releases/latest",
        "__APK_SIZE__": f"{apk_bytes / 1048576:.2f}",
        "__APK_FILE__": apk_name,
        "__N_REL__": str(len(normal)),
        "__N_PKGS__": str(total_pkgs),
        "__N_KMI__": str(len(gki)),
        "__ANN_FIRST__": str(n_ann),
    }.items():
        html = html.replace(k, v)

    html = apply_team_avatars(html)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8", newline="\n")
    print(f"OK -> {OUT}")
    print(f"releases={len(rel)} (normal={len(normal)}, gki={len(rel)-len(normal)})  gki_pkgs={total_pkgs}")
    for k, v in gki.items():
        print(f"  {k}: {len(v['files'])} pkgs, subs {[f['sub'] for f in v['files']]}")
    print(f"latest={latest['tag']} apk={apk_latest['n'] if apk_latest else None}")


TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0b0e16" media="(prefers-color-scheme: dark)">
<title>XECKernel Pro — 内核级 Root 方案</title>
<meta name="description" content="XECKernel Pro：基于KernelSU开发的内核级方案，旨在做到更稳定，更实用的管理器。">
<link rel="icon" type="image/webp" href="assets/logo.webp">
<style>
:root{
  --bg:#ffffff; --bg-soft:#f6f8fb; --card:#ffffff; --border:#e5e9f2; --text:#10131a; --sub:#5b6472;
  --acc:#4d6bfe; --acc2:#9c57fe; --acc-soft:rgba(77,107,254,.08); --code-bg:#f2f4f9;
  --shadow:0 1px 2px rgba(16,19,26,.04),0 8px 28px rgba(16,19,26,.06);
  --shadow-hover:0 2px 4px rgba(16,19,26,.05),0 14px 40px rgba(16,19,26,.10);
}
@media (prefers-color-scheme: dark){
  :root{
    --bg:#0b0e16; --bg-soft:#0f1320; --card:#111523; --border:#202739; --text:#e8ebf2; --sub:#98a1b3;
    --acc:#6b84ff; --acc2:#b07dff; --acc-soft:rgba(107,132,255,.12); --code-bg:#171c2c;
    --shadow:0 1px 2px rgba(0,0,0,.3),0 8px 28px rgba(0,0,0,.35);
    --shadow-hover:0 2px 4px rgba(0,0,0,.35),0 14px 40px rgba(0,0,0,.45);
  }
}
*{margin:0;padding:0;box-sizing:border-box}
html{scroll-behavior:smooth;scroll-padding-top:76px}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  background:var(--bg);color:var(--text);line-height:1.7;font-size:15.5px;-webkit-font-smoothing:antialiased}
a{color:var(--acc);text-decoration:none}
a:hover{text-decoration:underline}
code{background:var(--code-bg);border:1px solid var(--border);border-radius:6px;padding:.1em .4em;font-size:.88em;
  font-family:ui-monospace,SFMono-Regular,Consolas,"Cascadia Mono",monospace;overflow-wrap:normal}
pre{background:var(--code-bg);border:1px solid var(--border);border-radius:10px;padding:14px 16px;overflow-x:auto;
  font-size:13px;line-height:1.8;font-family:ui-monospace,SFMono-Regular,Consolas,monospace}
pre code{background:none;border:none;padding:0}
.wrap{max-width:1080px;margin:0 auto;padding:0 24px}

/* header */
header{position:fixed;top:0;left:0;right:0;z-index:100;
  background:color-mix(in srgb,var(--bg) 82%,transparent);backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);
  border-bottom:1px solid var(--border)}
.nav{max-width:1080px;margin:0 auto;padding:0 24px;height:60px;display:flex;align-items:center;gap:26px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;font-size:17px;color:var(--text);text-decoration:none}
.brand:hover{text-decoration:none}
.brand img{width:28px;height:28px;border-radius:8px}
.brand b{background:linear-gradient(120deg,var(--acc),var(--acc2));-webkit-background-clip:text;background-clip:text;color:transparent}
.nav .links{display:flex;gap:20px;margin-left:auto}
.nav .links a{color:var(--sub);font-size:14px}
.nav .links a:hover,.nav .links a.on{color:var(--text)}
.btn{display:inline-flex;align-items:center;gap:8px;border-radius:10px;padding:9px 18px;font-size:14.5px;font-weight:600;
  cursor:pointer;border:1px solid transparent;transition:.18s;text-decoration:none}
.btn:hover{text-decoration:none;transform:translateY(-1px)}
.btn-pri{background:var(--acc);color:#fff}
.btn-pri:hover{background:color-mix(in srgb,var(--acc) 88%,#000)}
.btn-gho{background:transparent;color:var(--text);border-color:var(--border)}
.btn-gho:hover{border-color:var(--acc);color:var(--acc)}
.btn-sm{padding:6px 14px;font-size:13.5px;border-radius:9px}
.menu-btn{display:none;margin-left:auto;background:none;border:none;color:var(--text);font-size:22px;line-height:1;cursor:pointer;padding:8px 4px}
.mobile-menu{display:none;position:fixed;top:60px;left:0;right:0;z-index:99;
  background:var(--bg);border-bottom:1px solid var(--border);box-shadow:var(--shadow);padding:6px 24px 12px}
.mobile-menu a{display:block;padding:12px 2px;color:var(--text);font-size:15px;border-bottom:1px solid var(--border)}
.mobile-menu a:last-child{border-bottom:none}
td .mono{overflow-wrap:break-word}
.ann-item .head .tag{word-break:break-all}

/* hero */
.hero{padding:150px 0 84px;text-align:center}
.hero .badge{display:inline-flex;align-items:center;gap:8px;background:var(--acc-soft);color:var(--acc);
  border:1px solid color-mix(in srgb,var(--acc) 25%,transparent);border-radius:999px;padding:5px 14px;font-size:13px;font-weight:600}
.hero .badge .dot{width:7px;height:7px;border-radius:50%;background:#22c55e;box-shadow:0 0 0 3px rgba(34,197,94,.18)}
.hero h1{font-size:58px;letter-spacing:-.5px;font-weight:800;margin:22px 0 6px;line-height:1.15}
.hero h1 .grad{background:linear-gradient(120deg,var(--acc) 10%,var(--acc2) 90%);-webkit-background-clip:text;background-clip:text;color:transparent}
.hero .tagline{max-width:680px;margin:14px auto 0;color:var(--sub);font-size:17px}
.hero .cta{margin-top:34px;display:flex;gap:14px;justify-content:center;flex-wrap:wrap}
.hero .cta .btn{padding:12px 26px;font-size:15.5px;border-radius:12px}
.hero .dl-hint{margin-top:14px;font-size:12.5px;color:var(--sub)}
.stats{margin-top:56px;display:flex;justify-content:center;gap:0;border:1px solid var(--border);border-radius:16px;
  max-width:720px;margin-left:auto;margin-right:auto;background:var(--card);box-shadow:var(--shadow)}
.stats>div{flex:1;padding:20px 10px;text-align:center}
.stats>div+div{border-left:1px solid var(--border)}
.stats b{display:block;font-size:26px;font-weight:800;background:linear-gradient(120deg,var(--acc),var(--acc2));
  -webkit-background-clip:text;background-clip:text;color:transparent}
.stats span{font-size:12.5px;color:var(--sub)}

/* sections */
section{padding:86px 0}
section.alt{background:var(--bg-soft)}
.sec-head{text-align:center;margin-bottom:44px}
.sec-head .kicker{color:var(--acc);font-size:13px;font-weight:700;letter-spacing:.12em;text-transform:uppercase}
.sec-head h2{font-size:32px;font-weight:800;margin-top:8px;letter-spacing:-.3px}
.sec-head p{color:var(--sub);margin-top:10px;max-width:640px;margin-left:auto;margin-right:auto;font-size:15px}

/* cards */
.cards{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
.card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:26px;box-shadow:var(--shadow);
  transition:.22s;display:flex;flex-direction:column}
.card:hover{box-shadow:var(--shadow-hover);transform:translateY(-3px);border-color:color-mix(in srgb,var(--acc) 30%,var(--border))}
.card .tag{align-self:flex-start;font-size:11.5px;font-weight:700;color:var(--acc);background:var(--acc-soft);
  border-radius:999px;padding:3px 10px;margin-bottom:14px}
.card .tag.green{color:#16a34a;background:rgba(34,197,94,.1)}
.card .tag.violet{color:var(--acc2);background:rgba(156,87,254,.1)}
.card h3{font-size:19px;font-weight:750;margin-bottom:4px}
.card .meta{font-size:12.5px;color:var(--sub);margin-bottom:12px}
.card p{font-size:14px;color:var(--sub);flex:1}
.card .acts{display:flex;gap:10px;margin-top:18px;flex-wrap:wrap}

/* announcements */
.ann{max-width:820px;margin:0 auto;display:flex;flex-direction:column;gap:18px}
.ann-item{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:24px 28px;box-shadow:var(--shadow)}
.ann-item .head{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:8px}
.ann-item .head .tag{font-family:ui-monospace,Consolas,monospace;font-weight:700;font-size:15px}
.ann-item .head time{color:var(--sub);font-size:13px}
.ann-item .head .pill{font-size:11px;font-weight:700;border-radius:999px;padding:2px 9px;margin-left:auto}
.pill.stable{background:rgba(34,197,94,.12);color:#16a34a}
.pill.pre{background:rgba(234,179,8,.14);color:#b45309}
.pill.gki{background:var(--acc-soft);color:var(--acc)}
.note{background:var(--code-bg);border-left:3px solid var(--acc);border-radius:0 10px 10px 0;padding:12px 16px;margin:10px 0;font-size:14px}
.note p{margin:4px 0}
.ann-item .body p{font-size:14.5px;margin:6px 0}
.ann-item .lnk{font-size:13px;margin-top:8px;display:inline-block}

/* tables */
.tbl-box{background:var(--card);border:1px solid var(--border);border-radius:16px;box-shadow:var(--shadow);overflow:hidden}
.tbl-scroll{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:13.5px}
th{font-size:11.5px;text-transform:uppercase;letter-spacing:.06em;color:var(--sub);text-align:left;
  padding:12px 18px;border-bottom:1px solid var(--border);background:var(--bg-soft);white-space:nowrap}
td{padding:11px 18px;border-bottom:1px solid var(--border);vertical-align:top}
tr:last-child td{border-bottom:none}
tbody tr:hover{background:var(--bg-soft)}
td .mono{font-family:ui-monospace,Consolas,monospace;font-size:13px;word-break:break-all}
td time{color:var(--sub);font-size:12.5px;white-space:nowrap}
.dl{white-space:nowrap;font-weight:600}
.toolbar{display:flex;gap:14px;align-items:center;margin-bottom:16px;flex-wrap:wrap}
.toolbar input{flex:1;min-width:220px;background:var(--card);border:1px solid var(--border);border-radius:10px;
  padding:10px 14px;font-size:14px;color:var(--text);outline:none}
.toolbar input:focus{border-color:var(--acc);box-shadow:0 0 0 3px var(--acc-soft)}
.toolbar .cnt{font-size:13px;color:var(--sub);white-space:nowrap}

  padding:30px 32px;box-shadow:var(--shadow)}
  display:flex;align-items:center;justify-content:center;font-size:26px;flex:none}
.hash{font-family:ui-monospace,Consolas,monospace;font-size:11.5px;color:var(--sub);word-break:break-all;margin-top:12px}
.hash code{word-break:break-all}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:22px}
.panel{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:20px 22px}
.panel h4{font-size:15.5px;font-weight:750;margin-bottom:10px}
.panel ul{padding-left:20px;color:var(--sub);font-size:13.5px}
.panel ul li{margin:5px 0}
.threat-note{max-width:860px;margin:18px auto 0;font-size:13px;color:var(--sub)}

/* gki search */
.chips{display:flex;gap:8px;flex-wrap:wrap}
.chip{cursor:pointer;border:1px solid var(--border);background:var(--card);color:var(--sub);border-radius:999px;padding:6px 14px;font-size:13px;transition:.15s;font-family:inherit}
.chip:hover{border-color:var(--acc);color:var(--acc)}
.chip.on{background:var(--acc-soft);border-color:var(--acc);color:var(--acc);font-weight:650}
.sync{max-width:860px;margin:14px auto 0;font-size:12.5px;color:var(--sub);text-align:right}
#build-table td{padding:9px 14px}
.mono + .pill{margin-left:8px}
.bsum{max-width:320px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--sub);font-size:13px}

/* team pyramid */
.pyramid{display:flex;flex-direction:column;align-items:center;gap:18px}
.ptier{display:flex;justify-content:center;gap:18px;width:100%}
.ptier.t2{max-width:940px}
.ptier.t3{max-width:1080px}
.pcard{background:var(--card);border:1px solid var(--border);border-radius:16px;box-shadow:var(--shadow);
  padding:22px 20px;text-align:center;display:flex;flex-direction:column;align-items:center;flex:1;transition:.2s}
.pcard:hover{transform:translateY(-3px);box-shadow:var(--shadow-hover);border-color:color-mix(in srgb,var(--acc) 30%,var(--border))}
.t1 .pcard{max-width:430px}
.pava{width:96px;height:96px;border-radius:50%;object-fit:cover;border:2px solid var(--border);box-shadow:var(--shadow)}
.t1 .pava{width:120px;height:120px}
.t3 .pava{width:76px;height:76px}
.pava-letter{display:flex;align-items:center;justify-content:center;background:linear-gradient(135deg,var(--acc),var(--acc2));
  color:#fff;font-size:34px;font-weight:800}
.t3 .pava-letter{font-size:26px}
.pname{font-weight:750;margin-top:12px;font-size:16.5px}
.t1 .pname{font-size:20px}
.prole{color:var(--acc);font-size:12.5px;font-weight:650;margin-top:3px}
.pcontrib{color:var(--sub);font-size:13px;margin-top:10px;line-height:1.65;flex:1}
.pcontact{margin-top:12px;font-size:12px;color:var(--sub);background:var(--bg-soft);border:1px solid var(--border);border-radius:999px;padding:3px 13px;white-space:nowrap}
.dev-group{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:16px 20px;box-shadow:var(--shadow)}
.dev-group h4{font-size:13.5px;font-weight:750;color:var(--sub);margin-bottom:8px}
.dev-group .names{display:flex;flex-wrap:wrap;gap:8px}
.dev-group .names span{background:var(--bg-soft);border:1px solid var(--border);border-radius:999px;padding:4px 13px;font-size:13.5px}
.qun{max-width:920px;margin:18px auto 0;background:var(--acc-soft);border:1px solid color-mix(in srgb,var(--acc) 22%,transparent);
  border-radius:12px;padding:13px 20px;font-size:14px;text-align:center;color:var(--text)}

/* repos */
.repo-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:18px;max-width:860px;margin:0 auto}
.repo{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:22px 24px;box-shadow:var(--shadow);transition:.2s;display:block;color:var(--text)}
.repo:hover{transform:translateY(-3px);box-shadow:var(--shadow-hover);text-decoration:none;border-color:color-mix(in srgb,var(--acc) 30%,var(--border))}
.repo .rname{font-weight:750;font-size:15.5px;display:flex;align-items:center;gap:8px}
.repo .rname svg{flex:none}
.repo p{font-size:13.5px;color:var(--sub);margin-top:6px}
.repo .lic{margin-top:10px;font-size:12px;color:var(--sub)}

/* footer */
footer{border-top:1px solid var(--border);padding:40px 0 calc(56px + env(safe-area-inset-bottom));color:var(--sub);font-size:13px}
footer .fwrap{max-width:1080px;margin:0 auto;padding:0 24px}
footer .flinks{display:flex;gap:18px;margin-bottom:14px;flex-wrap:wrap}
footer p{margin:4px 0}

/* reveal */
.reveal{opacity:0;transform:translateY(20px);transition:opacity .6s ease,transform .6s ease}
.reveal.on{opacity:1;transform:none}

@media (max-width:860px){
  .cards,.grid2,.repo-grid{grid-template-columns:1fr}
  #build-table .bsum{display:none}
  .ptier{flex-direction:column;align-items:stretch}
  .ptier.t1,.ptier.t2,.ptier.t3{max-width:430px}
  .t1 .pcard{max-width:100%}
  .nav{gap:12px}
  .nav .links{display:none}
  .menu-btn{display:block}
  .mobile-menu.open{display:block}
  .hero{padding:106px 0 52px}
  .hero h1{font-size:36px}
  .hero .tagline{font-size:15.5px}
  .hero .badge{font-size:12px;padding:4px 12px}
  .stats{flex-direction:row;flex-wrap:wrap}
  .stats>div{flex:1 1 50%;padding:15px 8px}
  .stats>div+div{border-left:none;border-top:none}
  .stats>div:nth-child(even){border-left:1px solid var(--border)}
  .stats>div:nth-child(n+3){border-top:1px solid var(--border)}
  section{padding:52px 0}
  .sec-head h2{font-size:26px}
  .pcard{padding:18px 16px}
  .toolbar input{font-size:16px}
  .tbl-scroll{-webkit-overflow-scrolling:touch}
}
@media (max-width:640px){
  #gki-result th:nth-child(2),#gki-result td:nth-child(2){display:none}
  #gki-result .mono{font-size:12px}
  #build-table td:first-child{min-width:140px}
  #build-table td:first-child .mono{overflow-wrap:anywhere}
  #build-table td{padding:9px 10px}
  #build-table .bna{display:none}
  .ann-item{padding:18px 16px}
  .tbl-box{border-radius:12px}
  table{font-size:13px}
}
</style>
</head>
<body>

<header>
  <nav class="nav">
    <a class="brand" href="#top"><img src="assets/logo.webp" alt="logo"><span>XECKernel&nbsp;<b>Pro</b></span></a>
    <div class="links">
      <a href="#products">产品</a>
      <a href="#announce">更新公告</a>
      <a href="#allbuilds">全部版本</a>
      <a href="#gki">GKI 内核</a>
      <a href="#team">团队</a>
      <a href="#repos">开源仓库</a>
    </div>
    <a class="btn btn-pri btn-sm" href="__APK_CN__" id="nav-dl">下载最新版</a>
    <button class="menu-btn" id="menu-btn" aria-label="打开菜单" aria-expanded="false">☰</button>
  </nav>
</header>

<div class="mobile-menu" id="mobile-menu">
  <a href="#products">产品</a>
  <a href="#announce">更新公告</a>
  <a href="#allbuilds">全部版本</a>
  <a href="#gki">GKI 内核</a>
  <a href="#team">团队</a>
  <a href="#repos">开源仓库</a>
  <a href="__APK_CN__" style="color:var(--acc);font-weight:650">⬇ 下载最新版管理器</a>
</div>

<main id="top">

<!-- ======================== 主页介绍 ======================== -->
<div class="hero wrap">
  <span class="badge"><span class="dot"></span>最新版本&nbsp;__LATEST_TAG__&nbsp;·&nbsp;__LATEST_DATE__</span>
  <h1>XECKernel <span class="grad">Pro</span></h1>
  <p class="tagline">XECKernel Pro：基于KernelSU开发的内核级方案，旨在做到更稳定，更实用的管理器</p>
  <div class="cta">
    <a class="btn btn-pri" href="__APK_CN__">⬇&nbsp;下载最新版管理器</a>
    <a class="btn btn-gho" href="https://github.com/byBOOK10086/XECKernelPro" target="_blank" rel="noopener">GitHub 仓库</a>
  </div>
  <p class="dl-hint">国内直链 __APK_SIZE__ MB（arm64）· 也可从 <a href="__APK_GH__" target="_blank" rel="noopener">GitHub Releases</a> 下载</p>
  <div class="stats reveal">
    <div><b>__N_REL__</b><span>个正式版本</span></div>
    <div><b>__N_PKGS__</b><span>个 GKI 内核包</span></div>
    <div><b>__N_KMI__</b><span>大 KMI 全覆盖</span></div>
    <div><b>8</b><span>个 KMI 的 LKM 支持</span></div>
  </div>
</div>

<!-- ======================== 产品 ======================== -->
<section id="products" class="alt">
  <div class="wrap">
    <div class="sec-head reveal">
      <div class="kicker">Products</div>
      <h2>产品</h2>
      <p>管理器的所有版本下载地址</p>
    </div>
    <div class="cards">
      <div class="card reveal">
        <span class="tag">最新版本</span>
        <h3>XECKernel Pro 管理器</h3>
        <div class="meta">__LATEST_TAG__ · __LATEST_DATE__ · __APK_SIZE__ MB · arm64-v8a</div>
        <p>一体化 Root 管理器：内核 / LKM 双形态，模块仓库与上传分享，TEE 状态看板，超级密钥安全锁，主题商店与液态玻璃 UI。</p>
        <div class="acts">
          <a class="btn btn-pri btn-sm" href="__APK_CN__">国内直链下载</a>
          <a class="btn btn-gho btn-sm" href="__APK_GH__" target="_blank" rel="noopener">GitHub</a>
        </div>
      </div>
      <div class="card reveal">
        <span class="tag violet">持续更新</span>
        <h3>GKI 内核卡刷包</h3>
        <div class="meta">__N_KMI__ 大 KMI · __N_PKGS__ 个构建</div>
        <p>内置 Root 与 SUSFS 的 GKI 2.0 内核，AnyKernel3 卡刷包 Recovery 直刷，支持 Android 12 – 16（内核 5.10 – 6.12），按 <code>uname -r</code> 选包。</p>
        <div class="acts">
          <a class="btn btn-gho btn-sm" href="#gki">前往下载</a>
        </div>
      </div>
    </div>
  </div>
</section>

<!-- ======================== 更新公告 ======================== -->
<section id="announce">
  <div class="wrap">
    <div class="sec-head reveal">
      <div class="kicker">Announcements</div>
      <h2>更新公告</h2>
      <p>来自 GitHub Releases 的最新公告。</p>
    </div>
    <div class="ann" id="ann-list"></div>
  </div>
</section>

<!-- ======================== 所有构建的版本 ======================== -->
<section id="allbuilds">
  <div class="wrap">
    <div class="sec-head reveal">
      <div class="kicker">All Builds</div>
      <h2>所有构建的版本</h2>
      <p>只放最近 5 个版本；更早的都在 <a href="https://github.com/byBOOK10086/XECKernelPro/releases" target="_blank" rel="noopener">GitHub Releases</a> 里存档。</p>
    </div>
    <div class="tbl-box reveal">
      <div class="tbl-scroll">
        <table id="build-table">
          <thead><tr><th>版本</th><th>日期</th><th>安装包</th><th class="bna">资产</th><th class="bsum">说明</th></tr></thead>
          <tbody id="build-tbody"></tbody>
        </table>
      </div>
    </div>
    <p style="text-align:center;margin-top:18px;font-size:13.5px"><a href="https://github.com/byBOOK10086/XECKernelPro/releases" target="_blank" rel="noopener">在 GitHub 查看全部 __N_REL__ 个正式版本与 10 个 GKI 内核内测版 →</a></p>
  </div>
</section>

<!-- ======================== GKI 内核包 ======================== -->
<section id="gki">
  <div class="wrap">
    <div class="sec-head reveal">
      <div class="kicker">GKI Kernels</div>
      <h2>GKI 内核包下载</h2>
      <p>内置 Root（KSU <code>CONFIG_KSU=y</code>）+ SUSFS 全套的 GKI 2.0 内核，AnyKernel3 卡刷包共 __N_PKGS__ 个构建——直接搜索你的内核版本即可找到对应的包。</p>
    </div>

    <div class="toolbar reveal">
      <input id="gki-search" type="search" placeholder="输入你的内核版本，如 6.12.23、6.6.108、android15、5.10…" aria-label="搜索 GKI 内核包">
      <span class="cnt" id="gki-cnt"></span>
    </div>
    <div class="chips reveal" id="gki-chips" style="margin:0 0 18px"></div>
    <div id="gki-result"></div>
    <p class="sync" id="gki-sync">快照生成于 __BUILT__ · 页面打开时会自动向构建服务器刷新目录</p>
    <div class="panel reveal" style="max-width:860px;margin:22px auto 0">
      <h4>📦 用法与刷入</h4>
      <ul>
        <li>先看内核版本：<code>uname -r</code>，形如 <code>6.12.23-android16-…</code> → 直接搜索 <code>6.12.23</code>，对应的包就出来了；</li>
        <li>没有完全一致的 sub_level 时会列出同段全部构建，选最接近的（优先更高一位）；小米设备选带小米符号适配批次；</li>
        <li>卡刷包（AnyKernel3）用第三方 Recovery 直刷或管理器内核安装，只替换 boot 内核、保留原 ramdisk；仅限 GKI 2.0 设备，非 GKI 走管理器 LKM / init_boot 修补；</li>
        <li>刷完开机进管理器完成守护（xudc）安装，SUSFS 才有调用者；刷前务必备份原 boot 分区。</li>
      </ul>
    </div>
  </div>
</section>

<!-- ======================== 团队 ======================== -->
<section id="team" class="alt">
  <div class="wrap">
    <div class="sec-head reveal">
      <div class="kicker">Team</div>
      <h2>团队与贡献者</h2>
      <p>贡献者名单与开发者</p>
    </div>

    <!-- ✏️ 团队金字塔：改名字/职位/贡献/联系方式直接编辑对应 .pcard；头像图放 assets/ 后改 src -->
    <div class="pyramid">
      <div class="ptier t1 reveal">
        <div class="pcard">
          <img class="pava" src="assets/av1.jpg?v=2" alt="小陈同学">
          <div class="pname">小陈同学</div>
          <div class="prole">主开发者</div>
          <p class="pcontrib">负责此项目的主要开发与受理，负责日常更新与产业规划</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
      </div>
      <div class="ptier t2 reveal">
        <div class="pcard">
          <img class="pava" src="assets/av2.jpg?v=2" alt="有希yuki">
          <div class="pname">有希yuki</div>
          <div class="prole">产品指导与开发</div>
          <p class="pcontrib">负责此项目的安全审计与开发路线指导，负责检查产品的路线规划与法律风险</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
        <div class="pcard">
          <img class="pava" src="assets/av3.jpg?v=2" alt="微微微">
          <div class="pname">微微微</div>
          <div class="prole">产品协助与开发</div>
          <p class="pcontrib">负责此项目的生态开发，与部分功能提供功能</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
        <div class="pcard">
          <img class="pava" src="assets/av4.jpg?v=2" alt="凌洛">
          <div class="pname">凌洛</div>
          <div class="prole">产品生态与开发</div>
          <p class="pcontrib">为此项目构建集成式的 GKI 通用内核，维护内核的稳定性</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
      </div>
      <div class="ptier t3 reveal">
        <div class="pcard">
          <img class="pava" src="assets/av5.jpg?v=2" alt="疑问小猫">
          <div class="pname">疑问小猫</div>
          <div class="prole">产品协助与资源投入</div>
          <p class="pcontrib">为此项目投入 OpenAI 等高产能生产工具</p>
          <span class="pcontact">QQ 3301237007</span>
        </div>
        <div class="pcard">
          <img class="pava" src="assets/av6.jpg?v=2" alt="江不辞">
          <div class="pname">江不辞</div>
          <div class="prole">产品协助与协助开发</div>
          <p class="pcontrib">集成修复重点 bug，协助开发此项目</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
        <div class="pcard">
          <img class="pava" src="assets/av7.jpg?v=2" alt="🍊">
          <div class="pname">🍊</div>
          <div class="prole">产品协助与建议开发</div>
          <p class="pcontrib">做产品的开发工作与宣传工作，功不可没</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
        <div class="pcard">
          <img class="pava" src="assets/av8.jpg?v=2" alt="SunRay_Ex">
          <div class="pname">SunRay_Ex</div>
          <div class="prole">特别鸣谢 · 产品开发路线规划与 UI 设计创作</div>
          <p class="pcontrib">暂不透露</p>
          <span class="pcontact">联系方式：暂不透露</span>
        </div>
      </div>
    </div>

    <div class="dev-group reveal" style="max-width:920px;margin:24px auto 0">
      <h4>致谢</h4>
      <div class="names"><span>tiann/KernelSU（weishu）</span><span>TEESimulator-RS</span><span>Tricky Addon</span><span>susfs4ksu</span><span>HMA-OSS</span><span>GhostLock</span><span>KPatch-Next</span><span>LSPosed</span></div>
    </div>
    <div class="qun reveal">官方交流群：941895988</div>
  </div>
</section>

<!-- ======================== 开源仓库 ======================== -->
<section id="repos">
  <div class="wrap">
    <div class="sec-head reveal">
      <div class="kicker">Open Source</div>
      <h2>开源仓库</h2>
      <p>全部核心组件开源于 GitHub，欢迎审阅与反馈。</p>
    </div>
    <div class="repo-grid">
      <a class="repo reveal" href="https://github.com/byBOOK10086/XECKernelPro" target="_blank" rel="noopener">
        <div class="rname"><svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"/></svg>byBOOK10086/XECKernelPro</div>
        <p>主仓库：内核（KSU / SUSFS 适配）+ 管理器 + 用户态守护 xudc，发版在此发布。</p>
        <div class="lic">GPL-3.0（继承上游 KernelSU）· fresh-main 分支</div>
      </a>
      <a class="repo reveal" href="https://github.com/byBOOK10086/AnyKernel3" target="_blank" rel="noopener">
        <div class="rname"><svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"/></svg>byBOOK10086/AnyKernel3</div>
        <p>GKI 内核构建仓：五大 KMI 矩阵（Android 12 – 16）在 GitHub Actions 自动构建 AnyKernel3 卡刷包。</p>
        <div class="lic">GPL-2.0 · main 分支</div>
      </a>
      <a class="repo reveal" href="https://github.com/byBOOK10086/XECKernelPro-Modules" target="_blank" rel="noopener">
        <div class="rname"><svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"/></svg>byBOOK10086/XECKernelPro-Modules</div>
        <p>官方模块仓库：管理器内置模块中心的数据源，支持 issue 表单投稿上架。</p>
        <div class="lic">API v1 契约 · 以 zip 内 module.prop 为准</div>
      </a>
      <a class="repo reveal" href="https://github.com/byBOOK10086/XECKernelPro-Themes" target="_blank" rel="noopener">
        <div class="rname"><svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"/></svg>byBOOK10086/XECKernelPro-Themes</div>
        <p>主题仓库：<code>.xectheme</code> 主题包的索引、上传与校验，管理器主题商店在此取数。</p>
        <div class="lic">主题包格式 v2 · 索引自动构建</div>
      </a>
    </div>
  </div>
</section>

</main>

<footer>
  <div class="fwrap">
    <div class="flinks">
      <a href="https://github.com/byBOOK10086/XECKernelPro" target="_blank" rel="noopener">GitHub</a>
      <a href="#top">回到顶部</a>
      <a href="http://42.193.123.248/upload.html">模块上传页</a>
      <a href="http://42.193.123.248:5244">文件仓库</a>
    </div>
    <p>本站为 XECKernel Pro 项目官网，基于开源项目 <a href="https://github.com/tiann/KernelSU" target="_blank" rel="noopener">KernelSU</a> 二次开发，感谢上游与所有贡献者。</p>
    <p>Root 与刷机有风险（变砖 / 失保 / 安全暴露），操作前请备份数据并阅读仓库文档；使用本项目造成的一切后果由使用者自行承担。</p>
    <p>© 2026 XEC · XECKernel Pro · 页面由构建流水线于 __BUILT__ 生成</p>
  </div>
</footer>

<script id="site-data" type="application/json">__DATA__</script>
<script>
const DATA = JSON.parse(document.getElementById('site-data').textContent);
const OL = 'http://42.193.123.248:5244';
const esc = s => String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');

function mdLite(src){
  const lines = String(src||'').split(/\r?\n/);
  let out = '', inQ = false;
  for (let raw of lines){
    let ln = raw;
    const q = ln.startsWith('> ');
    if (q && !inQ){ out += '<div class="note">'; inQ = true; ln = ln.slice(2); }
    else if (!q && inQ){ out += '</div>'; inQ = false; }
    else if (q){ ln = ln.slice(2); }
    let t = esc(ln);
    t = t.replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>');
    t = t.replace(/`([^`]+)`/g,'<code>$1</code>');
    t = t.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,'<a href="$2" target="_blank" rel="noopener">$1</a>');
    t = t.replace(/(^|[\s>])(https?:\/\/[^\s<"']+)/g,'$1<a href="$2" target="_blank" rel="noopener">$2</a>');
    if (t.trim()==='') { out += (inQ?'<br>':''); continue; }
    out += '<p>'+t+'</p>';
  }
  if (inQ) out += '</div>';
  return out;
}

function pill(r){
  if (r.gki) return '<span class="pill gki">GKI 内核</span>';
  return r.pre ? '<span class="pill pre">内测</span>' : '<span class="pill stable">正式</span>';
}
function mb(b){ return (b/1048576).toFixed(1) + ' MB'; }

/* 更新公告 */
const ann = DATA.rel.slice(0, __ANN_FIRST__);
document.getElementById('ann-list').innerHTML = ann.map(r => `
  <div class="ann-item reveal on">
    <div class="head">
      <span class="tag">${esc(r.tag)}</span><time>${esc(r.date)}</time>${pill(r)}
    </div>
    <div class="body">${mdLite(r.body) || '<p class="lnk">（无公告正文）</p>'}</div>
    <a class="lnk" href="${esc(r.u)}" target="_blank" rel="noopener">在 GitHub 查看（${r.na} 个资产）→</a>
  </div>`).join('');

/* 所有构建的版本 */
const tbody = document.getElementById('build-tbody');
function renderBuilds(list){
  tbody.innerHTML = list.map(r => `
    <tr>
      <td><span class="mono"><a href="${esc(r.u)}" target="_blank" rel="noopener">${esc(r.tag)}</a></span>${pill(r)}</td>
      <td><time>${esc(r.date)}</time></td>
      <td class="dl">${r.apk ? `<a href="${esc(r.apk.u)}" target="_blank" rel="noopener">APK ↓</a>` : '<span style="color:var(--sub)">—</span>'}</td>
      <td><a class="bna" href="${esc(r.u)}" target="_blank" rel="noopener">${r.na} 项</a></td>
      <td class="bsum" title="${esc(r.sum||'')}">${r.sum ? esc(r.sum.length>60 ? r.sum.slice(0,60)+'…' : r.sum) : '—'}</td>
    </tr>`).join('');
}
renderBuilds(DATA.rel.slice(0, 5));

/* GKI 搜索（支持 6.12.23 / 6.6.108-android15 / android15 / 5.10 / 纯 sub_level） */
const GKI = JSON.parse(JSON.stringify(DATA.gki));
const gkiInput = document.getElementById('gki-search');
const gkiRes = document.getElementById('gki-result');
const gkiCnt = document.getElementById('gki-cnt');
const gkiChips = document.getElementById('gki-chips');
const gkiSync = document.getElementById('gki-sync');

function parseQ(s){
  let q = s.trim().toLowerCase().replace(/^v/, '');
  if (!q) return {mode:'all'};
  const m = q.match(/^(\d{1,2})\.(\d{1,2})(?:\.(\d{1,3}))?(?:[-.\s].*)?$/);
  if (m) return {mode:'ver', series:m[1]+'.'+m[2], sub:m[3]?parseInt(m[3]):null};
  return {mode:'text', raw:q};
}
function collectGki(q){
  const rows = [];
  for (const dir of Object.keys(GKI)){
    const grp = GKI[dir];
    for (const f of grp.files){
      let hit;
      if (q.mode==='all') hit = true;
      else if (q.mode==='ver') hit = grp.kernel===q.series && (q.sub==null || f.sub===q.sub);
      else if (/^\d{1,3}$/.test(q.raw)) hit = f.sub === parseInt(q.raw) || f.n.toLowerCase().includes(q.raw);
      else hit = f.n.toLowerCase().includes(q.raw) || dir.includes(q.raw) || grp.kernel.includes(q.raw)
               || ('android'+grp.label.toLowerCase().replace('android ','')).includes(q.raw);
      if (hit) rows.push({dir, grp, f});
    }
  }
  return rows;
}
function renderGkiSearch(){
  const q = parseQ(gkiInput.value);
  let rows = collectGki(q);
  let notice = '';
  if (q.mode==='ver' && q.sub!=null && rows.length===0){
    rows = collectGki({mode:'ver', series:q.series, sub:null});
    notice = `没有 ${esc(q.series+'.'+q.sub)} 的完全匹配包——以下是同段（${esc(q.series)}）全部构建，选与你的 sub_level 最接近的（优先更高一位）：`;
  }
  gkiRes.innerHTML = (notice ? `<div class="note" style="margin:0 0 14px"><p>${notice}</p></div>` : '') + `
    <div class="tbl-box"><div class="tbl-scroll"><table>
      <thead><tr><th>文件名</th><th style="width:190px">系统 / sub_level</th><th style="width:90px">大小</th><th style="width:90px">下载</th></tr></thead>
      <tbody>${rows.map(r => `
        <tr>
          <td><span class="mono">${esc(r.f.n)}</span></td>
          <td><time>${esc(r.grp.label)} · ${esc(r.grp.kernel)}-${r.f.sub}</time></td>
          <td><time>${mb(r.f.s)}</time></td>
          <td class="dl"><a href="${OL}/d/gki/${r.dir}/${esc(r.f.n)}">下载 ↓</a></td>
        </tr>`).join('') || '<tr><td colspan="4" style="text-align:center;color:var(--sub)">没有匹配的包——试试只输内核段（如 6.12）或子版本号（如 23）</td></tr>'}
      </tbody>
    </table></div></div>`;
  gkiCnt.textContent = q.mode==='all' ? `共 ${rows.length} 个包` : `匹配 ${rows.length} 个包`;
  gkiChips.querySelectorAll('.chip').forEach(b =>
    b.classList.toggle('on', (b.dataset.v==='' && q.mode==='all') || (q.mode==='ver' && q.sub==null && b.dataset.v===q.series)));
}
gkiChips.innerHTML = [['','全部']].concat(Object.keys(GKI).map(d => [GKI[d].kernel, GKI[d].label + ' · ' + GKI[d].kernel]))
  .map(([v,l]) => `<button type="button" class="chip" data-v="${v}">${esc(l)}</button>`).join('');
gkiChips.addEventListener('click', e => {
  const b = e.target.closest('.chip'); if (!b) return;
  gkiInput.value = b.dataset.v;
  renderGkiSearch();
});
gkiInput.addEventListener('input', renderGkiSearch);
renderGkiSearch();

/* 实时刷新（尽力而为，失败保留快照） */
(async () => {
  try{
    for (const dir of Object.keys(DATA.gki)){
      const res = await fetch(OL + '/api/fs/list', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({path:'/gki/'+dir, page:1, per_page:300})});
      const j = await res.json();
      if (!j || j.code !== 200) return;
      const files = (j.data.content||[])
        .filter(f => f.name.endsWith('-AnyKernel3.flash.zip') && !f.name.includes('Build-Logs'))
        .map(f => { const m = f.name.match(/-(\d+)-AnyKernel3\.flash\.zip$/);
          return {n:f.name, s:f.size, sub: m ? parseInt(m[1]) : 99999}; })
        .sort((a,b) => a.sub - b.sub);
      GKI[dir] = Object.assign({}, GKI[dir], {files});
    }
    renderGkiSearch();
    gkiSync.textContent = '已连接构建服务器，目录为实时数据 · ' + new Date().toLocaleTimeString('zh-CN');
  }catch(e){ /* 保持快照 */ }
})();

/* 滚动显现 */
const io = new IntersectionObserver(es => es.forEach(x => { if (x.isIntersecting){ x.target.classList.add('on'); io.unobserve(x.target); } }), {threshold:.05});
document.querySelectorAll('.reveal').forEach(el => io.observe(el));
setTimeout(() => document.querySelectorAll('.reveal:not(.on)').forEach(el => el.classList.add('on')), 3000);

/* 移动端菜单 */
const menuBtn = document.getElementById('menu-btn');
const mobileMenu = document.getElementById('mobile-menu');
menuBtn.addEventListener('click', () => {
  const open = mobileMenu.classList.toggle('open');
  menuBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
});
mobileMenu.addEventListener('click', e => {
  if (e.target.tagName === 'A'){
    mobileMenu.classList.remove('open');
    menuBtn.setAttribute('aria-expanded', 'false');
  }
});

/* 导航高亮 */
const secs = ['products','announce','allbuilds','gki','team','repos'];
const navIo = new IntersectionObserver(es => es.forEach(x => {
  if (x.isIntersecting){
    document.querySelectorAll('.nav .links a').forEach(a =>
      a.classList.toggle('on', a.getAttribute('href') === '#' + x.target.id));
  }
}), {rootMargin:'-40% 0px -55% 0px'});
secs.forEach(id => { const el = document.getElementById(id); if (el) navIo.observe(el); });
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()
