# Capture outbox — 取込の証拠を落とさないための境界 (P1-1(a))

**状態**: 設計 (2026-08-20)。実装前。外部レビュー 11 巡 + 独立レビュー 3 体を反映。
**行番号の注意**: 本書が引く `CanonicalImportServiceImpl` の行番号は、
2026-08-20 の取込バグ修正 3 件で **80 行以上ずれている**。
主要なものだけ更新した — 引用が合わないときは**識別子で探すこと**。
**対象**: 3.4 / P1-1(a)。[`authenticity-roadmap.md`](authenticity-roadmap.md) の P1-1 行。

---

## 1. 何が壊れているか

取込は**文書をコミットしてから証拠を発行する**。発行は失敗しても取込は成功として返る。

```
CanonicalImportServiceImpl:1646   objectService.createDocument(...)   ← ここで文書は永続化される
      … (4〜6 回の CouchDB 書込。attachment / version series / content / change event / aspect)
CanonicalImportServiceImpl:1681   ingestLineageEmitter.emitLineageEvent(...)  ← ここで初めて証拠
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
  - 逆方向も壊れる — 新規作成 (`:1646`) と source identity の付与 (`:1653`) は**別の書込**で、
    間で落ちると**文書は在るのに source-id で引けない**

→ 判定には**試行に固有の刻印を、その試行が永続化する書込そのものに載せる**しかない。
**それは本 PR の範囲外にする** (理由は §3.3)。本 PR は**判定できないものを
`UNRESOLVED` として正直に残す**。

### 3.2 3 つのステップ

```
① intent を nemaki_lineage に書く (captureState = CAPTURE_INTENT)。
     **`LineageEmitter` 経由では書かない** — `JournaledLineageEmitter` は契約として
     fail-open (「The parent business operation is never blocked」) で、spool に
     逃がす経路もある。**`LineageJournalStore` を直接呼び、かつ戻り値を検査する** —
     例外は当てにならない (§6.8-1b: 書込層はあらゆる例外を握って null を返す)
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
(`CloudDriveResource` の legacy 経路。canonical が使えないときの fallback)。**この経路は本 PR の保証の外**で、
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
| 1 | **intent は「最初の変更操作の直前」で開き、その scope の所有者が完成させる** (root scope は公開入口、child scope は子操作)。** 入口の直後ではない — dry run・空/擬似ファイルの skip・dedupe/idempotency skip・`files_only` で添付が無い/全部 skip、といった**何も変更せずに正常終了する経路**が実在し、そこで intent を開くと**行き先の無い行**ができる (`CAPTURED` にすると objectId が無く完成できず、放置すると 15 分後に `UNRESOLVED` になるが、実行中の主体は「何も変更しなかった」と分かっているので「判定不能」は嘘になる)。**変更しないなら intent も作らない。** dedupe skip の後に wrapper がメタデータを更新する経路では、**その wrapper の変更の直前で開く** |
| 2 | **受け渡すのは intent ではなく `CaptureMutationScope`** — wrapper が**未 open のまま**生成し、内部 overload で `execute` に渡す。`execute` は変更の直前で `scope.ensureIntentOpened()` を呼ぶ。**これで「wrapper は `execute` を呼ぶまで変更が起きるか分からない」問題が解ける** (入口で開けば no-op 経路に行き場の無い行ができ、`execute` に所有させれば wrapper の後処理より前に完成してしまう) |
| 3 | **完成させるのは scope の所有者だけ** — root scope なら公開入口、child scope なら子操作 (規則 4)。内側の `execute` は open するが complete しない |
| 4 | **子操作 (添付・生 `.eml`) は 1 件ずつ自分の scope を持ち、その所有者は子操作自身**である。`executeNoteAttachment` が例 — 内部 `execute` に自分の scope を渡し、**その後の note メタデータ更新も同じ scope で行い、最後に自分で complete する**。親の scope を渡すと複数添付が 1 つの intent を共有して規則 4 に反し、子の公開 `execute` に自己完了させると**直後のメタデータ更新が intent の外**に出る |
| 5 | **子の処理の一部として作る relationship は、すべてその子の scope に属する** — mail の添付、生 `.eml`、**note の `files_and_body` で本文とを結ぶ relationship** (`CanonicalImportServiceImpl:493,509`。今は子の `execute()` が返った後に作られる) を含む。失敗の帰属を一意にする: 添付の relationship 作成が失敗したら、失敗するのは**その添付の intent** であって親ではない |
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

- ~~`replace` の削除失敗は**ログだけで作成に進む**~~ / ~~relationship の削除は失敗を
  **内部で飲み、戻り値も無い**~~ / ~~ACL の変更失敗も**内部で飲む**~~
  → **この 3 つは 2026-08-20 に修正した**。いずれも警告文字列を返し、結果に載る。
  **tracker はその警告を拾えばよい** (新たに配線し直す必要は無い)
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

各操作は `MutationOutcome { SUCCEEDED, FAILED, INDETERMINATE }` を tracker に**明示的に記録する**。
**`INDETERMINATE` が 1 つでもあれば `CAPTURED` にしない** — 「分からない」を成功に潰さない。

### 5.0.1 既存の `emitLineageEvent` はどうなるか

**設計がここを書いていなかった** (レビュー指摘)。放置すると実装者がどちらにも倒せる。

> **⚠ この節は §6.10 (B4) で撤回した。** 「③ が既存の発行を置き換える」は採らない —
> 本番の emitter は write-schema barrier に従って v1 append と spool を使い分けており、
> ③ が v1 を直接書くと **schema 2 環境で取込イベントの v2 表現を消す**。
> **intent 行は `lineage_capture_intent` のまま完成し、lineage イベントは従来どおり
> emitter が書く。** 以下の 2 つの挙動変更も、したがって**起きない**。

~~**採る**: ③ が既存の発行を置き換える。両方残すと同じ `eventKey` の行が 2 つできる~~

~~**その帰結を隠さない** — 置き換えは 2 つの挙動変更を伴う:~~ (撤回)

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
| **行が消えていた** | 取込 (③) が見つける | 遷移ではない。**復活させず、警告として呼び出し元に返す** (§6.8-6) |

**書込はすべて `_rev` CAS。** 衝突したら**読み直して遷移可否を再判定**し、不可なら何もしない。

### 5.2 ① が書くフィールド

**当初これを書いていなかった。** ① の行はクラッシュを生き延びる唯一の証拠であり、
未解決一覧の中身そのものである。`outputs` はまだ書けない (objectId が無い)。

| 欄 | 内容 |
|---|---|
| `_id` | `"lineage:" + eventId` (§保存形式) |
| `type` | `"lineage_capture_intent"` |
| `captureState` | `CAPTURE_INTENT` |
| `intentId` | 試行ごとに一意 |
| `intentOpenedAtMs` | 開いた時刻 (一覧の並び順の鍵) |
| `repositoryId` | どのリポジトリか (view の鍵、一覧の絞り込み) |
| `connectorId` / `sourceSystem` / `sourceObjectId` / `sourceObjectType` | **何を取り込もうとしたか** |
| `requestId` | 呼び出し元の要求と突き合わせるため |
| `processType` | archetype 由来 |
| `executedBy` / `onBehalfOf` | 誰が (§P1-1(b) と同じ規則) |

**未解決一覧はこれを全部出す。** `intentId` と時刻だけでは、運用者は何が起きたか分からない。

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
- **`eventKey` の重複判定が効かなくなる — これは 3 つ目の挙動変更である** (§5.0.1 に並べる)。
  `append` は `eventKeyExists` を先に見て、**同じ論理取込の 2 回目を書かない**
  (`CouchLineageJournalStore:515`)。完成は append ではなく**同一文書の update** なので
  この判定を通らず、**試行ごとに intent id が違う以上、同じ `eventKey` の行が複数できる**。
  再試行・dedupe 再走・冪等記録の書込失敗のたびに、Atlas / Purview へ**重複して配送される**。
  「append 経路に誤って乗せない」だけの注意ではなく、**設計上の帰結として価格を付ける**
- **フィールド名は `captureState`** (`state` は v2 の sequencing state が使用中)
- **`publishStatusByTarget` は intent の間は持たせない。** 既存 v1 projection view は
  `type == lineage_event && publishStatusByTarget` で選ぶ (`CouchLineageJournalStore:241`) ので、
  intent は構造上拾われない
- ③ の完成時に行を `lineage_event`(v1) として成立させ、`publishStatusByTarget` と
  `sequenceNumber` を付ける。**sequence は完成時にだけ振る**
- **`publishStatusByTarget` は `lineage.targets` が未設定 (既定) なら空**になり、
  `CouchLineageEvent` はそれを `null` として書く。結果その行は `by_target_status` に出ず、
  **即座に purge 対象になる**。既存 `append` と同じ挙動なので回帰ではないが、
  **書いておく** (発見されるべきではない)

### 5.3 §5.2 の保存形式は撤回済みの前提のまま残っていた (2026-08-21、実装着手時)

**§5.0.1 の B4 撤回と §5.2 の「保存形式」が矛盾している。** B4 は「intent 行は
`lineage_capture_intent` のまま完成し、lineage イベントは従来どおり emitter が書く」と
決めたが、§5.2 の 4 つの箇条書きは**撤回した方のモデル (完成時に `lineage_event` になる)
を前提に書かれたまま**である。設計書どおりに実装すると B4 が禁じたものが出来上がる。

#### コードで確かめた事実

| # | 確認したこと | 根拠 |
|---|---|---|
| 1 | 既存 purge が選ぶのは `type === 'lineage_event'` **だけ** | `CouchLineageJournalStore:260` の `by_occurred_at` |
| 2 | `findByRecordId` と `getRetryCount` は**型判定が一切ない** | 両メソッドとも `journalDocumentId` で引いて即 decode |
| 3 | `updatePublishStatus` と `discardEvent` は **`lineage_event_v2` しか弾かない** | 各メソッド内の `"lineage_event_v2".equals(doc.get("type"))` |

#### 帰結 1 — 無効になった記述 3 件 (価格を付けすぎている)

B4 のもとでは intent 行は配送対象の event にならないので、以下は**起きない**。
§5.0.1 が「3 つ目の挙動変更」として価格を付けた重複配送も**発生しない**。

- ~~`eventKey` の重複判定が効かず、Atlas / Purview へ重複配送される~~
- ~~`publishStatusByTarget` が空だと即 purge 対象になる~~
- ~~`_id` に独自接頭辞を付けると完成後に `journalDocumentId` で引けなくなる~~

#### 帰結 2 — B4 が開けた穴は §6.10 が半分だけ気づいていた (上の #1 より)

§6.10 の B4 は「**`CAPTURED` は本当に終端になる** — `by_occurred_at` に入らないので
purge されない」と正しく書いている。だが続きが「intent 専用の retention (§6) だけが効く」
であり、その §6 の retention は **`UNRESOLVED` の行しか対象にしていない**
(retention view の述語が `captureState == "UNRESOLVED"`)。
**つまり `CAPTURED` にはどの retention も掛からない。** §6.10 は purge されないことを
利点として書いたが、それが無期限の増加を意味することは書いていなかった。


完成した `CAPTURED` 行は `type = "lineage_capture_intent"` のままなので、
**既存のどの purge 経路にも当たらない**。取込 1 件につき 1 行が**無期限に積み上がる**。

§9.5 は撤回前のモデルに対して逆の問題 (「未解決は無期限、完成した証拠は 90 日」) を
指摘していた。**B4 はそれを反転させたが、反転したことをどこにも記録していなかった。**

#### 決定 — `_id` は専用接頭辞にする

§5.2 は `"lineage:" + eventId` を指定していたが、その理由 (完成後に既存経路から引ける
こと) は B4 で消えた。一方その `_id` は上の #2/#3 の 4 経路が**構造的に到達できる**
場所である (`journalDocumentId` が `"lineage:" + recordId` を組み立てるため)。

**`_id = "lineage_capture:" + intentId` にする。** 4 経路は `"lineage:" + recordId` しか
組み立てないので、**構造的に届かない**。型ガードは引き続き足すが、それは**唯一の防御では
なく多重防御**になる — 「新しく `_id` で引く経路を足す人がガードを覚えていること」に
依存しない形にする、という違いである。

emitter が書く lineage イベントとの対応は `_id` ではなく**フィールドで持つ**
(完成時に `journalRecordId` を記録する)。

#### 決定 — `CAPTURED` 行の保持期間は設定可能・既定は無期限 (オーナー判断、2026-08-21)

**`lineage.capture-boundary.retention.captured.days` を足し、未設定なら削除しない。**
(§6 の `lineage.capture-boundary.retention.unresolved.days` と対称にする。似た名前が 2 つ
並ぶので、状態を名前に入れて取り違えを防ぐ。接頭辞が `capture` ではなく
`capture-boundary` なのは、**`lineage.capture.version-events` が既に在り、別の意味の
「capture」だから** — 同じ接頭辞に置くと無関係な 2 機能が設定ファイル上で隣り合う。) 運用者が明示的に
有限を選んだときだけ purge が働く。前作業 2 で入れた `POST /dlq/purge?olderThanDays=N` と
同じ形で、**既定では証拠を消さない**。

| | |
|---|---|
| 起点 | `capturedAtMs` (`CAPTURED` になった時刻)。`intentOpenedAtMs` ではない — 未完成の行の起点は `unresolvedAtMs` で別に決まっている |
| 既定 | 未設定 = 無期限。取込 1 件 = 1 行が残る |
| 対価 (無期限を選んだ場合) | `nemaki_lineage` が取込量に比例して単調増加する。10 万件取込で 10 万行。**DB サイズは監視対象になる** |
| 対価 (有限を選んだ場合) | 「いつ取り込んだか」の証拠がその期限で消える。lineage イベント本体は既存 purge で別途 90 日なので、**揃えないと片方だけ残る**。運用手順に書くこと |

**主張しないこと**: 既定が無期限であることは「証拠が永続する」という保証ではない。
CouchDB の compaction・バックアップ方針・ディスク障害はこの設定の外にある。

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
    `lineage.capture-boundary.retention.unresolved.days` の既定は `0` = 無期限
    (当初 `lineage.capture-boundary.retention.unresolved.days` としていたが、§5.3 で `CAPTURED` 側の
    設定が増えたので、状態を名前に持つ対称な形に揃えた)。**journal の 90 日とは
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
   **leader については §6.8-4 / §6.10 を見ること** — ここに書いていた
   「既存 purge の `purge` role で守られているのと同じにする」は**撤回した**。
   leader election は既定で無効で、そのとき `isLeader` は全レプリカに true を返すので、
   「守られている」は事実ではない。**安全は `_rev` CAS で担保する。**
5. **retention は instance-wide。** `lineage.capture-boundary.retention.unresolved.days` は
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

## 6.8 実装前に決めた 6 件 (2026-08-20)

### 1. モードは「判定できない」と言えない — 保証に穴があることを書く

**当初「判定できないときは fail-closed」と決めたが、成立しないことが分かった。**
`getModeForRepository` は**構造上「判定できない」を返せない**:

- `readDynamic` → `PropertyManager.readValue` → `ContentDaoServiceImpl.getConfiguration` は
  **あらゆる例外を握って空の設定を返す** (`:1929-1936`、DEBUG ログのみ)。
  `nemaki_conf` が落ちていても値が無いだけになり、起動時の既定
  `@Value("${lineage.mode:disabled}")` に落ちて **`DISABLED`** になる
- `LineageMode.fromString` は**解釈できない値を DISABLED に潰す** (`:20-23`、WARN のみ)。
  `lineage.mode.override.{repo}` の打ち間違いは「無効」と同義になる

つまり**「設定を読めなかった」と「無効にした」は今日すでに区別できない**。当初の判断は、
それが起きる場所には効かず、起きない場所 (Spring context が無い) でだけ働く。

**決めたこと**:

1. **区別できる範囲でだけ fail-closed にする** — Spring context や bean が無い場合。
   これは `IngestLineageEmitter:294-303` が既に「良性ではない」と扱っている範囲と同じ
2. **区別できない範囲は、保証の穴として書く。** `nemaki_conf` が落ちている間、
   journaled のはずのリポジトリへの取込は**無効扱いで通り、intent は作られない**。
   §3.2 の保証はこの間**成立しない**。設定層の握り潰しを直すのは
   `PropertyManager` / `ContentDaoServiceImpl` の全利用者に影響するため本 PR の範囲外
3. **テスト環境と衝突させない** — §6.9-14 が指摘するとおり、context が無いのは
   実運用より**テストで起こる**状態である。fail-closed をここに効かせると取込テストが
   全部落ちる。よって**判定は scope の生成時ではなく、intent を書く直前**に行い、
   **既存のテストが到達しない位置**に置く

**`LineageConfig.getEmitter` は使わない** — global の `getMode()` で emitter を
キャッシュしており、リポジトリ単位の override を無視する。**なお本番の呼び出し元は 0 件**
なので、これは予防的な注意であって現存のバグではない。

### 1b. ① の書込は「例外」では失敗を検知できない

**これが実装上いちばん重い。** 「`LineageJournalStore` を直接呼び、例外を伝播させる」と
書いたが、**そうならない**:

- `CloudantClientWrapper.create(Map)` は**あらゆる例外を握って null を返す** (`:376-379`)
- `update(Map)` も同じ (`:761-764`)
- そして既存の `append` は**その戻り値すら見ていない** (`CouchLineageJournalStore:533`)

`nemaki_lineage` の 500・認証失敗・`max_document_size` 超過は、**例外にならない**。
① を素直に書くと **fail-closed が黙って fail-open に戻る**。

**決めたこと**: ① は**戻り値を検査する** — `null` でないこと、`isOk()` が真であることを
確かめ、そうでなければ取込を失敗させる。**「例外が飛ばなかったから成功」とは扱わない。**

**受入条件の注意**: `ensureDatabase()` は例外を投げる (`:140-143`) ので、
「DB を落として試す」テストは**修正前でも赤になる**。文書単位の書込失敗
(500 を返させる等) で試さないと、この判断を固定できない。

### 2. `MutationOutcome` の三値を改名する

`CapturedContent.ContentState.UNKNOWN` と同じ行に同居し、**片方は `CAPTURED` を止め、
片方は止めない**。同じ語で違う効果は誤読を招く。

| | 意味 | `CAPTURED` を止めるか |
|---|---|---|
| `MutationOutcome.SUCCEEDED` / `FAILED` / **`INDETERMINATE`** | **その操作が成功したかについての、我々の知識** | `FAILED` と `INDETERMINATE` は止める |
| `ContentState.STORED` / `NONE` / `UNKNOWN` | **リポジトリが何を持っているかという、記録する事実** | 止めない (`contentStored: "unknown"` として記録される) |

**`INDETERMINATE` は「呼んだが、成否を判定できなかった」**。無効化 (呼んでいない) とは別で、
呼んでいないものは tracker に載せない。

### 3. 新しい view は**別の design document** に置く

`_design/lineage` に足すと **view group 全体が再構築**される。その間に何が起きるかは
**測り方によって答えが違い、当初の書き方は主張のすり替えだった**:

- 「HTTP 200 で `rows:[]`」は、`v3.3-release-plan.md:100-121` によれば
  **map 関数を実行時に throw させて**再現したもので、**再構築の観測ではない**
- 同じ文書は再構築について**逆の観測**を記録している (`~:178`)
  —「再構築中の view クエリは**タイムアウトしうる**」
- コードは後者を支持する。lineage の読み取りは `update` を設定しないので
  CouchDB 既定の `update=true` が効き、**空を返すのではなくブロックする**

**どちらであっても結論は同じで、根拠を偽らない書き方はこう**:
再構築中の挙動は空応答かタイムアウトかのどちらかで、**空なら `eventKeyExists` が false を
返して重複イベントを書き、タイムアウトなら取込が止まる**。**証拠の仕組みを入れるために
証拠を壊すのは、どちらでも受け入れられない。**

→ **`_design/lineage_capture` を新設**し、4 本をそこに置く。CouchDB の再構築は design
document 単位なので、**既存の view は一切触られない**。第 2 の ddoc は
`_design/acl-epoch-indexes` (`AclEpochFinalizationService:604`) という先例があり、
`nemaki_lineage` の ddoc を列挙している箇所は無いので、readiness gate 等への影響も無い。

**ただし新 ddoc は既存の検査から外れる** — `viewSignatureViolations` は
`DESIGN_DOC` を名前で読み `VIEWS` に載る view しか見ない (`:1707-1752`)。
`LineageJournalViewCoverageTest` も同様。**第 2 の署名検査と分類の追加が要る**
(§6.9-13 の「デプロイ・クエリ・`descending` の反転まで見る」の置き場になる)。

**`deployViews` は view ごとに design document 全体を PUT する** (`:497-504` →
`createOrUpdateView:2929-2969`、差分検出なし)。4 本なら**4 回の署名変更 = 4 回の構築**を
順に起こす。1 回の PUT にまとめること。

**新 ddoc 自身の初回構築中は、同じく 200 + 空**になる。その間:
- sweeper は「期限切れの intent は無い」と判断して**何もしない** — 次の周回で拾う。害はない
- 運用者向け一覧は**空に見える**。これは「未解決が無い」と区別できない
- **`update_seq` の比較では出せない** — この SDK (cloudant 0.10.12) の
  `DesignDocumentViewIndex` に `update_seq` は無く、`ViewResult.getUpdateSeq()` は
  クエリが `update=true` である以上**構造上つねに最新**なので「構築中」を示せない。
  使えるのは `GetDesignDocumentInformationOptions` → `getViewIndex().isUpdaterRunning()` /
  `getUpdatesPending()`。**`CloudantClientWrapper` に該当のラッパは無いので新設が要る**
- **一覧は view の失敗を握り潰さないこと。** 既存の `queryRowsFromView` は
  例外を捕まえて `List.of()` を返す (`:1456-1458`) ので、そのまま使うと
  **障害が「未解決なし」に化ける** — この節の目的そのものを潰す

### 4. sweeper は既定で動かす。並行実行は CAS で守る

既存 purge を真似ると**動かない**: `lineage.purge.cron` の既定は空 (`LineageConfig:129-130`)。
leader gate は**有効な構成では本物の gate だが** (`LineagePurgeScheduler:179-183` が
`isEnabled()` を先に見る)、`lineage.leader-election.enabled` の既定は false で、
そのとき `LeaderElection:78` は**全レプリカに true を返す**。

- **sweeper は既定で有効**。起動条件は **`journalStore.isActive()`** (journal DB が在るか)
  であって、**モードを見ない**。
  **`journaled` のリポジトリの有無で判断してはいけない** — 最後の 1 つを `disabled` に
  切り替えた瞬間に sweeper が止まり、**開いたままの intent が永久に凍る**。
  それは §6.5-4 が禁じたことそのものである。`isActive()` は既にモード非依存
  (`CouchLineageJournalStore:1325-1333`)
- **既定で動かないと `UNRESOLVED` が現れず、一覧が常に空になる** — それは
  「問題が無い」と見分けがつかず、無いより悪い
- 周期は固定間隔 (既定 5 分)。staleness の閾値 (既定 15 分) とは別の設定
- **並行実行の安全は `_rev` CAS で担保する。** leader gate は有効な構成でだけ働くので、
  **それに依存しない**。各遷移は冪等なので、複数レプリカが同時に sweep しても**結果は正しい**。
  **「leader が守っている」とは書かない** — §6.5-4 の「既存 purge と同じ role で守られている」
  という記述は**この判断で置き換える** (両立しない)
- **正しさと費用は別**。既定 (leader election 無効) では N レプリカが同じ batch を
  5 分ごとに舐め、**N 回の GET と N−1 回の弾かれた PUT** が毎行に出る。
  **batch に上限を置き、間隔をレプリカごとにずらす**
- **lineage を使っていない環境でも polling は走る。** 既存 `LineagePurgeScheduler` も
  60 秒ごとに `isActive()` を呼び、`NotFoundException` をキャッシュしないので probe が
  永久に続く。**sweeper でそれを二重にしない** — 同じ判定結果を共有する

### 5. ③ が書く欄を全部書き出す

`occurredAt` を落とすと、v1 の retention は `by_occurred_at` を `startkey=""` で引くので
**key が `null` に照合されて永久に purge されない**。

③ が書くのは:

| 欄 | 値 |
|---|---|
| `type` | `"lineage_event"` に変える (intent の間は `"lineage_capture_intent"`) |
| `captureState` | `CAPTURED` |
| **`occurredAt`** | **完成時刻**。既存行と同じ意味に揃える (現行 emitter も emit 時刻を入れる)。**必ず明示的に入れる** — 落とした場合の壊れ方は経路で違い、生の map なら key が `null` になって**永久に purge されず**、`LineageEvent` 経由なら compact constructor が `""` に矯正して (`LineageEvent:105`) **`[""..cutoff]` に入り即座に消える**。受入条件は「在ること」ではなく**値**を見る |
| **`schemaVersion`** | `1`。`version` (upsert のカウンタ) とは**別の欄**で、落とすと完成行だけ `schemaVersion 0` になる |
| `intentOpenedAtMs` | そのまま残す — **試行が始まった時刻**は別の事実であり、捨てない |
| `sequenceNumber` | 完成時に採番 |
| `publishStatusByTarget` | 各 target を `PENDING` で初期化 |
| `eventId` / `eventKey` / `repositoryId` / `processType` / `inputs` / `outputs` / `runId` / `correlationId` / `version` / `snapshotAttributes` | 既存の v1 イベントと同じ |

### 6. 「行が消えていた」は遷移ではなく観測として報告する

有限の保持期間を設定した構成では、retention が `UNRESOLVED` を消した後に、
遅れて完走した取込が完成しようとして CAS が 409 → 読み直すと**文書が無い**。

- **復活させない。** 消えた行を書き戻すと、retention の決定を取込が覆すことになる
- **黙って捨てない。** 取込は**警告を返す**。ただし**原因を断定しない** —
  読み取り層は `NotFoundException` も他のあらゆる例外も**同じ null** に潰す
  (`CloudantClientWrapper:2843-2849`)、update も 409 と通信障害を**同じ null** にする
  (`:761-764`) ので、「消えていた」「一時的に読めなかった」「そもそも書けていなかった」を
  **区別できない**。文言は観測だけを書く — 「この取込の証拠行 (intent id: …) を
  完成時に読めなかった。考えられる原因は retention による削除、`nemaki_lineage` の
  一時的な障害、または ① の書込がそもそも成立していなかったこと」
- **既定 (無期限) でも起こりうる。** 当初「既定では起きない」と書いたが、
  上記のとおり一時的な障害でも同じ分岐に入る。**retention のせいだと書かない。**
- **① が戻り値を検査していることが前提** (§6.8-1b)。検査していなければ、
  この分岐は「消えた」ではなく「最初から無い」を見ていることになる

---

## 6.9 レビューで判明したことがら — 残り

独立レビュー (2026-08-20) が出したもの。**§6.8 で決着した 6 件はここから外した**
(モード判定不能 / `UNKNOWN` の重複 / view 再デプロイの窓 / sweeper の既定 /
`occurredAt` / 削除との競合)。以下は**実装のときに守る**もの。

| # | ことがら |
|---|---|
| 3 | **`UNRESOLVED` は「落ちた」と「まだ走っている」を区別しない。** sweep は 15 分だが `IngestSchedulerService` の fetch timeout は 30 分で、添付の多い mail/note は 15 分を超えうる。運用者向け一覧が**実行中の作業で汚れる**。lease か heartbeat か、少なくとも「実行中かもしれない」と分かる欄が要る |
| 8 | **idempotency 記録の書込失敗は握り潰される** (`:1636`)。次のポーリングで再取込され、`always_version_up` では 2 つ目の版と**2 つ目の `CAPTURED` 行**ができる。「重複防止であって証拠ではない」と外したが、**その失敗は証拠を捏造する** |
| 9 | **`createDirectRelationship` は 6 番目の公開変更メソッド** (`CanonicalImportService:81`)。規則 7 で対象外にしているが、§3.2 が「限定は保証文と同じ場所に置く」と決めた以上、**保証文にも書く** |
| 10 | **DLQ が取込バイトを永続化する** (`:1656-1664`)。最初の追跡対象変更より前に失敗すると、**intent 無しで取込内容の写しが残る**。CMIS 文書ではないので保証の文言上は外だが、**捕獲物が境界の外にある**ことは書く |
| 11 | **lineage を一切出さない文書作成経路が他にもある** — `BulkCheckInResource:405`、`api/v1/resource/DocumentResource:166`、`odata/CmisEntityProcessor:221`。§7 は「他は fail-open のまま」と書いているが、**これらは fail-open ですらなく発行が無い**。`CloudDriveResource` の legacy と並べて明記する |
| 12 | **`nemaki_lineage` の文書型は 3 つではなく 15 ある。** 「intent 行が既存 purge で消えない」のは**新しい問題ではなく、event 以外の型すべてに共通する既存の norm** である。そう書き直す |
| 13 | **AC の一部は本番経路を通せない** — sweep・遅延昇格・sweeper が探索しないこと・終端性・retention の 4 対照は、取込呼び出しからは到達しない store 層の挙動。**「全 AC を本番経路で」は守れない**ので、store 層と明示して、view については**デプロイ・クエリ・`descending` の startkey/endkey 反転**まで見る (Rhino で map 関数だけ評価する既存の型は、鍵と述語しか示さない) |
| 14 | **テストの土台に lineage が構造的に無い。** `CanonicalImportServiceTest` の `setUp` は `ingestLineageEmitter` を**設定しない**ので `:1594` は null チェックで素通りする。scope を差し替え可能な協調者として渡すと、**モード解決も耐久書込も偽物のまま緑になる** — `IngestLineageEmitter:186-188` にその前科が記録されている |

### 前提だった既存バグ 2 件 — **修正済み** (2026-08-20、ブランチ `fix/ingest-dry-run-writes`)

1. ✅ **dry run が 4 入口で実際に書き込んでいた** — 子要求への伝播、後処理の抑止、
   結果の組み直しでのフラグ落ち、冪等キー削除、DLQ 書込を修正。
   **§4 規則 1 は dry run を「変更しない経路」の例に戻せる。**
2. ✅ **ACL 適用が古い `_rev` で決定的に失敗し、失敗が観測できなかった** —
   update の結果を ACL 段へ渡し、両ヘルパを `String` 返しにした。
   **ACL を追跡対象にできる。** なお修正の過程で、失敗時にキャッシュ無効化を
   飛ばすと**永続していない ACL を配ってしまう**ことが分かった (キャッシュ済み DAO は
   contentCache に入れたのと同じ参照を返す) ので、無効化は必ず走らせる。

### ついでに見つかった既存バグ (この PR の範囲外)

- ✅ ~~`removeExistingRelationships` の `skipCount` が一度も加算されない — 無限に取り直す~~
  **これは誤り。取り消す** (外部レビュー、2026-08-22)。`skipCount` が 0 のままなのは
  **削除で index がずれるため意図的**で、`removedThisPass == 0` の回に break する。
  無限ループにはならない
- ✅ `executeNoteAttachment` の rebuild が `skipped` を落としていた件は**修正済み**
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

**取込から到達できる AC は、すべて本番の呼び出し経路を通すこと。**
**ただし store 層の AC (走査・遅延昇格・終端性・retention の各対照) は取込呼び出しから
到達しない** — そこは store を直接駆動してよく、**そう明示する** (§6.9-13)。
「全部を本番経路で」と書くと、守れない約束になる。 文書の記述だけで確かめる項目
(legacy cloud fallback と orchestrator の relationship が対象外であること、
`interpares-mapping.md` の該当行が更新されていること) は**受入条件ではなく
文書レビューの確認項目**として別に持つ — 挙動を伴わないものを AC に混ぜない。 ヘルパ単体のテストは PR #507 で 3 回騙されたので採らない。
`CanonicalImportServiceTest` には現在 lineage の記述が 1 つも無く、**この seam は端から端まで未テスト**である。

| # | 条件 | 戻したときに落ちること |
|---|---|---|
| 1 | intent の書込に失敗したら取込は**例外で失敗し (§6.10-B5)、その scope では変更を一切行わない**。**正のコントロールと対にする** — 同じ要求で intent の書込を成功させたら変更が起きること。「変更が無い」だけでは、無関係な理由 (scope 生成時の NPE、モックの配線漏れ) でも通る (「文書を作らない」では `replace` の削除や resync の relationship 削除を見逃す)。**「取込全体で変更なし」とは書けない** — 子 scope の intent が失敗する時点で親の文書は既に在り、しかも例外は wrapper の catch で `ExternalIngestResult.error(...)` になって**蓄積した警告を全部捨てる** | fail-closed を fail-open に戻すと落ちる |
| 2 | ③ が失敗しても行は `CAPTURE_INTENT` として残る (消えない) | **判別できない** — 戻すべき実装が「例外で行を消す」という誰も書かないものなので、これは*無い機能*を確かめる回帰テストである |
| 3 | 期限切れの `CAPTURE_INTENT` が `UNRESOLVED` になる | 走査をやめると落ちる |
| 4 | **遅れて完走した取込が `UNRESOLVED` から `CAPTURED` へ昇格できる** | 終端扱いすると落ちる |
| 5 | 走査は `UNRESOLVED` を **`CAPTURED` にしない**し、文書を探しにも行かない | **後半は判別できない** — 「探しに行かない」は構造 (sweeper が `ContentService` を持たない) でしか示せない。前半は判別できる |
| 6 | `CAPTURED` は終端 — 誰も上書きしない | **判別できない** — sweep の view 述語が `CAPTURE_INTENT` なので `CAPTURED` 行は構造上 sweeper に見えず、ガードに到達しない。ヘルパを直接叩けば判別できるが、それは §8 が採らない形 |
| 7 | **追跡対象の変更のいずれかが失敗したら `CAPTURED` にしない。** 次の 3 つを**個別に**含める — ⑴ ACL の適用失敗、⑵ relationship 削除の失敗、⑶ `replace` の削除失敗。**この 3 つは 2026-08-20 に警告文字列を返すようになった** (それ以前は戻り値も警告も無かった) ので、tracker は**その警告を拾えば済む**。ただし**メタデータ失敗だけを注入するテストでは 3 つとも外しても緑になる**ことは変わらないので、個別に注入する | 3 つのどれかの追跡を外すと、その 1 件が落ちる |
| 8 | **wrapper の後処理での失敗が、root の intent を未完成のままにする** (AC 10b の root 版)。「intent が後処理まで覆う」だけでは、`execute()` 内で完成させても最終状態は `CAPTURED` のままで**緑になる** — 後処理は snapshot を変えない (`captureWindow*` は要求から読み、`chatCapturedAt` は意図的に除外) ので、状態を見るだけの表明では判別できない | 後処理の前に完成させると落ちる |
| 9 | dedupe skip の**後に wrapper が行った変更が、同じ外側の intent を `CAPTURED` にする** (「内側が終端にしない」だけでは、機能が無くても空虚に緑になる) | 内側で終端にすると落ちる / wrapper の変更を scope の外に出すと落ちる |
| 10 | 添付・生 `.eml` は**1 件ずつ独立した intent** を持つ | 親に相乗りさせると落ちる |
| 10b | **子の後処理の失敗が、その子の intent を未完成のままにする** — ⑴ note の子メタデータ失敗でその子が `CAPTURED` にならない、⑵ mail の子 relationship 失敗でその子が `CAPTURED` にならず**親は完成しうる**、⑶ note `files_and_body` の relationship 失敗が**子に帰属する** (AC 10 は「別々の intent が在る」しか示さず、子が後処理より前に完成しても、relationship を親に付け替えても緑のまま) | 子を早く完成させると落ちる / relationship を親の scope に付けると⑶が落ちる |
| 11 | projection loop は `CAPTURE_INTENT` / `UNRESOLVED` の行を**配送しない** | **既存 view が `type === lineage_event` を要求するため、本 PR の変更を戻しても緑のまま** — これは判別テストではなく**安全性の回帰テスト**である。そう明記して置く |
| 12 | sequence は**完成時にだけ**振られる | intent 時に振ると落ちる |
| 13 | `UNRESOLVED` の一覧が **admin では成功し、非 admin では拒否される**。複数行で **`intentOpenedAtMs` の降順**・**`limit` / `offset`** (既存のパラメータ名は `skip` ではなく `offset`)・**正確な件数** (既存 `/events` は概算しか返さないので、**dead-letter 一覧と同じく `_count` reduce を持つ view** にする)が正しい | 認可を外すと落ちる / 並び順を変えると落ちる / pagination・件数を外すと落ちる (「一覧できる」だけでは、これらを戻しても緑) |
| 14 | **有限の保持期間を設定したとき**、4 つを対照する: ⑴ `unresolvedAtMs` が期限切れの `UNRESOLVED` は **purge される**、⑵ **`intentOpenedAtMs` は期限切れだが `unresolvedAtMs` は新しい** `UNRESOLVED` は **残る**、⑶ 同じだけ古い `CAPTURE_INTENT` は**直接 purge されず先に sweep される**、⑷ **既定 (無期限) では**期限切れの `UNRESOLVED` も**残る** | ⑵ が無いと起点を `intentOpenedAtMs` に戻しても緑 / ⑷ が無いと既定を有限に戻しても緑 |
| 15b | **`LineageJournalStore` が注入されているのに intent を書けなかったとき、取込が失敗する。** store が注入されていない構成 (単体テスト) では**素通りする** | fail-closed の条件を外すと落ちる / store 不在でも失敗させると既存テストが落ちる |
| 15c | ③ が書いた行に **`occurredAt` があり、retention の対象になる** | 落とすと落ちる |
| 15d | **消えていた行を完成させようとした取込が、警告を返す** (復活させない) | 黙って捨てると落ちる / 復活させると落ちる |
| 15e | 新 view は **`_design/lineage` を書き換えない** | 同じ ddoc に足すと落ちる |
| 15 | `lineage.mode` が `disabled` / `direct` のとき、**intent を書かず、取込の挙動も変わらない** | **これは判別テストではなく安全性の回帰テスト** — 機能ごと戻しても緑になる。そう明記して置く |
| 16 | **追跡対象の変更をせずに正常終了する経路 (dry run / 各種 skip / 添付ゼロ) で intent が作られず、かつ同じ入口に変更を伴う要求を与えると intent が作られる** — **不在だけを見ると、機能ごと消しても緑**になるので必ず対にする | 入口で開くと前半が落ち、遅延 open をやめると後半が落ちる |
| 17 | 追跡対象の変更が 1 つでも `FAILED` / `INDETERMINATE` なら **`CAPTURED` にしない** | 述語を緩めると落ちる |

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

## 6.10 B1〜B5 への決定 (2026-08-20、オーナー判断)

**オーナーは選択肢 1 を選んだ — fail-closed を維持し、その代価を引き受ける。**
以下はその帰結として決めたこと。**§6.8 と衝突する箇所はこちらが優先する。**

### B5 → ① は例外で返す。代価は DLQ 側で手当てする

**値で返してはいけない。** `ExternalIngestResult.error(...)` は DLQ に載らず
(`saveToDlq` は `catch (Exception)` からしか呼ばれない)、orchestrator は
**バッチ末尾でチェックポイントを無条件に前進させる**ので、**その項目は二度と取得されない**
— `FetchSupport:139-153` が自ら "permanent loss of that item's content" と書いている。
**証拠を守るために原本を失うのは本末転倒である。**

→ **① の失敗は例外にする。** DLQ に載り、再取得の対象になる。引き受ける代価は 2 つ:

| 代価 | 手当て |
|---|---|
| **取込バイトが `nemaki_conf` に暗号化なしで残る** (`IngestJobService` が添付として保存)。ACL も保持方針も無い | **本 PR の範囲に含める** — DLQ ペイロードの保護と保持期限。設計は別紙に切る。**payload は「暗号化か破棄」で実装済み。request JSON の側door (note の `contentBase64`) も 2026-08-23 に閉鎖** — bytes は暗号化 attachment 経路のみ、request JSON は常に byte-free (`IngestJobService.buildDlqRecord`)。replay は本文のみ復元し、添付は次ポーリングの skip-retry が取り直す |
| `isTransientError` に journal 障害の分類が無く `[permanent]` になって自動再試行されない | **本 PR で分類を足す** — `nemaki_lineage` 起因は transient |

### B1 → fail-closed の適用条件は「journal store の協調者が在ること」

「既存のテストが到達しない位置に置く」は**誤りだった** — `SpringContext` は単体テストで
null で、変更を起こすテストが 6 クラスあり、しかも**静的なのでクラスの実行順で結果が変わる**。

→ **判定するのは、`LineageJournalStore` の協調者が注入されている場合だけ。**
注入されていなければ「lineage は配線されていない」として素通りする。
既存の `ingestLineageEmitter != null` ガードと同じ形で、**テスト専用フラグではない**。
単体テストは store を配線しないので素通りし、本番は配線されているので効く。

**AC 15b を書き直す** — 「モードを解決できないとき失敗する」ではなく、
**「store が在るのに intent を書けなかったとき失敗する」**。

### B4 + M1 → ③ は intent 行を自分の型のまま完成させる。イベントは emitter が書く

**当初「③ が行を `lineage_event` にする」としたが、2 つの理由で成立しない**:

1. **write-schema barrier を破る。** 本番の emitter は `Present(2)` と `Indeterminate` では
   **spool に逃がす** (`JournaledLineageEmitter:77-88`)。javadoc は
   「**spool が無いことは v1 を書く許可ではない**」と明記している。③ が v1 を直接書くと、
   schema 2 に移行済みの環境で**取込イベントの v2 表現を消す**
2. **保持期間が逆転する。** `lineage_event` になれば既存 purge の対象 (既定 90 日、
   `lineage.targets` 未設定なら即 purge 可) — **未解決は無期限、完成した証拠は 90 日**になる

→ **intent 行は最後まで `lineage_capture_intent` のまま。**
③ は `captureState = CAPTURED` にして証拠を書き込む。**lineage イベントは従来どおり
emitter が barrier を通って書く** (v1 append か spool か は barrier が決める)。

**帰結として良くなること**:

- **`eventKey` の重複判定が生き残る** — emitter 経路が変わらないので、
  §5 が「3 つ目の挙動変更」として価格を付けた重複配送は**起きない**
- **§5.0.1 の「置き換える」も撤回** — 既存の発行は残る。部分失敗の取込が
  イベントを 1 つも残さなくなる問題も起きない
- **`occurredAt` / `schemaVersion` / `sequenceNumber` / `publishStatusByTarget` は
  ③ の関心事ではなくなる** (§6.8-5 の欄一覧は emitter 側の話に戻る)
- **`CAPTURED` は本当に終端になる** — `by_occurred_at` に入らないので purge されない。
  intent 専用の retention (§6) だけが効く

**代わりに残る限界**: 発行そのものは従来どおり fail-open。
**本 PR が閉じるのは「変更の前に intent がある」までで、「イベントが必ず配送される」ではない**
(それは既存の outbox の担当)。

### B2 → scope は 4 つのヘルパの中まで渡す

`applySourceMetadata` / `applyNoteMetadata` / `applyArchetypeMetadata` / `applySourceAcl` は
**書いても書かなくても `null` を返す**ので、外から「変更したか」が分からない。

→ **scope をこの 4 つに明示的に渡し、`ensureIntentOpened()` を実際の
`contentService.update` の直前で呼ぶ。** 併せて**「何もしなかった」を「成功した」と別に返す**
(§6.8-2 の `MutationOutcome` を戻り値にする)。これをやらないと規則 1 と AC 16 は両立しない。

### B3 → tracker は警告を読まない。型付きで配線する

**「tracker は警告を拾えばよい」を撤回する。** `mergeChildWarnings` が子の警告を親のリストに
畳み込むので、警告では**帰属を表せない**。しかも AC 10b⑵ が子に帰属させたい relationship は
**親のフレームで作られ親のリストに入る**。

→ **`MutationOutcome` を明示的に配線する。** `applyAclSyncPolicy` /
`removeExistingRelationships` / `replace` の削除は、警告文字列とは**別に** outcome を
scope に記録する。子の処理で作る relationship は、親のフレームで呼んでいても
**子の scope に記録する**。

### M2 → 厳格なプリミティブを使う

`readRawStrict` (`:1530`) / `updateStrictCas` (`:1569`) / `createIfAbsent` (`:643`) が
**既にストアに在る**。寛容な `CloudantClientWrapper.get` / `update` を使う理由は無い。

→ ① は `createIfAbsent`、③ は `updateStrictCas`、読み直しは `readRawStrict`。
これで §5.1 の「衝突したら読み直して再判定」が実装可能になり、
**§6.8-6 の「not-found と障害を区別できない」も解消する** — 404 だけが `null` で、
それ以外は投げる。**警告文言も「消えていた」と「読めなかった」を分けて書ける。**

### そのほか

- **§6.5-4 の leader についての記述は削除する** (§6.8-4 が置き換えると宣言したまま残っていた)
- **§5.0.1 の「2 つの挙動変更」は撤回** (B4 により置き換えそのものを止めた)
- **`MutationOutcome` の三値目は `INDETERMINATE`** — §5.0 と AC 17 の `UNKNOWN` を直す
- **AC 2 / 5 / 6 も判別できない**ので、11 / 15 / 22 と同じく明記する
- **§8 の「すべて本番の呼び出し経路を通すこと」は §6.9-13 と矛盾** — store 層の AC は
  そう明示する
- **AC 1 に正のコントロールを付ける** (「変更が無い」は無関係な理由でも通る)
- **① のフィールド集合と、未解決一覧の表示項目を決める** — 少なくとも
  `intentId` / `repositoryId` / `connectorId` / `sourceSystem` / `sourceObjectId` /
  `requestId` / `intentOpenedAtMs`。これが無いと一覧は時刻の羅列になる

---

## 9.6 4 巡目の判定 (2026-08-20) — **fail-closed の受け皿が存在しない**

§6.10 (オーナー判断=選択肢 1) を 2 体が攻撃した結果。**今回は設計の誤りではなく、
前提としていた仕組みが実在しないことが判明した。**

### B5 — DLQ は §4 が対象と定めた場所に届かない

| | |
|---|---|
| `executeMailImport` | `:389` で例外を**値に戻す** → catch-all に届かない → **DLQ 無し** |
| `executeNoteImport` / `executeBusinessRecordImport` / `executeChatContextImport` | **top-level の try が無い** → orchestrator へ伝播 |
| 受け側の orchestrator | **Slack / Chatwork / Gmail / IMAP / M365Mail / Salesforce には `saveToDlq` が 1 箇所も無い** (11 中 5 のみ、しかも download 失敗専用) |
| cloud drive の canonical 経路 | `executionMode="manual"` なので **DLQ から除外される** — §3.2 が保証対象と名指しした経路 |

→ **例外化で DLQ が得られるのは `execute()` の try の中で起きた失敗だけ。**
§4 は「intent は wrapper の後処理まで覆う」と決めたが、**その後処理こそ受け皿が無い**。

### B5 — 代価の手当ても成立しない

- **`isTransientError` を直しても何も変わらない。** 消費者は**文字列の prefix 連結のみ**で、
  `IngestDeadLetterRecord` に `retryable` フィールドは無く、
  **自動再試行はコードベースに 1 箇所も存在しない**。
  「auto-retry loops を避けるため permanent にする」という既存コメントは**現時点で既に虚偽**
- **書いていなかった本当の代価**: `saveToDlq` は毎回新しい id を採番するので**再試行が
  重複排除されない**。circuit breaker は毎サイクル half-open に戻される。
  purge も TTL も無い。一覧は **200 件で打ち切り**、201 件目以降は見ることも消すこともできない。
  **journal が落ちている間、5 分ごとに 1 バッチ分ずつ `nemaki_conf` に積み上がる**

### B1 — ガードが本番で不活性 (恐れた向きとは逆)

テスト側の主張も本番配線の主張も**どちらも真だった**。しかし
**`CouchLineageJournalStore` は無条件の `@Service`** なので、「store が注入されている」は
**既定の `disabled` を含む 100% のデプロイで真**。`store != null` だけを述語にすると
**既定構成が intent を書き始め fail-closed し始める** — §7 と AC 15 に真っ向から反する。

正しい述語は **`mode == JOURNALED && store != null`** で、**弁別を担うのは mode 側**。
そしてその mode には §6.8-1 の無期限キャッシュの穴があり、**運用者に見える信号がゼロ**
(`DISABLED` は唯一の benign ケースとして警告すら出ない)。

### B4 — 3 つの帰結が未処理 + `CAPTURED` が不滅になった

- **AC 9 が「イベントの無い `CAPTURED`」を要求している。** dedupe skip は発行 (`:1680`) の
  前に return するので、wrapper の後処理だけが動いた取込は **`CAPTURED` かつ配送物ゼロ**
- **`eventKey` の重複判定が `CAPTURED` を嘘にする。** `append` は重複時に黙って返り、
  loss も報告しないので、**N 個の `CAPTURED` に対しイベント 1 個・警告なし**
- **発行失敗と `CAPTURED` の関係が未定義。** §5.0 の allowlist に発行が無いので、
  「provenance was NOT recorded」の警告と `CAPTURED` が**同居できる**
- **`CAPTURED` がどの retention view にも入らない** → **不滅**。
  B4 が直したはずの逆転が、今度は高頻度側で鏡像になった。**AC 15c は B4 と両立しない**

### B2 / B3 / M2

- **B2 の 4 つは両端が誤り** — `applySourceMetadata` は内部で決めておらず (無条件に書く)、
  しかも**自分の結果ではなく ACL のエラーを返す**。さらに**5 箇所足りない**
  (`applyMessageMetadata` / capture window の**ヘルパですらない直書き** / `applyChatCapturedAt` /
  `createDirectRelationship` / `applyAclSyncPolicy`)。B2 と B3 が**別の ACL ヘルパを指している**
- **B3 は実装可能だが §4 規則 3・4 が禁じている** — 3 つの対象箇所には
  `executeNoteAttachment` に相当する子操作が**存在しない**ので、規則が所有者を定めていない
- **M2 は到達不能** — `createIfAbsent` は **private**、3 つとも `LineageJournalStore` に
  **無い** (`LineageStoreSupport` は package-private)。**M2 と B1 は現状の書き方では両立しない**

### 結論

**閉じていないのは設計ではなく、周囲の前提である。** fail-closed を安全にするには、
本 PR の前に少なくとも 3 つの別作業が要る:

1. **公開入口に一貫した失敗経路を作る** — mail は値に戻し、note/record/chat は catch が無く、
   orchestrator の 6/11 に DLQ が無い
2. **DLQ を実際の受け皿にする** — 重複排除・purge・200 件超のページング・暗号化
3. **lineage mode を観測可能にする** — 無期限キャッシュの穴と、`disabled` が無言である問題

---

## 9.5 独立レビュー 2 体の判定 (2026-08-20) — **現状では実装不可**

視点を分けた 2 体 (§6.8 の事実確認 / 設計全体への攻撃) が、独立に同じ結論に達した。

### 実装を止めている 5 件

| # | |
|---|---|
| **B1** | **§6.8-1-3 が事実として誤り。** 「既存のテストが到達しない位置に置く」と書いたが、**到達する**。`SpringContext.getApplicationContext()` は単体テストでは null で、`execute()` を通して実際に変更を起こすテストが **6 クラス**ある。fail-closed をそこに置くと全部赤になる。しかも `SpringContext` は**静的**で他のテストが set/reset するので、**クラスの実行順で結果が変わる**。**AC 15b も §6.8-1 が撤回した挙動を要求したままで矛盾している** |
| **B2** | **`ensureIntentOpened()` が「内部で書くかどうかを決める」4 つのヘルパの中を見られない。** `applySourceMetadata` / `applyNoteMetadata` / `applyArchetypeMetadata` / `applySourceAcl` は、書いても書かなくても `null` を返す。よって「変更しないなら intent も作らない」(規則 1 / AC 16) が**この機構では達成できない** — 何も書かない取込に intent 行ができ、`UNRESOLVED` として残る |
| **B3** | **AC 7 と AC 10b⑵ が両立しない。** AC 7 は「tracker は警告を拾えばよい」と言うが、`mergeChildWarnings` が**子の警告を親のリストに畳み込む**ので、警告では帰属を表せない。しかも AC 10b⑵ が子に帰属させたい relationship は**親のフレームで作られ親のリストに入る**。型付きの `MutationOutcome` を明示配線するしかなく、そのとき AC 7 の「配線し直す必要は無い」が偽になる |
| **B4** | **③ が v1 を無条件に書く前提が、write-schema barrier と spool を無視している。** 本番の emitter は `Present(2)` と `Indeterminate` では **spool に逃がす**。③ をそのまま書くと、emitter の javadoc が明示的に禁じる「**spool が無いことは v1 を書く許可ではない**」を破り、schema 2 に移行済みの環境では**取込イベントの v2 表現を消す** |
| **B5** | **fail-closed の実際の代価が §7 に書かれていない。** ① が値で返すと DLQ に載らず、orchestrator は**チェックポイントを無条件に進める**ので**その項目は二度と取得されない** (`FetchSupport:139-153` が自ら「permanent loss」と書いている)。例外で返すと DLQ に載るが、**取込バイトが `nemaki_conf` に暗号化なしで**残り、しかも `isTransientError` に journal 障害の分類が無いので `[permanent]` 扱いで再試行もされない |

### そのほか重いもの

- **`CAPTURED` → 削除が遷移表に無く、保持期間が逆転している。** ③ が `type` を `lineage_event`
  にする以上、完成行は既存 purge の対象になる (既定 90 日、しかも `lineage.targets` 未設定なら
  即 purge 可)。**未解決は無期限、完成した証拠は 90 日**という逆転。
- **`readRawStrict` / `updateStrictCas` / `createIfAbsent` が既に在る。** §6.8-6 の
  「not-found と障害を区別できない」は、**寛容な方のプリミティブを選んだ結果**であって
  ストアの制約ではない。厳格な方を使えば §5.1 の CAS も実装可能になる。
- **§6.8-1 の穴は書いたより大きい。** 空の設定は**永久キャッシュ**される
  (`configCache` は `eternal: true`)。起動時に `nemaki_conf` が一瞬落ちただけで、
  **その JVM の残りの生涯にわたって** lineage が無効として読まれ続ける。
- AC 2 / 5 / 6 も判別できないが未表示。§8 の「全 AC を本番経路で」は §6.9-13 と矛盾。
  AC 1 に正のコントロールが無い。① のフィールド集合と一覧の表示内容が未定義。

### B1 に対する具体的な解

レビューの提案が使える: **fail-closed の適用条件を「journal store の協調者が在ること」にする**。
単体テストは store を配線しないので**素通り**し、本番は配線されているので**効く**。
既存の `ingestLineageEmitter != null` ガードと同じ形で、テスト専用フラグではない。

---

## 9.7 前作業 3 件の完了 (2026-08-21)

§9.6 が「本 PR の前に少なくとも 3 つの別作業が要る」と結論した 3 件は**すべて済んだ**。
これで outbox 本体に着手できる。

| # | 前作業 | commit | 実際に直したもの |
|---|---|---|---|
| 1 | 公開入口に一貫した失敗経路 | `fcd196442` | 入口失敗時に `committedObjectId` と蓄積警告が捨てられていた。note/record/chat を `…Internal` + 薄い wrapper に割り、`EntryFailureState` で持ち回す |
| 2 | DLQ を実際の受け皿に | `c040380a2` | 重複排除 (id を**元項目の同一性**から導出)・ページング・保持期間 (`POST /dlq/purge`)・**取込バイトの暗号化**。DLQ の無かった orchestrator 6/11 に配線 |
| 3 | lineage mode の観測可能化 | `a7c9d8217` | 読めなかった設定が**無期限キャッシュ**され、`disabled` として**無言で**返っていた |

### 途中で見つかった製品バグ (前作業とは別に修正済み)

前作業の過程で、設計の前提として置いていた記述が**偽**だと分かった箇所が 4 件あり、
いずれも独立したバグとして直した (dry run が 4 入口で実際に書き込む / ACL 適用が古い
`_rev` で決定的に失敗する / relationship 除去が無限ループする / note の添付 skip 数が
合わない)。**設計書の行番号はこれらの修正で 80 行以上ずれている** — 冒頭の注記を見ること。

### 前作業 3 で分かった、設計に効く事実

- **`configurationReadFailed()` は「読めたか」しか言わない。** どちらだったのか
  (落ちていたのか、意図的に切ってあるのか) は区別できない。§6.8-1 の穴のうち
  **永続化する側**は塞いだが、**「取込の時点で lineage が有効だったと言えるか」は
  依然として言えない**。outbox の intent 行が要るのはまさにここ。
- **失敗した読みをキャッシュしないので、CouchDB 障害中は読みが素通りする。**
  stale を返し続けるより正しいが、**読みの頻度に上限は無い**。outbox の掃引が
  設定を読む間隔を決めるときはこれを前提にすること。

### 残っている実装項目 (§6.10 の決定に沿って)

1. `MutationOutcome { SUCCEEDED, FAILED, INDETERMINATE }` の型付き配線 (B3)
2. `CaptureMutationScope` と、内部で書くか決める 9 箇所への引き回し (B2)
3. 厳格プリミティブ (`readRawStrict` / `updateStrictCas` / `createIfAbsent`) を
   `LineageJournalStore` に出す (M2)
4. intent フィールド集合 (§5.2)、`_design/lineage_capture` の view、掃引、一覧、保持期間
5. fail-closed の適用条件を「journal store の協調者が在ること」にする (B1 の解)

---

## 9.8 実装後レビュー 1 巡目 (2026-08-21) — P0 1 件 / P1 4 件 / P2 1 件、すべて対応

配線を入れた直後に独立レビューを掛け、**6 件すべて実在した**。1 件は自分の memory に
書いてある罠 (`fail-open-boundary-trap`) をそのまま踏んでいる。

| | 指摘 | 何が起きていたか | 対応 |
|---|---|---|---|
| **P0** | fail-closed の例外を `catch (Exception)` が飲み、そのあと変更する | `ensureIntentOpened()` は投げるが、`replace` の削除・`applySourceMetadata`・`createDirectRelationship`・`removeExistingRelationships` は**内側に catch がある**。`replace` は警告にして**置換の作成に落ちていた** | 各 catch の**内側**に `catch (CaptureIntentFailedException) { throw; }` を 6 箇所。加えて **refused を scope に latch** し、`withCaptureOutcome` が結果をエラーにする (guard の取りこぼしに対する二重化) |
| **P1** | wrapper 後処理が intent も記録も持たない | `applyMessageMetadata` / `applyNoteMetadata` / `applyArchetypeMetadata` / `applyChatCapturedAt` は失敗が warning になるだけで、**メタデータが失敗しても `CAPTURED`** になっていた。dedupe skip 経路では intent が一切開かない | 各所で `ensureIntentOpened()` → `recordWrapperUpdate(...)`。失敗は `INDETERMINATE` (どこで落ちたか判定できないため) |
| **P1** | 子操作が公開 `execute()` で自己完了し、relationship が親に載る | 生 `.eml`・mail 添付・note 添付が**relationship の前に `CAPTURED`** になり、失敗の帰属が親に行っていた (規則 4・5 違反) | 子ごとに未 open の scope を作り、**relationship まで同じ scope**、所有者が完成させる |
| **P1** | `disabled` / `direct` でも intent を書き fail-closed する | `newCaptureScope` はストアの有無しか見ておらず、既定 `lineage.mode=disabled` でも取込のたびに `nemaki_lineage` を触っていた | `CaptureIntentStore.appliesTo(repositoryId)` を追加。`getModeForRepository` を **open 時に一度だけ**見て latch (AC 19)。完成時は**見ない** (AC 20) |
| **P1** | 未解決一覧の view 障害が「一件も無い」に見える | `queryRawView` が全例外を空リストに潰していた。**境界の可視性そのものが、障害時に「問題なし」になる** | `LineageViewUnreadableException` を投げる。一覧は 500、掃引と retention はその回を中止 |
| **P2** | リポジトリ絞り込みが `limit`/`skip` の**後** | 別リポジトリの新しい行がページを押し出し、「無い」と表示される | `[repositoryId, intentOpenedAtMs]` の複合キー view を足し、**CouchDB 側で**絞る |

### 判別しなかったテストを 1 件見つけた

`queryRawView` の変更に対する negative control が**通ってしまった**。capture 側のテストは
`FakeSupport` が自分で例外を投げるので、**製品の `queryRawView` を一度も通っていなかった** —
また代用品を検証していた。`LineageViewReadFailureTest` を実物に対して書き直し、
戻すと落ちることを確認した。

## 9.9 実装後レビュー 2 巡目 (2026-08-21) — 視点を分けた 2 体

正しさ担当と運用担当に分けた。**両方とも実在する欠陥を出した**。

### 正しさ側 — CRITICAL 1 / HIGH 2 / MEDIUM 6

| | 指摘 | 対応 |
|---|---|---|
| **F1 (CRITICAL)** | `ensureIntentOpened` が `isActive()` を `appliesTo` **より先**に呼んでいた。`isActive()` は `nemaki_lineage` を provision するので、(a) **全リポジトリが `disabled` の環境でも取込のたびに DB を作り**、(b) provision が失敗すると scope が丸ごと inert になって**文書を作り成功を返し**、(c) 途中で false→true に転じると**intent より前に作られた文書に対して `CAPTURED`** が立った | **順序を逆に**した。モードを先に見て latch し、適用されるのに store が届かないなら**拒否**する (沈黙しない) |
| **F8 (HIGH)** | note の `files_and_body` 添付だけ**公開 `execute()` のまま**で、relationship の前に `CAPTURED` になり、しかもその relationship を**親に**記録していた (規則 4・5 違反)。1 巡目で 3 箇所直して 4 箇所目を落としていた | 子 scope を持たせ、relationship まで同じ scope で、所有者が完成させる |
| **T1 (HIGH)** | **wrapper と子 scope の層にテストが 1 つも無かった。** `IngestCaptureBoundaryTest` は素の `execute` しか叩いていない。F3・F4・F5・F7・F8 が全部この穴の中にあった | `IngestCaptureWrapperScopeTest` を新設 (8 件) |
| F3 | chat の capture window の**直書き**が追跡対象から漏れていた (§9.6-B2 が名指ししていた 5 箇所の 1 つ) | open + record |
| F4 | 「作成後に読み戻せない」早期 return が**何も記録せず**、`CAPTURED` になっていた | `INDETERMINATE` を記録 |
| F5 | `applyNoteMetadata` / `applyArchetypeMetadata` は**書いても書かなくても null** を返すので、「何もしない」が `SUCCEEDED` として記録され、しかも**行き先の無い intent** が開いていた | `willWrite...()` 述語を helper 側に一元化し、**書くときだけ** open して record する |
| F6 | `removeExistingRelationships` の**外側の catch** が、内側の再送出をもう一度飲んでいた | 外側にも再送出を足した |
| F7 | note 添付ループに guard が無かった (mail 側には在った) | 足した |
| F9 | CAS を失うと**再試行せず** `NOT_COMPLETABLE`。掃引に負けただけの完走取込が永久に未解決一覧に残る | 状態を読み直して、まだ完成可能なら**最大 3 回**再試行 |

### 運用側 — HIGH 2 / MEDIUM 12

| | 指摘 | 対応 |
|---|---|---|
| **O7 (HIGH)** | design document が**未配備**だと `queryView` が `NotFoundException` を null に潰し、`200 + entries: []` になる。**「view が無い」が「未解決は無い」に見える** — この機能が防ぐはずのもの、そのもの | null は「読めなかった」として投げる |
| **O15 (HIGH)** | runbook が「leader election を有効にすれば掃引の費用が下がる」と書いていたが、**この掃引は leader gate を見ていない** | runbook を訂正し、`MULTI-REPLICA-DEPLOYMENT.md` の R2 にも注記 |
| O8 | 設計が「1 回の PUT にまとめること」と明記していたのに **view ごとに PUT** していた (5 回 = 5 世代の索引破棄) | `putDesignDocumentIfChanged` で 1 回に。**内容が同じなら書かない** |
| O13 | 未解決一覧の**読み取りが `nemaki_lineage` を作っていた** | 読み取り専用経路に変更 |
| O2 | 掃引の batch に上限が無く、設定 1 つで OOM に届いた (一覧側には上限が在った) | 2000 で頭打ちに |
| O5 | 「取込 1 件 = 1 行」は**過小**。子操作はそれぞれ行を持つ (添付 5 件のメールで 7 行) | 3 箇所の記述を訂正 |
| O11 / O12 | runbook の「200 + 空 = 未解決なし」が初回索引構築中は偽。実効モードの確認手順が**グローバル設定しか返さない**エンドポイントを指していた | 両方訂正 |
| O14 | `MULTI-REPLICA-DEPLOYMENT.md` にこの掃引が載っておらず、R2 の確認が**偽の安心**を与えていた | 注記を追加 |
| O16 | キー名のハイフンが `PropertyManager` の環境変数経路を壊していた (`LINEAGE_CAPTURE-BOUNDARY_...` は export できない) | **未対応** — 下記 |
| O1 / O3 / O4 / O6 / O17 / O18 / O19 | 掃引速度の上限、jitter が実質固定、余分な probe、成長を観測する手段が無い、interval は再起動が要る、properties の分岐、設計書の旧キー名 | 速度上限・interval・成長は runbook に明記。設計書の旧キー名は修正。**残りは未対応** |

### 判別しなかった negative control 3 件

いずれも**私のテストが弱かった**もので、製品の欠陥ではない。

- 添付の scope: 「行が別に在るか」を見ていたが、**公開 `execute()` でも行は別に在る**。差が出るのは*完成の順序*と*relationship の帰属*なので、**link を失敗させて「子が未完成・親は完成」を見る**形に書き直した
- null の `ViewResult`: `assertThrows(RuntimeException)` は**NPE でも通る**。元の形に戻す control を取り直した
- `queryRawView`: capture 側のテストは `FakeSupport` が投げていて**製品コードを通っていなかった** (1 巡目で発見・修正済み)

### 残していた 3 件も同じ巡で対応した

- **O16 (ハイフンと環境変数)**: キー名を 3 度目に変えるのではなく、**`PropertyManager` 側で
  `-` を `_` に畳む**ことにした。Spring の relaxed binding が既にそうしているので、
  `@Value` 経路と規則が揃う。**既存キーへの影響は無い** — 変更前はハイフンを含む名前が
  export 可能な変数に一致することが原理的に無かったため、依存しようが無い
- **O6 (成長の観測手段)**: `GET /v1/admin/capture-intents/counts` を足した。
  `nemaki_lineage` の `doc_count` は lineage イベント・dead letter・v2 行を混ぜて数えるので
  **capture 行の量には答えられない**。走査上限に達したら `truncated: true` を返し、
  **下限を合計として提示しない**
- **O10 (新 ddoc の署名検査)**: `captureViewViolations()` を足し、
  `viewSignatureViolations()` から呼ぶようにした。未配備・view 欠落・map の差異を報告する

### なお未対応

- **AC 13 の exact count** — 一覧は `count` にページ内件数を返す。`_count` reduce view による
  正確な総数ではない (`UnresolvedPage` の javadoc に明記)。`/counts` は状態別の概数を返すが、
  こちらも走査上限つき
- **O1 (掃引速度の上限)** — 1 回 200 行 × 5 分、レプリカを増やしても並列にならない。
  runbook に所要時間の見積もりを書いた
- **O3 (jitter が実質固定)** — `System.identityHashCode` は同一バイナリでは同じ値を返しうる。
  0〜59 秒は 300 秒周期の 20% でしかないので、効果自体が小さい

## 9.10 実装後レビュー 3・4 巡目 (2026-08-21)

### 3 巡目 — HIGH 3 / MEDIUM 5

| | 指摘 | 対応 |
|---|---|---|
| **H1 (データ損失)** | retention の削除が `_rev` 条件付きでない。`CloudantClientWrapper.delete` は**渡した rev を捨てて最新を取り直す**ので、走査が読んでから削除するまでに行が `CAPTURED` に変わると**完成した証拠を消す**。`UNRESOLVED` だけを対象と決めた設定が、成功した取込の証拠を消し、しかもどこにも記録されない | `deleteIfRevisionMatches` を足し、`deleteRaw` をそれに切り替え。409 は「取り損ねた」で false、404 は既に無いので true |
| **H2** | `appliesTo` が「無効」と「設定が読めなかった」を区別していない。区別する `configurationReadFailed()` は**前作業 3 で既に在り、兄弟の emitter 経路が使っている**。強い保証を名乗る側が古い fail-open な側より弱い判定をしていた | 三値 `Applicability { APPLIES, NOT_APPLICABLE, UNDETERMINED }` に (4 巡目で扱いを再訂正、下記) |
| **H3** | `PropertyManagerEnvKeyTest` が**製品ではなく自前のコピー**を検証していた。`PropertyManager` を戻しても 4 件とも緑。§9.8 で反省した直後の commit で同じ失敗形が再発 | 導出を `PropertyManager.environmentVariableNameFor` に切り出し、テストが製品を呼ぶように |
| M1 | `/counts` が数えるためだけに `include_docs=true` で最大 30 万文書を実体化 | `countRawView` を足し、文書を取らずに数える |
| M3 | CAS 再試行を使い切ったとき「状態が悪い」と**観測していない原因を断定**していた | `CONTENDED` を足し、競合と状態問題を分けた |
| M4 | wrapper のテストが note だけ。**`checkOut` の前に intent が開くことを誰も確かめていない** | **未対応** (下記) |
| M5 | 実行中の取込が `UNRESOLVED` に見える件が §9.9 の未対応にも載っていなかった | **未対応** (下記) |

### 4 巡目 — P0 1 / P1 1 / P2 1

| | 指摘 | 対応 |
|---|---|---|
| **P0 (起動不能)** | capture の store を**具象型と 2 つのインタフェース型で 3 つ bean 登録**していた。`CouchCaptureIntentStore` は両インタフェースを実装するので、各インタフェース型に**候補が 2 つ**できる。`@Autowired(required = false)` は**不在は許すが曖昧さは許さない**ので、フィールド名が bean 名と一致しない注入点 (`CaptureIntentSweeper.maintenanceStore` / `CaptureIntentController.maintenanceStore`) が `NoUniqueBeanDefinitionException` で落ちる。`serviceContext.xml` はルート context を再 refresh して読むので、**機能の劣化ではなく WAR が起動しない** | bean を **1 本**に。両インタフェースは同じ instance を見る (それが元々の要件) |
| **P1** | H2 の直しが規則 3 を破っていた。`appliesTo` は **DISABLED がどこから来たかを問わず** `configurationReadFailed()` を見るので、`-D` や env で `disabled` を固定していても設定 DB が読めなければ拒否になる。**lineage を一度も有効にしていないデプロイの全取込入口がエラーを返す** | **UNDETERMINED を拒否にしない**。§6.8-1 決定 2 が既に「穴として書く」と決めており、兄弟の emitter 経路も warning に留めている。変更は行わず**警告を返す** — 黙らないことが元の改善 |
| **P2** | `UNDETERMINED` を観測するテストが「どの enum を返すか」だけ。分岐を丸ごと消しても緑 | scope と取込入口の両方で、**拒否せず・何も書かず・警告が出る**ことを固定 |

### この 2 巡で得た教訓

**単体テストは Spring の配線を証明しない。** 5286 件 pass は「bean が解決できる」ことの証拠に
なっていなかった。全テストが setter で collaborator を挿しており、context を一度も組み立てて
いなかったためである。`CaptureWiringResolvesTest` を足し、壊れた形に戻すと
`NoUniqueBeanDefinitionException` で落ちることを確認した。

**「区別できないときは止める」は、止める範囲を間違えると保証 3 を壊す。** H2 の直しは
正しい問題を指していたが、**判定が失敗した読みに依存していない場合まで巻き込んだ**。
設計が既に「穴として書く」と決めていた範囲を、レビューの勢いで越えていた。

### 未対応 (この時点で残るもの)

- **AC 13 の exact count** — 一覧の `count` はページ内件数。`/counts` は走査上限つきの概数
- **O1 掃引速度** — 1 回 200 行 × 5 分。レプリカを増やしても並列にならない
- **O3 jitter** — `identityHashCode` は同一バイナリで同じ値を返しうる
- **M4 wrapper テストの範囲** — mail / chat / business record の入口は未固定のまま。
  ただし設計 §5.0 が名指しした **`checkOut`** は固定した (`intentPrecedesCheckOut` /
  `unwritableIntentStopsCheckOut`)。version-up 経路では checkOut が最初の変更であり、
  さらに **checkOut が成功して checkIn が走らないと文書が CHECKED OUT で固まり、
  同じ項目の以後の取込が永久にそこで落ちる**ため、優先して閉じた
- **M5 実行中の取込が `UNRESOLVED` に見える** — 既定 15 分に対し fetch timeout は 30 分。
  §6.9-3 が lease か heartbeat を求めていたが実装していない

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
- **既存バグ 2 件は修正済み** (dry run / ACL、+ 派生して dedupe 段の 2 件も)。
- **設計判断 6 件を §6.8 に書いた後、独立レビュー 2 体が「現状では実装不可」と判定** (§9.5)。
  **実装は §9.5 の B1〜B5 を片付けてから。**
