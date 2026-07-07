#!/usr/bin/env python3
"""MCP テストシナリオランナー: 同じ質問でもユーザによって応答が変わることを見せる。

各シナリオは「1つの自然言語クエリ × 複数ペルソナ」。ペルソナ毎に
MCP の nemakiware_login でセッションを取得し、nemakiware_rag_search
(ベクトル検索) を同一クエリで実行して結果を並べて比較する。

使い方:
    python3 mcp_scenarios.py                       # 全シナリオ実行
    python3 mcp_scenarios.py --scenario aurora     # 1つだけ
    python3 mcp_scenarios.py --report report.md    # Markdown レポート出力
    python3 mcp_scenarios.py --engine rest         # REST /rag/search で実行 (JSON)

前提: setup_test_env.py 実行済み + RAG インデックス完了 (--wait-rag)。
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from nemaki_client import NemakiApiError, NemakiClient
from org_model import DEFAULT_PASSWORD, USERS, folder_visibility

HERE = Path(__file__).resolve().parent
USER_BY_ID = {u.user_id: u for u in USERS}

# RAG レート制限 (既定 2 req/s, burst 5) を踏まないための呼び出し間隔
SEARCH_INTERVAL_SEC = 0.8


@dataclass(frozen=True)
class Scenario:
    key: str
    title: str
    query: str
    personas: tuple[str, ...]
    expectation: str  # 何が見どころかの説明 (レポート用)


SCENARIOS: tuple[Scenario, ...] = (
    Scenario(
        "aurora",
        "機密プロジェクト: 新製品Auroraの価格戦略",
        "新製品Auroraの価格戦略と料金プランについて教えてください",
        ("asada", "ogawa", "otsuka", "mori", "miyata"),
        "プロジェクトXメンバー (浅田/小川) だけが価格シミュレーションや製品仕様に到達する。"
        "社長 (大塚) はプロジェクトフォルダの ACL に含まれないため、取締役会議事録の言及しか見えない。"
        "非メンバーの課長 (森) や他部門 (宮田) にはほぼ何も返らない。",
    ),
    Scenario(
        "salary",
        "人事機密: 賞与と給与改定",
        "賞与の支給基準と給与テーブルの改定状況について知りたい",
        ("shimizu", "nagai", "kudo", "miyata"),
        "人事課 (清水) は給与テーブル改定案 (厳秘) まで到達。管理本部長 (永井) は評価情報 (経営 read) と"
        "全社共有の賞与規程までで、給与フォルダ (人事課限定) は見えない。他部門は賞与規程のみ。",
    ),
    Scenario(
        "sales",
        "営業情報: 顧客提案と見積の状況",
        "DocuHive導入を提案している顧客と見積の状況を教えて",
        ("kudo", "hoshino", "fukuda", "otsuka"),
        "営業本部 (工藤/星野) は提案書・見積書・訪問報告に広く到達。開発課長 (福田) は営業フォルダの"
        "ACL 外なので技術文書しか出ない。社長 (大塚) は経営 read で営業文書も見える。",
    ),
    Scenario(
        "incident",
        "技術情報: システム障害の状況",
        "最近発生したシステム障害の原因と対応状況を教えてください",
        ("hirata", "miyata", "okamoto", "kudo"),
        "技術本部 (平田/宮田) は障害報告書・運用手順に到達。経理 (岡本) と営業本部長 (工藤) は"
        "技術本部フォルダが見えないため、ほぼ結果なし。",
    ),
    Scenario(
        "board",
        "経営機密: 取締役会の決定事項",
        "取締役会で議論された経営課題と決定事項を教えて",
        ("otsuka", "nagai", "mori", "baba"),
        "経営会議メンバー (大塚/永井) だけが取締役会議事録と中期経営計画に到達。"
        "課長 (森) や法務 (馬場) には経営企画フォルダが見えない。",
    ),
)

RESULT_LINE = re.compile(r"^\s*\d+\.\s+\[(?P<name>.+?)\]\(.*?\)(?:\s+\(類似度:\s*(?P<score>\d+)%\))?")


def parse_mcp_results(markdown: str) -> list[tuple[str, str]]:
    """MCP の Markdown 応答から (文書名, 類似度%) のリストを抜き出す。"""
    hits = []
    for line in markdown.splitlines():
        m = RESULT_LINE.match(line)
        if m:
            name = re.sub(r"\\(.)", r"\1", m.group("name"))  # Markdown エスケープ除去
            hits.append((name, m.group("score") or "-"))
    return hits


def persona_label(user_id: str) -> str:
    u = USER_BY_ID[user_id]
    return f"{u.name}（{u.title}）"


def readable_areas(user_id: str) -> list[str]:
    """org_model の宣言から、このユーザが読めるエリア (トップ直下) を出す。"""
    vis = folder_visibility()
    areas = sorted({path.split("/")[0] for path, readers in vis.items() if user_id in readers})
    return areas


def run_search(client: NemakiClient, engine: str, user_id: str,
               query: str, top_k: int) -> list[tuple[str, str]]:
    auth = (user_id, DEFAULT_PASSWORD)
    if engine == "rest":
        data = client.rag_search(query, top_k=top_k, auth=auth)
        results = data.get("results") or data.get("documents") or []
        hits = []
        for item in results:
            name = item.get("name") or item.get("documentName") or item.get("title") or str(item)[:60]
            score = item.get("score") or item.get("similarity")
            hits.append((name, f"{float(score) * 100:.0f}" if score is not None else "-"))
        return hits
    token = client.mcp_login(user_id, DEFAULT_PASSWORD)
    try:
        markdown = client.mcp_rag_search(token, query, top_k=top_k)
        return parse_mcp_results(markdown)
    finally:
        client.mcp_logout(token)


def run_scenario(client: NemakiClient, sc: Scenario, engine: str,
                 top_k: int, report: list[str]) -> None:
    header = f"シナリオ [{sc.key}] {sc.title}"
    print("=" * 72)
    print(header)
    print(f"クエリ: {sc.query}")
    print("-" * 72)
    report.append(f"\n## {sc.title}\n")
    report.append(f"**クエリ:** {sc.query}\n")
    report.append(f"**見どころ:** {sc.expectation}\n")

    for user_id in sc.personas:
        label = persona_label(user_id)
        areas = readable_areas(user_id)
        try:
            hits = run_search(client, engine, user_id, sc.query, top_k)
            error = None
        except NemakiApiError as e:
            hits, error = [], str(e)
        print(f"\n▼ {label}")
        print(f"  読めるエリア: {', '.join(areas) if areas else '(なし)'}")
        report.append(f"\n### {label}\n")
        report.append(f"- 読めるエリア: {', '.join(areas) if areas else '(なし)'}\n")
        if error:
            print(f"  エラー: {error}")
            report.append(f"- エラー: `{error}`\n")
        elif not hits:
            print("  ヒットなし（この質問に答えられる文書が見えていない）")
            report.append("- **ヒットなし**（この質問に答えられる文書が見えていない）\n")
        else:
            for name, score in hits:
                print(f"  {score:>3}%  {name}")
                report.append(f"- {score}% — {name}\n")
        time.sleep(SEARCH_INTERVAL_SEC)

    # 差分サマリ: ペルソナ毎のヒット文書集合を比較
    print()


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--repository", default="bedroom")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-password", default="admin")
    p.add_argument("--scenario", help="実行するシナリオ key (既定: 全部)")
    p.add_argument("--engine", choices=("mcp", "rest"), default="mcp")
    p.add_argument("--top-k", type=int, default=6)
    p.add_argument("--report", help="Markdown レポートの出力先パス")
    args = p.parse_args()

    client = NemakiClient(args.base_url, args.repository,
                          args.admin_user, args.admin_password)
    if not client.check_core():
        print("エラー: NemakiWare core に接続できません")
        return 1
    try:
        rag = client.rag_health()
        if not rag.get("enabled"):
            print("エラー: RAG が無効です (rag.enabled=false または TEI 未起動)")
            return 1
    except NemakiApiError as e:
        print(f"エラー: RAG ヘルス確認に失敗: {e}")
        return 1

    targets = [s for s in SCENARIOS if not args.scenario or s.key == args.scenario]
    if not targets:
        print(f"シナリオ '{args.scenario}' は存在しません。候補: {[s.key for s in SCENARIOS]}")
        return 1

    report: list[str] = [
        "# MCP ユーザ別応答比較レポート\n",
        f"対象: {args.base_url} / repository={args.repository} / engine={args.engine}\n",
        "同一クエリを異なるユーザで実行し、ACL によって RAG (ベクトル検索) の結果が変わることを確認する。\n",
    ]
    for sc in targets:
        run_scenario(client, sc, args.engine, args.top_k, report)

    if args.report:
        Path(args.report).write_text("".join(report), encoding="utf-8")
        print(f"レポート出力: {args.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
