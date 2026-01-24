# NemakiWare TODOコメント一覧

**作成日**: 2026-01-24  
**目的**: プロジェクト全体のTODOコメントを実装難度と優先度で分類

---

## 評価基準

### 優先度
- **P0 (Critical)**: セキュリティ、データ整合性、重大なバグに関連
- **P1 (High)**: 機能の完全性、パフォーマンス、ユーザー体験に影響
- **P2 (Medium)**: コード品質、保守性、ドキュメント改善
- **P3 (Low)**: 将来の改善、最適化、リファクタリング

### 実装難度
- **Easy**: 1-2時間で実装可能
- **Medium**: 半日〜1日で実装可能
- **Hard**: 数日〜1週間の実装が必要
- **Very Hard**: アーキテクチャ変更や大規模リファクタリングが必要

---

## P0 (Critical) - 即座に対応が必要

### 1. エラーメッセージの実装不足
**ファイル**: `core/src/main/java/jp/aegif/nemaki/rest/SolrResource.java`  
**行**: 184, 188, 275, 279  
**内容**: Solr操作のエラーハンドリングでエラーメッセージが未実装  
**優先度**: P0  
**難度**: Easy  
**影響**: エラー発生時に原因特定が困難  
**推奨アクション**: 適切なエラーメッセージを追加し、ログ出力を改善

```java
// 現在
// TODO error message
status = checkSuccess(body);

// 推奨
if (!status) {
    String errorMsg = "Solr operation failed: " + body;
    log.error(errorMsg);
    errMsg.add(errorMsg);
}
```

---

## P1 (High) - 機能の完全性に影響

### 2. タイプ階層チェックの実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java`  
**行**: 1465  
**内容**: TypeManagerを使用した適切なタイプ階層チェックの実装  
**優先度**: P1  
**難度**: Medium  
**影響**: カスタムタイプの判定が不完全で、フォルダ判定が不正確になる可能性  
**推奨アクション**: TypeManagerを使用してタイプ階層を正しくチェック

### 3. セカンダリタイプのサポート
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`  
**行**: 162  
**内容**: セカンダリタイプのSolrプロパティ名マッピング  
**優先度**: P1  
**難度**: Hard  
**影響**: CMISセカンダリタイプの検索が正しく機能しない  
**推奨アクション**: セカンダリタイプのプロパティ定義を取得し、Solrマッピングに追加

### 4. Ancestors計算の実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`  
**行**: 959  
**内容**: IN_TREEクエリ用のancestors計算（循環依存回避）  
**優先度**: P1  
**難度**: Hard  
**影響**: IN_TREEクエリが正しく機能しない  
**推奨アクション**: 循環依存を回避する方法でancestorsを計算（例: キャッシュ、遅延評価）

### 5. コンテンツ長の正確な計算
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`  
**行**: 515  
**内容**: AttachmentNodeから正確なcontent_lengthを取得  
**優先度**: P1  
**難度**: Medium  
**影響**: 数値範囲クエリが不正確になる可能性  
**推奨アクション**: AttachmentNodeから実際のサイズを取得する方法を実装（循環依存回避）

### 6. バルク削除の実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 2420  
**内容**: ContentDaoServiceにバルク削除機能を追加  
**優先度**: P1  
**難度**: Medium  
**影響**: 大量のリレーションシップ削除時にパフォーマンスが低下  
**推奨アクション**: ContentDaoServiceにバルク削除メソッドを追加し、ContentServiceImplで使用

### 7. ポリシーとACEsの設定
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 1220, 1254  
**内容**: checkIn操作時のポリシーとACEs設定  
**優先度**: P1  
**難度**: Medium  
**影響**: CMISポリシー機能が不完全  
**推奨アクション**: CMISポリシーとACEsの設定ロジックを実装

### 8. 特定ドキュメントインデックス更新
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 958, 1118, 1230, 1264, 2301, 3289  
**内容**: 全ドキュメント再インデックスではなく、特定ドキュメントのみ更新  
**優先度**: P1  
**難度**: Medium  
**影響**: パフォーマンス低下、不要なインデックス更新  
**推奨アクション**: solrUtil.indexDocument()を呼び出して特定ドキュメントのみインデックス更新

### 9. deleteArchiveの戻り値
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java`  
**行**: 3704  
**内容**: 削除成功時にarchiveIdを返す  
**優先度**: P1  
**難度**: Easy  
**影響**: 削除操作の結果確認が困難  
**推奨アクション**: 戻り値をvoidからStringに変更し、archiveIdを返す

### 10. PWCの非所有者ユーザーからの非表示
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 305  
**内容**: チェックアウト中のPWCを非所有者ユーザーから非表示  
**優先度**: P1  
**難度**: Medium  
**影響**: セキュリティリスク、ユーザー混乱  
**推奨アクション**: クエリ結果からPWCをフィルタリング

### 11. orderByの有効化
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 319  
**内容**: getCheckedOutDocs()でorderByパラメータを実装  
**優先度**: P1  
**難度**: Medium  
**影響**: CMIS仕様準拠性、ユーザー体験  
**推奨アクション**: orderByパラメータに基づいてソート処理を実装

---

## P2 (Medium) - コード品質と保守性

### 12. パスの不規則性検証
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 225  
**内容**: 不正なパス形式の検証  
**優先度**: P2  
**難度**: Easy  
**影響**: 不正なパスによる予期しない動作  
**推奨アクション**: パス形式のバリデーションロジックを追加

### 13. パラメータのマージとリファクタリング
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`  
**行**: 127-128  
**内容**: メソッド引数のマージと重複コードのリファクタリング  
**優先度**: P2  
**難度**: Medium  
**影響**: コードの保守性低下  
**推奨アクション**: メソッドシグネチャを整理し、重複コードを削減

### 14. パフォーマンス最適化
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`  
**行**: 1894  
**内容**: プロパティ定義の存在チェックのパフォーマンス改善  
**優先度**: P2  
**難度**: Medium  
**影響**: 大量のプロパティ処理時のパフォーマンス低下  
**推奨アクション**: キャッシュやインデックスを使用して高速化

### 15. 定数の実装確認
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`  
**行**: 2481  
**内容**: CAN_CREATE_POLICY_FOLDER定数の実装状況確認  
**優先度**: P2  
**難度**: Easy  
**影響**: コメントアウトされたコードの整理  
**推奨アクション**: 定数の実装状況を確認し、不要なコメントを削除

### 16. ロジックの精緻化
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`  
**行**: 528  
**内容**: バリデーションロジックの改善  
**優先度**: P2  
**難度**: Medium  
**影響**: エッジケースでの予期しない動作  
**推奨アクション**: バリデーションロジックを詳細化

### 17. クラスキャストチェックの実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`  
**行**: 872  
**内容**: クラスキャスト例外の適切な処理  
**優先度**: P2  
**難度**: Easy  
**影響**: 実行時例外のリスク  
**推奨アクション**: instanceofチェックを追加

### 18. 設定の外部化
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java`  
**行**: 1229  
**内容**: MAX_NESTED_GROUP_EXPANSIONをnemakiware.propertiesに外部化  
**優先度**: P2  
**難度**: Easy  
**影響**: 設定変更の柔軟性向上  
**推奨アクション**: @Valueアノテーションで設定値を注入

### 19. ログ出力の実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java`  
**行**: 1692, 1694  
**内容**: キャッシュ操作のログ出力  
**優先度**: P2  
**難度**: Easy  
**影響**: デバッグの困難さ  
**推奨アクション**: 適切なログレベルでログ出力を追加

### 20. 作成時刻の設定
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java`  
**行**: 964  
**内容**: 作成時刻が設定されていない場合の処理  
**優先度**: P2  
**難度**: Easy  
**影響**: データ整合性  
**推奨アクション**: デフォルト値の設定またはバリデーション追加

### 21. ディープコピーの実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 3090  
**内容**: Aceオブジェクトのディープコピー  
**優先度**: P2  
**難度**: Medium  
**影響**: オブジェクト参照の問題  
**推奨アクション**: 適切なディープコピー実装（シリアライゼーション、Cloneable等）

### 22. null値の処理確認
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 3187  
**内容**: null値の許容性確認  
**優先度**: P2  
**難度**: Easy  
**影響**: NullPointerExceptionのリスク  
**推奨アクション**: nullチェックの追加またはドキュメント化

### 23. ログ出力の実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 2838  
**内容**: ログ出力の追加  
**優先度**: P2  
**難度**: Easy  
**影響**: デバッグの困難さ  
**推奨アクション**: 適切なログレベルでログ出力を追加

### 24. 管理者によるPWC操作の確認
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`  
**行**: 168  
**内容**: チェックアウト中でも管理者がPWCを操作できるか確認  
**優先度**: P2  
**難度**: Easy  
**影響**: セキュリティポリシーの明確化  
**推奨アクション**: CMIS仕様を確認し、適切な実装を決定

### 25. ワークアラウンドの修正
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`  
**行**: 2351  
**内容**: 一時的なワークアラウンドの適切な実装  
**優先度**: P2  
**難度**: Medium  
**影響**: コードの保守性低下  
**推奨アクション**: 根本原因を解決し、ワークアラウンドを削除

---

## P3 (Low) - 将来の改善

### 26. ドキュメントコメントの追加
**ファイル**: 複数（ContentDaoService.java, ContentService.java, PermissionService.java等）  
**行**: 多数  
**内容**: `@param repositoryId TODO`などのドキュメントコメント不足  
**優先度**: P3  
**難度**: Easy  
**影響**: コードの可読性、IDE補完  
**推奨アクション**: JavaDocコメントを追加

### 27. パフォーマンス最適化（フィルタリング）
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`  
**行**: 185  
**内容**: パフォーマンス向上のためのフィルタリング  
**優先度**: P3  
**難度**: Medium  
**影響**: 大量データ処理時のパフォーマンス  
**推奨アクション**: 不要なデータの早期フィルタリング

### 28. サブタイププロパティのDB登録確認
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`  
**行**: 1424  
**内容**: サブタイププロパティがDBに登録されていない場合の処理  
**優先度**: P3  
**難度**: Medium  
**影響**: エッジケースでの動作  
**推奨アクション**: 適切なエラーハンドリングまたはデフォルト値の返却

### 29. コメントの追加
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`  
**行**: 1541  
**内容**: コードの説明コメント追加  
**優先度**: P3  
**難度**: Easy  
**影響**: コードの可読性  
**推奨アクション**: 複雑なロジックにコメントを追加

### 30. Solr検索の実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/rest/UserItemResource.java`  
**行**: 304  
**内容**: ユーザー検索にSolrを使用  
**優先度**: P3  
**難度**: Medium  
**影響**: 検索パフォーマンスの向上  
**推奨アクション**: Solrインデックスを使用したユーザー検索を実装

### 31. テストユーザー/グループの初期化
**ファイル**: `core/src/main/java/jp/aegif/nemaki/patch/PatchService.java`  
**行**: 403  
**内容**: QA/開発用のテストユーザーとグループの初期化  
**優先度**: P3  
**難度**: Easy  
**影響**: 開発環境のセットアップ簡素化  
**推奨アクション**: 初期化スクリプトまたはパッチを実装

### 32. 演算子優先順位と括弧のサポート
**ファイル**: `core/src/main/java/jp/aegif/nemaki/odata/CmisEntityCollectionProcessor.java`  
**行**: 311  
**内容**: ODataクエリの演算子優先順位と括弧サポート  
**優先度**: P3  
**難度**: Hard  
**影響**: 複雑なクエリのサポート  
**推奨アクション**: パーサーの拡張

### 33. インスタンスチェックの実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java`  
**行**: 4109  
**内容**: ContentDaoServiceを使用したインスタンスチェック  
**優先度**: P3  
**難度**: Medium  
**影響**: タイプチェックの正確性  
**推奨アクション**: ContentDaoServiceを使用した実装

### 34. リテラルチェックの実装
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrPredicateWalker.java`  
**行**: 225  
**内容**: 各リテラル型のチェック実装  
**優先度**: P3  
**難度**: Medium  
**影響**: クエリの正確性  
**推奨アクション**: 各リテラル型のバリデーションを追加

### 35. Aspectクラスの完成
**ファイル**: `core/src/main/java/jp/aegif/nemaki/model/Aspect.java`  
**行**: 44  
**内容**: Aspectクラスの実装完了  
**優先度**: P3  
**難度**: Hard  
**影響**: 機能の完全性  
**推奨アクション**: Aspect機能の設計と実装

---

## サマリー

### 優先度別の統計
- **P0 (Critical)**: 1件
- **P1 (High)**: 10件
- **P2 (Medium)**: 14件
- **P3 (Low)**: 10件

### 難度別の統計
- **Easy**: 15件
- **Medium**: 15件
- **Hard**: 5件
- **Very Hard**: 0件

### 推奨される対応順序

1. **即座に対応（P0）**:
   - SolrResourceのエラーメッセージ実装

2. **短期（P1 - Easy/Medium）**:
   - deleteArchiveの戻り値修正
   - エラーメッセージの実装
   - パス検証の追加
   - ログ出力の追加
   - 設定の外部化

3. **中期（P1 - Medium/Hard）**:
   - タイプ階層チェック
   - セカンダリタイプサポート
   - Ancestors計算
   - バルク削除
   - ポリシーとACEs設定
   - 特定ドキュメントインデックス更新

4. **長期（P2/P3）**:
   - リファクタリング
   - パフォーマンス最適化
   - ドキュメント整備

---

## 注意事項

- このリストは2026-01-24時点のものです
- 実装前に各TODOの背景と影響範囲を確認してください
- 優先度はプロジェクトの状況に応じて変更される可能性があります
- 実装時は関連するテストも追加してください
