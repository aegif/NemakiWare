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
docker compose -f docker/docker-compose-atlas.yml up -d atlas
curl -s -u admin:admin http://localhost:21000/api/atlas/admin/version   # 200 を待つ

mvn -pl core test -Dgroups=atlas-integration -Dsurefire.excludedGroups= \
  -Dtest=LineageIncrementBAtlasTest -DforkCount=0 -f core/pom.xml -Pdevelopment
```

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
| B-E1 | **Atlas OSS live 結果** | 起動する Atlas 2.x コンテナ。<br>2026-08-04 時点で `docker/docker-compose-atlas.yml` の image は**起動に失敗する** (`NoSuchBeanDefinitionException: ...ConfigurationClassPostProcessor.importRegistry` — Atlas 自身の Spring 配線の問題で、増分 B とは無関係)。まず image を起動する版に更新する必要がある | 上記の `mvn -Dgroups=atlas-integration` | 8 tests, 0 failures |
| B-E2 | **Purview live 結果** | Azure Purview アカウント、collection、`tenantId` / `clientId` / `clientSecret` (Service Principal に Data Curator ロール) | `PURVIEW_ENDPOINT` 等を設定し、手順 1〜3 を実 Purview に対して実行 | schema apply が `applied: true` / backfill が `successful: true` / reconciliation が `clean: true` |
| B-E3 | **属性 round trip (Purview)** | 同上 | B-E2 の後、`nemaki_folder_dataset` を 1 件読む | `repositoryId` / `objectId` / `active` / `sourceState` が保存されている |
| B-E4 | **大規模 backfill の再開性** | 10,000 folder 以上の repository | `maxBatches=1` で複数回、途中で core を再起動 | `processed` が単調増加し、最終的に `successful: true`。folder 数と `processed` が一致 |

**B-E1 の結果を B-E2 の代わりにしてはならない。** Atlas OSS と Purview は
error vocabulary も属性型の対応も relationship の意味論も共有しない。
Atlas が green でも Purview の証跡にはならない。

---

## 5. 増分 B が意図的に**しない**こと

- **companion を削除しない。** folder が delete / purge されても `ARCHIVED` / `PURGED` を
  付けるだけ。過去の Process が参照しており、input が消えた Process は
  「lineage のバグ」に見える。削除できるのは retention 経路のみで、対象は `PURGED` かつ
  参照 Process 0 件かつ retention 経過のもの。
- **既存 entity の qualifiedName を変えない。** 変えると過去の lineage が参照先を失う。
- **§2 の obligation machine を実装しない。** `PENDING` / `CLAIMED` / `RESOLVED` /
  `UNRESOLVED` と projector の `WAITING_FOR_CATALOG` は、待避する側の projector が
  この build では非活性 (writer は v1、D-rest driver は全て off) なので、
  今作ると消費者の無い状態機械になる。projector を活性化する作業と一緒に入れる。

---

## 6. 関連

- 設計: [`docs/design/atlas-lineage-endpoints.md`](../design/atlas-lineage-endpoints.md) §3
- 変更履歴: [`docs/design/atlas-lineage-endpoints-changelog.md`](../design/atlas-lineage-endpoints-changelog.md)
- 4b activation (別件・未実施): [`lineage-4b-activation-checklist.md`](lineage-4b-activation-checklist.md)
