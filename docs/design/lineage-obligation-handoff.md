# §2 obligation machine — 申し送り

**承認待ちは不要。この文書の「次の作業」から、確定した順序でそのまま再開してください。**

最終 checkpoint: `d7abaa2cd` (`test/v3.3-arm64-full` / `deps/v3.3-breaking-majors` の両方)。
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
| 5 | **N-1 producer / consumer / reclaimer** | `d7abaa2cd` |

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

### N-1 で入ったもの (完了)

- `LineageCatalogEntityProbe` — 三値 (PRESENT / ABSENT / UNKNOWN)。
- `LineageCatalogObligationService` — producer / consumer / reclaimer。
  gate は `active()` 1 箇所のみ。

**残っている穴**: `SOURCE_PURGED` の historical entity builder。§2 は
「削除済み source の historical entity は endpoint snapshot からしか作れない」と
定めるが、その builder が無いので consumer は ABSENT を retryable として release し
続ける。**terminal な `SNAPSHOT_INCOMPLETE` へ到達する自動経路がまだ無い**
(`giveUp` は API としてはあり、projector の滞留上限と admin route の入口になる)。
`LineageCatalogEntityProbe` の実装 (catalog client 側) もまだ無い — N-2 で
projector を結線するときに、既存の `PurviewEntityRegistryClient` 越しの実装を足す。

### N-2. projector の `WAITING_FOR_CATALOG` 統合

`LineagePublishStatus.WAITING_FOR_CATALOG` と遷移は既にある
(`CouchLineageV2TransitionStore` / `LineageSpoolMaterializer`)。**無いのは**:

- v2 行の `waitingTaskKeys` (複数可) と `waitingSince`。
- **全件が `RESOLVED` になって初めて** `PENDING` へ戻す (1 件では戻さない)。
- `waitingSince` は `PENDING` へ戻って再び待機してもリセットしない
  (往復で滞留上限を回避させない)。
- 待機中は publish しない・**cursor を進めない**・retry を消費しない。
- task key から待機 event を逆引きする index。
- `lineage.catalog-wait.max-age` (既定 24h) 超過で `UNRESOLVED`。

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
