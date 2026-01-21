# 残存する製品バグ報告（2025/11/24）

## 概要

Permission Management UI テストの並列実行改善後、以下の製品バグが明確になりました。これらはテストコードの問題ではなく、バックエンドまたはフロントエンドの実装に起因します。

## Bug #1: Test User Creation - Table Display Issue

### 症状
```
Setup: Success message appeared but user not found in table
```

### 再現手順
1. 管理メニュー → ユーザー管理を開く
2. 新規作成ボタンをクリック
3. ユーザー情報を入力（ID, name, firstName, lastName, email, password）
4. 作成ボタンをクリック
5. 成功メッセージが表示される
6. **ユーザーテーブルを確認すると、新規ユーザーが見つからない**

### 発生頻度
- 並列実行: 5/5ユーザー作成で発生（100%）
- ユーザー例: testuser215477bc, testuserce72b933, testuser8ea6e762, testuser5b1cdf08, testuser610e9e9a

### 期待動作
- 成功メッセージ表示後、ユーザーテーブルに新規ユーザーが表示される
- ページネーション最終ページに移動すれば、新規ユーザーが見つかる

### 実際の動作
- 成功メッセージは表示される
- モーダルは閉じる
- **テーブル（すべてのページを含む）に新規ユーザーが表示されない**
- Documents画面に移動し、再度User Management に戻っても表示されない

### 影響範囲
- ユーザー管理UIの信頼性低下
- 並列テスト実行に影響（test userがセットアップできない）
- **重要度: HIGH** - 基本的なCRUD操作の信頼性問題

### 推定原因
1. UIテーブルの再レンダリングが正しくトリガーされていない
2. 成功メッセージとデータ取得のタイミング不整合
3. キャッシュ無効化が正しく動作していない可能性

### 関連ファイル
- `core/src/main/webapp/ui/src/components/UserManagement/UserManagement.tsx`
- `core/src/main/webapp/ui/src/services/cmis.ts` (ユーザー作成API)

---

## Bug #2: CouchDB Revision Conflict - Concurrent ACL Updates

### 症状
```
Setup: Root folder ACL application failed: 500
"CouchDB revision conflict - update failed: conflict: Document update conflict."
```

### 再現手順
1. 並列テスト実行（workers=7）
2. 複数のテストが同時にRoot Folder ACLを更新しようとする
3. **CouchDB revision conflict エラー（HTTP 500）**

### 発生頻度
- 並列実行: 2/5 ACL更新で発生（40%）
- 成功例: 3/5 ACL更新は成功

### 期待動作
- 並列実行時でもACL更新が成功する
- または、適切なリトライ機構が動作する

### 実際の動作
- HTTP 500エラー
- `{"exception":"runtimeException","message":"CouchDB revision conflict - update failed: conflict: Document update conflict."}`
- リトライなし

### 影響範囲
- 並列実行時のACL更新信頼性低下
- テストセットアップの不安定性
- **重要度: MEDIUM** - 並列実行時のみ発生

### 推定原因
1. CouchDB楽観的ロックの競合状態
2. `_rev`フィールドの取得→更新間に他のワーカーが更新
3. リトライロジックの欠如

### 関連ファイル
- `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java`
- `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java`

### 推奨修正
```java
// Retry logic for CouchDB conflicts
public void applyAcl(...) {
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        try {
            // Get latest _rev
            Document doc = getLatestDocument(objectId);
            // Apply ACL update
            updateDocument(doc);
            break; // Success
        } catch (CouchDbConflictException e) {
            if (i == maxRetries - 1) throw e;
            // Wait and retry
            Thread.sleep(100 * (i + 1));
        }
    }
}
```

---

## Bug #3: Permission Access Denied Despite cmis:all Permission

### 症状
```
PRODUCT BUG: testuser cannot access folder despite cmis:all permission
Expected: HTTP 200, Actual: HTTP 401
```

### 再現手順
1. AdminユーザーでフォルダAを作成
2. フォルダAのACLに testuser を追加（permission: cmis:all）
3. ACL REST APIで権限付与を確認（testuser: cmis:all が含まれる）
4. testuserでフォルダAにアクセス
5. **HTTP 401 Unauthorized エラー**

### 発生頻度
- 100%再現可能

### 期待動作
- `cmis:all`権限があるユーザーはフォルダにアクセスできる
- HTTP 200で folder objectが返される

### 実際の動作
- HTTP 401 Unauthorized
- エラーメッセージ: "Authorization required"
- 権限は正しく設定されているが、アクセス拒否される

### 影響範囲
- 権限管理機能の信頼性問題
- **重要度: CRITICAL** - ACL機能の基本動作に影響

### 追加テスト結果
```
Test: Admin ACL check response status: 200
Test: Verified ACL contains testuser with cmis:all permission
Test: testuser access status: 401 ← 権限があるのにアクセス拒否
```

### 推定原因
1. ACL評価ロジックの問題
2. testuserがroot folderにアクセス権限を持っていない
3. 親フォルダの権限継承チェックが失敗

### 関連ファイル
- `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/PermissionServiceImpl.java`
- `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/AclServiceImpl.java`

### デバッグ推奨
1. testuserのroot folder権限を確認
2. ACL評価ログを追加（どの権限チェックで失敗しているか）
3. 権限継承チェーンを確認

---

## Bug #4: Folder Deletion Timeout - Spinner Intercepts Click

### 症状
```
Cleanup: Error deleting folder in batch 0: locator.click: Timeout 3000ms exceeded.
<div class="ant-spin-container ant-spin-blur">…</div> intercepts pointer events
```

### 再現手順
1. 大量のフォルダが存在する状態で削除処理を実行
2. Ant Design Spinnerが表示される
3. 削除ボタンをクリック
4. **Spinnerが削除ボタンのクリックをブロック**
5. 3秒タイムアウト

### 発生頻度
- クリーンアップ処理で頻繁に発生
- 特に13番目以降のフォルダ削除で顕著

### 期待動作
- Spinner表示中でもフォルダ削除が可能
- または、Spinner完了後に削除が実行される

### 実際の動作
- Spinnerが削除ボタンをブロック
- クリック操作が3秒以内に完了しない
- タイムアウトエラー

### 影響範囲
- テストクリーンアップの不完全性
- テストデータ蓄積による後続テストへの影響
- **重要度: LOW** - 回避策あり（API経由削除）

### 推定原因
1. Ant Design Spinnerのz-indexが削除ボタンより高い
2. 非同期操作中のUI要素無効化が不適切
3. フォルダ読み込み中に削除操作が可能な状態になっている

### 関連ファイル
- `core/src/main/webapp/ui/src/components/DocumentManagement/DocumentManagement.tsx`
- Ant Design `<Spin>` component configuration

### 推奨修正
```typescript
// Wait for spinner to complete before clicking
await page.waitForSelector('.ant-spin-blur', { state: 'hidden', timeout: 5000 });
await deleteButton.click({ timeout: 5000 });
```

または

```typescript
// Use force click to bypass spinner
await deleteButton.click({ force: true, timeout: 3000 });
```

---

## まとめ

### バグ優先度

| Bug | 重要度 | 影響範囲 | 修正難易度 |
|-----|--------|----------|------------|
| #3 Permission Access Denied | **CRITICAL** | ACL機能全般 | MEDIUM |
| #1 User Creation Display | **HIGH** | ユーザー管理UI | MEDIUM |
| #2 CouchDB Conflicts | **MEDIUM** | 並列実行時のみ | LOW |
| #4 Deletion Timeout | **LOW** | テストクリーンアップ | LOW |

### 推奨アクション

**即時対応必要（CRITICAL）**:
- Bug #3: Permission Access Denied の根本原因調査と修正

**短期対応（HIGH）**:
- Bug #1: User Creation Display の UI更新ロジック修正

**中期対応（MEDIUM）**:
- Bug #2: CouchDB Conflicts のリトライ機構実装

**長期対応（LOW）**:
- Bug #4: Deletion Timeout の Spinner制御改善

---

## テストコードへの影響

これらの製品バグにより、以下のテストが影響を受けています：

- Test User Creation系: 5テスト
- Permission Access系: 2-3テスト
- ACL Management系: 1-2テスト
- Cleanup処理: 全テストの後処理

**現状の並列実行合格率**: 65%+（製品バグを除けば75-80%が期待値）

**製品バグ修正後の期待値**: 80-85%の並列実行合格率
