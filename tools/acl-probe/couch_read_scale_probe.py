"""CouchDB の 1 件読みは、データベースが大きくなると遅くなるのか。

## なぜこれを測るのか

2026-08-14 に全再索引の索引フェーズ (実時間の約 90%) を割ったところ、内訳はこうだった
(bedroom 5,615 オブジェクト):

    文書構築 (添付 + Tika)        63.6%   4.61 ms/文書
    content incarnation (CouchDB) 24.3%   1.76 ms/文書
    Solr realtime GET (文書ごと)   9.5%   0.69 ms/文書
    Solr add (バッチごと)          2.2%   0.16 ms/文書

**約 88% が CouchDB 読み**で、Solr 側は 12% に届かない。したがって RX1 (規模とともに
レートが 10.3 → 2.6 文書/秒に落ちる) の原因がどこかにあるとすれば、ほぼ確実に
CouchDB の読みである。残る仮説は ④ ehcache の working set 超過と
⑤ CouchDB の b-tree / ディスクキャッシュ。

**このプローブが切り分けるのはそのうち「データベースが大きいと 1 件読みが遅いのか」**。

## 2 つの機構を分けて測る

1. **b-tree の深さ** — `_all_docs?limit=1&startkey=...` は木を辿るだけで本体を読まない。
   文書数が 100 倍違えば深さは効くはずで、効かないならこの機構は消える。
2. **本体の読み** — 文書丸ごとの GET。本体サイズの差が乗るので、両者を並べて見る。

## 読み方

nb33 の bedroom は約 436,000 文書 / 568 MiB、canopy は約 4,500 文書 / 22 MiB
(**100 倍のサイズ差**)。ただし**どちらも OS のページキャッシュに載る**ので、

- **両者で差が出なければ** → b-tree の深さでは説明がつかない。残るのは
  「データベースがページキャッシュに載らなくなったとき」だけで、**RX1 の発火条件は
  メモリ量**ということになる (運用で対処できる = コードの問題ではない)。
- **bedroom が明確に遅ければ** → サイズそのものが効いている。キャッシュに載っていても
  遅いなら、メモリを積んでも解決しない。

**bedroom の 436,000 文書のうち本体は約 2,500 件で、残りは全部変更ログ**である点に注意。
つまりこの比較は「本体の件数」ではなく「データベース全体の大きさ」の比較になっている。
RX1 が本体件数ではなくデータベースの大きさで決まるなら、それ自体が発見。

## 使い方

    python3 tools/acl-probe/couch_read_scale_probe.py

**他の測定と同時に走らせないこと。** レイテンシを測るので負荷が乗ると意味が無い。
前提: nb33 スタック、CouchDB admin:password。
"""
import json
import random
import statistics
import sys
import time

import requests

COUCH = "http://localhost:5984"
DATABASES = sys.argv[1:] or ["bedroom", "canopy"]
SAMPLES = 120

C = requests.Session()
C.auth = ("admin", "password")


def percentiles(values):
    values = sorted(values)
    return {
        "median": round(statistics.median(values), 2),
        "p95": round(values[min(int(len(values) * 0.95), len(values) - 1)], 2),
    }


def timed(fn, n):
    out = []
    for _ in range(n):
        t = time.time()
        fn()
        out.append((time.time() - t) * 1000)
    return out


def measure(db):
    info = C.get(f"{COUCH}/{db}", timeout=60).json()
    doc_count = info["doc_count"]
    disk_mib = info["sizes"]["file"] / 1024 / 1024

    # A spread of ids to keep the b-tree seeks from all landing on the same page.
    rows = C.get(f"{COUCH}/{db}/_all_docs",
                 params={"limit": 2000, "include_docs": "false"}, timeout=300).json()["rows"]
    ids = [r["id"] for r in rows if not r["id"].startswith("_design")]
    if not ids:
        return {"database": db, "error": "no documents"}
    random.shuffle(ids)

    result = {
        "database": db,
        "docCount": doc_count,
        "diskMiB": round(disk_mib, 1),
        "sampledIds": len(ids),
    }

    # 1) b-tree seek only: one row, no body.
    result["seekOnlyMs"] = percentiles(timed(
        lambda: C.get(f"{COUCH}/{db}/_all_docs",
                      params={"limit": 1, "startkey": json.dumps(random.choice(ids))},
                      timeout=60), SAMPLES))

    # 2) whole-document GET: seek plus body.
    result["documentGetMs"] = percentiles(timed(
        lambda: C.get(f"{COUCH}/{db}/{random.choice(ids)}", timeout=60), SAMPLES))

    return result


def main():
    print(f"samples per measurement: {SAMPLES}\n", flush=True)
    results = []
    for db in DATABASES:
        r = measure(db)
        results.append(r)
        print(json.dumps(r, indent=2), flush=True)

    usable = [r for r in results if "seekOnlyMs" in r]
    if len(usable) < 2:
        return

    print("\n== READING ==", flush=True)
    biggest = max(usable, key=lambda r: r["docCount"])
    smallest = min(usable, key=lambda r: r["docCount"])
    size_ratio = biggest["docCount"] / max(smallest["docCount"], 1)
    seek_ratio = (biggest["seekOnlyMs"]["median"]
                  / max(smallest["seekOnlyMs"]["median"], 0.01))
    get_ratio = (biggest["documentGetMs"]["median"]
                 / max(smallest["documentGetMs"]["median"], 0.01))

    print(f"  {biggest['database']} holds {size_ratio:.0f}x the documents of "
          f"{smallest['database']} ({biggest['diskMiB']} MiB vs {smallest['diskMiB']} MiB).",
          flush=True)
    print(f"  b-tree seek is {seek_ratio:.2f}x, whole-document GET is {get_ratio:.2f}x.",
          flush=True)
    print("", flush=True)
    print("  Near 1.0x on both -> database SIZE does not cost anything while it still fits in",
          flush=True)
    print("    the page cache. RX1's scale term would then have to be cache EVICTION, i.e. it",
          flush=True)
    print("    fires when the database outgrows available memory — an operational condition,",
          flush=True)
    print("    not a code path. Say so rather than continuing to look for an algorithm.",
          flush=True)
    print("  Materially above 1.0x -> size itself costs, and adding memory will not fix it.",
          flush=True)
    print("", flush=True)
    print("  Both databases here fit in RAM, so this CANNOT observe the eviction case. It can",
          flush=True)
    print("    only rule the b-tree in or out.", flush=True)


main()
