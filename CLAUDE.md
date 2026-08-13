# CLAUDE.md

日本語で対話してください。

このファイルは**全セッションで常時ロード**されます。書いてよいのは
「ファイルシステムを読んでも分からない落とし穴」だけです。
手順・履歴・設計の詳細は下記の skill / docs に置き、必要になったときに読みます。

---

## プロジェクト概要

CMIS 1.1 準拠のオープンソース ECM。技術スタックは `pom.xml` / `package.json`、
モジュール構成は `ls` を見てください。

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

- **OpenCMIS は `2.0.0-RC2-nemakiware`** (自己ビルドの Jakarta EE 対応版)。
  `1.2.0-SNAPSHOT` は不安定につき**禁止**。1.1.0-nemakiware からの移行で踏んだ罠:
  - クエリ木は ANTLR4 になり、`parseStatement()` の戻りは `CmisTree`。
    **ルートが SELECT ノードそのもの** (1.1.0 では nil ノードが包んでいた)。
    子だけを見る抽出は WHERE を取りこぼし、Solr 側が `*:*` に落ちて**全件返す** —
    parse は成功しログも出ないので、TCK でしか気づけません。
  - **HttpComponents 5 はファミリで動く**。client-bindings が httpclient5 5.5.1 を
    持ち込むので core5 も 5.3.6 に揃える。ずれると Tomcat 起動時に
    `NoSuchMethodError` でアプリが上がりません (enforcer で検出)。
  - Browser binding の multipart は `MultipartReplayRequestWrapper` が再生します。
    servlet がルーティングのため body を先に読む必要があり、1.1.0-nemakiware は
    フォーク側パッチで救っていましたが、2.0 系にそれはありません。
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
- **`sso.` / `oidc.` / `saml.` / `cloud.auth.` / `cloud.drive.` は `-D` も ENV も効きません。**
  admin-managed dynamic key で、CouchDB `nemaki_conf` の保存値が system property より
  **先に**読まれます (`PropertyManager.isAdminManagedDynamicKey`)。setup ウィザードが
  書いた `config_sso_oidc_enabled = "false"` が残っていると、`-Dsso.oidc.enabled=true` を
  渡しても `/core/rest/auth/config` は false のままです。保存値は JSON のプロパティ名では
  なく `key` フィールドに入るので grep でも見落とします。手順は
  [`.claude/skills/testing/SKILL.md`](.claude/skills/testing/SKILL.md)。

### v3.3.0 へのアップグレード

- **全 CMIS + RAG 再索引はセキュリティ必須**(任意ではない)。Solr 10 移行で
  どのみち再索引が要るので追加コストはありませんが、**公開前に必ず実行**してください。

  ```
  POST /api/v1/cmis/repositories/{repo}/search-engine/reindex
  POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex   # RAG 有効時
  ```

  **接続リーク (F3) は塞ぎました。数十万規模だけ未実測です。** 修正前は 2,510 文書の
  再索引で ESTABLISHED が 3 → 1,289 に増え、完了後も約 90 秒張り付き、スイート 1 周で
  約 5,000 件の `A connection to http://couchdb:5984/ was leaked` が出ていました。
  2026-08-13 に **97,693 オブジェクトまで実測**し (26,416 の点も同様)、ピーク 5・
  アイドル時のピーク 3 と 2 しか違わず、leak 警告 0 件を確認しています
  (**再索引は完走させておらず**、約 1.4 時間・370 サンプルの部分観測)。
  **20 万以上は未実測** (投入 9 時間・再索引 15 時間超のため打ち切り) なので、
  その規模なら `ss -s` 等で監視し、必要ならリポジトリ単位や時間帯で分割実行
  してください。**なお 10 万規模では全再索引そのものが 10 時間級**になります
  (レートが規模とともに 10.3 → 2.6 文書/秒に低下。F3 とは別課題)。詳細は
  [`docs/design/v3.3-release-blockers.md`](docs/design/v3.3-release-blockers.md) の F3。

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

## ACL-epoch fencing (常時有効・flag なし)

ACL-in-Solr の恒久収束のためのリポジトリ単位の単調増加 ACL epoch。**applyAcl / move /
reconcile re-drive は必ず epoch fence を通ります**。切替スイッチはありません。

デプロイごとに 2 つだけ外せない点があります。

- **初期 epoch stamp は全再索引の「後」**。順序が逆だと再索引が stamp を捨てます。
- **生カウントではなく `verdict` を読む**。`COMPLETE` / `COMPLETE_EXCEPT_ORPHANS`
  のみが完了で、`EMPTY_INDEX` は**再索引がまだ**という意味です。

手順・エンドポイント・実走で判明した落とし穴は
[`.claude/skills/acl-epoch/SKILL.md`](.claude/skills/acl-epoch/SKILL.md)、
設計の正典は [`docs/design/acl-epoch-fencing.md`](docs/design/acl-epoch-fencing.md)。

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
| ビルドが「ソースと無関係に」落ちるとき | [`docs/development/troubleshooting-build.md`](docs/development/troubleshooting-build.md) |
| 開発者向け詳細ガイド | [`AGENTS.md`](AGENTS.md) |
| アーキテクチャ | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| multi-replica 運用 | [`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md) |
| クラウド統合 | [`docs/CLOUD_INTEGRATION.md`](docs/CLOUD_INTEGRATION.md) |
| MCP サーバ | [`docs/MCP-SERVER.md`](docs/MCP-SERVER.md) |
| Webhook 機能設計 | [`docs/design/webhook-feature-proposal.md`](docs/design/webhook-feature-proposal.md) |
