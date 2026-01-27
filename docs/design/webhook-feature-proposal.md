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

- **タイプシステム**: CMIS標準のタイプ継承機構を使用（`nemaki:folder` extends `cmis:folder`）
- **プロパティ定義**: CMIS標準のプロパティ定義形式を使用
- **イベントタイプ**: CMISのChangeType（CREATED, UPDATED, DELETED, SECURITY）に準拠

---

## 2. 新規タイプ定義

### 2.1 nemaki:folder

`cmis:folder`を継承したNemakiWare拡張フォルダタイプ。

```
タイプID: nemaki:folder
親タイプ: cmis:folder
表示名: NemakiWare Folder
説明: Webhook機能を持つ拡張フォルダタイプ
```

**追加プロパティ**:

| プロパティID | 表示名 | 型 | カーディナリティ | 必須 | 説明 |
|-------------|--------|-----|-----------------|------|------|
| nemaki:webhookEnabled | Webhook有効 | Boolean | single | No | Webhook通知の有効/無効 |
| nemaki:webhookUrl | Webhook URL | String | single | No | デフォルト通知先URL |
| nemaki:webhookEvents | 監視イベント | String | multi | No | 監視するイベントタイプのリスト |
| nemaki:webhookEventConfigs | イベント別設定 | String | single | No | イベントタイプ毎の個別設定（JSON形式、詳細は2.4参照） |
| nemaki:webhookSecret | Webhook Secret | String | single | No | HMAC-SHA256署名検証用シークレット |
| nemaki:webhookAuthType | 認証タイプ | String | single | No | 認証方式（none/basic/bearer/apikey） |
| nemaki:webhookAuthCredential | 認証情報 | String | single | No | 認証用クレデンシャル（暗号化保存） |
| nemaki:webhookHeaders | カスタムヘッダー | String | multi | No | カスタムHTTPヘッダー（JSON形式） |
| nemaki:webhookRetryCount | リトライ回数 | Integer | single | No | 失敗時のリトライ回数（デフォルト: 3） |
| nemaki:webhookIncludeChildren | 子要素含む | Boolean | single | No | 子フォルダ/ドキュメントのイベントも通知 |
| nemaki:webhookMaxDepth | 最大監視深度 | Integer | single | No | 子孫を監視する最大階層数（デフォルト: アプリ設定値） |

### 2.2 nemaki:document

`cmis:document`を継承したNemakiWare拡張ドキュメントタイプ。

```
タイプID: nemaki:document
親タイプ: cmis:document
表示名: NemakiWare Document
説明: Webhook機能を持つ拡張ドキュメントタイプ
```

**追加プロパティ**:

| プロパティID | 表示名 | 型 | カーディナリティ | 必須 | 説明 |
|-------------|--------|-----|-----------------|------|------|
| nemaki:webhookEnabled | Webhook有効 | Boolean | single | No | Webhook通知の有効/無効 |
| nemaki:webhookUrl | Webhook URL | String | single | No | デフォルト通知先URL |
| nemaki:webhookEvents | 監視イベント | String | multi | No | 監視するイベントタイプのリスト |
| nemaki:webhookEventConfigs | イベント別設定 | String | single | No | イベントタイプ毎の個別設定（JSON形式、詳細は2.4参照） |
| nemaki:webhookSecret | Webhook Secret | String | single | No | HMAC-SHA256署名検証用シークレット |
| nemaki:webhookAuthType | 認証タイプ | String | single | No | 認証方式（none/basic/bearer/apikey） |
| nemaki:webhookAuthCredential | 認証情報 | String | single | No | 認証用クレデンシャル（暗号化保存） |
| nemaki:webhookHeaders | カスタムヘッダー | String | multi | No | カスタムHTTPヘッダー（JSON形式） |
| nemaki:webhookRetryCount | リトライ回数 | Integer | single | No | 失敗時のリトライ回数（デフォルト: 3） |

### 2.3 監視イベントタイプ

`nemaki:webhookEvents`プロパティで指定可能なイベントタイプ：

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

### 2.4 イベント別Webhook設定

`nemaki:webhookEventConfigs`プロパティを使用して、イベントタイプ毎に異なるURL・認証情報・ヘッダーを設定できます。

**JSON形式**:

```json
[
  {
    "events": ["CREATED", "CHILD_CREATED"],
    "url": "https://example.com/webhooks/new-content",
    "authType": "bearer",
    "authCredential": "token-for-new-content",
    "headers": {"X-Custom-Header": "value1"}
  },
  {
    "events": ["UPDATED", "CONTENT_UPDATED", "CHILD_UPDATED"],
    "url": "https://example.com/webhooks/updates",
    "authType": "basic",
    "authCredential": "user:password",
    "headers": {}
  },
  {
    "events": ["SECURITY"],
    "url": "https://security-audit.example.com/acl-changes",
    "authType": "apikey",
    "authCredential": "X-API-Key:secret-key",
    "headers": {}
  },
  {
    "events": ["DELETED", "CHILD_DELETED"],
    "url": "https://example.com/webhooks/deletions",
    "authType": "none",
    "authCredential": null,
    "headers": {}
  }
]
```

**動作仕様**:

1. イベント発生時、`nemaki:webhookEventConfigs`を検索し、該当イベントの設定を取得
2. 該当する設定がない場合、デフォルト設定（`nemaki:webhookUrl`等）を使用
3. 同一イベントが複数の設定にマッチする場合、最初にマッチした設定を使用
4. 各設定は独立した認証情報・ヘッダーを持てる

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

---

## 2.6 フォルダ配下イベントの効率的な取得

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
      "webhookEnabled": true,
      "webhookUrl": "https://example.com/webhook",
      "webhookEvents": ["CREATED", "UPDATED", "DELETED", "CHILD_CREATED"],
      "includeChildren": true,
      "maxDepth": 5,
      "lastDelivery": {
        "deliveryId": "uuid",
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
      "webhookEnabled": true,
      "webhookUrl": "https://contracts.example.com/notify",
      "webhookEvents": ["UPDATED", "CONTENT_UPDATED", "SECURITY"],
      "includeChildren": false,
      "maxDepth": null,
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

### 6.1 デフォルトタイプ選択

UIでフォルダやドキュメントを作成する際、デフォルトで`nemaki:folder`/`nemaki:document`が選択されるように変更：

```typescript
// cmis.ts - createDocument/createFolder のデフォルト値変更

async createDocument(repositoryId: string, folderId: string, file: File, 
                     properties?: Record<string, unknown>): Promise<CMISObject> {
  const defaults = {
    'cmis:name': file.name,
    'cmis:objectTypeId': 'nemaki:document',  // 変更: cmis:document → nemaki:document
  };
  // ...
}

async createFolder(repositoryId: string, parentId: string, name: string,
                   properties?: Record<string, unknown>): Promise<CMISObject> {
  const defaults = {
    'cmis:name': name,
    'cmis:objectTypeId': 'nemaki:folder',  // 変更: cmis:folder → nemaki:folder
  };
  // ...
}
```

### 6.2 Webhook設定UI

プロパティエディタにWebhook設定セクションを追加：

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

複数Webhook対応のため、`nemaki:webhookConfigs`プロパティを使用：

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

### Phase 1: 基盤実装（2.5週間）

1. `nemaki:folder`と`nemaki:document`タイプ定義の追加
2. `WebhookService`インターフェースと基本実装
3. `WebhookDispatcher`の非同期配信実装
4. `WebhookDeliveryLog`モデルとDAO
5. `WebhookConfigCache`（PathTrie）の実装
6. 監査ログモデルとDAO
7. **RSSトークンモデルとDAO**

### Phase 2: ContentService統合（1週間）

1. `writeChangeEvent()`へのWebhookトリガー追加
2. 追加イベントポイント（checkOut, checkIn, move等）の実装
3. `WebhookConfigCache`を使用した効率的なWebhook設定検索
4. 監査ログ記録の統合
5. **フォルダ/ドキュメント変更取得メソッド追加（RSS用）**

### Phase 3: REST API（2週間）

1. 登録済みWebhook一覧取得API
2. 配信ログ取得API
3. 手動再送API
4. Webhookテスト送信API
5. 監査ログ取得API
6. **RSSフィードAPI（フォルダ/ドキュメント/リポジトリ）**
7. **RSSトークン管理API**

### Phase 4: UI実装（3.5週間）

1. デフォルトタイプ選択の変更
2. Webhook設定UIコンポーネント（複数Webhook対応）
3. 配信ログビューア
4. テスト送信機能
5. **管理画面: 登録済みWebhook一覧**
6. **管理画面: 監査ログビューア**
7. **RSSフィード購読UI（フォルダ/ドキュメント詳細画面）**
8. **RSSトークン管理UI**

### Phase 5: テスト・ドキュメント（2週間）

1. ユニットテスト
2. 統合テスト
3. E2Eテスト（管理画面・RSSフィード含む）
4. ユーザードキュメント
5. 管理者ドキュメント
6. **RSSフィード利用ガイド**

**合計: 約11週間**

---

## 11. 代替案の検討

### 11.1 Secondary Typeとしての実装

**案**: Webhook設定をSecondary Type（`nemaki:webhookable`）として実装

**メリット**:
- 既存の`cmis:folder`/`cmis:document`にも適用可能
- より柔軟な適用範囲

**デメリット**:
- UIでのデフォルト選択が複雑になる
- Secondary Typeの追加操作が必要

**結論**: 今回は新規タイプとして実装し、将来的にSecondary Type版も検討

### 11.2 グローバルWebhook設定

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

1. **タイプ名の確認**: `nemaki:folder`/`nemaki:document`で問題ないか？
2. **イベントタイプの追加**: 他に必要なイベントタイプはあるか？
3. **ペイロード形式**: 追加で含めるべき情報はあるか？
4. **セキュリティ要件**: 追加のセキュリティ要件はあるか？
5. **優先度**: Phase分けの優先度調整は必要か？

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
