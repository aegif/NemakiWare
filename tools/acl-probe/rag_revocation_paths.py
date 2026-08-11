"""RAG 剥奪の「経路」別確認 (台帳 R1 の残差)。

`rag_revocation_seed.py` は **グループ ACE 経由の read 剥奪**を測った。台帳 R1 は
その 1 経路だけでは基準 4 を「RAG 経路も含めて合格」と言えない、としている。
判定が PermissionServiceImpl ではなく**トークン交差**である以上、トークン集合の
作られ方が違う経路はそれぞれ別の検査が要るため。

ここで確認するのは 4 経路。いずれも「時間」ではなく「**漏れるか漏れないか**」を見る
(所要時間は seed プローブ側で測ってある。こちらはビルド等と並走しても結論が変わらない)。

  P1  anyone / GROUP_EVERYONE 経由で得た read の剥奪
      → 公開フォルダを非公開に戻す、最も日常的な操作
  P2  ネストグループ経由 (outer ⊃ inner ∋ user) の剥奪
      → トークンはグループ展開で作られるので、内側の membership を切ったときに
        外側のトークンが消えるかは ACE 直接付与とは別の話
  P3  admin の権限が RAG に及ばないこと (= 降格で剥奪すべきものが無いこと)
      → 当初は「admin にして、降格して、消えること」を測るつもりだった。実測すると
        **admin は最初から RAG を読めない** (CMIS の getObject は 200、RAG の seed は
        404) ので、降格しても剥奪する対象が無かった。差の向きは安全側だが、
        「管理者にだけ意味検索が効かない」という不整合でもあるので、性質として固定する。
        RAG に admin bypass を足すと**この検査が落ち**、降格シナリオが実在に変わる
  P4  move による剥奪 (読めるフォルダから読めないフォルダへ)
      → ACL は 1 バイトも変えずに実効権限だけが変わる。ACL 変更を契機に
        再索引する設計だと取りこぼす形

各経路とも:

  1. 剥奪「前」に対象ユーザが seed/検索できることを確認 (陽性対照)。
     取れなければその経路は測定不能として FAIL にする — 「できない」は
     「剥奪された」の証拠にならない。
  2. 剥奪する。
  3. 有界時間 (WINDOW 秒) 待って、seed も検索も落ちることを確認。
  4. 観測用 admin ACE が最後まで通ることを確認 (= fixture が消えたのではない)。

拒否と「答えが得られなかった」は混ぜない。`/rag/search` はユーザ単位
2 req/s のレート制限下にあり、429 を拒否と読むと**全経路が誤って合格する**。
allowed / denied / unknown の 3 値で扱う (seed プローブと同じ理由・同じ実装)。

前提: nb33 開発スタック + TEI (`--profile rag`)、admin:admin。bedroom に fixture を
作成する (書き込みあり)。終了時に自動削除 (--keep で残す)。
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
ANYONE = "GROUP_EVERYONE"

T = uuid.uuid4().hex[:5]
PW = "Mx!" + T
WINDOW = 60.0
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
created = {"folders": [], "users": [], "groups": []}


def sess(user, pw=PW):
    s = requests.Session()
    s.auth = (user, pw)
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    return s


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def get(s, url, tries=6):
    """GET, retrying past rate limiting. None when no answer was obtained.

    A 429 is the ABSENCE of information about permissions. Reading it as denial makes
    every check below pass for the wrong reason, which is the exact failure this probe
    exists to rule out elsewhere.
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


def seed_state(s, doc_id):
    r = get(s, f"{API}/rag/similar/{doc_id}?topK=5&minScore=0.0")
    if r is None:
        return UNKNOWN
    if r.status_code == 200:
        return ALLOWED
    if r.status_code in (403, 404):
        return DENIED
    return UNKNOWN


def search_count(s, term, only_ids):
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


def wait_for(predicate, timeout, poll):
    """Seconds until predicate() is True, or -1. None from predicate never decides."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        if predicate() is True:
            return round(time.time() - t0, 3)
        time.sleep(poll)
    return -1


def apply_acl(object_id, aces, break_inheritance=False):
    body = {
        "acl": {"permissions": [{"principalId": p, "permissions": ["cmis:read"], "direct": True}
                                for p in aces]},
        "breakInheritance": bool(break_inheritance),
    }
    return A.post(f"{BASE}/rest/repo/bedroom/node/{object_id}/acl", json=body, timeout=120)


def make_user(name, admin=False):
    A.post(f"{REST}/user/create/{name}",
           data={"name": name, "password": PW, "firstName": "r", "lastName": name,
                 "email": f"{name}@x.test"}, timeout=120)
    created["users"].append(name)
    if admin:
        A.put(f"{REST}/user/update/{name}", data={"isAdmin": "true"}, timeout=120)
    return name


def make_group(name, users=None, groups=None):
    data = {"name": name}
    if users:
        data["users"] = json.dumps(users)
    if groups:
        data["groups"] = json.dumps(groups)
    A.post(f"{REST}/group/create/{name}", data=data, timeout=120)
    created["groups"].append(name)
    return name


def make_folder(label, aces, break_inheritance=True):
    f = oid(A.post(BR, data={
        "cmisaction": "createFolder", "objectId": ROOT,
        "propertyId[0]": "cmis:name", "propertyValue[0]": f"{label}-{T}",
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=120).json())
    created["folders"].append(f)
    # admin keeps an explicit ACE throughout: the RAG path does NOT bypass reader tokens
    # for admin, so without one there is no observer that can tell "the block is gone"
    # apart from "the revocation worked".
    apply_acl(f, list(aces) + ["admin"], break_inheritance=break_inheritance)
    return f


def make_doc(folder, marker, i):
    text = (f"{marker} confidential quarterly revenue analysis segment {i}. "
            "The northern division reported growth while the southern division "
            "contracted. Margins narrowed on freight costs.").encode()
    return oid(A.post(BR, files={"content": (f"{marker}-{i}.txt", text, "text/plain")},
                      data={"cmisaction": "createDocument", "objectId": folder,
                            "propertyId[0]": "cmis:name",
                            "propertyValue[0]": f"{marker}-{i}.txt",
                            "propertyId[1]": "cmis:objectTypeId",
                            "propertyValue[1]": "cmis:document"}, timeout=180).json())


findings = []


def check(name, session, doc_id, marker, doc_ids, revoke):
    """One path: prove access, revoke, require it to go away and stay away."""
    global last_error
    print(f"\n== {name} ==", flush=True)

    indexed = wait_for(lambda: seed_state(A, doc_id) == ALLOWED, 300, SEED_POLL)
    if indexed < 0:
        findings.append((name, "INCONCLUSIVE", "never indexed for the granted observer"))
        print(f"{name}: INCONCLUSIVE (never indexed)", flush=True)
        return

    # Positive control. "Cannot seed after" only means something if it could seed before.
    before_seed = wait_for(lambda: seed_state(session, doc_id) == ALLOWED, 60, SEED_POLL)
    before_hits = wait_for(
        lambda: (search_count(session, marker, doc_ids) or 0) == len(doc_ids), 180, SEARCH_POLL)
    print(f"before: seed_allowed_after_s={before_seed} search_all_after_s={before_hits}",
          flush=True)
    if before_seed < 0 or before_hits < 0:
        findings.append((name, "INCONCLUSIVE",
                         f"no access to revoke (seed={before_seed} search={before_hits})"
                         f" last_error={last_error}"))
        print(f"{name}: INCONCLUSIVE (nothing to revoke)", flush=True)
        return

    t0 = time.time()
    revoke()
    seed_gone = wait_for(lambda: seed_state(session, doc_id) == DENIED, WINDOW, SEED_POLL)
    search_gone = wait_for(lambda: search_count(session, marker, doc_ids) == 0,
                           WINDOW, SEARCH_POLL)
    print(f"after: seed_denied_s={seed_gone} search_zero_s={search_gone} "
          f"(clock from before the revoking request, elapsed {round(time.time()-t0,2)}s)",
          flush=True)

    if seed_gone < 0 or search_gone < 0:
        findings.append((name, "LEAK",
                         f"still readable after {WINDOW}s (seed={seed_gone} "
                         f"search={search_gone})"))
        print(f"{name}: LEAK", flush=True)
        return

    # And it must STAY gone: a late re-index or a cache refill could put it back.
    settle = time.time()
    while time.time() - settle < 20:
        st, hits = seed_state(session, doc_id), search_count(session, marker, doc_ids)
        if st == ALLOWED or (hits is not None and hits != 0):
            findings.append((name, "LEAK", f"came back after {round(time.time()-settle,1)}s "
                                           f"(seed={st} hits={hits})"))
            print(f"{name}: LEAK (returned)", flush=True)
            return
        time.sleep(1.0)

    # The observer control is a LIVENESS check ("the block is still there"), so it gets a
    # bounded wait rather than one sample. A single unanswered request — a 5xx, a dropped
    # connection — is not evidence that the fixture disappeared, and treating it as such
    # discards an otherwise complete measurement. Observed once: exactly that, with no rate
    # limiting involved. What must NOT be retried away is a settled denial, so this waits for
    # ALLOWED and reports whatever it last saw if that never arrives.
    last_error = None
    observer_ok = wait_for(lambda: seed_state(A, doc_id) == ALLOWED, 30, SEED_POLL)
    if observer_ok < 0:
        findings.append((name, "INCONCLUSIVE",
                         "the granted observer also lost it for 30s — the denial is equally "
                         f"explained by the document being gone. observer_last_error={last_error}"))
        print(f"{name}: INCONCLUSIVE (observer lost access)", flush=True)
        return

    findings.append((name, "OK", f"seed {seed_gone}s / search {search_gone}s"))
    print(f"{name}: OK", flush=True)


def cleanup():
    if KEEP:
        print(f"\n== fixture kept ==\n{json.dumps(created, indent=2)}", flush=True)
        return
    print("\n== cleanup ==", flush=True)
    for f in created["folders"]:
        try:
            A.post(BR, data={"cmisaction": "deleteTree", "objectId": f}, timeout=300)
        except Exception as e:
            print(f"cleanup: folder {f} NOT deleted ({e})", flush=True)
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
    print("== preflight ==", flush=True)
    h = A.get(f"{API}/rag/health", timeout=60).json()
    if not h.get("enabled") or h.get("status") in ("unavailable", None):
        raise SystemExit("RAG is not enabled/healthy — measuring with RAG off says nothing")
    print("rag: enabled", flush=True)

    # ---- P1: anyone / GROUP_EVERYONE -------------------------------------------------
    u1 = make_user(f"pany{T}")
    s1 = sess(u1)
    f1 = make_folder("ragpath-anyone", [ANYONE])
    m1 = f"pathanyone{T}"
    d1 = [make_doc(f1, m1, i) for i in range(2)]
    check("P1 anyone/GROUP_EVERYONE の剥奪", s1, d1[0], m1, d1,
          lambda: apply_acl(f1, ["admin"], break_inheritance=True))

    # ---- P2: nested group ------------------------------------------------------------
    # outer ⊃ inner ∋ user, and only OUTER holds the ACE. Revoking by removing inner from
    # outer changes no ACL at all — only the group graph — so a token set built once and
    # cached, or an index keyed on the ACE, would not notice.
    u2 = make_user(f"pnest{T}")
    inner = make_group(f"ginner{T}", users=[u2])
    outer = make_group(f"gouter{T}", groups=[inner])
    s2 = sess(u2)
    f2 = make_folder("ragpath-nested", [outer])
    m2 = f"pathnested{T}"
    d2 = [make_doc(f2, m2, i) for i in range(2)]
    check("P2 ネストグループ (outer ⊃ inner) の剥奪", s2, d2[0], m2, d2,
          lambda: A.put(f"{REST}/group/remove/{outer}",
                        data={"groups": json.dumps([inner])}, timeout=120))

    # ---- P3: admin demotion ----------------------------------------------------------
    # Written first as "grant admin, then demote, then require RAG access to disappear".
    # It could not run: the admin never HAD RAG access to lose. That is not a gap in the
    # probe, it is the answer — so this asserts the property directly instead.
    #
    # Measured: an admin with no ACE on the folder reads the document through CMIS
    # (getObject 200) and is refused as a RAG seed (404). RAG authorizes purely by reader
    # tokens and does not implement the admin bypass that the CMIS layer does. Demotion
    # therefore has nothing to revoke on this path, and the direction of the difference is
    # the safe one.
    #
    # It is still an inconsistency worth keeping visible: an administrator sees a folder in
    # the tree and gets nothing from semantic search over it. If the admin bypass is ever
    # added to RAG for that reason, THIS check starts failing and the demotion scenario
    # above becomes real and must be measured.
    u3 = make_user(f"padm{T}", admin=True)
    s3 = sess(u3)
    f3 = make_folder("ragpath-admin", [])
    m3 = f"pathadmin{T}"
    d3 = [make_doc(f3, m3, i) for i in range(2)]
    print("\n== P3 admin の権限は RAG に及ばない (= 降格で剥奪すべきものが無い) ==", flush=True)
    if wait_for(lambda: seed_state(A, d3[0]) == ALLOWED, 300, SEED_POLL) < 0:
        findings.append(("P3 admin 経由の RAG 読み", "INCONCLUSIVE", "never indexed"))
    else:
        admin_user_is_admin = A.get(f"{REST}/user/show/{u3}", timeout=120).json() \
            .get("user", {}).get("isAdmin") is True
        cmis = A.get(f"{BR}?cmisselector=object&objectId={d3[0]}", timeout=120)
        cmis_as_user = sess(u3).get(f"{BR}?cmisselector=object&objectId={d3[0]}", timeout=120)
        rag_as_user = seed_state(s3, d3[0])
        print(f"isAdmin={admin_user_is_admin} cmis_admin={cmis.status_code} "
              f"cmis_as_that_admin={cmis_as_user.status_code} rag_as_that_admin={rag_as_user}",
              flush=True)
        if not admin_user_is_admin:
            findings.append(("P3 admin 経由の RAG 読み", "INCONCLUSIVE",
                             "the fixture user never became an admin, so nothing was tested"))
        elif cmis_as_user.status_code != 200:
            findings.append(("P3 admin 経由の RAG 読み", "INCONCLUSIVE",
                             f"the admin could not read via CMIS either ({cmis_as_user.status_code})"
                             " — there is no admin bypass to compare against"))
        elif rag_as_user == ALLOWED:
            findings.append(("P3 admin 経由の RAG 読み", "LEAK",
                             "RAG now honours the admin bypass — demotion revocation is a real "
                             "path again and this probe no longer covers it"))
        elif rag_as_user == DENIED:
            findings.append(("P3 admin 経由の RAG 読み", "OK",
                             "CMIS 200 / RAG 404 — admin-ness grants no RAG read, so demotion "
                             "has nothing to revoke (note: admins get no semantic search)"))
        else:
            findings.append(("P3 admin 経由の RAG 読み", "INCONCLUSIVE",
                             f"no answer from RAG ({rag_as_user})"))

    # ---- P4: move out of a readable folder -------------------------------------------
    # The document's own ACL never changes; only its parent does. A design that re-indexes
    # on ACL change alone would keep the old readers.
    u4 = make_user(f"pmove{T}")
    grp4 = make_group(f"gmove{T}", users=[u4])
    s4 = sess(u4)
    src = make_folder("ragpath-move-src", [grp4])
    dst = make_folder("ragpath-move-dst", [])
    m4 = f"pathmove{T}"
    d4 = [make_doc(src, m4, i) for i in range(2)]
    check("P4 読めないフォルダへの move", s4, d4[0], m4, d4,
          lambda: [A.post(BR, data={"cmisaction": "move", "objectId": doc,
                                    "sourceFolderId": src, "targetFolderId": dst},
                          timeout=180) for doc in d4])

    print("\n== SUMMARY ==", flush=True)
    for name, verdict, detail in findings:
        print(f"{verdict:14s} {name} — {detail}", flush=True)
    print(f"\nrate_limited_retries={rate_limited}", flush=True)

    leaks = [f for f in findings if f[1] == "LEAK"]
    unknowns = [f for f in findings if f[1] == "INCONCLUSIVE"]
    if leaks:
        raise SystemExit(f"FAIL: {len(leaks)} path(s) still readable after revocation")
    if unknowns:
        raise SystemExit(f"INCONCLUSIVE: {len(unknowns)} path(s) could not be measured — "
                         "an unmeasured path is not a passing path")
    print("\nOK: every path revoked and stayed revoked.", flush=True)


try:
    main()
finally:
    cleanup()
