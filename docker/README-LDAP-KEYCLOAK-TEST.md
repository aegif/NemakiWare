# LDAP + Keycloak 統合テスト環境

このドキュメントでは、NemakiWareのLDAP同期機能とKeycloak OIDC認証の統合テスト環境について説明します。

## 概要

このテスト環境は以下のコンポーネントで構成されています：

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│    OpenLDAP     │────▶│    Keycloak     │────▶│   NemakiWare    │
│  (ユーザー管理)  │     │  (IdP/認証)     │     │   (CMS)         │
└─────────────────┘     └─────────────────┘     └─────────────────┘
        ↑                       ↑                       ↑
   テストユーザー        LDAP User Federation      OIDC認証
   グループ定義         + OIDC Client            + ユーザー同期
```

## クイックスタート

### ワンコマンド実行

```bash
cd docker
./run-ldap-keycloak-tests.sh
```

このスクリプトは以下を自動的に実行します：
1. NemakiWare UIとWARのビルド
2. Docker環境の起動（OpenLDAP, Keycloak, CouchDB, Solr, NemakiWare）
3. サービスの起動待機
4. テストユーザーの作成
5. Playwright統合テストの実行
6. 環境のクリーンアップ

### オプション

```bash
# ビルドをスキップ（既存のWARを使用）
./run-ldap-keycloak-tests.sh --skip-build

# テスト後もコンテナを維持
./run-ldap-keycloak-tests.sh --keep-running

# 環境セットアップのみ（テストは手動実行）
./run-ldap-keycloak-tests.sh --only-setup
```

## 手動セットアップ

### 1. 環境の起動

```bash
cd docker
docker compose -f docker-compose-ldap-keycloak-test.yml up -d --build
```

### 2. サービスの確認

```bash
# OpenLDAP
docker exec openldap ldapsearch -x -H ldap://localhost \
  -b "dc=nemakiware,dc=example,dc=com" \
  -D "cn=admin,dc=nemakiware,dc=example,dc=com" \
  -w "adminpassword" "(objectClass=inetOrgPerson)"

# Keycloak
curl http://localhost:8088/realms/nemakiware/.well-known/openid-configuration

# NemakiWare
curl -u admin:admin http://localhost:8080/core/atom/bedroom
```

### 3. テストユーザーの作成

```bash
# ldapuser1
curl -u admin:admin -X POST \
  -d "name=LDAP%20User%20One&password=ldappass1&firstName=LDAP%20User&lastName=One&email=ldapuser1@nemakiware.example.com" \
  "http://localhost:8080/core/rest/repo/bedroom/user/create/ldapuser1"

# ldapuser2
curl -u admin:admin -X POST \
  -d "name=LDAP%20User%20Two&password=ldappass2&firstName=LDAP%20User&lastName=Two&email=ldapuser2@nemakiware.example.com" \
  "http://localhost:8080/core/rest/repo/bedroom/user/create/ldapuser2"

# ldapadmin
curl -u admin:admin -X POST \
  -d "name=LDAP%20Administrator&password=ldapadminpass&firstName=LDAP&lastName=Administrator&email=ldapadmin@nemakiware.example.com" \
  "http://localhost:8080/core/rest/repo/bedroom/user/create/ldapadmin"
```

### 4. テストの実行

```bash
cd core/src/main/webapp/ui
npx playwright test tests/auth/ldap-oidc-integration.spec.ts --project=chromium
```

### 5. 環境の停止

```bash
cd docker
docker compose -f docker-compose-ldap-keycloak-test.yml down -v
```

## テストユーザー

| ユーザーID | パスワード | 役割 | メール |
|-----------|-----------|------|--------|
| ldapuser1 | ldappass1 | 一般ユーザー | ldapuser1@nemakiware.example.com |
| ldapuser2 | ldappass2 | 一般ユーザー | ldapuser2@nemakiware.example.com |
| ldapadmin | ldapadminpass | 管理者 | ldapadmin@nemakiware.example.com |

## サービスURL

| サービス | URL | 認証情報 |
|---------|-----|---------|
| NemakiWare UI | http://localhost:8080/core/ui/ | OIDC経由でログイン |
| Keycloak Admin | http://localhost:8088/admin/ | admin / admin |
| OpenLDAP | ldap://localhost:389 | cn=admin,dc=nemakiware,dc=example,dc=com / adminpassword |
| CouchDB | http://localhost:5984 | admin / password |

## ファイル構成

```
docker/
├── docker-compose-ldap-keycloak-test.yml  # Docker Compose設定
├── run-ldap-keycloak-tests.sh             # テスト実行スクリプト
├── README-LDAP-KEYCLOAK-TEST.md           # このドキュメント
├── ldap/
│   └── bootstrap/
│       └── 01-users-groups.ldif           # LDAP初期データ
└── keycloak/
    └── realm-ldap-integration.json        # Keycloakレルム設定
```

## テストケース

### LDAP User Authentication via Keycloak OIDC
- ✅ LDAPユーザー（ldapuser1）がKeycloak OIDC経由でログインできる
- ✅ LDAP管理者（ldapadmin）がKeycloak OIDC経由でログインできる
- ✅ 無効な認証情報でログインが拒否される

### OIDC Token Conversion
- ✅ OIDCトークン変換でNemakiWareセッションが作成される
- ✅ 管理者ユーザーのトークン変換が正常に動作する

### Session Management
- ✅ ログアウトでセッションがクリアされる

## トラブルシューティング

### OpenLDAPが起動しない

```bash
# ログを確認
docker logs openldap

# ボリュームをクリアして再起動
docker compose -f docker-compose-ldap-keycloak-test.yml down -v
docker compose -f docker-compose-ldap-keycloak-test.yml up -d --build
```

### Keycloakのレルムインポートエラー

```bash
# レルム設定を確認
cat keycloak/realm-ldap-integration.json | jq .

# Keycloakログを確認
docker logs keycloak
```

### OIDCログインが失敗する

1. Keycloakのクライアント設定を確認：
   - クライアントID: `nemakiware-oidc-client`
   - リダイレクトURI: `http://localhost:8080/*`, `http://localhost:5173/*`

2. NemakiWareのOIDC設定を確認：
   ```bash
   # UI設定ファイル
   cat core/src/main/webapp/ui/src/services/oidc.ts
   ```

### テストユーザーが作成できない

```bash
# 既存ユーザーを確認
curl -u admin:admin "http://localhost:8080/core/rest/repo/bedroom/user/list" | jq '.users[] | .userId'
```

## 認証アーキテクチャ

### 推奨フロー（SAML/OIDC経由）

```
ブラウザ
    │
    ├──▶ NemakiWare UI (/core/ui/)
    │         │
    │         ▼
    │    OIDCボタンクリック
    │         │
    │         ▼
    └──▶ Keycloak (/realms/nemakiware)
              │
              ├── LDAPユーザー認証 (LDAP User Federation)
              │   または
              ├── Keycloakローカルユーザー認証
              │
              ▼
         認証成功 → OIDCトークン発行
              │
              ▼
         NemakiWare UI (コールバック)
              │
              ▼
         /rest/repo/{repo}/authtoken/oidc/convert
              │
              ▼
         NemakiWareセッション作成
```

### レガシーフロー（直接パスワード認証）

LDAP同期されたユーザーがNemakiWareのパスワードで直接認証することも可能ですが、
Keycloak OIDC経由の認証を推奨します。

## 関連ドキュメント

- [LDAP Sync + Keycloak Authentication Guide](../docs/ldap-sync-keycloak-authentication.md)
- [NemakiWare OIDC設定](../core/src/main/webapp/ui/src/services/oidc.ts)
