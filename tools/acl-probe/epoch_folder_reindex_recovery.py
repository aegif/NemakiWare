"""folder reindex で落ちた ACL-epoch は自動で戻るか (台帳 C8 の未確認事項)。

`acl-epoch-reindex-defect.md` §5 は「epoch scanner / reconciliation キューが自動で
stamp し直すか。観測した範囲 (数分) では復旧しなかった」を未確認として残していた。
「数分では戻らなかった」は、**まだ戻っていないだけ**とも読めるので、運用手順を
「必須」と書くか「推奨」と書くかが決まらない。

**コードから先に答えが出る。** `AclEpochScanScheduler` は 300 秒ごとに
`AclEpochFinalizationService.scan` を呼ぶが、その全パスの選択条件は **CouchDB 側の**
`aclEpochState` (PENDING_EPOCH / FINALIZED_NEEDS_RECONCILE) か `aclEpochMutationId` の
存在である。folder reindex が消すのは **Solr の `effective_acl_epoch` フィールド**で、
CouchDB 文書は一切触られない — 状態は settled のまま。
**したがってどのパスもその文書を選ばない。永久に戻らない。**

この計測はその結論を実機で裏取りする。スキャナ周期 (300 s) を複数回跨いで観測し、
「まだ戻っていない」ではなく「周期を N 回過ぎても戻らない」を記録する。

読み方:

- **陽性対照**: 対象フォルダの子が計測開始時に epoch を持っていること。
  持っていなければ「消えた」も「戻らない」も測れない。
- **陰性対照にあたるもの**: 単一文書 reindex は epoch を保持する (台帳 §1 で実測済み)。
  つまり「reindex 一般が消す」のではなく batch 経路だけ、という区別は既にある。
- 観測後、**stamp を流して元に戻すこと** (このスクリプトは戻さない。範囲が
  リポジトリ全体になるため、判断を利用者に残す)。

前提: nb33 スタック、admin:admin。対象フォルダは引数で渡す。
"""
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://localhost:8080/core"
SOLR = "http://localhost:8983/solr/nemaki"
REPO = "bedroom"
SCAN_INTERVAL_S = 300      # AclEpochScanScheduler.DEFAULT_INTERVAL_SECONDS
CYCLES_TO_WATCH = 3
POLL_S = 30

FOLDER = sys.argv[1] if len(sys.argv) > 1 else None
if not FOLDER:
    raise SystemExit("usage: epoch_folder_reindex_recovery.py <folderId>")


def solr(params):
    url = f"{SOLR}/select?" + urllib.parse.urlencode(params, doseq=True)
    with urllib.request.urlopen(url, timeout=120) as r:
        return json.loads(r.read().decode())


def counts():
    """(children in the folder, of which carry an epoch)."""
    total = solr({"q": f'parent_id:"{FOLDER}"', "rows": "0", "wt": "json"})["response"]["numFound"]
    fenced = solr({"q": f'parent_id:"{FOLDER}"', "fq": "effective_acl_epoch:[* TO *]",
                   "rows": "0", "wt": "json"})["response"]["numFound"]
    return total, fenced


def api(method, path):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("X-Requested-With", "XMLHttpRequest")
    import base64
    req.add_header("Authorization", "Basic " + base64.b64encode(b"admin:admin").decode())
    with urllib.request.urlopen(req, timeout=300) as r:
        body = r.read().decode()
        try:
            return r.status, json.loads(body)
        except Exception:
            return r.status, body


total, fenced = counts()
print(f"folder {FOLDER}: {fenced}/{total} children carry an epoch", flush=True)
if total == 0:
    raise SystemExit("the folder has no indexed children — nothing to measure")
if fenced == 0:
    raise SystemExit("the children are ALREADY unfenced: 'it did not come back' would be true "
                     "of a folder that was never stamped. Run the epoch stamp first.")

print("\n== folder reindex ==", flush=True)
status, body = api("POST", f"/api/v1/cmis/repositories/{REPO}/search-engine/reindex/folder/{FOLDER}")
print(f"HTTP {status}: {json.dumps(body, ensure_ascii=False)[:300] if isinstance(body, dict) else body[:300]}",
      flush=True)

# Wait for the epoch field to actually drop, so we are measuring recovery and not the reindex.
dropped_after = -1
t0 = time.time()
while time.time() - t0 < 600:
    _, f = counts()
    if f < fenced:
        dropped_after = round(time.time() - t0, 1)
        print(f"epochs dropped after {dropped_after}s: now {f}/{total}", flush=True)
        break
    time.sleep(5)
if dropped_after < 0:
    print("the epochs never dropped — either the reindex did not run or this build no longer "
          "has the defect. Nothing further to measure.", flush=True)
    raise SystemExit(0)

watch_for = SCAN_INTERVAL_S * CYCLES_TO_WATCH + 60
print(f"\n== watching for {watch_for}s ({CYCLES_TO_WATCH} scanner cycles of "
      f"{SCAN_INTERVAL_S}s + margin) ==", flush=True)
start = time.time()
best = None
while time.time() - start < watch_for:
    elapsed = round(time.time() - start)
    t, f = counts()
    if best is None or f > best:
        best = f
    cycles = elapsed / SCAN_INTERVAL_S
    print(f"  t+{elapsed:>4}s ({cycles:.1f} cycles): {f}/{t} fenced", flush=True)
    if f >= fenced:
        print(f"\nRECOVERED after {elapsed}s — the scanner DOES re-stamp. "
              "The ledger's 'did not recover in minutes' was a timing artefact.", flush=True)
        raise SystemExit(0)
    time.sleep(POLL_S)

t, f = counts()
print(f"\nNOT RECOVERED: {f}/{t} fenced after {round(time.time()-start)}s "
      f"({CYCLES_TO_WATCH}+ scanner cycles). Peak observed during the watch: {best}.", flush=True)
print("Matches the code: every scan pass selects on the CouchDB aclEpochState /"
      "\naclEpochMutationId fields, and a folder reindex changes neither — it drops a SOLR"
      "\nfield. No pass can select these documents, so this is permanent, not slow."
      "\n\nThe operational step (re-run the epoch stamp after a folder reindex) is therefore"
      "\nMANDATORY, not advisory.", flush=True)
