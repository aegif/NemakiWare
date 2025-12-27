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
- バージョニングUI（チェックアウト/チェックインボタン）
- カスタムタイプ作成UI（GUI）
- ユーザー自動プロビジョニング（SSO）

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

## 連絡先

問題発見時は以下に報告:
- GitHub Issues: https://github.com/aegif/NemakiWare/issues
- ブランチ: `release/3.0.0-RC1-QA`
