"""全再索引中の CouchDB 接続数を実測する (台帳 F3 の残差)。

F3 の主因 (メタデータのために添付本体を開いて閉じない) は 2026-08-11 に除去し、
開発スタックのスイート 1 周で leak 警告 0 件を確認した。ただし台帳・CLAUDE.md・
RELEASE_NOTES はいずれも **本番規模 (数万〜数十万文書) では未実測**と書いており、
「開発スタックで 0 件」を「発生源が無い」と読み替えないよう明記してある。

修正前の実測は **2,510 文書の再索引で ESTABLISHED 3 → 1,289、完了後も約 90 秒張り付き**。
つまり接続数が**文書数に比例して伸びた**。修正が効いているなら、伸びるのは
コネクションプールの上限までで、**文書数を増やしても頭打ちのまま**になるはず。

このプローブはそれを見る。**絶対値ではなく「文書数に対して伸びるかどうか」が判定基準**で、
同じ手順を規模を変えて 2 回以上走らせて比較すること。

計測方法:

- core コンテナの `/proc/net/tcp6` を 1 秒ごとに読み、CouchDB を peer とする
  ESTABLISHED を数える。`ss` も `netstat` もコンテナに無いのでこれが唯一の手段。
  JVM は IPv6 マップドアドレスを使うので `tcp` ではなく `tcp6` を見る。
- 再索引の前後にアイドル値を取る。**完了後に下がりきるかどうか**が、修正前に
  「完了後も約 90 秒張り付いた」と書いた点の再確認になる。

前提: nb33 スタック、admin:admin。他の測定と同時に走らせないこと。
"""
import json
import subprocess
import sys
import time
import urllib.request

BASE = "http://localhost:8080/core"
REPO = "bedroom"
CORE = "nb33-core-1"
COUCH_CONTAINER = "nb33-couchdb-1"
POLL_S = 1.0
IDLE_BEFORE_S = 15
IDLE_AFTER_S = 120
# 26,416 文書の再索引が 2,561 秒 (約 10 文書/秒) だったので、10 万規模では 2.5〜3 時間かかる。
# 3600 秒だと「完了しなかった」と報告して測定を捨てることになる。--max-wait で上書き可。
MAX_WAIT_S = 20000

AUTH = "Basic " + __import__("base64").b64encode(b"admin:admin").decode()


# The reindex ends on one of these. "completed_with_errors" is the newest and was added to
# the server on both the CMIS and RAG sides; a poller that waits for "completed" alone runs to
# its deadline instead, and for THIS probe that is not merely slow — it keeps sampling after the
# run is over and dilutes the peak it exists to measure.
#
# Unknown statuses stop the wait too, loudly. An accept-list breaks when a value is ADDED (this
# is the second time), and `!= "running"` breaks when a non-terminal value is added, which is
# worse for a probe because it ends early and reports a partial measurement as a whole one.
TERMINAL = ("completed", "completed_with_errors", "error", "cancelled")


def is_terminal(status):
    if status == "running":
        return False
    if status in TERMINAL:
        return True
    raise SystemExit(f"the reindex reported a status this probe does not know: {status!r}. "
                     f"Add it to TERMINAL if it is an end state; measuring against an "
                     f"unrecognised status would report whatever the last poll happened to see.")

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60).stdout.strip()


def couch_peer_hex():
    """The /proc/net/tcp6 remote-address string for CouchDB, derived, not hard-coded."""
    ip = sh(f"docker inspect {COUCH_CONTAINER} --format "
            "'{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'")
    if not ip:
        raise SystemExit(f"could not resolve {COUCH_CONTAINER}'s address")
    packed = "".join(f"{int(o):02X}" for o in reversed(ip.split(".")))
    return ip, f"0000000000000000FFFF0000{packed}:1760"  # 5984 = 0x1760


def established(peer_hex):
    out = sh(f"docker exec {CORE} sh -c \"awk 'NR>1 && \\$3==\\\"{peer_hex}\\\" && \\$4==\\\"01\\\"' "
             "/proc/net/tcp6 | wc -l\"")
    try:
        return int(out)
    except ValueError:
        return -1


def api(method, path):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("X-Requested-With", "XMLHttpRequest")
    req.add_header("Authorization", AUTH)
    with urllib.request.urlopen(req, timeout=120) as r:
        body = r.read().decode()
        try:
            return json.loads(body)
        except Exception:
            return {}


def reindex_status():
    return api("GET", f"/api/v1/cmis/repositories/{REPO}/search-engine/status")


def sample(peer_hex, seconds, label):
    """Sample for `seconds`, returning (peak, last, samples)."""
    peak, last, n = 0, 0, 0
    t0 = time.time()
    while time.time() - t0 < seconds:
        c = established(peer_hex)
        if c >= 0:
            peak = max(peak, c)
            last = c
            n += 1
        time.sleep(POLL_S)
    print(f"  {label}: peak={peak} last={last} ({n} samples)", flush=True)
    return peak, last


def main():
    global MAX_WAIT_S
    if "--max-wait" in sys.argv:
        MAX_WAIT_S = int(sys.argv[sys.argv.index("--max-wait") + 1])
    ip, peer_hex = couch_peer_hex()
    docs = api("GET", f"/api/v1/admin/acl-epoch/migration/{REPO}").get("indexedCmisObjects")
    print(f"CouchDB at {ip} (peer {peer_hex}); repository holds {docs} indexed objects", flush=True)
    print("Reading: what matters is whether the PEAK grows with the document count, not the"
          "\nabsolute number. Before the F3 fix it went 3 -> 1,289 for 2,510 documents.",
          flush=True)

    print("\n== idle before ==", flush=True)
    idle_peak, _ = sample(peer_hex, IDLE_BEFORE_S, "idle")

    print("\n== full reindex ==", flush=True)
    started = api("POST", f"/api/v1/cmis/repositories/{REPO}/search-engine/reindex")
    if not started.get("success", True):
        raise SystemExit(f"reindex did not start: {started}")

    peak, n = 0, 0
    t0 = time.time()
    last_report = 0
    while time.time() - t0 < MAX_WAIT_S:
        c = established(peer_hex)
        if c >= 0:
            peak = max(peak, c)
            n += 1
        st = reindex_status()
        elapsed = int(time.time() - t0)
        if elapsed - last_report >= 15:
            print(f"  t+{elapsed:>4}s established={c:<5} peak={peak:<5} "
                  f"indexed={st.get('indexedCount')}/{st.get('totalDocuments')}", flush=True)
            last_report = elapsed
        if is_terminal(st.get("status")):
            break
        time.sleep(POLL_S)
    duration = round(time.time() - t0, 1)
    st = reindex_status()
    print(f"  reindex {st.get('status')} after {duration}s, "
          f"{st.get('indexedCount')}/{st.get('totalDocuments')} documents", flush=True)
    print(f"  PEAK established during reindex: {peak} ({n} samples)", flush=True)

    print("\n== after (does it come back down?) ==", flush=True)
    # The pre-fix behaviour was ~90s of connections staying up after completion.
    after_peak, after_last = sample(peer_hex, IDLE_AFTER_S, "post-reindex")

    print("\n== SUMMARY ==", flush=True)
    print(json.dumps({
        "indexedObjects": st.get("indexedCount"),
        "reindexSeconds": duration,
        "idlePeakBefore": idle_peak,
        "peakDuringReindex": peak,
        "peakAfter": after_peak,
        "lastAfter": after_last,
    }, indent=2), flush=True)
    print("\nRun this again after growing the repository. A peak that stays flat while the"
          "\ndocument count grows is the evidence F3 needs; a peak that tracks the count is"
          "\nthe leak returning.", flush=True)


main()
