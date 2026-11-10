# -*- coding: utf-8 -*-
"""Publish the newest XECKernel Pro release to the official site.

Pipeline (each step is idempotent, run them one at a time):
  refresh  - pull every GitHub release into _scratch/releases_all.json
  apk      - download the latest release APK and upload it to OpenList /apk/
  build    - regenerate server/www/index.html from gen_site.py
  preview  - upload the page as /www/_preview.html and smoke-test it
  deploy   - promote the page to /www/index.html and verify the live site
  verify   - read-only checks against the live site + APK

Usage: python -I publish_site.py <step> [...]
"""
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(os.environ.get("XEC_ROOT") or Path(__file__).resolve().parents[1])
SITE = "http://42.193.123.248"
OPENLIST = SITE + ":5244"
ADMIN = (os.environ.get("XEC_OPENLIST_USER", "admin").strip(),
         os.environ.get("XEC_OPENLIST_PASS", "").strip())
REPO = "byBOOK10086/XECKernelPro"
API = "https://api.github.com/repos/" + REPO
RELEASES_JSON = Path(os.environ.get("XEC_RELEASES_JSON") or ROOT / "_scratch/releases_all.json")
WWW = ROOT / "server/www"
PAGE = WWW / "index.html"
APK_DIR = WWW / "apk"
UA = "xec-site-publisher/1.0"


def gh_token():
    return os.environ.get("XEC_GH_TOKEN", "").strip()


def http(url, data=None, headers=None, method=None, timeout=120):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", UA)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    return urllib.request.urlopen(req, timeout=timeout)


def gh_json(url):
    # Ask for gzip: one /releases page is ~1.3 MB of JSON and this local link
    # truncates large plain-text bodies (http.client.IncompleteRead). Gzipped
    # it is ~130 KB and arrives intact.
    headers = {"Accept": "application/vnd.github+json", "Accept-Encoding": "gzip"}
    if gh_token():
        headers["Authorization"] = "Bearer " + gh_token()
    with http(url, headers=headers) as resp:
        raw = resp.read()
        if (resp.headers.get("Content-Encoding") or "").lower() == "gzip":
            raw = gzip.decompress(raw)
    return json.loads(raw)


def ol_token():
    if not ADMIN[1]:
        raise SystemExit("XEC_OPENLIST_PASS is not set - see server/.env.example")
    body = json.dumps({"username": ADMIN[0], "password": ADMIN[1]}).encode()
    with http(OPENLIST + "/api/auth/login", data=body,
              headers={"Content-Type": "application/json"}) as resp:
        data = json.load(resp)
    if data.get("code") != 200:
        raise SystemExit("openlist login failed: %s" % data)
    return data["data"]["token"]


def ol_put(token, remote_path, blob):
    req = urllib.request.Request(
        OPENLIST + "/api/fs/put", data=blob, method="PUT",
        headers={
            "Authorization": token,
            "Content-Type": "application/octet-stream",
            "File-Path": remote_path,
            "Content-Length": str(len(blob)),
        })
    with urllib.request.urlopen(req, timeout=300) as resp:
        return resp.status, resp.read().decode("utf-8", "replace")[:300]


def ol_stat(token, remote_path):
    body = json.dumps({"path": remote_path, "password": ""}).encode()
    try:
        with http(OPENLIST + "/api/fs/get", data=body, headers={
                "Content-Type": "application/json", "Authorization": token}) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as exc:
        return {"http": exc.code, "body": exc.read()[:200]}


def latest_release():
    # Read the snapshot written by `refresh` instead of hitting the API again:
    # the release list is ~1.3 MB and the odd TLS read truncation here has
    # already produced IncompleteRead more than once.
    releases = json.loads(RELEASES_JSON.read_text(encoding="utf-8"))
    normal = [r for r in releases if not r["tag_name"].startswith("gki-")]
    # 只认**已发布**的：GitHub 的 /releases 会把草稿排在最前面、顺序也不保证按时间，
    # 取 [0] 会拿到草稿（2026-11-08 就是这样：v31410 发出去了，version.json 却还停在
    # 31368，而且更早那批草稿 31350-31355 就排在 31410 前面）。判据改成
    # "非草稿 + 有发布时间 + APK 文件名里的 versionCode 最大"。
    live = [r for r in normal if not r.get("draft") and r.get("published_at")]
    if not live:
        raise SystemExit("no published (non-draft) release in the snapshot")
    return max(live, key=lambda r: (_apk_code_of(r), r.get("published_at") or ""))


def _apk_code_of(release):
    asset = next((a for a in release.get("assets") or [] if a["name"].endswith(".apk")), None)
    return apk_version_code(asset["name"]) if asset else 0


def apk_version_code(name):
    """The public version code the manager parses out of the APK file name."""
    m = re.search(r"_(\d+)(?:-release|-debug)?\.apk$", name)
    return int(m.group(1)) if m else 0


def publish_version_json(token, release, asset, name):
    """Write /apk/version.json, the manager's update-check source.

    The in-app update check used to read api.github.com directly. That host is
    unreachable for a large part of our users, and a failed request was silently
    turned into "no update", so the home screen never offered the new build.
    The site is reachable; keep the same four fields the app already consumes.
    """
    payload = {
        "versionCode": apk_version_code(name),
        "tag": release["tag_name"],
        "apkName": name,
        "apkUrl": OPENLIST + "/d/apk/" + urllib.parse.quote(name),
        "changelog": (release.get("body") or "").strip(),
        "publishedAt": release.get("published_at") or "",
        "size": asset["size"],
    }
    blob = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
    status, body = ol_put(token, "/apk/version.json", blob)
    print("upload /apk/version.json -> %s %s" % (status, body))
    return payload


def apk_asset(release):
    return next((a for a in release["assets"] if a["name"].endswith(".apk")), None)


def step_refresh():
    releases, page = [], 1
    while True:
        batch = gh_json(API + "/releases?per_page=100&page=%d" % page)
        if not batch:
            break
        releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    RELEASES_JSON.write_text(json.dumps(releases, ensure_ascii=False, indent=1),
                             encoding="utf-8", newline="\n")
    print("releases:", len(releases), "latest:", releases[0]["tag_name"],
          "->", RELEASES_JSON)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def asset_stream(asset):
    """Open a release asset for reading.

    The plain `browser_download_url` lives on github.com, which is routinely
    unreachable from this host while api.github.com and the object host still
    answer. Fall back to the API asset endpoint and follow its redirect by hand:
    the signed URL must not carry our Authorization header.
    """
    try:
        # 直连是"事先就知道大概率失败"的那条路（github.com:443 在这台机器上常年不通，
        # 且失败形态有时是拒绝连接、有时是一直挂着不回数据）。给短超时，别让回退路径
        # 白等五分钟——2026-11-05 就这样卡了 21 分钟、.part 还是 0 字节。
        return http(asset["browser_download_url"], timeout=30)
    except (urllib.error.URLError, OSError) as exc:
        print("direct download failed (%s); retrying through the API asset endpoint" % exc)

    url = API + "/releases/assets/%d" % asset["id"]
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "application/octet-stream")
    if gh_token():
        req.add_header("Authorization", "Bearer " + gh_token())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)
    try:
        opener.open(req, timeout=60)
    except urllib.error.HTTPError as err:
        if err.code != 302:
            raise
        location = err.headers["Location"]
    else:
        raise SystemExit("asset endpoint answered without redirecting")
    return urllib.request.urlopen(location, timeout=300)


def step_apk():
    release = latest_release()
    asset = apk_asset(release)
    if not asset:
        raise SystemExit("latest release has no APK")
    name, size = asset["name"], asset["size"]
    APK_DIR.mkdir(parents=True, exist_ok=True)
    local = APK_DIR / name
    if local.exists() and local.stat().st_size == size:
        print("cached:", local, size, "bytes")
    else:
        tmp = local.with_suffix(".part")
        digest = hashlib.sha256()
        with asset_stream(asset) as resp, open(tmp, "wb") as fh:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                fh.write(chunk)
                digest.update(chunk)
        if tmp.stat().st_size != size:
            raise SystemExit("size mismatch: got %d want %d" % (tmp.stat().st_size, size))
        tmp.replace(local)
        print("downloaded:", local, size, "bytes sha256:", digest.hexdigest())
    token = ol_token()
    status, body = ol_put(token, "/apk/" + name, local.read_bytes())
    print("upload /apk/%s -> %s %s" % (name, status, body))
    info = ol_stat(token, "/apk/" + name)
    print("stat:", json.dumps(info, ensure_ascii=False)[:300])
    publish_version_json(token, release, asset, name)
    return name


def expected():
    """The (tag, apk name) the page must advertise after a successful publish."""
    release = latest_release()
    asset = apk_asset(release)
    if not asset:
        raise SystemExit("latest release has no APK")
    return release["tag_name"], asset["name"]


def step_build():
    proc = subprocess.run([sys.executable, "-I", str(Path(__file__).resolve().parent / "gen_site.py")],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(proc.stdout)
    if proc.returncode != 0:
        print(proc.stderr)
        raise SystemExit("gen_site failed")
    html = PAGE.read_text(encoding="utf-8")
    tag, apk = expected()
    print("page chars:", len(html))
    for needle in (tag, "/d/apk/" + apk):
        print("  contains %-60s %s" % (needle, needle in html))


def step_preview():
    # Port 80 only serves '/', '/upload.html' and '/assets/*' - any other path is
    # 404 by design, so a preview URL cannot work. Kept as a no-op pointer.
    print("skipped: the XecWeb listener serves only /, /assets/* and redirects; "
          "use `deploy` and roll back from server/www/_backup/ if needed")


def step_deploy():
    token = ol_token()
    blob = PAGE.read_bytes()
    status, body = ol_put(token, "/www/index.html", blob)
    print("upload index ->", status, body)
    time.sleep(1)
    tag, apk = expected()
    with http(SITE + "/?v=%d" % int(time.time())) as resp:
        live = resp.read().decode("utf-8", "replace")
    print("live chars:", len(live))
    for needle in (tag, "/d/apk/" + apk, release_first_line()):
        print("  contains %-58s %s" % (needle[:58], needle in live))


def release_first_line():
    body = (latest_release().get("body") or "").strip()
    return body.splitlines()[0][:40] if body else ""


def step_verify():
    release = latest_release()
    asset = apk_asset(release)
    name = asset["name"]
    url = OPENLIST + "/d/apk/" + name
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        length = int(resp.headers.get("Content-Length") or 0)
        if length != asset["size"]:
            raise SystemExit("APK download size mismatch: got %d want %d" %
                             (length, asset["size"]))
        print("HEAD", url, resp.status, length, "verified")
    range_req = urllib.request.Request(url, headers={"User-Agent": UA,
                                    "Range": "bytes=0-3"})
    with urllib.request.urlopen(range_req, timeout=60) as resp:
        magic = resp.read(4)
        if resp.status != 206 or magic != b"PK\x03\x04":
            raise SystemExit("APK range download verification failed")
        print("GET range", resp.status, magic.hex(), "verified")
    with http(SITE + "/?v=%d" % int(time.time())) as resp:
        live = resp.read().decode("utf-8", "replace")
    ok = (release["tag_name"] in live and name in live)
    print("live:", resp.status, len(live), "tag:", release["tag_name"],
          "in page:", release["tag_name"] in live, "apk in page:", name in live)
    if not ok:
        raise SystemExit("Live page does not advertise the latest release")


def main():
    steps = {"refresh": step_refresh, "apk": step_apk, "build": step_build,
             "preview": step_preview, "deploy": step_deploy, "verify": step_verify}
    if len(sys.argv) < 2 or sys.argv[1] not in steps:
        raise SystemExit(__doc__)
    steps[sys.argv[1]]()


if __name__ == "__main__":
    main()
