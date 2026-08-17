"""ACL 伝播 1 ノードあたりの CouchDB 読み取り回数 (台帳 A3 の裏取り)。

台帳 A3 は「1 ノードあたり **2×(1+祖先数) 回の非キャッシュ CouchDB GET**
(snapshot と revalidate が独立に祖先チェーンを全読み)」としている。設計 §4.6 は
**snapshot 側だけ**「1 traversal 内の祖先チェーン再利用」を許可しており
(revalidation の祖先再読は fence の正しさの根拠なので削減対象外)、P1-2 はその
未実装分を埋める話になっている。

**実装に手を入れる前に、その見積りが実際に出ているかを測る。** ここは ACL の
正しさの中枢で、リリース直前に性能目的で触る場所としては最も危ないので、
「2 倍速くなるはず」ではなく「今いくら払っているか」を先に確定させる。

測り方: 深さ D の鎖の末端に N 文書を置き、鎖の頂点に applyAcl を打ち、
`AclEffectiveEpochService` の authoritativeReads カウンタ差分を触ったノード数で割る。

読み方と限界:

- 使うのは **`AclEffectiveEpochService` の authoritativeReads カウンタ** (JVM ごと)。
  最初は CouchDB の `database_reads` を使ったが**あれは失敗**だった: サーバ全体の
  カウンタなので、アイドルの開発スタックでもスケジューラ由来で毎秒 6 件ほど動く一方、
  26 ノードの伝播は 300 秒の収束の中に 185 件が散るだけで、**ドリフト推定が生の差分を
  上回って負の値が出た**。呼び出し地点で数えれば引き算自体が要らない。
  アイドル時の同カウンタは対照として出す (大きければその行は無効)。
- 祖先数の異なる 2 通りを測る。1 ノードあたりの読みが祖先数に比例して増えるなら
  台帳の 2×(1+祖先数) と整合し、増えないなら別の要因が支配的ということになる。
- 伝播は非同期。収束を「読み取りが止まったこと」で判定する (件数が一定になるまで待つ)。

前提: nb33、admin:admin、CouchDB admin:password。他の測定と同時に走らせないこと。
"""
import json
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
COUCH = "http://localhost:5984"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

T = uuid.uuid4().hex[:5]
DOCS = 20
IDLE_SAMPLE_S = 20
SETTLE_QUIET_S = 15
SETTLE_MAX_S = 300

A = requests.Session()
A.auth = ("admin", "admin")
A.headers["X-Requested-With"] = "XMLHttpRequest"
C = requests.Session()
C.auth = ("admin", "password")

KEEP = "--keep" in sys.argv
created = []


def reads():
    """Authoritative-walk reads, counted at the call site inside AclEffectiveEpochService.

    NOT CouchDB's server-wide database_reads. That was the first attempt and it does not work:
    on an idle dev stack the counter still moves at ~6 reads/s from schedulers, while one ACL
    propagation over 26 nodes is ~185 reads spread over a 300s settle. The background swamps
    the signal — the drift estimate came out LARGER than the raw delta and the result went
    negative. A counter on the exact call site has no such problem and needs no subtraction.
    """
    r = A.get(f"{BASE}/api/v1/admin/acl-epoch/quarantine", timeout=60)
    return int(r.json()["authoritativeReads"])


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def mkfolder(parent, name):
    f = oid(A.post(BR, data={
        "cmisaction": "createFolder", "objectId": parent,
        "propertyId[0]": "cmis:name", "propertyValue[0]": name,
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=120).json())
    return f


def mkdoc(parent, name):
    return oid(A.post(BR, files={"content": (f"{name}.txt", b"acl propagation cost probe",
                                             "text/plain")},
                      data={"cmisaction": "createDocument", "objectId": parent,
                            "propertyId[0]": "cmis:name", "propertyValue[0]": f"{name}.txt",
                            "propertyId[1]": "cmis:objectTypeId",
                            "propertyValue[1]": "cmis:document"}, timeout=180).json())


def apply_acl(object_id, principals):
    return A.post(f"{BASE}/rest/repo/bedroom/node/{object_id}/acl", json={
        "acl": {"permissions": [{"principalId": p, "permissions": ["cmis:read"], "direct": True}
                                for p in principals]},
        "breakInheritance": True}, timeout=180)


def settle():
    """Wait until the read counter stops moving; returns (seconds waited, reads consumed)."""
    t0 = time.time()
    last = reads()
    quiet_since = None
    consumed = 0
    while time.time() - t0 < SETTLE_MAX_S:
        time.sleep(2)
        now = reads()
        delta = now - last
        consumed += delta
        last = now
        if delta == 0:
            if quiet_since is None:
                quiet_since = time.time()
            elif time.time() - quiet_since >= SETTLE_QUIET_S:
                return round(time.time() - t0, 1), consumed
        else:
            quiet_since = None
    return round(time.time() - t0, 1), consumed


def measure(depth):
    """Build a chain of `depth` folders with DOCS documents at the tip; return reads per node."""
    top = mkfolder(ROOT, f"a3-d{depth}-{T}")
    created.append(top)
    node = top
    for i in range(depth - 1):
        node = mkfolder(node, f"a3-d{depth}-{T}-lvl{i}")
    docs = [mkdoc(node, f"a3-d{depth}-{T}-{i}") for i in range(DOCS)]
    print(f"  depth {depth}: chain built, {len(docs)} documents at the tip", flush=True)

    # Let creation-driven indexing finish before measuring anything.
    settle()

    idle_before = reads()
    time.sleep(IDLE_SAMPLE_S)
    idle_drift = reads() - idle_before
    # Kept as a control, not a correction: this counter should be ~flat when nothing is
    # propagating. A large value here means something else is walking, and the per-node figure
    # below would be inflated by it.
    print(f"  idle drift over {IDLE_SAMPLE_S}s: {idle_drift} walk reads "
          f"(should be ~0; anything large invalidates the row below)", flush=True)

    before = reads()
    t0 = time.time()
    apply_acl(top, [f"a3grp{T}", "admin"])
    write_s = round(time.time() - t0, 2)
    settled_s, _ = settle()
    total = reads() - before
    nodes = depth + DOCS  # the folders in the chain plus the documents
    print(f"  applyAcl returned in {write_s}s; walk reads settled after {settled_s}s", flush=True)
    print(f"  walk reads: {total}", flush=True)
    print(f"  nodes touched: {nodes} ({depth} folders + {DOCS} documents)", flush=True)
    print(f"  => {total / nodes:.1f} reads per node", flush=True)
    return depth, nodes, total, idle_drift, round(total / nodes, 1)


def cleanup():
    if KEEP:
        print(f"\n== fixture kept ==\n{created}", flush=True)
        return
    print("\n== cleanup ==", flush=True)
    for f in created:
        try:
            A.post(BR, data={"cmisaction": "deleteTree", "objectId": f}, timeout=300)
        except Exception as e:
            print(f"cleanup: {f} ({e})", flush=True)
    print("fixture removed", flush=True)


def main():
    print("== ACL propagation read cost ==", flush=True)
    print("Counts authoritativeReads (the walk's own call site), not CouchDB's server-wide"
          "\ndatabase_reads — see the module docstring for why the latter cannot measure this.",
          flush=True)
    rows = []
    for depth in (2, 6):
        print(f"\n-- chain depth {depth} --", flush=True)
        rows.append(measure(depth))

    print("\n== SUMMARY ==", flush=True)
    print(f"{'depth':>6} | {'nodes':>6} | {'walk reads':>11} | {'idle ctrl':>9} | {'per node':>9}",
          flush=True)
    for depth, nodes, total, drift, per in rows:
        print(f"{depth:>6} | {nodes:>6} | {total:>11} | {drift:>9} | {per:>9}", flush=True)

    if len(rows) == 2:
        shallow, deep = rows[0], rows[-1]
        print(f"\nDepth {shallow[0]} -> {deep[0]}: {shallow[4]} -> {deep[4]} reads per node.",
              flush=True)
        print("The ledger's model is 2x(1+ancestors) uncached GETs per node, so a deeper chain"
              "\nshould cost proportionally more per node. If the two numbers are close, the"
              "\nancestor walk is NOT what dominates and A3's estimate needs revisiting before"
              "\nanyone optimises the ACL core for it.", flush=True)


try:
    main()
finally:
    cleanup()
