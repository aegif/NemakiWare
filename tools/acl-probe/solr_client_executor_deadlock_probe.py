"""同時書き込みで Solr クライアントが**恒久デッドロック**しないことを実機で確かめる。

## 何が起きていたか (2026-08-14 実測)

RAG 有効なリポジトリに同時に書き込むと、CMIS API 全体が**永久に**止まった。
`jcmd Thread.dump_to_file -format=json` で採取した内訳:

- `HttpJdkSolrClient-2-thread-*` の **4 スレッド全部**が
  `PipedInputStream.awaitSpace` (= リクエストボディをパイプに書いている途中)。
  その上のフレームは `JavaBinCodec.writeFloat` — RAG の埋め込みベクトル。
- 42 スレッドが `HttpClientImpl.send` で応答待ち。
- 25 スレッドが `RAGIndexingServiceImpl.indexToSolr` の per-ragId ストライプロック待ち。
- **数分空けた 2 つのダンプが完全に同一** (75 スレッド、出入りゼロ)。全コンテナ CPU ≒ 0%。
  同等の deleteByQuery を curl から投げると Solr は 0.02 秒で返す。
- 設定した 30 秒のリクエストタイムアウトは**発火しない** (応答を待っているのではなく、
  書いている途中で止まっているため)。

真因は SolrJ 10.0.0 の既定エグゼキュータ (bytecode で確認):

    MDCAwareThreadPoolExecutor(core=4, max=256, 60s, LinkedBlockingQueue(1024))

を作り、**同じ実体**を `HttpClient.Builder.executor(...)` にも渡している。
`ThreadPoolExecutor` は**キューが満杯になるまで core を超えて増えない**ので実質 4 スレッド。
1 リクエストは「ボディを書く側」と「パイプを読む側」で **2 枠**を同時に要る。
同時 4 本でボディがパイプバッファ (既定 1 KiB) を超えると、4 枠が全部書き手になり、
読み手が永久に起動しない。RAG のボディは常に 1 KiB を超えるので RAG が最初に踏む。

Nemaki 側の executor は CallerRunsPolicy なので、この停止は CMIS リクエストスレッドまで
逆流し、**API 全体が止まる。回復は JVM 再起動のみ。**

対処は `SolrHttpExecutor` を `withExecutor(...)` で両方のクライアントに渡すこと。

## このプローブが出すもの

同時 `CONCURRENCY` 本の createDocument を投げ、**全部が返るか**と所要時間。
修正前はここで止まったまま返らない (タイムアウトで失敗する)。
併せて JVM のスレッド名を数え、新しいエグゼキュータが実際に効いているか
(`solr-http-cmis-*` / `solr-http-rag-*` が居て、4 本を超えて増えられるか) を見る。

**遅いかどうかを測るプローブではない。** 判定は「返るか否か」。

前提: nb33 スタック、admin:admin。他の測定と同時に走らせないこと。
"""
import concurrent.futures
import json
import subprocess
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
REPO = sys.argv[1] if len(sys.argv) > 1 else "bedroom"
BR = f"{BASE}/browser/{REPO}/root"
CONCURRENCY = 12
PER_REQUEST_TIMEOUT = 120

# Large enough that the serialized Solr body cannot fit in the 1 KiB pipe buffer even
# before the RAG vectors are added.
BODY = ("NemakiWare deadlock probe. " * 400).encode()


def session():
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    return s


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def solr_http_threads():
    """Names of the JVM threads belonging to the Solr HTTP executors."""
    try:
        subprocess.run(["docker", "exec", "nb33-core-1", "jcmd", "1",
                        "Thread.dump_to_file", "-overwrite", "-format=json", "/tmp/probe_td.json"],
                       check=True, capture_output=True, timeout=120)
        raw = subprocess.run(["docker", "exec", "nb33-core-1", "cat", "/tmp/probe_td.json"],
                             check=True, capture_output=True, timeout=120).stdout
        data = json.loads(raw)
    except Exception as e:
        return {"error": str(e)}

    def walk(container):
        out = []
        for key, value in container.items():
            if key == "threads":
                out.extend(value)
            elif isinstance(value, dict):
                out.extend(walk(value))
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, dict):
                        out.extend(walk(item))
        return out

    counts = {"solr-http-cmis": 0, "solr-http-rag": 0, "HttpJdkSolrClient": 0, "awaitSpace": 0}
    for t in walk(data):
        name = t.get("name") or ""
        for prefix in ("solr-http-cmis", "solr-http-rag", "HttpJdkSolrClient"):
            if name.startswith(prefix):
                counts[prefix] += 1
        if any("awaitSpace" in f for f in (t.get("stack") or [])):
            counts["awaitSpace"] += 1
    return counts


def main():
    tag = uuid.uuid4().hex[:6]
    s = session()

    # Ask the repository where its root is rather than hard-coding an id, so this probe works
    # against canopy as well as bedroom.
    info = s.get(f"{BASE}/browser/{REPO}?cmisselector=repositoryInfo", timeout=120).json()
    root_id = info[REPO]["rootFolderId"] if REPO in info else list(info.values())[0]["rootFolderId"]
    parent = oid(s.post(BR, data={
        "cmisaction": "createFolder", "objectId": root_id,
        "propertyId[0]": "cmis:name", "propertyValue[0]": f"dlprobe-{tag}",
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=120).json())
    print(f"repository={REPO} parent={parent} concurrency={CONCURRENCY}", flush=True)

    def create(i):
        cs = session()
        t0 = time.time()
        try:
            r = cs.post(BR, files={"content": (f"dl-{tag}-{i}.txt", BODY, "text/plain")},
                        data={"cmisaction": "createDocument", "objectId": parent,
                              "propertyId[0]": "cmis:name",
                              "propertyValue[0]": f"dl-{tag}-{i}.txt",
                              "propertyId[1]": "cmis:objectTypeId",
                              "propertyValue[1]": "cmis:document"},
                        timeout=PER_REQUEST_TIMEOUT)
            return i, r.status_code, time.time() - t0, None
        except Exception as e:
            return i, None, time.time() - t0, type(e).__name__

    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        results = list(pool.map(create, range(CONCURRENCY)))
    wall = time.time() - t0

    ok = [r for r in results if r[1] and 200 <= r[1] < 300]
    hung = [r for r in results if r[1] is None]
    print(f"\n  completed {len(ok)}/{CONCURRENCY} in {wall:.1f}s "
          f"(slowest {max(r[2] for r in results):.1f}s)", flush=True)
    for i, code, secs, err in results:
        if code is None or not (200 <= code < 300):
            print(f"    #{i}: {code or err} after {secs:.1f}s", flush=True)

    print("\n== JVM threads ==", flush=True)
    print(json.dumps(solr_http_threads(), indent=2), flush=True)

    # Clean up regardless of the verdict.
    try:
        s.post(BR, data={"cmisaction": "deleteTree", "objectId": parent}, timeout=600)
    except Exception:
        print("  (cleanup of the probe folder failed — remove dlprobe-* by hand)", flush=True)

    print("\n== VERDICT ==", flush=True)
    if hung:
        print(f"  DEADLOCK: {len(hung)} request(s) never returned within {PER_REQUEST_TIMEOUT}s.",
              flush=True)
        print("  Check `solr-http-*` above: 0 means withExecutor is not in effect and the client",
              flush=True)
        print("  is back on SolrJ's 4-thread default. A non-zero `awaitSpace` count is the",
              flush=True)
        print("  signature of the pipe starvation itself.", flush=True)
        sys.exit(1)
    print(f"  All {CONCURRENCY} concurrent writes returned. No starvation.", flush=True)


main()
