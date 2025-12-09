# NemakiWare UI Testing with Playwright

## 概要

このディレクトリには、NemakiWare React UIの自動テスト環境が含まれています。[Playwright](https://playwright.dev/)を使用してブラウザベースのエンドツーエンドテストを実行します。

## セットアップ

### 前提条件

1. **NemakiWareバックエンドが実行中であること**
   ```bash
   # プロジェクトルートから
   cd docker
   docker compose -f docker-compose-simple.yml up -d
   ```

2. **Node.js 18以上がインストール済み**

### 依存関係のインストール

```bash
# UIディレクトリに移動
cd core/src/main/webapp/ui

# 依存関係のインストール
npm install

# Playwrightブラウザのインストール
npx playwright install
```

## テスト実行

### 基本的なテスト実行

```bash
# 全テストの実行
npm test

# ヘッドレスモードでテスト実行（デフォルト）
npm run test

# ブラウザを表示してテスト実行
npm run test:headed

# デバッグモードでテスト実行
npm run test:debug

# UIモードでテスト実行
npm run test:ui
```

### 特定のテストファイルの実行

```bash
# ログインテストのみ実行
npx playwright test tests/auth/login.spec.ts

# ドキュメント管理テストのみ実行
npx playwright test tests/documents/document-management.spec.ts

# 特定のブラウザでテスト実行
npx playwright test --project=chromium
npx playwright test --project=firefox
npx playwright test --project=webkit
```

### テスト結果の確認

```bash
# HTML テストレポートを開く
npx playwright show-report

# 最新のテスト結果を表示
npx playwright show-trace test-results/[test-name]/trace.zip
```

## テスト構造

```
tests/
├── auth/                   # 認証関連のテスト
│   └── login.spec.ts      # ログイン・ログアウト機能
├── documents/             # ドキュメント管理テスト
│   └── document-management.spec.ts
├── utils/                 # テストユーティリティ
│   ├── auth-helper.ts     # 認証ヘルパー
│   └── test-helper.ts     # 一般的なテストヘルパー
├── fixtures/              # テストフィクスチャ
├── global-setup.ts        # グローバルセットアップ
└── global-teardown.ts     # グローバルクリーンアップ
```

## テストカテゴリ

### 1. 認証テスト (`tests/auth/`)
- ログインフォームの表示確認
- 正常なログイン処理
- 不正な認証情報での失敗
- ログアウト機能
- セッション維持
- 未認証時のリダイレクト

### 2. ドキュメント管理テスト (`tests/documents/`)
- ドキュメント一覧の表示
- フォルダナビゲーション
- ファイルアップロード
- ドキュメントプロパティ表示
- 検索機能
- ダウンロード機能
- UIレスポンシブ性

## 設定

### 環境変数

- `PLAYWRIGHT_BASE_URL`: NemakiWareバックエンドのベースURL（デフォルト: `http://localhost:8080`）

### 認証情報

テストで使用するデフォルト認証情報：
- ユーザー名: `admin`
- パスワード: `admin`
- リポジトリ: `bedroom`

## 現在の状況

### ✅ 完了済み
- Playwright テスト環境のセットアップ
- 包括的なテストケースの実装（認証、ドキュメント管理）
- CI/CD パイプラインの設定
- テストヘルパーとユーティリティの作成
- 基本的な接続テストの動作確認

### ⚠️ 既知の問題
**静的アセット配信の問題**: React UIのJavaScriptファイル（index-*.js）が404エラーで配信されない

- **症状**: index.htmlは正常に配信されるが、JavaScriptとCSSファイルが404エラー
- **影響**: React アプリが初期化されず、ログインフォームが表示されない
- **原因**: Tomcatの静的ファイル配信設定またはMIMEタイプの問題と推測
- **状況**: WAR ファイルには正しく JavaScript ファイルが含まれている（確認済み）

### 🔍 診断結果
```bash
# 正常: HTML ファイル
curl http://localhost:8080/core/ui/index.html
# → HTTP 200

# 異常: JavaScript ファイル
curl http://localhost:8080/core/ui/assets/index-B81QkMzs.js
# → HTTP 404

# 診断テスト実行結果
npx playwright test tests/basic-connectivity.spec.ts
# → 0 form elements, 0 Ant Design elements, 0 input elements
```

### 📝 次のステップ
1. Tomcat web.xml の MIME タイプ設定確認
2. 静的リソースのマッピング設定確認
3. WAR デプロイメント設定の検証

## トラブルシューティング

### よくある問題

1. **バックエンドが起動していない**
   ```
   Error: NemakiWare backend not available at http://localhost:8080
   ```
   → `docker compose -f docker-compose-simple.yml up -d` でバックエンドを起動

2. **ブラウザがインストールされていない**
   ```
   Error: Executable doesn't exist at /path/to/browser
   ```
   → `npx playwright install` でブラウザをインストール

3. **テストがタイムアウトする**
   → `playwright.config.ts` でタイムアウト値を調整

4. **ポート競合**
   → `playwright.config.ts` の `webServer.port` を変更

5. **React UI が読み込まれない（既知の問題）**
   ```
   Found 0 form elements
   Found 0 Ant Design elements
   ```
   → 現在調査中：静的アセット配信問題を解決する必要があります（上記「既知の問題」参照）

### デバッグ

```bash
# 詳細ログでテスト実行
DEBUG=pw:api npm test

# 特定のテストをデバッグモードで実行
npx playwright test tests/auth/login.spec.ts --debug

# ブラウザを表示してステップ実行
npx playwright test --headed --debug
```

## CI/CD

GitHub Actionsワークフローが `.github/workflows/playwright.yml` に設定されています。プルリクエストやメインブランチへのプッシュ時に自動実行されます。

### ローカルでCI環境をシミュレート

```bash
# Docker環境での実行
docker run --rm -it \
  -v $(pwd):/workspace \
  -w /workspace/core/src/main/webapp/ui \
  mcr.microsoft.com/playwright:v1.45.0-jammy \
  npm test
```

## 拡張

### 新しいテストの追加

1. 適切なディレクトリにテストファイルを作成（`*.spec.ts`）
2. テストヘルパーを活用して共通処理を再利用
3. 適切なセレクターとアサーションを使用
4. モックデータが必要な場合は `fixtures/` に配置

### カスタムヘルパーの作成

`tests/utils/` ディレクトリに新しいヘルパークラスを作成し、テスト間で共通の機能を提供できます。

## 参考資料

- [Playwright Documentation](https://playwright.dev/)
- [NemakiWare Architecture](../../../../../../../../CLAUDE.md)
- [React Testing Best Practices](https://react.dev/learn/testing)