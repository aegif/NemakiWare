"""createDocument が並列化しない原因を、CouchDB の view インデックス更新待ちに絞り込む。

スレッドダンプ (`create_throughput_probe.py`) で、16 並列の createDocument のうち
**32/34 スレッドが `CloudantClientWrapper.queryView` で park している**ことが分かった。
呼び出し元は

    ObjectServiceImpl.createDocument
      → ExceptionServiceImpl.nameConstraintViolation
        → ContentDaoServiceImpl.getChildrenNames
          → view `childrenNames`

つまり **CMIS の名前一意性チェック**。作成のたびに親の子名一覧を view に聞く。

仮説: view クエリ自体は速い (アイドルで 9〜13ms) が、CouchDB の view は既定で
**クエリ時に未反映の書き込みを索引してから答える**。`_design/_repo` には **51 本の view**
が入っているので、文書 1 件の書き込みごとに 51 本の map を JS query server で回す。
書き込みと読み取りが交互に来る createDocument のループでは、これが毎回効く。

この仮説の決定的な検証は **`update=false` (stale=ok) との対比**。同じ書き込み負荷の下で

- `update=true` (既定) が遅く、
- `update=false` が速い

なら、遅いのは view の実行ではなく**索引更新待ち**。両方遅いなら仮説は外れで、
CouchDB 自体か接続側を疑う。

前提: nb33 スタック。他の測定と同時に走らせないこと。
"""
import concurrent.futures
import json
import statistics
import threading
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
COUCH = "http://localhost:5984/bedroom"
ROOT = "e02f784f8360a02cc14d1314c10038ff"
WRITERS = 8
SAMPLES = 12

BODY = b"view index wait probe"


def session():
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    s.mount("http://", requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32))
    return s


A = session()
C = requests.Session()
C.auth = ("admin", "password")


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def mkfolder(name, parent):
    return oid(A.post(BR, data={
        "cmisaction": "createFolder", "objectId": parent,
        "propertyId[0]": "cmis:name", "propertyValue[0]": name,
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=180).json())


def timed_view(update):
    """One childrenNames query; returns seconds. `update` picks the index-wait behaviour."""
    t0 = time.time()
    C.get(f"{COUCH}/_design/_repo/_view/childrenNames",
          params={"key": json.dumps(ROOT), "update": update}, timeout=300)
    return time.time() - t0


def pending():
    info = C.get(f"{COUCH}/_design/_repo/_info", timeout=60).json()["view_index"]
    return info["updates_pending"]["total"], info["updater_running"]


def measure(label, update, stop, folders, tag):
    """Sample the view under whatever load is running, alternating nothing else."""
    times = []
    for _ in range(SAMPLES):
        if stop.is_set():
            break
        times.append(timed_view(update))
        time.sleep(0.25)
    if not times:
        return None
    times.sort()
    print(f"  {label:<28} median {statistics.median(times):6.3f}s  "
          f"min {times[0]:6.3f}s  max {times[-1]:6.3f}s  (n={len(times)})", flush=True)
    return statistics.median(times)


def writer(stop, folder, tag, counter, lock):
    s = session()
    i = 0
    while not stop.is_set():
        i += 1
        try:
            s.post(BR, files={"content": (f"{tag}-{i}.txt", BODY, "text/plain")},
                   data={"cmisaction": "createDocument", "objectId": folder,
                         "propertyId[0]": "cmis:name", "propertyValue[0]": f"{tag}-{i}.txt",
                         "propertyId[1]": "cmis:objectTypeId",
                         "propertyValue[1]": "cmis:document"}, timeout=300)
            with lock:
                counter[0] += 1
        except Exception:
            pass


def main():
    tag = uuid.uuid4().hex[:4]
    container = mkfolder(f"vwait-{tag}", ROOT)
    folders = [mkfolder(f"vwait-{tag}-{i}", container) for i in range(WRITERS)]
    views = len(C.get(f"{COUCH}/_design/_repo", timeout=60).json().get("views", {}))
    print(f"container vwait-{tag}; _design/_repo holds {views} views\n", flush=True)

    print("== idle (no writers) ==", flush=True)
    p, running = pending()
    print(f"  updates_pending={p} updater_running={running}", flush=True)
    stop = threading.Event()
    idle_true = measure("update=true", "true", stop, folders, tag)
    idle_false = measure("update=false", "false", stop, folders, tag)

    print(f"\n== under {WRITERS} concurrent createDocument ==", flush=True)
    counter = [0]
    lock = threading.Lock()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WRITERS) as ex:
        for f in folders:
            ex.submit(writer, stop, f, tag, counter, lock)
        time.sleep(8)  # let the load establish
        p, running = pending()
        print(f"  updates_pending={p} updater_running={running}", flush=True)
        load_true = measure("update=true", "true", stop, folders, tag)
        load_false = measure("update=false", "false", stop, folders, tag)
        p2, running2 = pending()
        print(f"  updates_pending={p2} updater_running={running2}", flush=True)
        made = counter[0]
        stop.set()

    print(f"\n{made} documents created during the loaded window", flush=True)
    print("\n== VERDICT ==", flush=True)
    if load_true and load_false:
        print(f"  loaded update=true / update=false = {load_true/max(load_false,1e-9):.0f}x",
              flush=True)
        print("  A large ratio means the query is waiting for the INDEX, not running the view."
              "\n  A ratio near 1 refutes the hypothesis — look at CouchDB or the connector.",
              flush=True)
    print(json.dumps({"views": views, "idleTrue": idle_true, "idleFalse": idle_false,
                      "loadTrue": load_true, "loadFalse": load_false,
                      "created": made, "container": container}, indent=2), flush=True)
    print(f"cleanup: delete folder vwait-{tag} ({container})", flush=True)


main()
