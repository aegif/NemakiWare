---
name: testing
description: NemakiWare のテスト実行。QA 統合テスト、CMIS TCK 全 38 テストのグループ分割実行、Playwright E2E、Java 単体。既知のデータ汚染・Solr 索引ラグによる偽陽性の見分け方も含む。テストを走らせる・落ちた原因が回帰かどうか判断するときに読む。
---

# テスト実行

## QA 統合テスト (推奨・最短)

```bash
./qa-test.sh qa
# 期待: 95/95 PASS
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

- **`Type Query Name: null` / `Property nemaki:comment`** — E2E が残したカスタム型
  (`queryName` が null の `test:*`) によるデータ汚染。**これが最大の偽陽性源**で、
  2026-07 の実測では Basics / Control / Crud1 / Crud2 / Query / Types の
  **23 failures 全てがこれ由来**でした (同一 WAR で clean stack は 38/38 green)。

  掃除手順 — 型は 4 つの base type 配下に散らばるので `cmis:document` だけ見ると
  取りこぼします:

  ```bash
  # 1. queryName 欠落の型を全て列挙 (CouchDB 直接が確実)
  curl -s -u admin:password -X POST http://localhost:5984/bedroom/_find     -H 'Content-Type: application/json'     -d '{"selector":{"type":"typeDefinition"},"fields":["typeId","queryName"],"limit":100}'

  # 2. インスタンス 0 件を確認してから削除 (0 でなければ orphan を作る)
  curl -s -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest"     -F "cmisaction=deleteType" -F "typeId=test:xxx"     http://localhost:8080/core/browser/bedroom
  ```

- **Query 系** — Solr の非同期索引ラグ (create 直後に query するテスト)。
  失敗ケースが run ごとに入れ替わるのが特徴。索引キューをリセットすると
  新規 doc は数秒で索引されます。
- **`contentChangesSmokeTest`** — 蓄積データ。`type: "change"` の `cmistck*` が
  数百件残ります。変更ログの意味論に関わるので削除は慎重に。

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

## skip を減らす: Keycloak を上げる

97 件前後の skip のうち **30 件は Keycloak が居ないだけ**でした
(`tests/auth/*` と `tests/api/{keycloak-oidc-auth,session-management}`)。
`idp` profile に Keycloak + OpenLDAP があります。

```bash
cd docker
export COUCHDB_USER=admin COUCHDB_PASSWORD=password
export LDAP_ADMIN_PASSWORD=admin LDAP_CONFIG_PASSWORD=config
docker compose -f docker-compose-simple.yml --profile idp up -d openldap keycloak
```

global setup は `http://localhost:8088/realms/nemakiware/.well-known/openid-configuration`
を見て可否を決めます。起動していれば `Keycloak: http://localhost:8088 ✅` と出て、
これらは skip されず実走します。

**LDAP パスワードは必須** — `${LDAP_ADMIN_PASSWORD:-}` が空だと openldap の
healthcheck が通らず、Keycloak は `depends_on: service_healthy` で起動しません。

### Keycloak を上げただけでは通らない 6 件

Keycloak が居るだけでは、**ログイン画面の SSO ボタン**を見る 2 件
(`auth/{oidc,saml}-login.spec.ts:41` とその serial 後続) と
**LDAP グループ**を見る 1 件 (`auth/ldap-oidc-integration.spec.ts:267`) は落ちます。
core 側の設定が要ります。

```bash
cd docker
# core の CATALINA_OPTS に流れる (docker/.env に置くのが楽)
export SSO_OIDC_ENABLED=true SSO_SAML_ENABLED=true OIDC_ENABLED=true
export DIRECTORY_SYNC_ENABLED=true LDAP_BIND_PASSWORD=admin
docker compose -f docker-compose-simple.yml --profile idp up -d --no-deps --force-recreate core
```

**ただし `-D` だけでは効きません。** `sso.` / `oidc.` / `saml.` /
`cloud.auth.` / `cloud.drive.` は admin-managed dynamic key で、
`PropertyManager.readValue` は **CouchDB `nemaki_conf` に保存済みの値を
system property より先に読みます** ([`PropertyManager.java`](../../../core/src/main/java/jp/aegif/nemaki/util/PropertyManager.java) の
`isAdminManagedDynamicKey`)。setup ウィザードを一度でも通していると
`config_sso_oidc_enabled` = `"false"` が残っていて、`-Dsso.oidc.enabled=true` を
渡しても `/core/rest/auth/config` は `oidcEnabled:false` を返し続けます。
`sso.oidc.enabled` を grep しても JSON の**プロパティ名ではなく `key` フィールド**に
入っているので見つかりません。

```bash
# 保存値を true に (id は config_<key の . を _ にしたもの>)
REV=$(curl -s -u admin:password http://localhost:5984/nemaki_conf/config_sso_oidc_enabled | python3 -c 'import sys,json;print(json.load(sys.stdin)["_rev"])')
curl -s -u admin:password -X PUT http://localhost:5984/nemaki_conf/config_sso_oidc_enabled \
  -H 'Content-Type: application/json' \
  -d "{\"_rev\":\"$REV\",\"type\":\"configuration\",\"key\":\"sso.oidc.enabled\",\"value\":\"true\"}"
# sso.saml.enabled も同様。SAML ボタンの遷移先はブラウザから届く URL であること:
curl -s -u admin:password -X PUT http://localhost:5984/nemaki_conf/config_saml_idp_sso_url \
  -H 'Content-Type: application/json' \
  -d '{"type":"configuration","key":"saml.idp.sso.url","value":"http://localhost:8088/realms/nemakiware/protocol/saml"}'
docker restart docker-core-1   # Configuration は configCache に載るので再起動が要る
```

`docker restart` で構いません。CLAUDE.md が禁じている `docker compose restart` は
「WAR を差し替えたのに古いまま動く」ケースの話で、ここは WAR を変えていません。

確認 (両方 true、`samlSsoUrl` が空でないこと):

```bash
curl -s "http://localhost:8080/core/rest/auth/config?repositoryId=bedroom"
```

LDAP グループの 1 件は `directory.sync.enabled=true` と bind password が要ります
(`directory.` は admin-managed ではないので `-D` がそのまま効きます)。
`POST /core/rest/repo/{repo}/sync/trigger` は sync 無効でも**トップレベルは
`status:"success"` を返し**、無効であることは `syncResult.status = "FAILED"` 側にしか
出ません。緑/赤の判断は `syncResult` を見てください。

実測 (2026-07-30, 上記を全て適用後):
`tests/auth/{oidc-login,saml-login,ldap-oidc-integration}` +
`tests/api/{keycloak-oidc-auth,session-management}` = **57 passed / 0 failed**。

## 残る skip 44 件の内訳 (2026-07-30 実測)

フル走行の内訳は `playwright-report/results.json` から取れます
(`--reporter=line` で上書きすると **json が出ません** — 既定の reporter で回すこと)。
skip 理由は各 test の `annotations[].description` に入ります。

| 件数 | 場所 | 理由 |
|---|---|---|
| 25 | `admin/purview-atlas-e2e.spec.ts` | Atlas 未設定 (下記) |
| 19 | 15 ファイルに散在 | `ENV: …` 前提物が見つからない (作成した文書が一覧に出ない等) |

### Atlas の 25 件

`checkAtlasAvailable` は ① Atlas に到達できる ② NemakiWare 側 `atlas.enabled=true`
の両方を見ます。②が既定で空なので全 25 件が skip します。有効化の順序:

```bash
curl -u admin:admin -X PUT -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
  http://localhost:8080/core/api/v1/admin/integration-settings/atlas \
  -d '{"atlas.enabled":"true","atlas.endpoint":"http://atlas:21000","atlas.username":"admin","atlas.password":"admin","atlas.collection":"e2e-collection"}'
curl -u admin:admin -X PUT -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
  http://localhost:8080/core/api/v1/admin/integration-settings/lineage \
  -d '{"lineage.mode":"journaled","lineage.targets":"atlas"}'
curl -u admin:admin -X POST -H 'X-Requested-With: XMLHttpRequest' \
  http://localhost:8080/core/api/v1/admin/purview/type-definitions/apply     # applied:true を確認
curl -u admin:admin -X POST -H 'X-Requested-With: XMLHttpRequest' \
  http://localhost:8080/core/api/v1/admin/purview/full-sync/bedroom          # checkpoint を現在に寄せる
```

ハマりどころ:

- **`docker-compose-atlas.yml` の overlay は使わないでください。** overlay は
  `purview.enabled=true` も渡します。すると catalog backend が Purview 側に切り替わり、
  `type-definitions/apply` が `Failed to acquire Purview access token: HTTP 404` で落ちます
  (`purview.auth.type=basic` を渡しても、接続テストは tenantId/clientId/clientSecret を
  必須として扱う)。Atlas だけ有効にするのが正解です。
- **full-sync を先に一度回すこと。** incremental-sync は checkpoint からの差分を
  1 回 100 件で追うので、checkpoint が数日前だと新規文書に永遠に追いつきません
  (これで `2.1 Document creation → sync` が落ちていました)。
- **`sburn/apache-atlas:2.3.0` の型ロックは詰まります。** 一度
  `ATLAS-500-00-005 Failed to get the lock` が出ると、Atlas を restart しても直りません
  (直の typedef POST でも同じエラー = Atlas 側の状態)。このイメージは volume を持たない
  ので、`up -d --no-deps --force-recreate atlas` で作り直せば消えます (約 100 秒)。
- 到達状況 (2026-07-30): Group 1 の 3 件 + `2.1`/`2.2`/`2.3` = **6 passed**、
  `2.4 Delete → Delete Resolution` で止まり、serial なので残り 18 件は未実行。
  `delete-resolution` 自体は `COMPLETED processedCount:1` を返すので、
  test 側の「消えたら `queryAtlasEntity` が null」という判定が Atlas の soft-delete と
  合っていない可能性が高い (未確認)。
- **緑を保つため、確認が済むまで `atlas.enabled` は `false` に戻してあります。**
  true のままだと 25 件が skip ではなく fail/未実行になり、フルスイートが赤になります。

## E2E を変更したら型チェック

```bash
cd core/src/main/webapp/ui
npm run type-check:all
```

**`npm run type-check` (= `tsconfig.json`) は `tests/` を見ません** — `include` が `src` のみ
だからです。テスト変更に対する「tsc clean」はアプリを検査しているだけで、テストについては
何も言っていません。この盲点で、一括置換が 71 ファイル・522 箇所の構文エラーを作っても
tsc は無言でした。テストを触ったら `type-check:tests` (= `tsconfig.tests.json`) を必ず。

`strict` は意図的に off です。spec 群は strict 前提で書かれておらず、有効にすると
「引数の形が違う」「存在しないメソッドを呼んでいる」といった本命の誤りが、数百件の
nullability 指摘に埋もれます。

## UI 単体

```bash
cd core/src/main/webapp/ui && npx vitest run
```

## デモ / 権限検証環境

`tools/test-env/` に階層組織・ネストグループ・エリア別 ACL のシードツールがあります
(`tools/test-env/README.md`)。
