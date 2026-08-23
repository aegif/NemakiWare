# P1-1 残件計画 (2026-08-23 時点)

HEAD `246a0e03f` + 自己レビュー修正パスに対する、Codex + サブエージェント 2 本
(敵対的コードレビュー / 完全性監査) の並行レビューから作った計画。

> **この文書は日付入りのスナップショットである。** 各項目の正典は「所有文書」列。
> ここと正典が食い違ったら**正典を直してからここを直す**。

## 0. レビューの結果 (2026-08-23)

- **P0: 0 件** (3 本とも)
- 即時修正済み 7 件: 台帳欠落コミットの再適用 / tripwire の空虚 (null cast は投げない) /
  fill の TOCTOU (単一読み + before-write hook + order pin) / Assurance 宣言順の格子矛盾 /
  `keepEvidenceAspects` の参照持ち込み (防御コピー) / door 2 脅威文の誇張訂正 /
  `contentHashSubject` の v1 非配送の台帳追記
- 完全性監査は候補 17 項目を検証し、**8 件を「済み・偽」として殺し、4 件を新発見**した

---

## 1. 即時チケット (P1-1 の増分外。次に手を付ける)

| # | 項目 | 根拠 | アプローチ |
|---|---|---|---|
| I-1 ✅ 2026-08-23 | **DLQ の `originalRequestJson` に note 添付バイトが平文で残る** — 対応済み。決定した replay 意味論: **bytes は暗号化 attachment 経路のみ・request JSON は常に byte-free**。replay は本文を復元、添付は次ポーリングの skip-retry。stripped count を行と replay 応答に記録 | `NotionFetchOrchestrator:118-126` が metadata に `contentBase64` を注入し、除去は import が**返った後**の finally。その間の失敗で `saveToDlq` → `IngestJobService:215` が request を**無加工で** `nemaki_conf` へ。「payload は暗号化か破棄」(`:192-194`) の 20 行下で同じバイトが素通り | **payload と同じ規則に揃える**: DLQ 保存前に request JSON も `encryptDeadLetterPayload` を通すか、鍵が無ければ `contentBase64` を落として `payloadDropReason` に記録。**replay の意味論を先に決める** (バイトを失った replay は添付を再 fetch できるか)。復号は pre-encryption 行を許容する既存パターンを踏襲 |
| I-2 ✅ 2026-08-23 | **`purgeReplayed()` が到達不能** — 対応済み: `POST /dead-letters/purge-replayed` (admin) を配線 — replay 済み DLQ 行を製品の表面から消せない | `CouchLineageDeadLetterStore:292` 実装済み・呼び出し 0 件 | `LineageJournalController` の DLQ 系に purge エンドポイントを配線。admin 専用・CSRF 対象 |
| I-3 ✅ 2026-08-23 | **purge が既定で走らない**のに運用文書が保持期限を語る — 対応済み: replay 済み dead letter を purge スケジュールに同乗させ、runbook に保持の表を書いた (未 replay 行は意図的に残す) | `lineage.purge.cron` 既定空 → `LineagePurgeScheduler` は arm しない。DLQ 行は purge view の対象外 | 運用 runbook に「cron 未設定なら保持は無期限」を明記 (disclosure §6 は済)。**DLQ 行を purge 対象に含める**か別途の保持を設計 — I-2 と同時 |

## 2. (d) の残り (この順で)

| # | 項目 | 根拠 | アプローチ |
|---|---|---|---|
| D-1 ✅ 設計+実装 2026-08-23 | **メタデータ hash の設計** — [`p1-1d-metadata-hash.md`](p1-1d-metadata-hash.md)。レビュー済 (P1 3 / P2 6 を反映): 載った値を raw aspect 経路で読み戻して hash、**エスケープ付き正準形** (単射性)、**hash 2 本** (chat 11 = day-1 改竄シグナル / integration 9 = D-7 で昇格)、outbox 完了 evidence に記録、「hash を持つ最新の完了行」規則。**実装済み** (イベント側 hash と digest 式だけ (e) に残る) | data-model §5。改竄検出は content bytes のみ | 実装順は同文書 §5 |
| D-2 ✅ 2026-08-23 | **D5: 0 バイト attachment の skip が無記録** — 対応済み: 親 pass の完了 evidence に `attachmentsNotIngested` (fileName+reason)。CapturedContent のモデル側命名は残件 | `CanonicalImportServiceImpl:2168-2174` — hash 前に return。document・aspect・イベント・intent 皆無 | 「取り込まなかった」を D6 の reimport イベントと同じ形で記録 (`skippedReason` を pass 事実として)。**emit は wrapper 側** — early return に足すと D1 の形が戻る |
| D-3 ✅ 2026-08-23 | **D3: aspect の version 間参照共有** — 対応済み: `buildCopyDocument` が証拠 aspect を防御コピー (リストも新規)。4 呼び出し元を生成点 1 箇所で被覆 | `buildCopyDocument:1970` が同一 List/Aspect を共有。checkIn は `cancelCheckOut` の再読込で偶然救われ、`BulkCheckInResource:420` → `updateWithoutCheckInOut` に保険が無い | `buildCopyDocument` で**証拠 aspect だけ防御コピー** (keepEvidenceAspects の `copyAspect` を再利用)。全 aspect のコピーは挙動変更が広いので証拠に限定。AC7 を負のコントロール付きで |
| D-4 | **(d)-5: 会話の範囲 (window/scope/reason) の恒久的置き場** | `CaptureEvidenceField:226-240` 全て `V2Home.none` — flip で行き場を失う | Process 属性が唯一の候補 → **F-1 (flip 前提) と同じ設計で決める**。(d) 単独では動かさない |
| D-5 | **(d)-6: `chatCapturedAt` の第 2 の写しが無い** | `:883-901` 自認「There is no second copy」。11 個中 1 個だけイベントが運べない | aspect 付与を `execute()` へ引き込む設計 ((b) §8 の「emit 前倒し」は撤回済みのまま)。**(e) の隔離と同じ変更で** — 取込の境界を引き直すのは 1 度だけ |
| D-6 ✅ 2026-08-23 | **N2: ZipImporter の文書 import が証拠の空殻を作る** — 対応済み: **(iii) の精緻化 = 主張だけ落とす**。`stripEvidenceAssertions` が meta の証拠型 id (PROTECTED) と証拠プロパティ全部 (chat 11 + source identity 9 + externalContext 2、定数の合成で手数えなし) を import 前に除去し、**warning が落とした項目を全部名指し** (zip 全体拒否は棄却 — 捕獲文書 1 件で移行全体が死ぬ)。ACP 経路も同じ集合で濾過 | 分裂の設計判断は `stripEvidenceAssertions` の javadoc に記録: **archive restore (同一リポジトリ・raw copy・objectId 不変) は台帳行が主張を裏づけるので証拠は無傷で通る / zip import は新 id・台帳なしなので主張を書いてはならない**。(i) model 直書きは棄却 (import は create-child 権限だけで呼べる = folder 書込者が捏造可能。さらに contentHash+sourceObjectId は dedupe が読み戻すので**本物の捕獲を静かに抑止**する)。(ii) READONLY の作成時素通しは棄却 (P1-1(c) が閉じた表玄関を CMIS 全体で開ける)。CMIS 層の落とし (殻の機構) は `SecondaryIdsMatchAspectsAtCreateTest` がピン |
| D-7 ✅ 2026-08-23 | **保護の拡張**: mail/note/record の skip 上書き + `applySourceMetadata` の `""` 上書き + `externalIntegration` プロパティの READONLY 化 | `:311-321` (意図的除外と明記済み)、`:2674-2690` (欠落値を空文字で put、**どの台帳にも未記載だったのを完全性監査が発見**)、`Patch_ExternalIntegrationSourceFields:153` READWRITE | 対応済み (2e8cfcf30): `Patch_ExternalIntegrationEvidenceReadOnly` (8+contentHash 新設 READONLY) / fill 規則を mail/note/record へ (fill/refuse/reimport イベント + intent-before-write hook) / `putUnlessBlank` で `""` 上書き根絶。integration hash は改竄シグナルに昇格 |
| D-8 ✅ 2026-08-23 | **小物** — 対応済み: DLQ replay は archetype 無しを拒否 (plain fallback 廃止・直し方を明示)。作成時の `secondaryIds` は組み上がった aspect から導出 (解決できない型の殻を初版から作らない) | 監査 #21 / #17 | D-6 と同じ増分で。作成時は「secondaryIds と aspect の整合」を `setBaseProperties` に |
| D-9 | **復元 + InterPARES 逐条マッピング** (最後) | roadmap:192、棚卸し §5-§7 | モデルが安定してから。A.1 の判定 (§5) とセット |

## 3. (e) 失敗時の隔離 + 実行起源

| 項目 | 根拠 | アプローチ |
|---|---|---|
| **D7: 同名異義の解消** — イベントは `resolveExecutedBy` (`"unknown: …"`)、outbox は `callContext.getUsername()` + 呼び出し側申告の `onBehalfOf` | `newCaptureScope:1980-1981` vs `:997-1008`。`CaptureIntent:42-43` は同義と定義 | outbox 側を**イベントと同じ resolver に**揃える (弱い方に倒す)。AC9 |
| **実行起源の記録** — 委譲実行の executedBy が admitted-unknown | roadmap (e) 行 | scheduler が起動時に「誰の操作で schedule されたか」を運ぶ。**digest 式を動かすのはここで 1 度だけ** ((b) §5) — D-1 のメタデータ hash と同時 |
| **UNRESOLVED が「落ちた」と「実行中」を区別しない** | sweep 15 分 < fetch timeout 30 分、heartbeat 無し | intent に heartbeat / lease を足すか、sweep の stale 閾値を fetch timeout に連動。M5 の運用文言は済 |

## 4. flip 前提 (v2 write flip と同じ変更で閉じる)

| 項目 | 根拠 |
|---|---|
| **Process 属性の v2 供給** — `folderId`/`importMode`/`sourceDescription`/`externalStableKey` (必須) が v2 record では既定値で埋まる | (b) §3.4、`PurviewLineageSink:403-409` |
| D-4 の会話範囲 + `REIMPORT_*` + `assuranceAsserted` の v2 home | `CaptureEvidenceField` の `V2Home.none` 群 |
| `contentHashSubject` / `contentHash` の**カタログ配送開始** — v1 では Process 型が宣言せず落ちる | Codex P1、data-model R3 追記済み |

## 5. 独立チケット (どのフェーズにも属さない)

| 項目 | 根拠 | 一言 |
|---|---|---|
| ✅ 2026-08-23 **CMIS `updateType` の実保護** — `constraintUpdatePropertyDefinition` が READONLY の拡張を拒否 (絞り込みは許可)。tripwire は残置 | (c) §5.5 | 済 |
| **Solr/PII の方針判断** — `chatParticipants` が既定で全文検索に出る | `SolrUtil:1481-1512` が全 aspect 値を無濾過で索引。disclosure §6.2 で認知済み・担当未割当 | **方針判断が先** (検索できるべきか)。機構は Disclosure の第 3 の口として同じ表から導ける |
| ✅ 2026-08-23 **bulkUpdateProperties の add/remove 無視** (既存不具合) — `withSecondaryTypeChanges` が add/remove を `cmis:secondaryObjectTypeIds` へ畳んでから `updateProperties` へ渡す = 要求どおり `modifyProperties` → `keepEvidenceAspects` 経由。別口の attach/detach は作らない | `ObjectServiceImpl` BulkUpdateTask | helper 単体 4 + call() 配線 pin 1 (`BulkUpdateSecondaryTypeChangesTest`)。3 protections とも revert で fail を実測 |
| **CatalogSecretBoundary 統合** — `PurviewLineageSink` だけ `sealed()` を呼ばない | disclosure §4 | sink の qualifiedName (非 `nemaki://`) の許可を先に決める |

## 6. A.1「分かち難く結合した保護属性」の判定

**名乗るのは (d) 完了後の判定作業として。** 現時点のブロッカー
(〜~~取消線~~ = 2026-08-23 までに解消):
~~D-3 (参照共有)~~ / ~~D-2 (0 バイトの無名)~~ / D-5 (capturedAt 単一保存) /
~~D-6 (復元経路の分裂)~~ / ~~D-7 (chat 以外が無保護 + source identity の `""` 上書き)~~ /
(e) の D7 (実行主体の食い違い) / 複製の偽証拠 (evidence-types §0) /
~~`updateType` の偶然依存~~ (実保護に置換・tripwire 残置) / ローリング再起動の手続き依存 /
`PROTECTED` の機械的検査が書けない (`Patch_ChatContextMetadataSecondaryType` の定数が private のまま)。
残り: **D-5 / (e) の D7 / 複製の偽証拠 / ローリング再起動 / PROTECTED 機械検査** の 5 つ。

## 7. 最終レビュー (2026-08-23) と対応

バッチ全体 (570c99396..HEAD) に Codex + サブエージェントの 2 本を実施。
**修正 10 件 (全て revert→fail 実測済み)**:
bulk fold の properties=null NPE (この形は modifyProperties の早期 return で**元々一度も
動いていなかった** — fold が新規 Properties を実体化して初めて動く) /
add/remove リストの型 id 無検証 (typo が numUpdated=N の成功で黙殺 → properties 変種と
同じ `invalidArgumentSecondaryTypeIds` を合成リストに適用) /
strip の迂回 3 経路 (relationship JSON・ACP の `cmis:secondaryObjectTypeIds` property 要素・
FilesystemImporter) + String 形の無テスト /
patch の contentHash 既存 core 衝突 (**他型の detail を READONLY 化し `details.get(0)` を
共有結線していた** → 型が参照する detail だけに絞り、他型専有なら共有 core 上に detail
直作成・警告) / FillOutcome エラー経路の refused 喪失 / verify の重なり検出漏れ
(view キーが intentOpenedAtMs なので「baseline 完了前に open した未解決 pass」を見落とし
偽 MISMATCH → 全行取得 + メモリ判定、満杯ページは UNVERIFIABLE 側へ) /
copyAspect の可変値 (List/Calendar) 共有。
**文書修正 3 件**: 「単射」主張のスコープ明確化 (M1) / READWRITE 同期ペアを strip する
理由の明文化 (F-7) / 「製品自身の復元経路が殻を製造」は過大 — exporter は secondary ids を
書かないので細工/他製品 archive に限る (F-8)。
**対応不要と判断**: F-10 (型 import の再定義は skip-if-exists + patch 再適用で自己修復、
検証済み)。テスト強化: コンストラクタ明示参照 (F-5)・cast 撤去 (F-4)。

## 8. やり直さない (閉じたもの)

D6 (fill/refuse/reimport イベント + 洪水防止 + intent-before-write pin) / 証拠型の 2 経路保護 /
(c) の管理 API・ZipImporter 型取込・READONLY 消去防止 / R2 `assuranceAsserted` / R3 digest 主語 /
Disclosure の 2 経路 (door 2 の脅威文は訂正済み) / skipCount「無限ループ」(誤りとして取り消し) /
DLQ **payload** の暗号化 (I-1 は request JSON の別穴) / ローリング再起動の運用節 /
`nemaki:contentHash` の CMIS 書換 (「宣言が無い」ことで塞がっている — 意図した保護とは名乗らない)。
