# Next Devin Session Prompt for TCK Remaining Issues

## Recommended Prompt for Next Session

```
NemakiWareのTCKテスト結果を改善してください。現在のTCK成功率は32.71%（214テスト中70成功）ですが、より高い成功率を目指します。

## 現在の状況
- **ブランチ**: devin/1750282993-fix-cmis-query-permission-filtering  
- **PR**: #375 (CMIS query permission filtering fix)
- **現在の成功率**: 32.71% (70/214 tests passed)
- **実行時間**: 約10-15分
- **主要改善**: CMISクエリが0件ではなく実際のデータを返すようになった

## 解決済みの問題
✅ CMISクエリの権限フィルタリング問題（null ACL処理）
✅ CouchDBとSolr間のデータ同期問題
✅ 設計ドキュメントの自動作成
✅ Docker自動化システム（再現可能なテスト環境）

## 残存する課題（132件の失敗テスト）
以下の問題の調査と修正をお願いします：

### 1. データ整合性問題
- 一部のCMISオブジェクトがCouchDBに存在しない
- CmisObjectNotFoundException が発生
- オブジェクト参照の不整合

### 2. CMIS仕様準拠の問題  
- 一部のCMIS機能が未実装または不完全
- プロパティ定義の不整合
- タイプ管理機能の問題

### 3. バージョン管理とACL問題
- バージョン履歴の処理
- アクセス制御リストの複雑なケース
- 権限継承の問題

### 4. UI/プロトコル問題
- Thin client URI (http://localhost:8080/ui/) への接続失敗 (404エラー)
- HTTPS未使用による警告
- Changes Incomplete フラグが未設定

## 作業方針
1. **TCKテスト結果の詳細分析**: 失敗テストの分類と優先順位付け
2. **データ整合性の修正**: 不足しているCMISオブジェクトの特定と作成
3. **CMIS仕様準拠の改善**: 未実装機能の実装または適切なエラーハンドリング
4. **段階的改善**: 成功率を50%、70%、90%と段階的に向上

## 環境情報
- **Java**: Java 8必須（NemakiWareはJava 8でのみ動作）
- **自動化システム**: `./docker/automated-tck-test.sh` で完全自動テスト実行
- **ドキュメント**: `DEVIN-SESSION-HANDOFF.md` に環境セットアップの詳細
- **期待結果**: 現在214テスト中70成功 → より高い成功率を目指す

## 検証方法
```bash
cd ~/repos/NemakiWare/docker/
./automated-tck-test.sh
# 結果: tck-reports/ フォルダに詳細レポート生成
```

## 成功基準
- TCK成功率の向上（現在32.71%から50%以上を目標）
- 失敗テストの根本原因特定と修正
- CMIS仕様準拠の改善
- データ整合性問題の解決

既存のPR #375を継続使用し、段階的にTCK成功率を改善してください。
```

## 補足情報

### 現在のTCK結果詳細
- **Basics Test Group**: 基本機能は概ね動作、HTTPS/UI接続の警告あり
- **Query Test Group**: 大幅改善済み（0件→実際のデータ取得）
- **CRUD Test Group**: 作成・読取・更新・削除の基本操作
- **Types Test Group**: カスタムタイプ管理で一部問題
- **Versioning Test Group**: バージョン管理で一部スキップ

### 技術的背景
- **権限フィルタリング修正**: `PermissionServiceImpl.java`でnull ACL処理を改善
- **データ同期修正**: CouchDB設計ドキュメント作成とSolr再インデックス
- **自動化システム**: Docker完全自動化でクリーン環境からテスト実行まで

### 推奨アプローチ
1. **失敗テストの分類**: エラーメッセージ別にグループ化
2. **優先順位付け**: 影響範囲の大きい問題から対応
3. **段階的修正**: 一度に全て修正せず、段階的に成功率向上
4. **回帰テスト**: 修正後も既存の成功テストが維持されることを確認
