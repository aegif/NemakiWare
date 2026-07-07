#!/usr/bin/env python3
"""テスト環境の削除: setup_test_env.py が投入したデータだけを片付ける。

削除順序: フォルダツリー (deleteTree) → グループ (親→子) → ユーザ。

manifest.json があればそれを優先し、無ければ org_model の宣言 +
フォルダ名検索で削除対象を特定する (このツールが作るものしか消さない)。

注意: deleteTree された文書は NemakiWare のアーカイブに移る。アーカイブも
完全に空にしたい場合は管理 UI のアーカイブ管理から完全削除すること。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from nemaki_client import NemakiApiError, NemakiClient
from org_model import GROUPS, TOP_FOLDER_NAME, USERS

HERE = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--repository", default="bedroom")
    p.add_argument("--admin-user", default="admin")
    p.add_argument("--admin-password", default="admin")
    p.add_argument("--manifest", default=str(HERE / "manifest.json"))
    p.add_argument("--yes", action="store_true", help="確認プロンプトを省略")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    client = NemakiClient(args.base_url, args.repository,
                          args.admin_user, args.admin_password)
    if not client.check_core():
        print("エラー: NemakiWare core に接続できません")
        return 1

    manifest_path = Path(args.manifest)
    manifest = {}
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        print(f"manifest 使用: {manifest_path}")
    else:
        print("manifest が見つからないため org_model の宣言から削除対象を特定します")

    user_ids = manifest.get("users") or [u.user_id for u in USERS]
    group_ids = manifest.get("groups") or [g.group_id for g in GROUPS]

    top_id = manifest.get("top_folder_id")
    if not top_id:
        top_id = client.get_child_by_name(client.get_root_folder_id(), TOP_FOLDER_NAME)

    print("削除対象:")
    print(f"  フォルダツリー: {TOP_FOLDER_NAME} ({top_id or '見つからず'})")
    print(f"  グループ: {len(group_ids)}")
    print(f"  ユーザ: {len(user_ids)}")
    if not args.yes:
        answer = input("実行しますか? [y/N]: ").strip().lower()
        if answer != "y":
            print("中止しました")
            return 1

    # 1. フォルダツリー (文書ごと削除。文書はアーカイブへ移動)
    if top_id:
        ok = client.delete_tree(top_id)
        print(f"deleteTree {TOP_FOLDER_NAME}: {'OK' if ok else 'FAILED'}")

    # 2. グループ: 親→子の順 (org_model の宣言は子→親なので逆順)
    for gid in reversed(group_ids):
        try:
            ok = client.delete_group(gid)
        except NemakiApiError:
            ok = False
        print(f"delete group {gid}: {'OK' if ok else 'skip (not found?)'}")

    # 3. ユーザ
    for uid in user_ids:
        try:
            ok = client.delete_user(uid)
        except NemakiApiError:
            ok = False
        print(f"delete user {uid}: {'OK' if ok else 'skip (not found?)'}")

    if manifest_path.exists():
        manifest_path.unlink()
        print(f"manifest 削除: {manifest_path}")

    print("teardown 完了。文書の完全削除が必要な場合はアーカイブ管理から実施してください")
    return 0


if __name__ == "__main__":
    sys.exit(main())
