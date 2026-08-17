# §2 obligation machine — 申し送り

**この文書は現状を述べます。** 設計の正典は
[`atlas-lineage-endpoints.md`](atlas-lineage-endpoints.md)、経緯は
[`atlas-lineage-endpoints-changelog.md`](atlas-lineage-endpoints-changelog.md)。

最終 checkpoint: `v2.3.56` (`test/v3.3-arm64-full` / `deps/v3.3-breaking-majors` の両方)。
作業ツリー clean。

## 現状 — 実装は完了、残るのは Purview 固有の証跡のみ

producer・consumer・recovery・historical publish 状態機械・projector 統合・本番 adapter・
preflight まで実装済み。**4b は deployment を伴わない flag flip** なので、活性化した瞬間に
これらが揃っている必要がある — 非活性のうちに実装し検証するのが唯一の順序である。

| 部品 | 実体 | 状態 |
|---|---|---|
| obligation store / service / scanner | `CouchLineageCatalogObligationStore` ほか | 実装・IT 済 |
| catalog probe (target 別) | `LineageCatalogClientProbe` | 実装済 |
| historical publisher (target 別) | `CatalogHistoricalEntityPublisher` | 実装・実 Atlas IT 済 |
| exact read-back | 同上 (`readBackHistorical`) | 実装・実 Atlas IT 済 |
| current entity republisher | `CatalogCurrentEntityRepublisher` | 実装済 |
| source disposition resolver (kind 別) | `RepositorySourceDispositionResolver` | 実装済 |
| authoritative purge ledger | `CouchLineagePurgeLedger` + `ContentServiceImpl` hook | 実装済 |
| projector の `WAITING_FOR_CATALOG` | `LineageCatalogWaitCoordinator` | 実装・実 CouchDB IT 済 |
| operation budget (target/kind 別) | `ConfiguredLineageOperationBudgetProvider` | 実装済 |
| preflight | `GET /preflight` の `catalogObligations` 節 | 実装済 |

**残: B-E2〜B-E4 (Purview 固有)。** Atlas / mock の結果を Purview の証跡に流用しない。

## historical entity を書けない kind

`nemaki_external_asset` / `nemaki_import_artifact` / `nemaki_export_artifact` は Atlas 型に
`lifecycleState` も `sourceState` も無く、**tombstone marker を書く場所が無い**。Atlas は
未宣言属性を黙って捨てるため、書けば live entity と区別不能な entity ができる。
publisher はこれを `SNAPSHOT_INCOMPLETE` (唯一の終端 publish outcome) で拒否する。
`GET /preflight` の `historicalEntitySupportByKind` が kind ごとに表示する。

解消するには Atlas 型定義に marker 属性を追加する必要があり、これは別作業。

---


## 完了済み

| # | 内容 | commit |
|---|---|---|
| 0 | 増分 B の未カバー 2 項目 (stale 属性除去・artifact 接続) | `f87b147e3` |
| 1 | obligation の契約表 (設計書 §2 内) | `8c0217505` |
| 2 | identity / 文書形式 / 状態機械 / CAS・lease・fencing | `8c0217505` |
| 3 | barrier の server-defined 必須 capability へ統合 | `2be711b97` |
| 4 | Atlas 起動障害の原因究明 + B-E1 実測 (8/8) | `987b3f572` |
| 5 | N-1 producer / consumer / reclaimer | `d7abaa2cd` |
| 6 | N-1.5B probe の target 分離 | `35e530956` |
| 7 | N-1.5C durable capped backoff | `2cecb08a3` |
| 8 | N-1.5E/F/G 集合 verdict・timeout 分離・正確な件数 | `cbb238c24` |
| 9 | **N-1.5A(前半) readiness の配線検査 — false-green を閉じた** | `3fa6a0212` |

### 2 で入ったもの

- `LineageCatalogObligation` — record。`taskKey` は
  `hash("LINEAGE_CATALOG_OBLIGATION_V1", target, repositoryId, endpointKind, catalogQualifiedName)`。
  順序にも時刻にも依存しない。
- `LineageCatalogObligationStore` — 契約 (interface)。
- `CouchLineageCatalogObligationStore` — CouchDB 実装。`_rev` CAS、token fencing、
  `reclaimExpired` は CAS する read の下で期限を再確認する。
- view `obligationsByState`。event view ではないので coverage property から除外し、
  専用 coverage test を持つ。
- **record と store の両方が `SOURCE_ERROR` の `UNRESOLVED` 化を拒否する。**

### 3 で入ったもの

`catalog:obligations` を `LineageCapabilityProvider.WIRED` へ追加。これを欠く ACK は
barrier の condition 8 で落ちる。barrier 文書は必須集合を狭められない (binary の
baseline が union される) ことも test で固定。

---

## 完了した増分 (N-1.5 以降)

| # | 内容 | commit |
|---|---|---|
| N-1.5A(後半) | Spring 本番配線 + readiness 配線検査 | `987e2b5c9` |
| N-1.5C/E/F/G | durable backoff・集合 verdict・timeout 分離・正確な件数 | `cbb238c24` |
| N-2a.1/2/2.1 | snapshot resolver・replay provenance・origin 証明・publish 後補償 | `569e2cf64` `3b2c1009a` |
| N-1.5D/D.1/D.2 | intent 先行状態機械・lease 認可・arbitration・subject fence | `fab80d8aa` `2dee45aa2` `c0f3aa710` |
| N-1.5A-2/2.1 | historical machine 本番配線・実 CouchDB IT (本番バグ 2 件検出) | `53c44e651` `04e298a53` |
| N-1.5D.2.1 | target/kind 別 operation budget・view 障害の非隠蔽 (本番バグ 1 件) | `4acf101b3` |
| N-2b | projector の `WAITING_FOR_CATALOG` 統合 | `d99ae7cfc` |
| 本番 adapter + N-3 | publisher / read-back / republisher / resolver / purge ledger / preflight | `b3108a4ed` |

## 落とし穴 (この作業で踏んだもの)

- **`docker compose restart atlas` は効かない。** atlas に volume が無いので状態は
  writable layer にあり、restart は保持する。`--force-recreate --no-deps` を使う。
  詳細は [`../operations/lineage-increment-b-runbook.md`](../operations/lineage-increment-b-runbook.md)
  の「Atlas が起動しないとき」。
- **`mvn -pl core` は atlas IT に使えない** ("Could not find the selected project in
  the reactor")。`-f core/pom.xml` を使う。
- **JDT language server と Maven の race** —
  [`../development/troubleshooting-build.md`](../development/troubleshooting-build.md)。

---

## 引き続き禁止

4b activation / legacy projection 削除 / production・Purview 環境の変更 /
既存 CAS 契約・永続 identity の非互換変更。
