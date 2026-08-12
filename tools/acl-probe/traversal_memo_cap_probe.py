"""A3 の残差: TraversalMemo の上限 2,048 は適正か。

## なぜ「測らないと言えない」のか

memo は **1 回の走査の中だけ**生きる祖先スナップショットの再利用機構で、走査が終われば
消える。したがって上限が余裕なのか、きつくて捨てまくっているのか、そもそも関係ないのか、
**サーバを見ても分からなかった**。2,048 という値は議論で決めたもので実測の裏付けが無い、
というのが台帳に残っていた残差。

そこで memo のカウンタをプロセス全体で集計して
`GET /api/v1/admin/search-index/metrics` に出した:

- `traversalMemoEvictions` — **これが判定の中心**。0 なら、どの走査も working set が
  収まっていて上限は 1 円も損をさせていない。増えるなら実際の木が上限を叩いていて、
  memo が存在する理由 (祖先の再利用) を捨てている
- `traversalMemoPeakEntries` — 実際に見た最大の working set。上限との差が余裕
- `traversalMemoHits` / `traversalMemoInvalidations`

## このプローブが測る形

上限を叩きうるのは「深くて広い」木。memo は access-order LRU なので、**共有される上の
祖先鎖は残り、枝ごとの一時的なフォルダが落ちる**設計になっている。よって

- 深さを稼ぐ (祖先鎖を長くする)
- 同じ深さに枝を広げる (一時エントリを増やす)

の両方を持つ木を作り、その root に applyACL をかけて 1 回の走査で何エントリまで
膨らむかを見る。

**上限を叩けなければ「上限が十分」とは言い切れない**ので、比較のために
**わざと小さい上限**の効果も見たい — が、上限は定数なので実機では変えられない。
代わりに **peak と上限の比**を報告する。peak が上限に遠く及ばないなら、その木の形では
上限が効いていないことの直接の証拠になる。

前提: nb33 スタック、admin:admin。他の測定と同時に走らせないこと。
"""
import concurrent.futures
import json
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
REPO = "bedroom"
BR = f"{BASE}/browser/{REPO}/root"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

DEPTH = 12          # 祖先鎖の長さ
BRANCH = 6          # 各階層の枝
DOCS_PER_LEAF = 3
SEED_WORKERS = 16

BODY = b"memo cap probe"


def session():
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    s.mount("http://", requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32))
    return s


A = session()


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def mkfolder(s, name, parent):
    return oid(s.post(BR, data={
        "cmisaction": "createFolder", "objectId": parent,
        "propertyId[0]": "cmis:name", "propertyValue[0]": name,
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=300).json())


def mkdoc(s, folder, name):
    r = s.post(BR, files={"content": (f"{name}.txt", BODY, "text/plain")},
               data={"cmisaction": "createDocument", "objectId": folder,
                     "propertyId[0]": "cmis:name", "propertyValue[0]": f"{name}.txt",
                     "propertyId[1]": "cmis:objectTypeId",
                     "propertyValue[1]": "cmis:document"}, timeout=300)
    if not (200 <= r.status_code < 300):
        raise RuntimeError(f"{r.status_code}: {r.text[:120]}")


def metrics():
    return A.get(f"{BASE}/api/v1/admin/search-index/metrics", timeout=120).json()


def build(tag):
    """A tree that is both deep and wide: a long shared spine with branches hanging off it."""
    container = mkfolder(A, f"memo-{tag}", ROOT)
    leaves = []
    spine = container
    for d in range(DEPTH):
        spine = mkfolder(A, f"memo-{tag}-d{d}", spine)
        # Branches at this depth: transient memo entries that compete with the shared spine.
        for b in range(BRANCH):
            leaves.append(mkfolder(A, f"memo-{tag}-d{d}b{b}", spine))
    print(f"  spine depth {DEPTH}, {len(leaves)} branch folders", flush=True)

    sessions = [session() for _ in range(SEED_WORKERS)]
    jobs = [(leaves[i // DOCS_PER_LEAF], f"memo-{tag}-{i}")
            for i in range(len(leaves) * DOCS_PER_LEAF)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=SEED_WORKERS) as ex:
        futs = [ex.submit(mkdoc, sessions[i % SEED_WORKERS], f, n)
                for i, (f, n) in enumerate(jobs)]
        for f in concurrent.futures.as_completed(futs):
            f.result()
    total = 1 + DEPTH + len(leaves) + len(jobs)
    print(f"  {len(jobs)} documents; about {total} nodes in the subtree", flush=True)
    return container, total


def main():
    tag = uuid.uuid4().hex[:4]
    print(f"building a deep+wide tree under memo-{tag}...", flush=True)
    container, nodes = build(tag)

    before = metrics()
    cap = before["traversalMemoMaxEntries"]
    print(f"\nmemo cap = {cap}", flush=True)
    print(f"before: evictions={before['traversalMemoEvictions']} "
          f"peak={before['traversalMemoPeakEntries']} hits={before['traversalMemoHits']}",
          flush=True)

    principal = f"memoprobe-{tag}"
    print("\nfiring applyACL (propagate) on the tree root...", flush=True)
    t0 = time.time()
    r = A.post(BR, data={
        "cmisaction": "applyACL", "objectId": container,
        "ACLPropagation": "propagate",
        "addACEPrincipal[0]": principal,
        "addACEPermission[0][0]": "cmis:read"}, timeout=1800)
    print(f"  request returned in {time.time()-t0:.2f}s (status {r.status_code})", flush=True)

    # Wait for the propagation to leave the API.
    deadline = time.time() + 3600
    seen = False
    while time.time() < deadline:
        body = A.get(f"{BASE}/api/v1/admin/search-index/propagation", timeout=60).json()
        running = [x for x in body.get("running", []) if x.get("rootObjectId") == container]
        if running:
            seen = True
        elif seen:
            break
        time.sleep(0.5)
    print(f"  propagation finished after {time.time()-t0:.1f}s", flush=True)

    after = metrics()
    d_evict = after["traversalMemoEvictions"] - before["traversalMemoEvictions"]
    d_hits = after["traversalMemoHits"] - before["traversalMemoHits"]
    peak = after["traversalMemoPeakEntries"]

    print(f"\nafter: evictions={after['traversalMemoEvictions']} peak={peak} "
          f"hits={after['traversalMemoHits']}", flush=True)
    print(f"  delta: evictions +{d_evict}, hits +{d_hits}", flush=True)

    print("\n== VERDICT ==", flush=True)
    if not seen:
        print("  INCONCLUSIVE: the propagation was never observed, so nothing was measured.",
              flush=True)
        sys.exit(1)
    if d_hits == 0:
        print("  INCONCLUSIVE: the memo recorded no hits at all, so this traversal did not"
              "\n  exercise it and the cap was never approached. NOT a pass.", flush=True)
        sys.exit(1)

    headroom = cap - peak
    print(f"  subtree ~{nodes} nodes, spine depth {DEPTH}, branch factor {BRANCH}", flush=True)
    print(f"  largest working set actually needed: {peak} entries", flush=True)
    print(f"  configured cap: {cap} -> headroom {headroom} ({peak / cap:.1%} used)", flush=True)
    if d_evict == 0:
        print("  NO EVICTIONS. The cap did not cost this traversal anything: every ancestor"
              "\n  snapshot it wanted to reuse was still there.", flush=True)
    else:
        print(f"  {d_evict} EVICTIONS — real trees are thrashing the cap. Each eviction is an"
              "\n  ancestor read that has to be repeated. Raise the cap or re-examine the"
              "\n  access pattern.", flush=True)

    print(json.dumps({"nodes": nodes, "depth": DEPTH, "branch": BRANCH,
                      "cap": cap, "peakEntries": peak, "evictions": d_evict,
                      "hits": d_hits, "container": container}, indent=2), flush=True)

    print("\ncleaning up...", flush=True)
    A.post(BR, data={"cmisaction": "deleteTree", "objectId": container}, timeout=3600)
    print("  done", flush=True)


main()
