# 取込の fail-closed reads — 現行の主張・凍結・残件（正典）

作成 2026-09-14（HEAD `bfc5629db`）。ブランチ `fix/v34-fail-closed-reads` の取込側
（DLQ / IMAP IDLE / 設定読み）について、**今の振る舞いだけ**を書く。巡ごとの本文は
[`../history/fail-closed-review-rounds.md`](../history/fail-closed-review-rounds.md)（引用するな）。
custody の正典は [`p3-4-custody-transfer.md`](p3-4-custody-transfer.md)。

## 1. 何を主張し、何を主張しないか

- **主張する**: 設定・DLQ・IDLE の読みで「訊けなかった」を「無い」と書かない。
  一意の喪失は行か、書けなければメモリの数として残す。
- **主張しない**: 全読みを直した、通し negative-control 済み、DLQ 再実行が常に元バイトを
  復元する、ジョブ行の `upsertDocument` が原子的、DLQ の保存**全体**が原子的。
  DLQ の個々の書き込みは読んだ `_rev` への compare-and-swap（R1、バッチ 3）だが、
  メタデータ書き込み → 添付 PUT → 確定書き込みの 3 段を 1 つにはしない。

## 2. 凍結（CAS のためだけに限定解除、2026-09-15 バッチ 3）

- **DLQ payload 状態機械**（`hasContent` / `payloadPresenceAssumed` / `payloadDropReason` /
  `payloadWriteToken` / `sourceNeverRead` と、添付名 `payload`・2 段書き・確定書き込み）は
  **凍結のまま**。lease・自己修復・添付の再読・新しい意味の欄は禁止。
- 現行: token あり → 409・添付不使用。bytes 無し保存 → token 継承。読めない行の上の bytes 無し
  保存 → 既存 token 欄に新 UUID（R34）。
- **限定解除（R1）**: DLQ の書き込みは読んだ `_rev` に条件付ける compare-and-swap
  （`upsertDlqCas`）。そのために要る transient な bookkeeping（`storedId` / `storedRevision` /
  `storedAttachments`、永続化せず・継承せず）だけを足した。競合は `DlqWriteConflictException`
  で、保存は読み直して最大 3 回再マージ、負け続ければ「記録できなかった」。予約は競合で
  false（429 は、扉の読みから書き込みまでの窓に割り込んだ同時要求を確実に拒む答えになった。従来は内部の読み直しから書き込みまでの一瞬でしか起こりえなかった）。ジョブ行の `upsertDocument` は不変。
- **解凍していないもの**: 添付世代と token の結び付け（`upsertDlqCas` は読んだ revision の
  添付 stub を carry-forward するだけ）。これが要る指摘は引き続き残件 1 行。

## 3. 扉の許す組（新しい印を足す前に、この組を変えるかユーザーに聞け）

- **IDLE 再認可が訊けない**: この通は取り込まない。セッションは維持。
  行が書けたら `saveSourceNeverReadToDlq`。書けなければ `undurableMisses`。
  `stopIdle` しない。確定拒否（壊れた行、委譲取り消し）は止める。
- **DLQ 再実行**: token あり 409。`payloadDropReason` あり 409。
  `sourceNeverRead` の skip では削除しない。webhook 記録行
  （行自身の欄 `webhookDeliveryRecord`。接頭辞では見ない、R10）は replay しない。
- **設定読み**: 例外 / `loadFailed` は不在ではない。circuit breaker に数えない。

## 4. 残件表（再審して製品を開かない）

| ID | 内容 | 解凍の条件 | やめた |
|---|---|---|---|
| R1 | ~~`upsertDocument` は CAS ではない（lost-update class）~~ **処置済み 2026-09-15（バッチ 3、CAS のためだけの限定解除）**: `upsertDlqCas` が読んだ `_rev` に条件付け、`findBySelector` / `DlqEntryUnreadableException` が読みの bookkeeping を運ぶ。予約・確定・訂正の書き込みも同じ。錠 6 本、control AA3〜AE3。実測: 9/9 発火（AA3〜AE3 と再錨の XK2 / ZV2 / XR2 / UU2）、全ユニット 6,991 green。確認レビュー Codex / subagent とも CONVERGED（新規 P1 なし、錠の P2 なし）。製品差分は約 +210 行で Phase C の 1 ID 100 行を超える（CAS の再試行・型付き競合・読みの bookkeeping） | — | |
| R2 | ~~`getOrRefuse` は walk しない~~ **処置済み 2026-09-15（バッチ 4）**: walk は `getOrRefuse` の中ではなく、受信の署名検証・レート制限の**後**に `refuseUnlessUniquelyDefined`（`_all_docs` の行数え。2 行以上・0 行・走査未完了は typed 503）。署名前の読みは設計どおり walk しない — `dece81f7d` で取り下げた「未認証 1 リクエストで全走査」を戻さない（錠 `getOrRefuseDoesNotWalk` / `theWalkIsNotMadeForAnUnauthenticatedRequest`）。管理 API の subscribe / delete も同じ確認。残る限界: 相方の secret で署名されたイベントは 401 のまま（署名前に走査しない以上、分けられない）。錠 11 本、control AF3 / AG3 / AJ3〜AO3（AH3・AI3 は使用済みのため飛ばした） | — | |
| R3 | ~~セレクタ障害中の開示（署名不一致を 503 にする案）~~ **処置済み 2026-09-15（バッチ 5）**: 読みが「セレクタが答えたか」を `Resolution` で運び（`resolveOrRefuse`）、答えなかった窓では受信の 401（署名不一致・無効な行）と GET の 404 を、拒否した読みと**同じ状態コード・同じ本文**にする。一律拒否（窓の間すべての webhook を止める）は採らず、正しい secret の送信側は通る。ハンドシェイクの開示は従来どおり（署名より前に答えるもので対象外）。錠 11 本、control AP3〜AX3、再錨 XD / XE / AN3 / AO3 | — | |
| R4 | ~~公開 4 引数 `createDirectRelationship` の再認可なし~~ **処置済み 2026-09-15（バッチ 6）**: 入口が authorizing profile と request を受け取り、`createLinkAuthorized`（取込中のリンクと同じ核）を通る。委譲プロファイルは対象フォルダの現在の ACL に対して再確認、非委譲は対象外（過剰拒否の側も錠）。`outsideAnImport` の答え方（作られたリンクは null + WARN）は不変。`FetchSupport` は**両方とも**無ければリンクせず拒否を報告する。オーケストレータ 3 本の呼び出し側は**錠で測っていない**（orchestrator のユニットテストが無い）。**profile だけ渡して request を落とした場合は拒否されず**、コネクタ側の腕を黙って飛ばす（現在の 3 呼び出し側からは到達しない）。錠 6 本、control AY3 / AZ3 / BF3 / BG3 / BH3 | — | |
| R5 | ~~gate / execute の版 TOCTOU~~ **測って範囲を確定した 2026-09-16（バッチ 9）— 「窓は無い」ではない**: ゲートは認可した行の指紋と解決済みフォルダ ID をリクエストに載せ、`execute` は (1) プロファイル解決の直後、(2) 内容の吸い出し・重複判定・冪等記録・resync 計画を読んだ後で書き込みの直前、の 2 回、委譲を問い直す。**`CanonicalImportServiceImpl` の書き込み 9 か所を数えた**: 文書の作成・版の checkIn・関係の作成はすべて `execute` か `createLink`（自分で問い直す）の中。4 つの archetype 入口（`…Internal`）はサービスを直接呼ぶ書き込みを持たない。**ただし「入口は execute の後に書かない」は偽**で、4 入口とも `execute` が返った後に属性を書く（→ R47）。**2 回目の問い直しと書き込みの間の瞬間は閉じられない**（店にトランザクションが無い）。錠 3 本（chat / note を通す実測 2 本 + 「2 回問い直す」「入口は直接書かない」「書き込みの本数は 9 と 4」の構造 1 本）、control BQ3 / BR3 | fencing は別件 | |
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
| R23 | ~~DLQ 書き込みがセレクタの空答えを「行なし」と読み、生成 id の 2 行目を作りうる（60 巡 P3）~~ **処置済み 2026-09-15（バッチ 2）**: 新しい DLQ 行は `ingest_dlq:<dlqId>` の確定 `_id`。隠れた行への 2 度目の書き込みは 409 →「記録できなかった」に倒れる。CAS はこのバッチでは入れず、バッチ 3（R1）で入った。生成 id の旧行は未移行でその双子は残る。錠 2 本（ジョブ行は生成 id のままの対照を含む）、control ZV2。確認レビュー 2 本 CONVERGED。subagent が条件にした「削除済み行（tombstone）の上に `_rev` 無しで再作成すると 409 か」は scratch の CouchDB 3.3.3 で実測: POST / PUT とも 201（rev N+1）。再実行成功 → 行削除 → 同じ項目が再失敗、の順路は記録できる | 旧行の双子は R1 の後に | |
| R24 | ~~`upsertDocument` は競合で throw するのに、3 か所のコメントと `== null` 判定が「null を返す」前提（凍結領域、60 巡 P3）~~ **処置済み 2026-09-15（バッチ 3 の確認レビューで判定）**: `== null` 判定は `upsertDlqCasOrNull`（競合で本当に null）を通り事実になった。旧 `upsertDocument` を名指す 7 か所のコメントを書き直した（挙動変更なし） | — | |
| R25 | ~~`classifyErrorStatus` の 500 fallback に落ちる拒否: 自動解決の曖昧さ、`findExistingDocument` の `[permanent]` 重複拒否（60 巡 P3）~~ **処置済み 2026-09-16（バッチ 8）**: 重複判定の列挙が**答えなかった** → 503（再試行で読み直せる）、列挙が**不完全**（復号できない行） → 409（再試行は同じ行を読む）。錠 3 本（腕の無い失敗は 500 のままという対照を含む）。錠は製品が作ったメッセージを製品の分類器に通す（文字列を書き写さない）。control BM3 / BO3 / BP3、再錨 TO / UE。**「自動解決の曖昧さ」は残件の前提そのものが偽だった**（レビューが指摘し、呼び出し経路を自分で辿って確認）: このメッセージを作るのは `executeWithAutoResolve` だけで、その本番呼び出し元は `CloudDriveResource.tryCanonicalImport` 1 つ。そこは `classifyErrorStatus` を呼ばず自前の分岐で答える（HTTP 200 + 本文）。取込 API と DLQ 再実行の 2 つの扉はどちらも `executeWithAutoResolve` を通らない。一度足した 409 の腕は**到達不能**なので錠ごと取り下げた | — | |
| R26 | ~~`_all_docs` walk の transport 失敗が ISE 経由で 400「retry shortly」（定義 API の作成腕、60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `NemakiConfAllDocs.WalkDidNotAnswerException`（ISE の派生）で走査の未応答を型付けし、create の 3 腕が typed 503 に写す。行が読めない場合は従来どおり 400。錠 4 本、control ZH2/ZI2/ZJ2 | — | |
| R27 | ~~`DELETE /dlq/{id}` の 404 本文が「retry」、生 RuntimeException は 500（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `deleteDlqEntry` の**削除要求** (`deleteDocument`) の transport 失敗を `IngestStoreDidNotAnswerException`（503）に包む（セレクタ側の失敗はこのバッチでは未包装のまま、バッチ 1（R40）で包んだ）。404 の本文（索引が追いつくまで retry）は不在を確かめる手段が無いため残す。錠 1 本、control ZW2 | — | |
| R28 | ~~未配線腕が「無い」と答える潜在箇所: webhook の `propertyManager` null →「No access token」400、`integrationSettingsService` null で冪等性検査を飛ばす、`CheckpointManager` の「never polled」（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: webhook の `resolveToken`（未配線 → 503）、冪等性検査（未配線 → 拒否）、`CheckpointManager` の 2 つの読み（未配線 → 拒否）。錠 4 本、control ZP2/ZQ2/ZR2/ZS2 | — | |
| R29 | ~~scheduler poll: 1 プロファイルの listing 失敗（`ConnectorIndexNotReadyException`）が tick 全体を落とす（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `resolveConnectorFor` が `listByArchetype` の拒否を `Unresolved.LISTING_REFUSED`（答えではない）で返し、poll は続く。フォルダ実行・手動起動は 503。錠 3 本、control ZG2/ZX2/ZY2 | — | |
| R30 | ~~webhook 非同期 fetch が `SettingUnreadableException` を ERROR ログだけで落とす（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: 非同期 fetch の `SettingUnreadableException` を認可の腕と同じ `recordUndeliveredWebhook` で記録。錠 1 本、control ZO2 | — | |
| R31 | ~~`resolveTargetFolderId` が権限拒否も retryable=true（503「retry shortly」）にする（60 巡 P3）~~ **処置済み 2026-09-14（ユーザー指定のバッチ）**: `CmisPermissionDeniedException` は retryable=false、分類は 403（`permission denied for the importing user`）。錠 1 本、control ZT2/ZU2 | — | |
| R32 | ~~RELEASE_NOTES「中身を持つのに payload が返らない場合は 409」は assumed の腕（中身なしで再実行し、成功なら削除）を言っていない（60 巡 P3）~~ **文面を訂正済み 2026-09-14** | — | |
| R33 | ~~multipart 取込の未型 RuntimeException → 400「Invalid request」（JSON 経路は 500）（60 巡 P3）~~ **処置済み 2026-09-16（バッチ 7）**: catch の中身を**本文の解析だけ**にし、`doIngest` を外に出した。取込が投げたものは JSON 入口と同じ経路（クラスの handler で 503 / 409、それ以外は 500）。型付き 3 種の rethrow は不要になったので削除。壊れた本文・JSON の `null`・100MB 超は従来どおり 400（過剰拒否の側も錠）。**この処置が作った過剰投げ 1 件をレビューが実測で見つけた**（`null` は Jackson が投げずに null を返すので取込に渡って 500 になっていた。JSON 入口は 400）。錠 4 本 + 既存 1 本、control BI3 / BJ3 / BK3 / BL3、再錨 QR2 | — | |
| R34 | ~~**凍結領域の P1 class**: bytes 無し保存が**読めない既存行**の上に書くとき token を `null` にする — `existing == null` の 2 義（「行が無い」/「読めなかった」）のうち後者で、`hasContent` と履歴カウンタは同じ catch で「不明」扱いなのに token だけ隣に残った。読めない行が未確認 token を持てば次の bytes 無し保存で扉が開く（Phase 2 subagent）~~ **処置済み 2026-09-14（ユーザー判断）**: `rowWasUnreadable` の腕だけ、既存の `payloadWriteToken` に新 UUID を立てて既存の 409 扉に乗せた（新しい欄・lease・自己修復・添付の再読なし。「行が無い」側は null のまま）。錠 `aByteLessSaveOverAnUnreadableRowKeepsTheDoorShut`（保存 → token 非 null → 再実行 409）、control XV2。**凍結は解いていない** | 解凍条件は不変（CAS な upsert + 添付世代が token に結び付く） | |
| R35 | token 継承で、並行する bytes 無し保存 B の行が A と同じ token を持ち、A の確定書き込みの所有権検査（WF2）が B の行を自分の行と見る。結果は凍結前と同じ合成で退行ではないが、検査の保証は「payload 付きの別保存」に狭まった（Phase 2 subagent） | CAS 適用後も残る（2026-09-15 の確認レビューで両者確認: 所有権検査は token 等値で、B は A の token を継承する）。閉じるには token 継承の変更 = 凍結領域 | |
| R36 | ~~`deleteDlqEntry` は store が確認した削除だけを数えるようになったが、同じ dlqId の行が 2 行以上あり一方だけ未確認のとき `removed > 0` で success / resolved と答える（Codex 3 回目のレビュー。R23 の重複行が前提）。同じ領域の 3 度目の指摘なので止めた~~ **処置済み 2026-09-14（ユーザー判断）**: `deleteDlqEntry` が `DlqDeletion(confirmed, unconfirmed)` を返し、未確認が残れば `DELETE` は 503、再実行の後片付けは `*-entry-kept`。双子行のマージも一意制約も足していない（R23 / R1 のまま）。錠 3 本、control XX2 / XY2（+ XU2 / VG2 / VN2 の宣言追加）。**同じ領域の 4 度目は止まる** | — | |
| R37 | ~~再実行の後片付け `deleteDlqEntry` で `findRawDocs` が「store が答えなかった」（R18 の型付き拒否）を投げると、扉の `catch (Exception)` に落ちて **500 "Retry failed"** になる。取込は成功済みで行は残り、次の再実行は冪等に resolved になるので損失はないが、応答文が偽（R34/R36 確認レビューの subagent P3。範囲外）~~ **処置済み 2026-09-15（Phase C-1）**: 後片付けの typed 拒否は `*-entry-kept` + `entryKeptNote`（取込は成功済み、行は残る）。錠 1 本、control AH3 | — | |
| R38 | webhook 受信の GET 握手で「コネクタ読みが答えなかった → 503」は、腕の catch とクラスの `@ExceptionHandler` の**二重**の保護で、片方を外しても錠が緑のまま（通しスイープで WX が不発）。1 錨のサボタージュでは測れないので WX は退役。XE（`get()` に戻す）は独立に発火 | 二重保護のどちらかを外す製品変更（今は開かない）か、複数錨のサボタージュ | |
| R39 | ~~IDLE の per-message ラムダの `catch (Exception)`（`fetchMessage` の I/O 失敗、`executeMailImport` 自体の例外など）はログだけで、DLQ にも `undurableMisses` にも載らない。IMAP は再配信しない。R22 は**結果**として返る拒否だけを記録した（並行レビュー 2026-09-15 の隣接指摘）~~ **処置済み 2026-09-15（バッチ 1）**: IDLE ラムダの `catch (Exception)` が `recordIdleFailure` で記録する — fetch 前は never-read 記録行、fetch 後はメタデータのみの行、書けなければ `undurableMisses`。取込内部の失敗は結果として返り既に記録されるので二重には書かない。錠 3 本、control BC3/BD3/BE3 | — | |
| R40 | ~~`IngestJobService.findRawDocs` の `postFind` 自体は包まれておらず、`deleteDlqEntry` のセレクタ側 transport 失敗と `getConfClient()` の ISE は生のまま — DELETE は 500、再実行の後片付けは 500 "Retry failed"（R27/R37 の兄弟。損失も偽の不在もない。Phase D subagent P3）~~ **処置済み 2026-09-15（バッチ 1）**: `findRawDocs` の `postFind` と `getConfClient()` の未配線を `IngestStoreDidNotAnswerException`（503 / 後片付けは `*-entry-kept`）に。錠 2 本、control BA3/BB3 | — | |
| R41 | R10 の移行面: 印の無い接頭辞だけの記録行（このブランチの中間ビルド `55f35915d` 以降が書いたもの。リリース版には接頭辞自体が無い）は扉を通り、`sourceNeverRead` は dispatch 後にしか効かないので空の webhook 取込が走りうる。開発 DB だけの話で、`sourceObjectType=webhook_event` かつ接頭辞付きの行を消せば済む（Phase D subagent P3） | 開発 DB の掃除。移行は書かない | |
| R42 | ~~凍結領域（バッチ 2 の subagent P3、記録のみ）: `reserveDlqRetry` は扉の `getDlqEntry` と upsert の間で索引が行を隠すと、確定 id の 409 を「他の再実行が保持」(429) と読む。差分前は同じ窓で retryCount+1 の双子を作っていたので安全側への変化だが、文言は事実でない~~ **処置済み 2026-09-15（バッチ 3）**: 予約の内部の読み直しが消え、409 は「扉の読み以後に行が変わった」（他の再実行か並行する保存）だけを意味するようになった。文言はそのまま | — | |
| R43 | 凍結領域（バッチ 3 の subagent、記録のみ）: 確定書き込みの fallback 腕（再読が訊けず `current = dlq`）は、添付 PUT で rev が上がった後に PUT 前の rev に条件付けるので必ず負ける（死んだ腕）。落ち先は「確定書き込みが landed しなかった」状態（assumed + token → 再実行 409）で、差分前はこの腕が landed していた。fail-closed 方向だが、届いた payload の再実行を拒む過剰拒否 | 凍結解除時（再読の rev ではなく PUT 後の rev に条件付ける） | |
| R44 | 取込後のリンクの認可は、**フェッチ開始時のプロファイル行**に対して行う（`knownProfile` を渡すので行を読み直さない）。読み直すのはフォルダの ACL とコネクタ行だけで、`isDelegated` / `targetFolderId` は開始時の値。取込中の一部の呼び出し側（`null, request`）は行も読み直す（バッチ 6 の subagent、記録のみ） （バッチ 10 の追記: 「読み直すコストが高いので据え置き」という理由は、同バッチがより高頻度の装飾経路で同じコストを 5 か所ぶん受け入れたことで弱くなった。また向きが揃っていない — 装飾は**現在の行**で認可し、リンクは**フェッチ開始時の行**で認可する） | 読み直すなら 1 リンクにつき設定 DB の走査 1 回 | |
| R45 | 取込後のリンクで、`targetFolderPath` 指定の委譲プロファイルは新たに `resolveTargetFolderId` のパス解決を通る。一過性の読み失敗が拒否 → fetch のエラー → circuit breaker の前進になりうる。方向は取込中と同じだが、**過剰拒否の側の錠は非委譲の腕しか押さえていない**（バッチ 6 の subagent、記録のみ） （バッチ 10 の追記: 装飾経路が `resolveTargetFolderId` の新しい呼び出し元になった。ただし一過性の読み失敗は警告どまりで fetch エラーにはならない。パス解決の腕は装飾経路でも無錠のまま） | 過剰拒否の錠 | |
| R46 | ~~multipart 入口で `content.getInputStream()` の IOException が 400「Invalid request」~~ **処置済み 2026-09-17**: 開く読みだけを自分の try で囲み 503 に。他の 3 つの 400（解析できない本文・JSON の `null`・100MB 超）は経路も文言も不変。到達性は web.xml の `file-size-threshold` 1MB（超えた部分はディスク上のファイル）と `getInputStream()` の検査例外で繋いだ。錠 2 本（503 になる／開ける部分は開いたストリームごと取込に届く）、control BY3 / BZ3 | — | 確認レビュー 2 名とも CONVERGED |
| R47 | ~~4 つの archetype 入口はすべて `execute` が返った後に属性を書く~~ **処置済み 2026-09-16（バッチ 10）**: 装飾 5 か所（mail / note ページ / note 添付 / record / chat。chat の 2 つは同じパスなので問い直し 1 回で覆う）の手前で `refuseDecorationIfNoLongerAuthorized` が委譲を問い直す。拒否は**警告**で、取込は失敗にしない（有効だった認可で取り込まれたオブジェクトをDLQ に入れて breaker を進めないため。`createLink` の先例と同じ）。コネクタ行が読めなかった場合も拒否する（`get()` は不在と読み失敗の両方に null を返すので、渡すと委譲の半分を黙って飛ばす。`createLinkAuthorized` の先例と同じ腕。**初版はその先例から解決だけを写して拒否を写しておらず、2 本のレビューが P1 として出した**）。**コスト**（装飾 1 回 = 取込済み項目 1 件・ポーリング 1 回あたり）: プロファイル行の索引不要読み 1 回（設定 DB の走査、R44 と同じ family）、委譲プロファイルならさらにコネクタ行の読み 1 回と `cmis:all` 評価 1 回（フォルダ読み + グループ展開）。非委譲はプロファイル行の読みだけ（コネクタ読みは委譲判定の後ろに置いた）。錠 4 本（窓に正確に着地させる実測、過剰拒否の対照、コネクタ不達の拒否、問い直しと metadata service 使用箇所の本数）、control BS3 / BT3 / BU3 / BV3 / BW3、再錨 VF / BS3、巻き添えの宣言を 6 control に補完。**測定に穴が残る（→ R48）**: 装飾を metadata service を通さず直接書く扉は本数の錠が動かない。2 巡目のレビューで出た P1 で、ユーザー指示「同じ領域で 2 度目の P1 が出たら残件に戻して止まる」によりここで止めた | 直接書きの扉を数える（R48） | |
| R48 | R47 の本数の錠は「問い直しの出現数」と「metadata service の使用箇所数」を数えるので、**装飾を `contentService.update` で直接書く扉**（chat の capture window と同じ形）が問い直し無しで増えても動かない。5 つの装飾のうち 1 つが実際にその形（`applyCaptureWindow`）（バッチ 10 の 2 巡目 subagent P1。製品ではなく測定の穴）。**バッチ 11 で 3 つ目の数（`contentService.update(` = 5）を足そうとしたが、錠のコメントの過大主張が 2 巡続けて HOLD になったため、指示（`v34-fail-closed-resume.md` §3「過大主張が 2 度目なら 3 つ目の数を足さず残件のまま進め」）に従って取り下げた。取り下げたのは数と control BX3 で、製品は最初から触っていない。2 巡で判明した事実: この 3 形以外に `checkOut` / `deleteObject` はどの錠も数えていない、既存の `checkIn` の properties に装飾を載せる形は R5 の書き込み本数の錠でも見えない** | 3 つ目の数を足すなら、「捕まえないもの」を先に列挙してから | |
| R49 | 取込入口の 503 は例外の `getMessage()` をそのまま本文に入れる。R46 の腕（一時ファイルのパスが入りうる）と、既存の `definitionRowsCouldNotBeRead`（`ExternalIngestController` のクラス handler）の両方。どちらも委譲ゲートより手前で返るので、認証済みだが未認可の利用者にも届く（バッチ 12 の subagent P3。開示の方式はR46 が作ったものではない。パスが入る点は未実測の推論） | 2 か所まとめて判定 | |
| D1 | token 付き行の**添付前検査**（57 巡）、添付を landed の証拠に読む**自己修復**（58 巡）、15 分で通常経路に落とす **lease**（59 巡） | 凍結解除まで再導入しない | やめた（いずれも古い bytes を新しいメタデータで再生する同じ class に落ちた） |

## 5. 測定

- コントロール 694（2026-09-17 時点。R46 の BY3 / BZ3 を含む。内訳: 通し前 621 → 不発の WX を退役して 620 → Phase C で
  25 新設 = 645 → バッチ 1〜3 で 11 新設 = 656 → バッチ 4 で 8 新設 = 664 → バッチ 5〜10 で 28 新設）。**通し negative-control は
  2 回完走**。1 回目 2026-09-14〜15（621 本、約 15 時間、exit 1）: 620 発火、不発 1（WX。腕とクラス
  `@ExceptionHandler` の二重保護で 1 錨では測れない → 退役、R38）、宣言漏れ 68 本、錨外れ 0、製品欠陥 0。
  **2 回目 2026-09-15〜16（664 本、15 時間 23 分、exit 1）: 663 発火、不発 1（SN2 — 宣言していた
  巻き添えの錠 3 本が CAS（R1）で外れ、本体の錠 1 本だけが落ちた。3 本は SI2 / UH2 / UJ2 / XV2 が
  同じ通しで測っている）、宣言漏れ 9 本、錨外れ 0、「復元後に緑でない」0、製品欠陥 0。**
  **3 回目 2026-09-16〜17（692 本、17 時間 18 分、exit 1）: 692 発火、不発 0、宣言漏れ 6 control
  （のべ 7 本・異なり 4 本。いずれも R2 / R3 で足した錠で、2 回目の通しより後に生まれたため
  宣言が測られる機会が一度もなかった）、錨外れ 0、「復元後に緑でない」0、製品欠陥 0。**
  いずれも宣言はログ末尾の節から機械的に補完し、ID 指定で再実測（68/68、10/10、6/6）。
  3 回目は 1 度 254 本目で落ちている: RD の細工が `read()` の戻り値（R3 で `Resolution` に
  変更）とかみ合わずコンパイルできなくなっていた。**錨の事前検査は錨の一致しか見ないので、
  この形は通り抜ける。** 前回完走以降に変わった製品ファイルを狙う control 207 本を
  `--compile-check` に掛け、壊れていたのは RD だけと確かめてから通しをやり直した。
  以後、通しは毎バッチでは走らせない。事前検査は exit code と `== summary` の
  存在で確認（出力が無いことを clean と読むな）。**runner は錠が自分の assertion で落ちたときだけ
  発火と認める** — 例外が素通りする欠陥の錠は `assertDoesNotThrow` で包む。
- 全ユニット 7,002 本 green（バッチ 4 適用時点。Phase C 適用時点は 6,978、`bfc5629db` 時点は 6,927。
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
