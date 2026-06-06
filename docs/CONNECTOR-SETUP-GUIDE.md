# 外部コネクタ設定ガイド（サービス別ステップバイステップ）

NemakiWare の External Ingest（外部取り込み）で、各クラウドサービスから
コンテンツを取り込むためのコネクタ設定を、**サービスごとに** ステップバイ
ステップで解説します。

> このガイドの「NemakiWare 側の設定値」（`sourceSystem` 文字列、必須
> `schedulerParams`、認証ヘッダの形式、エンドポイントの要否）は **実装コードに
>基づく正確な値** です。一方、各 SaaS 管理画面でのトークン取得手順は変更され
> やすいため、画面名は目安として参照し、最終的には各サービスの公式ドキュメント
> を確認してください。

---

## 0. 前提：コネクタは「2階建て」構成

NemakiWare の取り込みは **コネクタ定義** と **インポートプロファイル** の
2 つを作って初めて動きます。混乱の原因になりやすいので最初に整理します。

| | コネクタ定義 (Connector) | インポートプロファイル (Import Profile) |
|---|---|---|
| 役割 | **どのサービスに・どの認証情報で繋ぐか** | **何を・どこへ取り込むか** |
| 主な項目 | `sourceSystem`（接続先）, `sourceArchetype`（種別）, `endpoint`, `credentialRef`（認証情報）, `webhookSecret` | `targetFolderId`（取込先フォルダ）, `defaultConnectorId`（使うコネクタ）, **`schedulerParams`**（何を取るか＝channelId / mailbox / soql 等） |
| 管理画面 | 管理 → 連携設定 → **コネクタ管理** タブ | 管理 → 連携設定 → **インポートプロファイル** タブ |
| REST API | `POST /core/api/v1/admin/connectors` | `POST /core/api/v1/admin/import-profiles` |

**重要：`sourceArchetype`（種別）と `sourceSystem`（接続先）は UI 上で別々に
選べますが、サービスごとに正しい組み合わせが決まっています。** 下表の通りに
組み合わせてください（誤った組み合わせは取り込みが動きません）。

### コネクタ早見表（正しい組み合わせ）

| サービス | `sourceSystem`（接続先） | `sourceArchetype`（種別） | `endpoint` の要否 | 実トークンの種類（`credentialRef` キーで参照、§2） | 必須 `schedulerParams` | Webhook |
|---|---|---|---|---|---|---|
| Slack | `slack` | `CHAT_CONTEXT` | 任意（既定 slack.com/api） | Bot Token (`xoxb-…`) | `channelId` | あり |
| Microsoft Teams | `teams` | `CHAT_CONTEXT` | 任意（既定 Graph） | Graph アクセストークン | `teamId`, `channelId` | あり |
| Mattermost | `mattermost` | `CHAT_CONTEXT` | **必須**（サーバ URL） | Personal Access / Bot Token | `channelId` | なし |
| Chatwork | `chatwork` | `CHAT_CONTEXT` | 任意（既定 API） | API Token | `roomId` | あり（汎用） |
| IMAP メール | `imap` | `MESSAGE_CONTEXT` | **必須**（`host:port`） | メールパスワード（または OAuth2） | `mailbox`（既定 INBOX） | なし |
| Gmail | `gmail_mail` | `MESSAGE_CONTEXT` | 不要 | OAuth2 アクセストークン | `query`（既定 in:inbox is:unread） | なし |
| Microsoft 365 メール | `m365_mail` | `MESSAGE_CONTEXT` | 任意（既定 Graph） | Graph アクセストークン | `folderId`（既定 inbox）, 任意 `userId` | あり |
| Notion | `notion` | `COMPOUND_NOTE` | 任意（既定 API） | Integration Token | `query`（任意） | なし |
| Salesforce | `salesforce` | `BUSINESS_RECORD` | **必須**（インスタンス URL） | OAuth2 アクセストークン | `soql`（任意・既定テンプレあり） | なし |
| Box | `box` | `FILE_SHARE` | 不要 | OAuth2 アクセストークン | `folderId`（既定 0） | なし |
| Dropbox | `dropbox` | `FILE_SHARE` | 不要 | OAuth2 アクセストークン | `folderPath`（既定 空=ルート） | なし |

> `tenantId` は IMAP のみ「メールアドレス（ログインユーザ名）」として **必須**。
> 他サービスでは任意です。

> **`google_drive` / `onedrive` について**：コネクタ管理タブの接続先プルダウンには
> `google_drive` と `onedrive` も表示されますが、これらは本ガイドが扱う「スケジュール
> 取り込みアダプタ」ではなく、**クラウドドライブ同期（双方向 push/pull）** の経路で
> 設定・運用します（別ドキュメント `docs/CLOUD_INTEGRATION.md` を参照）。本ガイドの
> 11 コネクタとは設定フローが異なるため、ここでは対象外とします。

> **`requiredParams` の強制範囲**：アダプタレジストリが保存時に必須チェックするのは
> チャット系（Slack=`channelId` / Teams=`channelId`,`teamId` / Mattermost=`channelId` /
> Chatwork=`roomId`）だけです。メール・ファイル・ノート・レコード系の
> `mailbox` / `folderId` / `query` / `soql` 等は技術的には「省略時に既定値が入る」
> 扱い（必須チェックなし）ですが、**実運用では明示指定を強く推奨**します（既定値は
> あくまで疎通確認用）。

---

## 1. 共通の設定手順（UI）

各サービス固有の手順（§3 以降）に入る前に、共通の流れです。

### 1-1. コネクタを作る（コネクタ管理タブ）

1. 管理者（`admin`）で NemakiWare にログイン
   （外部公開時は例: `https://avenue.aegif.jp:11469/core/ui/`）。
2. **管理 → 連携設定（Integration Settings） → コネクタ管理** タブを開く。
3. 「コネクタを追加」を押し、フォームに入力：
   - **コネクタ ID**：任意の一意な識別子（例 `slack-sales`）。
   - **表示名**：人間向けの名前（例 `営業 Slack`）。
   - **種別（sourceArchetype）**：早見表の通り（例 Slack なら `CHAT_CONTEXT`）。
   - **接続先（sourceSystem）**：早見表の通り（例 `slack`）。プルダウンに
     `表示名 (sourceSystem)` 形式で並びます。
   - **認証タイプ（authType）**：oauth2 / api_key / service_account / none から
     該当するもの（任意・記録用。実際の認証は credentialRef で行います）。
   - **エンドポイント（endpoint）**：早見表で「必須」のサービスのみ。
   - **テナント ID（tenantId）**：IMAP のみメールアドレスを入れる。
   - **認証情報（credentialRef）**：トークンそのものではなく **解決キー**
     （例 `ingest.slack.sales.token`）を入れる。実トークンは §2 の手順で
     環境変数等に provision する。保存後はマスク表示（`[configured]`）になります。
   - **Webhook シークレット（webhookSecret）**：Webhook を使うサービスのみ。
4. 保存。

### 1-2. インポートプロファイルを作る（インポートプロファイルタブ）

1. **管理 → 連携設定 → インポートプロファイル** タブを開く。
2. 「プロファイルを追加」：
   - **取込先フォルダ（targetFolderId）**：取り込んだコンテンツを置く NemakiWare
     フォルダ。フォルダピッカーで選択。
   - **既定コネクタ（defaultConnectorId）**：1-1 で作ったコネクタ ID。
   - **scheduler パラメータ（schedulerParams）**：**ここに「何を取るか」を入れます**
     （早見表の必須 `schedulerParams`）。例：Slack なら `channelId = C01ABCD2345`。
   - **スケジューラ有効（schedulerEnabled）**：定期取り込みする場合 ON。
     （委譲プロファイルでの scheduler は別途 admin 設定が必要。手動取り込みは OFF のままで可）。
3. 保存。

### 1-3. 手動で 1 回取り込んでテスト（手動インポートタブ）

- **管理 → 連携設定 → 手動インポート** タブで、プロファイルを選んで実行すると、
  schedulerParams で指定した対象を 1 回取り込みます。まずはここで疎通確認するのが確実です。

### 1-4. REST API で設定する場合（任意）

UI を使わずスクリプトで登録する場合の最小例（CSRF 回避のため
`X-Requested-With` を付ける）：

```bash
BASE=http://localhost:8080/core    # or https://avenue.aegif.jp:11469/core (-k)

# コネクタ作成
# credentialRef はキー。実トークンは env INGEST_SLACK_SALES_TOKEN 等で provision する (§2)
curl -s $CURLK -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  -H "Content-Type: application/json" \
  -d '{
        "connectorId":"slack-sales",
        "displayName":"営業 Slack",
        "sourceArchetype":"CHAT_CONTEXT",
        "sourceSystem":"slack",
        "credentialRef":"ingest.slack.sales.token",
        "enabled":true
      }' \
  "$BASE/api/v1/admin/connectors"

# インポートプロファイル作成（schedulerParams に channelId）
curl -s $CURLK -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  -H "Content-Type: application/json" \
  -d '{
        "targetFolderId":"<取込先フォルダID>",
        "defaultConnectorId":"slack-sales",
        "allowedConnectorIds":["slack-sales"],
        "sourceArchetype":"CHAT_CONTEXT",
        "schedulerParams":{"channelId":"C01ABCD2345"},
        "enabled":true
      }' \
  "$BASE/api/v1/admin/import-profiles"
```

---

## 2. 認証情報（credentialRef）の考え方

**`credentialRef` には秘密情報そのものを入れません。** `credentialRef` は
「実トークンを解決するためのキー」で、取り込み実行時に全アダプタ共通の
`FetchSupport.resolvePassword` → `PropertyManager.readValue(credentialRef)` が
次の優先順で実トークンを解決します：

1. **JVM システムプロパティ**：`-Dingest.slack.sites.token=xoxb-...`
2. **環境変数**：キーを大文字化し `.` を `_` に置換した名前
   （例 `ingest.slack.sites.token` → `INGEST_SLACK_SITES_TOKEN`）
3. **動的設定**（`nemaki_conf` システム設定 DB。書き込みは super-user 権限が必要）
4. **nemakiware.properties**

どこにも見つからない場合、取り込みは `No token for <サービス> connector`
エラーで失敗します。**トークン文字列を credentialRef に直接入れても動きません**
（キーとして検索されて null になるだけ）。

> **例外**: `webhookSecret` は credentialRef と異なり **リテラル値** として
> コネクタに保存され、そのまま署名検証に使われます（キー解決されない）。

### 推奨 provisioning: Docker secrets env_file

`docker/secrets/ingest-connectors.env`（gitignore 済み）にトークンを置き、
`docker-compose-simple.yml` の core サービス `env_file` で読み込みます
（テンプレート: `docker/secrets/ingest-connectors.env.example`）：

```bash
# docker/secrets/ingest-connectors.env
INGEST_SLACK_SITES_TOKEN=xoxb-...
INGEST_NOTION_SITES_TOKEN=ntn_...
```

```yaml
# docker-compose-simple.yml (core サービス)
    env_file:
      - path: ./secrets/ingest-connectors.env
        required: false
```

環境変数は JVM 起動時に読まれるため、**変更後は core コンテナの再作成が必要**です
（`docker compose -f docker-compose-simple.yml up -d --build --force-recreate core`。
`restart` では env_file の変更は反映されません）。

### 解決された実トークンの使われ方（サービス別）

- **Bearer トークン系**（Slack/Teams/Mattermost/Gmail/M365/Notion/Salesforce/Box/Dropbox）：
  `Authorization: Bearer {解決されたトークン}` として送信。
- **Chatwork**：`X-ChatWorkToken: {解決されたトークン}`（Bearer ではない）。
- **IMAP**：解決された値はパスワード、`tenantId` がログインユーザ名（メールアドレス）。

**OAuth2 系の注意**：Slack の Bot トークン・Notion の Integration トークンは
基本的に長期有効ですが、Microsoft Graph / Gmail / Salesforce / Box / Dropbox の
「アクセストークン」は**短命（数十分〜数時間）**です。env_file 方式では失効の
たびに「トークン再発行 → env 更新 → core 再作成」が必要になるため、本ガイドの
トークンは「まず疎通確認・手動取り込みを試す」ためのものとして扱い、継続運用では
リフレッシュトークンからの更新運用（または長期トークンの発行）を別途検討してください。

---

## 3. サービス別手順

各サービスとも「**A. SaaS 側で認証情報を取得** → **B. NemakiWare 側でコネクタ定義**
→ **C. プロファイルで取得対象を指定**」の順です。

---

### 3-1. Slack（`slack` / CHAT_CONTEXT）

**A. Bot Token を取得**
1. <https://api.slack.com/apps> で「Create New App」→ From scratch、ワークスペースを選択。
2. 左メニュー **OAuth & Permissions** → **Scopes → Bot Token Scopes** に以下を追加：
   - `channels:history`（公開チャンネルのメッセージ読取）
   - `groups:history`（プライベートチャンネル。必要時）
   - `files:read`（添付ファイル取得）
   - `channels:read` / `groups:read`（チャンネル情報）
3. 同ページ上部 **Install to Workspace** → 許可。
4. 表示される **Bot User OAuth Token**（`xoxb-` で始まる）をコピー。
5. Bot を対象チャンネルに招待（チャンネルで `/invite @アプリ名`）。
6. 取得したいチャンネルの **Channel ID** を控える（チャンネル名右クリック →
   「リンクをコピー」の末尾 `C0…`、または details の最下部）。

**B. コネクタ定義**
- 種別 `CHAT_CONTEXT`、接続先 `slack`、credentialRef = キー
  （例 `ingest.slack.sales.token`。実トークン `xoxb-…` は §2 で provision）。
- endpoint は空（既定 `https://slack.com/api`）。
- Webhook を使う場合は webhookSecret に Slack の **Signing Secret**
  （アプリの Basic Information → App Credentials）を設定。

**C. プロファイル**
- `schedulerParams`：`channelId = C01ABCD2345`（必須）、任意 `limit`。

**（任意）Webhook 設定**
- Slack アプリの **Event Subscriptions** を ON。
- Request URL に `https://<公開ホスト>/core/api/v1/ingest-webhook/<コネクタID>` を設定
  （Slack の URL 検証 `url_verification` に NemakiWare が自動応答します）。
- **Subscribe to bot events** に `message.channels`（必要に応じ `file_shared`）を追加。

---

### 3-2. Microsoft Teams（`teams` / CHAT_CONTEXT）

**A. Graph アクセストークンを取得**
1. <https://entra.microsoft.com>（Azure AD / Entra ID）→ **アプリの登録** → 新規登録。
2. **API のアクセス許可** で Microsoft Graph に以下を付与し、管理者の同意：
   - `ChannelMessage.Read.All`
   - `Files.Read.All`
   （アプリ権限＝クライアント資格情報フローを推奨。委任権限なら対象ユーザのサインインが必要）
3. **証明書とシークレット** でクライアントシークレットを作成。
4. トークンエンドポイントからアクセストークンを取得（client credentials flow）：
   ```bash
   curl -s -X POST \
     "https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token" \
     -d "client_id=<app-id>" \
     -d "client_secret=<secret>" \
     -d "scope=https://graph.microsoft.com/.default" \
     -d "grant_type=client_credentials" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
   ```
5. **Team ID** と **Channel ID** を控える（Teams のチャンネル「リンクを取得」URL の
   `groupId=`（=teamId）と `channel/…`、または Graph で `/teams` `/channels` を照会）。

**B. コネクタ定義**
- 種別 `CHAT_CONTEXT`、接続先 `teams`、credentialRef = キー
  （実値は取得したアクセストークン、§2 で provision）。
- endpoint は空（既定 Graph v1.0）。Webhook を使う場合 webhookSecret に Graph
  サブスクリプションの `clientState` を設定。

**C. プロファイル**
- `schedulerParams`：`teamId` と `channelId`（両方必須）、任意 `limit`。

**（任意）Webhook**
- Graph の change notification subscription を作成し、notificationUrl に
  `https://<公開ホスト>/core/api/v1/ingest-webhook/<コネクタID>` を指定。
  NemakiWare は `validationToken` ハンドシェイクに自動応答します。

---

### 3-3. Mattermost（`mattermost` / CHAT_CONTEXT）

**A. トークンを取得**
1. 取り込み用ユーザ（または Bot アカウント）でログイン。
2. **アカウント設定 → セキュリティ → Personal Access Tokens** でトークン発行
   （管理者が Personal Access Token を有効化している必要あり）。
   または **System Console → Integrations → Bot Accounts** で Bot を作成しトークン取得。
3. 対象 **Channel ID** を控える（チャンネル → View Info、または
   `GET /api/v4/teams/{team}/channels/name/{name}`）。

**B. コネクタ定義**
- 種別 `CHAT_CONTEXT`、接続先 `mattermost`。
- **endpoint は必須**：Mattermost サーバの base URL（例 `https://mm.example.com`）。
- credentialRef = キー（実値は Personal Access / Bot Token、§2 で provision）。

**C. プロファイル**
- `schedulerParams`：`channelId`（必須）、任意 `limit`。

Webhook はこのコネクタでは未対応（ポーリングのみ）。

---

### 3-4. Chatwork（`chatwork` / CHAT_CONTEXT）

**A. API Token を取得**
1. Chatwork にログイン → 右上アバター → **サービス連携 → API Token**。
2. パスワードを入れてトークンを表示・コピー。
3. 対象 **Room ID** を控える（ルームを開いた URL の `#!rid` の数字、または
   `GET /v2/rooms`）。

**B. コネクタ定義**
- 種別 `CHAT_CONTEXT`、接続先 `chatwork`、credentialRef = キー
  （実値は API Token、§2 で provision）。
- endpoint は空（既定 `https://api.chatwork.com/v2`）。
- **認証ヘッダは `X-ChatWorkToken`**（Bearer ではない）で NemakiWare が自動送信。

**C. プロファイル**
- `schedulerParams`：`roomId`（必須）、任意 `limit`。
- 注意：Chatwork API は 1 回あたり最大 100 件・タイムスタンプ絞り込み不可。
  メッセージ流量が多いルームは取りこぼし警告がログに出ます（短い間隔での取り込みを推奨）。

---

### 3-5. IMAP メール（`imap` / MESSAGE_CONTEXT）

**A. 接続情報を用意**
- IMAP サーバの **ホスト名とポート**（例 `imap.example.com:993`）。
- ログイン用 **メールアドレス** と **パスワード**。
  - Gmail/Microsoft の IMAP を使う場合は「アプリパスワード」や OAuth2(XOAUTH2) が必要な
    ことが多いです（通常パスワードでは拒否される）。OAuth2 を使うときは authType を
    `oauth2`、credentialRef に OAuth トークンを入れます。

**B. コネクタ定義**
- 種別 `MESSAGE_CONTEXT`、接続先 `imap`。
- **endpoint は必須**：`host:port`（ポート省略時は IMAPS 993）。
- **tenantId は必須**：メールアドレス（ログインユーザ名）。
- credentialRef = キー（実値はパスワード、または OAuth2 トークン。§2 で provision）。
- authType：`password`（既定）または `oauth2`。

**C. プロファイル**
- `schedulerParams`：`mailbox`（フォルダ名、既定 `INBOX`）、任意 `limit`。

Webhook 非対応（ポーリング。アダプタ内部では IDLE による継続監視に対応）。

---

### 3-6. Gmail（`gmail_mail` / MESSAGE_CONTEXT）

**A. OAuth2 アクセストークン（またはサービスアカウント）を取得**
1. Google Cloud Console でプロジェクト作成 → **Gmail API** を有効化。
2. OAuth クライアント（または ドメイン全体委任のサービスアカウント）を作成。
3. スコープ `https://www.googleapis.com/auth/gmail.readonly` で
   アクセストークンを取得。
   - 個別ユーザ：OAuth 同意フローで取得。
   - 組織運用：サービスアカウント + ドメイン全体委任で対象ユーザを impersonate。

**B. コネクタ定義**
- 種別 `MESSAGE_CONTEXT`、接続先 `gmail_mail`、credentialRef = キー
  （実値はアクセストークン、§2 で provision）。
- endpoint 不要。

**C. プロファイル**
- `schedulerParams`：`query`（Gmail 検索式、既定 `in:inbox is:unread`。
  例 `newer_than:1d`, `label:invoices`）、任意 `limit`。

Webhook 非対応。アクセストークンは短命なので継続運用ではリフレッシュ運用が必要。

---

### 3-7. Microsoft 365 メール（`m365_mail` / MESSAGE_CONTEXT）

**A. Graph アクセストークンを取得**
- Teams（§3-2 A）と同じ要領で Entra ID にアプリ登録。権限は
  - `Mail.Read`（委任）または `Mail.Read`（アプリ権限・クライアント資格情報）。
- アクセストークンを取得。

**B. コネクタ定義**
- 種別 `MESSAGE_CONTEXT`、接続先 `m365_mail`、credentialRef = キー
  （実値はアクセストークン、§2 で provision）。
- endpoint は空（既定 Graph v1.0）。Webhook 利用時は webhookSecret に Graph `clientState`。

**C. プロファイル**
- `schedulerParams`：`folderId`（メールフォルダ ID または `inbox` 等の既知名、既定 `inbox`）。
  クライアント資格情報フローで特定ユーザのメールを読む場合は `userId`
  （`user@contoso.com` または UPN）も指定（未指定なら委任認証の `/me`）。任意 `limit`。

**（任意）Webhook**
- Graph subscription の notificationUrl に
  `https://<公開ホスト>/core/api/v1/ingest-webhook/<コネクタID>`。
  resource は `users/{userId}/mailFolders('{folderId}')/messages` 形式。

---

### 3-8. Notion（`notion` / COMPOUND_NOTE）

**A. Integration Token を取得**
1. <https://www.notion.so/my-integrations> → 「New integration」。
2. 種別 Internal、ワークスペースを選び作成。
3. **Internal Integration Token**（`secret_` で始まる）をコピー。
4. 取り込みたいページ/データベースを開き、右上「…」→ **Connections → 連携を追加**
   で、作成した integration を共有（共有しないと API から見えません）。

**B. コネクタ定義**
- 種別 `COMPOUND_NOTE`、接続先 `notion`、credentialRef = キー
  （実値は Integration Token、§2 で provision）。
- endpoint は空（既定 API、Notion-Version `2022-06-28` を自動送信）。

**C. プロファイル**
- `schedulerParams`：`query`（任意。Notion search のキーワード。未指定なら共有された
  全ページが対象）、任意 `limit`。

Webhook 非対応。

---

### 3-9. Salesforce（`salesforce` / BUSINESS_RECORD）

**A. OAuth2 アクセストークンを取得**
1. Salesforce **設定 → アプリケーション → アプリケーションマネージャ** で
   「接続アプリケーション」を新規作成（OAuth 有効化、スコープに `api` を含める）。
2. OAuth フロー（JWT bearer / web server / username-password 等）でアクセストークンと
   **インスタンス URL**（例 `https://myorg.my.salesforce.com`）を取得。

**B. コネクタ定義**
- 種別 `BUSINESS_RECORD`、接続先 `salesforce`。
- **endpoint は必須**：インスタンス URL。
- credentialRef = キー（実値はアクセストークン、§2 で provision）。

**C. プロファイル**
- `schedulerParams`：`soql`（取得対象の SELECT クエリ。例
  `SELECT Id,Name,LastModifiedDate FROM Account`。未指定なら Account の既定テンプレ）。任意 `limit`。
  - 安全のため `DELETE`/`UPDATE`/`INSERT`、`--` コメント、`;` を含む SOQL は拒否されます。
  - `LastModifiedDate` での増分取得が自動で WHERE に注入されます（SELECT に
    `LastModifiedDate` を含めると確実）。

Webhook 非対応。

---

### 3-10. Box（`box` / FILE_SHARE）

**A. OAuth2 アクセストークンを取得**
1. <https://app.box.com/developers/console> でアプリ作成（OAuth 2.0、または JWT/CCG）。
2. スコープに「すべてのファイルとフォルダの読み取り」を付与。
3. アクセストークンを取得。
4. 取り込みたい **Folder ID** を控える（Box でフォルダを開いた URL の末尾数字。
   ルートは `0`）。

**B. コネクタ定義**
- 種別 `FILE_SHARE`、接続先 `box`、credentialRef = キー
  （実値はアクセストークン、§2 で provision）。
- endpoint 不要（API は `https://api.box.com/2.0` 固定）。

**C. プロファイル**
- `schedulerParams`：`folderId`（既定 `0`=ルート）、任意 `limit`。

Webhook 非対応。

---

### 3-11. Dropbox（`dropbox` / FILE_SHARE）

**A. OAuth2 アクセストークンを取得**
1. <https://www.dropbox.com/developers/apps> → Create app（Scoped access）。
2. **Permissions** に `files.metadata.read` と `files.content.read` を付与。
3. **Settings → OAuth 2 → Generated access token** で短期トークンを発行
   （継続運用はリフレッシュトークン運用を推奨）。

**B. コネクタ定義**
- 種別 `FILE_SHARE`、接続先 `dropbox`、credentialRef = キー
  （実値はアクセストークン、§2 で provision）。
- endpoint 不要（API は `https://api.dropboxapi.com/2` / content 固定）。

**C. プロファイル**
- `schedulerParams`：`folderPath`（既定 空文字＝ルート。例 `/Documents`）、任意 `limit`。

Webhook 非対応。

---

## 4. Webhook（プッシュ取り込み）まとめ

Webhook 対応は **Slack / Teams / M365 メール / Chatwork（汎用）** のみ。
共通の受信エンドポイントは：

```
POST https://<公開ホスト>/core/api/v1/ingest-webhook/<コネクタID>
```

- **Slack**：`X-Slack-Signature` + `X-Slack-Request-Timestamp` を webhookSecret
  （Signing Secret）で検証。`url_verification` challenge に自動応答。
  `event.channel` が profile の `schedulerParams.channelId` と一致するプロファイルを発火。
- **Teams / M365 メール**：Graph change notification。`validationToken` に自動応答、
  本文は webhookSecret（`clientState`）で HMAC 検証。resource からスコープ
  （Teams: teamId+channelId / M365: userId+folderId）を抽出して一致プロファイルを発火。
- **Chatwork**：webhookSecret 署名検証（汎用 JSON ハンドラ）。
- レート制限：コネクタあたり 100 req/分（署名検証後にカウント）。

> Webhook を使う場合も、対応する **プロファイルの schedulerParams** に該当スコープ
> （channelId / teamId+channelId / folderId 等）が入っている必要があります。
> イベント受信時に一致するプロファイルだけが取り込みを実行します。

---

## 5. トラブルシュート早見

| 症状 | 確認ポイント |
|---|---|
| 取り込みが 0 件 | プロファイルの `schedulerParams` が早見表の必須キーを満たしているか／コネクタ ID が一致しているか |
| `No token for <サービス> connector` | credentialRef キーが実トークンに解決できていない（§2）。env 変数名（キーを大文字化＋`.`→`_`）、env_file の記入、core コンテナ再作成（restart 不可）を確認。credentialRef にトークン文字列を直接入れていないか |
| 認証エラー | provision した実トークンの形式（Slack=`xoxb-`、Chatwork は Bearer ではなく X-ChatWorkToken）／OAuth トークンの失効 |
| Mattermost/IMAP/Salesforce で接続不可 | `endpoint`（必須）の設定漏れ。IMAP は `tenantId`（メールアドレス）も必須 |
| Webhook が発火しない | 対応サービスか（Slack/Teams/M365/Chatwork のみ）／webhookSecret 設定／受信 URL／profile の scope 一致 |
| 種別と接続先がちぐはぐ | §0 の早見表どおりの `sourceArchetype` × `sourceSystem` 組み合わせか |
