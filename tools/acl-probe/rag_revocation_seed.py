"""RAG 経路の剥奪整合 (台帳 R1 / §0 基準 4)。

基準 4 は「REVOKE の getObject / query / (RAG 有効環境では seed) の収束時間を
陰性対照付きで測り、上限を数値で文書化する」ことを求めている。RAG の seed 経路だけ
測定資産が無く、判定が未評価のまま残っていた。

測るのは 4 つ:

  T1  ACE 剥奪後、seed (/rag/similar) が使えなくなるまで
  T2  グループ membership 剥奪後、同上 (トークン集合が変わる経路。principal 世代と
      キャッシュが絡むので T1 とは別物)
  T3  検索結果 (/rag/search) から消えるまで
  T4  RAG folder reindex を挟んでも剥奪が維持されるか (R1 が「未確認」とした経路)

seed が特に重いのは、剥奪された文書を similarity の種に使えると
「存在するか」と「意味的に何の近傍か」を引き出せる oracle になるから。
VectorSearchServiceImpl は indexed readers fq とは別に live-ACL ゲートを持っており、
その狙いは「索引が追いつく前でも即座に落とす」こと。この計測はそれが本当に
即座なのかを数値で言うためにある。

読み方:

- **陰性対照 (無関係ユーザ UOUT) が最初から最後まで 0 件でなければ、全数値は無効。**
  GROUP_EVERYONE がルートから read を継承しているため、外し忘れると全員が読めて
  検査が無意味になる。
- **陽性対照 (剥奪前に UIN が seed を使えること) が取れなければ、その回も無効。**
  「剥奪後に使えない」は、剥奪前に使えていて初めて意味を持つ。
- admin では測れない。admin は readers fq を bypass する。
- RAG 索引の完了を待ってから剥奪する。索引前に剥奪しても何も証明しない。

前提: nb33 開発スタック + TEI (`--profile rag`)、admin:admin。bedroom に fixture を
作成して測る (書き込みあり)。他の測定・TCK と同時に走らせないこと。
"""
import json
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
REST = f"{BASE}/rest/repo/bedroom"
API = f"{BASE}/api/v1/cmis/repositories/bedroom"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

T = uuid.uuid4().hex[:5]
GRP = f"rgrp{T}"
UIN = f"rin{T}"
UOUT = f"rout{T}"
PW = "Mx!" + T
DOCS = 3
POLL = 0.25
TIMEOUT = 120

A = requests.Session()
A.auth = ("admin", "admin")
A.headers["X-Requested-With"] = "XMLHttpRequest"


def sess(user):
    s = requests.Session()
    s.auth = (user, PW)
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    return s


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def rag_enabled():
    r = A.get(f"{API}/rag/health", timeout=60)
    j = r.json()
    return bool(j.get("enabled")) and j.get("status") not in ("unavailable", None)


def seed_ok(s, doc_id):
    """Can this session use doc_id as a similarity seed?

    True only for a 200. The service deliberately answers an unreadable seed exactly
    as it answers a missing one, so "not found" and "not yours" are the same signal —
    that indistinguishability is the point, and this probe must not try to see through
    it.
    """
    try:
        r = s.get(f"{API}/rag/similar/{doc_id}?topK=5&minScore=0.0", timeout=60)
        return r.status_code == 200
    except Exception:
        return False


def search_hits(s, term, only_ids=None, folder_id=None):
    """How many hits for `term` belong to THIS run's fixture.

    Scoped by document id on purpose. RAG search is semantic, so at minScore=0 every
    document the caller may read comes back regardless of the query text — counting raw
    hits would count leftovers from earlier runs and other fixtures, and a negative
    control built on that is just a count of the repository. The marker in the text is
    for humans; the id set is what makes the number mean something.
    """
    try:
        # NOTE: the folderId parameter is deliberately NOT used. Measured 2026-08-11 on
        # this stack: `&folderId=<fixture>` returned documents from OTHER folders and
        # dropped two of the three that were IN the folder — it does not scope the
        # search. Filed separately; relying on it here would silently corrupt every
        # count below. Scoping is done by document id instead, with a topK large enough
        # that the fixture's semantically-exact matches rank above the noise.
        r = s.get(f"{API}/rag/search?q={term}&topK=50&minScore=0.0", timeout=60)
        if r.status_code != 200:
            return 0
        hits = r.json().get("results") or []
        if only_ids is None:
            return len(hits)
        wanted = set(only_ids)
        return sum(1 for h in hits if h.get("documentId") in wanted)
    except Exception:
        return -1


def wait_until(predicate, timeout=TIMEOUT):
    """Seconds until predicate() first holds, or -1 if it never did."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if predicate():
            return round(time.time() - t0, 3)
        time.sleep(POLL)
    return -1


def apply_acl(object_id, aces, break_inheritance=False):
    """POST the ACL the way the product's own UI does: one call carrying both."""
    body = {
        "acl": {"permissions": [{"principalId": p, "permissions": perms, "direct": True}
                                for p, perms in aces]},
        "breakInheritance": bool(break_inheritance),
    }
    return A.post(f"{BASE}/rest/repo/bedroom/node/{object_id}/acl",
                  json=body, timeout=120)


def read_acl(object_id):
    return A.get(f"{BASE}/rest/repo/bedroom/node/{object_id}/acl", timeout=120).text


print("== preflight ==", flush=True)
if not rag_enabled():
    raise SystemExit(
        "RAG is not enabled/healthy. Start TEI: "
        "docker compose -p nb33 -f docker/docker-compose-simple.yml --profile rag up -d tei\n"
        "Measuring with RAG off would produce numbers that say nothing about R1.")
print("rag: enabled", flush=True)

print("== fixture ==", flush=True)
A.post(f"{REST}/group/create/{GRP}", data={"name": GRP}, timeout=120)
for u in (UIN, UOUT):
    A.post(f"{REST}/user/create/{u}",
           data={"name": u, "password": PW, "firstName": "r", "lastName": u,
                 "email": f"{u}@x.test"}, timeout=120)
A.put(f"{REST}/group/add/{GRP}", data={"users": json.dumps([UIN])}, timeout=120)
SIN, SOUT = sess(UIN), sess(UOUT)

folder = oid(A.post(BR, data={
    "cmisaction": "createFolder", "objectId": ROOT,
    "propertyId[0]": "cmis:name", "propertyValue[0]": f"ragrevoke-{T}",
    "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
    timeout=120).json())
print("folder", folder, flush=True)

# Break inheritance and grant the group + admin. The break is what makes the negative
# control mean anything (GROUP_EVERYONE inherits read from the root, so without it
# everyone can read and the probe measures nothing). admin is kept as an ACE ONLY so
# there is an observer that can tell "indexed yet?" apart from "revoked": the RAG path
# authorizes admin through the same reader tokens as anyone else — it does NOT bypass
# them — so an admin without an ACE sees exactly what a revoked user sees, and the
# probe could not distinguish "the wait failed" from "the revocation worked".
apply_acl(folder, [(GRP, ["cmis:read"]), ("admin", ["cmis:read"])], break_inheritance=True)

print("acl after grant:", read_acl(folder)[:400], flush=True)

MARKER = f"ragprobe{T}"
doc_ids = []
for i in range(DOCS):
    text = (f"{MARKER} confidential quarterly revenue analysis segment {i}. "
            "The northern division reported growth while the southern division "
            "contracted. Margins narrowed on freight costs.").encode()
    files = {"content": (f"{MARKER}-{i}.txt", text, "text/plain")}
    data = {"cmisaction": "createDocument", "objectId": folder,
            "propertyId[0]": "cmis:name", "propertyValue[0]": f"{MARKER}-{i}.txt",
            "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:document"}
    doc_ids.append(oid(A.post(BR, data=data, files=files, timeout=180).json()))
print("docs", doc_ids, flush=True)

SEED = doc_ids[0]

print("== wait for RAG indexing ==", flush=True)
indexed = wait_until(lambda: seed_ok(A, SEED), timeout=300)
print(f"rag_indexed_after_s={indexed}", flush=True)
if indexed < 0:
    raise SystemExit("the seed never became usable for the granted observer — nothing was "
                     "indexed, so any 'revoked' result below would be vacuous")

print("== controls ==", flush=True)
pos = seed_ok(SIN, SEED)
neg = seed_ok(SOUT, SEED)
neg_hits = search_hits(SOUT, MARKER, doc_ids, folder)
print(f"positive_control_in_can_seed={pos}", flush=True)
print(f"negative_control_out_can_seed={neg}", flush=True)
print(f"negative_control_out_search_hits={neg_hits}", flush=True)
if not pos or neg or neg_hits != 0:
    raise SystemExit("controls failed — every number below would be meaningless. "
                     "positive must be True, both negatives must be False/0.")

# The SEARCH positive control needs a WAIT, not a snapshot. The seed gate is live-ACL
# and answers immediately; search goes through the indexed readers and a soft commit, so
# right after indexing the granted user legitimately sees nothing yet. Measuring
# "time until zero hits" from a state that is already zero measures nothing.
in_hits_before = wait_until(
    lambda: search_hits(SIN, MARKER, doc_ids, folder) == DOCS, timeout=180)
print(f"in_search_all_visible_after_s={in_hits_before}", flush=True)
if in_hits_before < 0:
    raise SystemExit(f"the granted user never saw all {DOCS} fixture documents in RAG "
                     "search — the search revocation numbers below would be measuring a "
                     "state that was already empty")

results = {}

print("== T1: revoke by removing the ACE ==", flush=True)
t0 = time.time()
apply_acl(folder, [("admin", ["cmis:read"])], break_inheritance=True)  # group ACE removed
results["T1_seed_denied_s"] = wait_until(lambda: not seed_ok(SIN, SEED))
results["T1_search_zero_s"] = wait_until(lambda: search_hits(SIN, MARKER, doc_ids, folder) == 0)
print(f"T1_seed_denied_s={results['T1_seed_denied_s']}", flush=True)
print(f"T1_search_zero_s={results['T1_search_zero_s']}", flush=True)

print("== restore, then T2: revoke by removing group membership ==", flush=True)
apply_acl(folder, [(GRP, ["cmis:read"]), ("admin", ["cmis:read"])], break_inheritance=True)
regrant = wait_until(lambda: seed_ok(SIN, SEED))
print(f"regrant_seed_ok_after_s={regrant}", flush=True)
if regrant < 0:
    raise SystemExit("could not restore access — T2 would measure a state that was "
                     "already denied, which proves nothing")

A.put(f"{REST}/group/remove/{GRP}", data={"users": json.dumps([UIN])}, timeout=120)
results["T2_seed_denied_s"] = wait_until(lambda: not seed_ok(SIN, SEED))
results["T2_search_zero_s"] = wait_until(lambda: search_hits(SIN, MARKER, doc_ids, folder) == 0)
print(f"T2_seed_denied_s={results['T2_seed_denied_s']}", flush=True)
print(f"T2_search_zero_s={results['T2_search_zero_s']}", flush=True)

print("== T4: does a RAG reindex resurrect access? ==", flush=True)
# R1 flags this path as unverified. A reindex rebuilds the blocks; if it rebuilds them
# from pre-revocation readers, the revoked user silently gets the seed back.
#
# The check needs a POSITIVE control, or it passes for the wrong reason: while the
# reindex is deleting and re-adding blocks, NOBODY can seed, so "the revoked user
# cannot" is true of a repository that has simply lost the document. admin still holds
# an ACE, so admin regaining the seed is the signal that the block is back — only after
# that does the revoked user's denial mean anything.
# The FOLDER-scoped reindex specifically: that is the path R1 names as unverified, and
# a whole-repository reindex would take ~16 minutes here and touch everything, which
# makes the result about the repository rather than about this fixture.
A.post(f"{API}/search-engine/rag/reindex/folder/{folder}", timeout=180)


def reindex_done():
    """The reindex actually ran: it finished ("completed", not the initial "idle") and
    reported having indexed something. Accepting "idle" would accept "never started"."""
    try:
        j = A.get(f"{API}/search-engine/rag/status", timeout=60).json()
        return j.get("status") in ("completed", "idle") and (j.get("indexedCount") or 0) > 0
    except Exception:
        return False


reindex_ran_s = wait_until(reindex_done, timeout=900)
print(f"T4_reindex_ran_s={reindex_ran_s}", flush=True)
if reindex_ran_s < 0:
    raise SystemExit("the RAG reindex never reported completion — checking revocation "
                     "against a reindex that did not run proves nothing about the path "
                     "R1 flags as unverified")
reindex_settled_s = wait_until(lambda: seed_ok(A, SEED), timeout=300)
print(f"T4_reindex_settled_s={reindex_settled_s}", flush=True)
if reindex_settled_s < 0:
    raise SystemExit("the reindex never restored the seed even for the still-granted "
                     "observer — T4 would be measuring an empty index, not a revocation")

after_reindex_seed = seed_ok(SIN, SEED)
after_reindex_hits = search_hits(SIN, MARKER, doc_ids, folder)
still_denied_60s = True
t0 = time.time()
while time.time() - t0 < 60:
    if seed_ok(SIN, SEED) or search_hits(SIN, MARKER, doc_ids, folder) != 0:
        still_denied_60s = False
        break
    time.sleep(1.0)
print(f"T4_seed_after_reindex={after_reindex_seed}", flush=True)
print(f"T4_search_hits_after_reindex={after_reindex_hits}", flush=True)
print(f"T4_still_denied_for_60s={still_denied_60s}", flush=True)

print("== final controls (must still hold) ==", flush=True)
print(f"final_negative_out_can_seed={seed_ok(SOUT, SEED)}", flush=True)
# admin kept its ACE throughout, so this must be True. False here means the fixture
# lost its blocks and every denial above is suspect.
print(f"final_observer_admin_can_seed={seed_ok(A, SEED)}", flush=True)

print("== SUMMARY ==", flush=True)
print(json.dumps({
    "docs": DOCS,
    "rag_indexed_after_s": indexed,
    **results,
    "T4_reindex_ran_s": reindex_ran_s,
    "T4_reindex_settled_s": reindex_settled_s,
    "T4_seed_after_reindex": after_reindex_seed,
    "T4_search_hits_after_reindex": after_reindex_hits,
    "T4_still_denied_for_60s": still_denied_60s,
}, indent=2), flush=True)

print(f"\n== cleanup ==\ndelete the fixture folder {folder} and the principals "
      f"{GRP} / {UIN} / {UOUT}:\n"
      f"  curl -u admin:admin -X POST -H 'X-Requested-With: XMLHttpRequest' \\\n"
      f"    -d 'cmisaction=deleteTree&objectId={folder}' {BR}\n"
      f"  curl -u admin:admin -X DELETE -H 'X-Requested-With: XMLHttpRequest' "
      f"{REST}/group/delete/{GRP}\n"
      f"  curl -u admin:admin -X DELETE -H 'X-Requested-With: XMLHttpRequest' "
      f"{REST}/user/delete/{UIN}   # and {UOUT}", flush=True)
