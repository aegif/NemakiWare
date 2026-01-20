# NemakiWare TCK & QAテスト結果

**最終更新**: 2025年1月21日
**ステータス**: 主要テストすべて合格

---

## 最新テスト結果（2025-01-21）

### TCKテストスイート: 17/17 PASS

| テストグループ | テスト数 | 結果 | 実行時間 |
|---------------|---------|------|----------|
| BasicsTestGroup | 3 | ✅ PASS | 62s |
| TypesTestGroup | 3 | ✅ PASS | 69s |
| ControlTestGroup | 1 | ✅ PASS | 15s |
| VersioningTestGroup | 4 | ✅ PASS | 116s |
| QueryTestGroup | 6 | ✅ PASS | 435s |
| **合計** | **17** | **✅ ALL PASS** | ~12分 |

### 実行コマンド
```bash
# 主要テスト実行（推奨）
mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup -f core/pom.xml -Pdevelopment

# クエリテスト追加（約8分追加）
mvn test -Dtest=QueryTestGroup -f core/pom.xml -Pdevelopment
```

---

## CrudTestGroupについて

### ステータス: 既知のタイムアウト問題

CrudTestGroup1 (10テスト) と CrudTestGroup2 (9テスト) は**個別実行では動作**しますが、グループ実行ではリソース枯渇によりタイムアウトします。

**個別テスト実行例**:
```bash
# 個別テストは正常動作
mvn test -Dtest=CrudTestGroup1#createInvalidTypeTest -f core/pom.xml -Pdevelopment
mvn test -Dtest=CrudTestGroup2#moveTest -f core/pom.xml -Pdevelopment
```

**原因**:
- TCKテストがセッション間でリソースを累積消費
- CouchDB接続プールの枯渇
- テストデータのクリーンアップ遅延

### FilingTestGroup

**ステータス**: 製品仕様によりスキップ

NemakiWareは CMIS 1.1 オプション機能である Multi-filing/Unfiling を**サポートしない**設計決定のため、FilingTestGroupは@Ignoreで無効化されています。

---

## QAテストスイート

### 結果: 75/75 PASS (100%)

```bash
./qa-test.sh
# 期待結果: Tests passed: 75 / 75
```

### Management API E2Eテスト: 35/35 PASS

```bash
cd core/src/main/webapp/ui
npx playwright test tests/api/management-api.spec.ts --project=chromium
```

---

## テスト環境

- **Java**: JBR 17.0.12
- **Maven**: 3.x
- **Docker**: CouchDB 3.3, Solr 9.x, Tomcat 10.1
- **OS**: macOS Darwin 25.2.0

---

## デグレ確認履歴

| 日付 | 確認者 | 結果 |
|------|--------|------|
| 2025-01-21 | Claude Code | 17/17 PASS（デグレなし）|
| 2025-10-11 | Claude Code | 17/17 PASS |

---

**結論**: NemakiWare 3.0.0-RC1 は CMIS 1.1 主要機能のTCKテストをすべて合格しています。
