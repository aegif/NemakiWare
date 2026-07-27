# CLAUDE.md

日本語で対話してください。

このファイルは**全セッションで常時ロード**されます。書いてよいのは
「ファイルシステムを読んでも分からない落とし穴」だけです。
手順・履歴・設計の詳細は下記の skill / docs に置き、必要になったときに読みます。

---

## プロジェクト概要

CMIS 1.1 準拠のオープンソース ECM。

| | |
|---|---|
| Backend | Spring Framework 7 / Apache Chemistry OpenCMIS / Jakarta EE 11 |
| DB / 検索 | CouchDB 3.x / Apache Solr 10.x |
| UI | React 19 + TypeScript + Vite 7 + Ant Design 6 |
| Server | Tomcat 11.0+ (Virtual Threads 有効) / **Java 21 必須** |

モジュール: `core/` (CMIS サーバ WAR) · `core/src/main/webapp/ui/` (React SPA) ·
`solr/` · `common/`

リポジトリ: **`bedroom`** (主要・テスト用) / **`canopy`** (初期作成・UI からは非表示、
それ以外は通常リポジトリと同等)

認証情報: NemakiWare `admin:admin` / CouchDB `admin:password`

---

## 落とし穴 (ここが本題)

### デプロイ

- **`docker compose restart` は使用禁止。** WAR はイメージビルド時にコピーされるため、
  `restart` では古い WAR のまま動作します。必ず `--build --force-recreate`。
- **WAR は `docker/core/core.war` からコピーされます。** `mvn package` しただけでは
  反映されません。`cp core/target/core.war docker/core/core.war` を挟んでから
  `--build --force-recreate`。忘れると「ビルドしたのに古い WAR が動いている」状態になり、
  TCK が旧コードを叩いていることに気づけません。
- **Solr の schema は名前付きボリューム側が正**です (`SOLR_HOME=/var/solr/data`,
  `ClassicIndexSchemaFactory`)。イメージ内の `schema.xml` は**新規ボリュームの種にしか
  ならず**、Schema API も `schema is not editable` で拒否します。既存ボリュームに
  フィールドを足すには `{SOLR_HOME}/nemaki/conf/schema.xml` を直接編集して
  `?action=RELOAD&core=nemaki`。足りないと全 document 書込みが
  `400 unknown field ...` で落ち、**CMIS 操作は成功したまま索引だけ止まります**
  (症状は「検索が 0 件」)。
- **全 compose で `COUCHDB_USER` / `COUCHDB_PASSWORD` が必須** (RC13 以降 `${VAR:?}` で
  fail-fast)。LDAP / Keycloak profile では `LDAP_ADMIN_PASSWORD` / `LDAP_CONFIG_PASSWORD` も。

### 依存・ランタイム

- **OpenCMIS は `1.1.0-nemakiware` 固定** (自己ビルドの Jakarta EE 対応版)。
  `1.2.0-SNAPSHOT` は不安定につき**禁止**。
- **Virtual Threads**: ThreadPoolExecutor の構造 (キューサイズ・CallerRunsPolicy 等) は
  バックプレッシャー維持のため**変更不可**。ThreadFactory のみ
  `Thread.ofVirtual().name(...).factory()` に統一。`ScheduledExecutorService`
  (McpAuthenticationHandler) は VT 非対応のためプラットフォームスレッドのまま。
- `/lib/jakarta-converted/` 内カスタム JAR による Maven systemPath 警告は**想定内**。

### データ・認証

- **ACL キャッシュは `removeCmisAndContentCache()` で両方同時にクリア**すること
  (片方だけだと stale を読みます)。
- **テストユーザーのパスワードは BCrypt ハッシュ必須** (平文は拒否されます)。
- cmislib 等は `/atom` にリポジトリ ID 未指定でサービス文書を取りに来ます。
  `nemakiware.properties` に **`cmis.server.default.repository=bedroom`** が必要
  (未設定なら `repositories.yml` の定義順で先頭、無効値は WARN + フォールバック)。

### v3.3.0 へのアップグレード

- **全 CMIS + RAG 再索引はセキュリティ必須**(任意ではない)。Solr 10 移行で
  どのみち再索引が要るので追加コストはありませんが、**公開前に必ず実行**してください。

  ```
  POST /api/v1/cmis/repositories/{repo}/search-engine/reindex
  POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex   # RAG 有効時
  ```

  理由と影響範囲: [`docs/history/development-log.md`](docs/history/development-log.md) の
  「ACL-in-Solr」節。
- 非 root TEI 採用時は root 所有の `tei_cache` volume を再作成 (初回モデル再 DL)。
- dev/eval overlay が 127.0.0.1 bind になりホスト外から到達不可 (常設デモは要確認)。

---

## CSRF (REST API を叩くとき)

`/core/rest/repo/...` (Jersey) と `/core/api/v1/...` (Spring MVC) の
state-changing request (POST/PUT/DELETE) は CSRF 検証されます。
**Basic auth はバイパスしません** (ブラウザが realm 単位で自動付与する
ambient credential のため)。

```bash
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/rest/repo/bedroom/..."
```

```python
requests.post(url, auth=(user, pw), headers={"X-Requested-With": "XMLHttpRequest"})
```

他のバイパス条件と Browser Binding の軽量ポリシーは
[`.claude/skills/cmis-api/SKILL.md`](.claude/skills/cmis-api/SKILL.md)。

---

## 進行中: ACL-epoch fencing (本番配線 NO-GO)

ACL-in-Solr の恒久収束のため、リポジトリ単位の単調増加 ACL epoch を実装中。
`AclEpochIndexWriter.write()` は**まだどの ACL write path にも接続していません**
(standalone bean / production caller ゼロ / scheduler・init・cron なし)。

**配線ゲート 4 項目はすべて閉鎖済み** (増分 7 / 8 / 9 / 10、principal tri-state は 5T)。
**それでも配線自体は未着手・NO-GO** です。`write()` を ACL write path に載せる作業は
独立した増分で、設計・レビュー・明示承認を経るまで着手しません。

**デプロイごとの運用義務** (ゲート 2 は「1 回閉じて終わり」ではありません):
全再索引の**後で**、リポジトリごとに初期 epoch stamp を実行してください。順序が逆だと
再索引が stamp を捨てます (content writer は既存 ACL group を preserve するので、
作り直した索引には preserve するものが無い)。

```
POST /api/v1/admin/acl-epoch/migration/{repositoryId}      # 実行
GET  /api/v1/admin/acl-epoch/migration/{repositoryId}      # verdict を確認
```

**生カウントではなく `verdict` を読んでください。** `COMPLETE` か
`COMPLETE_EXCEPT_ORPHANS` のみが完了です (後者の残数は CouchDB に実体が無い孤児 Solr 文書で、
stamp 不能かつ ACL write の対象にもならないため配線を妨げません)。
`EMPTY_INDEX` は**再索引がまだ**という意味で、完了ではありません。

設計と実装進捗の正典は
[`docs/design/acl-epoch-fencing.md`](docs/design/acl-epoch-fencing.md)。
作業の背景は [`.claude/skills/acl-epoch/SKILL.md`](.claude/skills/acl-epoch/SKILL.md)。

---

## どこを読むか

| 目的 | 参照先 |
|---|---|
| ビルド・デプロイ手順 | `.claude/skills/build-deploy/` |
| テスト実行 (QA / TCK / Playwright) | `.claude/skills/testing/` |
| CMIS API の叩き方・CSRF 詳細 | `.claude/skills/cmis-api/` |
| 外部取込コネクタの skip ルール | `.claude/skills/external-ingest/` |
| ACL-epoch の現在地 | `.claude/skills/acl-epoch/` |
| 利用者向け変更履歴 | [`RELEASE_NOTES.md`](RELEASE_NOTES.md) |
| per-commit 開発ログ (約 4200 行) | [`docs/history/development-log.md`](docs/history/development-log.md) |
| 開発者向け詳細ガイド | [`AGENTS.md`](AGENTS.md) |
| アーキテクチャ | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| multi-replica 運用 | [`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md) |
| クラウド統合 | [`docs/CLOUD_INTEGRATION.md`](docs/CLOUD_INTEGRATION.md) |
| MCP サーバ | [`docs/MCP-SERVER.md`](docs/MCP-SERVER.md) |
| Webhook 機能設計 | [`docs/design/webhook-feature-proposal.md`](docs/design/webhook-feature-proposal.md) |
