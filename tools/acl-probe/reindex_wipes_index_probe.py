"""B1 の再現: 全再索引が索引を消したまま「完了」を返す。

## 何が起きるか

3 つの歯車が噛み合う。**どれか 1 つでも外すと再現しない**ので、全部を明示しておく。

1. `ContentDaoServiceImpl.getChildren` (couch, :1143-1146) が例外を握り潰して
   **空リストを返す** — `return new ArrayList<Content>(); // Return empty list on error`
2. `SolrIndexMaintenanceServiceImpl.startFullReindex` は
   **件数カウント → `clearIndex` → 走査** の順 (:136-162)
3. 走査側の catch には**例外が来ない**ので `errorCount` も増えない (:401-407)

さらに、直し方を誤らせないために外せない歯車が 3 つある (並行レビュー指摘):

4. `countDocumentsRecursive` (:339) **も**握り潰す。`getChildren` を throw に変えただけでは
   件数は 1 のまま `clearIndex` に進む。**先頭プローブだけでは閉じない。**
5. cached DAO の tree cache (`getOrCreateTreeCache`, :960-971) が**空リストを覚える**。
   温かい cache のまま view を壊しても CouchDB に問い合わせないので**再現しない**。
   → このプローブは**冷えた JVM** を要求する (core を再作成してから走らせる)。
6. 完了後の health も `collectDocumentIds` (:796) で同じ `getChildren` を使う。
   `couchIds={root}` 対 `solrIds={root}` になるので **healthy=true** と答える。
   → 走査と**独立な母数**が無い限り、直したことを検証できない。

## 期待される結果 (壊れている状態)

    indexed=1 / total=1 / errors=0 / status=completed / healthy=true

Solr が完全に空になるわけではない。**root フォルダ 1 件だけ残る** — `indexDocument` が
root を view を介さずに書いているため。運用上は空と同じ。

## なぜ v3.3.0 で現実的か

初回起動で `Patch_JoinedGroupsSingleEmit` (F4) が `_design/_repo` を書き換え、CouchDB が
**全 view を再構築する**。手順書は起動後に全再索引を打てと言う。初回起動は tree cache も
空なので、キャッシュが救ってくれない。

## 障害の注入方法 — 試して外れた 2 つも書いておく

1. **view を削除する → 効かない。** core を再作成すると起動時に
   `Patch_StandardCmisViews` が走って **view を作り直す** (実測: rev 9 → 10)。
   つまり「view が消える」経路はシステムが自己修復する。
2. **map を構文エラーにする → 拒否される。** CouchDB が PUT を **400** で弾く。
3. **map を実行時に throw させる → これが効く。**
   `function(doc) { throw new Error("injected"); }` は受理され、view クエリは
   **HTTP 200 で `rows:[]`** を返す。

3 が重要なのは、**エラーが起きない**こと。`getChildren` の握り潰しを直して例外を
伝播させても、そもそも例外が無いので捕まらない。これは実測で確かめた事実で、
**「先頭で失敗を検知する」形の修正では閉じない**ことの根拠になっている。

tree cache は **`DELETE /api/v1/cmis/repositories/{repo}/cache/all`** で落とす。
core を再作成すると patch が view を戻してしまうので、**再起動してはいけない**。

## このプローブがやること

**bedroom は触らない。** 副リポジトリ canopy に文書を入れ、canopy の `children` view の
map を throw に差し替えて全再索引を打つ。**必ず map を復元し、cache も落とす**
(空の tree が残ると一覧が空のままになる)。

    python3 tools/acl-probe/reindex_wipes_index_probe.py --seed 150
    python3 tools/acl-probe/reindex_wipes_index_probe.py --break-and-reindex
    python3 tools/acl-probe/reindex_wipes_index_probe.py --restore

前提: nb33 スタック。他の測定と同時に走らせないこと。
"""
import concurrent.futures
import json
import subprocess
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
REPO = "canopy"
BR = f"{BASE}/browser/{REPO}/root"
ROOT = "ddd70e3ed8b847c2a364be81117c57ae"
COUCH = "http://localhost:5984/canopy"
SOLR = "http://localhost:8983/solr/nemaki/select"
BACKUP = "/tmp/canopy-design-backup.json"
MAP_BACKUP = "/tmp/canopy-children-map.txt"

S = requests.Session()
S.auth = ("admin", "admin")
S.headers["X-Requested-With"] = "XMLHttpRequest"
C = requests.Session()
C.auth = ("admin", "password")

BODY = b"B1 reproduction fixture"


# The reindex ends on one of these. "completed_with_errors" is the newest and was added to
# the server on both the CMIS and RAG sides; a poller that waits for "completed" alone runs to
# its deadline instead, and for THIS probe that is not merely slow — it keeps sampling after the
# run is over and dilutes the peak it exists to measure.
#
# Unknown statuses stop the wait too, loudly. An accept-list breaks when a value is ADDED (this
# is the second time), and `!= "running"` breaks when a non-terminal value is added, which is
# worse for a probe because it ends early and reports a partial measurement as a whole one.
TERMINAL = ("completed", "completed_with_errors", "error", "cancelled")


def is_terminal(status):
    if status == "running":
        return False
    if status in TERMINAL:
        return True
    raise SystemExit(f"the reindex reported a status this probe does not know: {status!r}. "
                     f"Add it to TERMINAL if it is an end state; measuring against an "
                     f"unrecognised status would report whatever the last poll happened to see.")

def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def mkfolder(name, parent):
    return oid(S.post(BR, data={
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


def solr_count():
    r = requests.get(SOLR, params={"q": f"repository_id:{REPO}", "rows": 0, "wt": "json"},
                     timeout=120)
    return r.json()["response"]["numFound"]


def couch_total():
    """An INDEPENDENT count: CouchDB's own document total, which does not go through the
    folder-tree walk and therefore cannot be silenced by the same failure."""
    return C.get(COUCH, timeout=120).json().get("doc_count")


def reindex_and_wait(timeout_s=1800):
    r = S.post(f"{BASE}/api/v1/cmis/repositories/{REPO}/search-engine/reindex", timeout=300)
    if not (200 <= r.status_code < 300):
        raise SystemExit(f"reindex did not start: {r.status_code} {r.text[:200]}")
    deadline = time.time() + timeout_s
    last = {}
    while time.time() < deadline:
        st = S.get(f"{BASE}/api/v1/cmis/repositories/{REPO}/search-engine/status",
                   timeout=120).json()
        last = st
        if is_terminal(st.get("status")):
            return st
        time.sleep(2)
    return last


def seed(n):
    print(f"seeding {n} documents into {REPO}...", flush=True)
    tag = uuid.uuid4().hex[:4]
    container = mkfolder(f"b1repro-{tag}", ROOT)
    folders = [mkfolder(f"b1repro-{tag}-{i}", container) for i in range(max(1, n // 50))]
    sessions = []
    for _ in range(8):
        s = requests.Session(); s.auth = ("admin", "admin")
        s.headers["X-Requested-With"] = "XMLHttpRequest"
        sessions.append(s)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(mkdoc, sessions[i % 8], folders[i % len(folders)],
                          f"b1repro-{tag}-{i}") for i in range(n)]
        for f in concurrent.futures.as_completed(futs):
            f.result()
    print(f"  done. container b1repro-{tag}", flush=True)
    st = reindex_and_wait()
    print(f"  baseline reindex: status={st.get('status')} indexed={st.get('indexedCount')} "
          f"total={st.get('totalDocuments')} errors={st.get('errorCount')}", flush=True)
    time.sleep(5)
    print(f"  Solr now holds {solr_count()} documents for {REPO}", flush=True)


def backup_design():
    d = C.get(f"{COUCH}/_design/_repo", timeout=120).json()
    with open(BACKUP, "w") as fh:
        json.dump(d, fh)
    print(f"  design doc backed up (rev {d['_rev']}, {len(d.get('views', {}))} views)", flush=True)
    return d


def clear_caches():
    """Drop the tree cache WITHOUT restarting.

    A cold cache is mandatory — the cached DAO would otherwise answer from a warm entry and never
    ask CouchDB, hiding the failure entirely. But restarting is not the way to get one here:
    startup re-applies Patch_StandardCmisViews, which repairs the very view this probe broke.
    """
    r = S.delete(f"{BASE}/api/v1/cmis/repositories/{REPO}/cache/all", timeout=300)
    print(f"  caches cleared: {r.status_code}", flush=True)


def break_and_reindex():
    before_solr = solr_count()
    before_couch = couch_total()
    print(f"before: Solr={before_solr} docs for {REPO}, CouchDB total={before_couch}", flush=True)

    d = backup_design()
    views = d.get("views", {})
    if "children" not in views:
        raise SystemExit("the children view is missing — restore first")
    with open(MAP_BACKUP, "w") as fh:
        fh.write(views["children"]["map"])
    # A map that throws is accepted by CouchDB and makes the view answer 200 with zero rows —
    # which is what a rebuilding or partially-built view looks like to a reader, and crucially
    # produces NO error for the application to notice.
    views["children"]["map"] = 'function(doc) { throw new Error("injected view failure"); }'
    d["views"] = views
    r = C.put(f"{COUCH}/_design/_repo", json=d, timeout=300)
    print(f"  children map replaced with a throwing one: {r.status_code}", flush=True)
    probe = C.post(f"{COUCH}/_design/_repo/_view/children",
                   json={"key": ROOT, "include_docs": False, "reduce": False}, timeout=300)
    print(f"  the view now answers {probe.status_code} with "
          f"{len(probe.json().get('rows', []))} rows (no error to detect)", flush=True)

    clear_caches()

    print("\nrunning the full reindex against a repository whose children view is gone...",
          flush=True)
    st = reindex_and_wait()
    time.sleep(8)
    after_solr = solr_count()

    print("\n== RESULT ==", flush=True)
    print(f"  status        : {st.get('status')}", flush=True)
    print(f"  totalDocuments: {st.get('totalDocuments')}", flush=True)
    print(f"  indexedCount  : {st.get('indexedCount')}", flush=True)
    print(f"  errorCount    : {st.get('errorCount')}", flush=True)
    print(f"  errors        : {(st.get('errors') or [])[:2]}", flush=True)
    print(f"  healthy       : {st.get('healthy')} / {st.get('healthCheck')}", flush=True)
    print(f"  Solr {REPO}    : {before_solr} -> {after_solr}", flush=True)
    print(f"  CouchDB total : {before_couch} (unchanged — the data is still there)", flush=True)

    # Three outcomes, and they must not be confused with each other.
    refused = st.get("status") == "error" and before_solr == after_solr
    wiped = before_solr > 10 and after_solr <= 2
    # Both completion words. The point of this probe is a wipe reported as a success, and
    # "completed_with_errors" over an emptied index is still that — the count is what makes it
    # silent, not the word.
    silent = (st.get("status") in ("completed", "completed_with_errors")
              and not st.get("errorCount"))
    print("\n== VERDICT ==", flush=True)
    if refused:
        print("  GUARDED (this is the FIXED behaviour). The walk found almost nothing, so the"
              "\n  reindex refused and cleared nothing: the index is untouched at"
              f" {after_solr} documents"
              f"\n  and the status is '{st.get('status')}' with a message naming both numbers."
              "\n  Before the fix this same injection reported completed/errors=0 and left 1.",
              flush=True)
        print(f"  message: {st.get('errorMessage')}", flush=True)
    elif wiped and silent:
        print("  REPRODUCED (the BROKEN behaviour). The index was emptied and the operation"
              f"\n  reported success: {before_solr} -> {after_solr}, status=completed, errors=0."
              "\n  The data in CouchDB is intact; only the search index was destroyed, and"
              "\n  nothing in the API told the operator.", flush=True)
    else:
        print(f"  INCONCLUSIVE: Solr {before_solr} -> {after_solr}, status={st.get('status')}."
              "\n  Neither the wipe nor the refusal. Most likely the tree cache was still warm"
              "\n  (clear it and retry) or the injection did not take — check that the view"
              "\n  really answers with 0 rows.", flush=True)
    print(json.dumps({"beforeSolr": before_solr, "afterSolr": after_solr,
                      "status": st.get("status"), "errors": st.get("errorCount"),
                      "total": st.get("totalDocuments"), "indexed": st.get("indexedCount")},
                     indent=2), flush=True)
    print("\n!! RESTORE NEXT: python3 tools/acl-probe/reindex_wipes_index_probe.py --restore",
          flush=True)
    # Exit 0 for either a clean reproduction or a clean refusal; the caller knows
    # which build it is running against.
    sys.exit(0 if (refused or (wiped and silent)) else 1)


def restore():
    with open(MAP_BACKUP) as fh:
        original_map = fh.read()
    d = C.get(f"{COUCH}/_design/_repo", timeout=120).json()
    d["views"]["children"]["map"] = original_map
    r = C.put(f"{COUCH}/_design/_repo", json=d, timeout=300)
    print(f"children map restored: {r.status_code}", flush=True)
    time.sleep(3)
    probe = C.post(f"{COUCH}/_design/_repo/_view/children",
                   json={"key": ROOT, "include_docs": False, "reduce": False}, timeout=900)
    print(f"  the view answers with {len(probe.json().get('rows', []))} rows again", flush=True)
    # The tree cache is holding the empty answers it learned while the view was broken. Left in
    # place, folder listings stay empty even though the view works again.
    clear_caches()
    st = reindex_and_wait()
    time.sleep(8)
    print(f"recovery reindex: status={st.get('status')} indexed={st.get('indexedCount')} "
          f"total={st.get('totalDocuments')} errors={st.get('errorCount')}", flush=True)
    print(f"Solr now holds {solr_count()} documents for {REPO}", flush=True)


def main():
    if "--seed" in sys.argv:
        seed(int(sys.argv[sys.argv.index("--seed") + 1]))
    elif "--break-and-reindex" in sys.argv:
        break_and_reindex()
    elif "--restore" in sys.argv:
        restore()
    else:
        print(__doc__)


main()
