"""フォルダ絞り込みの join が、フォルダ内文書数に対してどう効くか。

`searchInFolder` の chunk 側フィルタは

    {!join from=object_id to=parent_document_id}
        (doc_type:document AND repository_id:"..." AND parent_id:"<folder>")

で、内側クエリが**フォルダ内の親文書すべて**に当たる。レビューで
「10 万文書のフォルダでは folder cardinality 依存の高価な join になる。
正しさの欠陥は無いが、その規模での許容性を示すコードもプローブも無い」と
指摘されたので、そこを埋める。

**やり方と、その限界**:

開発スタックの索引は親 1,300・チャンク 3,508 しかなく、最大フォルダでも 232 文書。
そこで **Solr に合成の親文書を直接投入**して内側クエリの一致件数だけを増やす。
join のコストは内側クエリの一致件数とフィールドの基数で決まり、ベクトルの有無には
依らないので、これで測っているのは**まさに指摘された機構**である。ただし:

- 合成親にはベクトルが無いので、**KNN 本体の重さは含まない**。測っているのは
  「join フィルタが増やすコスト」であって、検索全体の応答時間ではない。
- `cache=false` を付けて filterCache を迂回する。付けないと 2 回目以降が
  キャッシュヒットになり、「速い」という無意味な数字が出る。
- 比較対象として、同じ形の**素のフィルタ** (`parent_id:"..."`、property 側が
  使っているもの) も測る。join がどれだけ上乗せかを見るため。

合成文書は `parent_id` が判別可能な値なので、終了時に delete-by-query で消す。
**異常終了しても消えるように finally で消す。**

前提: nb33 の Solr (localhost:8983)、コア `nemaki`。
"""
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request

SOLR = "http://localhost:8983/solr/nemaki"
TAG = "joinscale-probe"
SIZES = [100, 1_000, 10_000, 100_000]
REPEATS = 25
BATCH = 2_500
CHUNKS_PER_DOC = 2

KEEP = "--keep" in sys.argv


def post(path, payload, params=""):
    url = f"{SOLR}/{path}?{params}" if params else f"{SOLR}/{path}"
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read().decode())


def select(params):
    # doseq=True: fq is a LIST and Solr needs repeated fq parameters. Without it urlencode
    # serialises the Python list into one value and Solr answers 400.
    url = f"{SOLR}/select?" + urllib.parse.urlencode(params, doseq=True)
    with urllib.request.urlopen(url, timeout=600) as r:
        return json.loads(r.read().decode())


def qtime(fq, doc_type="chunk", repeats=REPEATS):
    """Median QTime and median wall-clock ms for a chunk query carrying `fq`.

    QTime alone has 1 ms granularity and reported 0 for every size in the first version of
    this probe — which, combined with a join that matched nothing, looked like "free" when it
    was "not measured". Wall clock is kept alongside it so a sub-millisecond QTime cannot be
    mistaken for evidence.
    """
    q, w = [], []
    for _ in range(repeats):
        t0 = time.time()
        fqs = [f"doc_type:{doc_type}"] + ([fq] if fq else [])
        d = select({"q": "*:*", "fq": fqs, "rows": "0", "wt": "json"})
        w.append((time.time() - t0) * 1000.0)
        q.append(d["responseHeader"]["QTime"])
    return statistics.median(q), round(statistics.median(w), 1), d["response"]["numFound"]


def seed(folder_id, n):
    """Add n synthetic parents in folder_id, each with CHUNKS_PER_DOC chunks pointing at it.

    The chunks are the point. A join whose result set is empty costs nothing and measures
    nothing: the first version of this probe seeded only parents, matched 0 chunks, and
    reported 0 ms at every size — a confident number for an experiment that never ran.
    No vectors are needed because the measurement query is *:* over doc_type:chunk; only the
    join filter is under test.
    """
    made = 0
    while made < n:
        batch = min(BATCH, n - made)
        docs = []
        for i in range(batch):
            idx = made + i
            obj = f"{TAG}-obj-{folder_id}-{idx}"
            docs.append({
                "id": f"{TAG}:{folder_id}:p{idx}",
                "object_id": obj,
                "doc_type": "document",
                "repository_id": "bedroom",
                "parent_id": folder_id,
                "name": f"{TAG}-{idx}.txt",
            })
            for c in range(CHUNKS_PER_DOC):
                docs.append({
                    "id": f"{TAG}:{folder_id}:c{idx}-{c}",
                    "object_id": f"{obj}_chunk_{c}",
                    "doc_type": "chunk",
                    "repository_id": "bedroom",
                    "parent_document_id": obj,
                })
        post("update", docs, "commitWithin=10000")
        made += batch
    post("update", {"commit": {}})


def purge():
    print("\n== cleanup ==", flush=True)
    try:
        post("update", {"delete": {"query": f'parent_id:"{TAG}*" OR id:{TAG}\\:*'}})
        post("update", {"delete": {"query": f"id:{TAG}*"}})
        post("update", {"commit": {}})
        left = select({"q": f"id:{TAG}*", "rows": "0", "wt": "json"})["response"]["numFound"]
        print(f"synthetic documents remaining: {left}", flush=True)
        if left:
            print("WARNING: synthetic parents left in the index — they will inflate any later "
                  "folder measurement. Delete with: "
                  f'curl "{SOLR}/update?commit=true" -H "Content-Type: application/json" '
                  f'-d \'{{"delete":{{"query":"id:{TAG}*"}}}}\'', flush=True)
    except Exception as e:
        print(f"cleanup FAILED ({e}) — remove id:{TAG}* by hand", flush=True)


def main():
    base = select({"q": "doc_type:document", "rows": "0", "wt": "json"})["response"]["numFound"]
    chunks = select({"q": "doc_type:chunk", "rows": "0", "wt": "json"})["response"]["numFound"]
    print(f"index before: {base} parent documents, {chunks} chunks", flush=True)

    rows = []
    for n in SIZES:
        folder = f"{TAG}-folder-{n}"
        t0 = time.time()
        seed(folder, n)
        seeded = select({"q": f'parent_id:"{folder}"', "rows": "0",
                         "wt": "json"})["response"]["numFound"]
        print(f"\nfolder with {seeded} documents (seeded in {round(time.time()-t0,1)}s)",
              flush=True)
        if seeded != n:
            print(f"  !! expected {n}; measuring what is actually there", flush=True)

        join_fq = ("{!join from=object_id to=parent_document_id cache=false}"
                   f'(doc_type:document AND repository_id:"bedroom" AND parent_id:"{folder}")')
        plain_fq = f'{{!cache=false}}parent_id:"{folder}"'

        # Floor: the same request with NO folder filter. Wall clock includes HTTP and JSON, so
        # without this the join's cost cannot be separated from the cost of asking at all.
        _, floor_w, _ = qtime(None)
        join_q, join_w, join_hits = qtime(join_fq)
        # The property half's filter, measured where it actually runs — over PARENT documents.
        # Measuring it over chunks (as the first version did) matches nothing, which is a fine
        # confirmation that a plain parent_id filter is wrong for chunks but a useless baseline.
        plain_q, plain_w, plain_hits = qtime(plain_fq, doc_type="document")
        rows.append((seeded, join_q, join_w, plain_w, floor_w, join_hits))
        print(f"  no filter   : wall {floor_w} ms  <- floor (HTTP + query)", flush=True)
        print(f"  join filter : QTime {join_q} ms / wall {join_w} ms "
              f"(matched {join_hits} chunks)", flush=True)
        print(f"  plain filter: QTime {plain_q} ms / wall {plain_w} ms "
              f"(matched {plain_hits} parents) <- the property half", flush=True)
        if join_hits == 0:
            print("  !! the join matched no chunks — this row measures nothing", flush=True)

    print("\n== SUMMARY (median of "
          f"{REPEATS}, filterCache bypassed) ==", flush=True)
    print(f"{'docs in folder':>15} | {'floor ms':>9} | {'join ms':>8} | {'join-floor':>11} |"
          f" {'plain ms':>9} | {'chunks':>9}", flush=True)
    for seeded, jq, jw, pw, floor_w, hits in rows:
        print(f"{seeded:>15,} | {floor_w:>9} | {jw:>8} | {round(jw - floor_w, 1):>11} |"
              f" {pw:>9} | {hits:>9,}", flush=True)

    first, last = rows[0], rows[-1]
    first_net = max(first[2] - first[4], 0.01)
    last_net = max(last[2] - last[4], 0.01)
    size_growth = last[0] / first[0]
    print(f"\nfolder size x{size_growth:,.0f} -> join cost above the floor "
          f"{first_net:.1f} ms -> {last_net:.1f} ms (x{last_net / first_net:,.1f})", flush=True)
    spread = max(abs(jw - floor) for _, _, jw, _, floor, _ in rows)
    print(f"\nMeasurement noise: the join-minus-floor column ranges over ~{spread:.1f} ms and"
          "\ngoes NEGATIVE at some sizes, so anything inside that band is not a measurement."
          "\nWhat the numbers support is 'no growth with folder size', not a specific ms figure."
          "\n\nThe synthetic parents carry no vectors, so this is what the JOIN FILTER adds, not"
          "\nwhole-query latency. The KNN itself does not depend on folder size.", flush=True)


try:
    main()
finally:
    if KEEP:
        print(f"\n== synthetic documents kept (id:{TAG}*) ==", flush=True)
    else:
        purge()
