# NemakiWare CMIS 1.1 TCK 完全準拠計画

## 現在の状況 (2025-08-25)

### 成果概要
- **QAテスト成功率**: 100% (56/56 tests passing)
- **基本機能**: すべて正常動作 (HTTP API, AtomPub Binding, CMIS core operations)
- **Jakarta EE 10**: 完全移行済み
- **OpenCMIS 1.1.0**: カスタムビルド統合済み

### 問題特定
- **TCKテスト**: 体系的失敗 (Browser Binding実装の標準非準拠)
- **根本原因**: `NemakiBrowserBindingServlet`がCMIS 1.1仕様に完全準拠していない
- **失敗パターン**: 全テストが"TCK FAILURE detected in test: [TEST_NAME] (BROWSER)"で失敗

## TCK完全準拠への技術的計画

### Phase 1: Browser Binding標準準拠調査 🔍

**目的**: OpenCMIS TCKクライアントが要求するCMIS 1.1 Browser Binding仕様の詳細分析

**実行内容**:
1. **CMIS 1.1 Browser Binding仕様書精読**
   - OASIS CMIS v1.1 Part I: Domain Model (Browser Binding section)
   - HTTP POST/GET パラメータ形式要求
   - JSON レスポンス構造標準

2. **OpenCMIS TCKクライアント期待値解析**
   - `rootFolderTest`が期待するJSONレスポンス形式
   - プロパティ配列構造、メタデータ形式
   - エラーハンドリング標準

3. **現在実装との差分特定**
   - `NemakiBrowserBindingServlet.java`の非準拠箇所
   - レスポンスヘッダー、Content-Type設定
   - パラメータ解析ロジック

**期待成果**: 修正すべき具体的コード箇所のリスト

### Phase 2: 基本操作Browser Binding修正 🔧

**目的**: rootFolderTest等の基本操作をTCK準拠に修正

**修正対象**:
1. **Repository Info取得** (`cmisselector=repositoryInfo`)
   - JSON構造の標準化
   - メタデータ形式修正

2. **Root Folder取得** (`cmisselector=children`)
   - children配列構造
   - プロパティオブジェクト形式

3. **Object情報取得** (`cmisselector=object`)
   - properties構造標準化
   - allowableActions準拠

**技術実装**:
```java
// Before: カスタム形式
{"children": [...]}

// After: CMIS 1.1準拠
{"objects": [{"object": {"properties": {...}}}]}
```

### Phase 3: 高度機能Browser Binding対応 ⚡

**ACL機能実装**:
- `cmisselector=acl` パラメータ処理
- ACE (Access Control Entry) JSON形式
- principal/permission構造標準化

**Query機能実装**:
- `cmisaction=query` POST処理
- SQL文パラメータ解析
- 結果セットJSON形式準拠

**Secondary Types機能実装**:
- `cmis:secondaryObjectTypeIds` プロパティ配列処理
- 型定義継承ロジック修正
- メタデータ展開処理

### Phase 4: TCK総合検証 ✅

**実行内容**:
1. **個別機能検証**
   ```bash
   JAVA_HOME=/path/to/java-17 timeout 60s mvn test -Dtest=BasicsTestGroup -f core/pom.xml -Pdevelopment
   ```

2. **全TCK実行**
   ```bash
   JAVA_HOME=/path/to/java-17 timeout 300s mvn test -Dtest=AllTest -f core/pom.xml -Pdevelopment
   ```

3. **成功基準**: 全TCKテストがFAILUREなしで完了

## 技術実装戦略

### コード修正箇所

**Primary Target**: `core/src/main/java/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.java`

**修正方針**:
1. **標準準拠レスポンス**: OpenCMIS `BrowserBindingUtils`活用
2. **互換性維持**: 既存QAテストへの影響最小化
3. **エラーハンドリング**: CMIS例外の標準JSON形式

### テスト戦略

**段階的検証**:
```bash
# Phase 1 検証
curl -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo"

# Phase 2 検証  
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# Phase 3 検証
curl -u admin:admin -X POST -F "cmisaction=query" -F "statement=SELECT * FROM cmis:document" "http://localhost:8080/core/browser/bedroom"

# Phase 4 検証
mvn test -Dtest=AllTest -f core/pom.xml -Pdevelopment
```

## リスク管理

### 既存機能影響最小化
- **QAテスト**: 修正後も100%成功維持
- **AtomPub Binding**: 無影響 (Browser Bindingのみ修正)
- **REST API**: 無影響

### 段階的デプロイメント
1. **開発環境**: 修正実装とTCK検証
2. **検証環境**: QA + TCK両方での総合テスト  
3. **本番対応**: 全テスト成功確認後

## 成功指標

### TCK完全準拠達成
- **BasicsTestGroup**: 100% PASS
- **TypesTestGroup**: 100% PASS  
- **CrudTestGroup**: 100% PASS
- **QueryTestGroup**: 100% PASS
- **VersioningTestGroup**: 100% PASS
- **AllTest**: タイムアウトなし、全PASS

### 既存品質維持
- **QAテスト**: 56/56 tests passing維持
- **パフォーマンス**: レスポンス時間変化なし
- **安定性**: メモリリーク、接続リークなし

## 次のアクション

### 即座実行
1. **Phase 1開始**: CMIS 1.1 Browser Binding仕様詳細調査
2. **rootFolderTest失敗原因**: 具体的JSON形式差分特定
3. **修正優先順位**: 影響度・実装難易度マトリクス作成

### 週間目標
- **Week 1**: Phase 1完了、修正方針確定
- **Week 2**: Phase 2実装、基本操作TCK準拠
- **Week 3**: Phase 3実装、高度機能対応
- **Week 4**: Phase 4検証、完全準拠達成

## 技術参考資料

- **CMIS 1.1 Specification**: OASIS Content Management Interoperability Services (CMIS) Version 1.1
- **OpenCMIS Documentation**: Apache Chemistry OpenCMIS Browser Binding Implementation
- **NemakiWare Architecture**: `/path/to/NemakiWare/CLAUDE.md`

---
**作成日**: 2025-08-25
**作成者**: Claude Code Assistant
**更新予定**: Phase完了毎に更新