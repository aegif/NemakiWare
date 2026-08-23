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
| D-6 | **N2: ZipImporter の文書 import が証拠の空殻を作る** | `ZipImporter:337/:513` → CMIS 経路 → `injectPropertyValue` が READONLY 値を**作成時にも**落とす。secondaryIds は生値で書かれ「型あり・中身 null」を製品の復元経路が製造 | 選択肢: (i) import 経路は model 直書き (取込と同じ側) に変える、(ii) 作成時だけ READONLY を通す (ONCREATE 相当)、(iii) 証拠型を含む zip を拒否。**archive restore (raw copy・無傷) との分裂**を設計に明記してから選ぶ |
| D-7 | **保護の拡張**: mail/note/record の skip 上書き + `applySourceMetadata` の `""` 上書き + `externalIntegration` プロパティの READONLY 化 | `:311-321` (意図的除外と明記済み)、`:2674-2690` (欠落値を空文字で put、**どの台帳にも未記載だったのを完全性監査が発見**)、`Patch_ExternalIntegrationSourceFields:153` READWRITE | 3 つは同じ仕事の面: **「証拠は記録なしに変わらない」を chat 以外へ**。READONLY 化パッチ → D6 の fill 規則を mail/note/record へ → `applySourceMetadata` を fill 化。**Gmail は当日メッセージを毎 poll 再取得**するので revision churn の解消も兼ねる |
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
| **CMIS `updateType` の実保護** — 今は偶然 3 つ + tripwire だけ | (c) §5.5、`ExceptionServiceImpl:1012-1059` は updatability を見ない | `constraintUpdatePropertyDefinition` に updatability 検査を足すのが本筋。tripwire は修正後も残す |
| **Solr/PII の方針判断** — `chatParticipants` が既定で全文検索に出る | `SolrUtil:1481-1512` が全 aspect 値を無濾過で索引。disclosure §6.2 で認知済み・担当未割当 | **方針判断が先** (検索できるべきか)。機構は Disclosure の第 3 の口として同じ表から導ける |
| **bulkUpdateProperties の add/remove 無視** (既存不具合) | `ObjectServiceImpl:1216-1244` | 直すなら必ず `modifyProperties` 経由 (= `keepEvidenceAspects` を通す) |
| **CatalogSecretBoundary 統合** — `PurviewLineageSink` だけ `sealed()` を呼ばない | disclosure §4 | sink の qualifiedName (非 `nemaki://`) の許可を先に決める |

## 6. A.1「分かち難く結合した保護属性」の判定

**名乗るのは (d) 完了後の判定作業として。** 現時点のブロッカー:
D-3 (参照共有) / D-2 (0 バイトの無名) / D-5 (capturedAt 単一保存) / D-6 (復元経路の分裂) /
D-7 (chat 以外が無保護 + source identity の `""` 上書き) / (e) の D7 (実行主体の食い違い) /
複製の偽証拠 (evidence-types §0) / `updateType` の偶然依存 / ローリング再起動の手続き依存 /
`PROTECTED` の機械的検査が書けない (`Patch_ChatContextMetadataSecondaryType` の定数が private のまま)。

## 7. やり直さない (閉じたもの)

D6 (fill/refuse/reimport イベント + 洪水防止 + intent-before-write pin) / 証拠型の 2 経路保護 /
(c) の管理 API・ZipImporter 型取込・READONLY 消去防止 / R2 `assuranceAsserted` / R3 digest 主語 /
Disclosure の 2 経路 (door 2 の脅威文は訂正済み) / skipCount「無限ループ」(誤りとして取り消し) /
DLQ **payload** の暗号化 (I-1 は request JSON の別穴) / ローリング再起動の運用節 /
`nemaki:contentHash` の CMIS 書換 (「宣言が無い」ことで塞がっている — 意図した保護とは名乗らない)。
