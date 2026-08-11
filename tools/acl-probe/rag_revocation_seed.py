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

「拒否」と「答えが得られなかった」を混ぜないこと (この版の主眼):

  `/rag/search` は **ユーザ単位 2 req/s・burst 5** のレート制限下にある
  (RAGSearchResource.checkRateLimit / RAGConfig の既定値)。前の版はこれを知らずに
  250ms 間隔で叩き、かつ 200 以外をすべて「0 件」に潰していた。つまり **429 が
  「剥奪済み」と読めていた**。陽性対照のループでバーストを使い切った直後に剥奪を
  測るので、これは理論上の懸念ではなく、あの版が出した T3 の値そのものが
  レート制限を測っていた可能性が高い。

  この版は allowed / denied / **unknown** の 3 値にし、429・5xx・通信失敗を
  unknown として扱う。unknown では判定せず、backoff して同じ質問をやり直す。
  429 の遭遇回数を数えて要約に出すので、値がレート制限律速かどうかを読者が判断できる。

前提: nb33 開発スタック + TEI (`--profile rag`)、admin:admin。bedroom に fixture を
作成して測る (書き込みあり)。他の測定・TCK と同時に走らせないこと。
終了時に fixture は自動削除する (--keep で残す)。
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
GRP = f"rgrp{T}"
UIN = f"rin{T}"
UOUT = f"rout{T}"
PW = "Mx!" + T
DOCS = 3

# The seed endpoint is NOT rate limited, so it can be polled tightly; search is, at
# 2 req/s sustained. Polling search faster than the limiter only produces 429s to back
# off from, which costs more wall clock than it buys resolution.
SEED_POLL = 0.25
SEARCH_POLL = 0.55
# Semantic search ranks by cosine similarity, so what comes back in topK is decided by the
# corpus, not by the caller. Querying with a random marker token made "is my fixture in the
# results" a matter of luck against ~1,300 indexed documents, and a positive control that
# depends on luck reports a permission failure when it loses. The query is therefore the
# documents' OWN distinctive sentence — cosine ≈ 1 for the fixture, so it ranks at the top of
# whatever the caller may read — and topK is generous on top of that.
SEARCH_TOPK = 100
SEARCH_QUERY = ("The northern division reported growth while the southern division"
                " contracted. Margins narrowed on freight costs.")
TIMEOUT = 120

KEEP = "--keep" in sys.argv

A = requests.Session()
A.auth = ("admin", "admin")
A.headers["X-Requested-With"] = "XMLHttpRequest"

ALLOWED, DENIED, UNKNOWN = "allowed", "denied", "unknown"
last_error = None


def note_error(r):
    """Remember the last non-OK answer so an 'unknown' can be diagnosed, not just reported."""
    global last_error
    if r is not None:
        body = (r.text or "")[:200].replace("\n", " ")
        last_error = f"HTTP {r.status_code}: {body}"

rate_limited = 0


def sess(user):
    s = requests.Session()
    s.auth = (user, PW)
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    return s


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def get(s, url, tries=6):
    """GET, retrying past rate limiting. None when no answer was obtained.

    A 429 is not information about permissions — it is the absence of information. The
    caller must not read it as either grant or denial, so it comes back as None and the
    caller retries rather than deciding.
    """
    global rate_limited
    delay = 0.5
    for _ in range(tries):
        try:
            r = s.get(url, timeout=60)
        except Exception:
            return None
        if r.status_code != 429:
            if r.status_code != 200:
                note_error(r)
            return r
        rate_limited += 1
        time.sleep(delay)
        delay = min(delay * 2, 4.0)
    return None


def rag_enabled():
    r = A.get(f"{API}/rag/health", timeout=60)
    j = r.json()
    return bool(j.get("enabled")) and j.get("status") not in ("unavailable", None)


def seed_state(s, doc_id):
    """Can this session use doc_id as a similarity seed? allowed / denied / unknown.

    The service deliberately answers an unreadable seed exactly as it answers a missing
    one (404 via the "not found in RAG index" path), so "not found" and "not yours" are
    the same signal — that indistinguishability is the point, and this probe must not try
    to see through it. Everything else is unknown: a 500 or a dropped connection says
    nothing about whether the revocation landed, and counting it as denial is how a probe
    reports success for an outage.
    """
    r = get(s, f"{API}/rag/similar/{doc_id}?topK=5&minScore=0.0")
    if r is None:
        return UNKNOWN
    if r.status_code == 200:
        return ALLOWED
    if r.status_code in (403, 404):
        return DENIED
    return UNKNOWN


def search_count(s, term, only_ids):
    """Hits for `term` belonging to THIS run's fixture, or None if unanswered.

    Scoped by document id on purpose. RAG search is semantic, so at minScore=0 every
    document the caller may read comes back regardless of the query text — counting raw
    hits would count leftovers from earlier runs and other fixtures, and a negative
    control built on that is just a count of the repository. The marker in the text is
    for humans; the id set is what makes the number mean something.

    The folderId parameter is deliberately NOT used. Measured 2026-08-11 on this stack:
    `&folderId=<fixture>` returned documents from OTHER folders and dropped two of the
    three that were IN the folder. Root cause found and fixed since (a to-parent Block
    Join searching the chunks for a field only the parent carries); this probe keeps
    scoping by id anyway, so its counts do not depend on that fix being present.
    """
    # `term` stays in the signature (and in the document text) as the human-readable
    # label for a run; the id set is what scopes the count, and SEARCH_QUERY is what makes
    # the fixture rank. Passing the marker as the query is what made this unreliable.
    r = get(s, f"{API}/rag/search?q={requests.utils.quote(SEARCH_QUERY)}"
               f"&topK={SEARCH_TOPK}&minScore=0.0")
    if r is None or r.status_code != 200:
        return None
    hits = r.json().get("results") or []
    wanted = set(only_ids)
    return sum(1 for h in hits if h.get("documentId") in wanted)


def wait_until(predicate, timeout=TIMEOUT, poll=SEED_POLL, t0=None):
    """Seconds until predicate() first returns True, or -1 if it never did.

    predicate returns True / False / None, where None means "no answer this time".
    None never decides anything; it just costs another round.

    t0 lets the caller start the clock BEFORE the mutation request is issued, so the
    reported figure includes the time the write itself took. Starting it afterwards
    reports the propagation delay only and quietly omits the part a user actually waits
    through.
    """
    start = t0 if t0 is not None else time.time()
    while time.time() - start < timeout:
        if predicate() is True:
            return round(time.time() - start, 3)
        time.sleep(poll)
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


folder = None


def cleanup():
    """Remove the fixture. Left-behind folders from earlier runs diluted topK and made a
    later run's counts wrong, so this is part of the measurement, not tidiness."""
    if KEEP or folder is None:
        print(f"\n== fixture kept ==\nfolder {folder}, principals {GRP} / {UIN} / {UOUT}",
              flush=True)
        return
    print("\n== cleanup ==", flush=True)
    try:
        A.post(BR, data={"cmisaction": "deleteTree", "objectId": folder}, timeout=300)
    except Exception as e:
        print(f"cleanup: folder {folder} NOT deleted ({e}) — remove it by hand", flush=True)
    for path in (f"group/delete/{GRP}", f"user/delete/{UIN}", f"user/delete/{UOUT}"):
        try:
            A.delete(f"{REST}/{path}", timeout=120)
        except Exception as e:
            print(f"cleanup: {path} failed ({e})", flush=True)
    print("fixture removed", flush=True)


def main():
    global folder

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
    apply_acl(folder, [(GRP, ["cmis:read"]), ("admin", ["cmis:read"])],
              break_inheritance=True)
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
    indexed = wait_until(lambda: seed_state(A, SEED) == ALLOWED, timeout=300)
    print(f"rag_indexed_after_s={indexed}", flush=True)
    if indexed < 0:
        raise SystemExit("the seed never became usable for the granted observer — nothing "
                         "was indexed, so any 'revoked' result below would be vacuous")

    print("== controls ==", flush=True)
    pos = seed_state(SIN, SEED)
    neg = seed_state(SOUT, SEED)
    neg_hits = search_count(SOUT, MARKER, doc_ids)
    print(f"positive_control_in_can_seed={pos}", flush=True)
    print(f"negative_control_out_can_seed={neg}", flush=True)
    print(f"negative_control_out_search_hits={neg_hits}", flush=True)
    if pos != ALLOWED or neg != DENIED or neg_hits != 0:
        raise SystemExit("controls failed — every number below would be meaningless. "
                         "positive must be allowed, negatives must be denied / 0. "
                         "An 'unknown' here is a failure too: it means the probe could "
                         "not get an answer, not that the answer was no.")

    # The SEARCH positive control needs a WAIT, not a snapshot. The seed gate is live-ACL
    # and answers immediately; search goes through the indexed readers and a soft commit,
    # so right after indexing the granted user legitimately sees nothing yet. Measuring
    # "time until zero hits" from a state that is already zero measures nothing.
    in_visible_s = wait_until(
        lambda: search_count(SIN, MARKER, doc_ids) == DOCS,
        timeout=180, poll=SEARCH_POLL)
    print(f"in_search_all_visible_after_s={in_visible_s}", flush=True)
    if in_visible_s < 0:
        raise SystemExit(f"the granted user never saw all {DOCS} fixture documents in RAG "
                         "search — the search revocation numbers below would be measuring "
                         "a state that was already empty")

    results = {}

    print("== T1: revoke by removing the ACE ==", flush=True)
    # Clock starts BEFORE the write: what a caller waits through includes the write.
    t0 = time.time()
    apply_acl(folder, [("admin", ["cmis:read"])], break_inheritance=True)
    results["T1_acl_write_s"] = round(time.time() - t0, 3)
    results["T1_seed_denied_s"] = wait_until(
        lambda: seed_state(SIN, SEED) == DENIED, t0=t0)
    results["T1_search_zero_s"] = wait_until(
        lambda: search_count(SIN, MARKER, doc_ids) == 0, poll=SEARCH_POLL, t0=t0)
    for k in ("T1_acl_write_s", "T1_seed_denied_s", "T1_search_zero_s"):
        print(f"{k}={results[k]}", flush=True)

    print("== restore, then T2: revoke by removing group membership ==", flush=True)
    apply_acl(folder, [(GRP, ["cmis:read"]), ("admin", ["cmis:read"])],
              break_inheritance=True)
    regrant = wait_until(lambda: seed_state(SIN, SEED) == ALLOWED)
    print(f"regrant_seed_ok_after_s={regrant}", flush=True)
    if regrant < 0:
        raise SystemExit("could not restore access — T2 would measure a state that was "
                         "already denied, which proves nothing")
    regrant_search = wait_until(
        lambda: search_count(SIN, MARKER, doc_ids) == DOCS, timeout=180, poll=SEARCH_POLL)
    print(f"regrant_search_all_visible_after_s={regrant_search}", flush=True)
    if regrant_search < 0:
        raise SystemExit("search never returned the fixture to the re-granted user, so "
                         "T2_search_zero_s would again start from an already-empty state")

    t0 = time.time()
    A.put(f"{REST}/group/remove/{GRP}", data={"users": json.dumps([UIN])}, timeout=120)
    results["T2_membership_write_s"] = round(time.time() - t0, 3)
    results["T2_seed_denied_s"] = wait_until(
        lambda: seed_state(SIN, SEED) == DENIED, t0=t0)
    results["T2_search_zero_s"] = wait_until(
        lambda: search_count(SIN, MARKER, doc_ids) == 0, poll=SEARCH_POLL, t0=t0)
    for k in ("T2_membership_write_s", "T2_seed_denied_s", "T2_search_zero_s"):
        print(f"{k}={results[k]}", flush=True)

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
    # The status endpoint reports the LAST reindex, so "completed" alone can be a previous
    # run's result — and this probe would then verify revocation against a reindex that
    # never touched the fixture. Remembering the prior startTime and requiring it to CHANGE
    # ties the completion to this POST without comparing our clock to the server's.
    def reindex_start_time():
        try:
            return A.get(f"{API}/search-engine/rag/status", timeout=60).json().get("startTime")
        except Exception:
            return None

    previous_start = reindex_start_time()
    started = A.post(f"{API}/search-engine/rag/reindex/folder/{folder}", timeout=180)
    started_json = started.json() if started.status_code == 200 else {}
    print(f"T4_reindex_start={started.status_code} {started_json.get('message')}", flush=True)
    if not started_json.get("success"):
        # success=false means "a reindex was already running" — that one is not scoped to
        # this folder, so waiting on it would report a whole-repository rebuild as though
        # it were the folder path R1 asks about.
        raise SystemExit("the folder RAG reindex did not start (success=false). Another "
                         "reindex is running; T4 must not be measured against it.")

    def reindex_done():
        try:
            j = A.get(f"{API}/search-engine/rag/status", timeout=60).json()
        except Exception:
            return None
        # "completed", not "idle": idle is also the state before anything ever ran, and
        # accepting it accepts "never started" as "finished". A changed startTime makes it
        # THIS run, and indexedCount == DOCS makes it THIS folder.
        return (j.get("status") == "completed"
                and j.get("startTime") != previous_start
                and j.get("indexedCount") == DOCS)

    reindex_ran_s = wait_until(reindex_done, timeout=900)
    print(f"T4_reindex_ran_s={reindex_ran_s}", flush=True)
    if reindex_ran_s < 0:
        raise SystemExit(f"no completed reindex of THIS folder appeared (previous startTime "
                         f"{previous_start} unchanged, or indexedCount != {DOCS}) — checking "
                         "revocation against a reindex that did not run, or ran elsewhere, "
                         "proves nothing about the path R1 flags as unverified")
    reindex_settled_s = wait_until(lambda: seed_state(A, SEED) == ALLOWED, timeout=300)
    print(f"T4_reindex_settled_s={reindex_settled_s}", flush=True)
    if reindex_settled_s < 0:
        raise SystemExit("the reindex never restored the seed even for the still-granted "
                         "observer — T4 would be measuring an empty index, not a revocation")

    after_seed = seed_state(SIN, SEED)
    after_hits = search_count(SIN, MARKER, doc_ids)
    still_denied_60s = True
    breach = None
    t0 = time.time()
    while time.time() - t0 < 60:
        st, hits = seed_state(SIN, SEED), search_count(SIN, MARKER, doc_ids)
        if st == ALLOWED or (hits is not None and hits != 0):
            still_denied_60s, breach = False, f"seed={st} hits={hits}"
            break
        time.sleep(1.0)
    print(f"T4_seed_after_reindex={after_seed}", flush=True)
    print(f"T4_search_hits_after_reindex={after_hits}", flush=True)
    print(f"T4_still_denied_for_60s={still_denied_60s} {breach or ''}", flush=True)

    print("== final controls (must still hold) ==", flush=True)
    final_out = seed_state(SOUT, SEED)
    # Bounded wait, not one sample: the observer control asks whether the fixture still
    # exists, and one unanswered request is not an answer of "no". A settled denial still
    # fails, because this waits for ALLOWED and gives up.
    final_admin = ALLOWED if wait_until(
        lambda: seed_state(A, SEED) == ALLOWED, timeout=30) >= 0 else seed_state(A, SEED)
    print(f"final_negative_out_can_seed={final_out}", flush=True)
    print(f"final_observer_admin_can_seed={final_admin}", flush=True)

    print("== SUMMARY ==", flush=True)
    print(json.dumps({
        "docs": DOCS,
        "rag_indexed_after_s": indexed,
        "in_search_all_visible_after_s": in_visible_s,
        **results,
        "T4_reindex_ran_s": reindex_ran_s,
        "T4_reindex_settled_s": reindex_settled_s,
        "T4_seed_after_reindex": after_seed,
        "T4_search_hits_after_reindex": after_hits,
        "T4_still_denied_for_60s": still_denied_60s,
        # How often search hit the limiter. Every search figure above has a floor of one
        # poll interval; if this is large, they have a higher floor than that and should
        # be read as upper bounds only.
        "rate_limited_retries": rate_limited,
        "search_poll_s": SEARCH_POLL,
    }, indent=2), flush=True)

    # Enforced, not printed. admin kept its ACE throughout, so it must still be allowed;
    # if it is not, the fixture lost its blocks and every denial above is equally
    # explained by "the document is gone", which is not the claim being made.
    if final_admin != ALLOWED:
        raise SystemExit(f"FAIL: the granted observer lost the seed ({final_admin}) — the "
                         "denials above cannot be attributed to the revocation")
    if final_out != DENIED:
        raise SystemExit(f"FAIL: the unrelated user could seed ({final_out}) — the fixture "
                         "was readable by someone it was never granted to")
    if not still_denied_60s:
        raise SystemExit(f"FAIL: the reindex resurrected access ({breach}) — this is the "
                         "R1 path, and it did not hold")
    print("\nOK: all controls held.", flush=True)


try:
    main()
finally:
    cleanup()
