#!/usr/bin/env python3
"""テスト環境セットアップ: 階層組織ユーザ/グループ + フォルダACL + 文書300件投入。

使い方:
    python3 setup_test_env.py [--base-url http://localhost:8080] [--repository bedroom]
                              [--admin-user admin] [--admin-password admin]
                              [--skip-docs] [--wait-rag] [--rag-timeout 3600]

投入内容 (org_model.py / doc_factory.py で宣言):
    - ユーザ 15 名 (パスワードは全員 Pass1234)
    - ネストグループ 13 (課 → 本部 → 全社、経営会議、部門横断プロジェクト)
    - トップフォルダ「組織共有文書」配下に文書種類毎のフォルダ + エリア別 ACL
    - Office 文書 300 件 (docx/xlsx/pptx/pdf)

再実行は安全 (ユーザ/グループ/フォルダは既存再利用、文書は同名スキップ)。
作成物は manifest.json に記録され、teardown_test_env.py で削除できる。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from doc_factory import CATEGORY_SPECS, TOTAL_DOCS, iter_documents
from nemaki_client import Ace, NemakiApiError, NemakiClient
from org_model import (DEFAULT_PASSWORD, FOLDERS, GROUPS, TOP_FOLDER_NAME,
                       USERS, transitive_users)

HERE = Path(__file__).resolve().parent


def log(msg: str) -> None:
    print(msg, flush=True)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--repository", default="bedroom")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-password", default="admin")
    p.add_argument("--manifest", default=str(HERE / "manifest.json"))
    p.add_argument("--skip-docs", action="store_true", help="文書投入をスキップ (組織のみ作成)")
    p.add_argument("--wait-rag", action="store_true", help="RAG インデックスの追い付きを待つ")
    p.add_argument("--rag-timeout", type=int, default=3600, help="--wait-rag の最大待機秒数")
    return p.parse_args()


def setup_users(client: NemakiClient, manifest: dict) -> None:
    log("=== 1. ユーザ作成 (15名) ===")
    for u in USERS:
        result = client.create_user(u.user_id, u.name, DEFAULT_PASSWORD,
                                    email=f"{u.user_id}@hinata.example")
        manifest["users"].append(u.user_id)
        log(f"  {u.user_id:<10} {u.name} ({u.title}): {result}")


def setup_groups(client: NemakiClient, manifest: dict) -> None:
    log("=== 2. グループ作成 (子→親の順、ネストあり) ===")
    # NemakiWare の実効 ACL 評価は直接メンバーのみ参照するため、
    # users には推移的メンバーを展開して投入する (org_model.transitive_users 参照)
    for g in GROUPS:
        users = sorted(transitive_users(g.group_id))
        result = client.create_group(g.group_id, g.name, users, list(g.groups))
        if result == "exists":
            result = client.update_group(g.group_id, g.name, users, list(g.groups))
        manifest["groups"].append(g.group_id)
        nested = f" (nested: {', '.join(g.groups)})" if g.groups else ""
        log(f"  {g.group_id:<20} {g.name}: {result} users={len(users)}{nested}")


def _acl_matches(client: NemakiClient, folder_id: str,
                 declared: tuple[tuple[str, str], ...]) -> bool:
    """フォルダの現在のローカル ACL が宣言と一致するか (継承遮断済み含む)。"""
    try:
        current = client.get_acl(folder_id).get("acl", {})
    except NemakiApiError:
        return False
    if current.get("aclInherited") is not False:
        return False
    direct = {(e.get("principalId"), frozenset(e.get("permissions", [])))
              for e in current.get("permissions", []) if e.get("direct")}
    want = {(p, frozenset([perm])) for p, perm in declared}
    return direct == want


def setup_folders(client: NemakiClient, manifest: dict) -> dict[str, str]:
    """フォルダツリーを作成し ACL を設定。doc_category → folderId の対応を返す。"""
    log(f"=== 3. フォルダ作成 + ACL 設定 (トップ: {TOP_FOLDER_NAME}) ===")
    root_id = client.get_root_folder_id()

    top_id = client.get_child_by_name(root_id, TOP_FOLDER_NAME)
    if top_id:
        log(f"  {TOP_FOLDER_NAME}: 既存を再利用 ({top_id})")
    else:
        top_id = client.create_folder(root_id, TOP_FOLDER_NAME)
        log(f"  {TOP_FOLDER_NAME}: created ({top_id})")
    if top_id == root_id:
        raise RuntimeError("トップフォルダの解決結果がリポジトリルートと一致しました。中断します")
    manifest["top_folder_id"] = top_id

    ids_by_path: dict[tuple[str, ...], str] = {(): top_id}
    category_folders: dict[str, str] = {}
    for f in FOLDERS:
        parent_id = ids_by_path[f.path[:-1]]
        folder_id = client.get_child_by_name(parent_id, f.path[-1])
        created = folder_id is None
        if created:
            folder_id = client.create_folder(parent_id, f.path[-1])
        ids_by_path[f.path] = folder_id
        path_str = "/".join(f.path)
        manifest["folders"][path_str] = folder_id

        acl_note = ""
        if f.acl is not None:
            # 既に宣言どおりなら再適用しない。ACL 変更は非同期の RAG ACL 更新を
            # 誘発し、既存文書のチャンクが消える既知の問題があるため冪等化する
            if _acl_matches(client, folder_id, f.acl):
                acl_note = " ACL=済"
            else:
                aces = [Ace(principal, [perm]) for principal, perm in f.acl]
                client.set_acl(folder_id, aces, break_inheritance=True)
                acl_note = " ACL={" + ", ".join(f"{p}:{perm.split(':')[1]}" for p, perm in f.acl) + "}"
        if f.doc_category:
            category_folders[f.doc_category] = folder_id
        log(f"  {path_str}: {'created' if created else 'reuse'}{acl_note}")

    missing = set(CATEGORY_SPECS) - set(category_folders)
    if missing:
        raise RuntimeError(f"doc_category に対応するフォルダ定義がありません: {missing}")
    return category_folders


def upload_documents(client: NemakiClient, manifest: dict,
                     category_folders: dict[str, str]) -> None:
    log(f"=== 4. 文書投入 ({TOTAL_DOCS}件) ===")
    existing_names = {d["name"] for d in manifest["documents"]}
    done = skipped = failed = 0
    started = time.time()
    for doc in iter_documents():
        if doc.filename in existing_names:
            skipped += 1
            continue
        folder_id = category_folders[doc.category]
        try:
            obj_id = client.create_document(folder_id, doc.filename, doc.content, doc.mimetype)
            manifest["documents"].append(
                {"id": obj_id, "name": doc.filename, "category": doc.category})
            done += 1
        except NemakiApiError as e:
            body = (e.body or "").lower()
            if e.status in (409,) or "already" in body or "nameconstraint" in body:
                skipped += 1
            else:
                failed += 1
                log(f"  !! {doc.filename}: {e}")
        if (done + skipped + failed) % 25 == 0:
            elapsed = time.time() - started
            log(f"  ... {done + skipped + failed}/{TOTAL_DOCS} "
                f"(created={done}, skipped={skipped}, failed={failed}, {elapsed:.0f}s)")
    log(f"  完了: created={done}, skipped={skipped}, failed={failed}")
    if failed:
        raise RuntimeError(f"{failed} 件の文書投入に失敗しました (ログ参照)")


def wait_for_rag(client: NemakiClient, timeout: int) -> None:
    """RAG インデックス済み文書数が伸び切る (プラトー) まで待つ。"""
    log("=== 5. RAG インデックス待機 ===")
    deadline = time.time() + timeout
    last_count = -1
    stable_rounds = 0
    while time.time() < deadline:
        try:
            h = client.rag_index_health()
        except NemakiApiError as e:
            log(f"  rag health 取得失敗 ({e})。30秒後に再試行")
            time.sleep(30)
            continue
        doc_count = h.get("ragDocumentCount", 0)
        chunk_count = h.get("ragChunkCount", 0)
        eligible = h.get("eligibleDocuments", 0)
        log(f"  indexed={doc_count}/{eligible} 文書, chunks={chunk_count}")
        if doc_count >= eligible and eligible > 0:
            log("  RAG インデックス追い付き完了")
            return
        if doc_count == last_count:
            stable_rounds += 1
            if stable_rounds >= 10:
                log("  警告: インデックス数が5分間増加していません。TEI ログを確認してください")
                return
        else:
            stable_rounds = 0
        last_count = doc_count
        time.sleep(30)
    log("  警告: タイムアウトしました。インデックスはバックグラウンドで継続します")


def main() -> int:
    args = parse_args()
    client = NemakiClient(args.base_url, args.repository,
                          args.admin_user, args.admin_password)

    log(f"対象: {args.base_url} / repository={args.repository}")
    if not client.check_core():
        log("エラー: NemakiWare core に接続できません。Docker スタックの起動を確認してください")
        return 1
    log("core 接続 OK")

    try:
        rag = client.rag_health()
        rag_enabled = bool(rag.get("enabled"))
    except NemakiApiError:
        rag_enabled = False
    if not rag_enabled:
        log("警告: RAG が無効です。ベクトル検索シナリオには --profile rag での起動 + rag.enabled=true が必要です")

    manifest_path = Path(args.manifest)
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        log(f"既存 manifest を再利用: {manifest_path} (文書 {len(manifest.get('documents', []))} 件を記録済み)")
    else:
        manifest = {"users": [], "groups": [], "folders": {}, "documents": []}
    manifest.setdefault("documents", [])
    manifest.update({
        "base_url": args.base_url,
        "repository": args.repository,
        "updated_at": datetime.now(timezone.utc).isoformat(),
    })
    # users / groups は宣言から毎回上書き (重複防止)
    manifest["users"] = []
    manifest["groups"] = []
    manifest.setdefault("folders", {})

    def save_manifest() -> None:
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    try:
        setup_users(client, manifest)
        setup_groups(client, manifest)
        category_folders = setup_folders(client, manifest)
        save_manifest()
        if not args.skip_docs:
            upload_documents(client, manifest, category_folders)
    finally:
        save_manifest()
        log(f"manifest 書き込み: {manifest_path}")

    if args.wait_rag and rag_enabled:
        wait_for_rag(client, args.rag_timeout)

    log("")
    log("=== セットアップ完了 ===")
    log(f"  ユーザ: {len(manifest['users'])} 名 (パスワード: {DEFAULT_PASSWORD})")
    log(f"  グループ: {len(manifest['groups'])}")
    log(f"  フォルダ: {len(manifest['folders'])} (トップ: {TOP_FOLDER_NAME})")
    log(f"  文書: {len(manifest['documents'])} 件")
    log("次のステップ: python3 mcp_scenarios.py でユーザ別 MCP 応答の違いを確認")
    return 0


if __name__ == "__main__":
    sys.exit(main())
