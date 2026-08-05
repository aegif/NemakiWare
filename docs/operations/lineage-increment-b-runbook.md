# 増分 B (lineage catalog 拡張) — 運用 runbook

対象: `nemaki_folder_dataset` / `nemaki_import_artifact` / `nemaki_export_artifact` の追加、
`nemaki_document` / `nemaki_external_asset` への属性追加、folder companion の backfill・
lifecycle・reconciliation。schema version **13 → 15**。

---

## 1. 適用順序

全 endpoint は admin 権限を要し、state-changing なものは CSRF 検証されるので
`X-Requested-With: XMLHttpRequest` が要る (`CLAUDE.md` の CSRF 節)。

**この順序でなければならない。** backfill は schema が適用済みでなければ fail-closed で
拒否する (拒否時は resume 文書も書かないので、後から「進捗のある run」に見えることはない)。

```
1. 新 WAR を deploy
2. schema apply        → catalog に型が作られる
3. backfill (dry-run)  → 件数を確認
4. backfill (実行)     → 既存 folder に companion を作る
5. reconciliation      → 照合し、clean を確認
```

### schema apply

```bash
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/purview/type-definitions/apply"
```

**期待**: `applied: true`、`schemaVersion: "15"`。
`customTypes` に `nemaki_folder_dataset` / `nemaki_import_artifact` /
`nemaki_export_artifact`、`relationshipTypes` に `nemaki_folder_has_dataset` を含む。

**単調性**: 13 の catalog に 15 を適用すると 14 と 15 が足すものだけを作り、既存には触れない。
**再実行可能**であり、途中で失敗しても同じコマンドを再度流してよい。

---

## 2. backfill

### 事前確認 (dry-run)

書き込みは一切行わず、実カウントを返す。

```bash
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/purview/lineage/backfill/folder-dataset/bedroom/plan"
```

**期待**: `runnable: true`、`folderCount` が repository の folder 実数。
`refusal` が `NONE` 以外なら**実行してはならない**:

| refusal | 意味 | 対処 |
|---|---|---|
| `SCHEMA_NOT_READY` | 適用済み schema が この build の manifest と一致しない | 手順 2 の schema apply を先に流す |
| `CATALOG_UNREACHABLE` | catalog が答えない | 接続・認証を直す |
| `REPOSITORY_UNAVAILABLE` | root folder が無い | repository 設定を確認 |

進捗だけを見る (catalog に触れない):

```bash
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/purview/lineage/backfill/folder-dataset/bedroom"
```

### 実行

```bash
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/purview/lineage/backfill/folder-dataset/bedroom?maxBatches=10"
```

- **有界**。`maxBatches` × 100 folder で止まり、`state: PAUSED` を返す。
  同じコマンドを繰り返せば続きから進む (resume 文書に frontier が入っている)。
- **冪等**。2 回流しても entity は 1 つ。
- **中断してよい**。crash の損失は最大 1 batch。

**完了条件**: `state: COMPLETE` **かつ** `successful: true`。

**`state: COMPLETE` だけでは完了ではない。** `failed > 0` の COMPLETE は
「最後まで歩いたが一部失敗した」という意味で、`successful` は false になる。

| refusal | 意味 |
|---|---|
| `PUBLISH_FAILED` | catalog が一部を拒否した。原因を直して再実行すれば残りだけ処理される |
| `FRONTIER_TOO_LARGE` | 未処理 folder が resume 文書の上限を超えた。**id は捨てていない** |

---

## 3. reconciliation

```bash
BASE=http://localhost:8080/core/api/v1/admin/purview

# 報告のみ (repair は既定で false)
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "$BASE/lineage/reconcile/folder-dataset/bedroom?maxFolders=1000"

# 修復あり
curl -u admin:admin -X POST -H "X-Requested-With: XMLHttpRequest" \
  "$BASE/lineage/reconcile/folder-dataset/bedroom?maxFolders=1000&repair=true"
```

**期待**: `clean: true`。

`undetermined > 0` は **clean ではない**。catalog が答えなかった folder があるという意味で、
「問題が無い」ではなく「見られていない」。接続を直して再実行する。

`sourceMissing` は異常ではない。folder が消えた後も companion は残る設計であり、
`ORPHAN` が付くだけで**削除はされない**。過去の lineage が参照しているため。

---

## 4. live 検証

### Atlas OSS

```bash
# ARM64 host はネイティブイメージを使う (docker/atlas/build-arm64.sh で一度ビルド)
NEMAKI_ATLAS_IMAGE=nemakiware-atlas:2.3.0-arm64 NEMAKI_ATLAS_PLATFORM=linux/arm64 \
  docker compose -f docker/docker-compose-simple.yml -f docker/docker-compose-atlas.yml \
  up -d --force-recreate --no-deps atlas

curl -s -o /dev/null -w "%{http_code}\n" -u admin:admin \
  http://localhost:21000/api/atlas/admin/version   # 200 を待つ (60〜90 秒)

# -pl core は使わない (core は reactor の一部ではないので "Could not find the selected
# project in the reactor" になる)。-f core/pom.xml で直接指定する。
mvn test -Dgroups=atlas-integration -Dsurefire.excludedGroups= \
  -Dtest=LineageIncrementBAtlasTest -DforkCount=0 -f core/pom.xml -Pdevelopment
```

> **`docker compose restart atlas` は使用禁止。** 下の「Atlas が起動しないとき」参照。

**期待**: 8 tests, 0 failures。検証内容は

1. 3 つの新型が schema apply 後に catalog に存在する
2. 同じ schema の 2 回目の適用がエラーにならない
3. folder と companion が 1 つの bulk で受理される
4. `nemaki_folder_has_dataset` が受理される
5. 同じ companion の再 publish が entity を 2 つにしない (backfill の冪等性の前提)
6. ARCHIVED 遷移が保存され、companion が消えない
7. 追加属性が round trip する (catalog が黙って捨てていない)
8. folder 自体が変わっていない (増分 B は additive)

### `EXTERNAL_EVIDENCE_REQUIRED`

以下は**このリポジトリの中では取得できない**。取得手順を固定して外部証跡として残す。

| # | 項目 | 必要なもの | 実行 | 期待 |
|---|---|---|---|---|
| ~~B-E1~~ | ~~**Atlas OSS live 結果**~~ | — | — | **実測済み (2026-08-04): 8 tests, 0 failures, 0 errors。** image `nemakiware-atlas:2.3.0-arm64` (digest `sha256:04eb610a…`)、Atlas 2.3.0 |
| B-E2 | **Purview live 結果** | Azure Purview アカウント、collection、`tenantId` / `clientId` / `clientSecret` (Service Principal に Data Curator ロール) | `PURVIEW_ENDPOINT` 等を設定し、手順 1〜3 を実 Purview に対して実行 | schema apply が `applied: true` / backfill が `successful: true` / reconciliation が `clean: true` |
| B-E3 | **属性 round trip (Purview)** | 同上 | B-E2 の後、`nemaki_folder_dataset` を 1 件読む | `repositoryId` / `objectId` / `active` / `sourceState` が保存されている |
| B-E4 | **大規模 backfill の再開性** | 10,000 folder 以上の repository | `maxBatches=1` で複数回、途中で core を再起動 | `processed` が単調増加し、最終的に `successful: true`。folder 数と `processed` が一致 |

### 前提の更新 (2026-08-06)

**当面、利用可能な Purview 環境は無い。** この前提の下での方針:

- **実装は公開技術情報を根拠に進めてよい。** Microsoft Learn の Purview REST API 仕様
  (Atlas 互換 surface・AAD client credentials・throttling ガイダンス) に基づく実装・修正は
  live 環境なしで行い、決定的な単体テストで固定する。適用済みの例:
  - AAD v2.0 client-credentials + scope `https://purview.azure.net/.default` (実装済み)
  - `atlasBasePath` の設定化 — classic `catalog/api/atlas/v2` と新 `datamap/` surface の両対応 (実装済み)
  - **429/503 の `Retry-After` 尊重 (v2.3.82)** — budget 内なら指示どおり眠り、超えるなら
    この pass の再試行を打ち切って応答を返す。長い待機は obligation の durable backoff
    (分単位・fence の外) が担う。HTTP-date 形式は clock skew が任意長の sleep になるため
    「指示なし」として扱う
- **証跡は代替できない。** B-E2〜B-E4 は open のまま残す。公開仕様への準拠は
  「実 Purview で動く」ことの証明ではなく、mock・Atlas・公開文書のどれも live 証跡の
  代わりにならない。環境が得られた時点で本表の手順をそのまま実行する。

**B-E1 の結果を B-E2 の代わりにしてはならない。** Atlas OSS と Purview は
error vocabulary も属性型の対応も relationship の意味論も共有しない。
Atlas が green でも Purview の証跡にはならない。

### Atlas が起動しないとき (2026-08-04 に調査・解消済み)

**症状**: コンテナは `Up` だが `/api/atlas/admin/version` が 503。ログ末尾に
`org.springframework.beans.factory.NoSuchBeanDefinitionException: No bean named
'...ConfigurationClassPostProcessor.importRegistry'`。

**この Spring エラーは原因ではない。** 起動失敗の連鎖を `/apache-atlas/logs/application.log`
(コンテナ内。`docker logs` の tail ではなくこちらが正典) で遡ると、最初の例外は

```
EmbeddedKafkaServer.start(isEmbedded=true)
Starting zookeeper at localhost:9026
org.apache.zookeeper.KeeperException$NodeExistsException: KeeperErrorCode = NodeExists
→ AtlasBaseException: EmbeddedServer.Start: failed!
→ BeanCreationException: atlasRelationshipStoreV2
→ NoSuchBeanDefinitionException (末尾に出るのはこれ)
```

**原因**: `docker-compose-atlas.yml` は atlas に **volume を宣言していない**ので、
embedded HBase / ZooKeeper / Kafka の状態はコンテナの writable layer に置かれる。
`docker compose restart` はこの layer を保持するため、一度壊れた ZK ノードは
**restart を何度繰り返しても消えない**。

**対処**: `--force-recreate` でコンテナを作り直す (writable layer が捨てられる)。

```bash
NEMAKI_ATLAS_IMAGE=nemakiware-atlas:2.3.0-arm64 NEMAKI_ATLAS_PLATFORM=linux/arm64 \
  docker compose -f docker/docker-compose-simple.yml -f docker/docker-compose-atlas.yml \
  up -d --force-recreate --no-deps atlas
```

`--no-deps` を付けないと core / couchdb / solr まで作り直される。

**イメージ側の不具合ではない。** 同じ digest のイメージが fresh な writable layer では
正常に起動し、B-E1 が 8/8 で通ることを確認済み。第三者 Atlas への patch も jar の
手動差替えも不要だった。

---

## 5. 増分 B が意図的に**しない**こと

- **companion を削除しない。** folder が delete / purge されても `ARCHIVED` / `PURGED` を
  付けるだけ。過去の Process が参照しており、input が消えた Process は
  「lineage のバグ」に見える。削除できるのは retention 経路のみで、対象は `PURGED` かつ
  参照 Process 0 件かつ retention 経過のもの。
- **既存 entity の qualifiedName を変えない。** 変えると過去の lineage が参照先を失う。
- **~~§2 の obligation machine を実装しない。~~ 撤回 (v2.3.37〜v2.3.55)。** 当時の理由は
  「projector が非活性なので消費者の無い状態機械になる」だったが、4b は deployment を
  伴わない flag flip なので、活性化した瞬間に producer・consumer・recovery が揃って
  いなければならない。非活性のうちに実装し検証するのが唯一の順序である。
  現状は [`docs/design/lineage-obligation-handoff.md`](../design/lineage-obligation-handoff.md)
  を参照。

---

## 6. 関連

- 設計: [`docs/design/atlas-lineage-endpoints.md`](../design/atlas-lineage-endpoints.md) §3
- 変更履歴: [`docs/design/atlas-lineage-endpoints-changelog.md`](../design/atlas-lineage-endpoints-changelog.md)
- 4b activation (別件・未実施): [`lineage-4b-activation-checklist.md`](lineage-4b-activation-checklist.md)
- §2 obligation machine の現状: [`docs/design/lineage-obligation-handoff.md`](../design/lineage-obligation-handoff.md)
