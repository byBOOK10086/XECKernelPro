# -*- coding: utf-8 -*-
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# This file is generated from the production server copy with the credentials
# stripped: everything that used to be hard-coded (absolute paths, the OpenList
# administrator password) now comes from the environment.  See server/README.md
# and server/.env.example.
"""Write /apk/version.json for the newest release, without touching the APK.

The full `apk` step also downloads and re-uploads the APK, which is slow and was
the step that kept timing out. When the APK is already mirrored, only the tiny
JSON needs refreshing, so this does exactly that and nothing else.
"""
import os
import json
import urllib.request

import publish_site as ps

urllib.request.install_opener(
    urllib.request.build_opener(urllib.request.ProxyHandler({}))
)


def main():
    rel = ps.latest_release()
    asset = ps.apk_asset(rel)
    if not asset:
        raise SystemExit("latest release has no APK")
    name = asset["name"]

    info = ps.ol_stat(ps.ol_token(), "/apk/" + name)
    mirrored = ((info or {}).get("data") or {}).get("size")
    print("release: %s\nasset: %s (%d bytes)\nmirrored size: %s"
          % (rel["tag_name"], name, asset["size"], mirrored))
    if mirrored != asset["size"]:
        raise SystemExit("APK is not mirrored yet; refusing to write version.json")

    payload = ps.publish_version_json(ps.ol_token(), rel, asset, name)
    print("versionCode:", payload["versionCode"], "| tag:", payload["tag"])
    print("apkUrl:", payload["apkUrl"])


if __name__ == "__main__":
    main()
