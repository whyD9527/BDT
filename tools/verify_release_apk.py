#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从「已发布的 release APK」里独立核验两件事，全程只用 HTTP Range，不下整包：

  1. 签名证书 SHA-256 指纹  —— 是否与密钥库/上一版一致（决定老用户能否覆盖升级）
  2. AndroidManifest 里的 versionName —— 打包的版本号对不对

背景：GitHub 的 release 资源支持 Range 请求，所以取签名块与 manifest 只需几百 KB。
（注意：不要用后缀区间写法 bytes=-1024，前置的 Varnish 会返回 501。）
"""
import json
import struct
import subprocess
import sys
import urllib.request
import zlib

SLUG = "whyD9527/BDT"
TAG = sys.argv[1] if len(sys.argv) > 1 else "v3.1.7"
ABI = sys.argv[2] if len(sys.argv) > 2 else "arm64-v8a"
EXPECT_FP = "72:2D:A0:56:6C:73:E7:08:F4:88:BC:95:6C:75:AA:91:CD:64:18:56:17:7A:4B:66:A6:87:CE:71:E3:03:CF:06"


def api(path):
    req = urllib.request.Request(
        f"https://api.github.com{path}",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "verify-release"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


release = api(f"/repos/{SLUG}/releases/tags/{TAG}")
print(f"release: {release['tag_name']}  「{release['name']}」  published {release['published_at']}")
print("资源：")
for a in release["assets"]:
    print(f"   {a['name']:44} {a['size']:>10} 字节   下载数 {a['download_count']}")

target = next((a for a in release["assets"] if ABI in a["name"]), None)
if target is None:
    sys.exit("没找到 arm64-v8a 资源")
URL, TOTAL = target["url"], target["size"]
print(f"\n核验对象：{target['name']}（{TOTAL} 字节）")


def fetch(start, end):
    req = urllib.request.Request(URL, headers={
        "Range": f"bytes={start}-{end}",
        "Accept": "application/octet-stream",
        "User-Agent": "verify-release",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def read_u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


# ---------- 1) 定位中央目录 ----------
tail_start = max(0, TOTAL - 65536)
tail = fetch(tail_start, TOTAL - 1)
idx = tail.rfind(b"PK\x05\x06")
if idx < 0:
    sys.exit("没找到 EOCD")
cd_size, cd_off = struct.unpack_from("<IHHHHIIH", tail, idx)[5:7]
cd = fetch(cd_off, cd_off + cd_size - 1)

entries = {}
p = 0
while p + 46 <= len(cd) and cd[p:p + 4] == b"PK\x01\x02":
    (_, _, _, _, method, _, _, crc, csize, usize, nlen, elen, clen,
     _, _, _, lho) = struct.unpack_from("<IHHHHHHIIIHHHHHII", cd, p)
    name = cd[p + 46:p + 46 + nlen].decode("utf-8", "replace")
    entries[name] = dict(method=method, csize=csize, lho=lho)
    p += 46 + nlen + elen + clen
print(f"zip 条目数: {len(entries)}")


def read_entry(name):
    ent = entries[name]
    lh = fetch(ent["lho"], ent["lho"] + 29)
    nlen, elen = struct.unpack_from("<IHHHHHIIIHH", lh, 0)[9:11]
    data_off = ent["lho"] + 30 + nlen + elen
    raw = fetch(data_off, data_off + ent["csize"] - 1)
    return zlib.decompress(raw, -15) if ent["method"] == 8 else raw


# ---------- 2) versionName（从 AndroidManifest 的字符串池里找） ----------
# 期望版本号从 tag 推导（v3.1.8 → 3.1.8）——别写死，否则换个 tag 就验不了
EXPECT_VERSION = TAG[1:] if TAG.startswith("v") else TAG
print(f"\n--- 版本号（期望 {EXPECT_VERSION}）---")
try:
    manifest = read_entry("AndroidManifest.xml")
    found = []
    for label, needle in (("UTF-8", EXPECT_VERSION.encode()),
                          ("UTF-16LE", EXPECT_VERSION.encode("utf-16-le"))):
        if needle in manifest:
            found.append(label)
    print(f"   AndroidManifest.xml 解出 {len(manifest)} 字节；"
          f"包含 \"{EXPECT_VERSION}\" 的编码：{found if found else '未找到 ❌'}")
except KeyError:
    print("   ❌ zip 里没有 AndroidManifest.xml")

# ---------- 3) 签名证书指纹 ----------
print("\n--- 签名证书 ---")
v1 = [n for n in entries if n.upper().startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))]
if v1:
    pem = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-print_certs", "-outform", "PEM"],
                         input=read_entry(v1[0]), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    der = subprocess.run(["openssl", "x509", "-outform", "DER"], input=pem.stdout,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
else:
    # v2 方案：在 APK Signing Block 里按 ID 直接定位
    start = max(0, cd_off - 262144)
    blk = fetch(start, cd_off - 1)
    pos = blk.rfind(b"\x1a\x87\x09\x71")
    if pos < 0:
        sys.exit("既无 v1 也无 v2 签名")
    ln = struct.unpack_from("<Q", blk, pos - 8)[0]
    value = blk[pos + 4:pos + ln]
    signers = value[4:4 + read_u32(value, 0)]
    signer = signers[4:4 + read_u32(signers, 0)]
    signed_data = signer[4:4 + read_u32(signer, 0)]
    o = 4 + read_u32(signed_data, 0)
    certs_seq = signed_data[o + 4:o + 4 + read_u32(signed_data, o)]
    der = certs_seq[4:4 + read_u32(certs_seq, 0)]
    print("   使用 v2 签名方案")

info = subprocess.run(["openssl", "x509", "-inform", "DER", "-noout", "-subject", "-dates"],
                      input=der, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode().strip()
fp = subprocess.run(["openssl", "x509", "-inform", "DER", "-noout", "-fingerprint", "-sha256"],
                    input=der, stdout=subprocess.PIPE).stdout.decode().strip()
print("  " + info.replace("\n", "\n  "))
print("  " + fp)
got = fp.split("=", 1)[1].strip()
print()
print("  ✅ 指纹与密钥库/上一版一致" if got == EXPECT_FP else f"  ❌ 指纹不一致！期望 {EXPECT_FP}")
