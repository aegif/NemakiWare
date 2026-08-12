"""基準 2 (隔離) の再判定: リクエストスレッドが他人のサブツリーを走らないこと。

## 何が合格ラインなのか (誤解しやすいので明記する)

**伝播の直列化そのものは設計上そのまま残る。** `ragAclExecutor` は
core=0 / max=1 / queue 256 / CallerRunsPolicy のままで、CLAUDE.md の凍結制約により
構造は変えない。したがって「小さいサブツリーが大きいサブツリーを追い越して速く
収束する」ことは**合格条件ではない**。それを測ると必ず落ちるが、それは仕様どおり。

合格ラインは P1-1 が実際に約束したこと 1 点:

> リクエストスレッドが他人のサブツリーを走ることは、reconcile が配線されており、
> かつキューへの書込みが成功する限り、起きなくなる。

キューが溢れると CallerRunsPolicy が**リクエストスレッド**でタスクを実行する。
P1-1 はそのタスク本体で「自分は呼び出し元スレッドだ」と検出したら**走査を一切せず**
root を reconcile キューへ積んで返る。よって観測すべきは:

1. `inlineRunsOnRequestThreads` (caller-runs が発火した回数) が**増えること**
2. `deferredToReconciliation` (委譲した回数) が**ちょうど同じだけ増えること**

2 が 1 に追随しないなら、リクエストスレッドが実際に走査している (reconcile 未配線か
キュー書込み失敗)。

**判定は `>=` ではなく `==`** (レビュー指摘)。`deferredToReconciliation` は委譲成功時に
のみ増えるので、静穏な JVM なら一致するのが正しい。`>=` を許すと、before スナップ
ショットより前に発火した caller-run の委譲が後から加算された分が、**キュー書込みに
失敗して inline 走査したタスクを覆い隠す**。多い側に振れたら合格ではなく
INCONCLUSIVE (他の伝播が重なっていて差分が帰属できない)。

**応答時間は判定に使わない** (レビュー指摘)。applyACL は submit した時点で返るので、
測れるのは同期キャッシュ退避の時間であって走査の時間ではない。しかも溢れさせる
サブツリーは 2 ノードなので、リクエストスレッドが丸ごと走ってもなお速い。
最初の版はここで big のリクエスト時間と比較して**自信を持って誤った FAIL** を出した。
参考値として出すだけにする。

**このプローブは applyACL 経路だけを見る。** move も同じ executor を使い、そちらには
2026-08-12 まで委譲が**一切無かった** (Codex 指摘)。move 側は
`MovedSubtreeInlineDeferralTest` が実 ThreadPoolExecutor を飽和させて検証する。

## このプローブが「落ちようがない」形にならないための条件

**CallerRuns が一度も発火しなければ、このプローブは何も証明しない。** 溢れなければ
1 も 2 も 0 のままで、素朴に書くと「差分が一致した (0 == 0)」で合格に見える。
そこで発火数 0 は **INCONCLUSIVE として明示的に失敗**させる。

溢れさせるには「1 本の実行中タスク + 256 件の待ち」を超える必要がある。単発の
applyACL は 1 サブツリー = 1 タスクなので、**大きいサブツリーで唯一のワーカーを
占有してから、小さい applyACL を 300 件叩き込む**。

前提: nb33 スタック、admin:admin。他の測定と同時に走らせないこと。
"""
import concurrent.futures
import json
import sys
import threading
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
REPO = "bedroom"
BR = f"{BASE}/browser/{REPO}/root"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

# queue 256 + 1 実行中。定常状態では 258 件目から CallerRuns が発火する。
# 余裕を持って 320 件投げる。
SMALL_FOLDERS = 320
# 唯一のワーカーを占有し続けるための大サブツリー。1 ノードあたり 100ms 前後なので
# 1,200 ノードで約 2 分。小さい方を投げ切るには十分。
BIG_DOCS = 1200
BIG_PER_FOLDER = 40
SEED_WORKERS = 16

BODY = b"isolation probe fixture"


def session():
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    s.mount("http://", requests.adapters.HTTPAdapter(pool_connections=64, pool_maxsize=64))
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
    r = A.get(f"{BASE}/api/v1/admin/search-index/metrics", timeout=120)
    return r.json()


def apply_acl(s, object_id, principal):
    """One applyACL that adds a read ACE, propagating. Returns (seconds, status)."""
    t0 = time.time()
    r = s.post(BR, data={
        "cmisaction": "applyACL", "objectId": object_id,
        "ACLPropagation": "propagate",
        "addACEPrincipal[0]": principal,
        "addACEPermission[0][0]": "cmis:read"}, timeout=1800)
    return time.time() - t0, r.status_code


def build_fixture(tag):
    container = mkfolder(A, f"iso-{tag}", ROOT)

    print(f"building the BIG subtree ({BIG_DOCS} documents)...", flush=True)
    big = mkfolder(A, f"iso-{tag}-big", container)
    big_folders = [mkfolder(A, f"iso-{tag}-big-{i}", big)
                   for i in range((BIG_DOCS + BIG_PER_FOLDER - 1) // BIG_PER_FOLDER)]
    sessions = [session() for _ in range(SEED_WORKERS)]
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=SEED_WORKERS) as ex:
        futures = [ex.submit(mkdoc, sessions[i % SEED_WORKERS],
                             big_folders[i // BIG_PER_FOLDER], f"iso-{tag}-big-{i}")
                   for i in range(BIG_DOCS)]
        made = sum(1 for f in concurrent.futures.as_completed(futures) if not f.exception())
    print(f"  {made} documents in {time.time()-t0:.0f}s", flush=True)
    # A short fixture means the big traversal may not occupy the worker long enough, and the
    # probe would report INCONCLUSIVE for a reason that has nothing to do with the claim.
    if made != BIG_DOCS:
        sys.exit(f"fixture incomplete: {made} of {BIG_DOCS} big documents were created")

    print(f"building {SMALL_FOLDERS} SMALL folders (one document each)...", flush=True)
    smalls = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=SEED_WORKERS) as ex:
        futs = {ex.submit(mkfolder, sessions[i % SEED_WORKERS], f"iso-{tag}-s{i}", container): i
                for i in range(SMALL_FOLDERS)}
        for f in concurrent.futures.as_completed(futs):
            smalls.append(f.result())
    with concurrent.futures.ThreadPoolExecutor(max_workers=SEED_WORKERS) as ex:
        futs = [ex.submit(mkdoc, sessions[i % SEED_WORKERS], fid, f"iso-{tag}-s{i}-doc")
                for i, fid in enumerate(smalls)]
        # .result() so a failed document raises here instead of leaving an EMPTY folder that
        # silently makes the subtree nothing to traverse.
        for f in concurrent.futures.as_completed(futs):
            f.result()
    if len(smalls) != SMALL_FOLDERS:
        sys.exit(f"fixture incomplete: {len(smalls)} of {SMALL_FOLDERS} small folders")
    print(f"  {len(smalls)} folders ready", flush=True)
    return container, big, smalls


def main():
    tag = uuid.uuid4().hex[:4]
    principal = "admin"

    container, big, smalls = build_fixture(tag)

    before = metrics()
    print(f"\nmetrics before: inlineRuns={before['inlineRunsOnRequestThreads']} "
          f"deferred={before['deferredToReconciliation']}", flush=True)

    print("\n== firing the BIG applyACL to occupy the single worker ==", flush=True)
    big_result = {}
    big_thread = threading.Thread(
        target=lambda: big_result.update(zip(("seconds", "status"),
                                             apply_acl(session(), big, principal))))
    big_thread.start()
    time.sleep(3)  # let the big traversal claim the worker

    print(f"== flooding {len(smalls)} small applyACL calls to overflow the queue ==", flush=True)
    latencies = []
    lock = threading.Lock()

    def small(fid):
        secs, status = apply_acl(session(), fid, principal)
        with lock:
            latencies.append((secs, status))

    t0 = time.time()
    transport_errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as ex:
        futs = [ex.submit(small, f) for f in smalls]
        for f in concurrent.futures.as_completed(futs):
            # Without .result() an exception stays inside the future: no latency is recorded, no
            # status is recorded, and the verdict never learns the call failed at all.
            try:
                f.result()
            except Exception as e:
                transport_errors.append(repr(e)[:120])
    flood_seconds = time.time() - t0
    print(f"  all {len(latencies)} small applyACL returned in {flood_seconds:.1f}s", flush=True)

    big_thread.join()
    print(f"  big applyACL returned in {big_result.get('seconds', -1):.1f}s "
          f"(status {big_result.get('status')})", flush=True)

    after = metrics()
    d_inline = after["inlineRunsOnRequestThreads"] - before["inlineRunsOnRequestThreads"]
    d_defer = after["deferredToReconciliation"] - before["deferredToReconciliation"]

    oks = [s for s, st in latencies if 200 <= st < 300]
    oks.sort()
    fails = [st for _, st in latencies if not (200 <= st < 300)]

    print(f"\nmetrics after: inlineRuns={after['inlineRunsOnRequestThreads']} "
          f"deferred={after['deferredToReconciliation']}", flush=True)
    print(f"  delta: caller-runs fired {d_inline}x, deferred to reconciliation {d_defer}x",
          flush=True)
    if oks:
        print(f"  small applyACL latency: min {oks[0]:.2f}s  median {oks[len(oks)//2]:.2f}s  "
              f"p95 {oks[int(len(oks)*0.95)]:.2f}s  max {oks[-1]:.2f}s", flush=True)
    if fails:
        print(f"  !! {len(fails)} non-2xx responses: {sorted(set(fails))}", flush=True)

    print("\n== VERDICT ==", flush=True)
    verdict_ok = True

    # Codex review: the counters are per-JVM CUMULATIVE, so a delta is only attributable to this
    # probe if nothing else was propagating. A restart between snapshots makes the delta negative,
    # and "negative >= negative" used to sail through the comparison below.
    if d_inline < 0 or d_defer < 0:
        print(f"  INCONCLUSIVE: a counter went BACKWARDS (inline {d_inline}, deferred {d_defer})."
              "\n  The JVM restarted between snapshots, or the two reads hit different replicas."
              "\n  Nothing can be concluded from these deltas.", flush=True)
        sys.exit(1)

    # The probe is worthless if the queue never overflowed. Say so instead of passing.
    if d_inline == 0:
        print("  INCONCLUSIVE: CallerRunsPolicy never fired, so the isolation claim was never"
              "\n  exercised. The queue (256) did not overflow — the big subtree probably"
              "\n  finished before the flood, or the flood was too small. Increase BIG_DOCS or"
              "\n  SMALL_FOLDERS and re-run. This is NOT a pass.", flush=True)
        sys.exit(1)

    print(f"  caller-runs fired {d_inline}x — the claim was exercised.", flush=True)

    # EQUALITY, not >=. Codex review: `deferredToReconciliation` is incremented only by a
    # successful deferral, so on a quiescent JVM the two deltas must match exactly. Accepting
    # `>=` let a surplus deferral (one whose inline event predated the "before" snapshot) mask a
    # probe task that failed its durable write and walked the subtree inline.
    if d_defer == d_inline:
        print(f"  every one of them was deferred ({d_defer} == {d_inline}): no request thread"
              "\n  ran a subtree traversal.", flush=True)
    elif d_defer < d_inline:
        print(f"  FAIL: {d_inline - d_defer} caller-runs were NOT deferred. Those request threads"
              "\n  walked the subtree themselves — either reconcile is unwired or the"
              "\n  durable-queue write failed (it falls back to inline on purpose).", flush=True)
        verdict_ok = False
    else:
        print(f"  INCONCLUSIVE: more deferrals ({d_defer}) than caller-runs ({d_inline}). Some"
              "\n  other propagation overlapped this run, so the deltas are not attributable"
              "\n  to it. Re-run on a quiescent server.", flush=True)
        sys.exit(1)

    # Latency is reported, NOT judged. Codex review: the earlier check compared the slowest small
    # request against the BIG request's duration — but applyACL returns after submitting, so that
    # number is the synchronous cache-eviction walk, not the traversal. Worse, each small subtree
    # is two nodes, so a request thread could walk one entirely and still look fast. Latency
    # cannot discriminate here; pretending otherwise produced a confident wrong FAIL.
    if oks:
        print(f"\n  (context only) small applyACL latency: median {oks[len(oks)//2]:.2f}s "
              f"max {oks[-1]:.2f}s; big request {big_result.get('seconds', 0):.2f}s."
              "\n  These are request times, not traversal times, and the flooded subtrees are"
              "\n  two nodes each — too small for latency to reveal an inline walk. The counters"
              "\n  above are the evidence.", flush=True)

    if fails or transport_errors:
        print(f"\n  FAIL: {len(fails)} non-2xx and {len(transport_errors)} transport errors among"
              f" the {len(smalls)} small calls.", flush=True)
        for e in transport_errors[:3]:
            print(f"    {e}", flush=True)
        verdict_ok = False
    if len(latencies) != len(smalls):
        print(f"  FAIL: only {len(latencies)} of {len(smalls)} small calls recorded a result.",
              flush=True)
        verdict_ok = False
    if not (200 <= (big_result.get("status") or 0) < 300):
        print(f"  FAIL: the big applyACL returned {big_result.get('status')}, so the worker may"
              "\n  never have been occupied and the overflow may have had another cause.",
              flush=True)
        verdict_ok = False

    print("\n  NOTE: this probe covers the applyACL submit site only. The move submit site shares"
          "\n  the same executor and had NO deferral at all until 2026-08-12; it is covered by"
          "\n  MovedSubtreeInlineDeferralTest, which saturates a real ThreadPoolExecutor.",
          flush=True)

    print(json.dumps({"callerRunsFired": d_inline, "deferred": d_defer,
                      "smallMedianSeconds": round(oks[len(oks)//2], 3) if oks else None,
                      "smallMaxSeconds": round(oks[-1], 3) if oks else None,
                      "bigRequestSeconds": round(big_result.get("seconds", 0), 2),
                      "container": container}, indent=2), flush=True)

    print("\ncleaning up the fixture...", flush=True)
    try:
        A.post(BR, data={"cmisaction": "deleteTree", "objectId": container}, timeout=3600)
        print("  done", flush=True)
    except Exception as e:
        print(f"  cleanup failed ({e}); delete iso-{tag} ({container}) by hand", flush=True)

    sys.exit(0 if verdict_ok else 1)


main()
