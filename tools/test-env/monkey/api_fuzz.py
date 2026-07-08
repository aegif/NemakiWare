#!/usr/bin/env python3
"""API エッジケース・ファザー (探索的バグあぶり出し用)。

CMIS Browser Binding / api/v1 / legacy REST / MCP に対し、境界値・不正値・
インジェクション片・型不一致などを投げ、**サーバ 500 / 予期せぬ例外**を
探す。認可・不存在・バリデーションの 4xx は想定内として無視する。

使い方:
    python3 api_fuzz.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

AUTH = ("admin", "admin")
CSRF = {"X-Requested-With": "XMLHttpRequest"}
IGNORE = {400, 401, 403, 404, 405, 409, 415, 429, 501}  # 想定内クライアントエラー

findings: list[dict] = []


def check(name: str, method: str, url: str, **kw):
    kw.setdefault("timeout", 40)
    kw.setdefault("auth", AUTH)
    try:
        r = requests.request(method, url, **kw)
    except requests.RequestException as e:
        findings.append({"name": name, "url": url, "err": f"REQ-EXC {type(e).__name__}: {str(e)[:150]}"})
        return None
    if r.status_code >= 500 or r.status_code == 0:
        body = (r.text or "")[:300].replace("\n", " ")
        findings.append({"name": name, "status": r.status_code, "method": method,
                         "url": url.replace("https://", "").replace("http://", ""), "body": body})
    return r


def run(base: str, repo: str):
    browser = f"{base}/core/browser/{repo}"
    apiv1 = f"{base}/core/api/v1/cmis/repositories/{repo}"
    rest = f"{base}/core/rest/repo/{repo}"
    mcp = f"{base}/core/mcp/message"

    # --- CMIS Browser Binding: 不正 selector / objectId / paging ---
    check("browser bad selector", "GET", f"{browser}/root", params={"cmisselector": "'; DROP--"})
    check("browser bad objectId", "GET", f"{browser}/root",
          params={"cmisselector": "object", "objectId": "../../etc/passwd"})
    check("browser neg maxItems", "GET", f"{browser}/root",
          params={"cmisselector": "children", "maxItems": "-5", "skipCount": "-1"})
    check("browser huge skip", "GET", f"{browser}/root",
          params={"cmisselector": "children", "maxItems": "999999999", "skipCount": "999999999"})
    check("browser nonnum maxItems", "GET", f"{browser}/root",
          params={"cmisselector": "children", "maxItems": "abc"})

    # --- CMIS Query (SQL) 経由の変な入力 ---
    for q in [
        "SELECT * FROM cmis:document WHERE cmis:name LIKE '%'",
        "SELECT * FROM cmis:document WHERE cmis:name = ''''",
        "SELECT * FROM cmis:document ORDER BY nonexistent:prop",
        "SELECT bogus FROM cmis:document",
        "SELECT * FROM cmis:document WHERE CONTAINS('\"')",
        "not a query at all",
        "SELECT * FROM cmis:document WHERE cmis:objectId IN (" + ",".join(["'x'"] * 500) + ")",
    ]:
        check("query", "POST", browser, headers=CSRF,
              data={"cmisaction": "query", "q": q, "searchAllVersions": "false"})

    # --- api/v1 RAG search 境界値 ---
    for body in [
        {"query": ""},
        {"query": "x" * 20000},
        {"query": "賞与", "topK": -1},
        {"query": "賞与", "topK": 100000},
        {"query": "賞与", "minScore": 5.0},
        {"query": "賞与", "minScore": "abc"},
        {"query": None},
        {},
        {"query": "賞与", "propertyBoost": "NaN", "contentBoost": -9},
        {"query": "賞与", "folderId": "does-not-exist"},
    ]:
        check("rag/search", "POST", f"{apiv1}/rag/search", headers=CSRF, json=body)

    # --- api/v1 CMIS query resource / users / groups の変な入力 ---
    check("users bad paging", "GET", f"{apiv1}/users", params={"skip": "-1", "limit": "-1"})
    check("users huge limit", "GET", f"{apiv1}/users", params={"skip": "0", "limit": "100000000"})
    check("groups bad paging", "GET", f"{apiv1}/groups", params={"skip": "abc", "limit": "xyz"})
    check("user weird id", "GET", f"{apiv1}/users/" + requests.utils.quote("../../*", safe=""))
    check("group weird id", "GET", f"{apiv1}/groups/" + requests.utils.quote("%00null", safe=""))
    check("rag similar bad id", "GET", f"{apiv1}/rag/similar/" + requests.utils.quote("../x", safe=""),
          headers=CSRF)

    # --- legacy REST: node acl / type ---
    check("acl get weird node", "GET", f"{rest}/node/" + requests.utils.quote("../x", safe="") + "/acl")
    check("type detail missing", "GET", f"{rest}/type/detail/nonexistent:type")

    # --- search-engine RAG admin ---
    check("rag health", "GET", f"{apiv1}/search-engine/rag/health", headers=CSRF)
    check("rag status", "GET", f"{apiv1}/search-engine/rag/status", headers=CSRF)

    # --- MCP: 不正 JSON-RPC / 未知メソッド / 型不一致 ---
    mcp_bodies = [
        {"jsonrpc": "2.0", "id": 1, "method": "does/notexist"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "nope", "arguments": {}}},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
         "params": {"name": "nemakiware_rag_search", "arguments": {"query": 12345, "sessionToken": ""}}},
        {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
         "params": {"name": "nemakiware_login", "arguments": {"username": None, "password": []}}},
        {"jsonrpc": "2.0", "id": 5, "method": "initialize", "params": "not-an-object"},
        {"garbage": True},
        [1, 2, 3],
    ]
    for i, mb in enumerate(mcp_bodies):
        check(f"mcp[{i}]", "POST", mcp, headers={"Content-Type": "application/json"},
              data=json.dumps(mb), auth=AUTH)

    # --- createDocument with weird props (no content) ---
    root = requests.get(f"{browser}/root", params={"cmisselector": "object", "succinct": "true"},
                        auth=AUTH, timeout=30)
    try:
        root_id = root.json().get("succinctProperties", {}).get("cmis:objectId")
    except Exception:
        root_id = None
    if root_id:
        check("createDoc empty name", "POST", browser, headers=CSRF, data={
            "cmisaction": "createDocument", "objectId": root_id,
            "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:document",
            "propertyId[1]": "cmis:name", "propertyValue[1]": ""})
        check("createFolder dup props", "POST", browser, headers=CSRF, data={
            "cmisaction": "createFolder", "objectId": root_id,
            "propertyId[0]": "cmis:objectTypeId", "propertyValue[0]": "cmis:folder",
            "propertyId[1]": "cmis:name", "propertyValue[1]": "x",
            "propertyId[2]": "cmis:name", "propertyValue[2]": "y"})


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--repository", default="bedroom")
    args = p.parse_args()
    print(f"api_fuzz: {args.base_url} repo={args.repository}")
    run(args.base_url.rstrip("/"), args.repository)
    print(f"\n=== 500/exception findings: {len(findings)} ===")
    for f in findings:
        print(json.dumps(f, ensure_ascii=False))
    out = HERE / ("api-fuzz-" + args.base_url.replace("://", "_").replace("/", "_").replace(".", "_") + ".json")
    out.write_text(json.dumps(findings, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
