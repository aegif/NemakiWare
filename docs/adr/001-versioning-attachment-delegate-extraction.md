# ADR-001: VersioningServiceDelegate / AttachmentServiceDelegate 抽出方針

## ステータス: 提案中

## 日付: 2026-02-15

## コンテキスト

ContentServiceImpl (4,636行, 188メソッド) の委譲パターン分割の一環として、
ArchiveServiceDelegate, AclServiceDelegate, UserGroupServiceDelegate, ChangeEventServiceDelegate
の4つは安全に抽出完了した。

残る VersioningServiceDelegate と AttachmentServiceDelegate の抽出は、
**共有privateメソッド群への深い依存**のため延期された。

本ADRは抽出戦略を定め、安全な実施手順を規定する。

---

## 依存関係マップ

### 1. Versioning系メソッド (抽出候補)

```
checkOut (L1328-1415)
├── getDocument [public, ContentDaoService委譲]
├── buildCopyDocument [private]
│   ├── setAclOnCreated [private]
│   │   ├── isTopLevel [public, repositoryInfoMap]
│   │   └── getAclInheritedWithDefault [public, AclServiceDelegate委譲済]
│   └── setSignature [private] ★共有
├── copyAttachment [private] ★Attachment系共有
│   ├── getAttachment [public, ContentDaoService委譲]
│   ├── setSignature [private] ★共有
│   └── contentDaoService.createAttachment
├── copyRenditions [private]
│   ├── getRendition [public, ContentDaoService委譲]
│   ├── setSignature [private] ★共有
│   └── contentDaoService.createRendition
├── updateVersionProperties [private]
│   ├── increasedVersionLabel [private] ★共有
│   └── updateFormerVersionFlags [private]
│       └── contentDaoService.getDocument / update
├── contentDaoService.create
├── contentDaoService.update
├── getVersionSeries [public, ContentDaoService委譲]
├── updateVersionSeriesWithPwc [private]
│   └── contentDaoService.getVersionSeries / update
├── contentDaoService.getAllVersions
├── writeChangeEvent [private/public] ★共有
│   ├── setSignature [private] ★共有
│   ├── generateChangeToken [private]
│   ├── contentDaoService.create (Change)
│   └── webhookService.triggerWebhook
├── solrUtil.indexDocument
└── nemakiCachePool (キャッシュ無効化)

cancelCheckOut (L1417-1486)
├── getDocument [public]
├── writeChangeEvent [private/public] ★共有
├── contentDaoService.delete
├── getVersionSeries [public]
├── setModifiedSignature [private]
│   └── setSignature [private] ★共有
├── contentDaoService.update (VersionSeries)
├── contentDaoService.getAllVersions
├── solrUtil.deleteDocument
└── nemakiCachePool (キャッシュ無効化)

checkIn (L1488-1560)
├── getDocument [public]
├── buildCopyDocument [private] (上記参照)
├── getDocumentOfLatestVersion [public, ContentDaoService委譲]
├── mergeSecondaryTypesFromLatest [private]
├── copyAttachment [private] ★Attachment系共有
├── createAttachment [public] ★Attachment系
│   ├── setSignature [private] ★共有
│   ├── calculateStreamSize [private]
│   └── contentDaoService.createAttachment
├── modifyProperties [public, 複雑]
├── setSignature [private] ★共有
├── cancelCheckOut (上記、再帰呼出し)
├── updateVersionProperties [private] (上記参照)
├── contentDaoService.create
├── applyPolicy [public]
│   ├── getPolicy [public]
│   ├── contentDaoService.update
│   └── writeChangeEvent [private/public] ★共有
├── writeChangeEvent [private/public] ★共有
└── solrUtil.indexDocument
```

### 2. Attachment系メソッド (抽出候補)

```
createAttachment (L3420-3466) [public]
├── setSignature [private] ★共有
├── calculateStreamSize [private]
└── contentDaoService.createAttachment

copyAttachment (L2217-2257) [private]
├── getAttachment [public, ContentDaoService委譲]
├── setSignature [private] ★共有
├── contentDaoService.createAttachment
└── nemakiCachePool (キャッシュ無効化)

copyRenditions (L2259-2281) [private]
├── getRendition [public, ContentDaoService委譲]
├── setSignature [private] ★共有
└── contentDaoService.createRendition
```

### 3. 共有privateメソッド一覧 (★マーク)

| メソッド | 行 | Versioning使用 | Attachment使用 | その他使用 |
|----------|-----|:-:|:-:|:-:|
| setSignature | L3761-3774 | checkOut, checkIn, cancelCheckOut | createAttachment, copyAttachment, copyRenditions | create, update, writeChangeEvent 他多数 |
| writeChangeEvent | L741-809 | checkOut, checkIn, cancelCheckOut | - | create, update, delete, move, applyPolicy 他多数 |
| generateChangeToken | L811-813 | (via writeChangeEvent) | - | (via writeChangeEvent) |
| buildCopyDocument | L1691-1709 | checkOut, checkIn | - | - |
| setAclOnCreated | L1664-1689 | (via buildCopyDocument) | - | create |
| updateVersionProperties | L1786-1829 | checkOut, checkIn | - | - |
| increasedVersionLabel | L3728-3759 | (via updateVersionProperties) | - | - |
| updateFormerVersionFlags | L1831-1856 | (via updateVersionProperties) | - | - |
| updateVersionSeriesWithPwc | L1877-1921 | checkOut | - | - |
| mergeSecondaryTypesFromLatest | L1562-1604 | checkIn | - | - |
| copyAttachment | L2217-2257 | checkOut, checkIn | ← 自身 | - |
| copyRenditions | L2259-2281 | checkOut | ← 自身 | - |
| calculateStreamSize | L3468-3506 | (via createAttachment in checkIn) | createAttachment | - |

### 4. 外部依存 (フィールド/Bean)

| 依存 | Versioning | Attachment | 既存Delegate |
|------|:-:|:-:|:-:|
| contentDaoService | ○ | ○ | 全delegate |
| nemakiCachePool | ○ | ○ | AclServiceDelegate |
| solrUtil | ○ | - | ArchiveServiceDelegate |
| webhookService | ○ (via writeChangeEvent) | - | - |
| repositoryInfoMap | ○ (via isTopLevel) | - | UserGroupServiceDelegate |
| propertyManager | - | - | AclServiceDelegate |

---

## 決定: 段階的抽出 + ヘルパークラス方式

### 方針A: 共有メソッドをUtilityクラスに抽出 (採用)

```
businesslogic/impl/
├── ContentServiceImpl.java
├── delegate/
│   ├── ArchiveServiceDelegate.java      (抽出済)
│   ├── AclServiceDelegate.java          (抽出済)
│   ├── UserGroupServiceDelegate.java    (抽出済)
│   ├── ChangeEventServiceDelegate.java  (抽出済)
│   ├── VersioningServiceDelegate.java   (新規)
│   ├── AttachmentServiceDelegate.java   (新規)
│   └── ContentServiceHelper.java        (新規: 共有privateメソッド群)
```

**ContentServiceHelper** には以下を移動:
- `setSignature(CallContext, NodeBase)` — 全delegate + ContentServiceImpl本体が使用
- `generateChangeToken(NodeBase)` — writeChangeEvent内で使用
- `writeChangeEvent(...)` — Versioning + CRUD共通
- `increasedVersionLabel(Document, VersioningState)` — Versioning専用だがUtility性質

ContentServiceHelper はステートレスなユーティリティメソッド群とし、
必要なサービス参照 (contentDaoService, webhookService) はコンストラクタで受け取る。

```java
public class ContentServiceHelper {
    private final ContentDaoService contentDaoService;
    private final WebhookService webhookService;  // nullable

    public ContentServiceHelper(ContentDaoService contentDaoService, WebhookService webhookService) {
        this.contentDaoService = contentDaoService;
        this.webhookService = webhookService;
    }

    public void setSignature(CallContext callContext, NodeBase node) { ... }
    public String writeChangeEvent(CallContext callContext, String repositoryId,
            Content content, Acl acl, ChangeType changeType) { ... }
    public String increasedVersionLabel(Document document, VersioningState versioningState) { ... }
    // etc.
}
```

**VersioningServiceDelegate**:
```java
public class VersioningServiceDelegate {
    private final ContentDaoService contentDaoService;
    private final ContentServiceHelper helper;
    private final NemakiCachePool nemakiCachePool;
    private final Supplier<SolrUtil> solrUtil;
    private final ContentService contentService; // 自身のpublic IF参照 (getDocument等)

    // checkOut, cancelCheckOut, checkIn
    // buildCopyDocument, updateVersionProperties, updateVersionSeriesWithPwc
    // updateFormerVersionFlags, mergeSecondaryTypesFromLatest
    // copyRenditions (Versioning固有のレンディションコピー)
}
```

**AttachmentServiceDelegate**:
```java
public class AttachmentServiceDelegate {
    private final ContentDaoService contentDaoService;
    private final ContentServiceHelper helper;
    private final NemakiCachePool nemakiCachePool;

    // createAttachment, copyAttachment, calculateStreamSize
}
```

### 却下した方針

**方針B: 共有メソッドをContentServiceImplに残してDelegate側から参照**
- 問題: Delegateが親クラス参照を持つ循環依存が発生
- ContentServiceImpl → VersioningServiceDelegate → ContentServiceImpl (setSignature等)
- テスタビリティが低下

**方針C: 共有メソッドを各Delegateに複製**
- 問題: DRY原則違反。setSignatureが3箇所に重複
- バグ修正時の見落としリスク

### 方針A の利点
- 循環依存なし (Helper → ContentDaoService のみ)
- DRY原則維持
- テスタビリティ良好 (Helperを個別にテスト可能)
- 段階的抽出可能 (Helper → AttachmentDelegate → VersioningDelegate)

---

## 実施手順

### Phase 1: ContentServiceHelper 抽出
1. ContentServiceHelper クラスを作成
2. setSignature, generateChangeToken, writeChangeEvent, increasedVersionLabel を移動
3. ContentServiceImpl 内の呼び出し元を `helper.xxx()` に変更
4. 振る舞い固定テスト (checkOut/checkIn/createAttachment) を実行し回帰なし確認
5. QA 94/94 + TCK 11/11 確認

### Phase 2: AttachmentServiceDelegate 抽出
1. createAttachment, copyAttachment, copyRenditions, calculateStreamSize を移動
2. ContentServiceImpl の対応メソッドを委譲に変更
3. テスト確認

### Phase 3: VersioningServiceDelegate 抽出
1. checkOut, cancelCheckOut, checkIn を移動
2. buildCopyDocument, setAclOnCreated, updateVersionProperties 等のVersioning専用privateメソッドを移動
3. テスト確認

### 各Phase完了条件
- コンパイル成功
- FilesystemImporterTest 24/24 PASS
- VersioningBehaviorTest 17/17 PASS (Skipped: 0)
- QA統合テスト 94/94 PASS
- TCK 11/11 PASS (checkOut/checkIn/cancelCheckOut のテストを含む)

---

## リスクと緩和策

| リスク | 影響 | 緩和策 |
|--------|------|--------|
| checkIn→cancelCheckOut再帰呼出し | Delegate間相互参照 | VersioningServiceDelegateにcancelCheckOutも含め、内部呼出しに変更 |
| writeChangeEventの多数の呼出し元 | 変更範囲が広い | Helper抽出時にContentServiceImplの全呼出し元を一括置換 |
| setAclOnCreatedのAclServiceDelegate依存 | Delegate間依存 | contentService (public IF) 経由で呼出し、Delegate直接依存を回避 |
| Spring XML設定変更 | 設定ファイル不整合 | Delegate生成はContentServiceImpl.initDelegates()内、XML変更不要 |

---

## 結果

### 実施完了 (2026-02-15)

**Phase 1: ContentServiceHelper 抽出** - 完了
- `setSignature`, `setModifiedSignature`, `getTimeStamp`, `generateChangeToken`, `writeChangeEvent` (2オーバーロード), `increasedVersionLabel` を移動
- ContentServiceImpl 側はフォワーディングメソッドで委譲 (呼び出し元の変更最小化)

**Phase 2: AttachmentServiceDelegate 抽出** - 完了
- `createAttachment`, `copyAttachment`, `copyRenditions`, `calculateStreamSize` を移動
- ContentDaoService + ContentServiceHelper + NemakiCachePool を依存として受け取り

**Phase 3: VersioningServiceDelegate 抽出** - 完了 (方針修正あり)
- `updateVersionProperties`, `updateFormerVersionFlags`, `updateVersionSeriesWithPwc`, `mergeSecondaryTypesFromLatest` を移動
- checkOut/cancelCheckOut/checkIn 本体は ContentServiceImpl に残す方針に修正
  - 理由: これらのメソッドは `buildCopyDocument`, `setAclOnCreated`, `modifyProperties`, `applyPolicy` 等の
    versioning以外でも使用される private メソッドへの依存が多く、移動のメリットが薄い
- ContentDaoService + ContentServiceHelper を依存として受け取り

### テスト結果
- FilesystemImporterTest: 24/24 PASS
- VersioningBehaviorTest: 17/17 PASS (Skipped: 0)
- TCK: 11/11 PASS (BasicsTestGroup 3, TypesTestGroup 3, ControlTestGroup 1, VersioningTestGroup 4)

### 最終構造
```
businesslogic/impl/
├── ContentServiceImpl.java (コア: CRUD + ナビゲーション + checkOut/checkIn/cancelCheckOut)
├── delegate/
│   ├── ContentServiceHelper.java      (新規: 共有ユーティリティ)
│   ├── ArchiveServiceDelegate.java    (既存)
│   ├── AclServiceDelegate.java        (既存)
│   ├── UserGroupServiceDelegate.java  (既存)
│   ├── ChangeEventServiceDelegate.java (既存)
│   ├── AttachmentServiceDelegate.java (新規: 添付・レンディション)
│   └── VersioningServiceDelegate.java (新規: バージョン属性管理)
```
