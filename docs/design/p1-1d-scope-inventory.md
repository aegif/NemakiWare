# P1-1(d) — 何を引き受け、何を引き受けないか (棚卸し)

(a)〜(c) から「(d) へ」と送ったものが溜まっている。**集めたまま 1 つの設計にすると、
(a) の outbox と同じく前提が偽のまま実装に入る。** 着手前に、仕事の種類で分ける。

> **この文書は棚卸しであって設計ではない。** (d) の設計は、下表で「引き受ける」と決めた
> ものだけを対象に別途書く。

---

## 0. 分類の基準

| 区分 | 意味 |
|---|---|
| **引き受ける** | evidence data model そのもの。「どの事実が、いつ、どの粒度で確定するか」を決めないと着手できない |
| **引き受けない** | モデルを待つ必要がない。別の仕事として今すぐ着手できる、または別 ID の担当 |
| **既に主張済み** | 実装済みで、(d) の対象ではない。再提案しない |

---

## 1. (c) から送った 3 経路 — **一塊にしない**

初稿は 3 つを「型システムに持ち込む必要があるから (d)」で括っていた。**括ると 2 番目と
3 番目がモデル設計の後ろに隠れる。** 仕事が違う。

| 経路 | 実際の仕事 | 区分 |
|---|---|---|
| `cmis:secondaryObjectTypeIds` から型ごと外す | **「どの型が証拠か」**。モデルが「証拠の型」を決めてからでないと、拒否する対象が無い | **引き受ける** |
| 管理 API の型更新 / 古い export の取り込み | この endpoint が保存値を書き換えるのは `updateTypeDefinition` の**無条件コピー 1 行**、import 側は**新規作成時の既定値**だけ (パーサも既存型も経路ではなかった)。CMIS `updateType` という 3 つ目の書き手が別に在る ((c) §5.5) | **引き受けない** — (c) の続き。**2026-08-22 に対応** (§4)。初稿の「欠落時に READWRITE」という診断自体が誤りだった |
| ローリング再起動の時間差 | パッチが走った JVM 以外の**型キャッシュ**。`isApplied` が 1 台だけ走る既知の形 | **引き受けない** — **運用正典の 1 節**。[`docs/MULTI-REPLICA-DEPLOYMENT.md`](../MULTI-REPLICA-DEPLOYMENT.md) に「型パッチを含む版へ上げるときは全レプリカを入れ替えるまで型更新を受け付けない」を書く。**2026-08-22 に書いた** (「型パッチを含む版へ上げるとき」) |

---

## 2. (b) から送った v2 の置き場の残り

| 事実 | 実際の仕事 | 区分 |
|---|---|---|
| Process 属性 (`folderId` / `importMode` / `sourceDescription` / `externalStableKey`) | v2 record では `legacyEventAttributes` が空になるため**必須属性が既定値で埋まる**。endpoint attribute では解けない。**供給経路の話**であってモデルの話ではない | **引き受けない** — **今やる別チケット**。[`p1-1b-v2-evidence-home.md`](p1-1b-v2-evidence-home.md) **§3.4** の前提条件として、**flip の実装と同じ変更で閉じる**。(d)・(e) のどちらにも入れない |
| `chat.participants` / `chat.channelName` | 個人名がカタログに常駐する。**保持期間の話**で、「証拠に PII をどう載せるか」はモデルが決める | **引き受ける** |
| 会話の範囲 (`captureWindowStart/End` / `evidenceScope` / `selectionReason`) | 「取込元の性質」ではなく**この取込の判断**。判断をどこに置くかはモデルの問い | **引き受ける** |
| `executedBy` / `onBehalfOf` | digest が覆わない。式を動かす | **引き受けない** — **(e)**。roadmap が実行起源を (e) に置いている |

---

## 3. モデル本体 (roadmap が元から (d) に置いていたもの)

| | 区分 |
|---|---|
| 空コンテンツ | **引き受ける** — 一度「閉じた」と書いたが**早かった**。0 バイト attachment は hash 以前に skip され、document も aspect もイベントも作られない。製品には「取り込まなかった」という第 3 の答えがあり、モデルに名前が無い ([`p1-1d-evidence-data-model.md`](p1-1d-evidence-data-model.md) §3 D5) |
| version ごとの hash | **引き受ける** — 同じく**早かった**。結論は今のところ真だが理由が偽で、aspect は `buildCopyDocument` を通じて version 間で**参照共有される**。救っているのは `cancelCheckOut` の副作用で、`updateWithoutCheckInOut` にその保険は無い (同 §3 D3)。要る invariant は「証拠 aspect は version 間で参照共有しない」 |
| メタデータ hash | **引き受ける** — ただし D1・D6 の後。「要求された値を hash するのか、載った値を hash するのか」が先に決まらない |
| **事実が確定する時点** | **引き受ける** — これが本体。他の多くがここから決まる |
| `chatCapturedAt` と emit の順序 | **引き受ける** — ただし下記の注意 |

> **「emit 直前に刻印を移す」は再提案しない。** (b) §8 で撤回済み — 刻印先の aspect を
> 作るのは `execute()` が返った後の wrapper なので、前倒しすると**空振りする**。
> (d) が決めるのは「aspect 付与を含めて、どの事実がどの時点で確定するか」であって、
> 順序の入れ替えそのものではない。
>
> **2026-08-24 追記**: (d) が決めたとおり「どの時点で確定するか」を動かした結果、
> 撤回理由そのものが消えた — **aspect 付与を `execute()` の中 (beforeEmit hook) へ
> 引き込んだ**ので、emit の時点で刻印先が存在する。chat は (e) D-5、mail/note/record は
> (c) §8.1 の保護拡張と同時。順序の入れ替えを「再提案」したのではなく、
> 前提が変わった。

---

## 4. 既に主張済み (再提案しない)

| | いつ |
|---|---|
| 型更新・型取込が保護を巻き戻す 2 経路 | **2026-08-22**。管理 API は `updateTypeDefinition` の**コピーを null ガード** (この endpoint が保存値を書き換える唯一の箇所)。CMIS `updateType` という 3 つ目の書き手には**tripwire だけ**を置いた ((c) §5.5) — 偶然 3 つで死んでいるので挙動は変えていない、`ZipImporter` は**欠落・不正を作成前に拒否**。2026-08-21 に入れたパーサ側の変更は**経路ではなかった**ので効いていない — 詳細と負のコントロールは [`p1-1c-evidence-updatability.md`](p1-1c-evidence-updatability.md) §5.3・§6 の 11〜13 |
| READONLY プロパティが CMIS の update で消える | 2026-08-21。`mergeAspectProperties` が引き継ぐ ((c) §5.1) |
| 取込 snapshot の v2 表現 | 2026-08-21 ((b)) |
| 証拠プロパティの CMIS からの書き換え | 2026-08-21 ((c)) |

---

## 5. 復元 — **スキーマを先に決めてから**

| | |
|---|---|
| 既存オブジェクトの保管開始 | 来歴イベントを読む話。**何がイベントに載っているか**が決まっていないと読めない |
| このパッチ以前に書き換えられた値 | 同上。**遡って検証する手段が無い**ことは (c) §0 で主張済み |

**区分: 引き受けるが、モデルの後。** (d) の中で順序を分ける。

---

## 6. 別 ID

| | 担当 |
|---|---|
| 記録単位のバイナリダイジェスト | roadmap の該当行 (環境同一性の証明) |
| InterPARES 逐条マッピング | P1-1(d) 待ちと roadmap にあるが、**モデルの後**。同じ (d) の中で最後 |
| timestamp エントリと記録の結び付け | **P2** |
| 実行起源 (`executedBy` の委譲実行) | **(e)** |

---

## 7. したがって (d) の設計が対象にするもの

> **この節は着手時点 (2026-08-22) の一覧である。** 現在地は右列に追記した。
> 額面どおり読むと未着手を過大に見積もる。

| # | 項目 | 現在地 (2026-08-24) |
|---|---|---|
| 1 | **事実が確定する時点** (本体) — [`p1-1d-evidence-data-model.md`](p1-1d-evidence-data-model.md) | ✅ モデル本体は通した |
| 2 | 空コンテンツ / version ごとの hash / メタデータ hash | version ごとの hash (D-3) と メタデータ hash (D-1) は ✅ 2026-08-23。空コンテンツは**記録面のみ ✅** (D-2 — 親 pass の完了 evidence に `attachmentsNotIngested`)。**残るのはモデル側の命名だけ** (`CapturedContent` に `NOT_INGESTED` を足すか) |
| 3 | 「どの型が証拠か」 — (c) の 1 番目がこれを待っている | ✅ [`p1-1d-evidence-types.md`](p1-1d-evidence-types.md)。2026-08-24 に 2 型 → **5 型** ((c) §8.1) |
| 4 | 証拠に PII をどう載せるか — participants / channelName | ✅ [`p1-1d-evidence-disclosure.md`](p1-1d-evidence-disclosure.md)。**恒久 none** + Solr 索引時除外。**mail/note/record 側の PII (mailFrom 等) は未判断** — (c) §8.1 の但し書き |
| 5 | この取込の判断 (会話の範囲) をどこに置くか | ✅ (e) Step 2 — journalFacts (digest 被覆・カタログへ構造的に出ない) |
| 6 | `chatCapturedAt` を含む aspect 付与の位置 | ✅ (e) D-5 の beforeEmit hook。2026-08-24 に mail/note/record も同じ形へ |
| 7 | (モデルの後) 復元と InterPARES 逐条 | ✅ D-9 — [`interpares-mapping.md`](interpares-mapping.md) A.1 逐条 + [`restore-drill-runbook.md`](../operations/restore-drill-runbook.md)。**「既存オブジェクトの保管開始」(遡及 custody) は別項目で未着手** |

**対象にしないもの**: Process 属性の v2 供給 (flip の前提)、ローリング再起動の型キャッシュ、
実行起源 ((e))、timestamp の結び付け (P2)。
