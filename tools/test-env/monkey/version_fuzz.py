#!/usr/bin/env python3
"""探索ファザー: CMIS バージョニング (checkOut/checkIn/cancel/PWC/削除競合)。

スクラッチフォルダ内で自己完結。探すもの:
  - HTTP 5xx (不正/競合入力)
  - 状態不整合: スタックした checked-out (再 checkOut 不能)、オーファン PWC、
    版の重複/消失、削除後の版シリーズ破損

使い方: python3 version_fuzz.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import sys
import uuid
from pathlib import Path

import requests

ADMIN = ("admin", "admin")
findings: list[dict] = []


def add(sev, name, detail=""):
    findings.append({"sev": sev, "name": name, "detail": str(detail)[:240]})
    print(f"  [{sev}] {name}: {str(detail)[:150]}")


def run(base):
    b = base.rstrip("/")
    browser = f"{b}/core/browser/bedroom"

    def post(data, files=None, timeout=60):
        return requests.post(browser, auth=ADMIN, data=data, files=files, timeout=timeout)

    def oid(r):
        try:
            d = r.json()
        except Exception:
            return None
        sp = d.get("succinctProperties")
        if sp and "cmis:objectId" in sp:
            return sp["cmis:objectId"]
        pr = d.get("properties")
        if pr and "cmis:objectId" in pr:
            return pr["cmis:objectId"].get("value")
        return None

    def get_props(object_id):
        r = requests.get(f"{browser}/root", params={"cmisselector": "object", "objectId": object_id, "succinct": "true"}, auth=ADMIN, timeout=30)
        if r.status_code != 200:
            return None
        d = r.json()
        return d.get("succinctProperties") or {k: v.get("value") for k, v in d.get("properties", {}).items()}

    def versions(object_id):
        r = requests.get(f"{browser}/root", params={"cmisselector": "versions", "objectId": object_id}, auth=ADMIN, timeout=30)
        if r.status_code != 200:
            return None, r.status_code
        d = r.json()
        objs = d.get("objects", d) if isinstance(d, dict) else d
        return d, r.status_code

    root = requests.get(f"{browser}/root", params={"cmisselector": "object", "succinct": "true"}, auth=ADMIN, timeout=30)
    root_id = oid(root)
    sc = post({"cmisaction": "createFolder", "objectId": root_id,
               "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:folder",
               "propertyId[1]": "cmis:name", "propertyValue[1]": "ver-" + uuid.uuid4().hex[:6], "succinct": "true"})
    scid = oid(sc)
    print("scratch:", scid)

    def mkdoc(name):
        r = post({"cmisaction": "createDocument", "objectId": scid,
                  "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
                  "propertyId[1]": "cmis:name", "propertyValue[1]": name, "succinct": "true"},
                 files={"content": (name, b"v1 content", "text/plain")})
        return oid(r)

    def checkout(doc_id, timeout=40):
        return requests.post(browser, auth=ADMIN, data={"cmisaction": "checkOut", "objectId": doc_id}, timeout=timeout)

    def checkin(pwc_id, comment="c", content=b"vN", timeout=40):
        return requests.post(browser, auth=ADMIN, data={
            "cmisaction": "checkIn", "objectId": pwc_id, "major": "true", "checkinComment": comment},
            files={"content": ("f", content, "text/plain")}, timeout=timeout)

    def cancel(pwc_or_doc, timeout=40):
        return requests.post(browser, auth=ADMIN, data={"cmisaction": "cancelCheckOut", "objectId": pwc_or_doc}, timeout=timeout)

    def is_checked_out(doc_id):
        p = get_props(doc_id) or {}
        v = p.get("cmis:isVersionSeriesCheckedOut")
        return v is True or v == "true"

    # ---------- A. 単発エッジ (5xx を出さないこと) ----------
    print("== single-thread edge ==")
    d = mkdoc("edge.txt")
    r = post({"cmisaction": "checkIn", "objectId": d, "major": "true"})  # checkin w/o checkout
    if r.status_code >= 500: add("MED", "checkIn without checkout 5xx", r.status_code)
    r = cancel(d)  # cancel when not checked out
    if r.status_code >= 500: add("MED", "cancel when not checked-out 5xx", r.status_code)
    rc = checkout(d); pwc = oid(rc)
    r = checkout(d)  # double checkout
    if r.status_code >= 500: add("MED", "double checkout 5xx", r.status_code)
    if pwc:
        r = checkin(pwc);
        if r.status_code >= 500: add("MED", "checkin 5xx", r.status_code)
        # double checkin on same (now consumed) pwc
        r = checkin(pwc)
        if r.status_code >= 500: add("MED", "double checkin on consumed pwc 5xx", r.status_code)
        # cancel a consumed pwc
        r = cancel(pwc)
        if r.status_code >= 500: add("MED", "cancel consumed pwc 5xx", r.status_code)
    if is_checked_out(d):
        add("HIGH", "doc STUCK checked-out after checkin", "isVersionSeriesCheckedOut=true post-checkin")

    # ---------- B. 並行 checkOut → 一貫状態 + 回復可能 ----------
    print("== concurrent checkOut ==")
    d = mkdoc("concheckout.txt")
    with cf.ThreadPoolExecutor(max_workers=10) as ex:
        res = list(ex.map(lambda _: checkout(d).status_code, range(10)))
    n200 = res.count(200); n5xx = sum(1 for s in res if s >= 500)
    print("  statuses:", sorted(set(res)), "200-count:", n200)
    if n5xx: add("MED", "concurrent checkout 5xx", res)
    if n200 > 1: add("HIGH", "concurrent checkOut created multiple PWCs", f"{n200} succeeded")
    # recover: cancel then must be able to checkout again
    p = get_props(d) or {}
    pwcid = p.get("cmis:versionSeriesCheckedOutId")
    if pwcid: cancel(pwcid)
    elif is_checked_out(d): cancel(d)
    r2 = checkout(d)
    if r2.status_code not in (200, 201):
        add("HIGH", "cannot re-checkOut after cancel (stuck)", r2.status_code)
    else:
        pwc2 = oid(r2)
        if pwc2: cancel(pwc2)

    # ---------- C. 並行 checkIn 同一 PWC → 版数健全 ----------
    print("== concurrent checkIn same PWC ==")
    d = mkdoc("concheckin.txt")
    rc = checkout(d); pwc = oid(rc)
    if pwc:
        with cf.ThreadPoolExecutor(max_workers=8) as ex:
            res = list(ex.map(lambda i: checkin(pwc, comment=f"c{i}", content=f"body{i}".encode()).status_code, range(8)))
        n2xx = sum(1 for s in res if 200 <= s < 300); n5xx = sum(1 for s in res if s >= 500)
        print("  checkin statuses:", sorted(set(res)), "2xx:", n2xx)
        if n5xx: add("MED", "concurrent checkin 5xx", res)
        if n2xx > 1: add("HIGH", "concurrent checkIn on one PWC succeeded multiple times", f"{n2xx}")
        if is_checked_out(d):
            add("MED", "doc still checked-out after concurrent checkin batch", "may be ok if all failed")

    # ---------- D. checkIn vs cancelCheckOut レース ----------
    print("== checkIn vs cancel race ==")
    for trial in range(4):
        d = mkdoc(f"race{trial}.txt")
        rc = checkout(d); pwc = oid(rc)
        if not pwc: continue
        with cf.ThreadPoolExecutor(max_workers=2) as ex:
            f1 = ex.submit(lambda: checkin(pwc, content=b"raced").status_code)
            f2 = ex.submit(lambda: cancel(pwc).status_code)
            s1, s2 = f1.result(), f2.result()
        for s in (s1, s2):
            if s >= 500: add("MED", f"checkin/cancel race 5xx (trial {trial})", (s1, s2))
        # after the race the doc must NOT be stuck checked-out and PWC must not dangle
        if is_checked_out(d):
            # try to recover
            p = get_props(d) or {}
            add("HIGH", f"doc stuck checked-out after checkin/cancel race (trial {trial})", (s1, s2))
            pid = p.get("cmis:versionSeriesCheckedOutId")
            if pid: cancel(pid)

    # ---------- E. PWC を直接 deleteObject → シリーズ健全 ----------
    print("== delete PWC directly ==")
    d = mkdoc("delpwc.txt")
    rc = checkout(d); pwc = oid(rc)
    if pwc:
        r = post({"cmisaction": "delete", "objectId": pwc, "allVersions": "false"})
        if r.status_code >= 500: add("MED", "delete PWC directly 5xx", r.status_code)
        # original doc should exist and be re-checkoutable (not stuck)
        p = get_props(d)
        if p is None:
            add("HIGH", "original doc gone after deleting its PWC", "getObject null")
        elif is_checked_out(d):
            r2 = checkout(d)
            if r2.status_code not in (200, 201):
                add("HIGH", "doc stuck checked-out after PWC direct delete", r2.status_code)
            else:
                pwc2 = oid(r2)
                if pwc2: cancel(pwc2)

    # ---------- F. checkOut 中に親フォルダ deleteTree ----------
    print("== deleteTree while checked out ==")
    subf = post({"cmisaction": "createFolder", "objectId": scid,
                 "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:folder",
                 "propertyId[1]": "cmis:name", "propertyValue[1]": "co-sub", "succinct": "true"})
    subid = oid(subf)
    if subid:
        rd = post({"cmisaction": "createDocument", "objectId": subid,
                   "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
                   "propertyId[1]": "cmis:name", "propertyValue[1]": "co-in-sub.txt", "succinct": "true"},
                  files={"content": ("f", b"x", "text/plain")})
        cid = oid(rd)
        if cid:
            checkout(cid)
            r = post({"cmisaction": "deleteTree", "objectId": subid, "allVersions": "true", "continueOnFailure": "true"})
            if r.status_code >= 500: add("MED", "deleteTree with checked-out child 5xx", r.status_code)
            # the doc (and its PWC) should be gone; verify no orphan by re-reading
            if get_props(cid) is not None:
                add("MED", "checked-out doc survived deleteTree", cid)

    # ---------- cleanup ----------
    if scid:
        post({"cmisaction": "deleteTree", "objectId": scid, "allVersions": "true", "continueOnFailure": "true"}, timeout=120)
    print("cleanup done")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    a = p.parse_args()
    print(f"version_fuzz: {a.base_url}")
    run(a.base_url)
    print(f"\n=== findings: {len(findings)} ===")
    for f in findings:
        print(json.dumps(f, ensure_ascii=False))
    out = Path(__file__).resolve().parent / ("version-" + a.base_url.replace("://", "_").replace("/", "_").replace(".", "_") + ".json")
    out.write_text(json.dumps(findings, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
