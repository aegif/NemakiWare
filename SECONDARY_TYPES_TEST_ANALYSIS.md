# SecondaryTypesTest 失敗の詳細分析レポート

## 調査概要

PropertyDefinition共有問題は既に解決済み（baseTypesTest、createAndDeleteTypeTest成功）ですが、secondaryTypesTestは別の回帰により失敗し続けています。

## 根本原因の特定

### 1. 失敗の症状
- **エラー**: CmisRuntimeException: "Content stream is null"
- **直接原因**: Secondary Typeを持つドキュメントが`attachmentNodeId = null`で作成される
- **結果**: `ObjectServiceImpl.getContentStream()`がnullを返し、TCKテストが失敗

### 2. 詳細な技術分析

#### A. ContentServiceImpl.javaでの詳細なデバッグログ
```java
// SECONDARY TYPES TEST DEBUG - Critical tracking for attachmentNodeId
System.err.println("⚠️  WARNING: attachmentNodeId is NULL in final result - THIS WILL CAUSE Content stream is null!");
System.err.println("⚠️  Secondary Types Test will fail with CmisRuntimeException: Content stream is null!");
```

#### B. Attachment作成条件の分析
```java
// CMIS 1.1 SPECIFICATION COMPLIANT: Evaluate attachment creation conditions correctly
boolean conditionA = (csa == ContentStreamAllowed.REQUIRED);
boolean conditionB = (csa == ContentStreamAllowed.ALLOWED && contentStream != null);
boolean overallCondition = conditionA || conditionB;
```

### 3. バージョン間比較

#### v2.4.2-candidate（動作版）
```java
// Secondary properties
List<Aspect> secondary = buildSecondaryTypes(repositoryId, properties, content);
if (!CollectionUtils.isEmpty(secondary)) {
    content.setAspects(secondary);
}
```

#### 現在のバージョン（失敗版）
```java
// Secondary
List<Aspect> secondary = buildSecondaryTypes(repositoryId, properties, content);
if (!CollectionUtils.isEmpty(secondary)) {
    content.setAspects(secondary);
}
```

**重要な発見**: `buildSecondaryTypes`メソッド自体の実装は両バージョンで同一です。

## 回帰の推定原因

### 1. Jakarta EE 10移行の影響
- ContentStream処理の微細な変更
- OpenCMIS自己ビルドによる動作の変化
- Tomcat 10でのクラスローダー動作の変更

### 2. Secondary Type特有の処理タイミング問題
- Secondary Typeプロパティの処理順序
- ContentStreamとSecondary Type処理の相互作用
- attachment作成条件の評価タイミング

### 3. CMIS 1.1準拠実装の副作用
現在のコードには以下のCMIS 1.1準拠コメントがあります：
```java
// CMIS 1.1 SPECIFICATION COMPLIANT: Evaluate attachment creation conditions correctly
boolean conditionB = (csa == ContentStreamAllowed.ALLOWED && contentStream != null); // RESTORED: Correct CMIS 1.1 spec
```

## 具体的な調査結果

### 1. buildSecondaryTypes メソッドの動作
```java
private List<Aspect> buildSecondaryTypes(String repositoryId, Properties properties, Content content) {
    List<Aspect> aspects = new ArrayList<Aspect>();
    PropertyData secondaryTypeIds = properties.getProperties().get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
    
    List<String> ids = new ArrayList<String>();
    if (secondaryTypeIds == null) {
        ids = getSecondaryTypeIds(content);
    } else {
        ids = secondaryTypeIds.getValues();
    }
    
    for (String secondaryTypeId : ids) {
        org.apache.chemistry.opencmis.commons.definitions.TypeDefinition td = getTypeManager()
                .getTypeDefinition(repositoryId, secondaryTypeId);
        Aspect aspect = new Aspect();
        aspect.setName(secondaryTypeId);
        
        List<Property> props = injectPropertyValue(td.getPropertyDefinitions().values(), properties, content);
        
        aspect.setProperties(props);
        aspects.add(aspect);
    }
    return aspects;
}
```

### 2. Secondary Type処理の流れ
1. **PHASE 1**: Document基本情報の構築
2. **PHASE 2**: Attachment作成（条件に基づく）
3. **PHASE 3**: Version properties設定
4. **PHASE 4**: Document作成（DAO）
5. **PHASE 5**: Version series更新
6. **PHASE 6**: Change event & indexing

**問題**: Secondary Type処理がAttachment作成の前に行われるため、Secondary Type固有のContentStream要件が正しく評価されていない可能性があります。

## 推奨される解決アプローチ

### 1. 緊急対応（短期）
- Secondary TypeドキュメントでのContentStream処理ロジックの詳細調査
- TCK SecondaryTypesTestの具体的な期待値の確認
- Secondary Type固有のattachment作成条件の再評価

### 2. 根本対応（中期）
- Secondary Type処理とContentStream処理の統合見直し
- CMIS 1.1準拠実装の副作用の詳細調査
- Jakarta EE 10移行による影響の特定と修正

### 3. 検証方法
- Secondary Typeを持つドキュメント作成の単体テスト
- ContentStreamAllowed設定の各パターンでのテスト
- 過去の動作版との詳細な動作比較

## 結論

secondaryTypesTestの失敗は、PropertyDefinition共有とは無関係の**Secondary Type機能の回帰**です。Jakarta EE 10移行やOpenCMIS自己ビルドの過程で、Secondary TypeドキュメントのContentStream処理に微細な変更が加わり、attachment作成条件の評価に影響を与えている可能性が高いです。

特に、Secondary Type固有のContentStream要件の処理タイミングや条件評価に問題があると推定されます。

---
*調査実施者: Devin AI*  
*調査日時: 2025年9月9日*  
*対象ブランチ: feature/unit-test-recovery*
