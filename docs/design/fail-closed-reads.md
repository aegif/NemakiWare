# 取込の fail-closed reads — 現行の主張・凍結・残件（正典）

作成 2026-09-14（HEAD `bfc5629db`）。ブランチ `fix/v34-fail-closed-reads` の取込側
（DLQ / IMAP IDLE / 設定読み）について、**今の振る舞いだけ**を書く。巡ごとの本文は
[`../history/fail-closed-review-rounds.md`](../history/fail-closed-review-rounds.md)（引用するな）。
custody の正典は [`p3-4-custody-transfer.md`](p3-4-custody-transfer.md)。

## 1. 何を主張し、何を主張しないか

- **主張する**: 設定・DLQ・IDLE の読みで「訊けなかった」を「無い」と書かない。
  一意の喪失は行か、書けなければメモリの数として残す。
- **主張しない**: 全読みを直した、通し negative-control 済み、DLQ 再実行が常に元バイトを
  復元する、`upsertDocument` が原子的。

## 2. 凍結

- **DLQ payload 状態機械**（`hasContent` / `payloadPresenceAssumed` / `payloadDropReason` /
  `payloadWriteToken` / `sourceNeverRead` と、添付名 `payload`・2 段書き・確定書き込み）。
- 現行: token あり → 409・添付不使用。bytes 無し保存 → token 継承。
  新しい欄・lease・自己修復を足さない。
- 解凍: CAS な upsert + 添付世代が token に結び付く。別バッチ。

## 3. 扉の許す組（新しい印を足す前に、この組を変えるかユーザーに聞け）

- **IDLE 再認可が訊けない**: この通は取り込まない。セッションは維持。
  行が書けたら `saveSourceNeverReadToDlq`。書けなければ `undurableMisses`。
  `stopIdle` しない。確定拒否（壊れた行、委譲取り消し）は止める。
- **DLQ 再実行**: token あり 409。`payloadDropReason` あり 409。
  `sourceNeverRead` の skip では削除しない。webhook 記録行
  （予約接頭辞の現状は残件）は replay しない。
- **設定読み**: 例外 / `loadFailed` は不在ではない。circuit breaker に数えない。

## 4. 残件表（再審して製品を開かない）

| ID | 内容 | 解凍の条件 | やめた |
|---|---|---|---|
| R1 | `upsertDocument` は CAS ではない（lost-update class） | CAS | |
| R2 | `getOrRefuse` は walk しない | 別バッチ | |
| R3 | セレクタ障害中の開示（署名不一致を 503 にする案） | 未着手 | |
| R4 | 公開 4 引数 `createDirectRelationship` の再認可なし | 別バッチ | |
| R5 | gate / execute の版 TOCTOU | 別バッチ | |
| R6 | 数値 `repositoryId` はどのリポジトリも名指さない | 意図 | |
| R7 | Mango `_find` が添付 stub を返す前提は未測定 | 実測 | |
| R8 | ~~通し negative-control 未実施~~ **処置済み 2026-09-15**: 通し 621 本を 1 回完走（§5）。以後は CAS の後に 1 回 | — | |
| R9 | DLQ の 503 級失敗は 200 `"failed"`（403 だけ例外） | 意図した限定 | |
| R10 | ~~`webhook-deliveries:` は予約名ではない~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: 既存の `sourceObjectId` 接頭辞ではなく、行自身の欄 `webhookDeliveryRecord`（`saveWebhookDeliveryRecordToDlq` だけが立てる）で扉が拒否する。錠 4 本、control ZK2/ZL2/ZM2/ZN2 | — | |
| R11 | ~~フォルダ読み swallow → `CREATOR_CMIS_ALL_LOST` / `TARGET_FOLDER_UNRESOLVABLE` で IDLE 停止~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `resolveFolderIdOrRefuse` / `canManageProfileForFolderAsUserOrRefuse` と新しい拒否理由 `TARGET_FOLDER_LOOKUP_FAILED` / `CREATOR_CMIS_ALL_LOOKUP_FAILED`（could-not-ask 側）。答える側の旧メソッドは他の呼び出し元のため不変（プロファイル編集 gate の 403 は別残件）。錠 8 本、control ZB2〜ZF2 | — | |
| R12 | IDLE ラムダの per-message 腕は 2026-09-14 に adapter 注入 (`adapterFactory`) で実測できるようになった（`driveOneIdleMessage`）。connect / IDLE ループ / `fetchMessage` の実 I/O は依然未測定 | 実セッション | |
| R13 | DLQ ページの `skip` に安定ソートが無い | 別バッチ | |
| R14 | Notion の per-page catch は adapter が `new` されるため未測定 | 注入 | |
| R15 | コントロール×兄弟錠 2403 組は未判定 | 掃きはユーザー指示があるまで禁止 | |
| R16 | `statusOfIdleRefusal` が `raw` も見る | 意図した非対称（敵対 id は 503 しか買えない） | |
| R17 | 一覧が空に見えるだけの経路 — 読みの失敗が「無い」という**断定**に使われない箇所は直していない（RELEASE_NOTES が指す） | 別バッチ | |
| R18 | ~~`IngestJobService.findRawDocs` が `docs == null`（store が文書一覧を返さない）を空扱い → DLQ 一覧は空、再実行は 404、purge は success（60 巡 Codex P2）~~ **処置済み 2026-09-14**（Codex 3 回のレビューを経て取り込み、`7ca81425d` でコミット済み） | — | |
| R19 | ~~purge の `deleteExactRevision` が競合以外の失敗も「行が動いた」= 0 として success（60 巡 Codex P2）~~ **処置済み 2026-09-14**（Codex 3 回のレビューを経て取り込み、`7ca81425d` でコミット済み） | — | |
| R20 | purge は先頭 1,000 行しか走査せず、続きがあることを応答が言わない（60 巡 Codex P2。RELEASE_NOTES の「中断した purge は 503」より弱い） — **文面で上限を明示済み (2026-09-14)**、製品は不変 | ページング | |
| R21 | ~~`reserveDlqRetry` の 429 の腕が死んでいる — `_rev` 競合は SDK が `ConflictException` で投げ、`catch (Exception)` が 503「訊けなかった」にする（60 巡 subagent P2）~~ **処置済み 2026-09-14**（Codex 3 回のレビューを経て取り込み、`7ca81425d` でコミット済み） | — | |
| R22 | ~~IMAP IDLE が `executeMailImport` の**結果**を捨て「imported」とログ — 結果として返る拒否（対象フォルダ読取失敗等）は DLQ にも `undurableMisses` にも載らず、IMAP は再配信しない（60 巡 subagent P2）~~ **処置済み 2026-09-14**（Codex 3 回のレビューを経て取り込み、`7ca81425d` でコミット済み） | — | |
| R23 | ~~DLQ 書き込みがセレクタの空答えを「行なし」と読み、生成 id の 2 行目を作りうる（60 巡 P3）~~ **処置済み 2026-09-15（バッチ 2）**: 新しい DLQ 行は `ingest_dlq:<dlqId>` の確定 `_id`。隠れた行への 2 度目の書き込みは 409 →「記録できなかった」に倒れる。CAS は入れていない（R1）。生成 id の旧行は未移行でその双子は残る。錠 2 本（ジョブ行は生成 id のままの対照を含む）、control ZV2。確認レビュー 2 本 CONVERGED。subagent が条件にした「削除済み行（tombstone）の上に `_rev` 無しで再作成すると 409 か」は scratch の CouchDB 3.3.3 で実測: POST / PUT とも 201（rev N+1）。再実行成功 → 行削除 → 同じ項目が再失敗、の順路は記録できる | 旧行の双子は R1 の後に | |
| R24 | `upsertDocument` は競合で throw するのに、3 か所のコメントと `== null` 判定が「null を返す」前提（凍結領域、60 巡 P3） | 凍結解除時 | |
| R25 | `classifyErrorStatus` の 500 fallback に落ちる拒否: 自動解決の曖昧さ、`findExistingDocument` の `[permanent]` 重複拒否（60 巡 P3） | 分類の腕 | |
| R26 | ~~`_all_docs` walk の transport 失敗が ISE 経由で 400「retry shortly」（定義 API の作成腕、60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `NemakiConfAllDocs.WalkDidNotAnswerException`（ISE の派生）で走査の未応答を型付けし、create の 3 腕が typed 503 に写す。行が読めない場合は従来どおり 400。錠 4 本、control ZH2/ZI2/ZJ2 | — | |
| R27 | ~~`DELETE /dlq/{id}` の 404 本文が「retry」、生 RuntimeException は 500（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `deleteDlqEntry` の**削除要求** (`deleteDocument`) の transport 失敗を `IngestStoreDidNotAnswerException`（503）に包む（セレクタ側の失敗は未包装 → R40）。404 の本文（索引が追いつくまで retry）は不在を確かめる手段が無いため残す。錠 1 本、control ZW2 | — | |
| R28 | ~~未配線腕が「無い」と答える潜在箇所: webhook の `propertyManager` null →「No access token」400、`integrationSettingsService` null で冪等性検査を飛ばす、`CheckpointManager` の「never polled」（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: webhook の `resolveToken`（未配線 → 503）、冪等性検査（未配線 → 拒否）、`CheckpointManager` の 2 つの読み（未配線 → 拒否）。錠 4 本、control ZP2/ZQ2/ZR2/ZS2 | — | |
| R29 | ~~scheduler poll: 1 プロファイルの listing 失敗（`ConnectorIndexNotReadyException`）が tick 全体を落とす（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `resolveConnectorFor` が `listByArchetype` の拒否を `Unresolved.LISTING_REFUSED`（答えではない）で返し、poll は続く。フォルダ実行・手動起動は 503。錠 3 本、control ZG2/ZX2/ZY2 | — | |
| R30 | ~~webhook 非同期 fetch が `SettingUnreadableException` を ERROR ログだけで落とす（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: 非同期 fetch の `SettingUnreadableException` を認可の腕と同じ `recordUndeliveredWebhook` で記録。錠 1 本、control ZO2 | — | |
| R31 | ~~`resolveTargetFolderId` が権限拒否も retryable=true（503「retry shortly」）にする（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `CmisPermissionDeniedException` は retryable=false、分類は 403（`permission denied for the importing user`）。錠 1 本、control ZT2/ZU2 | — | |
| R32 | ~~RELEASE_NOTES「中身を持つのに payload が返らない場合は 409」は assumed の腕（中身なしで再実行し、成功なら削除）を言っていない（60 巡 P3）~~ **文面を訂正済み 2026-09-14** | — | |
| R33 | multipart 取込の未型 RuntimeException → 400「Invalid request」（JSON 経路は 500）（60 巡 P3） | 型分け | |
| R34 | ~~**凍結領域の P1 class**: bytes 無し保存が**読めない既存行**の上に書くとき token を `null` にする — `existing == null` の 2 義（「行が無い」/「読めなかった」）のうち後者で、`hasContent` と履歴カウンタは同じ catch で「不明」扱いなのに token だけ隣に残った。読めない行が未確認 token を持てば次の bytes 無し保存で扉が開く（Phase 2 subagent）~~ **処置済み 2026-09-14（ユーザー判断）**: `rowWasUnreadable` の腕だけ、既存の `payloadWriteToken` に新 UUID を立てて既存の 409 扉に乗せた（新しい欄・lease・自己修復・添付の再読なし。「行が無い」側は null のまま）。錠 `aByteLessSaveOverAnUnreadableRowKeepsTheDoorShut`（保存 → token 非 null → 再実行 409）、control XV2。**凍結は解いていない** | 解凍条件は不変（CAS な upsert + 添付世代が token に結び付く） | |
| R35 | token 継承で、並行する bytes 無し保存 B の行が A と同じ token を持ち、A の確定書き込みの所有権検査（WF2）が B の行を自分の行と見る。結果は凍結前と同じ合成で退行ではないが、検査の保証は「payload 付きの別保存」に狭まった（Phase 2 subagent） | R1（CAS でも閉じない: A は最新 rev を読んでいる） | |
| R36 | ~~`deleteDlqEntry` は store が確認した削除だけを数えるようになったが、同じ dlqId の行が 2 行以上あり一方だけ未確認のとき `removed > 0` で success / resolved と答える（Codex 3 回目のレビュー。R23 の重複行が前提）。同じ領域の 3 度目の指摘なので止めた~~ **処置済み 2026-09-14（ユーザー判断）**: `deleteDlqEntry` が `DlqDeletion(confirmed, unconfirmed)` を返し、未確認が残れば `DELETE` は 503、再実行の後片付けは `*-entry-kept`。双子行のマージも一意制約も足していない（R23 / R1 のまま）。錠 3 本、control XX2 / XY2（+ XU2 / VG2 / VN2 の宣言追加）。**同じ領域の 4 度目は止まる** | — | |
| R37 | ~~再実行の後片付け `deleteDlqEntry` で `findRawDocs` が「store が答えなかった」（R18 の型付き拒否）を投げると、扉の `catch (Exception)` に落ちて **500 "Retry failed"** になる。取込は成功済みで行は残り、次の再実行は冪等に resolved になるので損失はないが、応答文が偽（R34/R36 確認レビューの subagent P3。範囲外）~~ **処置済み 2026-09-15（Phase C-1）**: 後片付けの typed 拒否は `*-entry-kept` + `entryKeptNote`（取込は成功済み、行は残る）。錠 1 本、control AH3 | — | |
| R38 | webhook 受信の GET 握手で「コネクタ読みが答えなかった → 503」は、腕の catch とクラスの `@ExceptionHandler` の**二重**の保護で、片方を外しても錠が緑のまま（通しスイープで WX が不発）。1 錨のサボタージュでは測れないので WX は退役。XE（`get()` に戻す）は独立に発火 | 二重保護のどちらかを外す製品変更（今は開かない）か、複数錨のサボタージュ | |
| R39 | ~~IDLE の per-message ラムダの `catch (Exception)`（`fetchMessage` の I/O 失敗、`executeMailImport` 自体の例外など）はログだけで、DLQ にも `undurableMisses` にも載らない。IMAP は再配信しない。R22 は**結果**として返る拒否だけを記録した（並行レビュー 2026-09-15 の隣接指摘）~~ **処置済み 2026-09-15（バッチ 1）**: IDLE ラムダの `catch (Exception)` が `recordIdleFailure` で記録する — fetch 前は never-read 記録行、fetch 後はメタデータのみの行、書けなければ `undurableMisses`。取込内部の失敗は結果として返り既に記録されるので二重には書かない。錠 3 本、control BC3/BD3/BE3 | — | |
| R40 | ~~`IngestJobService.findRawDocs` の `postFind` 自体は包まれておらず、`deleteDlqEntry` のセレクタ側 transport 失敗と `getConfClient()` の ISE は生のまま — DELETE は 500、再実行の後片付けは 500 "Retry failed"（R27/R37 の兄弟。損失も偽の不在もない。Phase D subagent P3）~~ **処置済み 2026-09-15（バッチ 1）**: `findRawDocs` の `postFind` と `getConfClient()` の未配線を `IngestStoreDidNotAnswerException`（503 / 後片付けは `*-entry-kept`）に。錠 2 本、control BA3/BB3 | — | |
| R41 | R10 の移行面: 印の無い接頭辞だけの記録行（このブランチの中間ビルド `55f35915d` 以降が書いたもの。リリース版には接頭辞自体が無い）は扉を通り、`sourceNeverRead` は dispatch 後にしか効かないので空の webhook 取込が走りうる。開発 DB だけの話で、`sourceObjectType=webhook_event` かつ接頭辞付きの行を消せば済む（Phase D subagent P3） | 開発 DB の掃除。移行は書かない | |
| R42 | 凍結領域（バッチ 2 の subagent P3、記録のみ）: `reserveDlqRetry` は扉の `getDlqEntry` と upsert の間で索引が行を隠すと、確定 id の 409 を「他の再実行が保持」(429) と読む。差分前は同じ窓で retryCount+1 の双子を作っていたので安全側への変化だが、文言は事実でない | R1 (CAS) の後に文言を確認 — CAS は読んだ `_rev` に条件付けるので、この窓そのものが消えるはず | |
| D1 | token 付き行の**添付前検査**（57 巡）、添付を landed の証拠に読む**自己修復**（58 巡）、15 分で通常経路に落とす **lease**（59 巡） | 凍結解除まで再導入しない | やめた（いずれも古い bytes を新しいメタデータで再生する同じ class に落ちた） |

## 5. 測定

- コントロール 645（2026-09-15 時点。内訳: 通し前 621 → 不発の WX を退役して 620 → Phase C で
  25 新設）。**通し negative-control は 2026-09-14〜15 に 1 回完走**（621 本、約 15 時間、exit 1）:
  620 発火、不発 1（WX。腕とクラス `@ExceptionHandler` の二重保護で 1 錨では測れない → 退役、R38）、
  宣言漏れ 68 本（ログ末尾の節から機械的に補完し、ID 指定で再実測 68/68）、錨外れ 0、製品欠陥 0。
  以後、通しは毎バッチでは走らせず、CAS（R1）のあとに 1 回。事前検査は exit code と `== summary` の
  存在で確認（出力が無いことを clean と読むな）。**runner は錠が自分の assertion で落ちたときだけ
  発火と認める** — 例外が素通りする欠陥の錠は `assertDoesNotThrow` で包む。
- 全ユニット 6,978 本 green（Phase C 適用時点。`bfc5629db` 時点は 6,927。
  `!MultiThreadTest,!InheritedFlagTest,!*IT,!jp.aegif.nemaki.cmis.tck.**,!AtlasManualDataLoader` を除外）。
- 製品差分の目安は 1 バッチ 100 行/ID。Phase C は 9 ID で +420/−71。

## 6. 巡の追記規則

- 新しい巡は**このファイルに 70 行足さない**。残件／凍結が変わったときだけ表を更新。
- 詳細が要るならアーカイブ末尾に「YYYY-MM-DD / コミット / P1 件数 / 一言」の **1 行**。

## 7. レビュー依頼文

下をそのまま貼る（一言も足さない）。

```
対象: ブランチ fix/v34-fail-closed-reads の凍結範囲だけ。
正典: docs/design/fail-closed-reads.md（なければ docs/design/p3-4-custody-transfer.md 末尾の凍結節）。
見てよい差分: DLQ 再実行扉の token→409、bytes 無し保存の token 継承、readValue の admin-managed 包み、その錠。
凍結: DLQ payload 状態機械。この領域の指摘は P1 でも残件に 1 行。新しい仕組みを提案するな。
既知残件は再審するな（CAS、getOrRefuse の walk、通し NC、webhook 接頭辞、フォルダ swallow、ラムダ未測定）。
「未検証面を掘れ」とは書いていない。範囲を広げるな。
判定: 凍結範囲の新規 P1 が無ければ CONVERGED。P2 は錠が本番メソッドを外しているときだけ。
通し NC を走らせるな。製品を編集するな。
```
