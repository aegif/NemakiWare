#!/usr/bin/env python3
"""書き込み系ライフサイクルの探索的ファザー。

スクラッチフォルダを 1 つ作り、その中で create / checkout / checkin /
setContent / updateProperties / ACL apply / move / versioning / type CRUD /
archive restore / relationship / rendition / MCP tools をエッジケースで叩き、
**HTTP 5xx / 例外** を探す。最後にスクラッチフォルダを deleteTree で掃除する。

破壊対象は自分で作ったスクラッチ配下 + 自分で作った type/user のみ。既存の
シードデータ (組織共有文書ツリー等) には触れない。

使い方:
    python3 write_fuzz.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import uuid
from pathlib import Path

import requests

AUTH = ("admin", "admin")
CSRF = {"X-Requested-With": "XMLHttpRequest"}
IGNORE = {400, 401, 403, 404, 405, 409, 415, 429, 501}

findings: list[dict] = []
created_type_ids: list[str] = []


def hit(name, method, url, expect_ok=False, **kw):
    kw.setdefault("timeout", 60)
    kw.setdefault("auth", AUTH)
    try:
        r = requests.request(method, url, **kw)
    except requests.RequestException as e:
        findings.append({"name": name, "err": f"REQ-EXC {type(e).__name__}: {str(e)[:150]}", "url": url})
        return None
    if r.status_code >= 500:
        body = (r.text or "")[:300].replace("\n", " ")
        findings.append({"name": name, "status": r.status_code, "method": method,
                         "url": url.split("/core/")[-1][:80], "body": body})
    return r


def browser_id(r):
    try:
        d = r.json()
        return (d.get("succinctProperties") or {}).get("cmis:objectId") \
            or d.get("properties", {}).get("cmis:objectId", {}).get("value")
    except Exception:
        return None


def run(base: str, repo: str):
    browser = f"{base}/core/browser/{repo}"
    apiv1 = f"{base}/core/api/v1/cmis/repositories/{repo}"
    rest = f"{base}/core/rest/repo/{repo}"
    mcp = f"{base}/core/mcp/message"

    root = requests.get(f"{browser}/root", params={"cmisselector": "object", "succinct": "true"},
                        auth=AUTH, timeout=30)
    root_id = browser_id(root)
    if not root_id:
        print("cannot resolve root; abort"); return

    scratch_name = "monkey-scratch-" + uuid.uuid4().hex[:8]
    r = hit("mk scratch", "POST", browser, data={
        "cmisaction": "createFolder", "objectId": root_id,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:folder",
        "propertyId[1]": "cmis:name", "propertyValue[1]": scratch_name, "succinct": "true"})
    scratch = browser_id(r) if r else None
    if not scratch:
        print("cannot create scratch; abort"); return
    print("scratch:", scratch_name, scratch)

    def mkdoc(name, content=b"hello world content", mime="text/plain"):
        rr = requests.post(browser, auth=AUTH, data={
            "cmisaction": "createDocument", "objectId": scratch,
            "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
            "propertyId[1]": "cmis:name", "propertyValue[1]": name, "succinct": "true"},
            files={"content": (name, content, mime)}, timeout=60)
        return browser_id(rr)

    # ---- createDocument エッジケース ----
    hit("createDoc dup props (2x name)", "POST", browser, data={
        "cmisaction": "createDocument", "objectId": scratch,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "dup1",
        "propertyId[2]": "cmis:name", "propertyValue[2]": "dup2"},
        files={"content": ("dup", b"x", "text/plain")})
    hit("createDoc unknown type", "POST", browser, data={
        "cmisaction": "createDocument", "objectId": scratch,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "bogus:type",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "bt"},
        files={"content": ("bt", b"x", "text/plain")})
    hit("createDoc name with slashes/nul", "POST", browser, data={
        "cmisaction": "createDocument", "objectId": scratch,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "a/b\\c\x01d"},
        files={"content": ("x", b"x", "text/plain")})
    hit("createDoc huge name", "POST", browser, data={
        "cmisaction": "createDocument", "objectId": scratch,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "N" * 5000},
        files={"content": ("x", b"x", "text/plain")})
    hit("createDoc bad datetime prop", "POST", browser, data={
        "cmisaction": "createDocument", "objectId": scratch,
        "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
        "propertyId[1]": "cmis:name", "propertyValue[1]": "dt",
        "propertyId[2]": "cmis:creationDate", "propertyValue[2]": "not-a-date"},
        files={"content": ("x", b"x", "text/plain")})

    doc = mkdoc("lifecycle.txt")
    print("doc:", doc)

    # ---- versioning / checkout-checkin ----
    if doc:
        r = hit("checkOut", "POST", browser, data={"cmisaction": "checkOut", "objectId": doc, "succinct": "true"})
        pwc = browser_id(r) if r else None
        # double checkout (should be 409, not 500)
        hit("checkOut again", "POST", browser, data={"cmisaction": "checkOut", "objectId": doc})
        if pwc:
            hit("checkIn no content", "POST", browser, data={
                "cmisaction": "checkIn", "objectId": pwc, "major": "true", "checkinComment": "c"})
        # checkIn on a non-PWC id
        hit("checkIn on non-pwc", "POST", browser, data={
            "cmisaction": "checkIn", "objectId": doc, "major": "true"})
        # cancelCheckOut twice
        hit("cancelCheckOut on doc", "POST", browser, data={"cmisaction": "cancelCheckOut", "objectId": doc})
        # getAllVersions
        hit("getAllVersions", "GET", f"{browser}/root",
            params={"cmisselector": "versions", "objectId": doc})

    # ---- setContentStream / updateProperties edge ----
    if doc:
        hit("setContent overwrite=false existing", "POST", browser,
            data={"cmisaction": "setContent", "objectId": doc, "overwriteFlag": "false"},
            files={"content": ("x", b"new", "text/plain")})
        hit("updateProps bad prop id", "POST", browser, data={
            "cmisaction": "update", "objectId": doc,
            "propertyId[0]": "nonexistent:prop", "propertyValue[0]": "x"})
        hit("updateProps set name empty", "POST", browser, data={
            "cmisaction": "update", "objectId": doc,
            "propertyId[0]": "cmis:name", "propertyValue[0]": ""})

    # ---- ACL apply edge cases ----
    if doc:
        hit("applyACL unknown permission", "POST", browser, data={
            "cmisaction": "applyACL", "objectId": doc,
            "addACEPrincipal[0]": "someone", "addACEPermission[0][0]": "cmis:superpower"})
        hit("applyACL empty principal", "POST", browser, data={
            "cmisaction": "applyACL", "objectId": doc,
            "addACEPrincipal[0]": "", "addACEPermission[0][0]": "cmis:read"})
        hit("applyACL no permission array", "POST", browser, data={
            "cmisaction": "applyACL", "objectId": doc, "addACEPrincipal[0]": "someone"})
        # legacy REST node acl with malformed body
        hit("legacy acl bad json", "POST", f"{rest}/node/{doc}/acl", headers=CSRF,
            data="{ not json")
        hit("legacy acl breakInheritance non-bool", "POST", f"{rest}/node/{doc}/acl", headers=CSRF,
            json={"breakInheritance": "yes", "permissions": [{"principalId": "x", "permissions": ["cmis:read"]}]})

    # ---- move edge cases ----
    if doc:
        hit("move bad source folder", "POST", browser, data={
            "cmisaction": "move", "objectId": doc, "sourceFolderId": "nope", "targetFolderId": scratch})
        hit("move into itself", "POST", browser, data={
            "cmisaction": "move", "objectId": scratch, "sourceFolderId": root_id, "targetFolderId": scratch})

    # ---- type CRUD edge cases (api/v1 or legacy) ----
    tname = "monkeytype:" + uuid.uuid4().hex[:6]
    hit("type create minimal", "POST", f"{rest}/type/create", headers=CSRF,
        data={"type": json.dumps({"id": tname, "baseId": "cmis:document",
                                  "parentId": "cmis:document", "displayName": tname,
                                  "queryName": tname, "propertyDefinitions": {}})})
    created_type_ids.append(tname)
    hit("type create malformed json", "POST", f"{rest}/type/create", headers=CSRF,
        data={"type": "{ broken"})
    hit("type create no baseId", "POST", f"{rest}/type/create", headers=CSRF,
        data={"type": json.dumps({"id": "x:y", "displayName": "z"})})
    hit("type create circular parent", "POST", f"{rest}/type/create", headers=CSRF,
        data={"type": json.dumps({"id": "self:ref", "baseId": "cmis:document",
                                  "parentId": "self:ref", "displayName": "s", "queryName": "selfref"})})
    hit("type delete base type", "DELETE", f"{rest}/type/delete/cmis:document", headers=CSRF)

    # ---- archive restore edge cases ----
    hit("archive restore nonexistent", "PUT", f"{rest}/archive/nonexistent-id/restore", headers=CSRF)
    hit("archive index bad paging", "GET", f"{rest}/archive/index", params={"skip": "-1", "limit": "abc"})

    # ---- relationship create edge ----
    if doc:
        hit("createRelationship bad targets", "POST", browser, data={
            "cmisaction": "createRelationship",
            "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:relationship",
            "propertyId[1]": "cmis:sourceId", "propertyValue[1]": doc,
            "propertyId[2]": "cmis:targetId", "propertyValue[2]": "does-not-exist"})

    # ---- rendition ----
    if doc:
        hit("renditions", "GET", f"{browser}/root", params={"cmisselector": "renditions", "objectId": doc})

    # ---- MCP tools deeper (needs session) ----
    def mcp_call(body):
        return requests.post(mcp, json=body, auth=AUTH, timeout=40)
    try:
        login = mcp_call({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                     "params": {"name": "nemakiware_login",
                                "arguments": {"username": "admin", "password": "admin", "repositoryId": repo}}})
        tok = None
        try:
            txt = login.json()["result"]["content"][0]["text"]; tok = json.loads(txt).get("session_token")
        except Exception:
            pass
        for nm, args in [
            ("nemakiware_get_document_content", {"documentId": "../etc/passwd", "sessionToken": tok}),
            ("nemakiware_get_document_content", {"documentId": doc or "x", "sessionToken": tok, "maxLength": -1}),
            ("nemakiware_similar_documents", {"documentId": "nope", "sessionToken": tok, "topK": -3}),
            ("nemakiware_search", {"query": "SELECT bogus FROM nothing", "sessionToken": tok}),
            ("nemakiware_search", {"query": "SELECT * FROM cmis:document WHERE CONTAINS('\"')", "sessionToken": tok}),
            ("nemakiware_rag_search", {"query": "z" * 20000, "sessionToken": tok}),
        ]:
            r = mcp_call({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                     "params": {"name": nm, "arguments": args}})
            if r.status_code >= 500:
                findings.append({"name": f"mcp {nm}", "status": r.status_code,
                                 "body": (r.text or "")[:200].replace("\n", " ")})
    except Exception as e:
        findings.append({"name": "mcp block", "err": str(e)[:150]})

    # ---- cleanup ----
    ok = requests.post(browser, auth=AUTH, data={
        "cmisaction": "deleteTree", "objectId": scratch,
        "allVersions": "true", "continueOnFailure": "true"}, timeout=120)
    print("cleanup scratch:", ok.status_code)
    for tid in created_type_ids:
        requests.delete(f"{rest}/type/delete/{tid}", auth=AUTH, headers=CSRF, timeout=30)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--repository", default="bedroom")
    args = p.parse_args()
    print(f"write_fuzz: {args.base_url} repo={args.repository}")
    run(args.base_url.rstrip("/"), args.repository)
    print(f"\n=== 500/exception findings: {len(findings)} ===")
    for f in findings:
        print(json.dumps(f, ensure_ascii=False))
    out = Path(__file__).resolve().parent / ("write-fuzz-" + args.base_url.replace("://", "_").replace("/", "_").replace(".", "_") + ".json")
    out.write_text(json.dumps(findings, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
