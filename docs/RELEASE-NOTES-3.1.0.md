# NemakiWare 3.1.0 Release Notes

**Release Date:** 2026-03-16

NemakiWare 3.1.0 は、2.4.0 以降の最大のメジャーアップデートです。技術スタックの全面刷新、エンタープライズ認証の強化、モダンUI への移行、そして大規模なパフォーマンス・セキュリティ改善を含みます。

---

## ハイライト

### プラットフォーム刷新
- **Java 21** (Virtual Threads 対応) へ移行
- **Jakarta EE 11** (javax → jakarta 名前空間) へ完全移行
- **Spring Framework 7** へアップグレード
- **Tomcat 11** (Jakarta Servlet 6.1) を採用
- **Apache Chemistry OpenCMIS 1.1.0-nemakiware** (Jakarta EE 対応自己ビルド版)

### モダン UI
- **React 19 + TypeScript + Vite 7 + Ant Design 5** による SPA を新規構築
- 旧 Ruby on Rails クライアント (NemakiShare) を廃止
- 日本語・英語の **多言語対応** (i18n)
- レスポンシブデザイン

### エンタープライズ認証
- **SAML 2.0** 認証 (POST binding ACS、SLO LogoutRequest、署名検証)
- **OIDC** 認証 (Google / Microsoft)
- **WebAuthn** パスキー認証
- **Setup Wizard** による初期設定 GUI

### クラウド統合
- **Google Workspace** / **Microsoft Entra ID** ディレクトリ同期
- **Google Drive** / **OneDrive** Cloud Drive 連携

---

## 新機能

### CMIS 準拠強化
- **ContentChanges API** 実装 (変更ログトークンベースの増分同期)
- **BulkUpdateProperties** メソッド実装
- CMIS 1.1 Browser Binding / Atom Binding / Web Services Binding 対応
- セカンダリタイプ完全対応
- タイプ定義の CRUD (作成・更新・削除) 対応

### コンテンツ管理
- **インポート / エクスポート** 機能改善 (同名上書き、リレーションシップ対応、ACL 付きエクスポート、ID 読替)
- **アーカイブ管理** 強化 (全文検索、一括操作、アーカイブからのダウンロード)
- **Webhook** 機能 (CMIS 権限チェック、CHILD_BATCH 配送、配信ログ永続化)
- **サーバーサイドページネーション** による大量文書対応

### AI・検索
- **RAG セマンティック検索** (TEI / Amazon Bedrock 埋め込み対応)
- **MCP (Model Context Protocol) サーバー** (AI エージェント統合)
- **Solr 多言語検索** (text_ja + text_en デュアルインデックス)

### Virtual Threads
- Tomcat の全 HTTP リクエストを **Virtual Thread** で処理
- アプリケーション内 ThreadFactory も Virtual Thread に統一
- 同時接続数の大幅改善

---

## パフォーマンス改善
- `deleteTree` 並列化による大量ファイル削除の高速化
- `getChildByName` O(1) CouchDB ビュー最適化
- `getLatestChange()` 全件ロード → limit=1 最適化 (OOM 解消)
- グループ更新の差分計算方式への変更 (全グループ走査の排除)
- ZIP インポートのストリーミング処理化
- N+1 クエリ 5 箇所の解消

---

## セキュリティ修正
- SAML 署名ラッピング攻撃対策 (Reference URI DOM 解決 + duplicate-ID 防御)
- SAML DEFLATE DoS 防止 (10MB 上限)
- XXE 防御の強化
- エクスポート時の ACL リーク防止 (CAN_GET_ACL 権限チェック)
- Webhook REST API の CMIS 権限チェック移行
- ACP インポートのサイズ上限適用
- クラウド専用ユーザーのパスワード / パスキー制限
- PDF.js CVE-2024-4367 対応 (react-pdf 10.0.1)
- npm 脆弱性 0 件達成 (dompurify 3.3.2、immutable 3.8.3)
- Dependabot アラート全件対応

---

## 破壊的変更

### 動作環境
- **Java 21 必須** (Java 8/11/17 はサポート対象外)
- **Tomcat 11** 必須 (Tomcat 7/8/9 はサポート対象外)
- **CouchDB 3.x** 必須

### 廃止
- NemakiShare (Ruby on Rails クライアント) を廃止 → React SPA に移行
- レガシーモジュール (setup, action, util 等) の削除
- javax 名前空間のクラス群 → jakarta に完全移行
- SLF4J 1.7 → 2.0 に統一

### API
- CMIS Atom / Browser Binding のエンドポイントパスに変更はありません
- REST API の権限モデルが一部変更されています (Webhook API: admin 限定 → CMIS 権限チェック)

---

## アップグレードガイド

### 2.4.0 からのアップグレード

1. **Java 21** をインストール
2. **Tomcat 11** に切り替え
3. **CouchDB 3.x** にアップグレード (必要に応じて)
4. `core.war` を Tomcat の `webapps/` にデプロイ
5. **Setup Wizard** (初回起動時に表示) で初期設定を実施
6. Solr コアの再構築 (スキーマ変更あり)

> **注意**: 2.4.0 の CouchDB データは基本的に互換性があり、ビュー構造の変更は起動時のパッチ機構で
> 適用されます。**ただし実際の 2.4 データベースでの移行は検証されていません** — パッチ履歴を持たない
> データベースには全パッチが適用される、という理屈は通りますが、3.3.0 で入れたパッチのゲート
> (view カナリア等) は 3.x 形状のデータベースでしか確認していません。「初回起動で全部直る」とは
> 読まないでください。

### 3.1.0 より後 — 3.3.0 へ上げる場合に増えた義務

**手順の正典は [`docs/operations/v3.3.0-upgrade-runbook.md`](operations/v3.3.0-upgrade-runbook.md)。**
上の 6 ステップに加えて、次が必須になります。

1. **CouchDB 3.3 以上。** 3.3.0 は起動時にバージョンを検査し、**満たさなければ起動しません**
   (読めない場合も拒否)。2.4 世代の構成は CouchDB 1.6 / 2.x のことが多いので、
   **NemakiWare を上げる前に CouchDB を上げてください**。
2. **全 CMIS 再索引 + RAG 再索引** (RAG 有効時)。任意ではなくセキュリティ必須です。
   10 万文書規模で 10 時間級になります。
3. **初期 ACL-epoch stamp を、再索引の「後」に。** 順序が逆だと再索引が stamp を捨てます。
4. **REST クライアントの CSRF 対応** (下記)。CMIS クライアントは影響を受けません。

### CSRF — 2.4 世代の REST クライアントには破壊的変更です

| 経路 | 3.3.0 での扱い | 2.4 のクライアントは |
|---|---|---|
| `/atom/*` (CMIS AtomPub) | **CSRF 検証の対象外** | **そのまま動く** |
| `/browser/*` (CMIS Browser Binding) | 軽量ポリシーのみ。`Sec-Fetch-Site: cross-site` を拒否し、`Origin` があれば same-origin を要求。**どちらのヘッダも無いクライアントは通す** | **そのまま動く** (cmislib・TCK・スクリプト) |
| `/core/rest/**` (Jersey) | state-changing (POST/PUT/DELETE) は CSRF 検証。**Basic auth はバイパスしません** | **拒否されます** |
| `/core/api/v1/**` (Spring MVC) | 同上 | **拒否されます** |

**対処**: NemakiWare 独自 REST を叩くクライアントに `X-Requested-With: XMLHttpRequest` を付ける
(または Bearer / `AUTH_TOKEN` / `X-API-Key` を使う)。Basic auth はブラウザが realm 単位で
自動付与する ambient credential なので、意図的にバイパス条件から外してあります。

---

## 既知の問題
- Maven 依存: netty 4.1.97 (odata-server-core 経由、CVE-2025-24970/58056) — 直接利用なし、影響軽微
- Maven 依存: logback 1.4.14 (CVE-2025-11226/CVE-2026-1225) — アップストリーム更新待ち
- FilingTestGroup (Multifiling/Unfiling) は NemakiWare が非対応のためスキップ

---

## テスト状況
- CMIS TCK: 38/38 PASS
- QA 統合テスト: 94/94 PASS
- Playwright E2E テスト: 827+ passed / 0 failed
- ユニットテスト: 全件 PASS

---

## 謝辞

このリリースは多くの貢献と長期にわたる開発の成果です。CMIS コミュニティおよびすべてのコントリビューターに感謝いたします。

---
---

# NemakiWare 3.1.0 Release Notes (English)

**Release Date:** 2026-03-16

NemakiWare 3.1.0 is the largest major update since 2.4.0. It includes a complete technology stack modernization, enterprise authentication enhancements, a modern UI rebuild, and extensive performance and security improvements.

---

## Highlights

### Platform Modernization
- Migrated to **Java 21** with Virtual Threads support
- Full migration to **Jakarta EE 11** (javax → jakarta namespace)
- Upgraded to **Spring Framework 7**
- Adopted **Tomcat 11** (Jakarta Servlet 6.1)
- **Apache Chemistry OpenCMIS 1.1.0-nemakiware** (custom Jakarta EE build)

### Modern UI
- Brand new SPA built with **React 19 + TypeScript + Vite 7 + Ant Design 5**
- Retired the legacy Ruby on Rails client (NemakiShare)
- **Internationalization** (Japanese / English)
- Responsive design

### Enterprise Authentication
- **SAML 2.0** authentication (POST binding ACS, SLO LogoutRequest, signature verification)
- **OIDC** authentication (Google / Microsoft)
- **WebAuthn** passkey authentication
- **Setup Wizard** for guided initial configuration

### Cloud Integration
- **Google Workspace** / **Microsoft Entra ID** directory sync
- **Google Drive** / **OneDrive** Cloud Drive integration

---

## New Features

### CMIS Compliance
- **ContentChanges API** implementation (incremental sync via change log tokens)
- **BulkUpdateProperties** method implementation
- CMIS 1.1 Browser Binding / Atom Binding / Web Services Binding support
- Full secondary types support
- Type definition CRUD (create, update, delete)

### Content Management
- **Import / Export** improvements (overwrite by name, relationship support, ACL-aware export, ID remapping)
- **Archive management** enhancements (full-text search, bulk operations, archive download)
- **Webhooks** (CMIS permission checks, CHILD_BATCH delivery, persistent delivery logs)
- **Server-side pagination** for handling large document sets

### AI & Search
- **RAG semantic search** (TEI / Amazon Bedrock embeddings)
- **MCP (Model Context Protocol) server** for AI agent integration
- **Solr multi-language search** (text_ja + text_en dual indexing)

### Virtual Threads
- All HTTP requests processed on **Virtual Threads** via Tomcat
- Application-level ThreadFactory unified to Virtual Threads
- Significant improvement in concurrent connection handling

---

## Performance Improvements
- Parallelized `deleteTree` for faster bulk deletion
- O(1) CouchDB view optimization for `getChildByName`
- `getLatestChange()` optimized from full-load to limit=1 (OOM fix)
- Group update changed to diff-based approach (eliminated full-group scan)
- Streaming ZIP import processing
- Resolved 5 N+1 query issues

---

## Security Fixes
- SAML signature wrapping attack mitigation (Reference URI DOM resolution + duplicate-ID defense)
- SAML DEFLATE DoS prevention (10MB limit)
- Enhanced XXE defense
- Export ACL leak prevention (CAN_GET_ACL permission check)
- Webhook REST API migrated to CMIS permission checks
- ACP import size limit enforcement
- Cloud-only user password/passkey restrictions
- PDF.js CVE-2024-4367 addressed (react-pdf 10.0.1)
- Zero npm vulnerabilities (dompurify 3.3.2, immutable 3.8.3)
- All Dependabot alerts resolved

---

## Breaking Changes

### System Requirements
- **Java 21 required** (Java 8/11/17 no longer supported)
- **Tomcat 11 required** (Tomcat 7/8/9 no longer supported)
- **CouchDB 3.x required**

### Deprecations
- NemakiShare (Ruby on Rails client) retired → replaced by React SPA
- Legacy modules removed (setup, action, util, etc.)
- javax namespace classes → fully migrated to jakarta
- SLF4J 1.7 → unified to 2.0

### API
- CMIS Atom/Browser Binding endpoint paths remain unchanged
- Some REST API permission models have changed (Webhook API: admin-only → CMIS permission checks)

---

## Upgrade Guide

### Upgrading from 2.4.0

1. Install **Java 21**
2. Switch to **Tomcat 11**
3. Upgrade to **CouchDB 3.x** (if not already)
4. Deploy `core.war` to Tomcat's `webapps/`
5. Complete initial configuration via the **Setup Wizard** (shown on first launch)
6. Rebuild Solr cores (schema changes required)

> **Note:** CouchDB data from 2.4.0 is generally compatible, but view structure changes will trigger automatic migration on first startup.

---

## Known Issues
- Maven dependency: netty 4.1.97 (via odata-server-core, CVE-2025-24970/58056) — not directly used, minimal impact
- Maven dependency: logback 1.4.14 (CVE-2025-11226/CVE-2026-1225) — awaiting upstream update
- FilingTestGroup (Multifiling/Unfiling) skipped as NemakiWare does not support these capabilities

---

## Test Status
- CMIS TCK: 38/38 PASS
- QA Integration Tests: 94/94 PASS
- Playwright E2E Tests: 827+ passed / 0 failed
- Unit Tests: All PASS

---

## Acknowledgments

This release is the culmination of extensive development and many contributions. We thank the CMIS community and all contributors for their support.
