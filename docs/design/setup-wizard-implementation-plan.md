# NemakiWare セットアップウィザード実装計画

最終更新: 2026-03-04  
対象ブランチ想定: `release/3.1.0-RC7` 以降

## 1. 目的

初回導入時の設定導線を整理し、以下を実現する。

- CouchDB 接続状態に応じた起動フロー分岐
- 既存リポジトリの概要可視化と、必要時のみ確認付きマイグレーション
- 認証方式（パスワード / Microsoft / Google）を初回で選択・設定
- `admin` 強化（初期パスワード変更 + passkey 登録）
- ベクトル検索（無効 / TEI / Bedrock）の有効化と接続テスト

## 2. 前提と方針

### 2.1 起動時の基本方針

- **設定ファイル・環境変数が有効で CouchDB 接続成功**:
  - 通常起動（Normal Mode）
  - 起動時自動パッチ適用を許容
  - ウィザード上では「新バージョン相当（migration 不要）」を表示
- **設定未完了または接続失敗**:
  - セットアップ起動（Setup Mode）
  - ウィザードで接続確認後、必要時のみ確認付きマイグレーションを実行

### 2.2 設定値の優先順位

読み取り順は既存仕様に合わせる。

1. JVM system property
2. environment variable
3. CouchDB dynamic config
4. properties ファイル

## 3. スコープ

### 3.1 実装対象

- 初回セットアップウィザード（UI）
- Setup Mode と Normal Mode の起動分岐
- CouchDB 接続 probe API とリポジトリ概要 API
- 確認付きマイグレーション API
- 認証方式設定 API（password / Microsoft / Google）
- admin passkey 登録導線（WebAuthn）
- ベクトル検索設定 API（disabled / TEI / Bedrock）

### 3.2 非スコープ

- 既存 CMIS パッチロジックの全面書き換え
- 全認証機能の仕様変更（既存 login API の互換性破壊）
- 既存 Docker Compose の全面再設計

## 4. 現状ギャップ（実装上の課題）

- `/rest/all/repositories` がハードコード応答で、実環境概要を返せない
- `/api/v1/.../health` の CouchDB チェックがプレースホルダ
- 設定永続化・読み出し経路の一部に暫定実装が残っており、動的設定の実効性確認が必要
- Setup Mode 時に起動パッチを抑止するガードが未実装

## 5. アーキテクチャ設計

## 5.1 起動モード

- `Normal Mode`:
  - Core 通常起動
  - パッチは既存起動シーケンスで適用
- `Setup Mode`:
  - UI はセットアップ画面中心で起動
  - パッチ適用は抑止
  - ユーザー確定後に migration endpoint を叩く

モード判定は `StartupProbeService`（新規）で行う。

## 5.2 状態モデル

- `DB_UNREACHABLE`: 接続不可
- `DB_CONNECTED_EMPTY`: 接続可、リポジトリ実体ほぼなし
- `DB_CONNECTED_CURRENT`: 接続可、新版相当
- `DB_CONNECTED_LEGACY`: 接続可、移行が必要

## 6. ウィザード UX 設計

## 6.1 ステップ構成

1. **CouchDB 接続**
   - URL / 認証入力
   - 接続テスト
2. **リポジトリ概要確認**
   - 既存リポジトリ一覧（ID / 名称 / doc 数 / ルート / ビュー整備状況 / 判定）
   - `legacy` がある場合のみ確認付き migration
3. **認証設定**
   - 有効化方式チェック: Password / Microsoft / Google
   - Microsoft/Google の接続情報入力と接続テスト
4. **admin 強化**
   - admin 初期パスワード変更
   - admin passkey 登録（WebAuthn）
5. **ベクトル検索設定**
   - disabled / TEI / Bedrock
   - TEI or Bedrock 接続テスト
6. **確認・適用**
   - 保存対象一覧
   - 適用後のヘルス再確認

## 6.2 ロックアウト防止ルール

- Password を無効化する場合:
  - admin passkey が 1 件以上登録済み
  - かつ Microsoft または Google いずれかの接続テスト成功
- 上記未達時は保存不可（UI + API 両方でバリデーション）

## 7. API 設計（新規）

## 7.1 セットアップ共通

- `GET /api/v1/setup/state`
  - 起動モード、現在ステップ、必要作業を返す
- `POST /api/v1/setup/apply`
  - 設定一括適用

## 7.2 CouchDB

- `POST /api/v1/setup/couchdb/test-connection`
  - CouchDB 到達性確認
- `POST /api/v1/setup/couchdb/probe`
  - リポジトリ概要と `current/legacy/empty` 判定
- `POST /api/v1/setup/couchdb/migrate`
  - `legacy` 対象に確認付き migration 実行

## 7.3 認証

- `GET /api/v1/setup/auth/state`
- `POST /api/v1/setup/auth/test-oidc`
- `POST /api/v1/setup/auth/admin/passkey/register/options`
- `POST /api/v1/setup/auth/admin/passkey/register/verify`
- `POST /api/v1/setup/auth/apply`

## 7.4 ベクトル検索

- `GET /api/v1/setup/vector/state`
- `POST /api/v1/setup/vector/test-connection`
- `POST /api/v1/setup/vector/apply`

## 8. 永続化設計

## 8.1 保存先

- 非機密:
  - 既存 dynamic config（CouchDB config doc）を基本利用
- 機密（OIDC client secret, AWS secret など）:
  - 暗号化保存（AES-GCM + 環境鍵）
  - 平文ログ出力禁止

## 8.2 権限方針

- コンテナ外部設定ファイルを使う場合:
  - ファイル権限 `600`
  - 実行ユーザー所有
- classpath 同梱 properties は read-only 運用

## 9. マイグレーション制御

## 9.1 Normal Mode

- 既存の自動パッチ適用を維持
- ウィザード上は `current` 表示のみ

## 9.2 Setup Mode

- 起動シーケンスで patch 実行を抑止
- `probe` 結果が `legacy` の場合のみ、明示同意後に `migrate` 実行

## 10. 実装フェーズ

### Phase 1: バックエンド基盤

- StartupProbeService
- setup API 一式（state / probe / migrate / auth / vector）
- Setup Mode ガード

### Phase 2: UI ウィザード

- ステップ UI 実装
- 接続テスト結果表示
- ロックアウト防止バリデーション

### Phase 3: 統合・運用

- ログ/監査追加
- エラーメッセージ整備
- ドキュメント更新

## 11. テスト計画

## 11.1 Backend

- 起動モード分岐テスト
- `probe` の `current/legacy/empty` 判定テスト
- `migrate` 確認フローの異常系
- 認証ロックアウト防止バリデーション
- vector 接続テスト（TEI/Bedrock/disabled）

## 11.2 E2E（Playwright）

- 正常系:
  - 正しい CouchDB 設定で Normal Mode 表示
  - Setup Mode で接続成功 -> `current` 判定 -> migration 省略
  - Setup Mode で `legacy` 判定 -> 確認後 migration 成功
  - 認証方式選択 + admin passkey 登録
  - vector disabled / TEI / Bedrock
- 異常系:
  - CouchDB 接続失敗
  - OIDC テスト失敗
  - Password 無効化条件未達

## 12. 受け入れ条件（DoD）

- 設定済み環境で自動起動・自動パッチ適用が継続動作
- 未設定環境で Setup Mode が起動し、接続確認後にのみ migration 実行可能
- 既存リポジトリ概要を UI で確認できる
- 認証方式選択と admin passkey 設定がウィザードで完結
- vector 機能の有効/無効/接続テストが可能
- 新規追加 API と E2E が CI で安定通過

