"""RAG 剥奪が **別レプリカ**にも届くか — 台帳 R1 の最後の残差。

単一 JVM 内では、剥奪は live-ACL ゲートで即座に落ちることを実測済み
(`rag_revocation_seed.py` / `rag_revocation_paths.py`)。しかしそのゲートは
**その JVM のキャッシュに載った ACL** を見る。ACL 変更を行っていないレプリカは、
自分が計算した答えを memoise したまま持ちうる — これが R1 に残っていた
cross-replica の懸念で、単一レプリカ環境では原理的に測れなかった。

構成: core (8080) と core2 (8090) が **同一の CouchDB と Solr** を見る。
両者は独立した JVM で、キャッシュを共有しない。

測るのは 3 つ。**いずれも「A で剥奪して B で確認する」**という向き:

  X1  ACE 剥奪:      A で ACE を外す → B の seed / 検索から消えるまで
  X2  グループ剥奪:  A で membership を外す → 同上 (principal 世代を跨ぐ)
  X3  持続:          消えたあと 30 秒戻らない (遅れて来た再索引やキャッシュ再充填で
                     復活しないこと)

**陽性対照が特に重要**: 剥奪前に **B 側で** seed / 検索できていること。
B が最初から読めていないなら「B で読めない」は何も証明しない
(索引の伝播待ちや、そもそも B がその文書を知らないだけかもしれない)。
剥奪は A に投げ、確認は B に投げる — 経路を跨がせるのがこの計測の全部なので、
セッションを取り違えると意味が消える。ここでは A 用・B 用のセッションを
別オブジェクトにして、**確認系は必ず B 用しか使わない**。

拒否と「答えが得られなかった」は混ぜない (429 / 5xx / 通信断は unknown)。
理由は他のプローブと同じ。

前提: nb33 + TEI、`core2` を 8090 に立てておくこと。override は本体 compose から
派生生成する (手書きで置くと `-D` が片方だけ増えて「設定の違う 2 台」になり、
測っているものが静かに変わるため):

    python3 tools/acl-probe/make_replica2_compose.py
    docker compose -p nb33 -f docker/docker-compose-simple.yml \\
        -f /tmp/nb33-replica2.yml --profile rag up -d core2
"""
import json
import sys
import time
import uuid

import requests

A_BASE = "http://localhost:8080/core"   # writes go here
B_BASE = "http://localhost:8090/core"   # reads are checked here
ROOT = "e02f784f8360a02cc14d1314c10038ff"

T = uuid.uuid4().hex[:5]
GRP = f"xgrp{T}"
USER = f"xusr{T}"
PW = "Mx!" + T
DOCS = 2
WINDOW = 180.0
SEED_POLL = 0.25
SEARCH_POLL = 0.55
SEARCH_TOPK = 100
SEARCH_QUERY = ("The northern division reported growth while the southern division"
                " contracted. Margins narrowed on freight costs.")

KEEP = "--keep" in sys.argv
ALLOWED, DENIED, UNKNOWN = "allowed", "denied", "unknown"
rate_limited = 0
findings = []
folder = None


def admin(base):
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    s.base = base
    return s


def user(base):
    s = requests.Session()
    s.auth = (USER, PW)
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    s.base = base
    return s


AA, AB = admin(A_BASE), admin(B_BASE)


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def get(s, path):
    """GET against THIS session's replica, retrying past rate limiting."""
    global rate_limited
    delay = 0.5
    for _ in range(6):
        try:
            r = s.get(s.base + path, timeout=60)
        except Exception:
            return None
        if r.status_code != 429:
            return r
        rate_limited += 1
        time.sleep(delay)
        delay = min(delay * 2, 4.0)
    return None


def seed_state(s, doc_id):
    r = get(s, f"/api/v1/cmis/repositories/bedroom/rag/similar/{doc_id}?topK=5&minScore=0.0")
    if r is None:
        return UNKNOWN
    if r.status_code == 200:
        return ALLOWED
    if r.status_code in (403, 404):
        return DENIED
    return UNKNOWN


def search_count(s, only_ids):
    r = get(s, "/api/v1/cmis/repositories/bedroom/rag/search"
               f"?q={requests.utils.quote(SEARCH_QUERY)}&topK={SEARCH_TOPK}&minScore=0.0")
    if r is None or r.status_code != 200:
        return None
    wanted = set(only_ids)
    return sum(1 for h in (r.json().get("results") or []) if h.get("documentId") in wanted)


def wait_for(predicate, timeout, poll=SEED_POLL, t0=None):
    start = t0 if t0 is not None else time.time()
    while time.time() - start < timeout:
        if predicate() is True:
            return round(time.time() - start, 3)
        time.sleep(poll)
    return -1


def stays_revoked(session, seed, doc_ids, seconds=30):
    """Watch for `seconds` and report whether access came back, or whether we could not tell.

    An UNKNOWN seed or an unanswered search is NOT evidence of continued denial. Counting
    those as "still revoked" is how a probe reports a clean bill of health for 30 seconds of
    5xx or rate limiting — the exact confusion this file refuses to make elsewhere. So a
    round that produced no usable answer is tracked, and a window with no confirmed denial
    at all fails instead of passing.
    """
    confirmed = 0
    unanswered = 0
    t0 = time.time()
    while time.time() - t0 < seconds:
        st = seed_state(session, seed)
        hits = search_count(session, doc_ids)
        if st == ALLOWED or (hits is not None and hits != 0):
            return False, f"came back after {round(time.time() - t0, 1)}s (seed={st} hits={hits})"
        if st == DENIED and hits == 0:
            confirmed += 1
        else:
            unanswered += 1
        time.sleep(1.0)
    if confirmed == 0:
        return False, (f"no round in {seconds}s produced a usable answer "
                       f"({unanswered} unanswered) — this is not evidence of denial")
    return True, f"no reappearance ({confirmed} confirmed / {unanswered} unanswered rounds)"


def apply_acl(object_id, principals, break_inheritance=True):
    """Always through replica A. The whole question is whether B notices."""
    return AA.post(f"{A_BASE}/rest/repo/bedroom/node/{object_id}/acl", json={
        "acl": {"permissions": [{"principalId": p, "permissions": ["cmis:read"], "direct": True}
                                for p in principals]},
        "breakInheritance": bool(break_inheritance)}, timeout=120)


def record(name, ok, detail):
    findings.append((name, "OK" if ok else "FAIL", detail))
    print(f"{'OK  ' if ok else 'FAIL'} {name} — {detail}", flush=True)


def cleanup():
    if KEEP or folder is None:
        print(f"\n== fixture kept ==\nfolder={folder} group={GRP} user={USER}", flush=True)
        return
    print("\n== cleanup ==", flush=True)
    try:
        AA.post(f"{A_BASE}/browser/bedroom/root",
                data={"cmisaction": "deleteTree", "objectId": folder}, timeout=300)
    except Exception as e:
        print(f"cleanup: folder {folder} ({e})", flush=True)
    for path in (f"group/delete/{GRP}", f"user/delete/{USER}"):
        try:
            AA.delete(f"{A_BASE}/rest/repo/bedroom/{path}", timeout=180)
        except Exception as e:
            print(f"cleanup: {path} ({e})", flush=True)
    print("fixture removed", flush=True)


def main():
    global folder

    for label, s in (("A(8080)", AA), ("B(8090)", AB)):
        h = get(s, "/api/v1/cmis/repositories/bedroom/rag/health")
        if h is None or h.status_code != 200 or not h.json().get("enabled"):
            raise SystemExit(f"{label}: RAG not enabled/healthy — start core2 first")
        print(f"{label}: rag enabled", flush=True)

    print("\n== fixture (created on A) ==", flush=True)
    AA.post(f"{A_BASE}/rest/repo/bedroom/group/create/{GRP}", data={"name": GRP}, timeout=120)
    AA.post(f"{A_BASE}/rest/repo/bedroom/user/create/{USER}",
            data={"name": USER, "password": PW, "firstName": "x", "lastName": USER,
                  "email": f"{USER}@x.test"}, timeout=120)
    AA.put(f"{A_BASE}/rest/repo/bedroom/group/add/{GRP}",
           data={"users": json.dumps([USER])}, timeout=120)
    UB = user(B_BASE)   # the subject, ALWAYS read through replica B

    folder = oid(AA.post(f"{A_BASE}/browser/bedroom/root", data={
        "cmisaction": "createFolder", "objectId": ROOT,
        "propertyId[0]": "cmis:name", "propertyValue[0]": f"xrep-{T}",
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=120).json())
    apply_acl(folder, [GRP, "admin"])

    body = ("confidential quarterly revenue analysis. " + SEARCH_QUERY).encode()
    doc_ids = []
    for i in range(DOCS):
        doc_ids.append(oid(AA.post(
            f"{A_BASE}/browser/bedroom/root",
            files={"content": (f"xrep{T}-{i}.txt", body, "text/plain")},
            data={"cmisaction": "createDocument", "objectId": folder,
                  "propertyId[0]": "cmis:name", "propertyValue[0]": f"xrep{T}-{i}.txt",
                  "propertyId[1]": "cmis:objectTypeId",
                  "propertyValue[1]": "cmis:document"}, timeout=180).json()))
    seed = doc_ids[0]
    print(f"folder={folder} docs={doc_ids}", flush=True)

    print("\n== positive control: the subject can read THROUGH B before anything is revoked ==",
          flush=True)
    b_seed = wait_for(lambda: seed_state(UB, seed) == ALLOWED, 300)
    b_search = wait_for(lambda: search_count(UB, doc_ids) == DOCS, 300, SEARCH_POLL)
    print(f"B_seed_allowed_after_s={b_seed} B_search_all_after_s={b_search}", flush=True)
    if b_seed < 0 or b_search < 0:
        raise SystemExit("the subject never gained access on replica B, so 'B denies it' below "
                         "would be indistinguishable from 'B never had it'")

    # ---- X1: revoke the ACE on A, observe on B --------------------------------------
    print("\n== X1: ACE を A で剥奪 → B で確認 ==", flush=True)
    t0 = time.time()
    apply_acl(folder, ["admin"])
    x1_seed = wait_for(lambda: seed_state(UB, seed) == DENIED, WINDOW, t0=t0)
    x1_search = wait_for(lambda: search_count(UB, doc_ids) == 0, WINDOW, SEARCH_POLL, t0=t0)
    record("X1 ACE 剥奪が別レプリカに届く", x1_seed >= 0 and x1_search >= 0,
           f"B_seed_denied_s={x1_seed} B_search_zero_s={x1_search}"
           " (clock starts before the write on A)")

    stayed, breach = stays_revoked(UB, seed, doc_ids)
    record("X3 (ACE) 剥奪が B 側で 30 秒戻らない", stayed, breach)

    # ---- X2: regrant, then revoke by group membership --------------------------------
    print("\n== X2: グループ membership を A で剥奪 → B で確認 ==", flush=True)
    apply_acl(folder, [GRP, "admin"])
    re_seed = wait_for(lambda: seed_state(UB, seed) == ALLOWED, 300)
    re_search = wait_for(lambda: search_count(UB, doc_ids) == DOCS, 300, SEARCH_POLL)
    print(f"B_regrant_seed_s={re_seed} B_regrant_search_s={re_search}", flush=True)
    if re_seed < 0 or re_search < 0:
        record("X2 グループ剥奪が別レプリカに届く", False,
               f"could not restore access on B (seed={re_seed} search={re_search}) — X2 would "
               "measure an already-denied state")
    else:
        t0 = time.time()
        AA.put(f"{A_BASE}/rest/repo/bedroom/group/remove/{GRP}",
               data={"users": json.dumps([USER])}, timeout=120)
        x2_seed = wait_for(lambda: seed_state(UB, seed) == DENIED, WINDOW, t0=t0)
        x2_search = wait_for(lambda: search_count(UB, doc_ids) == 0, WINDOW, SEARCH_POLL, t0=t0)
        record("X2 グループ剥奪が別レプリカに届く", x2_seed >= 0 and x2_search >= 0,
               f"B_seed_denied_s={x2_seed} B_search_zero_s={x2_search}")
        # X3 for the group path too. It ran only after the ACE revocation before, so the
        # slower and more cache-dependent of the two paths was never checked for persistence.
        g_stayed, g_breach = stays_revoked(UB, seed, doc_ids)
        record("X3 (グループ) 剥奪が B 側で 30 秒戻らない", g_stayed, g_breach)

    # ---- observer control: the fixture still exists, seen from B ---------------------
    obs = wait_for(lambda: seed_state(AB, seed) == ALLOWED, 30)
    record("観測用 admin は B 側で最後まで読める (fixture が消えたのではない)", obs >= 0,
           f"observer_allowed_after_s={obs}")

    print("\n== SUMMARY ==", flush=True)
    for name, verdict, detail in findings:
        print(f"{verdict:4s} {name} — {detail}", flush=True)
    print(f"\nrate_limited_retries={rate_limited}", flush=True)
    bad = [f for f in findings if f[1] == "FAIL"]
    if bad:
        raise SystemExit(f"{len(bad)} cross-replica failure(s)")
    print("\nOK: a revocation on one replica is enforced by the other.", flush=True)


try:
    main()
finally:
    cleanup()
