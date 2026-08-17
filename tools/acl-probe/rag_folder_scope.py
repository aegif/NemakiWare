"""`/rag/search` の `folderId` が **両方の経路で** 効いているか。

加重検索は 2 本の KNN を走らせる。**content 側は chunk** (`doc_type:chunk`)、
**property 側は親文書** (`doc_type:document`)。索引しているフィールドが違う:

  親文書: id="rag:<objectId>", object_id="<objectId>", parent_id="<folderId>"
  チャンク: id="<objectId>_chunk_N", parent_document_id="<objectId>"   ← フォルダ情報なし

つまり**1 本のフィルタを両方に載せると、必ずどちらかが間違う**。しかも Solr は
どちらの間違いも 200 で答えるので、目視では気づけない。実際 2 版続けて間違えた:

  1. `{!parent which='doc_type:document'}parent_id:X` (to-parent Block Join)。
     内側クエリは**子**に当たるので、チャンクを親のフィールドで探していた。
     実測: 2 件のフォルダで 1 件しか返らず、API 経由では**他フォルダの文書**が返った。
  2. 素の `parent_id:X` を両方に。property 側は正しくなったが、chunk 側は
     **1 件も当たらない** (実測 0/1,144)。フォルダ検索から本文ヒットが消える。

この計測は 3 通りの重み配分で、フォルダ内 2 件が返りフォルダ外 1 件が返らないことを
確認する。**content 100% の配分が要**で、これが前版で壊れていた経路。

判定は id 集合の一致で行う (件数だけだと、外の文書が混ざって内の文書が落ちた
組み合わせを取り逃がす)。

前提: nb33 + TEI (`--profile rag`)、admin:admin。fixture は終了時に自動削除。
"""
import json
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
API = f"{BASE}/api/v1/cmis/repositories/bedroom"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

T = uuid.uuid4().hex[:5]
KEEP = "--keep" in sys.argv

A = requests.Session()
A.auth = ("admin", "admin")
A.headers["X-Requested-With"] = "XMLHttpRequest"

# Distinctive body text, shared by the in-folder and out-of-folder documents so that a filter
# is the ONLY thing that can separate them. If they differed semantically, a ranking accident
# could pass for a working filter.
BODY = ("The northern division reported growth while the southern division contracted. "
        "Margins narrowed on freight costs across the quarterly revenue analysis.")

folders = []


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def make_folder(label):
    f = oid(A.post(BR, data={
        "cmisaction": "createFolder", "objectId": ROOT,
        "propertyId[0]": "cmis:name", "propertyValue[0]": f"{label}-{T}",
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=120).json())
    folders.append(f)
    return f


def make_doc(folder, name):
    return oid(A.post(BR, files={"content": (f"{name}.txt", BODY.encode(), "text/plain")},
                      data={"cmisaction": "createDocument", "objectId": folder,
                            "propertyId[0]": "cmis:name", "propertyValue[0]": f"{name}.txt",
                            "propertyId[1]": "cmis:objectTypeId",
                            "propertyValue[1]": "cmis:document"}, timeout=180).json())


def search(folder_id, property_boost, content_boost):
    """Returns the set of document ids, or None if the call did not answer."""
    params = {"q": BODY, "topK": 100, "minScore": 0.0}
    if folder_id:
        params["folderId"] = folder_id
    if property_boost is not None:
        params["propertyBoost"] = property_boost
    if content_boost is not None:
        params["contentBoost"] = content_boost
    for _ in range(6):
        r = A.get(f"{API}/rag/search", params=params, timeout=60)
        if r.status_code == 429:
            time.sleep(1.0)
            continue
        if r.status_code != 200:
            print(f"  !! HTTP {r.status_code}: {r.text[:200]}", flush=True)
            return None
        return {h.get("documentId") for h in (r.json().get("results") or [])}
    return None


def cleanup():
    if KEEP:
        print(f"\n== fixture kept ==\n{folders}", flush=True)
        return
    print("\n== cleanup ==", flush=True)
    for f in folders:
        try:
            A.post(BR, data={"cmisaction": "deleteTree", "objectId": f}, timeout=300)
        except Exception as e:
            print(f"cleanup: folder {f} NOT deleted ({e})", flush=True)
    print("fixture removed", flush=True)


def main():
    h = A.get(f"{API}/rag/health", timeout=60).json()
    if not h.get("enabled") or h.get("status") in ("unavailable", None):
        raise SystemExit("RAG is not enabled/healthy")

    inside = make_folder("scope-in")
    outside = make_folder("scope-out")
    in_ids = {make_doc(inside, f"scopein{T}-{i}") for i in range(2)}
    out_id = make_doc(outside, f"scopeout{T}")
    print(f"in={sorted(in_ids)} out={out_id}", flush=True)

    # Wait for all three to be indexed, measured WITHOUT a folder filter so the wait itself
    # cannot be satisfied (or blocked) by the thing under test.
    print("waiting for indexing…", flush=True)
    deadline = time.time() + 300
    while time.time() < deadline:
        seen = search(None, None, None)
        if seen is not None and in_ids <= seen and out_id in seen:
            break
        time.sleep(1.0)
    else:
        raise SystemExit("the fixture never became searchable at all — nothing below would mean "
                         "anything")
    print("indexed", flush=True)

    cases = [
        ("content 100% (chunk 経路のみ — 前版で壊れていたのはここ)", 0.0, 1.0),
        ("property 100% (親文書経路のみ)", 1.0, 0.0),
        ("既定の加重 (両方)", None, None),
    ]
    failures = []
    for label, pb, cb in cases:
        got = search(inside, pb, cb)
        if got is None:
            failures.append(f"{label}: no answer from the API")
            print(f"FAIL {label}: no answer", flush=True)
            continue
        scoped = got & (in_ids | {out_id})
        ok = scoped == in_ids
        print(f"{'OK  ' if ok else 'FAIL'} {label}: in={len(got & in_ids)}/2 "
              f"out_leaked={out_id in got} total_hits={len(got)}", flush=True)
        if not ok:
            if not (in_ids <= got):
                failures.append(f"{label}: dropped {len(in_ids - got)} of 2 in-folder documents"
                                " — the filter excludes what it should keep")
            if out_id in got:
                failures.append(f"{label}: returned the out-of-folder document"
                                " — the filter is not scoping")

    # Control: without folderId the out-of-folder document MUST come back, or the checks above
    # would pass simply because that document is unfindable.
    unscoped = search(None, None, None)
    if unscoped is None or out_id not in unscoped:
        failures.append("control: the out-of-folder document is not findable even unscoped, so"
                        " 'it did not leak' proves nothing")
    else:
        print("OK   control: unscoped search does return the out-of-folder document", flush=True)

    print("\n== SUMMARY ==", flush=True)
    if failures:
        for f in failures:
            print("FAIL", f, flush=True)
        raise SystemExit(f"{len(failures)} folder-scope failure(s)")
    print("OK: folderId scopes both the content and the property path.", flush=True)


try:
    main()
finally:
    cleanup()
