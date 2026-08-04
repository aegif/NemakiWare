# §2 obligation machine — 申し送り

**承認待ちは不要。この文書の「次の作業」から、確定した順序でそのまま再開してください。**

最終 checkpoint: `3fa6a0212` (`test/v3.3-arm64-full` / `deps/v3.3-breaking-majors` の両方)。
作業ツリー clean。

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

## 次の作業 (この順序)

### N-1.5 で入ったもの

レビュー指摘 5 点のうち **4 点を閉じた**。

- **B** — `presenceOf(target, repositoryId, kind, qn)`。`LineageCatalogProbeRegistry` が
  target ごとに routing し、未知 target は fail-closed で UNKNOWN。fallback 無し。
- **C** — obligation 文書に `notBeforeMs`。query 段と claim CAS 直前の 2 段で
  eligibility を確認。overflow / clock 逆行は `Long.MAX_VALUE` へ fail-closed。
  自動 terminal 化はしない。
- **E** — `verdictFor()` が `ALL_RESOLVED` / `WAITING` / `TERMINAL_UNRESOLVED` /
  `INDETERMINATE` を返す。**空 key 集合は INDETERMINATE** (成功ではない)。
- **F** — `giveUp()` → `recordSnapshotIncomplete()` に改名し用途限定。
  event の待機上限で obligation を terminal 化してはならない。
- **G** — `countByState()` は view の `_count` reduce で正確に数え、
  `StateCount(count, truncated)` を返す。読めなければ `lowerBound(0)`。
- **A(前半)** — readiness が `LineageObligationWiring` を検査する。gate を読まないので
  再帰せず、D-rest OFF でも意味のある答えを返す。

### 次の作業 (この順序)

### N-1.5A(後半). Spring 本番配線

**readiness は既に red**。配線が無い node は gate を通らないので、現状は
「動かない機構で activation できる」状態ではない。残っているのは配線そのもの。

1. `CouchLineageCatalogObligationStore` を bean にする (`LineageStoreSupport` は
   `CouchLineageJournalStore` が実装しているので、そこから作る)。
2. target ごとの `LineageCatalogEntityProbe` 実装 — 既存
   `PurviewEntityRegistryClient.getEntityByUniqueAttribute` 越し。
   **404 を ABSENT、例外を UNKNOWN** に分けること。
3. `LineageCatalogObligationService` bean。
4. bounded scanner/reclaimer (既存の scheduler に載せる。`active()` で gate 済み)。
5. `LineageObligationWiring` bean を組み立てて readiness へ注入。

### N-1.5D. historical entity builder

`LineageHistoricalEntityPublisher` の**契約だけ**があり実装が無い。
readiness が target ごとに要求するので、**これが無い限り gate は red のまま**。

- endpoint snapshot だけから再構成する (live source は読めない前提)。
- publish 後の read-back で PRESENT を確認してから `RESOLVED(SOURCE_PURGED)`。
- read-back UNKNOWN / 5xx / timeout は retryable。
- snapshot が構造的に不足しているときだけ `SNAPSHOT_INCOMPLETE`。
- consumer 側の `settle()` に `SOURCE_PURGED` 経路を足す
  (現在は ABSENT を一律 retryable として release している)。

### N-2. projector の `WAITING_FOR_CATALOG` 統合

`LineagePublishStatus.WAITING_FOR_CATALOG` と遷移は既にある
(`CouchLineageV2TransitionStore` / `LineageSpoolMaterializer`)。**無いのは**:

- v2 行の `waitingTaskKeys` (複数可) と `waitingSince`。
- **全件が `RESOLVED` になって初めて** `PENDING` へ戻す (1 件では戻さない)。
- `waitingSince` は `PENDING` へ戻って再び待機してもリセットしない
  (往復で滞留上限を回避させない)。
- 待機中は publish しない・**cursor を進めない**・retry を消費しない。
- task key から待機 event を逆引きする index。
- `lineage.catalog-wait.max-age` (既定 24h) 超過は **event だけ**を
  `UNRESOLVED(reason=CATALOG_WAIT_EXPIRED)` にする。obligation は PENDING/CLAIMED の
  まま継続し、同じ task を待つ別 event は待ち続ける (N-1.5F)。
- 判定は `verdictFor()` の 4 値を使う。`INDETERMINATE` は**状態を変えない** (fail-closed)。
- 逆引き view は event の document type と schema version を厳密に限定し、
  古い v1 view へ v2 行を露出させない coverage test を足す。

### N-3. activation 前 preflight への統合

`LineagePreflightShapeTest` / 4b preflight に obligation の状態を足す。
`PENDING` / `CLAIMED` が残っている状態での activation をどう扱うかを決めて test で固定
(安全側は「残っていても activation は拒否しない。ただし件数を verdict に出す」——
obligation は activation 後に発生するものなので、事前に 0 である必要はない)。

### N-4. B-E2〜B-E4

runbook の表のとおり。**Atlas の結果を Purview へ流用しない。**

### N-5. disposable 環境での 4b リハーサル

`POST /barrier/activate` は **disposable 環境でも実行禁止**。activation 直前までの
全条件が観測・判定できることを示す。手順は runbook §4 と
`lineage-4b-activation-checklist.md`。

---

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
