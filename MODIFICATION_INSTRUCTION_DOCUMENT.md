# NemakiWare 修正指示書
## feature/unit-test-recovery ブランチ対応項目

### 📋 **概要**
本文書は、feature/unit-test-recoveryブランチで特定された問題点と必要な修正項目を優先度別に整理したものです。

---

## 🚨 **緊急対応項目 (Priority 1)**

### 1.1 TCK Type Test 失敗問題の解決
**現状**: 3つのTCKテストすべてが失敗中
- `createAndDeleteTypeTest`: CmisObjectNotFoundException [objectTypeId:cmis:document]
- `secondaryTypesTest`: NullPointerException
- `baseTypesTest`: Type definition mismatches

**修正箇所**: 
- <ref_file file="/home/ubuntu/repos/NemakiWare/core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java" />

**具体的修正内容**:
```java
// PropertyDefinitionのinheritedフラグを正しく設定
// 基本タイプ: inherited=false
// 派生タイプの基本プロパティ: inherited=true
```

**検証方法**:
```bash
mvn test -Dtest=TypesTestGroup -DfailIfNoTests=false
```

### 1.2 PropertyDefinition継承フラグ修正
**問題**: すべてのプロパティで`inherited=false`になっている
**期待値**: CMIS標準プロパティは派生タイプで`inherited=true`であるべき

**修正対象メソッド**:
- `buildTypeDefinitionFromDB()`系メソッド
- `addBasePropertyDefinitions()`メソッド
- `shouldBeInherited()`メソッドの条件見直し

### 1.3 OpenCMIS依存関係の解決
**問題**: クライアント側JARファイルが不足
**不足ファイル**:
- `chemistry-opencmis-client-api-*.jar`
- `chemistry-opencmis-client-impl-*.jar`
- `chemistry-opencmis-client-bindings-*.jar`
- `chemistry-opencmis-test-tck-*.jar`

**対応方法**:
```bash
cd lib/nemaki-opencmis-1.1.0-jakarta/chemistry-opencmis-client
mvn clean install -DskipTests
# 生成されたJARを lib/built-jars/ に配置
```

---

## ⚠️ **重要対応項目 (Priority 2)**

### 2.1 Spring ProxyFactoryBean設定修正
**問題**: TypeManagerの複数インスタンス作成
**修正箇所**: <ref_file file="/home/ubuntu/repos/NemakiWare/core/src/main/webapp/WEB-INF/classes/serviceContext.xml" />

**修正内容**:
```xml
<bean id="TypeManager" class="org.springframework.aop.framework.ProxyFactoryBean" scope="singleton">
    <property name="singleton" value="true" />
    <property name="proxyInterfaces">
        <list>
            <value>jp.aegif.nemaki.cmis.aspect.type.TypeManager</value>
        </list>
    </property>
    <property name="target">
        <ref bean="typeManager" />
    </property>
</bean>
<bean id="typeManager" class="jp.aegif.nemaki.cmis.aspect.type.impl.TypeManagerImpl" 
      scope="singleton" init-method="init" depends-on="typeService">
    <!-- 既存プロパティ -->
</bean>
```

### 2.2 JSON処理統一化
**問題**: Jackson vs OpenCMIS JSON競合
**修正箇所**: <ref_file file="/home/ubuntu/repos/NemakiWare/core/src/main/java/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.java" />

**修正方針**: OpenCMIS JSONライブラリのみ使用するよう統一

### 2.3 TypeDefinitionオブジェクト同一性保証
**問題**: `getTypeDescendants()`と`getTypeDefinition()`で異なるインスタンスを返す
**修正方針**: 
- 全ての型定義アクセスパスで同一インスタンスを返すよう修正
- `flattenTypeDefinitionContainer`での共有処理を完全実装

---

## 🔧 **改善項目 (Priority 3)**

### 3.1 クラスローダー問題の根本解決
**現状**: 診断ログは追加済み、根本解決は未実施
**検証方法**:
```bash
# ログでクラスローダー分離を確認
grep "ClassLoader:" catalina.out
grep "TYPES identity:" catalina.out
```

**代替案検討**:
- シングルトンパターンの明示的実装
- ProxyFactoryBeanの代わりに直接Bean参照

### 3.2 スレッドセーフティ強化
**現状**: ConcurrentHashMapは使用済み
**追加検討項目**:
- 初期化処理の同期化見直し
- キャッシュクリア処理の原子性保証

### 3.3 パフォーマンス最適化
**対象**:
- 動的初期化ロジックのオーバーヘッド削減
- 同期ブロックの最小化
- メモリ使用量の最適化

---

## 📊 **検証・テスト項目**

### 4.1 単体テスト
```bash
# TypeManager関連テスト
mvn test -Dtest=*TypeManager* -DfailIfNoTests=false

# TCKテスト
mvn test -Dtest=TypesTestGroup -DfailIfNoTests=false
```

### 4.2 統合テスト
```bash
# ブラウザバインディングテスト
curl -X GET "http://localhost:8080/core/browser/bedroom/types/cmis:document"

# AtomPubバインディングテスト  
curl -X GET "http://localhost:8080/core/atom/bedroom/types/cmis:document"
```

### 4.3 マルチスレッドテスト
- 同時アクセス負荷テスト
- キャッシュ無効化シナリオテスト
- メモリリーク検証

---

## 🎯 **実装順序の推奨**

### Phase 1: 緊急修正 (1-2日)
1. OpenCMIS依存関係解決
2. PropertyDefinition継承フラグ修正
3. TCKテスト実行・検証

### Phase 2: 安定化 (3-5日)
1. Spring ProxyFactoryBean設定修正
2. JSON処理統一化
3. TypeDefinitionオブジェクト同一性保証

### Phase 3: 最適化 (1週間)
1. クラスローダー問題根本解決
2. パフォーマンス最適化
3. 包括的テスト実施

---

## 📝 **注意事項**

### 開発時の注意点
- **診断ログ**: 本番環境では削除またはログレベル調整が必要
- **静的フィールド**: 現在はインスタンスフィールドに戻されているため、マルチインスタンス環境での動作確認が必要
- **Spring設定変更**: 他のサービスへの影響を慎重に検証

### テスト環境
- Java 17環境での動作確認
- Tomcat 10での動作確認
- Jakarta EE 10互換性確認

### 依存関係
- OpenCMIS 1.1.0-jakarta
- Spring 6.1.13
- ConcurrentHashMap使用によるスレッドセーフティ

---

## 🔍 **トラブルシューティング**

### よくある問題
1. **TCKテスト失敗**: OpenCMIS JARファイルの配置を確認
2. **型定義取得失敗**: TypeManagerの初期化状態を確認
3. **メモリリーク**: キャッシュクリア処理の実行を確認

### デバッグ方法
```bash
# TypeManager初期化状態確認
grep "TypeManagerImpl CONSTRUCTOR" catalina.out

# 型定義キャッシュ状態確認
grep "TYPES.*repositories" catalina.out

# クラスローダー状態確認
grep "ClassLoader:" catalina.out
```

---

## 📚 **関連ドキュメント**

- [INTEGRATION_SUMMARY.md](./INTEGRATION_SUMMARY.md): vk/e95d-types統合結果
- [TCK_ANALYSIS_REPORT.md](./TCK_ANALYSIS_REPORT.md): TCKテスト詳細分析
- [CLASSLOADER_INVESTIGATION_REPORT.md](./CLASSLOADER_INVESTIGATION_REPORT.md): クラスローダー問題調査

---

**最終更新**: 2025年9月9日  
**対象ブランチ**: feature/unit-test-recovery  
**最新コミット**: e0abdb497 (Merge branch 'vk/61b7-tck-type-t')
