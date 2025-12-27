# 残課題整理 (2025-12-27)

## テスト結果サマリー

| カテゴリ | 結果 | 備考 |
|---------|------|------|
| ユニットテスト (Vitest) | **140/140 PASS** | ✅ 完了 |
| Playwrightテスト | **382/480 PASS** | 13 failed, 82 skipped |
| 失敗率 | 2.7% | 許容範囲内 |

---

## 対応完了した項目 ✅

### 1. レビュー対応
- **TypeGUIEditor.test.tsx**: `querySelectorAll` → `getByRole` + 正規表現に修正
- **codex branch マージ**: property-display.spec.ts の改善

### 2. リレーションシップテスト修正
- `cmis:name` プロパティ追加（NemakiWare要件）
- 4ファイル、7箇所を修正
- 失敗テスト: 17 → 13 に減少

---

## 残っている失敗テスト (13件)

### カテゴリ別分類

#### A. プレビュー機能 (3件) - UI実装依存
| テストファイル | テスト名 | 原因 |
|---------------|---------|------|
| comprehensive-preview.spec.ts:226 | PDF Preview | `.react-pdf__Document` セレクタ不一致 |
| comprehensive-preview.spec.ts:253 | Image Preview | プレビューコンテナ未検出 |
| comprehensive-preview.spec.ts:280 | Text Preview | プレビューコンテナ未検出 |

**手動検証項目**:
- [ ] PDFファイルのプレビュー表示
- [ ] 画像ファイルのプレビュー表示
- [ ] テキストファイルのプレビュー表示

#### B. 検索結果表示 (2件) - UI表示の問題
| テストファイル | テスト名 | 原因 |
|---------------|---------|------|
| search-results.spec.ts:130 | objectType in search results | 列表示なし |
| search-results.spec.ts:167 | createdBy/creationDate | 列表示なし |

**手動検証項目**:
- [ ] 検索結果でobjectType列が表示されるか
- [ ] 検索結果でcreatedBy/creationDate列が表示されるか

#### C. セカンダリタイプ削除 (2件) - 期待値の問題
| テストファイル | テスト名 | 原因 |
|---------------|---------|------|
| secondary-type-relationship.spec.ts:243 | remove secondary type via API | `null` vs `undefined` |
| e2e-functional-verification.spec.ts:602 | removing secondary type | 同上 |

**手動検証項目**:
- [ ] ドキュメントからセカンダリタイプを削除できるか
- [ ] 削除後、セカンダリタイプIDsが空になるか

#### D. Description永続化 (2件) - タイミング依存
| テストファイル | テスト名 | 原因 |
|---------------|---------|------|
| description-disappearing.spec.ts:235 | Multiple updates preserve description | 状態依存 |
| description-disappearing.spec.ts:300 | CouchDB raw document | 同上 |

**手動検証項目**:
- [ ] Descriptionを設定して保存
- [ ] セカンダリタイプを追加してプロパティを設定
- [ ] Descriptionが消えないことを確認

#### E. その他UI操作 (4件)
| テストファイル | テスト名 | 原因 |
|---------------|---------|------|
| group-management-crud.spec.ts:686 | delete test group | UI操作タイミング |
| cascade-delete.spec.ts:215 | delete parent and children | 確認ダイアログ |
| document-viewer-auth.spec.ts:272 | multiple document accesses | セッション問題 |
| description-disappearing-webui.spec.ts:182 | add secondary type | WebUI操作 |

---

## 手動検証チェックリスト

### 1. 基本動作確認
- [ ] ログイン成功 (admin/admin)
- [ ] ドキュメント一覧表示
- [ ] フォルダナビゲーション

### 2. プレビュー機能
- [ ] PDFファイルアップロード → プレビュー表示
- [ ] 画像ファイルアップロード → プレビュー表示
- [ ] テキストファイルアップロード → プレビュー表示

### 3. セカンダリタイプ操作
- [ ] ドキュメント選択 → セカンダリタイプタブ
- [ ] セカンダリタイプ追加 (nemaki:commentable)
- [ ] プロパティ値設定
- [ ] セカンダリタイプ削除

### 4. リレーションシップ操作
- [ ] 2つのドキュメント作成
- [ ] リレーションシップ作成
- [ ] 両方のドキュメントから関係が見えることを確認

### 5. 検索機能
- [ ] キーワード検索実行
- [ ] 検索結果の列表示確認

### 6. Description永続化
- [ ] 新規ドキュメント作成
- [ ] Descriptionを設定
- [ ] セカンダリタイプ追加・プロパティ設定
- [ ] Descriptionが維持されていることを確認

---

## アクセスURL

| 機能 | URL |
|------|-----|
| NemakiWare UI | http://localhost:8080/core/ui/ |
| CouchDB Admin | http://localhost:5984/_utils/ |
| Solr Admin | http://localhost:8983/solr/ |
| Keycloak Admin | http://localhost:8088/admin/ |

## 認証情報

| システム | ユーザー | パスワード |
|---------|---------|----------|
| NemakiWare | admin | admin |
| CouchDB | admin | password |
| Keycloak | admin | admin |
