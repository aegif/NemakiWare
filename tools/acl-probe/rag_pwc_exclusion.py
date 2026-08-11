"""PWC (private working copy) が RAG に載らないこと — 台帳 R1 の ownership 残差。

R1 は「判定が PermissionServiceImpl ではなく**トークン交差**なので ownership の
意味論差が残る」としていた。コードを読むと ownership が効くのは **PWC だけ**:

  PermissionServiceImpl.checkPermissionInternal
      PWC は VersionSeries.getVersionSeriesCheckedOutBy() と一致する本人のみ許可。
      **通常の継承 ACL は見ない**（PWC の ACL は空のことが多いので ownership に倒す）。

一方 RAG は継承 ACL 由来の reader トークンの交差で判定するので、この規則を知らない。
**PWC が索引されていると、同じグループの非オーナーが「作業中の草稿」を
findSimilarDocuments の種に使える** — 存在と意味的近傍を引き出せる oracle になる。

`RAGIndexingServiceImpl.indexDocument` は入口で PWC を弾き、過去のビルドが入れた
ブロックがあれば purge する。この計測はその除外が**実機で効いているか**を見る。

確認するのは 4 点:

  E1  PWC はオーナー自身にも seed できない (索引されていない)
  E2  PWC は同一グループの非オーナーにも seed できない ← **これが漏れの本体**
  E3  除外は「全部消える」ことではない: チェックアウト中も**元の文書**は seed できる
      (陽性対照。E1/E2 が「fixture ごと消えた」で通るのを防ぐ)
  E4  チェックイン後は新版が索引に戻る (除外が恒久的な機能喪失になっていない)

E3 が特に要る。除外を「全部消す」で実装しても E1/E2 は通ってしまうので、
何が残っているべきかを同時に主張しないと、この検査は劣化を検出できない。

前提: nb33 + TEI (`--profile rag`)、admin:admin。終了時に fixture は自動削除。
"""
import json
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
REST = f"{BASE}/rest/repo/bedroom"
API = f"{BASE}/api/v1/cmis/repositories/bedroom"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

T = uuid.uuid4().hex[:5]
GRP = f"pwcg{T}"
OWNER = f"pwco{T}"
PEER = f"pwcp{T}"
PW = "Mx!" + T

A = requests.Session()
A.auth = ("admin", "admin")
A.headers["X-Requested-With"] = "XMLHttpRequest"

ALLOWED, DENIED, UNKNOWN = "allowed", "denied", "unknown"
KEEP = "--keep" in sys.argv
created = {"folders": [], "users": [], "groups": []}
findings = []

BODY = ("The northern division reported growth while the southern division contracted. "
        "Margins narrowed on freight costs across the quarterly revenue analysis.")


def sess(user):
    s = requests.Session()
    s.auth = (user, PW)
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    return s


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def seed_state(s, doc_id):
    """allowed / denied / unknown. A 429 or 5xx is the ABSENCE of an answer, never a denial."""
    delay = 0.5
    for _ in range(6):
        try:
            r = s.get(f"{API}/rag/similar/{doc_id}?topK=5&minScore=0.0", timeout=60)
        except Exception:
            return UNKNOWN
        if r.status_code == 429:
            time.sleep(delay)
            delay = min(delay * 2, 4.0)
            continue
        if r.status_code == 200:
            return ALLOWED
        if r.status_code in (403, 404):
            return DENIED
        return UNKNOWN
    return UNKNOWN


def wait_for(predicate, timeout, poll=0.25):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if predicate() is True:
            return round(time.time() - t0, 3)
        time.sleep(poll)
    return -1


def record(name, ok, detail):
    findings.append((name, "OK" if ok else "FAIL", detail))
    print(f"{'OK  ' if ok else 'FAIL'} {name} — {detail}", flush=True)


def cleanup():
    if KEEP:
        print(f"\n== fixture kept ==\n{json.dumps(created, indent=2)}", flush=True)
        return
    print("\n== cleanup ==", flush=True)
    for f in created["folders"]:
        try:
            A.post(BR, data={"cmisaction": "deleteTree", "objectId": f}, timeout=300)
        except Exception as e:
            print(f"cleanup: folder {f} ({e})", flush=True)
    for g in created["groups"]:
        try:
            A.delete(f"{REST}/group/delete/{g}", timeout=120)
        except Exception as e:
            print(f"cleanup: group {g} ({e})", flush=True)
    for u in created["users"]:
        try:
            A.delete(f"{REST}/user/delete/{u}", timeout=120)
        except Exception as e:
            print(f"cleanup: user {u} ({e})", flush=True)
    print("fixture removed", flush=True)


def main():
    h = A.get(f"{API}/rag/health", timeout=60).json()
    if not h.get("enabled") or h.get("status") in ("unavailable", None):
        raise SystemExit("RAG is not enabled/healthy")

    print("== fixture ==", flush=True)
    A.post(f"{REST}/group/create/{GRP}", data={"name": GRP}, timeout=120)
    created["groups"].append(GRP)
    for u in (OWNER, PEER):
        A.post(f"{REST}/user/create/{u}",
               data={"name": u, "password": PW, "firstName": "p", "lastName": u,
                     "email": f"{u}@x.test"}, timeout=120)
        created["users"].append(u)
    A.put(f"{REST}/group/add/{GRP}", data={"users": json.dumps([OWNER, PEER])}, timeout=120)
    s_owner, s_peer = sess(OWNER), sess(PEER)

    folder = oid(A.post(BR, data={
        "cmisaction": "createFolder", "objectId": ROOT,
        "propertyId[0]": "cmis:name", "propertyValue[0]": f"pwcprobe-{T}",
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=120).json())
    created["folders"].append(folder)
    # Both users are in the group, and the group can read: so a PWC leak here is exactly the
    # "same-group non-owner reads the draft" case, not a missing grant.
    A.post(f"{BASE}/rest/repo/bedroom/node/{folder}/acl", json={
        "acl": {"permissions": [
            {"principalId": GRP, "permissions": ["cmis:read", "cmis:write"], "direct": True},
            {"principalId": "admin", "permissions": ["cmis:read"], "direct": True}]},
        "breakInheritance": True}, timeout=120)

    doc = oid(A.post(BR, files={"content": (f"pwc{T}.txt", BODY.encode(), "text/plain")},
                     data={"cmisaction": "createDocument", "objectId": folder,
                           "propertyId[0]": "cmis:name", "propertyValue[0]": f"pwc{T}.txt",
                           "propertyId[1]": "cmis:objectTypeId",
                           "propertyValue[1]": "cmis:document"}, timeout=180).json())
    print(f"folder={folder} doc={doc}", flush=True)

    if wait_for(lambda: seed_state(A, doc) == ALLOWED, 300) < 0:
        raise SystemExit("the original document never got indexed — nothing below would mean "
                         "anything")
    print("original indexed", flush=True)

    print("\n== checkout ==", flush=True)
    r = s_owner.post(BR, data={"cmisaction": "checkOut", "objectId": doc}, timeout=180)
    if r.status_code != 200:
        raise SystemExit(f"checkOut failed ({r.status_code}): {r.text[:300]}")
    pwc = oid(r.json())
    print(f"pwc={pwc}", flush=True)
    if pwc == doc:
        raise SystemExit("checkOut returned the same id — no PWC was created, so there is "
                         "nothing to test")

    # Give any indexing that WOULD happen a chance to happen. Asserting "not indexed" one
    # millisecond after checkout would pass against a build that indexes PWCs slowly.
    time.sleep(15)

    owner_seed = seed_state(s_owner, pwc)
    peer_seed = seed_state(s_peer, pwc)
    admin_seed = seed_state(A, pwc)
    record("E1 PWC はオーナー自身にも seed できない", owner_seed == DENIED,
           f"owner={owner_seed} (admin={admin_seed})")
    record("E2 PWC は同一グループの非オーナーにも seed できない", peer_seed == DENIED,
           f"peer={peer_seed} — this is the seed-oracle the exclusion exists to prevent")

    # E3: the exclusion must be an exclusion, not a deletion. If checking a document out
    # removed the ORIGINAL from the index, E1/E2 would pass for the wrong reason.
    original_still = seed_state(s_peer, doc)
    record("E3 チェックアウト中も元の文書は seed できる (陽性対照)",
           original_still == ALLOWED,
           f"original_as_peer={original_still} — if this is denied, E1/E2 prove nothing:"
           " they would also hold for a build that simply dropped the fixture")

    print("\n== checkin ==", flush=True)
    r = s_owner.post(BR, files={"content": (f"pwc{T}.txt", (BODY + " Updated after checkin.")
                                            .encode(), "text/plain")},
                     data={"cmisaction": "checkIn", "objectId": pwc, "major": "true",
                           "checkinComment": "probe"}, timeout=180)
    if r.status_code != 200:
        record("E4 チェックイン後に新版が索引に戻る", False,
               f"checkIn failed ({r.status_code}) — could not measure")
    else:
        new_id = oid(r.json())
        back = wait_for(lambda: seed_state(s_peer, new_id) == ALLOWED, 180)
        record("E4 チェックイン後は新版が索引に戻る", back >= 0,
               f"seedable_after_s={back} (id={new_id}) — the exclusion must not be a"
               " permanent loss of search for anything that was ever checked out")

    print("\n== SUMMARY ==", flush=True)
    for name, verdict, detail in findings:
        print(f"{verdict:4s} {name} — {detail}", flush=True)
    bad = [f for f in findings if f[1] == "FAIL"]
    if bad:
        raise SystemExit(f"{len(bad)} PWC exclusion failure(s)")
    print("\nOK: PWC is excluded from RAG, and only the PWC is.", flush=True)


try:
    main()
finally:
    cleanup()
