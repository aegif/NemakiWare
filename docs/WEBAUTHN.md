# WebAuthn / パスキー認証

NemakiWare は FIDO2 WebAuthn によるパスワードレス認証をサポートしています。Touch ID、Face ID、セキュリティキーを使ってログインできます。

## 前提条件

- HTTPS 接続（localhost / 127.0.0.1 では HTTP も許可）
- WebAuthn 対応ブラウザ（Chrome 67+、Safari 14+、Firefox 60+）
- 認証器（Touch ID、Face ID、Windows Hello、YubiKey 等）

> **セキュリティ**: HTTP + 非 localhost の組み合わせは拒否されます（MITM 攻撃によるパスキー偽造を防止）。

## 認証フロー

### パスキー登録

1. NemakiWare UI にログイン
2. ユーザー設定 → パスキー管理
3. 「パスキーを追加」をクリック
4. ブラウザの認証プロンプトに従う（指紋/顔認証/セキュリティキー）

### パスキーログイン

1. ログイン画面で「パスキーでログイン」をクリック
2. ブラウザの認証プロンプトに従う
3. 認証成功でセッションが開始

## REST API

| エンドポイント | メソッド | 認証 | 説明 |
|--------------|---------|------|------|
| `/rest/repo/{id}/webauthn/register/begin` | POST | 必要 | 登録チャレンジ生成 |
| `/rest/repo/{id}/webauthn/register/complete` | POST | 必要 | 登録完了 |
| `/rest/repo/{id}/webauthn/authenticate/begin` | POST | 不要 | 認証チャレンジ生成 |
| `/rest/repo/{id}/webauthn/authenticate/complete` | POST | 不要 | 認証完了 |
| `/rest/repo/{id}/webauthn/credentials` | GET | 必要 | 登録済みパスキー一覧 |
| `/rest/repo/{id}/webauthn/credentials/{credId}` | DELETE | 必要 | パスキー削除 |

> `/authenticate/begin` と `/authenticate/complete` は認証なしでアクセス可能です（パスキーログインの入口のため）。不正利用を防ぐために challenge store にサイズ上限（10,000）を設けています。

## 実装詳細

- **ライブラリ**: Yubico java-webauthn-server
- **Challenge Store**: ConcurrentHashMap（TTL: 5分、上限: 10,000エントリ）
- **RP ID**: `request.getServerName()`（RemoteIpValve 経由で解決）
- **Credential 保存**: CouchDB（ユーザードキュメントの `webauthnCredentials` フィールド）
- **Resident Key**: サポート（discoverable credential）

## トラブルシューティング

### 「WebAuthn requires HTTPS」エラー
HTTP + 非 localhost からのアクセス。HTTPS を設定するか、localhost でアクセスしてください。

### パスキーが表示されない
ブラウザが WebAuthn をサポートしていることを確認。`navigator.credentials` が undefined の場合は非対応です。

### 「Challenge not found」エラー
チャレンジの有効期限（5分）が切れた場合。再度「パスキーを追加」からやり直してください。
