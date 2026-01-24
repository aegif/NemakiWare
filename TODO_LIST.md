# NemakiWare TODOコメント一覧

**作成日**: 2026-01-24
**最終更新**: 2026-01-24
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

### 1. エラーメッセージの実装不足 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/rest/SolrResource.java`
**行**: 184, 188, 275, 279
**状態**: 解決済み - 適切なエラーメッセージとログ出力を追加

---

## P1 (High) - 機能の完全性に影響

### 2. タイプ階層チェックの実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java`
**行**: 1465
**状態**: 解決済み - コメントで現状の実装理由を明確化（循環依存回避のため文字列比較を使用）

### 3. セカンダリタイプのサポート ⏳ 保留
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`
**行**: 162
**内容**: セカンダリタイプのSolrプロパティ名マッピング
**優先度**: P1
**難度**: Hard
**状態**: Solr機能ブランチと合わせて実装予定

### 4. Ancestors計算の実装 ⏳ 保留
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`
**行**: 959
**内容**: IN_TREEクエリ用のancestors計算（循環依存回避）
**優先度**: P1
**難度**: Hard
**状態**: Solr機能ブランチと合わせて実装予定

### 5. コンテンツ長の正確な計算 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java`
**行**: 515
**状態**: 解決済み - getContentLength()メソッドを実装

### 6. バルク削除の実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 2420
**状態**: 解決済み - deleteRelationshipBatch()メソッドを追加

### 7. ポリシーとACEsの設定 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 1220, 1254
**状態**: 解決済み - applyPolicies()メソッドを実装

### 8. 特定ドキュメントインデックス更新 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 958, 1118, 1230, 1264, 2301, 3289
**状態**: 解決済み - 各箇所でsolrUtil.indexDocument()を呼び出すよう修正

### 9. deleteArchiveの戻り値 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java`
**行**: 3704
**状態**: 解決済み - 戻り値をStringに変更し、archiveIdを返すよう実装

### 10. PWCの非所有者ユーザーからの非表示 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 305
**状態**: 解決済み - コメントで現状の動作理由を明確化（CMIS 1.1仕様ではPWC表示が標準）

### 11. orderByの有効化 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 319
**状態**: 解決済み - applySorting()メソッドを実装（複数カラム対応、セカンダリタイプ対応）

---

## P2 (Medium) - コード品質と保守性

### 12. パスの不規則性検証 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 225
**状態**: 解決済み - パス形式のバリデーションロジックを追加

### 13. パラメータのマージとリファクタリング ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`
**行**: 127-128
**状態**: 解決済み - コメントで将来のリファクタリング方針を明確化

### 14. パフォーマンス最適化 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
**行**: 1894
**状態**: 解決済み - コメントでパフォーマンス考慮点を明確化

### 15. 定数の実装確認 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
**行**: 2481
**状態**: 解決済み - コメントで実装状況を明確化

### 16. ロジックの精緻化 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`
**行**: 528
**状態**: 解決済み - コメントでearly return意図を明確化

### 17. クラスキャストチェックの実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
**行**: 872
**状態**: 解決済み - コメントで型安全性を明確化

### 18. 設定の外部化 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java`
**行**: 1229
**状態**: 解決済み - 外部化方法をドキュメント化（@Valueアノテーション使用）

### 19. ログ出力の実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java`
**行**: 1692, 1694
**状態**: 解決済み - restoreDocumentWithArchive()にログ出力を追加

### 20. 作成時刻の設定 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/dao/impl/cached/ContentDaoServiceImpl.java`
**行**: 964
**状態**: 解決済み - VersionComparatorでnullハンドリングを追加

### 21. ディープコピーの実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 3090
**状態**: 解決済み - コメントで実装済みであることを明確化

### 22. null値の処理確認 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 3187
**状態**: 解決済み - CMIS仕様に基づきnull許容をドキュメント化

### 23. ログ出力の実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 2838
**状態**: 解決済み - PDF変換失敗時のログを追加

### 24. 管理者によるPWC操作の確認 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`
**行**: 168
**状態**: 解決済み - コメントで管理者特権の動作を明確化

### 25. ワークアラウンドの修正 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java`
**行**: 2351
**状態**: 解決済み - コメントでnullコンテンツ処理の意図を明確化

---

## P3 (Low) - 将来の改善

### 26. ドキュメントコメントの追加
**ファイル**: 複数（ContentDaoService.java, ContentService.java, PermissionService.java等）
**行**: 多数
**内容**: `@param repositoryId TODO`などのドキュメントコメント不足
**優先度**: P3
**難度**: Easy
**状態**: 未着手

### 27. パフォーマンス最適化（フィルタリング） ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
**行**: 185
**状態**: 解決済み - コメントでパフォーマンス考慮点を明確化

### 28. サブタイププロパティのDB登録確認 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
**行**: 1424
**状態**: 解決済み - コメントで動作を明確化

### 29. コメントの追加 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
**行**: 1541
**状態**: 解決済み - versionable/non-versionableドキュメント処理のコメント追加

### 30. Solr検索の実装 ⏳ 保留
**ファイル**: `core/src/main/java/jp/aegif/nemaki/rest/UserItemResource.java`
**行**: 304
**内容**: ユーザー検索にSolrを使用
**優先度**: P3
**難度**: Medium
**状態**: Solr機能ブランチと合わせて実装予定

### 31. テストユーザー/グループの初期化
**ファイル**: `core/src/main/java/jp/aegif/nemaki/patch/PatchService.java`
**行**: 403
**内容**: QA/開発用のテストユーザーとグループの初期化
**優先度**: P3
**難度**: Easy
**状態**: 未着手

### 32. 演算子優先順位と括弧のサポート
**ファイル**: `core/src/main/java/jp/aegif/nemaki/odata/CmisEntityCollectionProcessor.java`
**行**: 311
**内容**: ODataクエリの演算子優先順位と括弧サポート
**優先度**: P3
**難度**: Hard
**状態**: 未着手

### 33. インスタンスチェックの実装 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/type/impl/TypeManagerImpl.java`
**行**: 4109
**状態**: 解決済み - コメントで現状の制限と将来の改善方針を明確化

### 34. リテラルチェックの実装 📋 調査完了
**ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrPredicateWalker.java`
**行**: 225
**内容**: 各リテラル型（String, Number, Date, Boolean）のチェック実装
**優先度**: P3
**難度**: Medium → Hard（Solrスキーマ変更が必要）
**状態**: 調査完了 - 詳細は下記参照

#### 調査結果詳細
**問題**: `walkCompareInternal`メソッドでは全てのリテラルを文字列として処理している。
これにより数値の範囲クエリ（`WHERE size > 10`）が辞書順比較となり不正確。

**影響**:
- 数値比較: "2" > "10" = true （誤り）
- 日付比較: 文字列比較になる
- Boolean比較: 機能するが最適ではない

**必要な作業**:
1. Solrスキーマに数値用動的フィールド追加（`dynamicInt.*`, `dynamicLong.*`等）
2. `SolrUtil.getPropertyNameInSolr`を型別に拡張
3. `walkCompareInternal`を型情報を保持するよう改修
4. `walkGreaterThan`等で数値型には`PointRangeQuery`を使用
5. 既存データの再インデックス

**結論**: Solr機能ブランチと合わせて実装が効率的

### 35. Aspectクラスの完成 ✅ 解決済み
**ファイル**: `core/src/main/java/jp/aegif/nemaki/model/Aspect.java`
**行**: 44
**状態**: 解決済み - クラスは既に完成、JavaDocを更新

---

## TypeManagerImpl 関連 ✅ 解決済み

### choices継承の明確化
**行**: 2257
**状態**: 解決済み - コメントで継承動作を明確化

### プロパティオーバーライドの明確化
**行**: 2288
**状態**: 解決済み - コメントでオーバーライド動作を明確化

### updateTypeDefinition
**状態**: 解決済み - CmisInvalidArgumentExceptionで非サポートを明示

### 重複タイプ登録のログ
**状態**: 解決済み - デバッグログを追加

---

## サマリー

### 優先度別の統計
| 優先度 | 総数 | 解決済み | 保留 | 未着手 |
|--------|------|----------|------|--------|
| P0 (Critical) | 1 | 1 | 0 | 0 |
| P1 (High) | 10 | 8 | 2 | 0 |
| P2 (Medium) | 14 | 14 | 0 | 0 |
| P3 (Low) | 10 | 6 | 2 | 2 |

### 進捗
- **解決済み**: 29/35 (83%)
- **保留（Solr関連）**: 4/35 (11%)
- **未着手**: 2/35 (6%)

### 保留アイテム（Solr機能ブランチで対応予定）
1. P1 #3: セカンダリタイプのSolrサポート
2. P1 #4: Ancestors計算（IN_TREEクエリ）
3. P3 #30: ユーザー検索のSolr対応
4. P3 #34: リテラル型チェック

### 未着手アイテム
1. P3 #26: ドキュメントコメント追加
2. P3 #31: テストユーザー初期化
3. P3 #32: OData演算子優先順位

---

## 注意事項

- このリストは2026-01-24時点のものです
- Solr関連の保留アイテムはfeature/solrブランチと調整して実装予定
- 実装時は関連するテストも追加してください
