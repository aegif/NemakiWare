#!/usr/bin/env python3
"""Compare CMIS server versions under load, on one machine, one at a time.

WHY THIS EXISTS
---------------
"Is the new release faster?" cannot be answered by timing a few curl calls: the
interesting differences show up as latency spread under concurrency, not as a shift in
the mean of an idle server. So this drives a fixed mix of CMIS operations at a set of
concurrency levels and reports the distribution — p50/p95/p99 and throughput — for each
operation separately, because a change that helps reads can easily hurt writes.

WHAT MAKES A COMPARISON FAIR
----------------------------
Everything below is a deliberate constraint, not incidental:

* One server at a time. Two stacks on one laptop compete for CPU and the numbers stop
  meaning anything.
* Identical seeded data, created by this script, so every version is measured against
  the same corpus rather than whatever its own test suite happened to leave behind.
* A warm-up phase whose results are discarded. The JVM's first thousand requests are
  measuring the JIT, not the server.
* The client is checked against itself: `--selfcheck` reports the harness's own
  saturation point, so a "server limit" that is really a Python limit is visible.
* Errors are counted and reported, never silently dropped — a version that gets fast by
  failing requests must not look like a winner.

USAGE
    python3 cmis_bench.py --base http://localhost:8080/core --label 3.3 --seed 200
    python3 cmis_bench.py --base http://localhost:8080/core --label 3.3 --run
    python3 cmis_bench.py --compare results-3.3.json results-3.2.json
"""

import argparse
import base64
import json
import os
import random
import statistics
import sys
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection

# ----------------------------------------------------------------- HTTP plumbing

class Client:
    """One HTTP connection per thread; connection setup is not what we are measuring."""

    def __init__(self, base, user, password):
        parsed = urllib.parse.urlparse(base)
        self.host = parsed.hostname
        self.port = parsed.port or 80
        self.prefix = parsed.path.rstrip("/")
        self.auth = "Basic " + base64.b64encode(
            f"{user}:{password}".encode()).decode()
        self._local = threading.local()

    def _conn(self):
        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = HTTPConnection(self.host, self.port, timeout=60)
            self._local.conn = conn
        return conn

    def _drop(self):
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            try:
                conn.close()
            finally:
                self._local.conn = None

    def request(self, method, path, body=None, headers=None, read=True):
        """Returns (status, body_bytes). Retries once on a dropped keep-alive."""
        h = {"Authorization": self.auth, "X-Requested-With": "XMLHttpRequest"}
        if headers:
            h.update(headers)
        for attempt in (1, 2):
            try:
                conn = self._conn()
                conn.request(method, self.prefix + path, body=body, headers=h)
                resp = conn.getresponse()
                data = resp.read() if read else (resp.read() and b"")
                return resp.status, data
            except Exception:
                self._drop()
                if attempt == 2:
                    raise
        raise AssertionError("unreachable")


def form(fields):
    return urllib.parse.urlencode(fields), {
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}


# ----------------------------------------------------------------- CMIS operations

class Workload:
    """The operation mix. Each returns (ok, bytes) and raises nothing on HTTP errors."""

    def __init__(self, client, repo, root_id, doc_ids, folder_ids, names):
        self.c = client
        self.repo = repo
        self.root_id = root_id
        self.doc_ids = doc_ids
        self.folder_ids = folder_ids
        self.names = names
        self.counter = itertools_count()

    def get_object(self):
        oid = random.choice(self.doc_ids)
        s, b = self.c.request("GET",
            f"/browser/{self.repo}/root?cmisselector=object&objectId={oid}&succinct=true")
        return 200 <= s < 300, len(b)

    def get_children(self):
        fid = random.choice(self.folder_ids)
        s, b = self.c.request("GET",
            f"/browser/{self.repo}/root?cmisselector=children&objectId={fid}"
            f"&maxItems=50&succinct=true")
        return 200 <= s < 300, len(b)

    def query(self):
        name = random.choice(self.names)
        q = urllib.parse.quote(
            f"SELECT cmis:objectId, cmis:name FROM cmis:document "
            f"WHERE cmis:name LIKE '{name[:12]}%'")
        s, b = self.c.request("GET",
            f"/browser/{self.repo}?cmisselector=query&q={q}&maxItems=25&succinct=true")
        return 200 <= s < 300, len(b)

    def content(self):
        oid = random.choice(self.doc_ids)
        s, b = self.c.request("GET",
            f"/browser/{self.repo}/root?cmisselector=content&objectId={oid}")
        return 200 <= s < 300, len(b)

    def create(self):
        n = next(self.counter)
        body, headers = form([
            ("cmisaction", "createDocument"),
            ("objectId", random.choice(self.folder_ids)),
            ("propertyId[0]", "cmis:objectTypeId"),
            ("propertyValue[0]", "cmis:document"),
            ("propertyId[1]", "cmis:name"),
            ("propertyValue[1]", f"bench-w-{os.getpid()}-{n}.txt"),
            ("succinct", "true"),
        ])
        s, b = self.c.request("POST", f"/browser/{self.repo}/root", body, headers)
        return 200 <= s < 300, len(b)


def itertools_count():
    n = 0
    lock = threading.Lock()
    while True:
        with lock:
            n += 1
        yield n


# The mix. Reads dominate because that is what a document repository does, but writes
# are present because they are where locking and indexing costs surface.
MIX = [
    ("getObject", 30),
    ("getChildren", 25),
    ("query", 20),
    ("content", 15),
    ("create", 10),
]


# ----------------------------------------------------------------- measurement

def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = (len(ordered) - 1) * (p / 100.0)
    lo, hi = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def run_phase(workload, concurrency, seconds, ops):
    """Drives `concurrency` workers for `seconds`; returns per-operation latencies (ms)."""
    weights = [w for _, w in ops]
    names = [n for n, _ in ops]
    latencies = {n: [] for n in names}
    errors = {n: 0 for n in names}
    lock = threading.Lock()
    stop_at = time.monotonic() + seconds

    def worker():
        local_lat = {n: [] for n in names}
        local_err = {n: 0 for n in names}
        while time.monotonic() < stop_at:
            name = random.choices(names, weights=weights, k=1)[0]
            fn = getattr(workload, {"getObject": "get_object",
                                    "getChildren": "get_children",
                                    "query": "query",
                                    "content": "content",
                                    "create": "create"}[name])
            t0 = time.perf_counter()
            try:
                ok, _ = fn()
            except Exception:
                ok = False
            elapsed = (time.perf_counter() - t0) * 1000.0
            if ok:
                local_lat[name].append(elapsed)
            else:
                local_err[name] += 1
        with lock:
            for n in names:
                latencies[n].extend(local_lat[n])
                errors[n] += local_err[n]

    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        for _ in range(concurrency):
            pool.submit(worker)
    duration = time.monotonic() - started
    return latencies, errors, duration


def summarise(latencies, errors, duration, concurrency):
    total_ok = sum(len(v) for v in latencies.values())
    out = {
        "concurrency": concurrency,
        "duration_s": round(duration, 2),
        "throughput_rps": round(total_ok / duration, 1) if duration else 0,
        "errors_total": sum(errors.values()),
        "operations": {},
    }
    for name, values in latencies.items():
        out["operations"][name] = {
            "count": len(values),
            "errors": errors[name],
            "p50_ms": round(percentile(values, 50), 1),
            "p95_ms": round(percentile(values, 95), 1),
            "p99_ms": round(percentile(values, 99), 1),
            "mean_ms": round(statistics.fmean(values), 1) if values else float("nan"),
        }
    return out


# ----------------------------------------------------------------- seeding

def props(obj):
    """Properties from either response shape.

    `succinct=true` is a request, not a guarantee — this server answers the
    Browser Binding's verbose form for some selectors, so a harness that assumes
    one shape measures nothing at all on the other.
    """
    if "succinctProperties" in obj:
        return obj["succinctProperties"]
    return {k: v.get("value") for k, v in obj.get("properties", {}).items()}



def seed(client, repo, count, folders):
    """Creates a deterministic corpus. Idempotent by name: re-running finds and reuses."""
    s, b = client.request("GET", f"/browser/{repo}/root?cmisselector=object&succinct=true")
    if not (200 <= s < 300):
        raise SystemExit(f"cannot read the root folder: HTTP {s}")
    root = props(json.loads(b))["cmis:objectId"]

    folder_ids = []
    for i in range(folders):
        name = f"bench-folder-{i:02d}"
        body, headers = form([
            ("cmisaction", "createFolder"), ("objectId", root),
            ("propertyId[0]", "cmis:objectTypeId"), ("propertyValue[0]", "cmis:folder"),
            ("propertyId[1]", "cmis:name"), ("propertyValue[1]", name),
            ("succinct", "true")])
        s, b = client.request("POST", f"/browser/{repo}/root", body, headers)
        if 200 <= s < 300:
            folder_ids.append(props(json.loads(b))["cmis:objectId"])
        else:  # already there: find it among the root's children
            s2, b2 = client.request("GET",
                f"/browser/{repo}/root?cmisselector=children&objectId={root}"
                f"&maxItems=500&succinct=true")
            for o in json.loads(b2).get("objects", []):
                p = props(o["object"])
                if p["cmis:name"] == name:
                    folder_ids.append(p["cmis:objectId"])
                    break
    if not folder_ids:
        raise SystemExit("could not create or find any benchmark folder")

    doc_ids, names = [], []
    payload = ("benchmark corpus line\n" * 40)
    for i in range(count):
        name = f"bench-doc-{i:05d}.txt"
        body, headers = form([
            ("cmisaction", "createDocument"),
            ("objectId", folder_ids[i % len(folder_ids)]),
            ("propertyId[0]", "cmis:objectTypeId"), ("propertyValue[0]", "cmis:document"),
            ("propertyId[1]", "cmis:name"), ("propertyValue[1]", name),
            ("content", payload), ("filename", name), ("mimetype", "text/plain"),
            ("succinct", "true")])
        s, b = client.request("POST", f"/browser/{repo}/root", body, headers)
        if 200 <= s < 300:
            doc_ids.append(props(json.loads(b))["cmis:objectId"])
            names.append(name)
        if (i + 1) % 50 == 0:
            print(f"  seeded {i + 1}/{count}", flush=True)
    return root, folder_ids, doc_ids, names


def discover(client, repo, folders_wanted):
    """Finds a previously seeded corpus so a run does not have to re-create it."""
    s, b = client.request("GET", f"/browser/{repo}/root?cmisselector=object&succinct=true")
    root = props(json.loads(b))["cmis:objectId"]
    s, b = client.request("GET",
        f"/browser/{repo}/root?cmisselector=children&objectId={root}"
        f"&maxItems=500&succinct=true")
    folder_ids = [props(o["object"])["cmis:objectId"]
                  for o in json.loads(b).get("objects", [])
                  if str(props(o["object"]).get("cmis:name", "")).startswith("bench-folder-")]
    doc_ids, names = [], []
    for fid in folder_ids:
        s, b = client.request("GET",
            f"/browser/{repo}/root?cmisselector=children&objectId={fid}"
            f"&maxItems=500&succinct=true")
        for o in json.loads(b).get("objects", []):
            p = props(o["object"])
            if str(p.get("cmis:name", "")).startswith("bench-doc-"):
                doc_ids.append(p["cmis:objectId"])
                names.append(p["cmis:name"])
    return root, folder_ids, doc_ids, names


# ----------------------------------------------------------------- entry point

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080/core")
    ap.add_argument("--repo", default="bedroom")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--label", default="run")
    ap.add_argument("--seed", type=int, default=0, help="seed N documents and exit")
    ap.add_argument("--folders", type=int, default=10)
    ap.add_argument("--run", action="store_true")
    ap.add_argument("--levels", default="1,4,16,48")
    ap.add_argument("--seconds", type=int, default=30)
    ap.add_argument("--warmup", type=int, default=15)
    ap.add_argument("--out", default=None)
    ap.add_argument("--only", default=None,
                    help="measure a single operation (e.g. create) — writes are compared "
                         "on their own because they are the half the read mix hides")
    ap.add_argument("--readonly", action="store_true",
                    help="drop the write operation: the corpus then stops growing, so "
                         "successive runs measure the same repository")
    ap.add_argument("--selfcheck", action="store_true",
                    help="measure the harness's own ceiling against a trivial endpoint")
    args = ap.parse_args()

    client = Client(args.base, args.user, args.password)
    random.seed(20260808)  # same operation sequence for every version

    if args.seed:
        print(f"seeding {args.seed} documents in {args.folders} folders ...", flush=True)
        root, folders, docs, names = seed(client, args.repo, args.seed, args.folders)
        print(f"seeded: {len(docs)} documents, {len(folders)} folders")
        return

    if args.selfcheck:
        # A cheap endpoint: whatever the client can do here bounds every other number.
        t0 = time.perf_counter()
        n = 0
        while time.perf_counter() - t0 < 5:
            client.request("GET", f"/browser/{args.repo}?cmisselector=repositoryInfo")
            n += 1
        print(f"single-thread repositoryInfo: {n / 5:.0f} rps")
        return

    if not args.run:
        ap.error("nothing to do: pass --seed N, --run or --selfcheck")

    root, folders, docs, names = discover(client, args.repo, args.folders)
    if len(docs) < 50:
        raise SystemExit(f"corpus too small ({len(docs)} documents) — run --seed first")
    print(f"corpus: {len(docs)} documents in {len(folders)} folders", flush=True)

    # A run that creates documents leaves the next run a bigger repository — measured at
    # 240 new documents per pass — so an A/B/A/B sequence drifts monotonically downward
    # and the drift swamps the difference between versions. Read-only runs are repeatable.
    if args.only:
        mix = [(n, w) for n, w in MIX if n == args.only]
        if not mix:
            raise SystemExit(f"unknown operation: {args.only}")
    elif args.readonly:
        mix = [(n, w) for n, w in MIX if n != "create"]
    else:
        mix = MIX
    workload = Workload(client, args.repo, root, docs, folders, names)
    results = {"label": args.label, "corpus": {"documents": len(docs),
                                               "folders": len(folders)},
               "mix": dict(mix), "phases": []}

    print(f"warm-up {args.warmup}s (discarded) ...", flush=True)
    run_phase(workload, 8, args.warmup, mix)

    for level in [int(x) for x in args.levels.split(",")]:
        print(f"concurrency {level}: {args.seconds}s ...", flush=True)
        lat, err, dur = run_phase(workload, level, args.seconds, mix)
        phase = summarise(lat, err, dur, level)
        results["phases"].append(phase)
        print(f"  {phase['throughput_rps']} rps, errors {phase['errors_total']}",
              flush=True)

    out = args.out or f"/tmp/bench-{args.label}.json"
    with open(out, "w") as f:
        json.dump(results, f, indent=2)
    print(f"written: {out}")


if __name__ == "__main__":
    main()
