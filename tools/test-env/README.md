# テスト環境セットアップツール (test-env)

権限の多様性 × ベクトル検索 (RAG) × MCP を一度に検証・デモできるテスト環境を
NemakiWare に投入するツール群。**「同じ質問でも、ユーザによって MCP の応答が
変わる」** ことを印象付けるためのシナリオランナーを含む。

## 投入されるもの

架空の会社「ヒナタ産業株式会社」の階層組織:

- **ユーザ 15 名** (パスワードは全員 `Pass1234`)
- **ネストグループ 13** — 課 (7) → 本部 (3) → 全社、経営会議、部門横断の
  「新製品Xプロジェクト」
- **フォルダ 30** — トップフォルダ `組織共有文書` 配下に文書種類毎のフォルダ
- **Office 文書 300 件** — docx / xlsx / pptx / pdf (テンプレート生成、決定論的)

### 組織図

```
経営会議 (te-mgmt): 大塚社長, 工藤, 平田, 永井
├── 営業本部 (te-div-sales): 工藤本部長
│   ├── 東日本営業課 (te-sec-sales-east): 森課長, 浅田*
│   └── 西日本営業課 (te-sec-sales-west): 上田課長, 星野*
├── 技術本部 (te-div-eng): 平田本部長
│   ├── 開発課 (te-sec-dev): 福田課長, 小川*, 西田*
│   └── インフラ課 (te-sec-infra): 宮田
└── 管理本部 (te-div-corp): 永井本部長
    ├── 人事課 (te-sec-hr): 清水
    ├── 経理課 (te-sec-finance): 岡本
    └── 法務課 (te-sec-legal): 馬場*

* = 新製品Xプロジェクト (te-proj-x) 兼務。全社員 = te-all (本部+経営をネスト)
```

### ACL パターン (エリア別)

| エリア | 読み書き | 読み取りのみ | 見えない人の例 |
|---|---|---|---|
| 全社共有 | 人事課 | 全社員 | — |
| 経営企画 | 経営会議 | — | 課長以下全員 |
| 営業本部 | 営業本部 | 経営会議 | 技術・管理の各課 |
| 技術本部 | 技術本部 | 経営会議 | 営業・管理の各課 |
| 管理本部/人事 | 人事課 | 経営会議 | 他部門 |
| 管理本部/人事/**給与** | **人事課のみ** | — | **経営会議すら不可** |
| 管理本部/経理・法務 | 各課 | 経営会議 | 他部門 |
| 機密プロジェクトX | プロジェクトメンバーのみ | — | **社長含む非メンバー全員** |

機密プロジェクトX の文書には新製品のコードネーム **Aurora** が登場する
(取締役会議事録にも言及があるため、経営会議メンバーは片鱗だけ見える)。

## 前提

1. NemakiWare スタックが **RAG profile 付き**で起動していること:

   ```bash
   cd docker
   export COUCHDB_USER=admin COUCHDB_PASSWORD=password
   docker compose -f docker-compose-simple.yml --profile rag up -d
   ```

   TEI (embedding) コンテナと `rag.enabled=true` が必要。RAG なしでも投入自体は
   可能だが、ベクトル検索シナリオは動かない。

2. Python 3.9+ と依存ライブラリ:

   ```bash
   pip3 install -r requirements.txt
   ```

3. PDF 生成には日本語フォント (macOS のヒラギノ / Linux の Noto CJK) が必要。
   見つからない場合は該当文書を自動的に docx で生成する。

## 使い方

```bash
cd tools/test-env

# 1. 投入 (約5-10分) + RAG インデックス追い付き待ち
python3 setup_test_env.py --wait-rag

# 2. ユーザ別 MCP 応答比較シナリオ (5本) を実行
python3 mcp_scenarios.py --report report.md

# 特定シナリオだけ / REST API で機械可読に
python3 mcp_scenarios.py --scenario aurora
python3 mcp_scenarios.py --engine rest

# 3. 片付け (このツールが作ったものだけ削除)
python3 teardown_test_env.py
```

接続先の指定: `--base-url http://host:8080 --repository bedroom --admin-user admin --admin-password admin`

再実行は安全: ユーザ/グループ/フォルダは既存を再利用し、文書は
`manifest.json` 記録済み・同名のものをスキップする。

## MCP シナリオ

| key | クエリ | 見どころ |
|---|---|---|
| `aurora` | 新製品Auroraの価格戦略 | プロジェクトメンバーだけが価格・仕様に到達。社長は議事録の言及のみ |
| `salary` | 賞与と給与改定 | 人事課だけが給与テーブル (厳秘) に到達。本部長でも見えない |
| `sales` | 顧客提案と見積の状況 | 営業と経営には見えるが開発課長には見えない |
| `incident` | システム障害の状況 | 技術本部には見えるが経理・営業には見えない |
| `board` | 取締役会の決定事項 | 経営会議メンバー限定 |

各シナリオは MCP `nemakiware_login` → `nemakiware_rag_search` をペルソナ毎に
実行し、ヒット文書と類似度を並べて表示する。裏の仕組み: RAG インデックスの
`readers` トークン (`user:{repo}:{id}` / `group:{repo}:{id}`) に対して検索時に
ユーザの所属グループ (ネスト展開済み) でフィルタされる。

## ファイル構成

| ファイル | 役割 |
|---|---|
| `org_model.py` | ユーザ・グループ・フォルダ・ACL の宣言的定義 (単一情報源) |
| `doc_factory.py` | 22 カテゴリ × 300 文書の Office 文書ジェネレータ |
| `nemaki_client.py` | REST / CMIS Browser Binding / MCP の薄いクライアント |
| `setup_test_env.py` | 投入本体。`manifest.json` に作成物を記録 |
| `teardown_test_env.py` | manifest ベースの削除 (無ければ宣言から特定) |
| `mcp_scenarios.py` | ユーザ別 MCP 応答比較シナリオランナー |

## 注意事項

- 既存の `setup-test-data.sh` (営業部/技術部/… + tanaka 等 8 ユーザ) とは
  独立。ID プレフィックス `te-`、別トップフォルダで共存する。
- **グループの推移的メンバー展開**: 本ツールは各グループの `users` に
  ネスト展開済みの全メンバーを投入する (`groups` のネスト構造は組織構造の
  表現として保持)。かつて NemakiWare の実効 ACL 評価がネストグループを
  解決できないバグがあった名残だが (2026-07-07 に本体修正済み:
  `UserGroupDaoDelegate` の view キー配列化 + `ACLExpander` のネスト走査)、
  未修正の環境でも動くよう展開投入は維持している。
- **ACL の再適用は冪等化済み**: ACL 変更時の非同期 RAG 更新が検索チャンクを
  消すバグがあった (2026-07-07 に本体修正済み: `updateDocumentACL` の
  ブロック全体再構築)。未修正環境でチャンクが消えた場合は
  `POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex` で復旧。
- pptx の RAG 対象可否は `rag.supported.mimetypes` 次第
  (docker/core/nemakiware.properties は pptx を含む)。シナリオが参照する
  内容は docx / xlsx / pdf 側にも存在させてある。
- teardown で deleteTree された文書はアーカイブに移動する。完全削除は
  管理 UI のアーカイブ管理から行う。
- RAG 検索にはレート制限 (既定 2 req/s) があるため、シナリオランナーは
  呼び出し間隔を空けて実行する。
- リポジトリルートへの誤操作防止として、クライアントは root に対する
  `set_acl` / `deleteTree` を拒否する安全ガードを持つ。
