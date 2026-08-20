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
① intent を nemaki_lineage に書く (captureState = CAPTURE_INTENT)
     ↓ 失敗したら取込を中止する。文書はまだ 1 つも作られていない (fail-closed)
② 業務操作を行う (既存のコミット群。ここは変えない)
     ↓
③ 業務操作が全部終わり、変更操作がすべて成功してから、同じ intent 行を完成させる
     (captureState = CAPTURED、objectId・content 状態・snapshot・sequence を付ける)
```

**得られる保証は 1 つだけ** (§7 の「主張しないこと」もこの 1 点を基準に読む):

> **実効モードが `journaled` の構成で**、canonical な 5 つの公開入口
> (`execute` / `executeMailImport` / `executeNoteImport` / `executeBusinessRecordImport` /
> `executeChatContextImport`) が CMIS 文書を変更するときは、
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
| 1 | **intent は「最初の変更操作の直前」で開き、その scope の所有者が完成させる** (root scope は公開入口、child scope は子操作)。** 入口の直後ではない — dry run・空/擬似ファイルの skip・dedupe/idempotency skip・`files_only` で添付が無い/全部 skip、といった**何も変更せずに正常終了する経路**が実在し、そこで intent を開くと**行き先の無い行**ができる (`CAPTURED` にすると objectId が無く完成できず、放置すると 15 分後に `UNRESOLVED` になるが、実行中の主体は「何も変更しなかった」と分かっているので「判定不能」は嘘になる)。**変更しないなら intent も作らない。** dedupe skip の後に wrapper がメタデータを更新する経路では、**その wrapper の変更の直前で開く** |
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
- `ContentService.update` は null を返しうるが、**多くの呼び出し元が確認していない**
  (`ContentServiceImpl:2819`)

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
| 1 | intent の書込に失敗したら取込は**エラーを返し、文書を作らない** | fail-closed を fail-open に戻すと落ちる |
| 2 | ③ が失敗しても行は `CAPTURE_INTENT` として残る (消えない) | 例外で行を消すと落ちる |
| 3 | 期限切れの `CAPTURE_INTENT` が `UNRESOLVED` になる | 走査をやめると落ちる |
| 4 | **遅れて完走した取込が `UNRESOLVED` から `CAPTURED` へ昇格できる** | 終端扱いすると落ちる |
| 5 | 走査は `UNRESOLVED` を **`CAPTURED` にしない**し、文書を探しにも行かない | 探索を足すと落ちる |
| 6 | `CAPTURED` は終端 — 誰も上書きしない | 上書きを許すと落ちる |
| 7 | **変更操作のいずれかが失敗したら `CAPTURED` にしない** (warning に落ちる経路を含む) | 述語を「PUT を試みた」に戻すと落ちる |
| 8 | 5 つの公開入口**それぞれ**で、intent が **wrapper の後処理まで**覆う | どれか 1 つでも `execute()` 内で完成させると落ちる |
| 9 | dedupe skip の**後に wrapper が行った変更が、同じ外側の intent を `CAPTURED` にする** (「内側が終端にしない」だけでは、機能が無くても空虚に緑になる) | 内側で終端にすると落ちる / wrapper の変更を scope の外に出すと落ちる |
| 10 | 添付・生 `.eml` は**1 件ずつ独立した intent** を持つ | 親に相乗りさせると落ちる |
| 10b | **子の後処理の失敗が、その子の intent を未完成のままにする** — ⑴ note の子メタデータ失敗でその子が `CAPTURED` にならない、⑵ mail の子 relationship 失敗でその子が `CAPTURED` にならず**親は完成しうる**、⑶ note `files_and_body` の relationship 失敗が**子に帰属する** (AC 10 は「別々の intent が在る」しか示さず、子が後処理より前に完成しても、relationship を親に付け替えても緑のまま) | 子を早く完成させると落ちる / relationship を親の scope に付けると⑶が落ちる |
| 11 | projection loop は `CAPTURE_INTENT` / `UNRESOLVED` の行を**配送しない** | **既存 view が `type === lineage_event` を要求するため、本 PR の変更を戻しても緑のまま** — これは判別テストではなく**安全性の回帰テスト**である。そう明記して置く |
| 12 | sequence は**完成時にだけ**振られる | intent 時に振ると落ちる |
| 13 | `UNRESOLVED` の一覧が **admin では成功し、非 admin では拒否される**。複数行で **`intentOpenedAtMs` の降順**・**`limit` / `skip`**・**件数**が正しい | 認可を外すと落ちる / 並び順を変えると落ちる / pagination・件数を外すと落ちる (「一覧できる」だけでは、これらを戻しても緑) |
| 14 | **有限の保持期間を設定したとき**、4 つを対照する: ⑴ `unresolvedAtMs` が期限切れの `UNRESOLVED` は **purge される**、⑵ **`intentOpenedAtMs` は期限切れだが `unresolvedAtMs` は新しい** `UNRESOLVED` は **残る**、⑶ 同じだけ古い `CAPTURE_INTENT` は**直接 purge されず先に sweep される**、⑷ **既定 (無期限) では**期限切れの `UNRESOLVED` も**残る** | ⑵ が無いと起点を `intentOpenedAtMs` に戻しても緑 / ⑷ が無いと既定を有限に戻しても緑 |
| 15 | `lineage.mode` が `disabled` / `direct` のとき、**intent を書かず、取込の挙動も変わらない** | **これは判別テストではなく安全性の回帰テスト** — 機能ごと戻しても緑になる。そう明記して置く |
| 16 | **何も変更せずに正常終了する経路 (dry run / 各種 skip / 添付ゼロ) で intent が作られず、かつ同じ入口に変更を伴う要求を与えると intent が作られる** — **不在だけを見ると、機能ごと消しても緑**になるので必ず対にする | 入口で開くと前半が落ち、遅延 open をやめると後半が落ちる |
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
