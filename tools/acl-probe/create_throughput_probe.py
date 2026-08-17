"""createDocument が並列化しない件を、スレッドダンプで実地に切り分ける。

一括投入 (`bulk_seed_documents.py`) の速度を上げようとして分かったこと:
**16 並列でも約 1.2 文書/秒**しか出ず、単発の createDocument が 1.05〜1.35 秒なので
**並列度が実質 1 に潰れている**。RAG を止めても、親フォルダを分散させても変わらない。

推測で当たりを付けても外れるので、**負荷をかけている最中の JVM のスレッドダンプ**を
取って、リクエストスレッドが実際に何を待っているかを読む。これが唯一の決定的な観測。

読み方:

- 待ちが 1 つのモニタ/ロックに集中していれば直列化点。`- waiting to lock <0x...>` の
  アドレスでグループ化して、同じアドレスを待つスレッド数を数える。
- どのスレッドも RUNNABLE でソケット read (CouchDB / Solr) にいるなら、直列化ではなく
  下流のレイテンシ。この場合は「1.2/s」の説明が付かない (16 並列なら 16 倍出るはず) ので、
  下流側に単一接続やプールサイズ 1 が無いかを見る。
- スレッド数そのものが少なければ、Tomcat 側の maxThreads か、その手前の
  コネクタ/フィルタで絞られている。

前提: nb33 スタック、admin:admin。他の測定と同時に走らせないこと。
"""
import collections
import concurrent.futures
import json
import re
import subprocess
import sys
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
BR = f"{BASE}/browser/bedroom/root"
ROOT = "e02f784f8360a02cc14d1314c10038ff"
CORE = "nb33-core-1"
WORKERS = 16
DOCS = 64
DUMPS = 3

BODY = b"create throughput probe fixture, deliberately small so IO is not the story"


def session():
    s = requests.Session()
    s.auth = ("admin", "admin")
    s.headers["X-Requested-With"] = "XMLHttpRequest"
    s.mount("http://", requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32))
    return s


A = session()


def oid(j):
    return (j.get("succinctProperties") or {}).get("cmis:objectId") \
        or j["properties"]["cmis:objectId"]["value"]


def mkfolder(name, parent):
    return oid(A.post(BR, data={
        "cmisaction": "createFolder", "objectId": parent,
        "propertyId[0]": "cmis:name", "propertyValue[0]": name,
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=180).json())


def mkdoc(s, folder, name, timings):
    t0 = time.time()
    r = s.post(BR, files={"content": (f"{name}.txt", BODY, "text/plain")},
               data={"cmisaction": "createDocument", "objectId": folder,
                     "propertyId[0]": "cmis:name", "propertyValue[0]": f"{name}.txt",
                     "propertyId[1]": "cmis:objectTypeId",
                     "propertyValue[1]": "cmis:document"}, timeout=300)
    timings.append(time.time() - t0)
    if not (200 <= r.status_code < 300):
        raise RuntimeError(f"{r.status_code}: {r.text[:120]}")


def dump():
    """jcmd Thread.dump_to_file, NOT jstack.

    jstack does not show virtual threads, and Tomcat serves requests on them here — the first
    version of this probe reported "2 request threads" while sixteen creates were in flight,
    which reads as "nothing is happening" rather than "the tool is blind". Only the JSON dump
    walks the virtual threads.
    """
    pid = subprocess.run(["docker", "exec", CORE, "sh", "-c", "jcmd -l | head -1"],
                         capture_output=True, text=True, timeout=60).stdout.split()[0]
    subprocess.run(["docker", "exec", CORE, "jcmd", pid, "Thread.dump_to_file",
                    "-overwrite", "-format=json", "/tmp/vt.json"],
                   capture_output=True, text=True, timeout=180)
    return subprocess.run(["docker", "exec", CORE, "cat", "/tmp/vt.json"],
                          capture_output=True, text=True, timeout=180).stdout


def walk(node, out):
    for t in node.get("threads", []):
        out.append(t)
    for c in node.get("threadContainers", []):
        walk(c, out)


def analyse(text):
    """Group the threads that are actually inside a createDocument by where they are stuck."""
    root = json.loads(text)["threadDump"]
    threads = []
    for c in root.get("threadContainers", []):
        walk(c, threads)
    walk(root, threads)

    in_create = []
    for t in threads:
        stack = t.get("stack") or []
        if any("createDocument" in f or "CmisServiceImpl" in f or "ObjectServiceImpl" in f
               for f in stack):
            in_create.append(t)

    blocked_at = collections.Counter()
    deepest_nemaki = collections.Counter()
    for t in in_create:
        stack = t.get("stack") or []
        # Frame 0 is where the thread actually is. Strip the "at " and the source suffix so
        # sixteen threads in the same place group into one line.
        if stack:
            blocked_at[re.sub(r'\(.*\)$', '', stack[0].strip()[3:])] += 1
        nemaki = [f for f in stack if "jp.aegif.nemaki" in f]
        if nemaki:
            deepest_nemaki[re.sub(r'\(.*\)$', '', nemaki[0].strip()[3:])] += 1
    return len(threads), len(in_create), blocked_at, deepest_nemaki


def main():
    tag = uuid.uuid4().hex[:4]
    container = mkfolder(f"cthr-{tag}", ROOT)
    folders = [mkfolder(f"cthr-{tag}-{i}", container) for i in range(WORKERS)]
    print(f"container cthr-{tag}, {len(folders)} folders (one per worker so the parent "
          f"folder lock cannot be the answer)", flush=True)

    sessions = [session() for _ in range(WORKERS)]
    timings = []
    dumps = []
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS + 1) as ex:
        futures = [ex.submit(mkdoc, sessions[i % WORKERS], folders[i % WORKERS],
                             f"cthr-{tag}-{i}", timings) for i in range(DOCS)]
        # Let the load establish, then sample.
        time.sleep(6)
        for k in range(DUMPS):
            dumps.append(dump())
            print(f"  dump {k+1}/{DUMPS} taken at t+{time.time()-t0:.1f}s "
                  f"({len(timings)} creates done)", flush=True)
            time.sleep(4)
        done = sum(1 for f in futures if not f.exception()) if False else 0
        failed = 0
        for f in futures:
            try:
                f.result()
                done += 1
            except Exception as e:
                failed += 1
                if failed <= 2:
                    print(f"  !! {e}", flush=True)
    elapsed = time.time() - t0

    print(f"\n{done} created, {failed} failed in {elapsed:.1f}s "
          f"= {done/elapsed:.2f}/s with {WORKERS} workers", flush=True)
    if timings:
        ts = sorted(timings)
        print(f"per-create latency: min {ts[0]:.2f}s  median {ts[len(ts)//2]:.2f}s  "
              f"max {ts[-1]:.2f}s", flush=True)
        print(f"if this were parallel, {WORKERS} workers at median {ts[len(ts)//2]:.2f}s "
              f"would give {WORKERS/ts[len(ts)//2]:.1f}/s", flush=True)

    for k, text in enumerate(dumps, 1):
        total, n, blocked, frames = analyse(text)
        print(f"\n== dump {k}: {total} threads total, {n} inside createDocument ==", flush=True)
        print("  where they actually are (frame 0):", flush=True)
        for f, c in blocked.most_common(6):
            print(f"    {c:>3}x {f}", flush=True)
        print("  deepest nemaki frame:", flush=True)
        for f, c in frames.most_common(6):
            print(f"    {c:>3}x {f}", flush=True)

    for k, d in enumerate(dumps, 1):
        with open(f"/tmp/create-throughput-dump-{k}.json", "w") as fh:
            fh.write(d)
    print("\nfull dumps: /tmp/create-throughput-dump-{1,2,3}.json", flush=True)
    print(f"cleanup: delete folder cthr-{tag} ({container})", flush=True)
    print(json.dumps({"container": container, "tag": tag}), flush=True)


main()
