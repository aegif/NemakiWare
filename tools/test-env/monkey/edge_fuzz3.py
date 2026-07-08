#!/usr/bin/env python3
"""探索ファザー 第3波: 認証境界 / 複数リポジトリ分離 / Webhook 受信。

過去に cross-repo 分離バグがあった領域を重点的に。**HTTP 5xx / 予期せぬ
挙動 (認可漏れ・情報漏洩)** を探す。認可・不存在・CSRF・レートの 4xx は想定内。

使い方: python3 edge_fuzz3.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import uuid
from pathlib import Path

import requests

ADMIN = ("admin", "admin")
CSRF = {"X-Requested-With": "XMLHttpRequest"}
findings: list[dict] = []


def add(sev, name, detail):
    findings.append({"sev": sev, "name": name, "detail": str(detail)[:260]})
    print(f"  [{sev}] {name}: {str(detail)[:160]}")


def is5xx(r):
    return r is not None and r.status_code >= 500


def _oid(resp):
    try:
        d = resp.json()
    except Exception:
        return None
    sp = d.get("succinctProperties")
    if sp and "cmis:objectId" in sp:
        return sp["cmis:objectId"]
    pr = d.get("properties")
    if pr and "cmis:objectId" in pr:
        return pr["cmis:objectId"].get("value")
    return None


def run(base):
    b = base.rstrip("/")
    apiv1 = lambda repo: f"{b}/core/api/v1/cmis/repositories/{repo}"
    browser = lambda repo: f"{b}/core/browser/{repo}"

    # ---------- 認証境界 ----------
    print("== auth boundaries ==")
    # malformed Authorization headers -> expect 401/400, never 500
    for label, hdr in [
        ("Basic non-base64", {"Authorization": "Basic not@@base64"}),
        ("Basic empty", {"Authorization": "Basic "}),
        ("Bearer garbage", {"Authorization": "Bearer ...garbage..."}),
        ("AUTH_TOKEN garbage", {"AUTH_TOKEN": "not-a-real-token-" + uuid.uuid4().hex}),
        ("X-API-Key garbage", {"X-API-Key": "nope"}),
        ("Basic wrong-creds", {"Authorization": "Basic " + base64.b64encode(b"admin:wrongpw").decode()}),
        ("Basic user-no-colon", {"Authorization": "Basic " + base64.b64encode(b"noselector").decode()}),
    ]:
        r = requests.get(f"{apiv1('bedroom')}/users", headers=hdr, timeout=30)
        if is5xx(r):
            add("HIGH", f"auth {label} -> 5xx", r.status_code)
        elif r.status_code == 200:
            add("HIGH", f"auth {label} -> 200 (auth bypass?)", r.status_code)
    # no creds on a protected endpoint -> 401
    r = requests.get(f"{apiv1('bedroom')}/users", timeout=30)
    if r.status_code not in (401, 403):
        add("HIGH", "no-creds users list not 401/403", r.status_code)

    # CSRF: state-changing POST without X-Requested-With/Origin -> expect 403, never 500/200
    for path, body in [
        (f"{apiv1('bedroom')}/search-engine/rag/reindex", {}),
        (f"{apiv1('bedroom')}/users", {"userId": "csrf" + uuid.uuid4().hex[:6], "userName": "x", "password": "Pass1234"}),
    ]:
        r = requests.post(path, auth=ADMIN, json=body, timeout=40)  # no CSRF header
        if is5xx(r):
            add("MED", f"CSRF-less POST 5xx {path.split('/core/')[-1]}", r.status_code)
        elif r.status_code in (200, 201):
            add("HIGH", f"CSRF-less POST accepted {path.split('/core/')[-1]}", r.status_code)

    # ---------- 複数リポジトリ分離 ----------
    print("== multi-repo isolation ==")
    # make a bedroom-only doc, capture its id
    root_bed = requests.get(f"{browser('bedroom')}/root", params={"cmisselector": "object", "succinct": "true"}, auth=ADMIN, timeout=30).json()
    bed_root = (root_bed.get("succinctProperties") or {}).get("cmis:objectId") or (root_bed.get("properties") or {}).get("cmis:objectId", {}).get("value")
    sc = requests.post(browser("bedroom"), auth=ADMIN, data={
        "cmisaction": "createFolder", "objectId": bed_root,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:folder",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "iso-" + uuid.uuid4().hex[:6], "succinct": "true"}, timeout=40)
    scid = _oid(sc)
    doc = requests.post(browser("bedroom"), auth=ADMIN, data={
        "cmisaction": "createDocument", "objectId": scid,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "secret-bed.txt", "succinct": "true"},
        files={"content": ("s", b"bedroom-only-secret", "text/plain")}, timeout=40)
    docid = _oid(doc)
    print("  bedroom doc:", docid)

    if docid:
        # fetch bedroom object id through the CANOPY binding -> must NOT return bedroom content
        r = requests.get(f"{browser('canopy')}/root", params={"cmisselector": "object", "objectId": docid, "succinct": "true"}, auth=ADMIN, timeout=30)
        if is5xx(r):
            add("MED", "cross-repo object fetch 5xx", r.status_code)
        elif r.status_code == 200 and "secret-bed" in r.text:
            add("HIGH", "cross-repo object LEAK (bedroom doc via canopy)", r.text[:150])
        # legacy node acl of bedroom object via canopy
        r = requests.get(f"{b}/core/rest/repo/canopy/node/{docid}/acl", auth=ADMIN, timeout=30)
        if is5xx(r):
            add("MED", "cross-repo acl 5xx", r.status_code)
        elif r.status_code == 200 and '"status":true' in r.text.replace(" ", ""):
            add("HIGH", "cross-repo ACL readable across repo", r.text[:150])
        # RAG similar/get_content of a bedroom doc scoped to canopy
        r = requests.get(f"{apiv1('canopy')}/rag/similar/{docid}", auth=ADMIN, headers=CSRF, timeout=40)
        if is5xx(r):
            add("MED", "cross-repo rag similar 5xx", r.status_code)

    # RAG search in canopy as admin (canopy has no seeded secret data) -> must not surface bedroom docs
    r = requests.post(f"{apiv1('canopy')}/rag/search", auth=ADMIN, headers=CSRF, json={"query": "secret bedroom Aurora 賞与", "topK": 10}, timeout=60)
    if is5xx(r):
        add("MED", "canopy rag search 5xx", r.status_code)
    elif r.status_code == 200:
        try:
            names = [x.get("name") or x.get("documentName") for x in r.json().get("results", [])]
            leak = [n for n in names if n and ("secret-bed" in n or "Aurora" in n or "給与" in n or "賞与" in n)]
            if leak:
                add("HIGH", "canopy RAG surfaced bedroom docs", leak[:5])
        except Exception:
            pass

    # nonexistent repo -> 404, not 500
    r = requests.get(f"{browser('nonexistent-repo')}/root", params={"cmisselector": "object"}, auth=ADMIN, timeout=30)
    if is5xx(r):
        add("MED", "nonexistent repo 5xx", r.status_code)

    # ---------- Webhook 受信 ----------
    print("== ingest webhook ==")
    wh = lambda cid: f"{b}/core/api/v1/ingest-webhook/{cid}"
    for label, kw in [
        ("no body", dict(data=b"")),
        ("garbage json", dict(data=b"{not json", headers={"Content-Type": "application/json"})),
        ("huge body", dict(data=b"{\"x\":\"" + b"A" * (2 * 1024 * 1024) + b"\"}", headers={"Content-Type": "application/json"})),
        ("slack url_verification", dict(json={"type": "url_verification", "challenge": "abc"})),
        ("slack event no sig", dict(json={"type": "event_callback", "event": {"type": "message"}})),
        ("wrong content-type", dict(data=b"<xml/>", headers={"Content-Type": "application/xml"})),
        ("bad graph token", dict(data=b"validationToken=xyz", headers={"Content-Type": "application/x-www-form-urlencoded"})),
    ]:
        # unknown connector id (and also with admin creds since receiver may require auth)
        r = requests.post(wh("nonexistent-connector"), auth=ADMIN, timeout=60, **kw)
        if is5xx(r):
            add("MED", f"webhook {label} -> 5xx", f"{r.status_code} {r.text[:120]}")

    # ---------- cleanup ----------
    if scid:
        requests.post(browser("bedroom"), auth=ADMIN, data={
            "cmisaction": "deleteTree", "objectId": scid, "allVersions": "true", "continueOnFailure": "true"}, timeout=120)
    print("cleanup done")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    a = p.parse_args()
    print(f"edge_fuzz3: {a.base_url}")
    run(a.base_url)
    print(f"\n=== findings: {len(findings)} ===")
    for f in findings:
        print(json.dumps(f, ensure_ascii=False))
    out = Path(__file__).resolve().parent / ("edge3-" + a.base_url.replace("://", "_").replace("/", "_").replace(".", "_") + ".json")
    out.write_text(json.dumps(findings, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
