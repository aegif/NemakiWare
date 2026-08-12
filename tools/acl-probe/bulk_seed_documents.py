"""F3 の規模を上げるための一括投入 (計測用の道具、製品機能ではない)。

**TCK / 全スイートと同時に走らせないこと。** ライブサーバを共有しているので、
投入中の TCK は `rootFolderTest` の「root の子の数と getDescendants が一致しない」で
落ちる (実際に落とした)。文書はこの run 専用のコンテナ配下に作るようにしたので
root 直下は汚さないが、負荷そのものは避けられない。

`reindex_connection_watch.py` は「接続数のピークが文書数に対して伸びるか」を見る。
その判定には**規模を変えた 2 点以上**が要るので、リポジトリを数万件まで太らせる。

添付**あり**で作る。F3 のリークは添付のメタデータを取るために本体を開いていた経路に
あったので、添付の無い文書をいくら並べても当該経路を踏まない。

    python3 tools/acl-probe/bulk_seed_documents.py 20000 [--workers 12]

後始末は folder ごと deleteTree:

    python3 tools/acl-probe/bulk_seed_documents.py --cleanup

注意: RAG が有効だと 1 文書ごとに埋め込み生成が走って投入が遅い。計測対象は
**CMIS の全再索引**なので、投入中だけ RAG を止めても測りたいものは変わらない。
"""
import concurrent.futures
import json
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
ROOT = "e02f784f8360a02cc14d1314c10038ff"
PREFIX = "f3bulk"
# Small on purpose: createDocument takes a write lock on the PARENT folder, so many
# workers aimed at one folder serialise on it. Spreading the load across folders is what
# makes concurrency real here. Measured on the dev stack: 500-per-folder gave ~1 doc/s
# with 16 workers regardless of whether RAG was on.
PER_FOLDER = 25

BODY = (b"F3 scale fixture. The northern division reported growth while the southern "
        b"division contracted. Margins narrowed on freight costs across the quarter.")


def session():
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    a = requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32)
    s.mount("http://", a)
    return s


A = session()


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def mkfolder(s, name, parent=None):
    """Create under `parent`, defaulting to this run's own container — NOT the CMIS root.

    Seeding directly under the root broke the TCK's rootFolderTest, which counts the root's
    children and compares them against getDescendants: folders appearing mid-test made the two
    disagree. One container per run also makes cleanup a single deleteTree.
    """
    return oid(s.post(BR, data={
        "cmisaction": "createFolder", "objectId": parent or ROOT,
        "propertyId[0]": "cmis:name", "propertyValue[0]": name,
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=180).json())


def mkdoc(s, folder, name):
    r = s.post(BR, files={"content": (f"{name}.txt", BODY, "text/plain")},
               data={"cmisaction": "createDocument", "objectId": folder,
                     "propertyId[0]": "cmis:name", "propertyValue[0]": f"{name}.txt",
                     "propertyId[1]": "cmis:objectTypeId",
                     "propertyValue[1]": "cmis:document"}, timeout=300)
    # createDocument answers 201 Created — checking for 200 rejected every success.
    if not (200 <= r.status_code < 300):
        raise RuntimeError(f"{r.status_code}: {r.text[:120]}")
    return oid(r.json())


def cleanup():
    # This endpoint answers with `properties` (each value nested under "value"), NOT
    # `succinctProperties` — and asking for succinct=true does not change that. Reading the
    # succinct shape yielded empty names for every child, so an earlier cleanup cheerfully
    # reported "0 bulk folders" while 629 were sitting there. Read both shapes.
    r = A.get(f"{BR}?cmisselector=children&objectId={ROOT}&maxItems=5000", timeout=600)
    targets = []
    for o in r.json().get("objects", []):
        ob = o.get("object", {})
        succinct = ob.get("succinctProperties") or {}
        props = ob.get("properties") or {}
        name = succinct.get("cmis:name") or (props.get("cmis:name") or {}).get("value")
        oid_ = succinct.get("cmis:objectId") or (props.get("cmis:objectId") or {}).get("value")
        if name and str(name).startswith(PREFIX):
            targets.append((name, oid_))
    print(f"deleting {len(targets)} bulk folders", flush=True)
    for name, fid in targets:
        t0 = time.time()
        A.post(BR, data={"cmisaction": "deleteTree", "objectId": fid}, timeout=1800)
        print(f"  {name} in {round(time.time()-t0,1)}s", flush=True)
    print("done", flush=True)


def main():
    if "--cleanup" in sys.argv:
        cleanup()
        return
    total = int(sys.argv[1])
    workers = 12
    if "--workers" in sys.argv:
        workers = int(sys.argv[sys.argv.index("--workers") + 1])

    tag = uuid.uuid4().hex[:4]
    # One container under the root; every batch folder goes inside it.
    container = mkfolder(A, f"{PREFIX}-{tag}")
    folders = []
    for i in range((total + PER_FOLDER - 1) // PER_FOLDER):
        folders.append(mkfolder(A, f"{PREFIX}-{tag}-{i}", container))
    print(f"{len(folders)} folders, target {total} documents, {workers} workers", flush=True)

    sessions = [session() for _ in range(workers)]
    made = 0
    failed = 0
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futures = []
        for i in range(total):
            s = sessions[i % workers]
            folder = folders[i // PER_FOLDER]
            futures.append(ex.submit(mkdoc, s, folder, f"{PREFIX}-{tag}-{i}"))
        for n, f in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                f.result()
                made += 1
            except Exception as e:
                failed += 1
                if failed <= 3:
                    print(f"  !! {e}", flush=True)
            if n % 1000 == 0:
                rate = n / max(time.time() - t0, 0.001)
                print(f"  {n}/{total} ({rate:.0f}/s, {failed} failed)", flush=True)

    print(json.dumps({"created": made, "failed": failed,
                      "seconds": round(time.time() - t0, 1)}, indent=2), flush=True)


main()
