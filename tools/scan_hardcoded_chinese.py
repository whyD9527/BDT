#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描 Kotlin 源码里的**硬编码中文**（② i18n 分批用）。

为什么需要它：
    历史代码里有大量中文直接写在 Kotlin 里（`Text("下载管理")`、toast 文案、异常消息…）。
    要本地化成 `strings.xml`（中英双套）就得先知道"有多少、都在哪、哪些是给用户看的"。
    本地没有 JDK，改错资源只有 CI 能发现 —— 所以先出清单、再分批，一批推一次。

分类（启发式，够用来排批次）：
    ui       面向用户的文案（要抽资源）
    internal 异常/断言消息（开发者看的，一般不翻译）
    diag     日志/轨迹（`Log.*` / `trace(` / `logDiagnostic(`，不翻译）

用法：
    python3 tools/scan_hardcoded_chinese.py                 # 人读的报告
    python3 tools/scan_hardcoded_chinese.py --json out.json # 机器可读
    python3 tools/scan_hardcoded_chinese.py --top 30
"""
import argparse
import json
import os
import re
import sys

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
# 字符串字面量：三引号（可跨行）在前，普通字符串在后
LITERAL = re.compile(r'"""(.*?)"""|"((?:[^"\\\n]|\\.)*)"', re.S)
DIAG_CALL = re.compile(r"\b(Log\.[dviwe]|trace|logDiagnostic|println|printStackTrace)\s*[\( ]")
INTERNAL_CALL = re.compile(r"\b(\w*Exception|require|check|error)\s*\(")

ROOTS = ("app/src/main", "core")
SKIP_DIR_PARTS = ("/build/", "/src/test/", "/src/androidTest/")


def strip_comments(src: str) -> str:
    out = []
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src[i] == '"':
            # 字符串整体保留（注释符号在字符串里不算注释）
            raw = src.startswith('"""', i)
            out.append(src[i:i + 3] if raw else src[i])
            i += 3 if raw else 1
            while i < n:
                if not raw and src[i] == "\\":
                    out.append(src[i:i + 2])
                    i += 2
                    continue
                if raw and src.startswith('"""', i):
                    out.append('"""')
                    i += 3
                    break
                if not raw and src[i] == '"':
                    out.append('"')
                    i += 1
                    break
                if not raw and src[i] == "\n":
                    break
                out.append(src[i])
                i += 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def kotlin_files():
    for root in ROOTS:
        for dirpath, dirnames, filenames in os.walk(root):
            if any(part in f"/{dirpath}/" for part in SKIP_DIR_PARTS):
                continue
            for name in filenames:
                if name.endswith(".kt"):
                    yield os.path.join(dirpath, name)


def classify(line: str) -> str:
    if DIAG_CALL.search(line):
        return "diag"
    if INTERNAL_CALL.search(line):
        return "internal"
    return "ui"


def scan_file(path: str):
    src = strip_comments(open(path, encoding="utf-8").read())
    lines = src.split("\n")
    hits = []
    for m in LITERAL.finditer(src):
        text = m.group(1) if m.group(1) is not None else m.group(2)
        if not text or not CJK.search(text):
            continue
        line = src.count("\n", 0, m.start()) + 1
        line_text = lines[line - 1].strip()
        # ⚠️ 跨行调用要回溯：`trace(\n "中文…"\n)` 这种，字面量所在行里看不到 `trace(`，
        # 按单行判定会误落进 ui 桶（E7 之后统计大部分"剩余 ui"就是这种假阳性）。
        context = " ".join(x.strip() for x in lines[max(0, line - 6):line])
        hits.append({
            "line": line,
            "kind": classify(context),
            "text": text.strip()[:120],
            "code": line_text[:160],
        })
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", help="把明细写到这个 JSON")
    ap.add_argument("--top", type=int, default=25)
    args = ap.parse_args()

    per_file = {}
    buckets = {"ui": 0, "internal": 0, "diag": 0}
    for path in sorted(kotlin_files()):
        hits = scan_file(path)
        if not hits:
            continue
        for h in hits:
            buckets[h["kind"]] += 1
        ui = sum(1 for h in hits if h["kind"] == "ui")
        per_file[path] = {"total": len(hits), "ui": ui, "hits": hits}

    total = sum(v["total"] for v in per_file.values())
    print(f"# 硬编码中文扫描：{len(per_file)} 个文件 / 共 {total} 处")
    print(f"# ui={buckets['ui']} internal={buckets['internal']} diag={buckets['diag']}")
    print()
    ranked = sorted(per_file.items(), key=lambda kv: (-kv[1]["ui"], kv[0]))
    print(f"## 按「面向用户」条数排序（前 {args.top}）")
    print("| ui | 合计 | 文件 |")
    print("|---|---|---|")
    for path, v in ranked[:args.top]:
        print(f"| {v['ui']} | {v['total']} | `{path}` |")
    print()
    print("## 样例（ui 桶，每条取前 2 个）")
    for path, v in ranked[:12]:
        samples = [h for h in v["hits"] if h["kind"] == "ui"][:2]
        for h in samples:
            print(f"- `{path}:{h['line']}` → {h['code']}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"buckets": buckets, "files": per_file}, fh, ensure_ascii=False, indent=2)
        print(f"\n# 明细已写 {args.json}", file=sys.stderr)


if __name__ == "__main__":
    main()
