# NemakiWare Webhook機能 仕様・設計提案書

**作成日**: 2026-01-27  
**ベースブランチ**: origin/feature/rag-vector-search  
**ステータス**: 設計提案（レビュー待ち）

---

## 1. 概要

### 1.1 目的

NemakiWareにWebhook機能を追加し、フォルダやドキュメントに対する編集、ファイル追加・削除などのイベントが発生した際に、外部システムへHTTP通知を送信できる仕組みを実現する。

### 1.2 背景

CMIS 1.1仕様にはChange Log（変更ログ）機能が存在するが、これはポーリングベースの仕組みである。Webhookはプッシュ型の通知であり、リアルタイム性が求められるユースケース（外部システム連携、ワークフロー自動化など）に適している。

### 1.3 CMIS仕様との関係

CMIS 1.1仕様にはWebhook/イベント通知の直接的な定義は存在しない。本機能はNemakiWare独自の拡張として実装する。ただし、以下の点でCMIS仕様との整合性を維持する：

- **セカンダリタイプ**: Webhook機能は`nemaki:webhookable`セカンダリタイプとして実装（CMIS標準機構を活用）
- **プロパティ定義**: CMIS標準のプロパティ定義形式を使用
- **イベントタイプ**: CMISのChangeType（CREATED, UPDATED, DELETED, SECURITY）に準拠

### 1.4 設計方針

**Webhook機能**: セカンダリタイプ（`nemaki:webhookable`）として実装。多数のオブジェクトにはWebhookは不要であり、必要なオブジェクトにのみ選択的に適用できる方式を採用。

**RSS機能**: プライマリタイプ（`nemaki:folder`/`nemaki:document`）に関連付け。RSSフィードは全オブジェクトが対象となりうるため、APIベースで任意のオブジェクトに対してフィードを生成可能。

---

## 2. タイプ定義

### 2.1 nemaki:webhookable（セカンダリタイプ）

Webhook機能を提供するセカンダリタイプ。任意の`cmis:folder`または`cmis:document`に追加することで、そのオブジェクトにWebhook機能を付与できます。

```
タイプID: nemaki:webhookable
ベースタイプ: cmis:secondary
表示名: Webhookable
説明: Webhook通知機能を有効にするセカンダリタイプ
```

**プロパティ**:

| プロパティID | 表示名 | 型 | カーディナリティ | 必須 | 説明 |
|-------------|--------|-----|-----------------|------|------|
| nemaki:webhookConfigs | Webhook設定 | String | single | No | 複数Webhook設定を格納するJSON配列（詳細は2.4参照） |
| nemaki:webhookMaxDepth | 最大監視深度 | Integer | single | No | 子孫を監視する最大階層数（デフォルト: アプリ設定値、フォルダのみ有効） |

**セカンダリタイプ採用の理由**:

1. **選択的適用**: 多数のオブジェクトにはWebhookは不要。必要なオブジェクトにのみセカンダリタイプを追加することで、効率的な運用が可能
2. **既存オブジェクトへの適用**: 既存の`cmis:folder`/`cmis:document`にもWebhook機能を追加可能
3. **柔軟性**: カスタムタイプ（例: `myapp:contract`）にもWebhook機能を追加可能
4. **CMIS準拠**: CMISのセカンダリタイプ機構を活用した標準的な拡張方法

**使用例**:

```
// 既存フォルダにWebhook機能を追加
POST /browser/{repositoryId}/root?objectId={folderId}&cmisaction=update
Content-Type: application/x-www-form-urlencoded

cmis:secondaryObjectTypeIds=nemaki:webhookable
nemaki:webhookConfigs=[{"id":"webhook-1","url":"https://example.com/webhook",...}]
```

### 2.2 nemaki:folder / nemaki:document（RSS機能用）

RSS機能は全オブジェクトが対象となりうるため、プライマリタイプとして実装します。

```
タイプID: nemaki:folder
親タイプ: cmis:folder
表示名: NemakiWare Folder
説明: RSS機能を持つ拡張フォルダタイプ
```

```
タイプID: nemaki:document
親タイプ: cmis:document
表示名: NemakiWare Document
説明: RSS機能を持つ拡張ドキュメントタイプ
```

**注意**: RSS機能はAPIベースで提供されるため、これらのタイプに追加プロパティは不要です。RSSフィードはリポジトリ内の任意のフォルダ/ドキュメントに対して生成可能です（セクション7参照）。

### 2.3 監視イベントタイプ

`nemaki:webhookConfigs`内の各Webhook設定で指定可能なイベントタイプ：

| イベントタイプ | 説明 | 対応CMISイベント |
|---------------|------|-----------------|
| `CREATED` | オブジェクト作成 | ChangeType.CREATED |
| `UPDATED` | プロパティ更新 | ChangeType.UPDATED |
| `DELETED` | オブジェクト削除 | ChangeType.DELETED |
| `SECURITY` | ACL変更 | ChangeType.SECURITY |
| `CONTENT_UPDATED` | コンテンツストリーム更新 | ChangeType.UPDATED (content) |
| `CHECKED_OUT` | チェックアウト | - |
| `CHECKED_IN` | チェックイン | - |
| `VERSION_CREATED` | 新バージョン作成 | ChangeType.CREATED (version) |
| `MOVED` | 移動 | ChangeType.UPDATED (parent) |
| `CHILD_CREATED` | 子要素作成（フォルダのみ） | - |
| `CHILD_DELETED` | 子要素削除（フォルダのみ） | - |
| `CHILD_UPDATED` | 子要素更新（フォルダのみ） | - |

### 2.4 nemaki:webhookConfigs プロパティ仕様

`nemaki:webhookConfigs`プロパティは、1つのオブジェクトに複数のWebhook設定を格納するJSON配列です。各設定は独立したURL・イベント・認証情報を持ちます。

**JSON形式**:

```json
[
  {
    "id": "webhook-1",
    "enabled": true,
    "url": "https://example.com/webhooks/content",
    "events": ["CREATED", "UPDATED", "CONTENT_UPDATED"],
    "authType": "bearer",
    "authCredential": "encrypted-token",
    "secret": "encrypted-hmac-secret",
    "headers": {"X-Custom-Header": "value1"},
    "includeChildren": true,
    "maxDepth": 5,
    "retryCount": 3
  },
  {
    "id": "webhook-2",
    "enabled": true,
    "url": "https://security-audit.example.com/acl-changes",
    "events": ["SECURITY"],
    "authType": "apikey",
    "authCredential": "X-API-Key:encrypted-key",
    "secret": null,
    "headers": {},
    "includeChildren": false,
    "maxDepth": null,
    "retryCount": 5
  }
]
```

**各フィールドの説明**:

| フィールド | 型 | 必須 | 説明 |
|-----------|-----|------|------|
| `id` | String | Yes | Webhook設定の一意識別子（UUID推奨） |
| `enabled` | Boolean | Yes | この設定の有効/無効 |
| `url` | String | Yes | 通知先URL（httpsのみ、開発環境除く） |
| `events` | String[] | Yes | 監視するイベントタイプのリスト |
| `authType` | String | No | 認証方式（none/basic/bearer/apikey） |
| `authCredential` | String | No | 認証用クレデンシャル（暗号化保存） |
| `secret` | String | No | HMAC-SHA256署名用シークレット（暗号化保存） |
| `headers` | Object | No | カスタムHTTPヘッダー |
| `includeChildren` | Boolean | No | 子孫のイベントも通知（フォルダのみ） |
| `maxDepth` | Integer | No | 子孫監視の最大深度（null=アプリ設定値） |
| `retryCount` | Integer | No | リトライ回数（null=アプリ設定値） |

**動作仕様**:

1. イベント発生時、`nemaki:webhookConfigs`配列を走査
2. `enabled=true`かつ`events`に該当イベントを含む設定を全て抽出
3. 抽出された各設定に対して独立してWebhook配信を実行
4. 同一イベントが複数の設定にマッチする場合、**全ての設定に配信**（最初のみではない）

### 2.5 HTTPリクエストのセキュリティオプション

Webhook配信時に使用可能な認証・セキュリティ方式：

| 方式 | authType値 | authCredential形式 | HTTPヘッダー例 |
|------|-----------|-------------------|---------------|
| なし | `none` | - | - |
| Basic認証 | `basic` | `username:password` | `Authorization: Basic base64(user:pass)` |
| Bearer Token | `bearer` | `token-value` | `Authorization: Bearer token-value` |
| API Key | `apikey` | `Header-Name:key-value` | `Header-Name: key-value` |
| HMAC署名 | - | `nemaki:webhookSecret`使用 | `X-NemakiWare-Signature: sha256=...` |

**追加のセキュリティ機能**:

1. **タイムスタンプ検証**: `X-NemakiWare-Timestamp`ヘッダーでリプレイ攻撃を防止
2. **配信ID**: `X-NemakiWare-Delivery`ヘッダーで重複配信を検知
3. **TLS必須**: 本番環境では`https://`のみ許可（開発環境は`http://localhost`も可）

### 2.5.1 URL検証とDNSリバインディング対策

**URL検証フロー**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    URL検証フロー                                  │
├─────────────────────────────────────────────────────────────────┤
│  1. スキーム検証: https:// のみ許可（開発環境除く）              │
│  2. ホスト名検証: allowlist/denylist チェック                    │
│  3. DNS解決: ホスト名 → IPアドレス                               │
│  4. IP検証: プライベートIP/ループバック/リンクローカル拒否       │
│  5. 接続: 検証済みIPに直接接続（Host ヘッダーは元のホスト名）    │
└─────────────────────────────────────────────────────────────────┘
```

**拒否するIPアドレス範囲**:

| 範囲 | 説明 |
|------|------|
| `127.0.0.0/8` | ループバック |
| `10.0.0.0/8` | プライベート（クラスA） |
| `172.16.0.0/12` | プライベート（クラスB） |
| `192.168.0.0/16` | プライベート（クラスC） |
| `169.254.0.0/16` | リンクローカル |
| `::1/128` | IPv6ループバック |
| `fc00::/7` | IPv6ユニークローカル |
| `fe80::/10` | IPv6リンクローカル |

**DNSリバインディング対策**:

```java
public class WebhookUrlValidator {
    
    /**
     * DNS解決後のIPアドレスを検証し、プライベートIPなら拒否
     */
    public void validateUrl(String webhookUrl) throws WebhookSecurityException {
        URL url = new URL(webhookUrl);
        
        // 1. スキーム検証
        if (!isProductionMode() && url.getProtocol().equals("http") 
            && url.getHost().equals("localhost")) {
            // 開発環境のみ許可
        } else if (!url.getProtocol().equals("https")) {
            throw new WebhookSecurityException("HTTPS required");
        }
        
        // 2. Allowlist/Denylist検証
        if (!isHostAllowed(url.getHost())) {
            throw new WebhookSecurityException("Host not allowed");
        }
        
        // 3. DNS解決とIP検証
        InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
        for (InetAddress addr : addresses) {
            if (isPrivateOrReserved(addr)) {
                throw new WebhookSecurityException(
                    "Private/reserved IP not allowed: " + addr.getHostAddress());
            }
        }
    }
    
    private boolean isPrivateOrReserved(InetAddress addr) {
        return addr.isLoopbackAddress() 
            || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress()
            || addr.isAnyLocalAddress();
    }
}
```

**設定オプション**:

```properties
# URL検証設定
webhook.url.validation.enabled=true

# Allowlist（空の場合は全て許可、設定時は指定ドメインのみ許可）
webhook.url.allowlist=example.com,*.trusted-domain.com

# Denylist（常に拒否するドメイン）
webhook.url.denylist=internal.company.com,*.local

# プライベートIP拒否（本番環境ではtrue推奨）
webhook.url.deny.private.ip=true

# DNS解決タイムアウト（ミリ秒）
webhook.url.dns.timeout.ms=5000
```

### 2.5.2 認証情報の暗号化と鍵管理

**暗号化対象**:

| プロパティ | 暗号化 | 説明 |
|-----------|--------|------|
| `authCredential` | **必須** | Basic認証パスワード、Bearer Token、API Key |
| `secret` | **必須** | HMAC署名用シークレット |

**暗号化方式**:

```
アルゴリズム: AES-256-GCM
鍵導出: PBKDF2-HMAC-SHA256 (100,000 iterations)
IV: 12バイト（ランダム生成、暗号文に付加）
認証タグ: 16バイト
```

**鍵管理オプション**:

| 方式 | 設定 | 用途 |
|------|------|------|
| 環境変数 | `NEMAKI_WEBHOOK_ENCRYPTION_KEY` | シンプルな運用 |
| 設定ファイル | `webhook.encryption.key.file=/path/to/keyfile` | ファイルベース |
| KMS連携 | `webhook.encryption.kms.provider=aws` | エンタープライズ |

**設定例**:

```properties
# 暗号化設定
webhook.encryption.enabled=true

# 鍵ソース（env/file/kms）
webhook.encryption.key.source=env

# 環境変数名（key.source=envの場合）
webhook.encryption.key.env.name=NEMAKI_WEBHOOK_ENCRYPTION_KEY

# ファイルパス（key.source=fileの場合）
webhook.encryption.key.file=/etc/nemakiware/webhook-key

# KMS設定（key.source=kmsの場合）
webhook.encryption.kms.provider=aws
webhook.encryption.kms.key.id=alias/nemakiware-webhook
```

**鍵ローテーション**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    鍵ローテーションフロー                         │
├─────────────────────────────────────────────────────────────────┤
│  1. 新しい鍵を生成/取得                                          │
│  2. 新鍵を「アクティブ」、旧鍵を「復号のみ」に設定               │
│  3. 新規暗号化は新鍵を使用                                       │
│  4. 復号時は鍵IDを確認し、適切な鍵を選択                         │
│  5. バックグラウンドで旧鍵で暗号化されたデータを新鍵で再暗号化   │
│  6. 全データ移行完了後、旧鍵を無効化                             │
└─────────────────────────────────────────────────────────────────┘
```

**暗号化データ形式**:

```json
{
  "v": 1,                          // 暗号化フォーマットバージョン
  "kid": "key-2026-01",            // 鍵ID（ローテーション用）
  "iv": "base64-encoded-iv",       // 初期化ベクトル
  "ct": "base64-encoded-ciphertext", // 暗号文
  "tag": "base64-encoded-auth-tag"  // 認証タグ
}
```

---

## 2.6 冪等性・順序保証・再送ポリシー

### 2.6.1 配信IDの仕様

**deliveryId**は配信の一意識別子であり、以下の仕様に従います：

| 項目 | 仕様 |
|------|------|
| 形式 | UUID v4 |
| 生成タイミング | 初回配信試行時に生成 |
| 再送時の扱い | **同一deliveryIdを維持**（受信側の冪等処理を容易にするため） |
| ヘッダー | `X-NemakiWare-Delivery: {deliveryId}` |

**受信側での冪等処理例**:

```python
# 受信側での重複排除
def handle_webhook(request):
    delivery_id = request.headers.get('X-NemakiWare-Delivery')
    
    # 既に処理済みなら早期リターン
    if is_already_processed(delivery_id):
        return Response(status=200)  # 成功として応答
    
    # 処理実行
    process_event(request.json)
    mark_as_processed(delivery_id)
    return Response(status=200)
```

### 2.6.2 順序保証

**仕様**: **順序保証なし（best-effort）**

| 項目 | 説明 |
|------|------|
| 同一オブジェクトのイベント | 順序保証なし（並列配信のため） |
| 異なるオブジェクトのイベント | 順序保証なし |
| 理由 | 非同期スレッドプールによる並列配信を優先 |

**受信側での順序処理**:

順序が重要な場合、受信側で`changeToken`を使用して処理順序を制御できます：

```json
{
  "object": {
    "changeToken": "1706365800000"  // タイムスタンプベースのトークン
  }
}
```

**将来の拡張オプション**（Phase 2以降で検討）:
- 同一オブジェクトの順序保証オプション（`webhook.ordering.per.object=true`）
- 順序付きキューの導入

### 2.6.3 再送ポリシー

**再送条件**:

| 条件 | 再送 |
|------|------|
| HTTP 2xx | 成功、再送なし |
| HTTP 4xx（400, 401, 403, 404） | 失敗、**再送なし**（クライアントエラー） |
| HTTP 429（Rate Limit） | 失敗、**再送あり**（Retry-Afterヘッダー考慮） |
| HTTP 5xx | 失敗、**再送あり** |
| タイムアウト | 失敗、**再送あり** |
| 接続エラー | 失敗、**再送あり** |

**再送パラメータ**:

```properties
# デフォルトリトライ回数
webhook.retry.default.count=3

# 最大リトライ回数（オブジェクト設定でもこれを超えられない）
webhook.retry.max.count=10

# 指数バックオフの基底時間（ミリ秒）
webhook.retry.backoff.base.ms=1000

# 最大バックオフ時間（ミリ秒）
webhook.retry.backoff.max.ms=300000
```

**バックオフ計算**:

```
delay = min(base * 2^attempt, max_delay)

例（base=1000ms, max=300000ms）:
- 1回目リトライ: 2秒後
- 2回目リトライ: 4秒後
- 3回目リトライ: 8秒後
- ...
- 9回目リトライ: 300秒後（上限）
```

### 2.6.4 changeTokenとの関係

`changeToken`はCMIS Change Logのトークンであり、Webhook配信でも以下の用途で使用します：

| 用途 | 説明 |
|------|------|
| イベント識別 | ペイロードの`object.changeToken`で変更を一意に識別 |
| 順序判定 | 受信側で古いイベントを無視する判定に使用可能 |
| 冪等キー | `deliveryId`と組み合わせて冪等処理に使用可能 |

**注意**: `changeToken`はオブジェクトの変更毎に更新されるため、同一オブジェクトの複数イベントを区別できます。ただし、`deliveryId`が冪等処理の主キーであり、`changeToken`は補助的な役割です。

#### 2.6.4.1 changeToken比較ルール（受信側ガイダンス）

Webhook受信側で順序判定を行う際の`changeToken`比較ルールを以下に定めます：

**changeTokenの形式保証**:

| 項目 | 保証内容 |
|------|----------|
| 形式 | 数値文字列（例: `"1706367004123"`） |
| 単調増加 | 同一オブジェクトに対して、後の変更は必ず大きい値を持つ |
| 比較方法 | **数値として比較可能**（文字列比較ではなく数値比較を推奨） |

**受信側の推奨実装**:

```java
// 受信側での順序判定例
public boolean shouldProcess(WebhookPayload payload) {
    String objectId = payload.getObject().getId();
    long newToken = Long.parseLong(payload.getObject().getChangeToken());
    
    // 保存済みの最新changeTokenと比較
    Long lastToken = lastProcessedTokens.get(objectId);
    if (lastToken != null && newToken <= lastToken) {
        // 古いイベント（または重複）→スキップ
        log.info("Skipping stale event: objectId={}, token={}, lastToken={}", 
                 objectId, newToken, lastToken);
        return false;
    }
    
    // 処理後にトークンを更新
    lastProcessedTokens.put(objectId, newToken);
    return true;
}
```

**優先順位**:

| 判定基準 | 優先度 | 説明 |
|----------|--------|------|
| `deliveryId` | 1（最優先） | 同一deliveryIdは重複配信→スキップ |
| `changeToken` | 2 | 同一オブジェクトの古いイベント判定 |
| `timestamp` | 3（参考） | changeTokenが使えない場合の代替 |

**重要**: `timestamp`よりも`changeToken`を優先してください。`timestamp`はサーバー時刻に依存し、クロックスキューの影響を受ける可能性がありますが、`changeToken`はCouchDBのシーケンス番号に基づくため、厳密な順序を保証します。

### 2.6.5 永続キューと再起動時の動作

**Phase 1の制約（スレッドプール方式）**:

| 項目 | 動作 |
|------|------|
| 配信中の再起動 | **未完了の配信は失われる** |
| リトライ中の再起動 | **リトライは再開されない** |
| 配信ログ | CouchDBに永続化（再起動後も参照可能） |

**制約の明示**:

```
⚠️ 重要な制約事項（Phase 1）

NemakiWareの再起動時、以下の状態のWebhook配信は失われます：
- スレッドプールで実行中の配信
- リトライ待機中の配信

この制約は、スレッドプール方式の簡易実装によるものです。
ミッションクリティカルな用途では、Phase 2の永続キュー方式を検討してください。
```

**Phase 2での改善案（将来実装）**:

永続キュー方式への移行により、再起動時の配信ロスを防止：

```
┌─────────────────────────────────────────────────────────────────┐
│                    WebhookDeliveryQueue (CouchDB)               │
├─────────────────────────────────────────────────────────────────┤
│  状態遷移:                                                       │
│  PENDING → PROCESSING → SUCCESS                                 │
│              ↓                                                   │
│           FAILED → RETRY_PENDING → PROCESSING → ...             │
│              ↓                                                   │
│           DEAD_LETTER (最大リトライ超過)                         │
└─────────────────────────────────────────────────────────────────┘
```

**永続キューのデータモデル**:

```json
{
  "_id": "webhook_queue_uuid",
  "type": "webhookDeliveryQueue",
  "status": "PENDING",
  "deliveryId": "uuid",
  "repositoryId": "bedroom",
  "objectId": "doc-uuid",
  "webhookUrl": "https://example.com/webhook",
  "eventType": "UPDATED",
  "payload": { ... },
  "attemptCount": 0,
  "maxRetries": 3,
  "nextRetryAt": null,
  "createdAt": "2026-01-27T14:30:00.000Z",
  "lastAttemptAt": null,
  "lastError": null
}
```

**再起動時の動作（Phase 2）**:

1. 起動時に`status=PROCESSING`のエントリを`RETRY_PENDING`に戻す
2. `status=PENDING`または`RETRY_PENDING`のエントリをワーカーが処理
3. `nextRetryAt`を過ぎたエントリを優先的に処理

---

## 2.7 フォルダ配下イベントの効率的な取得

### 2.6.1 課題

フォルダに`nemaki:webhookIncludeChildren=true`を設定した場合、子孫要素のイベントも通知する必要があります。単純に「親を遡る」方式では、深い階層でWebhook設定が見つかるまでのコストが発生します。

### 2.6.2 推奨アプローチ: Webhook設定フォルダのキャッシュ

**アーキテクチャ**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    WebhookConfigCache                            │
├─────────────────────────────────────────────────────────────────┤
│  Map<repositoryId, PathTrie<WebhookConfigRef>>                  │
│                                                                  │
│  PathTrie構造:                                                   │
│  /                                                               │
│  └── Sites/                                                      │
│      └── Documents/ [WebhookConfig: maxDepth=5]                 │
│          └── Reports/ [WebhookConfig: maxDepth=3]               │
│      └── Archive/ [WebhookConfig: maxDepth=10]                  │
└─────────────────────────────────────────────────────────────────┘
```

**処理フロー**:

1. **アプリケーション起動時**: Webhook設定を持つ全フォルダをスキャンし、パスをTrieに登録
2. **イベント発生時**:
   - オブジェクトのパスを取得（例: `/Sites/Documents/Reports/2026/Q1/report.pdf`）
   - PathTrieでプレフィックスマッチング（O(パス長)）
   - マッチしたWebhook設定の`maxDepth`を確認
   - 深度が範囲内なら通知対象
3. **Webhook設定変更時**: キャッシュを更新（追加/削除/変更）

**コスト分析**:

| 操作 | 従来方式（親を遡る） | キャッシュ方式 |
|------|---------------------|---------------|
| イベント発生時 | O(階層深度) | O(パス長) ≈ O(1) |
| Webhook設定変更時 | O(1) | O(1) キャッシュ更新 |
| メモリ使用量 | なし | O(Webhook設定数) |

### 2.6.3 深度制限の設計

**アプリケーション全体設定** (`nemakiware.properties`):

```properties
# Webhook子孫監視のデフォルト最大深度
webhook.default.max.depth=10

# 深度制限の絶対上限（フォルダ個別設定でもこれを超えられない）
webhook.absolute.max.depth=50
```

**フォルダ個別設定** (`nemaki:webhookMaxDepth`):

- 未設定: アプリケーションのデフォルト値を使用
- 0: 直接の子要素のみ（孫以下は対象外）
- N: N階層下まで監視

**深度計算例**:

```
/Sites/Documents/  [Webhook設定: maxDepth=3]
├── file1.pdf                    → 深度1 ✓ 通知対象
├── Reports/                     → 深度1 ✓ 通知対象
│   ├── 2026/                    → 深度2 ✓ 通知対象
│   │   ├── Q1/                  → 深度3 ✓ 通知対象
│   │   │   └── report.pdf       → 深度4 ✗ 対象外
│   │   └── summary.xlsx         → 深度3 ✓ 通知対象
│   └── archive.zip              → 深度2 ✓ 通知対象
└── temp/                        → 深度1 ✓ 通知対象
```

### 2.6.4 キャッシュ実装

```java
@Service
public class WebhookConfigCache {
    
    // リポジトリ毎のPathTrie
    private final Map<String, PathTrie<WebhookConfigRef>> cacheByRepository = 
        new ConcurrentHashMap<>();
    
    /**
     * 起動時にWebhook設定を持つフォルダをロード
     */
    @PostConstruct
    public void initialize() {
        for (String repositoryId : repositoryInfoMap.keys()) {
            loadWebhookConfigs(repositoryId);
        }
    }
    
    /**
     * イベント発生時にマッチするWebhook設定を検索
     * @return マッチしたWebhook設定のリスト（深度制限内のもの）
     */
    public List<WebhookConfigRef> findMatchingConfigs(
            String repositoryId, String objectPath, int objectDepth) {
        PathTrie<WebhookConfigRef> trie = cacheByRepository.get(repositoryId);
        if (trie == null) return Collections.emptyList();
        
        List<WebhookConfigRef> matches = trie.findPrefixMatches(objectPath);
        return matches.stream()
            .filter(config -> {
                int relativeDepth = objectDepth - config.getConfigDepth();
                return relativeDepth <= config.getMaxDepth();
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Webhook設定変更時にキャッシュを更新
     */
    public void updateCache(String repositoryId, String folderPath, 
                           WebhookConfigRef config, CacheOperation operation) {
        PathTrie<WebhookConfigRef> trie = cacheByRepository
            .computeIfAbsent(repositoryId, k -> new PathTrie<>());
        
        switch (operation) {
            case ADD:
            case UPDATE:
                trie.put(folderPath, config);
                break;
            case REMOVE:
                trie.remove(folderPath);
                break;
        }
    }
}
```

---

## 3. Webhookペイロード仕様

### 3.1 リクエスト形式

```
POST {webhookUrl}
Content-Type: application/json
X-NemakiWare-Event: {eventType}
X-NemakiWare-Signature: {HMAC-SHA256署名}
X-NemakiWare-Delivery: {配信ID}
X-NemakiWare-Timestamp: {ISO8601タイムスタンプ}
{カスタムヘッダー}
```

### 3.2 ペイロード構造

```json
{
  "event": {
    "type": "UPDATED",
    "timestamp": "2026-01-27T14:30:00.000Z",
    "deliveryId": "uuid-delivery-id"
  },
  "repository": {
    "id": "bedroom",
    "name": "Default Repository"
  },
  "object": {
    "id": "object-uuid",
    "name": "example.pdf",
    "objectTypeId": "nemaki:document",
    "baseTypeId": "cmis:document",
    "parentId": "parent-folder-uuid",
    "path": "/Sites/Documents/example.pdf",
    "createdBy": "admin",
    "creationDate": "2026-01-20T10:00:00.000Z",
    "lastModifiedBy": "user1",
    "lastModificationDate": "2026-01-27T14:30:00.000Z",
    "changeToken": "1706365800000"
  },
  "changes": {
    "properties": {
      "cmis:name": {
        "oldValue": "old-name.pdf",
        "newValue": "example.pdf"
      }
    },
    "contentStream": {
      "updated": true,
      "mimeType": "application/pdf",
      "length": 102400
    }
  },
  "actor": {
    "userId": "user1",
    "displayName": "User One"
  },
  "webhookConfig": {
    "sourceObjectId": "webhook-config-object-id",
    "sourceObjectPath": "/Sites/Documents"
  }
}
```

### 3.3 署名検証

Webhook受信側でリクエストの正当性を検証するため、HMAC-SHA256署名を使用：

```
signature = HMAC-SHA256(webhookSecret, requestBody)
X-NemakiWare-Signature: sha256={signature}
```

---

## 4. アーキテクチャ設計

### 4.1 コンポーネント構成

```
┌─────────────────────────────────────────────────────────────────┐
│                         NemakiWare Core                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐    ┌──────────────────┐                   │
│  │  ContentService  │───▶│  WebhookService  │                   │
│  │  (既存)          │    │  (新規)          │                   │
│  └──────────────────┘    └────────┬─────────┘                   │
│           │                       │                              │
│           │                       ▼                              │
│           │              ┌──────────────────┐                   │
│           │              │ WebhookDispatcher│                   │
│           │              │ (非同期配信)      │                   │
│           │              └────────┬─────────┘                   │
│           │                       │                              │
│           ▼                       ▼                              │
│  ┌──────────────────┐    ┌──────────────────┐                   │
│  │  ContentDao      │    │  WebhookDelivery │                   │
│  │  (CouchDB)       │    │  Log (CouchDB)   │                   │
│  └──────────────────┘    └──────────────────┘                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
                          ┌──────────────────┐
                          │  外部システム     │
                          │  (Webhook受信)   │
                          └──────────────────┘
```

### 4.2 新規クラス/インターフェース

#### 4.2.1 WebhookService

```java
package jp.aegif.nemaki.businesslogic;

public interface WebhookService {
    
    /**
     * イベント発生時にWebhook通知をトリガー
     */
    void triggerWebhook(String repositoryId, Content content, 
                        WebhookEventType eventType, Map<String, Object> changes);
    
    /**
     * オブジェクトのWebhook設定を取得
     */
    WebhookConfig getWebhookConfig(String repositoryId, Content content);
    
    /**
     * 親フォルダからWebhook設定を継承取得
     */
    WebhookConfig getInheritedWebhookConfig(String repositoryId, Content content);
    
    /**
     * Webhook配信ログを取得
     */
    List<WebhookDeliveryLog> getDeliveryLogs(String repositoryId, 
                                              String objectId, int limit);
    
    /**
     * 手動でWebhookを再送
     */
    void retryDelivery(String repositoryId, String deliveryId);
}
```

#### 4.2.2 WebhookDispatcher

```java
package jp.aegif.nemaki.businesslogic.webhook;

public interface WebhookDispatcher {
    
    /**
     * 非同期でWebhookを配信
     */
    CompletableFuture<WebhookDeliveryResult> dispatch(WebhookRequest request);
    
    /**
     * リトライ付きで配信
     */
    CompletableFuture<WebhookDeliveryResult> dispatchWithRetry(
        WebhookRequest request, int maxRetries);
}
```

#### 4.2.3 モデルクラス

```java
// WebhookConfig - Webhook設定を表すモデル
public class WebhookConfig {
    private boolean enabled;
    private String url;
    private List<WebhookEventType> events;
    private String secret;
    private Map<String, String> customHeaders;
    private int retryCount;
    private boolean includeChildren;
    private String sourceObjectId;
}

// WebhookEventType - イベントタイプ列挙
public enum WebhookEventType {
    CREATED, UPDATED, DELETED, SECURITY,
    CONTENT_UPDATED, CHECKED_OUT, CHECKED_IN,
    VERSION_CREATED, MOVED, CHILD_CREATED, CHILD_DELETED
}

// WebhookRequest - 配信リクエスト
public class WebhookRequest {
    private String deliveryId;
    private WebhookConfig config;
    private WebhookPayload payload;
}

// WebhookPayload - ペイロードデータ
public class WebhookPayload {
    private WebhookEvent event;
    private RepositoryInfo repository;
    private ObjectInfo object;
    private ChangesInfo changes;
    private ActorInfo actor;
}

// WebhookDeliveryLog - 配信ログ
public class WebhookDeliveryLog extends NodeBase {
    private String deliveryId;
    private String objectId;
    private String webhookUrl;
    private WebhookEventType eventType;
    private int statusCode;
    private String responseBody;
    private boolean success;
    private int attemptCount;
    private GregorianCalendar deliveredAt;
}
```

### 4.3 ContentServiceへの統合

既存の`writeChangeEvent()`メソッドの呼び出し箇所にWebhookトリガーを追加：

```java
// ContentServiceImpl.java

private String writeChangeEvent(CallContext callContext, String repositoryId, 
                                 Content content, Acl acl, ChangeType changeType) {
    // 既存のChange Event記録処理
    Change change = new Change();
    // ... 既存コード ...
    
    // Webhook通知をトリガー（非同期）
    webhookService.triggerWebhook(repositoryId, content, 
        convertToWebhookEventType(changeType), buildChangesMap(content, changeType));
    
    return change.getToken();
}

// 追加のイベントポイント
// - checkOut() → CHECKED_OUT
// - checkIn() → CHECKED_IN, VERSION_CREATED
// - move() → MOVED
// - setContentStream() → CONTENT_UPDATED
// - createDocument/createFolder (親フォルダへ) → CHILD_CREATED
// - delete (親フォルダへ) → CHILD_DELETED
```

### 4.4 非同期配信の実装

```java
@Service
public class WebhookDispatcherImpl implements WebhookDispatcher {
    
    private final ExecutorService executorService;
    private final HttpClient httpClient;
    private final ContentDaoService contentDaoService;
    
    public WebhookDispatcherImpl() {
        // 専用スレッドプールで非同期実行
        this.executorService = Executors.newFixedThreadPool(10);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
    
    @Override
    public CompletableFuture<WebhookDeliveryResult> dispatch(WebhookRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest httpRequest = buildHttpRequest(request);
                HttpResponse<String> response = httpClient.send(httpRequest, 
                    HttpResponse.BodyHandlers.ofString());
                
                WebhookDeliveryResult result = new WebhookDeliveryResult();
                result.setSuccess(response.statusCode() >= 200 && response.statusCode() < 300);
                result.setStatusCode(response.statusCode());
                result.setResponseBody(response.body());
                
                // 配信ログを記録
                saveDeliveryLog(request, result);
                
                return result;
            } catch (Exception e) {
                // エラーログを記録
                return handleDeliveryError(request, e);
            }
        }, executorService);
    }
    
    @Override
    public CompletableFuture<WebhookDeliveryResult> dispatchWithRetry(
            WebhookRequest request, int maxRetries) {
        return dispatch(request).thenCompose(result -> {
            if (!result.isSuccess() && result.getAttemptCount() < maxRetries) {
                // 指数バックオフでリトライ
                long delay = (long) Math.pow(2, result.getAttemptCount()) * 1000;
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }).thenCompose(v -> dispatchWithRetry(request, maxRetries));
            }
            return CompletableFuture.completedFuture(result);
        });
    }
}
```

---

## 5. REST API設計

### 5.1 Webhook登録管理API

#### 5.1.0 登録済みWebhook一覧取得

```
GET /rest/repo/{repositoryId}/webhooks?page={page}&limit={limit}&status={status}

Query Parameters:
- page: ページ番号（デフォルト: 1）
- limit: 1ページあたりの件数（デフォルト: 20、最大: 100）
- status: フィルタ（all/enabled/disabled、デフォルト: all）

Response:
{
  "webhooks": [
    {
      "objectId": "folder-uuid-1",
      "objectName": "Documents",
      "objectPath": "/Sites/Documents",
      "objectType": "nemaki:folder",
      "webhookConfigs": [
        {
          "id": "webhook-1",
          "enabled": true,
          "url": "https://example.com/webhook",
          "events": ["CREATED", "UPDATED", "DELETED"],
          "authType": "bearer",
          "includeChildren": true,
          "maxDepth": 5
        },
        {
          "id": "webhook-2",
          "enabled": true,
          "url": "https://audit.example.com/security",
          "events": ["SECURITY"],
          "authType": "apikey",
          "includeChildren": false,
          "maxDepth": null
        }
      ],
      "lastDelivery": {
        "deliveryId": "uuid",
        "webhookId": "webhook-1",
        "timestamp": "2026-01-27T14:30:00.000Z",
        "success": true,
        "statusCode": 200
      },
      "stats": {
        "totalDeliveries": 150,
        "successCount": 148,
        "failureCount": 2,
        "lastWeekDeliveries": 25
      }
    },
    {
      "objectId": "doc-uuid-1",
      "objectName": "important-contract.pdf",
      "objectPath": "/Sites/Documents/Contracts/important-contract.pdf",
      "objectType": "nemaki:document",
      "webhookConfigs": [
        {
          "id": "webhook-3",
          "enabled": true,
          "url": "https://contracts.example.com/notify",
          "events": ["UPDATED", "CONTENT_UPDATED", "SECURITY"],
          "authType": "basic",
          "includeChildren": false,
          "maxDepth": null
        }
      ],
      "lastDelivery": null,
      "stats": {
        "totalDeliveries": 0,
        "successCount": 0,
        "failureCount": 0,
        "lastWeekDeliveries": 0
      }
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 20,
    "totalCount": 45,
    "totalPages": 3
  }
}
```

### 5.2 Webhook配信API

#### 5.2.1 配信ログ取得

```
GET /rest/repo/{repositoryId}/webhook/deliveries?objectId={objectId}&limit={limit}

Response:
{
  "deliveries": [
    {
      "deliveryId": "uuid",
      "objectId": "object-uuid",
      "eventType": "UPDATED",
      "webhookUrl": "https://example.com/webhook",
      "statusCode": 200,
      "success": true,
      "attemptCount": 1,
      "deliveredAt": "2026-01-27T14:30:00.000Z"
    }
  ]
}
```

#### 5.1.2 手動再送

```
POST /rest/repo/{repositoryId}/webhook/deliveries/{deliveryId}/retry

Response:
{
  "deliveryId": "new-uuid",
  "status": "queued"
}
```

#### 5.1.3 Webhookテスト

```
POST /rest/repo/{repositoryId}/webhook/test
Content-Type: application/json

{
  "url": "https://example.com/webhook",
  "secret": "test-secret",
  "headers": {"X-Custom": "value"}
}

Response:
{
  "success": true,
  "statusCode": 200,
  "responseTime": 150
}
```

---

## 6. UI設計

### 6.1 Webhook有効化フロー（セカンダリタイプ追加）

Webhook機能はセカンダリタイプ（`nemaki:webhookable`）として実装されるため、UIでは以下のフローでWebhookを有効化します：

**フォルダ/ドキュメント詳細画面からの有効化**:

```
┌─────────────────────────────────────────────────────────────┐
│ Documents フォルダ                                           │
├─────────────────────────────────────────────────────────────┤
│ [プロパティ] [バージョン] [権限] [Webhook]                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│ このフォルダにはWebhook機能が有効になっていません。          │
│                                                              │
│ [Webhookを有効にする]                                        │
│                                                              │
│ ※ Webhookを有効にすると、このフォルダ（および子孫）の        │
│   変更イベントを外部システムに通知できます。                 │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

**「Webhookを有効にする」ボタン押下時の処理**:

```typescript
// セカンダリタイプを追加してWebhook機能を有効化
async enableWebhook(repositoryId: string, objectId: string): Promise<void> {
  const currentSecondaryTypes = await this.getSecondaryTypes(repositoryId, objectId);
  const updatedSecondaryTypes = [...currentSecondaryTypes, 'nemaki:webhookable'];
  
  await this.updateProperties(repositoryId, objectId, {
    'cmis:secondaryObjectTypeIds': updatedSecondaryTypes
  });
}
```

### 6.2 Webhook設定UI

セカンダリタイプ追加後、Webhook設定セクションが表示されます：

```
┌─────────────────────────────────────────────────────────────┐
│ Webhook設定                                                  │
├─────────────────────────────────────────────────────────────┤
│ [x] Webhook有効                                              │
│                                                              │
│ Webhook URL:                                                 │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ https://example.com/webhook                             │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ 監視イベント:                                                │
│ [x] CREATED  [x] UPDATED  [x] DELETED  [ ] SECURITY        │
│ [ ] CONTENT_UPDATED  [ ] CHECKED_OUT  [ ] CHECKED_IN       │
│ [ ] VERSION_CREATED  [ ] MOVED                              │
│ [x] CHILD_CREATED  [x] CHILD_DELETED (フォルダのみ)         │
│                                                              │
│ Secret (署名検証用):                                         │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ ••••••••••••••••                                        │ │
│ └─────────────────────────────────────────────────────────┘ │
│                                                              │
│ [x] 子要素のイベントも通知 (フォルダのみ)                    │
│                                                              │
│ リトライ回数: [3 ▼]                                          │
│                                                              │
│ [テスト送信] [配信ログを表示]                                │
└─────────────────────────────────────────────────────────────┘
```

### 6.3 配信ログビューア

```
┌─────────────────────────────────────────────────────────────┐
│ Webhook配信ログ                                              │
├─────────────────────────────────────────────────────────────┤
│ 日時                 │ イベント │ ステータス │ 試行回数      │
├─────────────────────────────────────────────────────────────┤
│ 2026-01-27 14:30:00 │ UPDATED  │ 200 OK    │ 1            │
│ 2026-01-27 14:25:00 │ CREATED  │ 200 OK    │ 1            │
│ 2026-01-27 14:20:00 │ UPDATED  │ 500 Error │ 3 (失敗)     │
│                     │          │           │ [再送]       │
└─────────────────────────────────────────────────────────────┘
```

### 6.4 管理画面: 登録済みWebhook一覧

管理メニューに「Webhook管理」を追加し、リポジトリ全体のWebhook設定を一覧表示：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 管理 > Webhook管理                                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ [有効のみ ▼] [検索: ________________] [更新]                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ 📁 /Sites/Documents                                          [有効] ● │ │
│ │ nemaki:folder                                                          │ │
│ │ URL: https://example.com/webhook                                       │ │
│ │ イベント: CREATED, UPDATED, DELETED, CHILD_CREATED, CHILD_DELETED     │ │
│ │ 子孫監視: 有効 (深度: 5)                                               │ │
│ │ 配信統計: 成功 148 / 失敗 2 / 合計 150                                 │ │
│ │ 最終配信: 2026-01-27 14:30:00 (成功)                                   │ │
│ │                                                    [詳細] [ログ] [編集] │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ 📄 /Sites/Documents/Contracts/important-contract.pdf         [有効] ● │ │
│ │ nemaki:document                                                        │ │
│ │ URL: https://contracts.example.com/notify                              │ │
│ │ イベント: UPDATED, CONTENT_UPDATED, SECURITY                           │ │
│ │ 配信統計: 成功 0 / 失敗 0 / 合計 0                                     │ │
│ │ 最終配信: なし                                                         │ │
│ │                                                    [詳細] [ログ] [編集] │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ 📁 /Sites/Archive                                           [無効] ○ │ │
│ │ nemaki:folder                                                          │ │
│ │ URL: https://archive.example.com/events                                │ │
│ │ イベント: DELETED, CHILD_DELETED                                       │ │
│ │ 子孫監視: 有効 (深度: 10)                                              │ │
│ │ 配信統計: 成功 50 / 失敗 0 / 合計 50                                   │ │
│ │ 最終配信: 2026-01-20 10:00:00 (成功)                                   │ │
│ │                                                    [詳細] [ログ] [編集] │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│ ページ: [< 前へ] 1 / 3 [次へ >]                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**機能**:

1. **フィルタリング**: 有効/無効/全て、キーワード検索（パス、URL）
2. **ソート**: パス、最終配信日時、配信数
3. **クイックアクション**: 詳細表示、配信ログ、設定編集へのリンク
4. **ステータス表示**: 有効/無効、最終配信の成功/失敗

### 6.5 複数Webhook登録UI

1つのオブジェクトに複数のWebhook設定を登録できるUI：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Webhook設定 - /Sites/Documents                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ Webhook #1                                                    [削除] │ │
│ ├─────────────────────────────────────────────────────────────────────────┤ │
│ │ [x] 有効                                                               │ │
│ │                                                                        │ │
│ │ URL: [https://example.com/webhooks/content________________]            │ │
│ │                                                                        │ │
│ │ 監視イベント:                                                          │ │
│ │ [x] CREATED  [x] UPDATED  [ ] DELETED  [ ] SECURITY                   │ │
│ │ [x] CONTENT_UPDATED  [ ] CHECKED_OUT  [ ] CHECKED_IN                  │ │
│ │ [ ] VERSION_CREATED  [ ] MOVED                                        │ │
│ │ [x] CHILD_CREATED  [x] CHILD_UPDATED  [ ] CHILD_DELETED               │ │
│ │                                                                        │ │
│ │ 認証: [Bearer Token ▼]  トークン: [••••••••••••••••______]            │ │
│ │ Secret: [••••••••••••••••______]                                       │ │
│ │                                                                        │ │
│ │ [x] 子要素のイベントも通知  最大深度: [5 ▼]                            │ │
│ │ リトライ回数: [3 ▼]                                                    │ │
│ │                                                                        │ │
│ │ [テスト送信] [配信ログ]                                                │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ Webhook #2                                                    [削除] │ │
│ ├─────────────────────────────────────────────────────────────────────────┤ │
│ │ [x] 有効                                                               │ │
│ │                                                                        │ │
│ │ URL: [https://security-audit.example.com/acl-changes______]            │ │
│ │                                                                        │ │
│ │ 監視イベント:                                                          │ │
│ │ [ ] CREATED  [ ] UPDATED  [ ] DELETED  [x] SECURITY                   │ │
│ │ [ ] CONTENT_UPDATED  [ ] CHECKED_OUT  [ ] CHECKED_IN                  │ │
│ │ [ ] VERSION_CREATED  [ ] MOVED                                        │ │
│ │ [ ] CHILD_CREATED  [ ] CHILD_UPDATED  [ ] CHILD_DELETED               │ │
│ │                                                                        │ │
│ │ 認証: [API Key ▼]  ヘッダー名: [X-API-Key]  値: [••••••••]            │ │
│ │ Secret: [••••••••••••••••______]                                       │ │
│ │                                                                        │ │
│ │ [ ] 子要素のイベントも通知                                             │ │
│ │ リトライ回数: [5 ▼]                                                    │ │
│ │                                                                        │ │
│ │ [テスト送信] [配信ログ]                                                │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│ [+ Webhookを追加]                                                            │
│                                                                              │
│                                                    [キャンセル] [保存]       │
└─────────────────────────────────────────────────────────────────────────────┘
```

**データモデルの変更**:

複数Webhook対応のため、プロパティ構造を以下のように統一します：

**プロパティ名の統一（重要）**:

| 旧プロパティ名 | 新プロパティ名 | 説明 |
|---------------|---------------|------|
| `nemaki:webhookEventConfigs` | **廃止** | イベント別設定（セクション2.4）は`nemaki:webhookConfigs`に統合 |
| `nemaki:webhookUrl`, `nemaki:webhookEvents`, etc. | **廃止** | 個別プロパティは`nemaki:webhookConfigs`配列に統合 |
| - | `nemaki:webhookConfigs` | **確定仕様**: 複数Webhook設定を格納するJSON配列 |

**マイグレーション方針**:

既存の個別プロパティ（`nemaki:webhookUrl`等）から`nemaki:webhookConfigs`への移行：

1. 初回起動時に自動マイグレーションを実行
2. 旧プロパティの値を`nemaki:webhookConfigs`配列の1要素として変換
3. マイグレーション完了後、旧プロパティは読み取り専用（後方互換性のため保持）

**`nemaki:webhookConfigs`プロパティ仕様**:

```json
[
  {
    "id": "webhook-1",
    "enabled": true,
    "url": "https://example.com/webhooks/content",
    "events": ["CREATED", "UPDATED", "CONTENT_UPDATED", "CHILD_CREATED", "CHILD_UPDATED"],
    "authType": "bearer",
    "authCredential": "encrypted-token",
    "secret": "encrypted-secret",
    "includeChildren": true,
    "maxDepth": 5,
    "retryCount": 3
  },
  {
    "id": "webhook-2",
    "enabled": true,
    "url": "https://security-audit.example.com/acl-changes",
    "events": ["SECURITY"],
    "authType": "apikey",
    "authCredential": "X-API-Key:encrypted-key",
    "secret": "encrypted-secret",
    "includeChildren": false,
    "maxDepth": null,
    "retryCount": 5
  }
]
```

---

## 6.6 監査ログへのWebhook発火記録

Webhook配信イベントは2つの方法で記録できます：

1. **CMIS Change Log統合**: 既存のCMIS Change Log（`writeChangeEvent`）に統合し、標準的なCMISクライアントからも参照可能
2. **専用監査ログ**: Webhook専用の詳細な監査ログ（配信結果、レスポンス時間等を含む）

### 6.6.1 CMIS Change Log統合

既存の`ContentService.writeChangeEvent()`を拡張し、Webhook配信イベントをCMIS Change Logに記録します。

**拡張ChangeType**:

```java
// 既存のCMIS ChangeType
public enum ChangeType {
    CREATED,    // オブジェクト作成
    UPDATED,    // プロパティ更新
    DELETED,    // オブジェクト削除
    SECURITY    // ACL変更
}

// NemakiWare拡張イベントタイプ（nemaki:changeSubType プロパティで識別）
public enum NemakiChangeSubType {
    // 標準CMIS（subTypeなし）
    STANDARD,
    
    // Webhook関連
    WEBHOOK_DELIVERED,      // Webhook配信成功
    WEBHOOK_FAILED,         // Webhook配信失敗
    WEBHOOK_CONFIG_CHANGED  // Webhook設定変更
}
```

**Change Logエントリ構造**:

```json
{
  "_id": "change_uuid",
  "type": "change",
  "repositoryId": "bedroom",
  "objectId": "document-uuid",
  "changeType": "UPDATED",
  "nemaki:changeSubType": "WEBHOOK_DELIVERED",
  "changeToken": "1706365800000",
  "created": "2026-01-27T14:30:00.000Z",
  "creator": "system",
  "nemaki:webhookDelivery": {
    "webhookId": "webhook-1",
    "url": "https://example.com/webhook",
    "eventType": "UPDATED",
    "deliveryId": "delivery-uuid",
    "success": true,
    "statusCode": 200,
    "attemptCount": 1
  }
}
```

**CMIS getContentChanges() での取得**:

```
GET /browser/{repositoryId}?cmisselector=contentChanges&changeLogToken={token}&includeProperties=true

Response:
{
  "objects": [
    {
      "changeType": "updated",
      "changeTime": "2026-01-27T14:30:00.000Z",
      "objectId": "document-uuid",
      "properties": {
        "nemaki:changeSubType": {"value": "WEBHOOK_DELIVERED"},
        "nemaki:webhookUrl": {"value": "https://example.com/webhook"},
        "nemaki:webhookSuccess": {"value": true},
        "nemaki:webhookStatusCode": {"value": 200}
      }
    }
  ],
  "hasMoreItems": false,
  "changeLogToken": "1706365800001"
}
```

**設定オプション** (`nemakiware.properties`):

```properties
# Webhook配信をCMIS Change Logに記録するか
webhook.changelog.enabled=true

# 成功した配信も記録するか（falseの場合は失敗のみ）
webhook.changelog.include.success=true

# Change Logに含めるWebhook詳細レベル
# minimal: objectId, webhookUrl, success のみ
# standard: + statusCode, attemptCount, eventType
# full: + responseTime, payloadSize, headers
webhook.changelog.detail.level=standard
```

### 6.6.2 専用監査ログエントリ構造

Webhook配信を専用の監査ログ（Change Logとは別）に記録し、詳細な配信情報を保持：

```json
{
  "_id": "audit_webhook_uuid",
  "type": "auditLog",
  "category": "WEBHOOK_DELIVERY",
  "repositoryId": "bedroom",
  "timestamp": "2026-01-27T14:30:00.000Z",
  "actor": {
    "type": "SYSTEM",
    "triggeredBy": "user1"
  },
  "target": {
    "objectId": "document-uuid",
    "objectName": "report.pdf",
    "objectPath": "/Sites/Documents/Reports/report.pdf"
  },
  "webhook": {
    "webhookId": "webhook-1",
    "url": "https://example.com/webhook",
    "eventType": "UPDATED",
    "deliveryId": "delivery-uuid"
  },
  "result": {
    "success": true,
    "statusCode": 200,
    "responseTime": 150,
    "attemptCount": 1
  },
  "details": {
    "payloadSize": 2048,
    "requestHeaders": ["Content-Type", "X-NemakiWare-Event", "X-NemakiWare-Signature"],
    "triggerEvent": {
      "changeType": "UPDATED",
      "changeToken": "1706365800000"
    }
  }
}
```

### 6.6.2 監査ログカテゴリ

| カテゴリ | 説明 |
|---------|------|
| `WEBHOOK_DELIVERY_SUCCESS` | Webhook配信成功 |
| `WEBHOOK_DELIVERY_FAILURE` | Webhook配信失敗（リトライ後も失敗） |
| `WEBHOOK_DELIVERY_RETRY` | Webhook配信リトライ |
| `WEBHOOK_CONFIG_CREATED` | Webhook設定作成 |
| `WEBHOOK_CONFIG_UPDATED` | Webhook設定更新 |
| `WEBHOOK_CONFIG_DELETED` | Webhook設定削除 |
| `WEBHOOK_CONFIG_ENABLED` | Webhook有効化 |
| `WEBHOOK_CONFIG_DISABLED` | Webhook無効化 |

### 6.6.3 監査ログビューア（管理画面）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 管理 > 監査ログ > Webhook                                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│ 期間: [2026-01-01] ～ [2026-01-27]  カテゴリ: [全て ▼]  [検索]              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│ 日時                 │ カテゴリ           │ 対象                │ 結果     │
│ ────────────────────┼───────────────────┼────────────────────┼─────────│
│ 2026-01-27 14:30:00 │ DELIVERY_SUCCESS  │ /Sites/.../report  │ 200 OK  │
│ 2026-01-27 14:25:00 │ DELIVERY_SUCCESS  │ /Sites/.../data    │ 200 OK  │
│ 2026-01-27 14:20:00 │ DELIVERY_FAILURE  │ /Sites/.../old     │ 500 Err │
│ 2026-01-27 14:15:00 │ CONFIG_UPDATED    │ /Sites/Documents   │ -       │
│ 2026-01-27 14:10:00 │ DELIVERY_RETRY    │ /Sites/.../old     │ 503 Err │
│ 2026-01-27 14:05:00 │ DELIVERY_RETRY    │ /Sites/.../old     │ 503 Err │
│ 2026-01-27 14:00:00 │ CONFIG_CREATED    │ /Sites/Archive     │ -       │
│                                                                              │
│ ページ: [< 前へ] 1 / 10 [次へ >]                                             │
│                                                                              │
│ [CSVエクスポート]                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.6.4 監査ログAPI

```
GET /rest/repo/{repositoryId}/audit/webhooks?from={from}&to={to}&category={category}&limit={limit}

Response:
{
  "auditLogs": [
    {
      "id": "audit-uuid",
      "timestamp": "2026-01-27T14:30:00.000Z",
      "category": "WEBHOOK_DELIVERY_SUCCESS",
      "target": {
        "objectId": "doc-uuid",
        "objectPath": "/Sites/Documents/Reports/report.pdf"
      },
      "webhook": {
        "url": "https://example.com/webhook",
        "eventType": "UPDATED"
      },
      "result": {
        "success": true,
        "statusCode": 200
      }
    }
  ],
  "pagination": {...}
}
```

---

## 7. RSSフィード機能

Webhook機能と同時に、nemaki:folderおよびnemaki:documentの変更イベントをRSS形式で取得できる機能を実装します。

### 7.1 RSSフィード概要

**目的**: フォルダやドキュメントの変更をRSSリーダーやRSS対応アプリケーションで購読可能にする

**対応フォーマット**:
- RSS 2.0（標準）
- Atom 1.0（オプション）

### 7.2 RSSフィードエンドポイント

#### 7.2.1 フォルダ変更フィード

```
GET /rest/repo/{repositoryId}/rss/folder/{folderId}?includeChildren={true|false}&maxDepth={n}&limit={n}

Query Parameters:
- includeChildren: 子孫要素の変更も含めるか（デフォルト: true）
- maxDepth: 子孫を含める最大深度（デフォルト: 5）
- limit: 最大エントリ数（デフォルト: 50、最大: 200）
- events: フィルタするイベントタイプ（カンマ区切り、例: CREATED,UPDATED）
- format: rss または atom（デフォルト: rss）

Response (RSS 2.0):
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:nemaki="http://nemakiware.org/rss/1.0">
  <channel>
    <title>NemakiWare - /Sites/Documents の変更</title>
    <link>https://nemakiware.example.com/ui/#/folder/folder-uuid</link>
    <description>フォルダ /Sites/Documents とその子孫の変更フィード</description>
    <language>ja</language>
    <lastBuildDate>Mon, 27 Jan 2026 14:30:00 +0000</lastBuildDate>
    <ttl>5</ttl>
    
    <item>
      <title>[UPDATED] report.pdf</title>
      <link>https://nemakiware.example.com/ui/#/document/doc-uuid</link>
      <description>ドキュメント report.pdf が更新されました</description>
      <pubDate>Mon, 27 Jan 2026 14:30:00 +0000</pubDate>
      <guid isPermaLink="false">change-uuid-1</guid>
      <nemaki:eventType>UPDATED</nemaki:eventType>
      <nemaki:objectId>doc-uuid</nemaki:objectId>
      <nemaki:objectType>nemaki:document</nemaki:objectType>
      <nemaki:objectPath>/Sites/Documents/Reports/report.pdf</nemaki:objectPath>
      <nemaki:modifier>user1</nemaki:modifier>
      <nemaki:changeToken>1706365800000</nemaki:changeToken>
    </item>
    
    <item>
      <title>[CREATED] new-folder</title>
      <link>https://nemakiware.example.com/ui/#/folder/folder-uuid-2</link>
      <description>フォルダ new-folder が作成されました</description>
      <pubDate>Mon, 27 Jan 2026 14:25:00 +0000</pubDate>
      <guid isPermaLink="false">change-uuid-2</guid>
      <nemaki:eventType>CREATED</nemaki:eventType>
      <nemaki:objectId>folder-uuid-2</nemaki:objectId>
      <nemaki:objectType>nemaki:folder</nemaki:objectType>
      <nemaki:objectPath>/Sites/Documents/new-folder</nemaki:objectPath>
      <nemaki:modifier>user2</nemaki:modifier>
      <nemaki:changeToken>1706365500000</nemaki:changeToken>
    </item>
  </channel>
</rss>
```

#### 7.2.2 ドキュメント変更フィード

```
GET /rest/repo/{repositoryId}/rss/document/{documentId}?limit={n}

Query Parameters:
- limit: 最大エントリ数（デフォルト: 50）
- events: フィルタするイベントタイプ
- format: rss または atom

Response (RSS 2.0):
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:nemaki="http://nemakiware.org/rss/1.0">
  <channel>
    <title>NemakiWare - report.pdf の変更</title>
    <link>https://nemakiware.example.com/ui/#/document/doc-uuid</link>
    <description>ドキュメント report.pdf の変更フィード</description>
    <lastBuildDate>Mon, 27 Jan 2026 14:30:00 +0000</lastBuildDate>
    
    <item>
      <title>[CONTENT_UPDATED] report.pdf - バージョン 2.0</title>
      <link>https://nemakiware.example.com/ui/#/document/doc-uuid?version=2.0</link>
      <description>コンテンツが更新されました（バージョン 2.0）</description>
      <pubDate>Mon, 27 Jan 2026 14:30:00 +0000</pubDate>
      <guid isPermaLink="false">change-uuid-1</guid>
      <nemaki:eventType>CONTENT_UPDATED</nemaki:eventType>
      <nemaki:versionLabel>2.0</nemaki:versionLabel>
      <nemaki:checkinComment>月次レポート更新</nemaki:checkinComment>
    </item>
    
    <item>
      <title>[SECURITY] report.pdf - ACL変更</title>
      <link>https://nemakiware.example.com/ui/#/document/doc-uuid</link>
      <description>アクセス権限が変更されました</description>
      <pubDate>Mon, 27 Jan 2026 14:20:00 +0000</pubDate>
      <guid isPermaLink="false">change-uuid-2</guid>
      <nemaki:eventType>SECURITY</nemaki:eventType>
    </item>
  </channel>
</rss>
```

#### 7.2.3 リポジトリ全体の変更フィード

```
GET /rest/repo/{repositoryId}/rss?limit={n}&events={events}

Response: リポジトリ全体の最新変更をRSSで取得
```

### 7.3 認証とアクセス制御

RSSフィードへのアクセスには認証が必要です：

**認証方式**:

1. **トークン認証（推奨）**: URLにトークンを含める
   ```
   GET /rest/repo/{repositoryId}/rss/folder/{folderId}?token={rss-token}
   ```

2. **Basic認証**: HTTPヘッダーで認証
   ```
   Authorization: Basic base64(username:password)
   ```

3. **APIキー**: カスタムヘッダー
   ```
   X-NemakiWare-API-Key: {api-key}
   ```

**RSSトークン管理**:

```
POST /rest/repo/{repositoryId}/rss/token
Content-Type: application/json

{
  "name": "My RSS Reader",
  "expiresIn": "30d",
  "scope": {
    "folders": ["folder-uuid-1", "folder-uuid-2"],
    "documents": ["doc-uuid-1"],
    "events": ["CREATED", "UPDATED", "DELETED"]
  }
}

Response:
{
  "token": "rss-token-uuid",
  "expiresAt": "2026-02-26T14:30:00.000Z"
}
```

**アクセス制御**:
- ユーザーは自分がアクセス権を持つオブジェクトの変更のみ取得可能
- 管理者はリポジトリ全体のフィードにアクセス可能

### 7.4 UIでのRSSフィード購読

#### 7.4.1 フォルダ詳細画面

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ /Sites/Documents                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ [プロパティ] [権限] [Webhook] [RSSフィード]                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│ RSSフィード購読                                                              │
│                                                                              │
│ このフォルダの変更をRSSリーダーで購読できます。                              │
│                                                                              │
│ フィードURL:                                                                 │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ https://nemakiware.example.com/rest/repo/bedroom/rss/folder/folder-uuid │ │
│ │ ?token=rss-token-xxx&includeChildren=true                               │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
│ [コピー] [QRコード表示]                                                      │
│                                                                              │
│ オプション:                                                                  │
│ [x] 子フォルダの変更も含める                                                 │
│ 最大深度: [5 ▼]                                                              │
│                                                                              │
│ イベントフィルタ:                                                            │
│ [x] CREATED  [x] UPDATED  [x] DELETED  [ ] SECURITY                         │
│ [ ] CONTENT_UPDATED  [ ] VERSION_CREATED                                    │
│                                                                              │
│ [新しいトークンを生成]                                                       │
│                                                                              │
│ 既存のトークン:                                                              │
│ ┌─────────────────────────────────────────────────────────────────────────┐ │
│ │ My RSS Reader          有効期限: 2026-02-26        [無効化] [更新]     │ │
│ │ Slack Integration      有効期限: 2026-03-15        [無効化] [更新]     │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.5 RSS実装アーキテクチャ

```java
@Path("/rest/repo/{repositoryId}/rss")
public class RssFeedResource {
    
    @Inject
    private ContentService contentService;
    
    @Inject
    private RssTokenService rssTokenService;
    
    @GET
    @Path("/folder/{folderId}")
    @Produces({"application/rss+xml", "application/atom+xml"})
    public Response getFolderFeed(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("folderId") String folderId,
            @QueryParam("token") String token,
            @QueryParam("includeChildren") @DefaultValue("true") boolean includeChildren,
            @QueryParam("maxDepth") @DefaultValue("5") int maxDepth,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("events") String events,
            @QueryParam("format") @DefaultValue("rss") String format) {
        
        // トークン検証
        CallContext callContext = rssTokenService.validateToken(token);
        
        // 変更イベント取得（WebhookConfigCacheと同じPathTrieを活用）
        List<Change> changes = contentService.getChangesForFolder(
            callContext, repositoryId, folderId, includeChildren, maxDepth, limit, events);
        
        // RSS/Atom生成
        if ("atom".equals(format)) {
            return Response.ok(buildAtomFeed(changes)).type("application/atom+xml").build();
        }
        return Response.ok(buildRssFeed(changes)).type("application/rss+xml").build();
    }
}
```

### 7.6 設定オプション

```properties
# RSSフィード機能の有効/無効
rss.feed.enabled=true

# デフォルトのエントリ数上限
rss.feed.default.limit=50

# 最大エントリ数上限
rss.feed.max.limit=200

# フィードのキャッシュ時間（秒）
rss.feed.cache.ttl=60

# RSSトークンのデフォルト有効期限（日）
rss.token.default.expiry.days=30

# RSSトークンの最大有効期限（日）
rss.token.max.expiry.days=365
```

---

## 8. 設定ファイル

### 8.1 nemakiware.properties

```properties
# Webhook機能の有効/無効
webhook.enabled=true

# 配信スレッドプールサイズ
webhook.dispatcher.pool.size=10

# 接続タイムアウト（秒）
webhook.http.connect.timeout=10

# 読み取りタイムアウト（秒）
webhook.http.read.timeout=30

# デフォルトリトライ回数
webhook.default.retry.count=3

# リトライ間隔の基数（ミリ秒）
webhook.retry.backoff.base=1000

# 配信ログ保持期間（日）
webhook.delivery.log.retention.days=30

# 最大ペイロードサイズ（バイト）
webhook.max.payload.size=1048576
```

---

## 8. データベース設計

### 8.1 CouchDBドキュメント構造

#### 8.1.1 WebhookDeliveryLog

```json
{
  "_id": "webhook_delivery_uuid",
  "type": "webhookDeliveryLog",
  "deliveryId": "uuid",
  "repositoryId": "bedroom",
  "objectId": "target-object-uuid",
  "webhookUrl": "https://example.com/webhook",
  "eventType": "UPDATED",
  "payload": { ... },
  "statusCode": 200,
  "responseBody": "OK",
  "success": true,
  "attemptCount": 1,
  "created": "2026-01-27T14:30:00.000Z",
  "creator": "system"
}
```

### 8.2 ビュー定義

```javascript
// webhook_deliveries_by_object
{
  "map": "function(doc) { 
    if (doc.type === 'webhookDeliveryLog') { 
      emit([doc.repositoryId, doc.objectId, doc.created], null); 
    } 
  }"
}

// webhook_deliveries_failed
{
  "map": "function(doc) { 
    if (doc.type === 'webhookDeliveryLog' && !doc.success) { 
      emit([doc.repositoryId, doc.created], null); 
    } 
  }"
}
```

---

## 9. セキュリティ考慮事項

### 9.1 Webhook URLの検証

- URLスキームは`https://`のみ許可（開発環境では`http://localhost`も許可）
- プライベートIPアドレス（10.x.x.x, 192.168.x.x, 127.x.x.x）へのアクセスを禁止
- URLの長さ制限（2048文字）

### 9.2 シークレット管理

- `nemaki:webhookSecret`プロパティは読み取り時にマスク
- データベースには暗号化して保存（オプション）
- 署名検証により改ざん防止

### 9.3 レート制限

- 同一URLへの配信は1秒間に最大10リクエスト
- 失敗が連続する場合はサーキットブレーカーで一時停止

### 9.4 ペイロードサイズ制限

- 最大1MBまで
- 大きなコンテンツは含めず、参照URLを提供

---

## 10. 実装フェーズ

### 10.1 イベントタイプの段階的実装

**Phase 1で実装するイベント（必須イベント）**:

| イベント | 優先度 | 理由 |
|---------|--------|------|
| `CREATED` | **必須** | 基本CRUD |
| `UPDATED` | **必須** | 基本CRUD |
| `DELETED` | **必須** | 基本CRUD |
| `SECURITY` | **必須** | ACL変更の監査要件 |

**Phase 2で実装するイベント（拡張イベント）**:

| イベント | 優先度 | 理由 |
|---------|--------|------|
| `CONTENT_UPDATED` | 高 | ファイル内容変更の検知 |
| `CHECKED_OUT` | 中 | バージョン管理ワークフロー |
| `CHECKED_IN` | 中 | バージョン管理ワークフロー |
| `VERSION_CREATED` | 中 | バージョン履歴追跡 |
| `MOVED` | 中 | フォルダ構造変更の追跡 |

**Phase 3で実装するイベント（CHILD_*イベント）**:

| イベント | 優先度 | 理由 |
|---------|--------|------|
| `CHILD_CREATED` | 低 | 大量イベント発生リスク |
| `CHILD_UPDATED` | 低 | 大量イベント発生リスク |
| `CHILD_DELETED` | 低 | 大量イベント発生リスク |

### 10.2 CHILD_*イベントの負荷制御

CHILD_*イベントは大量発生のリスクがあるため、以下の多層的な負荷制御を実装します。

#### 10.2.1 サーバー側バッチ集約

**バッチ処理の仕組み**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    CHILD_*イベントバッチ処理                      │
├─────────────────────────────────────────────────────────────────┤
│  1. 短時間（5秒）内の同一フォルダへのCHILD_*イベントを集約       │
│  2. バッチとして1つのWebhookリクエストにまとめて配信             │
│  3. ペイロードに変更オブジェクトのリストを含める                  │
│  4. バッチサイズ上限超過時は複数バッチに分割                     │
└─────────────────────────────────────────────────────────────────┘
```

**バッチ集約のオプション**（Webhook設定で選択可能）:

| オプション | 説明 | ユースケース |
|-----------|------|-------------|
| `immediate` | 即座に個別配信（デフォルト） | リアルタイム性重視 |
| `batched` | 5秒ウィンドウで集約 | 大量イベント対応 |
| `summary` | 1分間のサマリーのみ配信 | 統計・監視用途 |

**Webhook設定でのバッチモード指定**:

```json
{
  "id": "webhook-1",
  "url": "https://example.com/webhook",
  "events": ["CHILD_CREATED", "CHILD_UPDATED", "CHILD_DELETED"],
  "childEventMode": "batched",
  "batchWindowSeconds": 5
}
```

**バッチペイロード例**:

```json
{
  "event": {
    "type": "CHILD_BATCH",
    "timestamp": "2026-01-27T14:30:00.000Z",
    "deliveryId": "uuid"
  },
  "repository": { ... },
  "parentFolder": {
    "id": "folder-uuid",
    "path": "/Sites/Documents"
  },
  "changes": [
    {"type": "CHILD_CREATED", "objectId": "doc-1", "name": "file1.pdf"},
    {"type": "CHILD_CREATED", "objectId": "doc-2", "name": "file2.pdf"},
    {"type": "CHILD_UPDATED", "objectId": "doc-3", "name": "file3.pdf"}
  ],
  "batchInfo": {
    "windowStart": "2026-01-27T14:29:55.000Z",
    "windowEnd": "2026-01-27T14:30:00.000Z",
    "eventCount": 3
  }
}
```

#### 10.2.2 レート制限と上限設定

**レート制限設定**:

```properties
# CHILD_*イベントのバッチウィンドウ（秒）
webhook.child.event.batch.window.seconds=5

# CHILD_*イベントの最大バッチサイズ
webhook.child.event.batch.max.size=100

# CHILD_*イベントの1分あたり最大配信数（フォルダ毎）
webhook.child.event.rate.limit.per.minute=60

# CHILD_*イベントのサーキットブレーカー閾値
webhook.child.event.circuit.breaker.threshold=500

# 大規模階層での絶対上限（1秒あたり）
webhook.child.event.absolute.max.per.second=50
```

**大規模階層での保護**:

大量のファイルを含むフォルダ（例: 10,000ファイル以上）での一括操作時の保護：

| シナリオ | 発生イベント数 | 保護メカニズム |
|----------|---------------|----------------|
| 10,000ファイルの一括アップロード | 10,000 CHILD_CREATED | バッチ集約 + レート制限 |
| フォルダ削除（1,000ファイル含む） | 1,000 CHILD_DELETED | サーキットブレーカー発動 |
| 再帰的ACL変更 | 深度×ファイル数 | 深度制限 + レート制限 |

**絶対上限の動作**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    絶対上限（50イベント/秒）                      │
├─────────────────────────────────────────────────────────────────┤
│  1. 1秒間に50イベントを超えた場合、超過分はキューに滞留         │
│  2. キューサイズが1000を超えた場合、古いイベントを破棄          │
│  3. 破棄されたイベントは監査ログに「DROPPED」として記録         │
│  4. 管理画面で破棄イベント数を確認可能                          │
└─────────────────────────────────────────────────────────────────┘
```

#### 10.2.3 サーキットブレーカー

**サーキットブレーカー**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    サーキットブレーカー動作                       │
├─────────────────────────────────────────────────────────────────┤
│  状態: CLOSED → OPEN → HALF_OPEN → CLOSED                       │
│                                                                  │
│  CLOSED: 通常動作                                                │
│  OPEN: 閾値超過で配信停止（5分間）、イベントはログのみ記録       │
│  HALF_OPEN: 1リクエストのみ試行、成功でCLOSED、失敗でOPEN        │
└─────────────────────────────────────────────────────────────────┘
```

### 10.3 実装スケジュール

### Phase 1: Webhook基盤（MVP）（3週間）

**目標**: 必須イベント（CREATED/UPDATED/DELETED/SECURITY）のWebhook配信

1. `nemaki:folder`と`nemaki:document`タイプ定義の追加
2. `nemaki:webhookConfigs`プロパティ（統一仕様）
3. `WebhookService`インターフェースと基本実装
4. `WebhookDispatcher`の非同期配信実装（スレッドプール方式）
5. `WebhookDeliveryLog`モデルとDAO
6. `WebhookConfigCache`（PathTrie）の実装
7. URL検証・DNSリバインディング対策
8. 認証情報暗号化

### Phase 2: 拡張イベント・監査ログ（2週間）

**目標**: 拡張イベントと監査ログ機能

1. `writeChangeEvent()`へのWebhookトリガー追加
2. 拡張イベント実装（CONTENT_UPDATED, CHECKED_OUT, CHECKED_IN, VERSION_CREATED, MOVED）
3. `WebhookConfigCache`を使用した効率的なWebhook設定検索
4. 監査ログモデルとDAO
5. CMIS Change Log統合

### Phase 3: REST API・管理機能（2週間）

**目標**: 管理APIと基本UI

1. 登録済みWebhook一覧取得API
2. 配信ログ取得API
3. 手動再送API
4. Webhookテスト送信API
5. 監査ログ取得API
6. 管理画面: 登録済みWebhook一覧
7. 管理画面: 監査ログビューア

### Phase 4: CHILD_*イベント・負荷制御（2週間）

**目標**: CHILD_*イベントの安全な実装

1. CHILD_CREATED/CHILD_UPDATED/CHILD_DELETEDイベント実装
2. バッチ処理機構
3. レート制限
4. サーキットブレーカー
5. 負荷テスト・チューニング

### Phase 5: RSSフィード機能（2週間）

**目標**: RSSフィード機能（Webhookとは独立してリリース可能）

1. RSSトークンモデルとDAO
2. RSSフィードAPI（フォルダ/ドキュメント/リポジトリ）
3. RSSトークン管理API
4. RSSフィード購読UI（フォルダ/ドキュメント詳細画面）
5. RSSトークン管理UI

### Phase 6: UI・テスト・ドキュメント（2週間）

**目標**: 完成度向上

1. デフォルトタイプ選択の変更
2. Webhook設定UIコンポーネント（複数Webhook対応）
3. 配信ログビューア
4. テスト送信機能
5. ユニットテスト・統合テスト・E2Eテスト
6. ユーザードキュメント・管理者ドキュメント
7. RSSフィード利用ガイド

**合計: 約13週間**

### 10.4 リリース戦略

| リリース | 含まれる機能 | 目安時期 |
|---------|-------------|---------|
| **v1.0-alpha** | Phase 1完了（必須イベントのみ） | 3週間後 |
| **v1.0-beta** | Phase 1-3完了（CHILD_*以外） | 7週間後 |
| **v1.0** | Phase 1-4完了（全Webhook機能） | 9週間後 |
| **v1.1** | Phase 5完了（RSSフィード追加） | 11週間後 |
| **v1.2** | Phase 6完了（UI改善・ドキュメント） | 13週間後 |

---

## 11. 設計判断と代替案

### 11.1 採用: Secondary Typeとしての実装

**採用案**: Webhook設定をSecondary Type（`nemaki:webhookable`）として実装

**メリット**:
- **選択的適用**: 多数のオブジェクトにはWebhookは不要。必要なオブジェクトにのみ追加可能
- **既存オブジェクト対応**: 既存の`cmis:folder`/`cmis:document`にもWebhook機能を追加可能
- **柔軟性**: カスタムタイプにもWebhook機能を追加可能
- **CMIS準拠**: 標準的なセカンダリタイプ機構を活用

**考慮事項**:
- UIでセカンダリタイプの追加操作が必要（専用UIで対応）
- Webhook設定を持つオブジェクトの検索にはセカンダリタイプでのフィルタリングが必要

**結論**: **採用**。多数のオブジェクトにはWebhookが不要であり、必要なオブジェクトにのみ選択的に適用できるセカンダリタイプ方式が最適。

### 11.2 不採用: プライマリタイプとしての実装

**案**: `nemaki:folder`/`nemaki:document`プライマリタイプにWebhookプロパティを含める

**メリット**:
- UIでのデフォルト選択が簡単
- セカンダリタイプ追加操作が不要

**デメリット**:
- 多数のオブジェクトに不要なプロパティが付与される
- 既存オブジェクトへの適用が困難（タイプ変更が必要）

**結論**: **不採用**。Webhookは限られたオブジェクトにのみ必要であり、全オブジェクトにプロパティを持たせるのは非効率。

### 11.3 グローバルWebhook設定

**案**: リポジトリレベルでグローバルなWebhook設定を持つ

**メリット**:
- 一括設定が容易
- 管理が簡単

**デメリット**:
- 細かい制御ができない
- 不要な通知が増える可能性

**結論**: オブジェクトレベルの設定を基本とし、将来的にグローバル設定も追加可能

---

## 12. 今後の拡張可能性

1. **Webhook条件フィルター**: 特定のプロパティ値変更時のみ通知
2. **バッチ配信**: 複数イベントをまとめて配信
3. **Webhook認証方式の拡張**: OAuth2, API Key等
4. **配信先の多様化**: AWS SNS, Azure Event Grid, Kafka等
5. **Webhookテンプレート**: ペイロード形式のカスタマイズ

---

## 13. 質問・確認事項

### 13.1 レビュー指摘への回答

| 質問 | 回答 |
|------|------|
| **再送時のdeliveryIdは維持？新規？** | **維持**。受信側の冪等処理を容易にするため、再送時も同一deliveryIdを使用します（セクション2.6.1参照）。 |
| **順序保証の必要性は？** | **順序保証なし（best-effort）**。並列配信を優先し、順序が必要な場合は受信側で`changeToken`を使用して制御します（セクション2.6.2参照）。将来的に同一オブジェクトの順序保証オプションを検討。 |
| **Webhook設定プロパティはどちらが確定仕様か？** | **`nemaki:webhookConfigs`が確定仕様**。`nemaki:webhookEventConfigs`は廃止し、複数Webhook設定を格納するJSON配列として統一します（セクション6.5参照）。 |

### 13.2 追加の確認事項

1. **タイプ名の確認**: `nemaki:folder`/`nemaki:document`で問題ないか？
2. **イベントタイプの追加**: 他に必要なイベントタイプはあるか？
3. **ペイロード形式**: 追加で含めるべき情報はあるか？
4. **セキュリティ要件**: 追加のセキュリティ要件はあるか？
5. **優先度**: Phase分けの優先度調整は必要か？
6. **永続キュー**: Phase 1ではスレッドプール方式（再起動時に未送信イベント消失）で問題ないか？永続キューはPhase 2以降で検討。
7. **RSSフィード**: Webhook機能とは独立してPhase 5でリリースする方針で問題ないか？

---

## 付録A: 参考資料

- [CMIS 1.1 Specification](https://docs.oasis-open.org/cmis/CMIS/v1.1/CMIS-v1.1.pdf)
- [NemakiWare AGENTS.md](../AGENTS.md)
- [NemakiWare CLAUDE.md](../CLAUDE.md)

## 付録B: 用語集

| 用語 | 説明 |
|------|------|
| Webhook | HTTPコールバックによるイベント通知機構 |
| PWC | Private Working Copy（チェックアウト時の作業コピー） |
| Change Log | CMISの変更履歴機能 |
| Secondary Type | CMISのオブジェクトに追加できる補助的なタイプ |
| RSS | Really Simple Syndication - コンテンツ配信のためのXMLフォーマット |
| Atom | RSS代替のフィード配信フォーマット（RFC 4287） |
| RSSトークン | RSSフィード購読用の認証トークン |

## 付録C: レガシープロパティとマイグレーション

### C.1 廃止されたプロパティ名

設計初期段階で検討された以下のプロパティ名は、`nemaki:webhookConfigs`への統一により**廃止**されました：

| 廃止プロパティ | 代替 | 備考 |
|---------------|------|------|
| `nemaki:webhookUrl` | `webhookConfigs[].url` | 単一URLから複数URL対応へ |
| `nemaki:webhookEvents` | `webhookConfigs[].events` | 配列形式に統合 |
| `nemaki:webhookEventConfigs` | `nemaki:webhookConfigs` | プロパティ名を簡略化 |
| `nemaki:webhookSecret` | `webhookConfigs[].secret` | 各Webhook設定に内包 |
| `nemaki:webhookAuthType` | `webhookConfigs[].authType` | 各Webhook設定に内包 |
| `nemaki:webhookAuthCredential` | `webhookConfigs[].authCredential` | 各Webhook設定に内包 |
| `nemaki:webhookHeaders` | `webhookConfigs[].headers` | 各Webhook設定に内包 |
| `nemaki:webhookRetryCount` | `webhookConfigs[].retryCount` | 各Webhook設定に内包 |
| `nemaki:webhookIncludeChildren` | `webhookConfigs[].includeChildren` | 各Webhook設定に内包 |

### C.2 統一仕様の利点

`nemaki:webhookConfigs`への統一により、以下の利点が得られます：

1. **複数Webhook対応**: 1つのオブジェクトに複数のWebhook設定を登録可能
2. **イベント別設定**: イベントタイプごとに異なるURL・認証を設定可能
3. **プロパティ数削減**: 10個以上のプロパティを1つに集約
4. **拡張性**: 新しいフィールドをJSON内に追加可能
5. **一貫性**: API/UI/内部モデルで同一のデータ構造を使用

### C.3 マイグレーション（該当なし）

本機能は新規実装のため、既存データのマイグレーションは不要です。廃止プロパティは設計段階でのみ検討されたものであり、実装されていません。

将来的に設計変更が発生した場合のマイグレーション方針：

1. **後方互換性**: 旧フォーマットのデータも読み取り可能にする
2. **自動変換**: 旧フォーマット検出時に新フォーマットへ自動変換
3. **マイグレーションツール**: 一括変換用のCLIツールを提供
