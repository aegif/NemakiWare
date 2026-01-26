# NemakiWare UI Testing Guide

## 概要

NemakiWare UIには2種類のテスト環境があります：

1. **標準テスト** - CouchDB + Solr + Coreのみで実行可能
2. **外部認証テスト** - LDAP + Keycloak が必要

## クイックスタート

### 標準テスト（推奨）

```bash
# 1. Docker環境を起動
cd docker
docker compose -f docker-compose-simple.yml up -d

# 2. NemakiWareの起動を待つ（約90秒）
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# 3. UIディレクトリに移動
cd ../core/src/main/webapp/ui

# 4. テストを実行
npm test
```

### テストコマンド一覧

| コマンド | 説明 |
|---------|------|
| `npm test` | 標準テストを実行（外部認証テストはスキップ） |
| `npm run test:standard` | 標準テストを実行（`npm test`と同じ） |
| `npm run test:all` | 全テストを実行（Keycloak必要） |
| `npm run test:external-auth` | LDAP/OIDC/SAMLテストのみ実行 |
| `npm run test:headed` | ブラウザを表示してテスト実行 |
| `npm run test:ui` | Playwright UIモードで実行 |

## テスト環境

### 標準環境（docker-compose-simple.yml）

外部認証なしの基本的なテスト環境：

```yaml
services:
  - couchdb    # データベース
  - solr       # 検索エンジン
  - core       # NemakiWare本体
```

**対象テスト:**
- 認証（ベーシック認証）
- ファイル操作
- フォルダ操作
- 管理機能（ユーザー、グループ、タイプ）
- アーカイブ機能
- Solrメンテナンス

### 外部認証環境（docker-compose-ldap-keycloak-test.yml）

LDAP + Keycloak を含む完全なテスト環境：

```yaml
services:
  - couchdb    # データベース
  - solr       # 検索エンジン
  - openldap   # LDAPサーバー
  - keycloak   # 認証サーバー
  - core       # NemakiWare本体
```

**対象テスト:**
- 標準テストすべて
- OIDC認証
- SAML認証
- LDAP連携
- LDAPユーザー同期

## 環境変数

| 変数名 | デフォルト | 説明 |
|--------|-----------|------|
| `SKIP_KEYCLOAK` | `false` | `true`でKeycloakチェックをスキップ |
| `KEYCLOAK_URL` | `http://localhost:8088` | Keycloak URL |
| `NEMAKIWARE_URL` | `http://localhost:8080` | NemakiWare URL |
| `COUCHDB_URL` | `http://localhost:5984` | CouchDB URL |

## テスト構成

```
tests/
├── auth/                     # 認証テスト
│   ├── login.spec.ts         # ベーシック認証 ✅ 標準
│   ├── admin-protection.spec.ts  # 管理者保護 ✅ 標準
│   ├── oidc-login.spec.ts    # OIDC認証 ⚠️ 要Keycloak
│   ├── saml-login.spec.ts    # SAML認証 ⚠️ 要Keycloak
│   └── ldap-oidc-integration.spec.ts  # LDAP連携 ⚠️ 要Keycloak
├── admin/                    # 管理機能テスト ✅ 標準
│   ├── archive-management.spec.ts
│   ├── custom-type-*.spec.ts
│   ├── group-*.spec.ts
│   ├── solr-maintenance.spec.ts
│   └── user-management.spec.ts
├── bugfix/                   # バグ修正検証 ✅ 標準
│   ├── 2025-12-18-regression-tests.spec.ts
│   └── aspect-and-preview-verification.spec.ts
├── documents/                # ドキュメント操作 ✅ 標準
└── global-setup.ts           # グローバルセットアップ
```

## トラブルシューティング

### テストがスキップされる

外部認証テストが「Keycloak not available」でスキップされる場合：

```bash
# 外部認証環境を起動
cd docker
docker compose -f docker-compose-ldap-keycloak-test.yml up -d

# LDAP同期を実行
curl -u admin:admin -X POST http://localhost:8080/core/rest/repo/bedroom/sync/trigger

# 外部認証テストを実行
npm run test:external-auth
```

### バックエンドに接続できない

```bash
# Dockerコンテナの状態を確認
docker ps

# ログを確認
docker logs docker-core-1 --tail 50

# ヘルスチェック
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### テストがタイムアウトする

1. マシンリソースを確認
2. `playwright.config.ts` の `timeout` を調整
3. `--workers=1` オプションでシリアル実行

## CI/CD統合

### GitHub Actions例

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Start Docker
        run: |
          cd docker
          docker compose -f docker-compose-simple.yml up -d
          sleep 90

      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Install dependencies
        run: |
          cd core/src/main/webapp/ui
          npm ci
          npx playwright install chromium

      - name: Run tests
        run: |
          cd core/src/main/webapp/ui
          npm test
```

## 関連ドキュメント

- `CLAUDE.md` - プロジェクト概要
- `docker/README-LDAP-KEYCLOAK-TEST.md` - LDAP/Keycloak環境の詳細
- `playwright.config.ts` - Playwright設定
