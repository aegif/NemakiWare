"""基準 3 (可観測性) の再判定: ETA が実測の収束時刻と ±20% で一致するか。

## 測る対象の契約

`GET /core/api/v1/admin/search-index/propagation` は進行中の伝播ごとに
`expectedNodes` / `doneNodes` / `elapsedMs` / `estimatedRemainingMs` / `etaReliable` /
`runningInlineOnRequestThread` を返す。ETA は**この走査で実測したレートからの線形外挿**:

    remaining * elapsed / completed

null になるのは (a) caller-run 中、(b) 件数が数えられていない、(c) サンプル数が
閾値未満、のいずれか。台帳 E4 が明記するとおり **ETA は定常時のみ有効**で、
CallerRunsPolicy 発火中は FIFO 前提が崩れるので出さない設計。したがってこの
プローブは**単発の applyACL を他の負荷なしで**走らせる。

## 判定

各ポーリング時刻 t で ETA e が出ていたとき、実際の残り時間は
`actual_finish - t`。誤差を

    |e - actual_remaining| / actual_remaining

とし、**±20% 以内**を合格とする。

早期のサンプルは原理的に外れる (走査開始直後はレートが立っていない) ので、
**進捗 20% 以降のサンプルで 80% 以上が ±20% 以内**を合格ラインとする。
この線引きは先に宣言しておくもので、結果を見てから動かさない。全体の分布と
進捗十分位ごとの内訳も併せて出すので、線引きの妥当性は読み手が判断できる。

## 「落ちようがない」形にしないための条件

ETA が最後まで null なら誤差サンプルは 0 件になり、素朴に書くと「違反 0 件」で
合格に見える。以下はすべて **INCONCLUSIVE (exit 1)** として明示的に失敗させる:

- ETA を伴うサンプルが 1 件も無い
- `expectedNodes` が最後まで null (件数が数えられていない)
- ポーリングが 1 度も伝播を捉えられなかった (サブツリーが小さすぎ / 終わるのが速すぎ)
- 全サンプルが `runningInlineOnRequestThread` (定常時という前提が成立していない)

前提: nb33 スタック、admin:admin。**他の負荷と同時に走らせないこと** (定常時が前提)。
"""
import concurrent.futures
import json
import statistics
import sys
import threading
import time
import uuid

import requests

BASE = "http://localhost:8080/core"
REPO = "bedroom"
BR = f"{BASE}/browser/{REPO}/root"
ROOT = "e02f784f8360a02cc14d1314c10038ff"

# Big enough that the traversal lasts long enough to sample many times, small enough to seed
# in a few minutes at the measured ~5 documents/second.
DOCS = 900
PER_FOLDER = 30
SEED_WORKERS = 16
POLL_S = 0.5

# Declared BEFORE the run, not tuned to the result.
TOLERANCE = 0.20
MIN_PROGRESS_FOR_VERDICT = 0.20
REQUIRED_FRACTION_WITHIN = 0.80

BODY = b"eta probe fixture"


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


def mkfolder(s, name, parent):
    return oid(s.post(BR, data={
        "cmisaction": "createFolder", "objectId": parent,
        "propertyId[0]": "cmis:name", "propertyValue[0]": name,
        "propertyId[1]": "cmis:objectTypeId", "propertyValue[1]": "cmis:folder"},
        timeout=300).json())


def mkdoc(s, folder, name):
    r = s.post(BR, files={"content": (f"{name}.txt", BODY, "text/plain")},
               data={"cmisaction": "createDocument", "objectId": folder,
                     "propertyId[0]": "cmis:name", "propertyValue[0]": f"{name}.txt",
                     "propertyId[1]": "cmis:objectTypeId",
                     "propertyValue[1]": "cmis:document"}, timeout=300)
    if not (200 <= r.status_code < 300):
        raise RuntimeError(f"{r.status_code}: {r.text[:120]}")


def propagation():
    return A.get(f"{BASE}/api/v1/admin/search-index/propagation", timeout=60).json()


def main():
    tag = uuid.uuid4().hex[:4]

    # A principal that is NOT admin, so the ACE actually changes the effective ACL and the
    # implementation cannot short-circuit the propagation.
    principal = f"etaprobe-{tag}"

    container = mkfolder(A, f"eta-{tag}", ROOT)
    folders = [mkfolder(A, f"eta-{tag}-{i}", container)
               for i in range((DOCS + PER_FOLDER - 1) // PER_FOLDER)]
    sessions = [session() for _ in range(SEED_WORKERS)]
    print(f"seeding {DOCS} documents under eta-{tag}...", flush=True)
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=SEED_WORKERS) as ex:
        futs = [ex.submit(mkdoc, sessions[i % SEED_WORKERS], folders[i // PER_FOLDER],
                          f"eta-{tag}-{i}") for i in range(DOCS)]
        made = sum(1 for f in concurrent.futures.as_completed(futs) if not f.exception())
    print(f"  {made} documents + {len(folders)} folders in {time.time()-t0:.0f}s", flush=True)

    if propagation()["count"] != 0:
        sys.exit("a propagation is already in flight — the steady-state precondition is not met")

    samples = []
    stop = threading.Event()
    saw_any = threading.Event()

    def poll():
        while not stop.is_set():
            try:
                body = propagation()
                now = time.time()
                for r in body.get("running", []):
                    if r.get("rootObjectId") == container:
                        saw_any.set()
                        samples.append({
                            "t": now,
                            "done": r.get("doneNodes"),
                            "expected": r.get("expectedNodes"),
                            "etaMs": r.get("estimatedRemainingMs"),
                            "etaReliable": r.get("etaReliable"),
                            "callerRun": r.get("runningInlineOnRequestThread"),
                        })
            except Exception:
                pass
            time.sleep(POLL_S)

    poller = threading.Thread(target=poll, daemon=True)
    poller.start()

    print("\nfiring applyACL (propagate) on the container...", flush=True)
    s = session()
    t_start = time.time()
    r = s.post(BR, data={
        "cmisaction": "applyACL", "objectId": container,
        "ACLPropagation": "propagate",
        "addACEPrincipal[0]": principal,
        "addACEPermission[0][0]": "cmis:read"}, timeout=1800)
    t_request_returned = time.time()
    print(f"  request returned in {t_request_returned - t_start:.2f}s (status {r.status_code})",
          flush=True)

    # The propagation outlives the request. Wait for the entry to disappear.
    t_finish = None
    deadline = time.time() + 3600
    while time.time() < deadline:
        body = propagation()
        still = [x for x in body.get("running", []) if x.get("rootObjectId") == container]
        if saw_any.is_set() and not still:
            t_finish = time.time()
            break
        time.sleep(POLL_S)
    stop.set()
    poller.join(timeout=5)

    print(f"  propagation left the API after {t_finish - t_start:.1f}s"
          if t_finish else "  propagation never left the API (timed out)", flush=True)

    print(f"\ncollected {len(samples)} samples", flush=True)

    print("\n== VERDICT ==", flush=True)
    if not saw_any.is_set() or not samples:
        print("  INCONCLUSIVE: the poller never observed this propagation. Either it finished"
              "\n  faster than the poll interval or the subtree is too small. Raise DOCS."
              "\n  This is NOT a pass.", flush=True)
        sys.exit(1)
    if t_finish is None:
        print("  INCONCLUSIVE: the propagation never completed within the deadline, so there is"
              "\n  no actual finish time to compare the ETA against.", flush=True)
        sys.exit(1)
    if all(x["expected"] is None for x in samples):
        print("  INCONCLUSIVE: expectedNodes was null in every sample — the node count was never"
              "\n  established, so no ETA could be computed by construction.", flush=True)
        sys.exit(1)
    if all(x["callerRun"] for x in samples):
        print("  INCONCLUSIVE: every sample ran inline on a request thread, so the steady-state"
              "\n  precondition the ETA is defined under never held.", flush=True)
        sys.exit(1)

    with_eta = [x for x in samples if x.get("etaMs") is not None and not x["callerRun"]]
    print(f"  samples with an ETA: {len(with_eta)} of {len(samples)}", flush=True)
    if not with_eta:
        print("  INCONCLUSIVE: no sample carried an ETA, so '0 violations' would be vacuous."
              "\n  This is NOT a pass.", flush=True)
        sys.exit(1)

    scored = []
    for x in with_eta:
        actual_remaining = t_finish - x["t"]
        if actual_remaining <= 0:
            continue
        eta_s = x["etaMs"] / 1000.0
        err = abs(eta_s - actual_remaining) / actual_remaining
        progress = (x["done"] / x["expected"]) if x["expected"] else 0.0
        scored.append({"progress": progress, "etaS": eta_s,
                       "actualS": actual_remaining, "err": err})

    print("\n  accuracy by progress decile (|ETA - actual| / actual):", flush=True)
    for lo in range(0, 10):
        band = [s for s in scored if lo / 10 <= s["progress"] < (lo + 1) / 10]
        if band:
            errs = sorted(s["err"] for s in band)
            within = sum(1 for e in errs if e <= TOLERANCE)
            print(f"    {lo*10:>3}-{lo*10+10:>3}%: n={len(band):>3}  "
                  f"median err {statistics.median(errs)*100:>6.1f}%  "
                  f"within ±20%: {within}/{len(band)}", flush=True)

    judged = [s for s in scored if s["progress"] >= MIN_PROGRESS_FOR_VERDICT]
    if not judged:
        print(f"\n  INCONCLUSIVE: no ETA sample past {MIN_PROGRESS_FOR_VERDICT:.0%} progress.",
              flush=True)
        sys.exit(1)

    within = sum(1 for s in judged if s["err"] <= TOLERANCE)
    fraction = within / len(judged)
    overall_within = sum(1 for s in scored if s["err"] <= TOLERANCE) / len(scored)

    print(f"\n  judged window (progress >= {MIN_PROGRESS_FOR_VERDICT:.0%}): "
          f"{within}/{len(judged)} within ±{TOLERANCE:.0%} = {fraction:.0%}", flush=True)
    print(f"  (all samples, for context: {overall_within:.0%})", flush=True)

    ok = fraction >= REQUIRED_FRACTION_WITHIN
    print(f"\n  {'PASS' if ok else 'FAIL'}: criterion 3 requires {REQUIRED_FRACTION_WITHIN:.0%} "
          f"of the judged window within ±{TOLERANCE:.0%}.", flush=True)

    print(json.dumps({
        "expectedNodes": next((x["expected"] for x in samples if x["expected"]), None),
        "actualSeconds": round(t_finish - t_start, 1),
        "requestSeconds": round(t_request_returned - t_start, 2),
        "samples": len(samples),
        "samplesWithEta": len(with_eta),
        "judged": len(judged),
        "withinTolerance": round(fraction, 3),
        "overallWithinTolerance": round(overall_within, 3),
        "container": container,
    }, indent=2), flush=True)
    print(f"cleanup: delete folder eta-{tag} ({container})", flush=True)
    sys.exit(0 if ok else 1)


main()
