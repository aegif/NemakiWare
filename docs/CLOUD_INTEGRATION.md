# クラウド統合ガイド (Google / Microsoft)

NemakiWare 3.1.0 では、Google Workspace と Microsoft 365 (Entra ID) との統合機能を提供しています。

## 目次

1. [機能概要](#機能概要)
2. [Google 統合設定](#google-統合設定)
   - [OIDC認証 (Googleでログイン)](#1-oidc認証-googleでログイン)
   - [Cloud Drive連携](#2-cloud-drive連携-google-drive)
   - [ディレクトリ同期](#3-ディレクトリ同期-google-workspace)
3. [Microsoft 統合設定](#microsoft-統合設定)
   - [OIDC認証 (Microsoftでログイン)](#1-oidc認証-microsoftでログイン)
   - [Cloud Drive連携](#2-cloud-drive連携-onedrive)
   - [ディレクトリ同期](#3-ディレクトリ同期-microsoft-entra-id)
4. [NemakiWare設定](#nemakiware設定)
5. [トラブルシューティング](#トラブルシューティング)

---

## 機能概要

| 機能 | 説明 | Google | Microsoft |
|------|------|--------|-----------|
| **OIDC認証** | クラウドアカウントでNemakiWareにログイン | ✅ | ✅ |
| **Cloud Drive連携** | クラウドストレージからファイルをインポート | ✅ Google Drive | ✅ OneDrive |
| **ディレクトリ同期** | ユーザー・グループを自動同期 | ✅ Google Workspace | ✅ Entra ID |

---

## Google 統合設定

### 前提条件

- Google Cloud Platform (GCP) アカウント
- Google Workspace 管理者権限（ディレクトリ同期を使用する場合）

### 1. OIDC認証 (Googleでログイン)

ユーザーが Google アカウントで NemakiWare にログインできるようにします。

#### Step 1: GCP プロジェクトの作成

1. [Google Cloud Console](https://console.cloud.google.com/) にアクセス
2. 左上のプロジェクトセレクタ → **新しいプロジェクト**
3. プロジェクト名を入力（例: `nemakiware-integration`）
4. **作成** をクリック

#### Step 2: OAuth 同意画面の設定

1. 左メニュー: **APIとサービス** → **OAuth 同意画面**
2. User Type を選択:
   - **内部**: Google Workspace 組織内ユーザーのみ（推奨）
   - **外部**: すべての Google アカウント
3. **作成** をクリック
4. アプリ情報を入力:
   - **アプリ名**: `NemakiWare`
   - **ユーザーサポートメール**: 管理者のメールアドレス
   - **デベロッパーの連絡先情報**: 管理者のメールアドレス
5. **保存して次へ**
6. スコープ画面: **保存して次へ**（デフォルトのまま）
7. テストユーザー画面: **保存して次へ**
8. **ダッシュボードに戻る**

#### Step 3: OAuth クライアントIDの作成

1. 左メニュー: **APIとサービス** → **認証情報**
2. 上部: **+ 認証情報を作成** → **OAuth クライアント ID**
3. アプリケーションの種類: **ウェブ アプリケーション**
4. 名前: `NemakiWare Web Client`
5. **承認済みの JavaScript 生成元** に追加:
   ```
   https://your-nemakiware-domain.com
   http://localhost:8080  (開発環境用)
   ```
6. **承認済みのリダイレクト URI** に追加:
   ```
   https://your-nemakiware-domain.com/core/rest/repo/bedroom/authtoken/oidc/callback
   http://localhost:8080/core/rest/repo/bedroom/authtoken/oidc/callback
   ```
7. **作成** をクリック
8. 表示された **クライアントID** と **クライアントシークレット** を安全に保存

> ⚠️ **重要**: クライアントシークレットは一度しか表示されません。必ず保存してください。

---

### 2. Cloud Drive連携 (Google Drive)

ユーザーが Google Drive からファイルを NemakiWare にインポートできるようにします。

#### Step 1: Google Drive API の有効化

1. [Google Cloud Console](https://console.cloud.google.com/) → 対象プロジェクト
2. 左メニュー: **APIとサービス** → **ライブラリ**
3. 検索: `Google Drive API`
4. **Google Drive API** をクリック → **有効にする**

#### Step 2: OAuth スコープの追加

1. 左メニュー: **APIとサービス** → **OAuth 同意画面**
2. **アプリを編集** をクリック
3. **スコープを追加または削除** をクリック
4. 以下のスコープを追加:

| スコープ | 説明 |
|----------|------|
| `https://www.googleapis.com/auth/drive.readonly` | Drive ファイルの読み取り |
| `https://www.googleapis.com/auth/drive.metadata.readonly` | ファイルメタデータの読み取り |

5. **更新** → **保存して次へ**

> 📝 **Note**: Cloud Drive連携は OIDC認証と同じ OAuth クライアントIDを使用します。追加の認証情報作成は不要です。

---

### 3. ディレクトリ同期 (Google Workspace)

Google Workspace のユーザー・グループを NemakiWare に自動同期します。

#### 前提条件

- Google Workspace 管理者権限
- ドメイン全体の委任が可能な環境

#### Step 1: サービスアカウントの作成

1. [Google Cloud Console](https://console.cloud.google.com/) → 対象プロジェクト
2. 左メニュー: **IAM と管理** → **サービスアカウント**
3. 上部: **+ サービスアカウントを作成**
4. サービスアカウント名: `nemakiware-directory-sync`
5. **作成して続行**
6. ロールの付与: スキップ（**続行**）
7. **完了**

#### Step 2: サービスアカウントキーの作成

1. 作成したサービスアカウントをクリック
2. **キー** タブ → **鍵を追加** → **新しい鍵を作成**
3. キーのタイプ: **JSON**
4. **作成** → JSONファイルがダウンロードされます
5. このファイルを安全に保存（例: `google-service-account.json`）

> ⚠️ **重要**: このキーファイルは機密情報です。Git にコミットしないでください。

#### Step 3: Admin SDK API の有効化

1. 左メニュー: **APIとサービス** → **ライブラリ**
2. 検索: `Admin SDK API`
3. **Admin SDK API** をクリック → **有効にする**

#### Step 4: ドメイン全体の委任の設定

1. [Google Workspace 管理コンソール](https://admin.google.com/) にアクセス
2. **セキュリティ** → **アクセスとデータ管理** → **API の制御**
3. **ドメイン全体の委任** → **ドメイン全体の委任を管理**
4. **新しく追加** をクリック
5. 入力項目:
   - **クライアント ID**: サービスアカウントの OAuth 2.0 クライアント ID
     （サービスアカウント詳細画面の「一意のID」）
   - **OAuth スコープ**:
     ```
     https://www.googleapis.com/auth/admin.directory.user.readonly,https://www.googleapis.com/auth/admin.directory.group.readonly
     ```
6. **承認** をクリック

#### Step 5: 委任ユーザーの確認

サービスアカウントは管理者ユーザーとして動作します。Google Workspace の管理者メールアドレスを控えておいてください（例: `admin@your-domain.com`）。このメールアドレスは NemakiWare 設定で使用します。

---

## Microsoft 統合設定

### 前提条件

- Microsoft Azure アカウント
- Microsoft Entra ID (旧 Azure AD) 管理者権限

### 1. OIDC認証 (Microsoftでログイン)

ユーザーが Microsoft アカウントで NemakiWare にログインできるようにします。

#### Step 1: Azure アプリの登録

1. [Azure Portal](https://portal.azure.com/) にアクセス
2. 検索バー: `アプリの登録` を検索 → **アプリの登録**
3. **+ 新規登録** をクリック
4. アプリケーション情報を入力:
   - **名前**: `NemakiWare`
   - **サポートされているアカウントの種類**:
     - **この組織ディレクトリのみ**: 自社テナントユーザーのみ（推奨）
     - **任意の組織ディレクトリ**: マルチテナント
   - **リダイレクト URI**:
     - プラットフォーム: **Web**
     - URI: `https://your-nemakiware-domain.com/core/rest/repo/bedroom/authtoken/oidc/callback`
5. **登録** をクリック

#### Step 2: クライアントシークレットの作成

1. 登録したアプリの画面で左メニュー: **証明書とシークレット**
2. **クライアント シークレット** タブ → **+ 新しいクライアント シークレット**
3. 説明: `NemakiWare Auth`
4. 有効期限: 組織のポリシーに従って選択（推奨: 24ヶ月）
5. **追加** をクリック
6. 表示された **値** を安全に保存（これがクライアントシークレット）

> ⚠️ **重要**: シークレットの値は一度しか表示されません。必ず保存してください。

#### Step 3: 必要な情報の確認

アプリの **概要** ページで以下の値を確認:

| 項目 | 説明 | NemakiWare設定キー |
|------|------|-------------------|
| アプリケーション (クライアント) ID | アプリの一意識別子 | `cloud.auth.microsoft.clientId` |
| ディレクトリ (テナント) ID | Azure AD テナント識別子 | `cloud.auth.microsoft.tenantId` |
| クライアントシークレット | Step 2 で作成した値 | `cloud.auth.microsoft.clientSecret` |

---

### 2. Cloud Drive連携 (OneDrive)

ユーザーが OneDrive からファイルを NemakiWare にインポートできるようにします。

#### Step 1: API アクセス許可の追加

1. アプリの登録画面 → 左メニュー: **API のアクセス許可**
2. **+ アクセス許可の追加** をクリック
3. **Microsoft Graph** を選択
4. **委任されたアクセス許可** を選択
5. 以下のアクセス許可を追加:

| アクセス許可 | 説明 |
|-------------|------|
| `Files.Read` | ユーザーのファイルを読み取り |
| `Files.Read.All` | ユーザーがアクセスできるすべてのファイルを読み取り |
| `User.Read` | サインインとユーザープロファイルの読み取り |

6. **アクセス許可の追加** をクリック

> 📝 **Note**: 「管理者の同意が必要」と表示されるアクセス許可がある場合は、Azure AD 管理者に同意を依頼してください。

#### Step 2: リダイレクト URI の追加（ポップアップ認証用）

1. 左メニュー: **認証**
2. **プラットフォーム構成** → **Web** → **URI の追加**
3. 以下を追加:
   ```
   https://your-nemakiware-domain.com/core/ui/auth-popup.html
   http://localhost:8080/core/ui/auth-popup.html (開発環境用)
   ```
4. **保存** をクリック

---

### 3. ディレクトリ同期 (Microsoft Entra ID)

Microsoft Entra ID のユーザー・グループを NemakiWare に自動同期します。

#### Step 1: アプリケーションのアクセス許可の追加

1. アプリの登録画面 → 左メニュー: **API のアクセス許可**
2. **+ アクセス許可の追加** → **Microsoft Graph**
3. **アプリケーションのアクセス許可** を選択（委任ではなく）
4. 以下のアクセス許可を追加:

| アクセス許可 | 説明 |
|-------------|------|
| `User.Read.All` | すべてのユーザーの完全なプロファイルの読み取り |
| `Group.Read.All` | すべてのグループの読み取り |
| `GroupMember.Read.All` | すべてのグループメンバーシップの読み取り |

5. **アクセス許可の追加** をクリック

#### Step 2: 管理者の同意の付与

1. **API のアクセス許可** 画面で
2. **[テナント名] に管理者の同意を与えます** をクリック
3. 確認ダイアログで **はい** をクリック

> ⚠️ **重要**: アプリケーションのアクセス許可は管理者の同意が必須です。

#### Step 3: 同期対象グループの確認（オプション）

特定のグループのみを同期したい場合は、Azure AD でグループの Object ID を確認しておいてください:

1. Azure Portal → **Microsoft Entra ID** → **グループ**
2. 対象グループをクリック
3. **オブジェクト ID** をコピー

---

## NemakiWare設定

### 設定ファイル

`nemakiware.properties` または Docker 環境変数で設定します。

### Google 設定

```properties
# === OIDC認証 (Google) ===
cloud.auth.google.enabled=true
cloud.auth.google.clientId=YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com
cloud.auth.google.clientSecret=YOUR_GOOGLE_CLIENT_SECRET

# === Cloud Drive (Google Drive) ===
cloud.drive.google.enabled=true
# 注: OIDC認証と同じクライアントIDを使用

# === ディレクトリ同期 (Google Workspace) ===
cloud.directory.sync.google.enabled=true
cloud.directory.sync.google.serviceAccountKey=/path/to/google-service-account.json
cloud.directory.sync.google.domain=your-domain.com
cloud.directory.sync.google.adminEmail=admin@your-domain.com
```

### Microsoft 設定

```properties
# === OIDC認証 (Microsoft) ===
cloud.auth.microsoft.enabled=true
cloud.auth.microsoft.clientId=YOUR_AZURE_APP_CLIENT_ID
cloud.auth.microsoft.tenantId=YOUR_AZURE_TENANT_ID
cloud.auth.microsoft.clientSecret=YOUR_AZURE_CLIENT_SECRET

# === Cloud Drive (OneDrive) ===
cloud.drive.microsoft.enabled=true
# 注: OIDC認証と同じクライアントID/シークレットを使用

# === ディレクトリ同期 (Microsoft Entra ID) ===
cloud.directory.sync.microsoft.enabled=true
cloud.directory.sync.microsoft.tenantId=YOUR_AZURE_TENANT_ID
cloud.directory.sync.microsoft.clientId=YOUR_AZURE_APP_CLIENT_ID
cloud.directory.sync.microsoft.clientSecret=YOUR_AZURE_CLIENT_SECRET
```

### Docker 環境変数

`docker/.env` ファイルで設定する場合:

```bash
# Google
NEMAKI_CLOUD_AUTH_GOOGLE_ENABLED=true
NEMAKI_CLOUD_AUTH_GOOGLE_CLIENT_ID=xxx.apps.googleusercontent.com
NEMAKI_CLOUD_AUTH_GOOGLE_CLIENT_SECRET=xxx

NEMAKI_CLOUD_DRIVE_GOOGLE_ENABLED=true

NEMAKI_CLOUD_DIRECTORY_SYNC_GOOGLE_ENABLED=true
NEMAKI_CLOUD_DIRECTORY_SYNC_GOOGLE_SERVICE_ACCOUNT_KEY=/config/google-service-account.json
NEMAKI_CLOUD_DIRECTORY_SYNC_GOOGLE_DOMAIN=your-domain.com
NEMAKI_CLOUD_DIRECTORY_SYNC_GOOGLE_ADMIN_EMAIL=admin@your-domain.com

# Microsoft
NEMAKI_CLOUD_AUTH_MICROSOFT_ENABLED=true
NEMAKI_CLOUD_AUTH_MICROSOFT_CLIENT_ID=xxx
NEMAKI_CLOUD_AUTH_MICROSOFT_TENANT_ID=xxx
NEMAKI_CLOUD_AUTH_MICROSOFT_CLIENT_SECRET=xxx

NEMAKI_CLOUD_DRIVE_MICROSOFT_ENABLED=true

NEMAKI_CLOUD_DIRECTORY_SYNC_MICROSOFT_ENABLED=true
NEMAKI_CLOUD_DIRECTORY_SYNC_MICROSOFT_TENANT_ID=xxx
NEMAKI_CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_ID=xxx
NEMAKI_CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_SECRET=xxx
```

---

## 設定の検証

### 1. OIDC認証のテスト

1. NemakiWare UI (`http://localhost:8080/core/ui/`) にアクセス
2. ログイン画面で **Googleでログイン** または **Microsoftでログイン** をクリック
3. クラウドプロバイダーの認証画面が表示されることを確認
4. 認証後、NemakiWare にリダイレクトされることを確認

### 2. Cloud Drive連携のテスト

1. ログイン後、ドキュメント一覧画面に移動
2. **クラウドからインポート** ボタンをクリック
3. Google Drive または OneDrive の認証画面が表示されることを確認
4. ファイル選択画面が表示されることを確認

### 3. ディレクトリ同期のテスト

1. 管理者でログイン
2. **管理** → **クラウドディレクトリ同期** に移動
3. 対象プロバイダーのタブを選択
4. **今すぐ同期** をクリック
5. 同期結果を確認

---

## トラブルシューティング

### Google

| エラー | 原因 | 対処法 |
|--------|------|--------|
| `redirect_uri_mismatch` | リダイレクトURIが一致しない | GCP Console でリダイレクトURIを確認・修正 |
| `access_denied` | OAuth同意画面が未承認 | GCP Console でアプリを公開するか、テストユーザーを追加 |
| `invalid_grant` | 認証コードの期限切れ | 再度認証フローを開始 |
| `unauthorized_client` | ドメイン全体の委任が未設定 | Google Workspace 管理コンソールで委任を設定 |

### Microsoft

| エラー | 原因 | 対処法 |
|--------|------|--------|
| `AADSTS50011` | リダイレクトURIが一致しない | Azure Portal でリダイレクトURIを確認・修正 |
| `AADSTS65001` | 管理者の同意が必要 | Azure AD 管理者に同意を依頼 |
| `AADSTS7000215` | クライアントシークレットが無効 | 新しいシークレットを作成して更新 |
| `AADSTS700016` | テナントIDが間違っている | Azure Portal でテナントIDを確認 |

### 共通

| 問題 | 確認事項 |
|------|----------|
| ログインボタンが表示されない | `cloud.auth.*.enabled=true` を確認 |
| 同期が動作しない | サービスアカウントキー/クライアントシークレットを確認 |
| ユーザーが作成されない | `oidc.isAutoCreateUser=true` を確認 |

---

## セキュリティに関する注意

1. **クライアントシークレットは機密情報です**
   - 環境変数またはシークレット管理サービスを使用
   - Git にコミットしない

2. **サービスアカウントキーは厳重に管理**
   - 最小権限の原則に従う
   - 定期的にローテーション

3. **本番環境ではHTTPSを使用**
   - リダイレクトURIは `https://` を使用
   - `http://localhost` は開発環境のみ

4. **定期的な監査**
   - Azure Portal / GCP Console でアプリのアクセスログを確認
   - 不要になったアクセス許可は削除

---

## 参考リンク

### Google
- [Google Cloud Console](https://console.cloud.google.com/)
- [Google Workspace 管理コンソール](https://admin.google.com/)
- [OAuth 2.0 ドキュメント](https://developers.google.com/identity/protocols/oauth2)
- [Admin SDK API リファレンス](https://developers.google.com/admin-sdk/directory)

### Microsoft
- [Azure Portal](https://portal.azure.com/)
- [Microsoft Entra 管理センター](https://entra.microsoft.com/)
- [Microsoft Graph API リファレンス](https://docs.microsoft.com/graph/api/overview)
- [Azure AD アプリ登録ガイド](https://docs.microsoft.com/azure/active-directory/develop/quickstart-register-app)
