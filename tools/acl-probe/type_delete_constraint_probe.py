"""`deleteType` が「まだそのタイプのオブジェクトが在る」ときに拒否するかを実機で確かめる。

CMIS の `deleteType` は、当該タイプのインスタンスが残っている間は
`CmisConstraintException` を投げなければならない。NemakiWare でその番人は
`ExceptionServiceImpl.constraintObjectsStillExist` → `ContentService.existContent`
→ view `countByObjectType`。

**現在の実測結果: 番人は発火する (2026-08-12 に配線済み)。** 以下は経緯。

`existContent` は確かに壊れていた。`countByObjectType` は reduce 付き view で、
`CloudantClientWrapper` の当該 overload が常に `include_docs=true` を付けていたため
CouchDB が

    query_parse_error: `include_docs` is invalid for reduce

で拒否 → wrapper が例外 → `existContent` の catch が **false** を返していた。
これは 2026-08-12 に修正した (include_docs を送らない)。

**しかしそれだけでは制約は復活しない。** 判明したこと:

- `constraintObjectsStillExist` は **どこからも呼ばれていない** (interface と impl は
  在るが呼び出し元が無い)。
- 実際の `deleteType` は `TypeManagerImpl.checkTypeDependencies` を通り、その
  インスタンス確認 `checkTypeHasInstances` は **未実装のスタブで常に false** を返す
  ("Instance checking not yet implemented")。

当初はこの状態で「インスタンスが在るタイプの削除は成功する」ことを実測した。
その後 **2026-08-12 にスタブを `existContent` に配線し、このプローブは exit 0 になった**
(空のタイプ削除は 200、インスタンスが在るタイプは 409)。

**まだ塞がっていないもの** (このプローブは CMIS 経路しか見ていない):
- 管理画面が使う NemakiWare 独自の REST 削除
  (`DELETE /core/rest/repo/{repo}/type/delete/{typeId}`) は検査を通らない。
  これは「CMIS 非準拠」と自ら警告する意図的な抜け道
- ~~**secondary type** は `countByObjectType` の key (主タイプ) に載らないので対象外~~
  → 2026-08-13 に `secondaryIds` の確認を足して塞いだ

手順は「タイプを作る → そのタイプの文書を 1 件作る → deleteType」。
期待は **409 (constraint)**。200 が返るなら番人はまだ効いていない。

前提: nb33 スタック、admin:admin。他の測定と同時に走らせないこと。
"""
import json
import sys
import uuid

import requests

BASE = "http://localhost:8080/core"
REPO = "bedroom"
BR = f"{BASE}/browser/{REPO}/root"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

S = requests.Session()
S.auth = ("admin", "admin")
S.headers["X-Requested-With"] = "XMLHttpRequest"


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def main():
    tag = uuid.uuid4().hex[:6]
    type_id = f"nemaki:probe{tag}"

    # A minimal document subtype. creatable=true so the instance below can exist.
    definition = {
        "id": type_id,
        "localName": type_id,
        "displayName": type_id,
        "queryName": type_id,
        "description": "type-delete constraint probe",
        "baseId": "cmis:document",
        "parentId": "cmis:document",
        "creatable": True,
        "fileable": True,
        "queryable": True,
        "controllablePolicy": False,
        "controllableACL": True,
        "fulltextIndexed": False,
        "includedInSupertypeQuery": True,
        "versionable": False,
        "contentStreamAllowed": "allowed",
        "typeMutability": {"create": True, "update": True, "delete": True},
    }
    r = S.post(f"{BASE}/browser/{REPO}", data={
        "cmisaction": "createType", "type": json.dumps(definition)}, timeout=180)
    print(f"createType {type_id}: {r.status_code}", flush=True)
    if not (200 <= r.status_code < 300):
        print(r.text[:400])
        sys.exit("could not create the probe type — nothing to prove")

    # 1) Delete it while EMPTY. This must succeed, or the probe proves nothing about the
    #    constraint (it would just mean deleteType is broken outright).
    r = S.post(f"{BASE}/browser/{REPO}", data={
        "cmisaction": "deleteType", "typeId": type_id}, timeout=180)
    print(f"deleteType while empty: {r.status_code} (expected 2xx)", flush=True)
    empty_ok = 200 <= r.status_code < 300

    # 2) Recreate, put ONE object in it, and try again. This must be refused.
    S.post(f"{BASE}/browser/{REPO}", data={
        "cmisaction": "createType", "type": json.dumps(definition)}, timeout=180)
    doc = S.post(BR, files={"content": (f"probe-{tag}.txt", b"x", "text/plain")},
                 data={"cmisaction": "createDocument", "objectId": ROOT,
                       "propertyId[0]": "cmis:name", "propertyValue[0]": f"probe-{tag}.txt",
                       "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": type_id},
                 timeout=300)
    print(f"createDocument of that type: {doc.status_code}", flush=True)
    if not (200 <= doc.status_code < 300):
        print(doc.text[:400])
        sys.exit("could not create an instance — the constraint cannot be exercised")
    doc_id = oid(doc.json())

    r = S.post(f"{BASE}/browser/{REPO}", data={
        "cmisaction": "deleteType", "typeId": type_id}, timeout=180)
    print(f"deleteType while POPULATED: {r.status_code} (expected 409 constraint)", flush=True)
    refused = r.status_code == 409
    body = r.text[:300]

    # Clean up whatever survived, in either direction.
    S.post(BR, data={"cmisaction": "delete", "objectId": doc_id,
                     "allVersions": "true"}, timeout=180)
    S.post(f"{BASE}/browser/{REPO}", data={"cmisaction": "deleteType", "typeId": type_id},
           timeout=180)

    print("\n== VERDICT ==", flush=True)
    print(f"  empty type deletable      : {empty_ok}", flush=True)
    print(f"  populated type refused    : {refused}", flush=True)
    if not empty_ok:
        print("  INCONCLUSIVE: deleteType does not work even on an empty type.", flush=True)
    elif refused:
        print("  The guard fires. If this is the first run that says so, the stub"
              "\n  TypeManagerImpl.checkTypeHasInstances has been wired to existContent.",
              flush=True)
    else:
        print(f"  REGRESSION: a populated type was deleted (response: {body!r}).", flush=True)
        print("  The guard was wired on 2026-08-12 and this probe has passed since."
              "\n  Check TypeManagerImpl.checkTypeHasInstances and the ContentDaoService"
              "\n  injection in serviceContext.xml before looking anywhere else.", flush=True)
        sys.exit(1)


main()
