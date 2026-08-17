"""RX1 の真因を絞る: 再索引の時間の内訳と、CouchDB の 1 回あたりのレイテンシ。

## なぜこれが要るのか

全再索引のレートが規模とともに落ちる (5,615 で 70〜125 文書/秒、26,416 で 10.3、
97,693 で 2.6 まで低下)。**最有力だった仮説は実測で棄却済み**: 「未コミットのバッチを
検索クエリで検証するので全件が 1 件ずつ再索引される」は事実だったが、同一規模の A/B で
77.2s → 84.0s と**速くならなかった**。

つまり原因はまだ分かっていない。**推測を足すより、規模を変えて同じ数字を 2 回取る**方が早い。

## 何を出すか

1. **フェーズ内訳** — status API の `enumerationMs` / `indexingMs` / `verificationMs`。
   5,615 では 走査 1.7% / 索引 90.7% / 検証 0.4% だった。
2. **CouchDB の 1 回あたりのレイテンシ** — content 文書 GET / 添付メタデータ GET /
   添付本体 GET。5,615 でのベースラインは 7.3 / 5.8 / 42.9 ms (median)。
3. **1 文書あたりの索引時間** — `indexingMs / indexedCount`。5,615 では 7.2 ms で、
   **CouchDB の文書 GET 1 回分とほぼ同じ**。26,416 では 97 ms/文書 (13 倍)。

## 読み方

規模を上げて再実行し、

- **CouchDB のレイテンシが伸びていれば** → CouchDB / OS のキャッシュ常駐性 (環境要因)。
  対処はメモリ・ストレージ・compaction の話であってコードではない。
- **横ばいなのに 1 文書あたりの索引時間が伸びていれば** → Solr 側かアプリ側。
  そのとき `indexingMs` を CouchDB 読みと Solr 往復にさらに割る。

**ばらつきが大きい**点に注意。同じ 5,615 で 44.8s / 77.2s / 84.0s と振れた。
**単発の比較で「速くなった/遅くなった」と言わないこと** — 一度それで誤った主張を書いた。

前提: nb33 スタック。**再索引は索引を消して作り直す**ので、他の測定と同時に走らせないこと。
"""
import json
import random
import statistics
import sys
import time

import requests

BASE = "http://localhost:8080/core"
REPO = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "bedroom"
COUCH = f"http://localhost:5984/{REPO}"

S = requests.Session()
S.auth = ("admin", "admin")
S.headers["X-Requested-With"] = "XMLHttpRequest"
C = requests.Session()
C.auth = ("admin", "password")


def couch_latencies(samples=60):
    """Time the three CouchDB reads the indexing phase makes per document."""
    r = C.post(f"{COUCH}/_design/_repo/_view/documents",
               json={"include_docs": True, "reduce": False, "limit": 300}, timeout=300)
    docs = [x.get("doc") for x in r.json().get("rows", []) if x.get("doc")]
    if not docs:
        return {}
    ids = [d["_id"] for d in docs]
    attachments = [d.get("attachmentNodeId") for d in docs if d.get("attachmentNodeId")]

    def timed(fn, n):
        out = []
        for _ in range(n):
            t = time.time()
            fn()
            out.append((time.time() - t) * 1000)
        out.sort()
        return out

    result = {"sampledDocuments": len(docs), "withAttachments": len(attachments)}
    t = timed(lambda: C.get(f"{COUCH}/{random.choice(ids)}", timeout=60), samples)
    result["contentDocGetMs"] = {"median": round(statistics.median(t), 2),
                                 "p95": round(t[int(len(t) * 0.95)], 2)}
    if attachments:
        picks = attachments[:samples]
        t = timed(lambda: C.get(f"{COUCH}/{random.choice(picks)}", timeout=60), samples)
        result["attachmentMetadataGetMs"] = {"median": round(statistics.median(t), 2),
                                             "p95": round(t[int(len(t) * 0.95)], 2)}
        meta = C.get(f"{COUCH}/{picks[0]}", timeout=60).json()
        names = list((meta.get("_attachments") or {}).keys())
        if names:
            t = timed(lambda: C.get(f"{COUCH}/{picks[0]}/{names[0]}", timeout=60), max(20, samples // 3))
            result["attachmentBodyGetMs"] = {"median": round(statistics.median(t), 2),
                                             "p95": round(t[int(len(t) * 0.95)], 2)}
    return result


def reindex_with_breakdown():
    t0 = time.time()
    r = S.post(f"{BASE}/api/v1/cmis/repositories/{REPO}/search-engine/reindex", timeout=300)
    if not (200 <= r.status_code < 300):
        raise SystemExit(f"reindex did not start: {r.status_code} {r.text[:200]}")
    st = {}
    while time.time() - t0 < 36000:
        st = S.get(f"{BASE}/api/v1/cmis/repositories/{REPO}/search-engine/status",
                   timeout=120).json()
        if st.get("status") in ("completed", "error", "cancelled"):
            break
        time.sleep(5)
    st["_wallMs"] = int((time.time() - t0) * 1000)
    return st


def main():
    print(f"repository: {REPO}", flush=True)
    print("\n== CouchDB per-read latency BEFORE the reindex ==", flush=True)
    before = couch_latencies()
    print(json.dumps(before, indent=2), flush=True)

    print("\n== full reindex ==", flush=True)
    st = reindex_with_breakdown()
    indexed = st.get("indexedCount") or 0
    wall = st.get("_wallMs") or 1
    enum_ms = st.get("enumerationMs") or 0
    idx_ms = st.get("indexingMs") or 0
    ver_ms = st.get("verificationMs") or 0

    print(f"  status={st.get('status')} indexed={indexed}/{st.get('totalDocuments')} "
          f"errors={st.get('errorCount')} silentDrop={st.get('silentDropCount')}", flush=True)
    print(f"  {'enumeration (folder walk)':42}: {enum_ms:>8} ms ({enum_ms/wall*100:5.1f}%)", flush=True)
    print(f"  {'indexing (build + couch reads + solr)':42}: {idx_ms:>8} ms ({idx_ms/wall*100:5.1f}%)", flush=True)

    # The indexing phase is ~90% of the wall clock, so it is split again. These four sum to just
    # under indexingMs; the remainder is loop overhead.
    rtg = st.get("solrRtgMs") or 0
    inc = st.get("incarnationMs") or 0
    bld = st.get("buildDocMs") or 0
    add = st.get("solrAddMs") or 0
    for label, value in (("solr realtime GET (per DOCUMENT)", rtg),
                         ("content incarnation (CouchDB)", inc),
                         ("build doc (attachments + Tika)", bld),
                         ("solr add (per BATCH)", add)):
        share = value / idx_ms * 100 if idx_ms else 0
        per = value / max(indexed, 1)
        print(f"    - {label:38}: {value:>8} ms ({share:5.1f}% of indexing, {per:6.2f} ms/doc)",
              flush=True)
    print(f"    - {'unattributed (loop overhead)':38}: {idx_ms-rtg-inc-bld-add:>8} ms", flush=True)

    print(f"  {'verification':42}: {ver_ms:>8} ms ({ver_ms/wall*100:5.1f}%)", flush=True)
    print(f"  {'wall':42}: {wall:>8} ms", flush=True)
    print(f"  {'unaccounted':42}: {wall-enum_ms-idx_ms-ver_ms:>8} ms", flush=True)
    per_doc = idx_ms / max(indexed, 1)
    print(f"\n  indexing per document: {per_doc:.2f} ms   overall rate: {indexed/(wall/1000):.1f} docs/s",
          flush=True)

    print("\n== CouchDB per-read latency AFTER (caches are warm now) ==", flush=True)
    after = couch_latencies()
    print(json.dumps(after, indent=2), flush=True)

    doc_get = (before.get("contentDocGetMs") or {}).get("median")
    print("\n== READING ==", flush=True)
    if doc_get:
        print(f"  indexing costs {per_doc:.1f} ms/document; one CouchDB document GET is "
              f"{doc_get} ms.", flush=True)
        ratio = per_doc / doc_get if doc_get else 0
        print(f"  ratio {ratio:.1f}x — at 5,615 objects this was ~1.0x (7.2 ms vs 7.3 ms).",
              flush=True)
    print("  Compare BOTH numbers against the 5,615 baseline:", flush=True)
    print("    contentDocGet 7.3ms / attachmentMetadata 5.8ms / attachmentBody 42.9ms,", flush=True)
    print("    indexing 7.2 ms/document, 90.7% of wall in the indexing phase.", flush=True)
    print("  CouchDB latencies grew  -> cache residency (environmental, not code).", flush=True)
    print("  Latencies flat, per-document indexing grew -> Solr side or application;", flush=True)
    print("    split indexingMs further before guessing.", flush=True)
    print("\n  Variance is large: the same 5,615 objects took 44.8s / 77.2s / 84.0s across runs.",
          flush=True)
    print("  Do not call a single comparison an improvement.", flush=True)

    print(json.dumps({"repository": REPO, "indexed": indexed, "wallMs": wall,
                      "enumerationMs": enum_ms, "indexingMs": idx_ms,
                      "verificationMs": ver_ms,
                      "solrRtgMs": rtg, "incarnationMs": inc,
                      "buildDocMs": bld, "solrAddMs": add,
                      "indexingMsPerDocument": round(per_doc, 2),
                      "couchBefore": before, "couchAfter": after}, indent=2), flush=True)


main()
