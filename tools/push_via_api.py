#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
push_via_api.py —— 走 GitHub REST API 推送代码（这台设备专用）

为什么不用 `git push`：
    本机到 github.com:443 被阻断，但 api.github.com 正常。
    所以这里改用 Git Data API（blobs / trees / commits / refs）完成推送。

设计要点：
  * **逐文件比对本地与远程**，不依赖 commit SHA 是否一致。
    走 API 推送时，远程会生成与我们本地不同的 SHA（父提交不同），
    所以"本地 HEAD 和远程不一致"是正常现象，不代表有改动。
  * 比对依据是 **git blob SHA-1**：本地用 `git hash-object` 现算（含未提交的改动），
    远程用 `GET /git/trees?recursive=1` 拿到的 sha。两者相等即内容完全一致。
  * 只要内容没变就**不会产生空提交**。

用法：
    GH_TOKEN=<你的token> python3 tools/push_via_api.py
    GH_TOKEN=<你的token> python3 tools/push_via_api.py --message "fix: xxx"
    python3 tools/push_via_api.py --dry-run          # 只看差异，不写远程（公开仓库可免 token）
    python3 tools/push_via_api.py --stage-all         # 先 git add -A 再推送（含新文件）

Token 需要两项权限（Read and write）：
    * Contents      —— 读写仓库内容
    * Workflows     —— 推送 .github/workflows/ 下的文件时必须，
                       否则 API 会拒绝并提示 "without workflow scope"

注意：`git push` 在本机一定失败，别浪费时间重试；也不要 `git pull/fetch`。
"""

import argparse
import base64
import json
import os
import http.client
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO_SLUG_DEFAULT = "whyD9527/BDT"
API = "https://api.github.com"
# 被阻断的域名：万一有人改回 git push，这里留个提示
BLOCKED_HOST = "github.com"

GITLINK = "160000"


# --------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------

def die(msg, code=1):
    print(f"错误：{msg}", file=sys.stderr)
    sys.exit(code)


def run_git(args, **kw):
    """
    执行 git 命令并返回 stdout（bytes）。

    强制 core.quotePath=false：否则 git 会把非 ASCII 路径转义成
    "\\345\\212\\237..."，而 GitHub 返回的是原始 UTF-8，
    比对时中文文件名会被误判成一堆"新增 + 删除"。
    """
    proc = subprocess.run(
        ["git", "-c", "core.quotePath=false"] + args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        **kw,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            "git %s 失败：%s" % (" ".join(args), proc.stderr.decode("utf-8", "replace").strip())
        )
    return proc.stdout


def repo_root():
    try:
        out = run_git(["rev-parse", "--show-toplevel"])
    except RuntimeError as e:
        die(str(e))
    return out.decode("utf-8").strip()


class GitHub:
    """极简 GitHub REST 客户端，只实现本脚本需要的几个端点。"""

    def __init__(self, token, slug, verbose=True):
        self.token = token
        self.slug = slug
        self.verbose = verbose

    def request(self, method, path, payload=None, ok=(200, 201), retries=5):
        url = path if path.startswith("http") else f"{API}{path}"
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")

        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "bilibilias-push-script",
        }
        if data is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        last_err = None
        for attempt in range(1, retries + 1):
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    body = resp.read()
                    if resp.status not in ok:
                        raise urllib.error.HTTPError(
                            url, resp.status, "unexpected status", resp.headers, None
                        )
                    return json.loads(body) if body else None
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", "replace")
                last_err = self._explain(e.code, detail, method, path)
                # 4xx 是请求本身的问题，重试没有意义
                if e.code < 500:
                    die(last_err)
                if self.verbose:
                    print(f"  [{attempt}/{retries}] 服务端 {e.code}，重试…")
                time.sleep(2 * attempt)
            except urllib.error.URLError as e:
                last_err = f"网络错误：{e.reason}（api.github.com 是否可达？）"
                if self.verbose:
                    print(f"  [{attempt}/{retries}] {last_err}，重试…")
                time.sleep(2 * attempt)
            except (http.client.HTTPException, OSError) as e:
                # 这台设备到 api.github.com 的**偶发中途断连**：
                # `http.client.RemoteDisconnected`（连接被对端关掉、没回响应）
                # 是 `ConnectionResetError` 的子类，而 urllib **不会**把它包装成
                # `URLError`，所以上面那个 except 兜不住它 —— 不单独接住的话，
                # 推到一半（blob 上传阶段）整个脚本会带着 traceback 崩掉。
                # 重试是安全的：同内容 blob / tree / commit 会得到同一个 SHA。
                last_err = f"连接中断：{type(e).__name__}: {e}"
                if self.verbose:
                    print(f"  [{attempt}/{retries}] {last_err}，重试…")
                time.sleep(2 * attempt)

        die(last_err or "请求失败")

    @staticmethod
    def _explain(code, detail, method, path):
        try:
            msg = json.loads(detail).get("message", detail)
        except Exception:
            msg = detail
        hint = ""
        if code == 403 and "workflow" in detail.lower():
            hint = (
                "\n提示：token 缺少 Workflows 权限。请到 GitHub → Settings → Developer settings → "
                "Personal access tokens 给该 token 勾上 Workflows（Read and write）。"
            )
        elif code == 401:
            hint = "\n提示：token 无效或已过期。"
        elif code == 404:
            hint = "\n提示：仓库不存在，或 token 没有该仓库的访问权。"
        elif code == 403:
            hint = "\n提示：权限不足，或触发了 GitHub 限流（未带 token 时限流很紧）。"
        return f"{method} {path} → HTTP {code}: {msg}{hint}"

    # ---- 具体端点 ----
    def default_branch(self):
        info = self.request("GET", f"/repos/{self.slug}")
        return info.get("default_branch", "main")

    def ref_sha(self, branch):
        obj = self.request("GET", f"/repos/{self.slug}/git/ref/heads/{branch}")
        return obj["object"]["sha"]

    def commit(self, sha):
        return self.request("GET", f"/repos/{self.slug}/git/commits/{sha}")

    def tree(self, sha):
        obj = self.request("GET", f"/repos/{self.slug}/git/trees/{sha}?recursive=1")
        if obj.get("truncated"):
            print("警告：远程文件树被 GitHub 截断，差异比对可能不完整。", file=sys.stderr)
        return obj.get("tree", [])

    def blob(self, content_bytes):
        payload = {
            "content": base64.b64encode(content_bytes).decode("ascii"),
            "encoding": "base64",
        }
        return self.request("POST", f"/repos/{self.slug}/git/blobs", payload)["sha"]

    def create_tree(self, base_tree, entries):
        payload = {"base_tree": base_tree, "tree": entries}
        return self.request("POST", f"/repos/{self.slug}/git/trees", payload)["sha"]

    def create_commit(self, message, tree, parents):
        payload = {"message": message, "tree": tree, "parents": parents}
        return self.request("POST", f"/repos/{self.slug}/git/commits", payload)["sha"]

    def update_ref(self, branch, sha):
        payload = {"sha": sha, "force": False}
        return self.request(
            "PATCH", f"/repos/{self.slug}/git/refs/heads/{branch}", payload
        )


# --------------------------------------------------------------------------
# 本地状态
# --------------------------------------------------------------------------

def local_files(root, stage_all=False):
    """
    返回 {path: (mode, sha)}，mode 为 git 文件模式字符串，sha 为 blob SHA-1。

    * 普通文件：用 `git hash-object` 现算工作区内容，因此**未提交的改动也会被推送**。
    * 子模块（gitlink, mode 160000）：直接取索引里的提交 SHA，不去读它内部的文件
      （子模块目录常常是空的，硬读会炸）。
    """
    if stage_all:
        run_git(["-C", root, "add", "-A"])

    entries = run_git(["-C", root, "ls-files", "-s"]).decode("utf-8", "replace")
    result = {}
    regular_paths = []

    for line in entries.splitlines():
        if not line.strip():
            continue
        meta, path = line.split("\t", 1)
        mode, sha, _stage = meta.split()
        if mode == GITLINK:
            result[path] = (mode, sha)
        else:
            regular_paths.append(path)

    # 一次性批量现算 blob SHA
    if regular_paths:
        proc = subprocess.run(
            ["git", "-c", "core.quotePath=false", "-C", root, "hash-object", "--stdin-paths"],
            input=("\n".join(regular_paths) + "\n").encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if proc.returncode != 0:
            die("git hash-object 失败：" + proc.stderr.decode("utf-8", "replace").strip())
        shas = proc.stdout.decode("utf-8", "replace").split()
        if len(shas) != len(regular_paths):
            die("git hash-object 返回数量与文件数不符，中止。")
        mode_by_path = {}
        for line in entries.splitlines():
            if not line.strip():
                continue
            meta, path = line.split("\t", 1)
            mode_by_path[path] = meta.split()[0]
        for path, sha in zip(regular_paths, shas):
            result[path] = (mode_by_path[path], sha)

    return result


def remote_files(gh, tree_sha):
    result = {}
    for item in gh.tree(tree_sha):
        if item["type"] not in ("blob", "commit"):
            continue
        # tree 里 commit 类型就是子模块
        mode = "160000" if item["type"] == "commit" else item["mode"]
        result[item["path"]] = (mode, item["sha"])
    return result


def untracked_warning(root):
    out = run_git(["-C", root, "ls-files", "--others", "--exclude-standard"])
    paths = [p for p in out.decode("utf-8", "replace").splitlines() if p.strip()]
    return paths


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(
        description="走 GitHub API 推送（本机 github.com 被阻断）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--dry-run", action="store_true", help="只显示差异，不写远程")
    ap.add_argument("--message", "-m", default=None, help="提交信息")
    ap.add_argument("--branch", default=None, help="目标分支，默认仓库默认分支")
    ap.add_argument("--repo", default=REPO_SLUG_DEFAULT, help=f"owner/repo，默认 {REPO_SLUG_DEFAULT}")
    ap.add_argument("--stage-all", action="store_true", help="推送前先执行 git add -A（纳入新文件）")
    ap.add_argument("--limit", type=int, default=40, help="差异列表最多打印多少条")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
    root = repo_root()
    os.chdir(root)

    if not args.dry_run and not token:
        die(
            "未设置 GH_TOKEN。\n"
            "  用法：GH_TOKEN=<你的token> python3 tools/push_via_api.py\n"
            "  只查看差异可加 --dry-run（公开仓库免 token）。"
        )
    if not token:
        print("提示：未提供 GH_TOKEN，按只读模式运行（公开仓库可读，但限流很紧）。\n")

    gh = GitHub(token, args.repo)

    # 1) 目标分支
    branch = args.branch or gh.default_branch()
    print(f"仓库：{args.repo}    分支：{branch}")

    # 2) 远程基线
    head_sha = gh.ref_sha(branch)
    head_commit = gh.commit(head_sha)
    base_tree = head_commit["tree"]["sha"]
    print(f"远程 HEAD：{head_sha[:8]}  {head_commit['message'].splitlines()[0][:60]}")
    print(f"远程 base tree：{base_tree[:8]}")

    # 3) 本地状态
    if args.stage_all:
        print("已执行 git add -A")
    local = local_files(root, stage_all=args.stage_all)
    remote = remote_files(gh, base_tree)
    print(f"本地文件：{len(local)} 个    远程文件：{len(remote)} 个")

    # 4) 逐文件比对（blob SHA 相等 = 内容完全一致；模式位单独算一类）
    added = sorted(p for p in local if p not in remote)
    modified = sorted(
        p for p in local if p in remote and local[p][1] != remote[p][1]
    )
    # 内容相同、只有权限位不同（如 100644 ↔ 100755）。
    # 本机 core.filemode=false，权限位不会变，但远程可能被改过，所以要比。
    mode_only = sorted(
        p for p in local
        if p in remote
        and local[p][1] == remote[p][1]
        and local[p][0] != remote[p][0]
        and local[p][0] != GITLINK
    )
    deleted = sorted(p for p in remote if p not in local)

    def show(title, paths, annotate=None):
        if not paths:
            return
        print(f"\n{title}（{len(paths)}）：")
        for p in paths[: args.limit]:
            extra = f"   [{annotate(p)}]" if annotate else ""
            print(f"  {p}{extra}")
        if len(paths) > args.limit:
            print(f"  … 另有 {len(paths) - args.limit} 个未列出")

    show("新增", added)
    show("修改", modified)
    show("仅权限位变化", mode_only,
         annotate=lambda p: f"本地 {local[p][0]} → 远程 {remote[p][0]}")
    show("删除", deleted)

    stray = untracked_warning(root)
    if stray:
        print(f"\n注意：{len(stray)} 个未跟踪文件不会被推送（git 不跟踪它们）：")
        for p in stray[: args.limit]:
            print(f"  {p}")
        if len(stray) > args.limit:
            print(f"  … 另有 {len(stray) - args.limit} 个")
        print("  要一起推送，用 --stage-all 或在本地 git add 后再跑。")

    changed = added + modified + mode_only + deleted
    if not changed:
        print("\n本地与远程内容完全一致，无需推送。")
        print("（本地 HEAD 与远程 SHA 不同是正常的：API 推送会生成新 SHA。）")
        return 0

    print(f"\n共 {len(changed)} 个文件有差异（新增 {len(added)} / 修改 {len(modified)}"
          f" / 权限位 {len(mode_only)} / 删除 {len(deleted)}）。")

    if args.dry_run:
        print("\n--dry-run：未写入远程。确认无误后去掉该参数正式推送。")
        return 0

    # 5) 上传 blob
    tree_entries = []

    def add_entry(path, sha, mode):
        if mode == GITLINK:
            tree_entries.append({
                "path": path, "mode": "160000", "type": "commit", "sha": sha,
            })
        else:
            tree_entries.append({
                "path": path, "mode": mode, "type": "blob", "sha": sha,
            })

    for p in added + modified:
        mode, sha = local[p]
        if mode == GITLINK:
            add_entry(p, sha, mode)
            continue
        with open(p, "rb") as fh:
            content = fh.read()
        add_entry(p, gh.blob(content), mode)
        print(f"  上传 {p}")

    # 仅权限位变化：内容与远程相同，直接复用远程的 blob sha，只改 mode
    for p in mode_only:
        add_entry(p, remote[p][1], local[p][0])
        print(f"  改权限位 {p}: {remote[p][0]} → {local[p][0]}")

    # 删除：tree 条目 sha 置 null
    for p in deleted:
        tree_entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})
        print(f"  删除 {p}")

    # 6) 建树 → 建提交 → 移动分支
    new_tree = gh.create_tree(base_tree, tree_entries)
    print(f"新 tree：{new_tree[:8]}")

    message = args.message
    if not message:
        message = input("提交信息：").strip() or "chore: 通过 API 推送"
    new_commit = gh.create_commit(message, new_tree, [head_sha])
    print(f"新提交：{new_commit[:8]}  {message.splitlines()[0]}")

    gh.update_ref(branch, new_commit)
    print(f"\n推送完成：https://github.com/{args.repo}/commit/{new_commit}")
    print(f"CI 状态：https://github.com/{args.repo}/actions")

    if changed:
        print("\n提示：CI 出包后，Release 里上传的 APK 必须来自同一次构建，别混用不同 run 的产物。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n已取消。", file=sys.stderr)
        sys.exit(130)
