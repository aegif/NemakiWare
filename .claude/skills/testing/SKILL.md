---
name: testing
description: NemakiWare のテスト実行。QA 統合テスト、CMIS TCK 全 38 テストのグループ分割実行、Playwright E2E、Java 単体。既知のデータ汚染・Solr 索引ラグによる偽陽性の見分け方も含む。テストを走らせる・落ちた原因が回帰かどうか判断するときに読む。
---

# テスト実行

## QA 統合テスト (推奨・最短)

```bash
./qa-test.sh qa
# 期待: 94/94 PASS
```

## CMIS TCK (全 38 テスト)

前提: Docker で NemakiWare core + Solr が起動中。

```bash
# 基本 (11 テスト、約 4 分)
timeout 900s mvn test -Dtest=ConnectionTestGroup,BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup -f core/pom.xml -Pdevelopment

# CRUD (19 テスト、約 20 分)
timeout 1800s mvn test -Dtest=CrudTestGroup1,CrudTestGroup2 -f core/pom.xml -Pdevelopment

# クエリ + 変更ログ (6 テスト、約 12 分、Solr 必須)
timeout 1800s mvn test -Dtest=QueryTestGroup -f core/pom.xml -Pdevelopment

# 一括 (38 テスト、約 35 分)
timeout 3600s mvn test -Dtest=ConnectionTestGroup,BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup,CrudTestGroup1,CrudTestGroup2,QueryTestGroup -f core/pom.xml -Pdevelopment
```

`FilingTestGroup` は NemakiWare が Multifiling / Unfiling 非対応のためスキップ。

### 回帰と偽陽性の見分け

永続 volume を使い回した環境では、**回帰でないのに落ちる**既知パターンがあります。
落ちたら疑ってください:

- **`baseTypesTest` / Types** — E2E が残したカスタム型 (`queryName` が null の
  `test:customFolderForE2E` 等) によるデータ汚染。type delete API で掃除すると green。
- **Query 系** — Solr の非同期索引ラグ (create 直後に query するテスト)。
  失敗ケースが run ごとに入れ替わるのが特徴。索引キューをリセットすると
  新規 doc は数秒で索引されます。
- **`contentChangesSmokeTest`** — 蓄積データ。

権威的な判定はクリーン DB (CI の push ごとの fresh DB、またはローカルで
`ci-complete-setup.sh`) で行ってください。テスト失敗時は CouchDB に
`cmistck*` / `test-custom-*` のゴミが残ることがあります。

## Playwright E2E

```bash
cd core/src/main/webapp/ui
npx playwright test --project=chromium
```

flaky が出た場合、原因は多くが **client-side metadata XHR の hang** です
(データ蓄積ではありません)。`CmisHttpClient` の 60s timeout と reload-retry で
収束済み。詳細は自動メモリ `test-infra-hang-rootcause`。

## UI 単体

```bash
cd core/src/main/webapp/ui && npx vitest run
```

## デモ / 権限検証環境

`tools/test-env/` に階層組織・ネストグループ・エリア別 ACL のシードツールがあります
(`tools/test-env/README.md`)。
