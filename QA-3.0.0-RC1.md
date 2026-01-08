# NemakiWare 3.0.0-RC1 QA作業指示書

## 概要

**ブランチ**: `release/3.0.0-RC1-QA`
**作成日**: 2025-12-28
**目的**: 3.0.0リリース候補1の品質保証テスト

## 新機能サマリー (3.0.0)

### 主要な変更点

1. **多言語対応 (i18n)**
   - 日本語/英語の切り替え機能
   - LanguageSwitcherコンポーネント
   - 翻訳ファイル: `src/i18n/locales/{ja,en}.json`

2. **タイプ管理機能強化**
   - カスタムタイプの作成・更新・削除
   - GUI/JSONエディタ
   - リレーションシップタイプのサポート

3. **セキュリティ更新**
   - 全npm脆弱性解消 (0 vulnerabilities)
   - Maven依存関係の最新化
   - PDF.js CVE-2024-4367 対応済み

4. **UI/UX改善**
   - React 18 + Vite 7.2
   - Ant Design 5.x
   - OIDC/SAML認証対応

5. **SSO自動プロビジョニング** ⚠️ 新機能
   - OIDC/SAML認証時にユーザーが存在しない場合、自動的にユーザーを作成
   - IdPから取得した情報（ユーザーID、氏名、メール）を使用
   - デフォルトで有効（無効化オプションは次回リリースで追加予定）
   - **運用上の注意**: IdPで認証されたユーザーは自動的にNemakiWareにアクセス可能になります

6. **バージョン削除バグ修正** 🔧
   - `allVersions=false` で単一バージョンを削除する際の問題を修正
   - 以前は `allVersions` パラメータが無視され、常に全バージョンが削除されていた
   - 修正後は正しく単一バージョンのみ削除され、残りのバージョンが昇格される

---

## QAテスト手順

### 1. 環境準備

```bash
# ブランチ確認
git checkout release/3.0.0-RC1-QA

# UIビルド
cd core/src/main/webapp/ui
npm install
npm run build

# Coreビルド
cd /path/to/NemakiWare
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# Dockerデプロイ
cp core/target/core.war docker/core/core.war
cd docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# 起動待機 (90秒)
sleep 90
```

### 2. 自動テスト実行

#### QA統合テスト (必須)
```bash
./qa-test.sh
# 期待結果: 75/75 PASS (100%)
```

#### TCKコアテスト (推奨)
```bash
export JAVA_HOME=/path/to/java-17
timeout 300s mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup -f core/pom.xml -Pdevelopment
# 期待結果: 11/11 PASS
```

#### Playwrightテスト (必須)
```bash
cd core/src/main/webapp/ui

# ログインテスト
npx playwright test tests/auth/login.spec.ts --project=chromium
# 期待結果: 7/7 PASS

# タイプ管理テスト
npx playwright test tests/admin/type-management.spec.ts --project=chromium
# 期待結果: 6/6 PASS

# 全テスト (オプション - 時間がかかる)
npx playwright test --project=chromium
```

### 3. 手動テスト項目

#### 3.1 言語切り替え機能
- [ ] ログイン後、ヘッダー右側に言語セレクタが表示される
- [ ] 日本語 → 英語 切り替えでメニューが英語に変わる
- [ ] 英語 → 日本語 切り替えでメニューが日本語に変わる
- [ ] 言語設定がページリロード後も保持される

#### 3.2 タイプ管理
- [ ] 管理メニュー → タイプ管理 でタイプ一覧が表示される
- [ ] 6つの基本CMISタイプが表示される (document, folder, relationship, policy, item, secondary)
- [ ] nemaki:parentChildRelationship が表示される
- [ ] JSONエディタでタイプ編集モーダルが開く

#### 3.3 認証機能
- [ ] admin/admin でログインできる
- [ ] ログアウトでログイン画面に戻る
- [ ] セッション維持 (ページリロード後も認証状態保持)

#### 3.4 ドキュメント操作
- [ ] ドキュメント一覧が表示される
- [ ] フォルダナビゲーションが動作する
- [ ] ファイルアップロードが動作する
- [ ] プレビュー機能が動作する (PDF, 画像)

---

## 既知の問題点

### 制限事項
1. **タイプ編集**: CMISタイプ更新APIはネットワークエラーを返すことがある（バックエンド制約）
2. **一部Playwrightテスト**: UIタイミングによるスキップあり（機能自体は正常）

### 未実装機能
- バージョニングUI（チェックアウト/チェックインボタン）- APIレベルでは完全動作
- カスタムタイプ作成UI（GUI）- JSONインポートで代替可能

### セキュリティ考慮事項
- **SSO自動プロビジョニング**: OIDC/SAML認証時、IdPで認証されたユーザーは自動的にNemakiWareユーザーとして作成されます。これはIdPの認証を信頼する設計です。組織のセキュリティポリシーに応じて、次回リリースで無効化オプションを追加予定です。

---

## 合格基準

| テスト種別 | 合格基準 |
|-----------|---------|
| QA統合テスト | 75/75 (100%) |
| TCKコアテスト | 11/11 (100%) |
| Playwrightログインテスト | 7/7 (100%) |
| Playwrightタイプ管理テスト | 6/6 (100%) |
| 手動テスト | 全チェック項目クリア |

---

## テスト実行結果 (2026-01-07)

### 自動テスト結果サマリー

| テスト種別 | 結果 | 合格基準 | ステータス |
|-----------|------|---------|----------|
| QA統合テスト | **75/75 PASS (100%)** | 75/75 | ✅ 合格 |
| TCKコアテスト | **11/11 PASS (100%)** | 11/11 | ✅ 合格 |
| Playwrightログインテスト | **7/7 PASS (100%)** | 7/7 | ✅ 合格 |
| Playwrightタイプ管理テスト | **15/15 PASS (100%)** | 6/6 | ✅ 合格 |
| 必須プロパティ検証テスト | **3/3 PASS (100%)** | - | ✅ 合格 |

### QA統合テスト詳細 (75/75 PASS)

```
実行コマンド: ./qa-test.sh
実行日時: 2026-01-07

=== TEST SUMMARY ===
Tests passed: 75 / 75
Success rate: 100%
ALL TESTS PASSED! ✅

カテゴリ別:
- 環境検証: 4/4 PASS
- データベース初期化: 4/4 PASS
- 設計ドキュメント検証: 4/4 PASS
- コアアプリケーション: 4/4 PASS
- CMIS Browser Binding: 2/2 PASS
- CMIS SQLクエリ: 2/2 PASS
- 認証トークンサービス: 4/4 PASS
- パッチシステム統合: 1/1 PASS
- Solr統合: 3/3 PASS
- Jakarta EE互換性: 1/1 PASS
- タイプ定義: 5/5 PASS
- パフォーマンス: 1/1 PASS
- ドキュメントCRUD: 3/3 PASS
- フォルダCRUD: 2/2 PASS
- バージョニング: 3/3 PASS
- 高度なクエリ: 3/3 PASS
- ACL/セキュリティ: 3/3 PASS
- ファイリング操作: 3/3 PASS
- REST API (アーカイブ): 4/4 PASS
- REST API (ユーザー): 4/4 PASS
- REST API (グループ): 3/3 PASS
- REST API (タイプ): 4/4 PASS
- REST API (検索): 2/2 PASS
- 認証セキュリティ: 6/6 PASS
```

### TCKコアテスト詳細 (11/11 PASS)

```
実行コマンド: mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup -f core/pom.xml -Pdevelopment
実行日時: 2026-01-07

Results:
- BasicsTestGroup: 3/3 PASS (392.8s)
- TypesTestGroup: 3/3 PASS (71.1s)
- ControlTestGroup: 1/1 PASS (16.1s)
- VersioningTestGroup: 4/4 PASS (140.6s)

Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

### Playwrightテスト詳細

#### ログインテスト (7/7 PASS)
```
実行コマンド: npx playwright test tests/auth/login.spec.ts --project=chromium
実行日時: 2026-01-07
結果: 7 passed (57.1s)

- should display login page correctly ✅
- should successfully login with valid credentials ✅
- should fail login with invalid credentials ✅
- should handle empty credentials ✅
- should logout successfully ✅
- should maintain session on page refresh ✅
- should redirect to login when accessing protected routes without authentication ✅
```

#### タイプ管理テスト (15/15 PASS)
```
実行コマンド: npx playwright test tests/admin/type-management.spec.ts tests/admin/type-gui-editor.spec.ts --project=chromium
実行日時: 2026-01-07
結果: 15 passed (7.4m)

type-management.spec.ts:
- should display all base CMIS types ✅
- should display nemaki: custom types ✅
- should display correct type information ✅
- should allow viewing type details ✅
- should verify API returns all types ✅
- should allow editing custom type description ✅

type-gui-editor.spec.ts:
- should display GUI create button ✅
- should open GUI editor modal ✅
- should display GUI editor tabs (GUI and JSON) ✅
- should display basic info panel ✅
- should switch between GUI and JSON tabs ✅
- should show validation error for empty type ID ✅
- should display GUI edit button for custom types ✅
- should show add property button ✅
- should cancel and close GUI editor modal ✅
```

#### 必須プロパティ検証テスト (3/3 PASS)
```
実行コマンド: npx playwright test tests/documents/required-property-validation.spec.ts --project=chromium
実行日時: 2026-01-07
結果: 3 passed (2.7m)

- should show validation error when required custom property is empty (Document) ✅
- should show validation error when required custom property is empty (Folder) ✅
- should only show required indicators for properties with required=true ✅
```

### 手動テスト確認項目

#### 3.1 言語切り替え機能
- [x] ログイン後、ヘッダー右側に言語セレクタが表示される
- [x] 日本語 → 英語 切り替えでメニューが英語に変わる
- [x] 英語 → 日本語 切り替えでメニューが日本語に変わる
- [x] 言語設定がページリロード後も保持される

#### 3.2 タイプ管理
- [x] 管理メニュー → タイプ管理 でタイプ一覧が表示される
- [x] 6つの基本CMISタイプが表示される
- [x] nemaki:parentChildRelationship が表示される
- [x] JSONエディタでタイプ編集モーダルが開く

#### 3.3 認証機能
- [x] admin/admin でログインできる
- [x] ログアウトでログイン画面に戻る
- [x] セッション維持 (ページリロード後も認証状態保持)

#### 3.4 ドキュメント操作
- [x] ドキュメント一覧が表示される
- [x] フォルダナビゲーションが動作する
- [x] ファイルアップロードが動作する
- [x] プレビュー機能が動作する (PDF, 画像)

---

## リリース判定

### 結論: ✅ リリース可能

全ての合格基準を満たしています:

| 基準 | 結果 | 判定 |
|------|------|------|
| QA統合テスト 75/75 | 75/75 (100%) | ✅ |
| TCKコアテスト 11/11 | 11/11 (100%) | ✅ |
| Playwrightログインテスト 7/7 | 7/7 (100%) | ✅ |
| Playwrightタイプ管理テスト 6/6 | 15/15 (100%)※ | ✅ |
| 手動テスト全項目クリア | 全項目チェック済み | ✅ |

※ タイプ管理テストは type-management.spec.ts (6件) + type-gui-editor.spec.ts (9件) = 15件すべてPASS

### 既知の制限事項（仕様として認容）

以下は仕様上の制限として認容されています:
1. バージョニングUI未実装（APIレベルでは動作確認済み、バグ修正済み）
2. カスタムタイプGUI作成未実装（JSONインポートで代替可能）

### 新機能の運用上の注意

1. **SSO自動プロビジョニング**
   - IdPで認証されたユーザーは自動的にNemakiWareユーザーとして作成されます
   - 作成されるユーザーには最小限の権限が付与されます
   - 管理者がACLで適切な権限を設定する必要があります
   - 無効化オプションは次回リリースで追加予定

2. **バージョン削除の動作変更**
   - `allVersions=false` で削除すると、指定したバージョンのみが削除されます
   - 残りのバージョンの中から最新版が自動的に昇格されます
   - 以前の動作（全バージョン削除）はバグでした

---

## 連絡先

問題発見時は以下に報告:
- GitHub Issues: https://github.com/aegif/NemakiWare/issues
- ブランチ: `release/3.0.0-RC1-QA`
