# Capture outbox — 取込の証拠を落とさないための境界 (P1-1(a))

**状態**: 設計 (2026-08-20、外部レビュー 1 巡を反映して全面改稿)。実装前。
**対象**: 3.4 / P1-1(a)。[`authenticity-roadmap.md`](authenticity-roadmap.md) の P1-1 行。

---

## 1. 何が壊れているか

取込は**文書をコミットしてから証拠を発行する**。発行は失敗しても取込は成功として返る。

```
CanonicalImportServiceImpl:1560   objectService.createDocument(...)   ← ここで文書は永続化される
      … (4〜6 回の CouchDB 書込。attachment / version series / content / change event / aspect)
CanonicalImportServiceImpl:1594   ingestLineageEmitter.emitLineageEvent(...)  ← ここで初めて証拠
```

間に**トランザクションも中間状態も無い**。PR #506 で「失敗したこと」は呼び出し元に届くように
なったが (`IngestLineageEmitter:117` のコメントがこの設計を待っている)、**届いた時にはもう
文書は在る**。

### 既にある outbox は別の区間を守っている

`nemaki_lineage` の journal には完全な outbox がある — `PENDING → PROJECTING → PUBLISHED /
FAILED / DISCARDED`、leader gate、`_rev` CAS による claim、dead letter、replay。
**ただしそれが守るのは journal → Atlas/Purview の配送**であって、
**content commit → journal write の区間ではない**。落ちているのはそこ 1 区間だけである。

---

## 2. 使える原子性は「単一文書の書込」だけ

事実 (すべてコードで確認):

- **content と journal は別データベース** — content は `repositoryId` 名の DB、journal は
  `nemaki_lineage`。**CouchDB はデータベースを跨いだトランザクションを持たない。**
- **`_bulk_docs` は使えない** — 本コードベースでは削除にしか使っておらず、`all_or_nothing` は
  **CouchDB 2.0 で廃止**、SDK (`cloudant:0.10.12`) の `BulkDocs$Builder` にも無い。稼働は 3.3.3。
- 公開されている書込は**単一文書の `create` / `update` のみ**。

→ **原子性は「1 文書 1 PUT」からしか得られない。**

---

## 3. 設計 — 先行 intent で境界を作る

### 3.1 「後から判定する」は、この PR では諦める

先行 intent は「**コミットの前に必ず intent がある**」を保証する。
しかし**クラッシュ後に「その intent は結局コミットされたのか」は判定できない。**

判定を「source identity で文書を探す」で代用しようとしたのが最初の誤りだった。
**存在は、その試行が成功した証拠にならない**:
  - `update_metadata_only` / `version_up_on_content_change` — **文書は intent の前から在る**。
    更新が失敗しても探せば見つかる
  - `always_version_up` — checkout/checkin の途中で落ちても旧版か PWC が残る
  - 再試行 — 先の試行が作る前に落ち、後の試行が作ると、**古い intent まで新しい文書を見て
    「成功した」と読んでしまう**
  - 同時取込 — 同じ source identity の複数 intent が同じ 1 件に収束する
  - `replace` — 削除後・再作成前の失敗と、再試行による再作成を区別できない
  - 逆方向も壊れる — 新規作成 (`:1560`) と source identity の付与 (`:1567`) は**別の書込**で、
    間で落ちると**文書は在るのに source-id で引けない**

→ 判定には**試行に固有の刻印を、その試行が永続化する書込そのものに載せる**しかない。
**それは本 PR の範囲外にする** (理由は §3.3)。本 PR は**判定できないものを
`UNRESOLVED` として正直に残す**。

### 3.2 3 つのステップ

```
① intent を nemaki_lineage に書く (captureState = CAPTURE_INTENT)。
     **`LineageEmitter` 経由では書かない** — `JournaledLineageEmitter` は契約として
     fail-open (「The parent business operation is never blocked」) で、spool に
     逃がす経路もある。**`LineageJournalStore` を直接呼び、例外は伝播させる**
     ↓ 失敗したら取込を中止する。文書はまだ 1 つも作られていない (fail-closed)
② 業務操作を行う (既存のコミット群。ここは変えない)
     ↓
③ 業務操作が全部終わり、変更操作がすべて成功してから、同じ intent 行を完成させる
     (captureState = CAPTURED、objectId・content 状態・snapshot・sequence を付ける)
```

**得られる保証は 1 つだけ** (§7 の「主張しないこと」もこの 1 点を基準に読む):

> **実効モードが `journaled` の構成で**、canonical な 5 つの公開入口
> (`execute` / `executeMailImport` / `executeNoteImport` / `executeBusinessRecordImport` /
> `executeChatContextImport`、および**変更前に `execute` へ委譲するだけの
> `executeWithAutoResolve`** — クラウド取込の canonical 経路はここから入る) が
> CMIS 文書を変更するときは、
> **必ずそれに先立つ耐久的な intent がある。**
> (`disabled` / `direct` では働かない — この限定は保証文と同じ場所に置く)

**「取込経路」全部ではない。** `CloudDriveResource` は connector / profile が無い構成では
canonical pipeline を使わず **legacy fallback で直接 `createDocument` / `checkIn` する**
(`CloudDriveResource:892,923,993`、fallback は `:1268`)。**この経路は本 PR の保証の外**で、
従来どおり `emitSafely` のままである。

**示さないこと**: intent が在ることは、**その取込が完了した証拠ではない**。
完了は ③ が書けたときにだけ言える。③ に到達しなかったものは `UNRESOLVED` として残り、
**それが在るのか無いのかは本 PR では判定しない**。

### 3.3 刻印は本 PR の対象外 — なぜ切ったか

当初は「intent id を content 文書に同じ PUT で刻む」ところまでを本 PR に入れていた。
**4 巡の外部レビューで、それが別の仕事だと分かったので切り出す。**

刻印が要るのは「**その intent は結局コミットされたのか**」を**後から判定する**ためで、
境界そのものには要らない。そして判定を成り立たせるには、次が全部要る:

- `createDocument` / `checkIn` / `update` それぞれへの配線 (CMIS 公開 signature は避けて内部 API へ)
- **CMIS 由来の `ExtensionsData` を権威にしない**内部型の信頼境界
- **複製規則** — `createDocumentFromSource`・コピー・クライアント由来の `checkIn` で
  刻印が別 identity へ運ばれると偽造できる
- **単数フィールドでは足りない** — 再試行と同時取込で上書きされ、先の成功が引けなくなる。
  追記リストと **CAS による併合**が要る
- **引ける索引** — Mango の `$elemMatch` が索引を使う保証は無い。要素ごとに 1 行を出す
  map view が要る (でなければ intent 1 件ごとに全走査)
- **所有範囲が実際にはもっと広い** — Slack / Teams / Mattermost の orchestrator は
  `executeChatContextImport` が**返った後に** relationship を作る
- **完了の述語が「PUT を試みた」では成立しない** — 失敗した更新が warning に落ちて
  `CAPTURED` になりうる。「**変更操作が全部成功した**」でなければならない
- **DELETE が先に走る経路** (`replace` / `replace_relationships_on_resync`) は刻める先が無い

これは **P1-1(e) 「失敗時の隔離 + 再構築可能性」** の中身であって、境界の話ではない。
本 PR は境界だけを、正しく閉じる。

**帰結として、本 PR の照合は「解決」しない。** 完了しなかった intent は
**`UNRESOLVED` のまま運用者に見せる**。自動で `ABANDONED` にも `CAPTURED` にもしない。

---

## 4. intent が覆う範囲は「業務操作」であって `execute()` ではない

mail / note / record / chat の入口は **`execute()` が返った後にも同じ文書を更新する**
(メタデータ付与・capture window・`chatCapturedAt`・relationship)。さらに Slack / Teams /
Mattermost の orchestrator は **`executeChatContextImport` が返った後に** relationship を作る
(`SlackFetchOrchestrator:111` ほか)。`execute()` の中だけで完成させると、
**snapshot が最終状態を表さない**。

したがって:

| 規則 | |
|---|---|
| 1 | **intent は「最初の変更操作の直前」で開き、その scope の所有者が完成させる** (root scope は公開入口、child scope は子操作)。** 入口の直後ではない — 空/擬似ファイルの skip・dedupe/idempotency skip・`files_only` で添付が無い/全部 skip、といった**何も変更せずに正常終了する経路**が実在し、そこで intent を開くと**行き先の無い行**ができる (`CAPTURED` にすると objectId が無く完成できず、放置すると 15 分後に `UNRESOLVED` になるが、実行中の主体は「何も変更しなかった」と分かっているので「判定不能」は嘘になる)。**dry run はこの一覧から外した** — `execute` では確かに何も変更しないが、**mail / note / record / chat の 4 入口では dry run でも実際に書き込む** (既存のバグ。`ExternalIngestResult.dryRun` は `isSuccess()==true` かつ `skipped==false` で、wrapper は誰も `dryRun()` を見ず、子要求にも伝播しない)。**このバグを直すまで dry run を「変更しない経路」の例に使えない** — 別タスクとして起票済み。**変更しないなら intent も作らない。** dedupe skip の後に wrapper がメタデータを更新する経路では、**その wrapper の変更の直前で開く** |
| 2 | **受け渡すのは intent ではなく `CaptureMutationScope`** — wrapper が**未 open のまま**生成し、内部 overload で `execute` に渡す。`execute` は変更の直前で `scope.ensureIntentOpened()` を呼ぶ。**これで「wrapper は `execute` を呼ぶまで変更が起きるか分からない」問題が解ける** (入口で開けば no-op 経路に行き場の無い行ができ、`execute` に所有させれば wrapper の後処理より前に完成してしまう) |
| 3 | **完成させるのは scope の所有者だけ** — root scope なら公開入口、child scope なら子操作 (規則 4)。内側の `execute` は open するが complete しない |
| 4 | **子操作 (添付・生 `.eml`) は 1 件ずつ自分の scope を持ち、その所有者は子操作自身**である。`executeNoteAttachment` が例 — 内部 `execute` に自分の scope を渡し、**その後の note メタデータ更新も同じ scope で行い、最後に自分で complete する**。親の scope を渡すと複数添付が 1 つの intent を共有して規則 4 に反し、子の公開 `execute` に自己完了させると**直後のメタデータ更新が intent の外**に出る |
| 5 | **子の処理の一部として作る relationship は、すべてその子の scope に属する** — mail の添付、生 `.eml`、**note の `files_and_body` で本文とを結ぶ relationship** (`CanonicalImportServiceImpl:475,490`。今は子の `execute()` が返った後に作られる) を含む。失敗の帰属を一意にする: 添付の relationship 作成が失敗したら、失敗するのは**その添付の intent** であって親ではない |
| 6 | **内側の `execute` が dedupe skip を返しても終端にしない** — wrapper は skip の後も既存文書を更新する |
| 7 | **orchestrator が入口の後に relationship を作る経路は、本 PR では intent の外に出る。** そう明記し、[`interpares-mapping.md`](interpares-mapping.md) の該当行に残す。閉じるのは刻印と同じ P1-1(e) |

---

## 5. 状態機械

`nemaki_lineage` の 1 文書。**intent と証拠は同じ文書**にする — ③ が単一文書 update に
なるので原子的で、「intent は在るが証拠が別の場所で失敗した」という三つ目の状態を作らない。

| captureState | 意味 | 誰が書くか |
|---|---|---|
| `CAPTURE_INTENT` | 「これから取り込む」。文書はまだ無いか、コミット中 | 取込 (①) |
| `CAPTURED` | **業務操作が最後まで終わり、変更操作がすべて成功し**、証拠が揃った | **取込 (③) だけ** |
| `UNRESOLVED` | どちらとも判定できない。**期限切れの intent はここへ落ちる** | 走査 |

**3 状態しか持たない。** `ABANDONED` も `COMMIT_OBSERVED` も作らない —
どちらも「判定」を必要とし、その判定には刻印が要るからである (§3.3)。

> **`CAPTURED` の述語は「追跡対象の変更操作がすべて成功したこと」**であって「PUT を試みたこと」
> ではない。失敗した更新が warning に落ちる経路がある以上、「試みた」では偽の `CAPTURED` を作る。

#### 5.0 変更の観測 — 現状のままでは述語を実装できない

現行の `warnings` / 戻り値だけでは足りない。**失敗を呼び出し元に返さない変更が実在する**:

- `replace` の削除失敗は**ログだけで作成に進む** (`CanonicalImportServiceImpl:1467`)
- relationship の削除は query / delete の失敗を**内部で飲み、戻り値も無い** (`:1161`)
- ACL の変更失敗も**内部で飲む** (`:1032`)
- `ContentService.update` は **null を返さない** — `writeChangeEvent` が無条件に
  `content.getId()` を呼ぶので、null なら**返る前に NPE で落ちる**。
  **「戻り値の null を見る」形で tracker を実装すると永遠に発火しない** (レビュー指摘)。
  実際の fail-open は 1 段外側 — `IngestMetadataService:52-55/89-92/123-126` と
  `applyChatCapturedAt:890-892` が `catch (Exception)` して warning 文字列に落とす所である。
  **tracker は例外を捕まえる側に置く**

したがって本 PR は、**型付きの mutation tracker** を入れる。**刻印とは独立で、`CAPTURED` の
遷移に必須**なので P1-1(e) には送れない。

**追跡対象の allowlist** (これ以外は `CAPTURED` の判定に入れない):

| 追跡する | 追跡しない (理由) |
|---|---|
| 文書の作成 (`createDocument`) | idempotency の記録 (証拠ではなく重複防止) |
| **`checkOut`** — PWC を作り version series を変える**それ自体が CMIS の変更**であり、version-up 経路では**これが最初の変更になる**。追跡しないと intent が checkout の**後**に開き、保証が破れる | 監査ログ (別系統。落ちても取込の成否ではない) |
| checkIn による新版の作成 | 変更イベント (CMIS の派生物) |
| content / aspect の更新 (`update`) | Solr 索引・キャッシュ (派生物。再構築できる) |
| ACL の変更 | |
| relationship の作成・削除 | |
| `replace` の削除 | |

各操作は `MutationOutcome { SUCCEEDED, FAILED, UNKNOWN }` を tracker に**明示的に記録する**。
**`UNKNOWN` が 1 つでもあれば `CAPTURED` にしない** — 「分からない」を成功に潰さない。

### 5.0.1 既存の `emitLineageEvent` はどうなるか

**設計がここを書いていなかった** (レビュー指摘)。放置すると実装者がどちらにも倒せる。

**採る**: ③ が**既存の発行を置き換える** (`IngestLineageEvent:258-260` のコメント
「this method disappears when that lands」が期待している側)。両方残すと、同じ
`eventKey` の `lineage_event` が 1 操作につき 2 行でき、projection と件数が二重になる。

**その帰結を隠さない** — 置き換えは 2 つの挙動変更を伴う:

1. **部分的に失敗した取込は、イベントを 1 つも残さなくなる。** ③ は追跡対象の変更が
   すべて成功したときにしか書かないため。**今日は**イベントを出したうえで警告も返している。
   **問題があった取込ほど証拠が減る**ので、`UNRESOLVED` 行と一覧 (§6) が
   その穴を埋める設計になっていることを、実装で必ず確かめる
2. **dedupe skip が新たにイベントを出すようになる。** 今日は `:1465` で早期 return して
   何も出さないが、本設計では wrapper の後処理が scope を開いて完成させる (規則 6 / AC 9)。
   **これらが Atlas / Purview へ配送され始める**

### 5.1 許される遷移

| from → to | 誰が | 可否 |
|---|---|---|
| `CAPTURE_INTENT` → `CAPTURED` | 取込 (③) | ○ |
| `CAPTURE_INTENT` → `UNRESOLVED` | 走査 (期限切れ) | ○ |
| **`UNRESOLVED` → `CAPTURED`** | **取込 (③) のみ** | ○ — 遅れて完走した取込は完成できる (**retention による削除より前に限る**) |
| `UNRESOLVED` → `CAPTURED` | 走査 | **×** |
| `CAPTURED` → 何か | 誰も | **×** — 終端 |
| `UNRESOLVED` → **削除** | retention (有限の保持期間を設定した場合のみ) | ○ — 起点は `unresolvedAtMs` |
| `CAPTURE_INTENT` → **削除** | 誰も | **×** — 必ず先に sweeper が `UNRESOLVED` にする |

**書込はすべて `_rev` CAS。** 衝突したら**読み直して遷移可否を再判定**し、不可なら何もしない。

### 保存形式

- **document type は専用**: `type = "lineage_capture_intent"`
- **`_id` は試行ごとに一意**にする。**source identity から導出しない** — 同じ source を
  2 つのリポジトリへ取り込む場合や、再試行で衝突する。**開いた時点で最終的な
  event id を採番し、その `_id` のまま完成まで通す** (完成時に別文書へ移し替えない)
- **`_id` の形は既存の慣行に合わせる**: `"lineage:" + eventId` (`CouchLineageEvent:27,39`)。
  独自の接頭辞を付けると、**完成して `lineage_event` になった後に
  `journalDocumentId(recordId)` で引けなくなる** — 完成後の行は既存の照会経路から
  普通に見えなければならない。
- **`_id` で引く既存の v1 経路に型ガードを足す。** view はすべて型で守られているが、
  **`_id` で直接引く経路は `lineage_event_v2` しか弾かない**:
  `findByRecordId` (型判定なし)、`updatePublishStatus` (`:756` で v2 のみ)、
  `discardEvent` (`:937` で同)、`getRetryCount` (判定なし)。
  そのため **`GET /events/{recordId}` は intent 行を普通のイベントとして表示し、
  管理画面の retry / discard がその行に `publishStatusByTarget` を書き込めてしまう** —
  §5.1 の遷移表に無い遷移である。**`type == "lineage_capture_intent"` を弾くガードを
  4 箇所に足し、AC を付ける** (本 PR の範囲)。
- **`eventKey` の重複判定と衝突させない。** `append` は `eventKeyExists` を先に見る
  (`CouchLineageJournalStore:515`)。**完成は append ではなく同一文書の update** なので
  この判定は通らないが、**実装で誤って append 経路に乗せると自分自身を重複とみなす**。
  完成が update であることをテストで固定する
- **フィールド名は `captureState`** (`state` は v2 の sequencing state が使用中)
- **`publishStatusByTarget` は intent の間は持たせない。** 既存 v1 projection view は
  `type == lineage_event && publishStatusByTarget` で選ぶ (`CouchLineageJournalStore:241`) ので、
  intent は構造上拾われない
- ③ の完成時に行を `lineage_event`(v1) として成立させ、`publishStatusByTarget` と
  `sequenceNumber` を付ける。**sequence は完成時にだけ振る**

---

## 6. 期限切れの走査と可視化

- `CAPTURE_INTENT` のまま既定 15 分を超えた行を **`UNRESOLVED`** にする。**それだけ**。
  文書を探しに行かない (探しても判定できないため — §3.1)
- **`UNRESOLVED` を運用者が見る手段を用意する。** 既存のイベント一覧は
  `type == lineage_event` しか出さないので、**専用の一覧が要る**:
  - **専用 view の述語**: `type == "lineage_capture_intent" && captureState == "UNRESOLVED"`
    (Mango ではなく map view。`$elemMatch` 等の索引が効かない形は使わない)
  - **専用の map view** (`intentOpenedAtMs` を key に emit)。並び順は `intentOpenedAtMs` の降順
  - pagination は既存の journal 一覧と同じ `limit` / `skip`、件数も返す
  - **admin 限定** (既存の `requireAdminOrForbidden` と同じ扱い)
- **intent 行の保持は既存の purge では扱えない。** 現行 v1 retention view は
  `type === 'lineage_event'` だけを選ぶ (`CouchLineageJournalStore:259`) ので、
  **`lineage_capture_intent` のままの行は永久に purge されない**。専用の方針を本 PR で決める:
  - **`CAPTURE_INTENT` を直接 purge しない。** 必ず先に sweeper が `UNRESOLVED` にする
    (直接消すと「開いたまま落ちた」証拠が消える)
  - **`UNRESOLVED` は既定で purge しない (無期限保持)。** 設定
    `lineage.capture-intent.retention.days` の既定は `0` = 無期限。**journal の 90 日とは
    別の値**にする — 未解決の証拠を配送ログと同じ期限で消さない
  - **保持期間の起点は `unresolvedAtMs`** (sweeper が `UNRESOLVED` にした時刻) であって
    `intentOpenedAtMs` ではない。起点を開始時刻にすると、**長時間走った取込が
    `UNRESOLVED` になった瞬間に purge 対象になる**
  - retention view は **`captureState == "UNRESOLVED"` の行だけ**を `unresolvedAtMs` を
    key に emit する。`CAPTURE_INTENT` は**この view に出ない** (だから直接消えない)
  - purge も `_rev` CAS。衝突したら読み直して再判定

## 6.5 リポジトリが増えても壊れないか

`bedroom` 以外のリポジトリが足されたときに矛盾しないことを、実物で確認した。

### 確認した事実

| | |
|---|---|
| **モードはリポジトリ単位** | `lineage.mode.override.{repositoryId}` が在る (`LineageConfig:270,286`)。**repo A が `journaled`、repo B が `disabled` はあり得る** |
| **他の設定は instance-wide** | `lineage.targets` / `lineage.retention.days` / `lineage.purge.cron` 等はリポジトリ単位の override を**持たない** (`LineageConfig:36-40`) |
| **journal DB は 1 つを共有** | `nemaki_lineage` (`LineageStoreDocuments:36`)。**リポジトリごとの DB ではない**。行が `repositoryId` を持って区別する |
| **purge は cross-repository** | `purgeOlderThan(Instant)` は `repositoryId` を取らず、`by_occurred_at` view は「cross-repository time ordering for findAll and purge」と自称する (`CouchLineageJournalStore:811,257`) |
| **sequence は既にリポジトリ単位** | `lineage_seq:{repositoryId}` (`LineageJournalStore:34`) |
| **admin は「デフォルトリポジトリの admin」** | `isAdmin()` 自身に repositoryId は無いが、その boolean を書く `AuthenticationFilter:378` は `principalService.getAdmins(repositoryId)` で判定し、`/v1/admin/lineage-journal` は `repoScopedAdmin` に該当しないので **必ず `getDefaultRepositoryId()`** に束縛される (`:456,:468`)。**repo B だけの管理者は B の未解決行を見られない**。`/events` は `repositoryId` を任意パラメータとして受ける (`:61`) |

### 設計への帰結

1. **保証はリポジトリ単位で評価する。** §3.2 の「実効モードが `journaled`」は
   **`getModeForRepository(repositoryId)`** で判定する。**global 設定を読まない。**
   `disabled` / `direct` のリポジトリへの取込は**今日とまったく同じ挙動**で、
   **fail-closed にもならない**。
2. **モードは open 時に 1 回だけ決める。** scope が開いた後に管理者がそのリポジトリを
   `disabled` に切り替えても、**③ は完成させる**。完成時に読み直すと、
   **設定変更が、正当に開いた intent を孤児にする**。
3. **intent 行は `repositoryId` を持つ。view は用途ごとに分ける** — 「全 view の鍵に
   `repositoryId` を含める」は誤り: 鍵の**先頭**を `repositoryId` にすると絞り込みは
   効くが**リポジトリ横断の時系列**が引けず、時刻を先頭にすると逆になる。**4 本用意する**
   (末尾の `intentId` は時刻が衝突したときの決定的な順序のため):

   | 用途 | 述語 | key |
   |---|---|---|
   | 横断の未解決一覧 | `type == "lineage_capture_intent" && captureState == "UNRESOLVED"` | `[intentOpenedAtMs, repositoryId, intentId]` |
   | リポジトリで絞る一覧 | 同上 | `[repositoryId, intentOpenedAtMs, intentId]` |
   | 期限切れ intent の sweep | `type == "lineage_capture_intent" && captureState == "CAPTURE_INTENT"` | `[intentOpenedAtMs, repositoryId, intentId]` |
   | `UNRESOLVED` の retention | `type == "lineage_capture_intent" && captureState == "UNRESOLVED"` | `[unresolvedAtMs, repositoryId, intentId]` |

   **鍵が同じでも述語が違う view は別物**である (1 番目と 3 番目)。まとめて 1 本にすると、
   sweep が未解決の行まで拾うか、一覧が未 sweep の行まで見せるかのどちらかになる。

   一覧は既存の `/events` と同じ形 — **admin 判定は「デフォルトリポジトリの admin」**、
   `repositoryId` は任意フィルタ。リポジトリごとの認可は**この PR で新設しない**
   (既存の慣行から外れる変更になる)。**帰結を隠さない**: repo B だけの管理者は
   B の未解決行を見られない。AC 13 も「どのリポジトリの admin か」を書く。
4. **sweeper と retention は、行の側のモードを見ない。** 一度できた intent は、
   そのリポジトリが今 `disabled` でも、**リポジトリごと消えていても**、
   横断で処理する。**「今のモード」を見て処理を止めると、`journaled` を切った瞬間に
   未解決の行が凍りつく**。モードを凍結するのは §6.5-2 の「完成」だけで、
   sweep と purge は凍結の対象ではない。
   **leader は instance-wide の役割**を使う — 既存 purge が全体の `purge` role で
   守られている (`LineagePurgeScheduler:178`) のと同じにする。**リポジトリ単位の
   leader role は作らない。** CAS があるので二重実行は壊れないが、
   どちらの構成を意図しているかは決めておく。
5. **retention は instance-wide。** `lineage.capture-intent.retention.days` は
   `lineage.retention.days` と同じく override を持たない。**リポジトリごとに違う保持期間は
   持たせない** — 既存の設定体系がそうなっており、ここだけ変えると一貫性が壊れる。
6. **リポジトリを増やしても、この機能のための準備作業は要らない。**
   新設する状態はすべて共有の `nemaki_lineage` にあり、その view は 1 回だけ用意される。
   **これは範囲を切ったことの副次的な利点**でもある — 撤回した刻印案は
   **content DB 側に Mango index が要り、新しいリポジトリを作るたびに用意が必要**だった。
7. **リポジトリが消えても intent は残る。** 共有 DB にあるので、リポジトリ削除では消えない。
   **それでよい** — 「そのリポジトリへ取り込もうとした」という記録は証拠である。
   retention の対象にはなる。
8. **リポジトリのライフサイクルの限定。** リポジトリは `repositories.yml` から初期化時に
   読まれる (`RepositoryInfoMap.init()` → `loadRepositoriesSetting()`) だけで、
   **本番の実行時生成・改名の経路は見つからなかった** — `createRepository` という識別子は
   **コードベースに存在しない** (`Patch_SystemFolderSetup` にあるのは
   `createRepositoryConfigurationEntry` で、これは設定エントリを作るもの)。
   `RepositoryInfoMap.add(RepositoryInfo)` は main 内に呼び出し元が無い。よって:
   - 「新しいリポジトリ」とは**構成に足して起動/再読込した後**のもの (AC 22 の意味)
   - **表示名の変更は lineage の同一性に影響しない** (鍵は `id`)
   - **`id` の変更は別のリポジトリ**である。過去の行は**古い `id` のまま残る**
   - **実行時の動的生成は本設計の対象外** — そういう経路が別に在るなら、
     この前提から見直すこと

### 受入条件 (§8 に追加)

| # | 条件 | 戻したときに落ちること |
|---|---|---|
| 19 | **repo A が `journaled`、repo B が `disabled` のとき**、A への取込は intent を作り、**B への取込は intent を作らず挙動も変わらない** | global 設定を読むと落ちる |
| 20 | scope を開いた後にそのリポジトリを `disabled` にしても、**③ は完成する** | 完成時に読み直すと落ちる |
| 21 | 2 リポジトリの intent が並存し、**一覧が `repositoryId` で正しく絞れる** | view の鍵から repositoryId を外すと落ちる |
| 22 | (**安全性の回帰テスト** — 共有 DB に置く限りどう実装しても緑。リポジトリ単位の状態を意図的に作らない限り判別できない) **新しく構成したリポジトリ** (`repositories.yml` に足して再起動/再読込した後) への取込が、追加の準備作業なしで intent を作る | 共有でない場所に状態を置くと落ちる |

---

## 6.9 レビューで判明した、実装前に決めるか直すことがら

独立レビュー (2026-08-20) が出したもののうち、上で本文に織り込めなかったもの。
**すべて実装着手前に片付ける。**

| # | ことがら |
|---|---|
| 1 | **view group の再デプロイ中、既存の lineage view は HTTP 200 で `rows:[]` を返す** (本リポジトリで実測済み — `v3.3-release-plan.md:211`)。その間 `eventKeyExists` が false を返して**重複イベントを書き**、projection が止まり、purge が黙って 0 件になる。**新設 4 view の投入手順に、この窓の扱いを書く。** `putDesignDocument` は例外を握って null を返すので、409 で view が 1 本落ちても気づけない |
| 2 | **leader election は既定で無効** (`LeaderElection:78` が `isEnabled()` false なら true を返す) で、**purge の cron も既定は空**。「既存 purge と同じにする」とだけ書くと、**無防備で動かない sweeper**になる。sweeper の既定挙動を明示する |
| 3 | **`UNRESOLVED` は「落ちた」と「まだ走っている」を区別しない。** sweep は 15 分だが `IngestSchedulerService` の fetch timeout は 30 分で、添付の多い mail/note は 15 分を超えうる。運用者向け一覧が**実行中の作業で汚れる**。lease か heartbeat か、少なくとも「実行中かもしれない」と分かる欄が要る |
| 4 | **③ が書く欄に `occurredAt` を含める。** v1 の retention は `by_occurred_at` を `startkey=""` で引くので、`occurredAt` の無い行は key が `null` に照合されて**永久に purge されない**。③ の全項目を書き出す |
| 5 | **`UNRESOLVED` の削除と遅れた完成が競合する。** retention が消した後に完成側の CAS が 409 → 読み直すと**文書が無い**。「無い」は §5.1 の表に無い。復活させるのか、証拠を捨てるのかを決める |
| 6 | **「モードが判定できない」場合の規則が無い。** `getModeForRepository` は `SpringContext` 経由で、`IngestLineageEmitter:296-303` は「context が無いのは良性ではない」と明記している。`journaled でない`として通すと配線ミスで保証が消え、fail-closed にすると取込が全部止まる。**どちらかを選ぶ** |
| 7 | **`MutationOutcome.UNKNOWN` と `CapturedContent.ContentState.UNKNOWN` が同じ行に同居する。** 前者は `CAPTURED` を止め、後者は止めない (`contentStored: "unknown"` として記録される)。**片方を改名する** |
| 8 | **idempotency 記録の書込失敗は握り潰される** (`:1636`)。次のポーリングで再取込され、`always_version_up` では 2 つ目の版と**2 つ目の `CAPTURED` 行**ができる。「重複防止であって証拠ではない」と外したが、**その失敗は証拠を捏造する** |
| 9 | **`createDirectRelationship` は 6 番目の公開変更メソッド** (`CanonicalImportService:81`)。規則 7 で対象外にしているが、§3.2 が「限定は保証文と同じ場所に置く」と決めた以上、**保証文にも書く** |
| 10 | **DLQ が取込バイトを永続化する** (`:1656-1664`)。最初の追跡対象変更より前に失敗すると、**intent 無しで取込内容の写しが残る**。CMIS 文書ではないので保証の文言上は外だが、**捕獲物が境界の外にある**ことは書く |
| 11 | **lineage を一切出さない文書作成経路が他にもある** — `BulkCheckInResource:405`、`api/v1/resource/DocumentResource:166`、`odata/CmisEntityProcessor:221`。§7 は「他は fail-open のまま」と書いているが、**これらは fail-open ですらなく発行が無い**。`CloudDriveResource` の legacy と並べて明記する |
| 12 | **`nemaki_lineage` の文書型は 3 つではなく 15 ある。** 「intent 行が既存 purge で消えない」のは**新しい問題ではなく、event 以外の型すべてに共通する既存の norm** である。そう書き直す |
| 13 | **AC の一部は本番経路を通せない** — sweep・遅延昇格・sweeper が探索しないこと・終端性・retention の 4 対照は、取込呼び出しからは到達しない store 層の挙動。**「全 AC を本番経路で」は守れない**ので、store 層と明示して、view については**デプロイ・クエリ・`descending` の startkey/endkey 反転**まで見る (Rhino で map 関数だけ評価する既存の型は、鍵と述語しか示さない) |
| 14 | **テストの土台に lineage が構造的に無い。** `CanonicalImportServiceTest` の `setUp` は `ingestLineageEmitter` を**設定しない**ので `:1594` は null チェックで素通りする。scope を差し替え可能な協調者として渡すと、**モード解決も耐久書込も偽物のまま緑になる** — `IngestLineageEmitter:186-188` にその前科が記録されている |

### 別タスクに切り出した既存バグ (これらが直るまで設計の前提が立たない)

1. **dry run が 4 入口で実際に書き込む** — §4 規則 1 と AC 16 の前提。
2. **ACL 適用が古い `_rev` で決定的に失敗し、失敗が観測できない** — `aclSyncPolicy = none` /
   `copy_from_source` は canonical 経路で**効いていないように見える**。ACL を追跡対象に
   するには先にこれを直す必要がある。

### ついでに見つかった既存バグ (この PR の範囲外)

- `removeExistingRelationships:1166` の `skipCount` が**一度も加算されない** —
  relationship が 100 件超で削除が失敗し続けると**同じページを無限に取り直す**
- `:553-555` が note 添付の結果を `skipped=false` で組み直すため、**dedupe skip された添付が
  「取込済み」として数えられ**、note 全体が skipped から imported に反転する
- `lineage.mode` は admin-managed dynamic key **ではない**ので system property が先に来る。
  Atlas 用 compose が `-Dlineage.mode=journaled` を渡しており、**設定 UI のトグルが効かない**
  (CLAUDE.md の落とし穴の逆パターン)
- `LineageConfig:698-705` の `getEmitter` は **global の `getMode()`** で emitter を
  キャッシュする。① をここ経由にすると AC 19 が別の理由で落ちる

---

## 7. 何を主張しないか

- **「すべての証拠が必ず残る」とは言わない。** 保証するのは §3.2 の 1 点だけ。
- **intent 行の保持は既存の purge では扱えない。** 現行の v1 retention view は
  `type === 'lineage_event'` だけを選ぶ (`CouchLineageJournalStore:259`) ので、
  **`lineage_capture_intent` のままの行 (`CAPTURE_INTENT` / `UNRESOLVED`) は既存の v1 purge では削除されない** (有限の保持期間を設定すれば `UNRESOLVED` は専用 view 経由で削除される — §5.1)。③ で完成して `lineage_event` に
  なった行だけが purge 対象になる。**intent 専用の retention view と保持方針を作る**
  — かつ**未解決の行を短い期限で消さない** (消したら証拠にならない)。
- **既存のイベント一覧にも出てこない。** 運用者が `UNRESOLVED` を見るには
  **専用の一覧が要る** — 「運用者が見る対象」と書く以上、見る手段を用意する。
- **`UNRESOLVED` は解決ではない。** クラッシュ後の多くはここに落ちる。**それが正直な状態**で
  あり、運用者が見る対象である。自動で `ABANDONED` に倒さない。
- **親と子は原子的でない** (§4)。添付を伴う取込は、途中まで成功した状態があり得る。
- **他の発行経路は fail-open のまま。** import/export・アーカイブ・cloud sync・retention は
  すべて `emitSafely` で、本 PR では変えない。**アーカイブ経路は文書を削除してから発行する**
  ので刻印する先が無く、処分証跡 (P3-3) と一緒に設計する。
- **`lineage.mode` の既定は `disabled`** なので、**既定構成ではこの保証は働かない**。
  **`direct` でも働かない** (journal を持たないモード) — `journaled` 限定である。
- **fail-closed は挙動変更である。** `journaled` を有効にした構成で `nemaki_lineage` が
  落ちていると、**これまで成功していた取込が失敗するようになる**。これは意図した交換
  (証拠の無い文書を作らない) だが、**リリースノートに明記する**。

---

## 8. 受入条件 (負のコントロールつき)

**すべて本番の呼び出し経路を通すこと。** 文書の記述だけで確かめる項目
(legacy cloud fallback と orchestrator の relationship が対象外であること、
`interpares-mapping.md` の該当行が更新されていること) は**受入条件ではなく
文書レビューの確認項目**として別に持つ — 挙動を伴わないものを AC に混ぜない。 ヘルパ単体のテストは PR #507 で 3 回騙されたので採らない。
`CanonicalImportServiceTest` には現在 lineage の記述が 1 つも無く、**この seam は端から端まで未テスト**である。

| # | 条件 | 戻したときに落ちること |
|---|---|---|
| 1 | intent の書込に失敗したら取込は**エラーを返し、変更を一切行わない** (「文書を作らない」では、`replace` の削除や resync の relationship 削除が先に走る経路を見逃す) | fail-closed を fail-open に戻すと落ちる |
| 2 | ③ が失敗しても行は `CAPTURE_INTENT` として残る (消えない) | 例外で行を消すと落ちる |
| 3 | 期限切れの `CAPTURE_INTENT` が `UNRESOLVED` になる | 走査をやめると落ちる |
| 4 | **遅れて完走した取込が `UNRESOLVED` から `CAPTURED` へ昇格できる** | 終端扱いすると落ちる |
| 5 | 走査は `UNRESOLVED` を **`CAPTURED` にしない**し、文書を探しにも行かない | 探索を足すと落ちる |
| 6 | `CAPTURED` は終端 — 誰も上書きしない | 上書きを許すと落ちる |
| 7 | **追跡対象の変更のいずれかが失敗したら `CAPTURED` にしない。** 次の 3 つを**個別に**含める — ⑴ ACL の適用失敗 (`:1049`)、⑵ relationship 削除の失敗 (`:1181`)、⑶ `replace` の削除失敗 (`:1473`)。**いずれも戻り値も警告文字列も出さない**ので、メタデータ失敗だけを注入するテストでは 3 つとも外しても緑になる | 3 つのどれかの追跡を外すと、その 1 件が落ちる |
| 8 | **wrapper の後処理での失敗が、root の intent を未完成のままにする** (AC 10b の root 版)。「intent が後処理まで覆う」だけでは、`execute()` 内で完成させても最終状態は `CAPTURED` のままで**緑になる** — 後処理は snapshot を変えない (`captureWindow*` は要求から読み、`chatCapturedAt` は意図的に除外) ので、状態を見るだけの表明では判別できない | 後処理の前に完成させると落ちる |
| 9 | dedupe skip の**後に wrapper が行った変更が、同じ外側の intent を `CAPTURED` にする** (「内側が終端にしない」だけでは、機能が無くても空虚に緑になる) | 内側で終端にすると落ちる / wrapper の変更を scope の外に出すと落ちる |
| 10 | 添付・生 `.eml` は**1 件ずつ独立した intent** を持つ | 親に相乗りさせると落ちる |
| 10b | **子の後処理の失敗が、その子の intent を未完成のままにする** — ⑴ note の子メタデータ失敗でその子が `CAPTURED` にならない、⑵ mail の子 relationship 失敗でその子が `CAPTURED` にならず**親は完成しうる**、⑶ note `files_and_body` の relationship 失敗が**子に帰属する** (AC 10 は「別々の intent が在る」しか示さず、子が後処理より前に完成しても、relationship を親に付け替えても緑のまま) | 子を早く完成させると落ちる / relationship を親の scope に付けると⑶が落ちる |
| 11 | projection loop は `CAPTURE_INTENT` / `UNRESOLVED` の行を**配送しない** | **既存 view が `type === lineage_event` を要求するため、本 PR の変更を戻しても緑のまま** — これは判別テストではなく**安全性の回帰テスト**である。そう明記して置く |
| 12 | sequence は**完成時にだけ**振られる | intent 時に振ると落ちる |
| 13 | `UNRESOLVED` の一覧が **admin では成功し、非 admin では拒否される**。複数行で **`intentOpenedAtMs` の降順**・**`limit` / `offset`** (既存のパラメータ名は `skip` ではなく `offset`)・**正確な件数** (既存 `/events` は概算しか返さないので、**dead-letter 一覧と同じく `_count` reduce を持つ view** にする)が正しい | 認可を外すと落ちる / 並び順を変えると落ちる / pagination・件数を外すと落ちる (「一覧できる」だけでは、これらを戻しても緑) |
| 14 | **有限の保持期間を設定したとき**、4 つを対照する: ⑴ `unresolvedAtMs` が期限切れの `UNRESOLVED` は **purge される**、⑵ **`intentOpenedAtMs` は期限切れだが `unresolvedAtMs` は新しい** `UNRESOLVED` は **残る**、⑶ 同じだけ古い `CAPTURE_INTENT` は**直接 purge されず先に sweep される**、⑷ **既定 (無期限) では**期限切れの `UNRESOLVED` も**残る** | ⑵ が無いと起点を `intentOpenedAtMs` に戻しても緑 / ⑷ が無いと既定を有限に戻しても緑 |
| 15 | `lineage.mode` が `disabled` / `direct` のとき、**intent を書かず、取込の挙動も変わらない** | **これは判別テストではなく安全性の回帰テスト** — 機能ごと戻しても緑になる。そう明記して置く |
| 16 | **追跡対象の変更をせずに正常終了する経路 (各種 skip / 添付ゼロ。**dry run は上記のバグが直るまで含めない**) で intent が作られず、かつ同じ入口に変更を伴う要求を与えると intent が作られる** — **不在だけを見ると、機能ごと消しても緑**になるので必ず対にする | 入口で開くと前半が落ち、遅延 open をやめると後半が落ちる |
| 17 | 追跡対象の変更が 1 つでも `FAILED` / `UNKNOWN` なら **`CAPTURED` にしない** | 述語を緩めると落ちる |

## 9. 段階

本 PR は §3〜§6 まで — **境界を閉じ、閉じられなかったものを可視化するところまで**。
以下は**やらない**と明記する:

- **刻印と、それによる後からの判定** (§3.3) → **P1-1(e)**。これには
  内部型の信頼境界・複製規則・CAS 併合・要素ごとの map view・
  orchestrator が入口の後に作る relationship・DELETE が先に走る経路が全部含まれる
- **`ABANDONED` / `COMMIT_OBSERVED` の判定** — 上と同じ理由で持たない
- evidence ledger との分離 (P1-3) — intent は今のところ `nemaki_lineage` にある
- v2 表現 (P1-1(b)) — intent の snapshot も v1 projection に乗る
- アーカイブ・export 経路への展開 (P3-3 と一緒に)
- 実行起源 — `executedBy` は委譲実行で admitted-unknown のまま

---

## 10. レビューの状態

- **9 巡目**で「指定どおり実装可能」の判定 (外部レビュー)。
- **10 巡目**でオーナー要望のマルチリポジトリ確認を追加し、4 件の指摘を受けて §6.5 を修正。
- **11 巡目は外部レビューの残高切れで回せず**、自己レビューで 2 件直した
  (view ごとの述語が未記載 / `_id` の接頭辞を既存の `"lineage:"` に合わせないと
  完成後に `journalDocumentId` で引けなくなる)。
- **代わりに独立レビューを 2 体 (事実照合 / 保証への攻撃) 走らせた (2026-08-20)。**
  **11 巡の外部レビューが見つけなかった CRITICAL を複数出した** — 結果は §6.9。
  主なもの: dry run が 4 入口で実際に書き込む (前提が偽)、ACL 適用が古い `_rev` で
  決定的に失敗し観測できない、intent 行が `_id` 経由の既存 API から読み書きできる、
  既存 emitter をどうするか未定義、AC 8 が判別できない。
- **現状: §6.9 の 14 件と、切り出した既存バグ 2 件が片付くまで実装に入らない。**
