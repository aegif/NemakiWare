#!/usr/bin/env python3
"""デモ動画用データ収集: 組織概観 + ユーザ別のライブ MCP 応答を JSON に固める。

viewer.html が読む demo-data.json を生成する。MCP 応答は本物
(nemakiware_login → nemakiware_rag_search) をそのまま記録する。

使い方:
    python3 fetch_demo_data.py --base-url https://35.79.113.17.nip.io \
                               --manifest ../manifest-remote.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from doc_factory import CATEGORY_SPECS, TOTAL_DOCS  # noqa: E402
from nemaki_client import NemakiClient  # noqa: E402
from org_model import FOLDERS, GROUPS, USERS, folder_visibility  # noqa: E402

RESULT_LINE = re.compile(
    r"^\s*\d+\.\s+\[(?P<name>.+?)\]\(.*?\)(?:\s+\(類似度:\s*(?P<score>\d+)%\))?")

# 動画で使うシナリオ (ペルソナは 3 名ずつ、対比が際立つ組み合わせ)
SCENARIOS = [
    {
        "key": "aurora",
        "title": "機密プロジェクト",
        "query": "新製品Auroraの価格戦略と料金プランについて教えてください",
        "personas": ["asada", "otsuka", "miyata"],
        "note": "プロジェクトXメンバーだけが極秘の価格資料に到達。社長ですらフォルダACL外では一般の営業資料しか出ない。",
    },
    {
        "key": "salary",
        "title": "人事機密",
        "query": "賞与の支給基準と給与テーブルの改定状況について知りたい",
        "personas": ["shimizu", "nagai", "miyata"],
        "note": "人事課だけが厳秘の給与テーブルに到達。管理本部長でも評価情報まで。他部門は全社規程のみ。",
    },
    {
        "key": "incident",
        "title": "技術情報",
        "query": "最近発生したシステム障害の原因と対応状況を教えてください",
        "personas": ["miyata", "otsuka", "okamoto"],
        "note": "ネストグループ(インフラ課⊂技術本部)経由の宮田も障害報告に到達 — 3.2.3のネスト解決修正の実証。経理には見えない。",
    },
]

PERSONA_COLORS = {
    "otsuka": "#e8590c", "kudo": "#0b7285", "hirata": "#5f3dc4", "nagai": "#a61e4d",
    "mori": "#087f5b", "asada": "#1971c2", "ueda": "#087f5b", "hoshino": "#1971c2",
    "fukuda": "#5f3dc4", "ogawa": "#7048e8", "nishida": "#7048e8", "miyata": "#3b5bdb",
    "shimizu": "#c2255c", "okamoto": "#e67700", "baba": "#2b8a3e",
}


def parse_hits(markdown: str) -> list[dict]:
    hits = []
    for line in markdown.splitlines():
        m = RESULT_LINE.match(line)
        if m:
            name = re.sub(r"\\(.)", r"\1", m.group("name"))
            hits.append({"name": name, "score": int(m.group("score") or 0)})
    return hits


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--repository", default="bedroom")
    p.add_argument("--manifest", default=str(HERE.parent / "manifest-remote.json"))
    p.add_argument("--out", default=str(HERE / "demo-data.json"))
    args = p.parse_args()

    client = NemakiClient(args.base_url, args.repository)
    user_by_id = {u.user_id: u for u in USERS}
    vis = folder_visibility()

    # --- 環境情報 -----------------------------------------------------------
    rag = client.rag_index_health()
    env = {
        "host": args.base_url.replace("https://", "").replace("http://", ""),
        "version": "3.2.3",
        "ragDocs": rag.get("ragDocumentCount"),
        "ragChunks": rag.get("ragChunkCount"),
        "embedding": "Amazon Bedrock (titan-embed-text-v2, 1024次元)",
    }

    # --- 組織 ---------------------------------------------------------------
    users = [{"id": u.user_id, "name": u.name, "title": u.title,
              "projx": "新製品X" in u.title,
              "color": PERSONA_COLORS.get(u.user_id, "#555")} for u in USERS]
    groups = [{"id": g.group_id, "name": g.name, "users": list(g.users),
               "groups": list(g.groups)} for g in GROUPS]

    # --- フォルダ (エリア別: ACL + 文書数) ----------------------------------
    doc_count_by_category = {k: n for k, (n, _) in CATEGORY_SPECS.items()}
    folders = []
    for f in FOLDERS:
        entry = {
            "path": list(f.path),
            "acl": [[p_, perm.split(":")[1]] for p_, perm in f.acl] if f.acl else None,
            "docs": doc_count_by_category.get(f.doc_category, 0) if f.doc_category else 0,
        }
        folders.append(entry)

    # --- MCP ライブ応答 ------------------------------------------------------
    scenarios = []
    for sc in SCENARIOS:
        entry = {"key": sc["key"], "title": sc["title"], "query": sc["query"],
                 "note": sc["note"], "personas": []}
        for uid in sc["personas"]:
            u = user_by_id[uid]
            areas = sorted({path.split("/")[0] for path, readers in vis.items()
                            if uid in readers})
            print(f"[{sc['key']}] MCP login → rag_search as {uid} ...", flush=True)
            token = client.mcp_login(uid, "Pass1234")
            try:
                md = client.mcp_rag_search(token, sc["query"], top_k=4)
            finally:
                client.mcp_logout(token)
            hits = parse_hits(md)
            entry["personas"].append({
                "id": uid, "name": u.name, "title": u.title,
                "color": PERSONA_COLORS.get(uid, "#555"),
                "areas": areas, "hits": hits,
            })
            time.sleep(0.8)  # RAG レート制限回避
        scenarios.append(entry)

    data = {"env": env, "users": users, "groups": groups, "folders": folders,
            "totalDocs": TOTAL_DOCS, "scenarios": scenarios}
    Path(args.out).write_text(json.dumps(data, ensure_ascii=False, indent=1),
                              encoding="utf-8")
    print(f"wrote {args.out}")
    for sc in scenarios:
        for pe in sc["personas"]:
            names = [h["name"][:30] for h in pe["hits"][:2]]
            print(f"  {sc['key']:>8} {pe['id']:>8}: {len(pe['hits'])} hits {names}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
