# CLAUDE.md

日本語で対話してください。
ファイルの読み込みは100行毎などではなく、常に一気にまとめて読み込むようにしてください。
---
## Tool Execution Safety

- Run tools **sequentially only**; do not issue a new `tool_use` until the previous `tool_result` arrives.
- If an API error reports a missing `tool_result`, pause immediately and ask for user direction—never retry automatically.

---

## プロジェクト概要

NemakiWare は CMIS 1.1 準拠のオープンソースエンタープライズコンテンツ管理システムです。

**技術スタック**:
- Backend: Spring Framework, Apache Chemistry OpenCMIS, Jakarta EE 10
- Database: CouchDB 3.x
- Search: Apache Solr 9.x
- UI: React 18 + TypeScript + Vite 7 + Ant Design 5
- Server: Tomcat 10.1+ (Jakarta EE)
- Java: 17 (必須)

**モジュール構成**:
- `core/`: メインCMISリポジトリサーバー (WAR)
- `core/src/main/webapp/ui/`: React SPA UI
- `solr/`: 検索エンジン設定
- `common/`: 共有ユーティリティ

---

## 環境セットアップ

### Java 17 設定 (必須)
```bash
export JAVA_HOME=/path/to/java-17
export PATH=$JAVA_HOME/bin:$PATH
java -version  # 17.x.x を確認
```

### 認証情報
- NemakiWare: `admin:admin`
- CouchDB: `admin:password`

---

## ビルド・デプロイ

### UIビルド
```bash
cd core/src/main/webapp/ui
npm install
npm run build
```

### Coreビルド
```bash
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
```

### Dockerデプロイ
```bash
cp core/target/core.war docker/core/core.war
cd docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
sleep 90  # 起動待機
```

### ヘルスチェック
```bash
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# HTTP 200 + XML が正常
```

---

## テスト実行

### QA統合テスト (推奨)
```bash
./qa-test.sh
# 期待: 75/75 PASS
```

### TCKテスト
```bash
timeout 300s mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup -f core/pom.xml -Pdevelopment
# 期待: 11/11 PASS
```

### Playwrightテスト
```bash
cd core/src/main/webapp/ui
npx playwright test --project=chromium
```

---

## CMIS API

### Browser Binding (推奨)
```bash
# GET リクエスト: cmisselector パラメータ
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"

# POST リクエスト: cmisaction + propertyId[N]/propertyValue[N]
curl -u admin:admin -X POST \
  -F "cmisaction=createDocument" \
  -F "folderId=ROOT_FOLDER_ID" \
  -F "propertyId[0]=cmis:objectTypeId" \
  -F "propertyValue[0]=cmis:document" \
  -F "propertyId[1]=cmis:name" \
  -F "propertyValue[1]=test.txt" \
  "http://localhost:8080/core/browser/bedroom"
```

### リポジトリ
- `bedroom`: 主要リポジトリ (テスト用)
- `canopy`: マルチリポジトリ管理用

---

## UI開発

### 開発サーバー
```bash
cd core/src/main/webapp/ui
npm run dev  # http://localhost:5173
```

### i18n (多言語対応)
- 翻訳ファイル: `src/i18n/locales/{ja,en}.json`
- デフォルト言語: 日本語
- 言語設定保存: localStorage (`nemakiware-language`)

### 主要コンポーネント
- `Layout.tsx`: メインレイアウト + LanguageSwitcher
- `AuthContext.tsx`: 認証状態管理
- `cmis.ts`: CMIS APIサービス

---

## 重要な設計決定

### OpenCMIS バージョン
- 使用: `1.1.0-nemakiware` (自己ビルド Jakarta EE 対応版)
- 禁止: 1.2.0-SNAPSHOT (不安定)

### Maven systemPath 警告
- 無視可: `/lib/jakarta-converted/` 内のカスタムJARによる警告は想定内

### ACLキャッシュ
- 修正済み: `removeCmisAndContentCache()` で両キャッシュを同期クリア

### テストユーザーパスワード
- BCryptハッシュ必須（平文は拒否される）

---

## トラブルシューティング

### コンテナ起動問題
```bash
docker logs docker-core-1 --tail 50
curl -u admin:password http://localhost:5984/_all_dbs
```

### UIが更新されない
1. `npm run build` 実行
2. WAR再ビルド
3. Docker再起動 (--force-recreate)
4. ブラウザキャッシュクリア

---

## セキュリティステータス (2025-12-28) ✅

- npm脆弱性: 0件
- Maven依存関係: 最新化済み
- PDF.js CVE-2024-4367: 対応済み (react-pdf 10.0.1)

---

## 現在のバージョン

**3.0.0-RC1** (2025-12-28)
- 多言語対応 (日本語/英語)
- セキュリティ脆弱性全解消
- タイプ管理機能強化
- React 18 + Vite 7 移行完了

---

## 関連ドキュメント

- `QA-3.0.0-RC1.md`: QA作業指示書
- `CLAUDE-archive-2025-12-28.md`: 詳細履歴アーカイブ
- `AGENTS.md`: 開発者向け詳細ガイド
- `README.md`: プロジェクト概要
