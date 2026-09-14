#!/usr/bin/env python3
"""从某次 CI run 的 artifact 里**只取一个 APK**（用 HTTP Range），不下整包。

## 为什么需要它
真机验证要装一个新构建，而 artifact `bilibilias-alpha-apk` 是 139MB 的 zip、
里面 4 个 APK 只有 arm64-v8a 那个（约 38MB）用得上。zip 的中央目录在文件末尾，
每个条目的数据段可以按偏移单独取 —— 于是 43 秒、约 20MB 流量就够（整包 139MB）。

## 用法
    python3 tools/fetch_ci_apk.py <token> <run_id> arm64-v8a /sdcard/Download/app.apk

## 两个坑（都真撞过）
1. GitHub 会 **302 到 Azure Blob 的 SAS 地址**，而**不能把 `Authorization` 头带过去**：
   SAS 签名 + Authorization 头会被一起拒（`401 Server failed to authenticate`）。
   所以要自定义 `HTTPRedirectHandler`，在 `redirect_request` 里把那个头删掉。
2. 这条链路会**中途断流**（`IncompleteRead`）：按块取、逐块重试，断在哪就从哪接着取。
"""
import json
import struct
import sys
import time
import urllib.request
import zlib

CHUNK = 2 * 1024 * 1024
REPO = "whyD9527/BDT"


class StripAuthRedirect(urllib.request.HTTPRedirectHandler):
    """302 到 SAS 地址时要丢掉 Authorization 头（见文档字符串里的坑 1）"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None:
            new.headers.pop("Authorization", None)
            new.unredirected_hdrs.pop("Authorization", None)
        return new


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 2
    token, run_id, want, out_path = sys.argv[1:5]

    api = f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts"
    req = urllib.request.Request(api, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(req) as f:
        artifacts = json.load(f)["artifacts"]
    if not artifacts:
        print("这次 run 没有 artifact（可能失败在测试步骤，或还没打包完）")
        return 1
    art = artifacts[0]
    print(f"artifact: {art['name']}  {art['size_in_bytes'] / 1048576:.1f} MB")

    zip_api = f"https://api.github.com/repos/{REPO}/actions/artifacts/{art['id']}/zip"
    req = urllib.request.Request(zip_api, headers={"Authorization": f"Bearer {token}"})
    opener = urllib.request.build_opener(StripAuthRedirect)
    try:
        resp = opener.open(req)
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307, 308):
            raise
        signed = e.headers["Location"]
    else:
        signed = resp.geturl()

    def fetch(start, end, attempts=6):
        out = bytearray()
        cur = start
        while cur <= end:
            stop = min(cur + CHUNK - 1, end)
            for attempt in range(attempts):
                try:
                    r = urllib.request.Request(signed, headers={"Range": f"bytes={cur}-{stop}"})
                    with urllib.request.urlopen(r, timeout=180) as f:
                        data = f.read()
                    if len(data) != stop - cur + 1:
                        raise IOError(f"短读 {len(data)} != {stop - cur + 1}")
                    out += data
                    break
                except Exception as e:  # noqa: BLE001
                    print(f"  取 {cur}-{stop} 第 {attempt + 1} 次失败：{e}", flush=True)
                    time.sleep(2 * (attempt + 1))
            else:
                raise RuntimeError(f"区间 {cur}-{stop} 连续 {attempts} 次失败")
            cur = stop + 1
        return bytes(out)

    total = int(urllib.request.urlopen(
        urllib.request.Request(signed, headers={"Range": "bytes=0-0"})
    ).headers["Content-Range"].split("/")[1])
    print(f"zip 总大小: {total} 字节")

    tail = fetch(max(0, total - 66000), total - 1)
    i = tail.rfind(b"PK\x05\x06")
    if i < 0:
        print("找不到 EOCD（zip64？）")
        return 1
    cd_size, cd_off = struct.unpack_from("<II", tail, i + 12)
    cd = fetch(cd_off, cd_off + cd_size - 1)

    target = None
    pos = 0
    while pos < len(cd) - 4 and cd[pos:pos + 4] == b"PK\x01\x02":
        (method, _mt, _md, _crc, csize, usize, nlen, elen, clen,
         _d, _ia, _ea, lho) = struct.unpack_from("<HHHIIIHHHHHII", cd, pos + 10)
        name = cd[pos + 46:pos + 46 + nlen].decode("utf-8", "replace")
        print(f"  {name}  method={method} usize={usize}")
        if want in name:
            target = (name, method, csize, usize, lho)
        pos += 46 + nlen + elen + clen
    if not target:
        print(f"zip 里没有名字含 {want!r} 的条目")
        return 1

    name, method, csize, usize, lho = target
    head = fetch(lho, lho + 29)
    nlen, elen = struct.unpack_from("<HH", head, 26)
    data_start = lho + 30 + nlen + elen
    blob = fetch(data_start, data_start + csize - 1)
    raw = zlib.decompress(blob, -15) if method == 8 else blob
    if len(raw) != usize:
        print(f"解压后大小不符 {len(raw)} != {usize}")
        return 1
    with open(out_path, "wb") as f:
        f.write(raw)
    print(f"写出 {out_path}（{name}，{len(raw)} 字节）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
