#!/usr/bin/env python3
"""探索ファザー 第2波: 未踏サーフェス (アップロード/インポート/同時実行/検索特殊入力)。

スクラッチフォルダ内で自己完結。**HTTP 5xx / 例外**を探す。
使い方: python3 edge_fuzz2.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import io
import json
import sys
import uuid
import zipfile
from pathlib import Path

import requests

AUTH = ("admin", "admin")
CSRF = {"X-Requested-With": "XMLHttpRequest"}
findings: list[dict] = []


def note(name, status, extra=""):
    findings.append({"name": name, "status": status, "extra": str(extra)[:200]})


def bid(r):
    try:
        d = r.json()
        return (d.get("succinctProperties") or {}).get("cmis:objectId") \
            or d.get("properties", {}).get("cmis:objectId", {}).get("value")
    except Exception:
        return None


def run(base, repo):
    browser = f"{base}/core/browser/{repo}"
    rest = f"{base}/core/rest/repo/{repo}"
    root = requests.get(f"{browser}/root", params={"cmisselector": "object", "succinct": "true"},
                        auth=AUTH, timeout=30)
    root_id = bid(root)
    scratch_name = "edge2-" + uuid.uuid4().hex[:8]
    r = requests.post(browser, auth=AUTH, data={
        "cmisaction": "createFolder", "objectId": root_id,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:folder",
        "propertyId[1]": "cmis:name", "propertyValue[1]": scratch_name, "succinct": "true"})
    scratch = bid(r)
    print("scratch:", scratch)
    if not scratch:
        return

    def create_doc(name, content, mime, tag):
        rr = requests.post(browser, auth=AUTH, data={
            "cmisaction": "createDocument", "objectId": scratch,
            "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
            "propertyId[1]": "cmis:name", "propertyValue[1]": name, "succinct": "true"},
            files={"content": (name, content, mime)}, timeout=120)
        if rr.status_code >= 500:
            note(tag, rr.status_code, rr.text[:200])
        return rr

    # ---- アップロード: 異常 MIME / 空 / 大きめ / 偽装拡張子 ----
    create_doc("empty.txt", b"", "text/plain", "upload empty content")
    create_doc("weird.mime", b"data", "application/x-not-a-real-type;charset=xxx", "upload weird mime")
    create_doc("bad-utf8.txt", b"\xff\xfe\x00\x01\x80bad", "text/plain", "upload invalid utf8 bytes")
    create_doc("fake.pdf", b"NOT-A-PDF-JUST-TEXT", "application/pdf", "upload pdf-mime non-pdf")
    create_doc("big.txt", b"A" * (12 * 1024 * 1024), "text/plain", "upload 12MB")  # RAG/Tika 経路
    create_doc("nul\x00name.txt", b"x", "text/plain", "upload nul in name")
    create_doc("emoji-😀-name.txt", "絵文字本文😀".encode(), "text/plain", "upload emoji")

    # ---- インポート: 不正 ZIP / ACP ----
    # まともでない zip
    note_pre = len(findings)
    r = requests.post(f"{rest}/importexport/import/{scratch}", auth=AUTH, headers=CSRF,
                      files={"file": ("bad.zip", b"PK\x03\x04 not really a zip", "application/zip")},
                      timeout=60)
    if r.status_code >= 500:
        note("import garbage zip", r.status_code, r.text[:200])
    # 空 zip
    empty_zip = io.BytesIO()
    with zipfile.ZipFile(empty_zip, "w"):
        pass
    r = requests.post(f"{rest}/importexport/import/{scratch}", auth=AUTH, headers=CSRF,
                      files={"file": ("empty.zip", empty_zip.getvalue(), "application/zip")}, timeout=60)
    if r.status_code >= 500:
        note("import empty zip", r.status_code, r.text[:200])
    # zip-slip 風エントリ名
    slip = io.BytesIO()
    with zipfile.ZipFile(slip, "w") as z:
        z.writestr("../../../../tmp/evil.txt", "pwned")
        z.writestr("normal.txt", "ok")
    r = requests.post(f"{rest}/importexport/import/{scratch}", auth=AUTH, headers=CSRF,
                      files={"file": ("slip.zip", slip.getvalue(), "application/zip")}, timeout=60)
    if r.status_code >= 500:
        note("import zip-slip names", r.status_code, r.text[:200])
    # 巨大に展開される小さな zip (zip bomb 片鱗: 1 エントリを大きめに)
    bomb = io.BytesIO()
    with zipfile.ZipFile(bomb, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("big.txt", "0" * (5 * 1024 * 1024))
    r = requests.post(f"{rest}/importexport/import/{scratch}", auth=AUTH, headers=CSRF,
                      files={"file": ("bomb.zip", bomb.getvalue(), "application/zip")}, timeout=90)
    if r.status_code >= 500:
        note("import compressible bomb", r.status_code, r.text[:200])
    if len(findings) == note_pre:
        print("import edge cases: no 5xx")

    # ---- エクスポート: 大量/ルート近辺/不正 objectId ----
    for oid in [scratch, root_id, "nonexistent-obj", "../etc"]:
        r = requests.get(f"{rest}/importexport/export/{requests.utils.quote(oid, safe='')}",
                         auth=AUTH, headers=CSRF, timeout=90)
        if r.status_code >= 500:
            note(f"export {oid[:20]}", r.status_code, r.text[:150])

    # ---- 同時実行競合: 同じ文書を並行 checkOut / 並行 update / 並行 delete-create ----
    doc = bid(create_doc("concurrent.txt", b"seed", "text/plain", "seed concurrent doc"))
    if doc:
        def checkout():
            return requests.post(browser, auth=AUTH,
                                 data={"cmisaction": "checkOut", "objectId": doc}, timeout=40).status_code
        def update(i):
            return requests.post(browser, auth=AUTH, data={
                "cmisaction": "update", "objectId": doc,
                "propertyId[0]": "cmis:name", "propertyValue[0]": f"renamed{i}"}, timeout=40).status_code
        with cf.ThreadPoolExecutor(max_workers=8) as ex:
            cor = list(ex.map(lambda _: checkout(), range(8)))
            upr = list(ex.map(update, range(8)))
        print("concurrent checkout statuses:", sorted(set(cor)))
        print("concurrent update statuses:", sorted(set(upr)))
        for s in cor + upr:
            if s >= 500:
                note("concurrent op 5xx", s)

    # ---- 並行 createDocument 同名 (name-constraint レース) ----
    def create_same(i):
        rr = requests.post(browser, auth=AUTH, data={
            "cmisaction": "createDocument", "objectId": scratch,
            "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
            "propertyId[1]": "cmis:name", "propertyValue[1]": "samename.txt", "succinct": "true"},
            files={"content": ("s", b"x", "text/plain")}, timeout=40)
        return rr.status_code
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        same = list(ex.map(create_same, range(8)))
    print("concurrent same-name create statuses:", sorted(set(same)))
    for s in same:
        if s >= 500:
            note("concurrent same-name 5xx", s)

    # ---- 検索: CMIS SQL の変な組合せ (browser query) ----
    for q in [
        "SELECT * FROM cmis:document WHERE IN_FOLDER('bogus')",
        "SELECT * FROM cmis:document WHERE IN_TREE('../..')",
        "SELECT * FROM cmis:document WHERE cmis:name IN ('a','b'",  # unbalanced paren
        "SELECT * FROM cmis:document WHERE ANY cmis:name = 'x'",
        "SELECT cmis:objectId, cmis:objectId FROM cmis:document",  # dup select
        "SELECT * FROM cmis:folder JOIN cmis:document ON x = y",
    ]:
        r = requests.post(browser, auth=AUTH, headers=CSRF,
                          data={"cmisaction": "query", "q": q, "searchAllVersions": "false"}, timeout=40)
        if r.status_code >= 500:
            note(f"query: {q[:40]}", r.status_code, r.text[:150])

    # ---- cleanup ----
    requests.post(browser, auth=AUTH, data={
        "cmisaction": "deleteTree", "objectId": scratch,
        "allVersions": "true", "continueOnFailure": "true"}, timeout=120)
    print("cleanup done")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--repository", default="bedroom")
    a = p.parse_args()
    print(f"edge_fuzz2: {a.base_url}")
    run(a.base_url.rstrip("/"), a.repository)
    print(f"\n=== 500/exception findings: {len(findings)} ===")
    for f in findings:
        print(json.dumps(f, ensure_ascii=False))
    out = Path(__file__).resolve().parent / ("edge2-" + a.base_url.replace("://", "_").replace("/", "_").replace(".", "_") + ".json")
    out.write_text(json.dumps(findings, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
