# ChangeLog生成問題の修正手順

## 問題の概要

Jakarta EE移行後、CMISサービス経由でフォルダ・ドキュメントを作成しても、ChangeLogエントリが正しく生成されない問題が発生している。

### 症状
1. **CMISサービス**: フォルダ作成は成功（HTTP 200）
2. **ChangeEvent生成**: ContentServiceImpl.writeChangeEventは実行される（ログで確認済み）
3. **CouchDB保存**: Changeドキュメントが作成される（ID/revisionのみ）
4. **内容が空**: 実際のChangeドキュメントの内容が空になる
5. **View検索失敗**: `changesByToken`ビューで取得できない（0件）
6. **Solrインデクシング不能**: TrackerがChangeを検知できない

### 根本原因

**CloudantClientWrapper.java**の`ObjectMapper.convertValue()`メソッドが、CouchChangeオブジェクトの複雑な継承階層を正しくMapに変換できない。

- `CouchChange` -> `CouchNodeBase` -> 基底クラス
- `ChangeType` enum
- `GregorianCalendar` オブジェクト
- Jackson ObjectMapperの設定不備

## 修正方法

### 1. CloudantClientWrapper.javaの修正

**ファイル**: `/core/src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java`

**修正箇所**: `create(Object document)`メソッド（lines 648-694）

```java
// CRITICAL FIX: Handle CouchChange objects manually due to ObjectMapper issues
if (document instanceof jp.aegif.nemaki.model.couch.CouchChange) {
    jp.aegif.nemaki.model.couch.CouchChange change = (jp.aegif.nemaki.model.couch.CouchChange) document;
    log.error("CLOUDANT CREATE: Using manual mapping for CouchChange");
    
    documentMap = new java.util.HashMap<>();
    // Required fields for change documents
    documentMap.put("type", change.getType());
    documentMap.put("created", change.getCreated() != null ? change.getCreated().getTimeInMillis() : null);
    documentMap.put("creator", change.getCreator());
    documentMap.put("modified", change.getModified() != null ? change.getModified().getTimeInMillis() : null);
    documentMap.put("modifier", change.getModifier());
    
    // Change-specific fields
    documentMap.put("objectId", change.getObjectId());
    documentMap.put("token", change.getToken());
    documentMap.put("changeType", change.getChangeType() != null ? change.getChangeType().toString() : null);
    documentMap.put("time", change.getTime() != null ? change.getTime().getTime() : null);
    documentMap.put("name", change.getName());
    documentMap.put("baseType", change.getBaseType());
    documentMap.put("objectType", change.getObjectType());
    documentMap.put("versionSeriesId", change.getVersionSeriesId());
    documentMap.put("versionLabel", change.getVersionLabel());
    documentMap.put("policyIds", change.getPolicyIds());
    documentMap.put("acl", change.getAcl());
    documentMap.put("paretnId", change.getParetnId());
    
    // Additional properties
    documentMap.put("additionalProperties", new java.util.HashMap<>());
    
    // Content type flags
    documentMap.put("content", change.isContent());
    documentMap.put("document", change.isDocument());
    documentMap.put("folder", change.isFolder());
    documentMap.put("attachment", change.isAttachment());
    documentMap.put("relationship", change.isRelationship());
    documentMap.put("policy", change.isPolicy());
    
    log.error("CLOUDANT CREATE: Manual mapping completed, map size: " + documentMap.size());
} else {
    // Use ObjectMapper for other document types
    @SuppressWarnings("unchecked")
    Map<String, Object> tempMap = mapper.convertValue(document, Map.class);
    documentMap = tempMap;
}
```

### 2. デバッグログの追加

CouchChangeオブジェクトの詳細ログを追加（lines 602-637）:

```java
// Add debug logging for CouchChange objects - CRITICAL FIX
if (document instanceof jp.aegif.nemaki.model.couch.CouchChange) {
    jp.aegif.nemaki.model.couch.CouchChange change = (jp.aegif.nemaki.model.couch.CouchChange) document;
    log.error("CLOUDANT CREATE: CouchChange before mapping - DETAILED ANALYSIS");
    log.error("  - ID: " + change.getId());
    log.error("  - Type: " + change.getType());
    log.error("  - ObjectId: " + change.getObjectId());
    log.error("  - Token: " + change.getToken());
    log.error("  - ChangeType: " + change.getChangeType());
    // ... 他のフィールド
}
```

## 検証方法

### 1. フォルダ作成テスト

```bash
curl -u admin:admin -X POST \
  -H "Content-Type: application/atom+xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
  <atom:title>Test Folder</atom:title>
  <cmisra:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:folder</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>Test Folder</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmisra:object>
</atom:entry>' \
  "http://localhost:8080/core/atom/bedroom/children?id=e02f784f8360a02cc14d1314c10038ff"
```

### 2. ChangeLog確認

```bash
# CMISサービス経由
curl -u admin:admin "http://localhost:8080/core/atom/bedroom/changes"

# CouchDB直接確認
curl -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/changesByToken"
```

### 3. Solrインデクシング確認

```bash
# 作成したフォルダがインデックスされているか
curl "http://localhost:8983/solr/nemaki/select?q=name:\"Test Folder\"&wt=json"
```

## 期待される結果

修正後:
1. **ChangeLogエントリ**: 適切に生成される（type=change, token, objectId等）
2. **CouchDBビュー**: `changesByToken`で取得可能
3. **Solrインデクシング**: TrackerがChangeを検知してインデクシング実行
4. **全文検索**: 作成したフォルダ・ドキュメントが検索可能

## 追加の修正が必要な場合

### ObjectMapper設定の改善

```java
private ObjectMapper createConfiguredObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    
    // Field-based access for complex inheritance
    mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
    mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    mapper.setVisibility(PropertyAccessor.GETTER, Visibility.ANY);
    
    // Handle enums properly
    mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
    
    return mapper;
}
```

### CouchChangeクラスのJacksonアノテーション追加

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchChange extends CouchNodeBase {
    // existing fields...
    
    @JsonProperty("changeType")
    @JsonSerialize(using = ToStringSerializer.class)
    private ChangeType changeType;
    
    // existing methods...
}
```

## 重要な注意事項

1. **Jakarta化の影響**: この問題はJakarta EE移行とCloudant SDK採用の結果
2. **過去バージョン混入の回避**: 修正時は完全なクリーンビルドを実行
3. **設計文書の互換性**: `changesByToken`ビューは標準仕様を維持
4. **デバッグログの活用**: 問題解析時は詳細ログを確認

## 修正の適用順序

1. **CloudantClientWrapper.java修正**: 手動Map構築の追加
2. **コンパイル・ビルド**: Java 17環境で完全ビルド
3. **デプロイ**: 新しいWARファイルでコンテナ更新
4. **テスト**: フォルダ作成 -> ChangeLog確認 -> Solrインデクシング確認
5. **文書化**: 修正内容をドキュメント化（本ファイル）
6. **初期化プロセス統合**: 修正を初期化プロセスに組み込み