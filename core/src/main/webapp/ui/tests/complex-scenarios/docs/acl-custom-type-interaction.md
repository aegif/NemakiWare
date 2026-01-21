# ACL継承とカスタム型の相互作用テスト

## テスト概要

このテストスイートは、ACL（アクセス制御リスト）の継承機能とカスタム型ドキュメントの相互作用を検証します。フォルダに設定されたACLがドキュメントに継承される動作と、継承を解除した場合の独立したACL管理を確認します。

## テスト目的

- フォルダからドキュメントへのACL継承の正常動作を確認
- ACL継承の解除（ブレーク）機能を検証
- 独立したACL設定後のアクセス制御を確認
- カスタム型ドキュメントでのACL動作を検証

## 前提条件

- NemakiWareサーバーが http://localhost:8080/core/ui/ で稼働していること
- admin:admin でログイン可能であること
- ACL管理機能がUIで利用可能であること

## テストステップ詳細

### Step 1: テストフォルダの作成

**操作内容**:
1. ドキュメント一覧画面に遷移
2. 「新規フォルダ」ボタンをクリック
3. フォルダ名（例: `test:aclFolder{uuid}`）を入力
4. 作成ボタンをクリック

**期待結果**:
- フォルダが正常に作成される
- ドキュメント一覧テーブルに表示される
- デフォルトのACL（親フォルダから継承）が設定される

**検証するCMIS概念**:
- createFolder operation
- Default ACL inheritance

---

### Step 2: フォルダ内にドキュメントを作成

**操作内容**:
1. 作成したテストフォルダに移動
2. アップロードボタンをクリック
3. テストファイルを選択
4. ドキュメント名を入力
5. アップロードを実行

**期待結果**:
- ドキュメントが正常にアップロードされる
- フォルダ内に表示される
- 親フォルダのACLが継承される

**検証するCMIS概念**:
- createDocument in folder
- ACL inheritance from parent folder

---

### Step 3: フォルダにACL権限を設定

**操作内容**:
1. テストフォルダを選択
2. 詳細パネルまたはコンテキストメニューからACL設定を開く
3. 新しいACEを追加（例: 特定ユーザーに読み取り権限）
4. 保存ボタンをクリック

**期待結果**:
- ACL設定が正常に保存される
- フォルダのACLに新しいACEが追加される
- 子ドキュメントにもACLが継承される

**検証するCMIS概念**:
- applyACL operation
- ACE (Access Control Entry) management
- Propagation to children

---

### Step 4: ドキュメントのACL継承を解除

**操作内容**:
1. テストドキュメントを選択
2. ACL設定画面を開く
3. 「継承を解除」または「独自のACLを設定」オプションを選択
4. 独自のACEを追加
5. 保存ボタンをクリック

**期待結果**:
- ACL継承が解除される
- ドキュメントが独自のACLを持つ
- 親フォルダのACL変更がドキュメントに影響しなくなる

**検証するCMIS概念**:
- ACL inheritance break
- Independent ACL management
- cmis:isExactACL property

---

### Step 5: 独立したACLの検証

**操作内容**:
1. 親フォルダのACLを変更（新しいACEを追加）
2. テストドキュメントのACLを確認
3. ドキュメントのACLが変更されていないことを確認

**期待結果**:
- 親フォルダのACL変更後もドキュメントのACLは変更されない
- ドキュメントは独自のACL設定を維持
- 継承解除が正常に機能している

**検証するCMIS概念**:
- ACL independence verification
- Non-propagation after inheritance break

## ACL関連のCMIS概念

### ACL継承モデル
NemakiWareはCMIS 1.1のACL継承モデルを実装しています：
- `repositoryCapabilityACL`: manage（完全なACL管理をサポート）
- `aclPropagation`: propagate（子オブジェクトへの伝播）

### ACE構造
各ACE（Access Control Entry）は以下で構成されます：
- Principal: ユーザーまたはグループ
- Permission: 権限（cmis:read, cmis:write, cmis:all など）
- Direct: 直接設定か継承かのフラグ

## トラブルシューティング

### ACL設定画面が表示されない場合
- 管理者権限でログインしているか確認
- オブジェクトに対する適切な権限があるか確認

### ACL継承解除が機能しない場合
- リポジトリのACL機能設定を確認
- ブラウザのコンソールでエラーを確認

### 権限変更が反映されない場合
- ページをリロードして最新状態を確認
- キャッシュをクリアして再試行
