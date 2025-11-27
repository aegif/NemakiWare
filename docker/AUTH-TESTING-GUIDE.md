# NemakiWare OIDC/SAML認証テストガイド

このガイドでは、NemakiWareのOIDC/SAML認証機能をローカル環境でテストする方法を説明します。

## 概要

Keycloakをローカル認証プロバイダ (IdP) として使用し、OIDC (OpenID Connect) と SAML 2.0 の両方の認証フローをテストできます。

## クイックスタート

### 1. 認証テスト環境の起動

```bash
cd docker
docker compose -f docker-compose-auth-test.yml up -d
```

### 2. サービスの起動確認

以下のサービスが起動します：

| サービス | URL | 説明 |
|---------|-----|------|
| NemakiWare Core | http://localhost:8080/core/ui/ | メインアプリケーション |
| Keycloak Admin | http://localhost:8180/admin | IdP管理コンソール |
| CouchDB | http://localhost:5984 | ドキュメントDB |
| Solr | http://localhost:8983 | 検索エンジン |

### 3. Keycloak管理コンソールへのアクセス

- URL: http://localhost:8180/admin
- ユーザー名: `admin`
- パスワード: `admin`

## テストユーザー

以下のテストユーザーが事前設定されています：

| ユーザー名 | パスワード | ロール | 用途 |
|-----------|-----------|-------|------|
| testuser | testpass | user | 一般ユーザーテスト |
| admin | admin | admin, user | 管理者テスト |

## OIDC認証テスト

### 設定情報

| 項目 | 値 |
|-----|-----|
| Authority (発行者URL) | http://localhost:8180/realms/nemakiware |
| Client ID | nemakiware-ui |
| Response Type | code (Authorization Code Flow) |
| Scope | openid profile email |
| Redirect URI | http://localhost:8080/core/ui/oidc-callback |

### テスト手順

1. **NemakiWare UIにアクセス**
   ```
   http://localhost:8080/core/ui/
   ```

2. **OIDCログインボタンをクリック**
   - Keycloakのログイン画面にリダイレクトされます

3. **テストユーザーでログイン**
   - ユーザー名: `testuser`
   - パスワード: `testpass`

4. **認証完了を確認**
   - NemakiWare UIに戻り、ログイン状態になります
   - ブラウザのDevToolsでトークンを確認できます

### OIDC Discovery エンドポイント

```bash
curl http://localhost:8180/realms/nemakiware/.well-known/openid-configuration
```

### トークン変換エンドポイント

React UIがOIDCトークンをNemakiWare内部トークンに変換する際に使用：

```bash
curl -X POST http://localhost:8080/core/rest/repo/bedroom/authtoken/oidc/convert \
  -H "Content-Type: application/json" \
  -d '{
    "oidc_token": "access_token_value",
    "id_token": "id_token_value",
    "user_info": {
      "preferred_username": "testuser",
      "email": "testuser@example.com",
      "sub": "user-uuid"
    }
  }'
```

## SAML認証テスト

### 設定情報

| 項目 | 値 |
|-----|-----|
| IdP Entity ID | http://localhost:8180/realms/nemakiware |
| IdP SSO URL | http://localhost:8180/realms/nemakiware/protocol/saml |
| SP Entity ID | nemakiware-sp |
| Callback URL | http://localhost:8080/core/ui/saml-callback |

### テスト手順

1. **NemakiWare UIにアクセス**
   ```
   http://localhost:8080/core/ui/
   ```

2. **SAMLログインボタンをクリック**
   - Keycloakのログイン画面にリダイレクトされます

3. **テストユーザーでログイン**
   - ユーザー名: `testuser`
   - パスワード: `testpass`

4. **認証完了を確認**
   - SAMLレスポンスがコールバックURLに送信されます
   - NemakiWare UIがSAMLレスポンスを処理し、ログイン状態になります

### IdP Metadata エンドポイント

```bash
curl http://localhost:8180/realms/nemakiware/protocol/saml/descriptor
```

### トークン変換エンドポイント

React UIがSAMLレスポンスをNemakiWare内部トークンに変換する際に使用：

```bash
curl -X POST http://localhost:8080/core/rest/repo/bedroom/authtoken/saml/convert \
  -H "Content-Type: application/json" \
  -d '{
    "saml_response": "base64_encoded_saml_response",
    "relay_state": "repositoryId=bedroom",
    "user_attributes": {
      "email": "testuser@example.com",
      "uid": "testuser"
    }
  }'
```

## 環境変数によるカスタマイズ

React UIの認証設定は環境変数で上書きできます：

### OIDC設定

```bash
VITE_OIDC_AUTHORITY=http://your-idp.example.com/realms/your-realm
VITE_OIDC_CLIENT_ID=your-client-id
VITE_OIDC_ENABLED=true
```

### SAML設定

```bash
VITE_SAML_SSO_URL=http://your-idp.example.com/saml/sso
VITE_SAML_ENTITY_ID=your-sp-entity-id
VITE_SAML_LOGOUT_URL=http://your-idp.example.com/saml/logout
VITE_SAML_ENABLED=true
```

## トラブルシューティング

### Keycloakが起動しない

```bash
# ログを確認
docker logs docker-keycloak-1

# ヘルスチェック
curl http://localhost:8180/health/ready
```

### OIDC認証が失敗する

1. **リダイレクトURIの確認**
   - Keycloak管理コンソールで `nemakiware-ui` クライアントの設定を確認
   - Valid Redirect URIs に `http://localhost:8080/*` が含まれているか確認

2. **CORS設定の確認**
   - Web Origins に `http://localhost:8080` が含まれているか確認

3. **ブラウザコンソールでエラーを確認**
   - DevTools > Console でエラーメッセージを確認

### SAML認証が失敗する

1. **SAMLレスポンスの確認**
   - ブラウザのDevToolsでNetworkタブを確認
   - SAMLResponse パラメータが含まれているか確認

2. **署名検証エラーの場合**
   - Keycloakの署名設定を確認
   - 現在の設定: `saml.server.signature=true`, `saml.client.signature=false`

### トークン変換が失敗する

```bash
# サーバーログを確認
docker logs docker-core-1 2>&1 | grep -i "token\|oidc\|saml"
```

## 環境のクリーンアップ

```bash
cd docker
docker compose -f docker-compose-auth-test.yml down -v
```

ボリュームを残したい場合は `-v` を省略：

```bash
docker compose -f docker-compose-auth-test.yml down
```

## Keycloak レルム設定の詳細

### OIDC クライアント (nemakiware-ui)

- Protocol: openid-connect
- Access Type: public (PKCE推奨)
- Standard Flow: enabled
- Direct Access Grants: enabled (テスト用)
- Valid Redirect URIs: http://localhost:8080/*, http://localhost:5173/*
- Web Origins: http://localhost:8080, http://localhost:5173

### SAML クライアント (nemakiware-sp)

- Protocol: saml
- Include AuthnStatement: true
- Sign Documents: true
- Force POST Binding: true
- Name ID Format: username
- Valid Redirect URIs: http://localhost:8080/*, http://localhost:5173/*

## 参考リンク

- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [OpenID Connect 1.0 Specification](https://openid.net/specs/openid-connect-core-1_0.html)
- [SAML 2.0 Specification](https://docs.oasis-open.org/security/saml/v2.0/)
- [oidc-client-ts Library](https://github.com/authts/oidc-client-ts)
