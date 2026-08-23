# P1-1(d) — メタデータ hash

棚卸し §7 の 2 番目の最後の項。改竄検出の対象は現在 **content bytes だけ**
(`nemaki:contentHash`、主語 `input`) で、**証拠メタデータには検出装置が無い**。

ブロッカーだった D1 (要求された値 vs 載った値) と D6 (再取込の上書き) は
2026-08-22 に解消済みなので、その答えを前提に置ける。

> **この文書は設計であり、実装しない。** digest 式・(e) との同時実装の制約は §5。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 取込が**実際にオブジェクトへ載せた**証拠メタデータの、再計算可能な
ダイジェストが、**オブジェクトとは別の DB** に、pass ごとに記録される。後から
オブジェクトを読み直して再計算し、**一致しないことを検出できる**。

**主張しない**:

- **改竄防止ではない。** アプリケーション層の記録で、**両方の DB に届く者は両方を
  書き換えられる**。片方だけ触った改変を見えるようにするだけ。アンカーは **P1-3**
- **content bytes は対象外。** それは `contentHash` (主語 `input`) が既に担う。
  このハッシュは**メタデータ専用**で、2 つを混ぜない
- **遡及しない。** これ以前の pass に hash は無く、(c) §0 のとおり過去の書き換えは
  検証不能のまま
- **fixity ではない。** 読み戻した値の hash であって、保管系の健全性証明ではない

---

## 1. 現状 (コードで確認)

| | |
|---|---|
| 証拠メタデータの在処 | オブジェクトの aspect (`nemaki:chatContextMetadata` の 11 個 + `nemaki:externalIntegration` の source identity)。**可変ストア 1 箇所だけ** |
| イベントが運ぶもの | **要求された値** (`assuranceAsserted` が名指す ASSERTED)。載った値ではない — D1 |
| 書かれる時点 | emit の**後**、wrapper の中 ([`p1-1d-evidence-data-model.md`](p1-1d-evidence-data-model.md) §1.1 の表)。fill (D6) は後続 pass でも欠けを埋める |
| 完了の記録 | `CaptureScope.complete` が **wrapper の出口**で evidence map を組み、`nemaki_lineage` の outbox 行に畳み込む ([`CaptureScope:327-332`](../../core/src/main/java/jp/aegif/nemaki/rest/ingest/capture/CaptureScope.java#L327))。**全 fill の後**である |
| outbox 行の保持 | 設定可能・**既定は無期限** (owner 決定) |
| 検出の空白 | CouchDB 直接の書き換え・(c) 以前の編集は、**何とも突き合わせられない** |

**したがって「載った値の hash」を書ける時点と場所は既に在る** — outbox の完了 evidence。
**書く側には**新しい保存場所も emit の移動も要らない。**読む側には無い** (外部レビュー P1-2):
`_design/lineage_capture` の 5 view はどれも objectId をキーにせず、完了行を 1 件読む製品
API も無い。検証には **view 1 本 (`captured_by_object`: 完了行を `[objectId, capturedAtMs]`
で emit) と store の読み出しメソッドと endpoint** の追加が要る — §5 の作業項目に含める。

---

## 2. 何を hash するか — **載った値。要求された値ではない**

D1 の教訓そのもの: イベントの chat.\* は「こう要求された」であり、wrapper の書込は
失敗して warning に格下げされうる。**要求された値を hash すると、主張を公証することに
なる** — R2 が ASSERTED と OBSERVED を分けた意味が消える。

**hash するのは、完了時点でオブジェクトを読み戻した証拠プロパティの値** (R2 で言う
`observed`)。読み戻しに失敗したら **hash は書かない** — 「読めなかった」を明示し、
書けた風の値で埋めない (claim-substitution)。

### 2.1 対象プロパティ

| 集合 | 中身 | 理由 |
|---|---|---|
| `Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES` | chat の 11 個 | (c) が保護した本体。**リストは製品定数から導く** — 表をここに写すと検算しない数がまた流れる |
| `nemaki:externalIntegration` の source identity | `sourceArchetype` / `sourceSystem` / `sourceObjectId` / `sourceObjectType` / `sourceUrl` / `ingestionRunId` / `externalSourceType` / `externalSourceId` / `contentHash` の **9 個** — `applySourceMetadata` が put する全キーから除外 2 つ (下記) を引いた集合であり、**リストは put 箇所と突き合わせて数えた** (`sourceArchetype` を落とした初稿を外部レビューが捕捉) | dedupe と provenance の骨格。`contentHash` **値**も対象に入れる (bytes ではなく「記録された digest が変わっていないか」を見る) |

> **integration 側も製品定数から導く** (外部レビュー P2-6)。chat 側は
> `EVIDENCE_PROPERTIES` から導くのに、integration 側を「put 箇所と手で突き合わせた」ままに
> すると、`applySourceMetadata` に 12 個目の put が足された日に golden vector は緑のまま
> hash から漏れる。**put するキー集合を定数 (`SourceIdentityProperties` 相当) に括り出し、
> `applySourceMetadata` と hasher が同じ定数を読む** — willWrite が collector を共有するのと
> 同じ手。

**除外 2 つ、理由つき**:

- `nemaki:externalContext` — 上限 1MB の自由 JSON。**証拠ではなく運搬物**で、
  入れると hash が「メタデータの同一性」ではなく「巨大 blob の同一性」になる
- `nemaki:externalContextUpdatedAt` — 装飾のたびに動く**運用時刻**。入れると
  すべての再装飾で hash が変わり、変化検出が意味を失う

### 2.2 正準形 — 過去に踏んだ罠を式に焼き込む

```
mh1:SHA-256( join(LF, sorted(propertyId + "=" + escape(canonicalValue))) )
escape: "\\" → "\\\\"、LF → "\\n"
```

| 規則 | 焼き込む罠 |
|---|---|
| **値をエスケープする** (`\\` と LF)。行の区切りが値の中に現れない。**単射なのは join であって raw 状態ではない** — blank=不在・重複キーは後勝ち・datetime Number の小数切捨ては**製品が同値とみなす状態を意図的に畳む** (最終レビュー M1 で明確化) | **エスケープ無しの初稿は単射でなかった** (外部レビュー P1-1): `chatSelectionReason` は呼び出し元の自由記述で LF を含みうるし、悪意なら `sourceObjectId` に `id\nnemaki:sourceObjectType=message…` を入れて隣接する行を**値の中に飲み込み**、他を空文字 (=不在) にすれば**正準文字列が一致したまま証拠を書き換えられる** — 設計自身の脅威モデル (content DB だけを触る改変) の内側で hash が MATCH を返す |
| **propertyId でソート**。map の反復順に依存しない | JSON のキー順は JVM のクラスロード順依存 (実測済みの既知トラップ)。propertyId は固定語彙なのでキー側のエスケープは不要、最初の `=` で分割できる |
| datetime は **epoch millis の 10 進**に正規化。`Calendar` / `Date` / `Number` / ISO 文字列 / millis 文字列を同じ値に | aspect 値は CouchDB 往復で `Double`/`Long` になり、キャッシュヒットでは `Calendar` のまま — `instanceof Calendar` が決して真にならなかった F1 と同じ地形。`toEpochMillis` と同じ正規化を使う (**現在 private — 公開が要る**) |
| **欠けているプロパティは行ごと出さない** (null 埋めしない)。**blank は不在扱い** (`isBlank` — 空白のみも不在。`presentValues` ほか実経路と同じ語で) | 「空を保管した」と「無い」の混同 (D5 の教訓は content の話だが、規則は揃える) |
| 値は UTF-8。`mh1:` の**式バージョン接頭辞** | 式を変える日が来ても、どの式で計算されたか行が自分で言える |

**読み戻しは raw aspect 経路** (`ContentService.getContent` → aspect の `Property` 値) と明記する
(外部レビュー P2-2)。CMIS compile 後の値は**別物** — `nemaki:contentHash` は型定義が無いため
compile 経路には**決して現れず**、書込 raw / 検証 compile の組合せは全件 MISMATCH になる。

正準形は**文字列として決定的**なので golden hash を固定できる (WAR バイナリの
golden とは違い JDK ビルドに依存しない — 依存したのは環境ダイジェストの話)。

---

### 2.3 hash は 2 本 — 保護の強さが違うものを 1 本に混ぜない

外部レビュー P1-3: 対象 20 個のうち **integration 側 9 個は D-7 まで CMIS-READWRITE** で、
書込権限のある通常ユーザーの `updateProperties` 一発で変わる。1 本の hash に混ぜると、
**正当な編集が改竄と区別不能な MISMATCH** になり、警報として死ぬ。

| hash | 対象 | MISMATCH の意味 |
|---|---|---|
| `appliedChatEvidenceHash` | chat の 11 個 (READONLY 済み) | **記録された pass の無い変化** — day-1 から改竄シグナル |
| `appliedSourceIdentityHash` | integration の 9 個 | ~~D-7 までは変化検出~~ **D-7 は 2026-08-23 に実装され、昇格済み**: 9 個とも READONLY (contentHash は宣言ごと新設) なので、MISMATCH は chat 側と同じく「記録された pass の無い変化」を意味する。式は変わっていない |

検証はそれぞれ別に判定して返す。

## 3. どこに記録するか — outbox の完了 evidence

| 案 | 判定 |
|---|---|
| **(i) outbox 完了 evidence に hash 2 本 + `metadataHashSubject: "applied"` + 式バージョン** | **採る**。書く時点が正しく (全 fill の後)、別 DB で、保持は既定無期限、**digest 式に触らない** |
| (ii) 来歴イベントに載せる | **今はやらない**。イベントは emit 時点で載った値を知らない (D1)。aspect 付与が `execute()` に入る **(e) の境界引き直しの後**なら載せられる — そのとき digest 式の変更と同時に ((b) §5「式は 1 度だけ動かす」) |
| (iii) オブジェクト自身にプロパティとして | **採らない**。hash を被対象と同じストアに置くと、そのストアに届く者への検出力が**ゼロ**になる。二重ストアが本体 |

**D6 との相互作用が仕様になる**: fill が欠けを埋めた pass は、埋めた後の状態の hash を
**自分の完了行に**記録する。pass ごとの hash 列 = 「各時点で証拠がどうだったか」の履歴。

**「最新」の規則** (外部レビュー P2-1/P2-4 — 単純な「最新の完了行」では偽る):

- 比較相手は **hash を持つ最新の完了行** (`capturedAtMs` 順)
- **それより新しい行があれば必ず併記する** — hash 無しの完了行 (mail wrapper への
  fallback や DLQ null-archetype 再実行など、**hash を書かない書き手が実在する**) と、
  **UNRESOLVED 行** (部分失敗 — chat 値を書いた後に window が落ちた pass は完了せず、
  変化に「記録」はあるのに完了行が無い)。この場合の不一致は
  **`UNVERIFIABLE`(later pass) に降格**する — 「記録の無い変化」と断定しない
- **D-7 が `applySourceMetadata` の `""` 上書きを直すまで**、version-up の完了行の hash は
  上書き後の状態を「載った値」として写す。hash 履歴は**変わったこと**を示すが、
  **何が失われたか**は示さない — この設計は D-7 前の証拠破壊を検出せず正規化する。
  一文で言う: **hash の意味は D-7 と同時に強くなる** (§2.3 と同じ構図)

## 4. 検証

admin エンドポイント (読み取り専用、`CaptureIntentController` と同型・GET は CSRF 対象外):
`GET .../capture-intents/verify-metadata?repositoryId=…&objectId=…` — §3 の規則で行を選び、
現物を **raw aspect 経路**で読み直して再計算し、hash 2 本 (§2.3) をそれぞれ判定。
`MATCH` / `MISMATCH` / `UNVERIFIABLE` (hash を持つ完了行が無い・より新しい hash 無し行 or
UNRESOLVED 行がある・オブジェクトが読めない・式バージョン不明) の三値 × 2。**二値に潰さない**。

- **並行 pass の競走** (外部レビュー P2-3): pass B が A の書込前に読み戻し A の後に完了すると、
  最新行が古い状態の hash を持ち一時的に MISMATCH に見える。**MISMATCH は 1 度再検証してから
  報じる**を運用手順にする — 間に pass の無い 2 連続 MISMATCH が本物
- **archive / restore**: aspects は verbatim に保存・復元されるので restore 後は MATCH、
  削除後は UNVERIFIABLE — 三値がそのまま吸収する

検出できるもの / できないもの:

| | |
|---|---|
| 検出**できる** | content DB だけを触った証拠値の書き換え・消去 (lineage DB の hash と食い違う) |
| 検出**できない** | 両 DB を揃えて触る改変 (→ P1-3 のアンカー)、hash 記録以前の編集、`externalContext` 内の変化 (対象外)、bytes の改変 (fixity P1-2) |

## 5. 実装の順序と制約

**1〜4 は 2026-08-23 に実装済み** (`EvidenceMetadataHash` / `buildSourceIdentityProps` の
括り出し / 完了 evidence への記録 / `captured_by_object`・`rows_by_source_object` view +
`listCapturedForObject`・`listRowsForSourceBefore` (keyset cursor — 単ページの `listRowsForSourceSince` は第 2 レビューで 100 行キャップが長履歴 source の MISMATCH を恒久に殺すと判明し置換) + `GET /verify-metadata`)。
golden vector は式の外 (python hashlib) で計算し、**エスケープ無しの旧式で 2 状態が実際に
衝突することも確認した**。負のコントロール実測: 記録を外すと
「the completed row carries no metadata hash」。

1. **この設計のレビュー** — 済 (2026-08-23、P1 3 件 / P2 6 件を反映)
2. `SourceIdentityProperties` 定数の括り出し (§2.1) + `toEpochMillis` の公開 (§2.2)
3. 正準形 (エスケープ込み) + golden vector + 完了 evidence への hash 2 本の記録
   (chat wrapper から。読み戻し失敗は hash 無し + 理由)
4. **読む側**: `captured_by_object` view + store 読み出し + 検証エンドポイント (§1/§4)
5. **(e) の境界引き直しのとき**: イベント側にも載せ、digest 式の変更を executedBy と
   **同時に 1 度だけ**行う
6. mail/note/record への拡張は **D-7 (保護拡張) と同時** — 保護の無い型の hash は
   「変わりうるものの写真」でしかない。**§2.3 のとおり同じ批判は day-1 の
   `appliedSourceIdentityHash` にも当てはまり、D-7 で昇格する**

## 6. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | 完了行に hash **2 本** (§2.3) / subject / 式バージョンが載る | どちらか 1 本を落とすと落ちる (1 本に混ぜる実装も落ちる) |
| 2 | hash は**読み戻した値**から計算される — 要求値から計算する実装は落ちる | request 値で計算すると、書込失敗を注入したケースで落ちる |
| 3 | `Calendar` / `Double` / `Long` / ISO 文字列の同じ時刻が**同じ hash** になる | 正規化を外すと落ちる (F1 の再発防止) |
| 4 | ソートが propertyId 順で、挿入順に依存しない | 順序を map 依存にすると落ちる |
| 5 | 読み戻し失敗の pass は hash を**書かず**、理由を書く | 失敗時に request 値で埋めると落ちる |
| 6 | fill した pass の hash は **fill 後**の状態 | fill 前に計算すると落ちる |
| 7 | golden vector (固定 fixture → 固定 hash)。**LF 入り値の fixture を含む** | 式のどこを変えても落ちる。**エスケープを外すと「値に行を飲み込ませた偽装 fixture」と正規 fixture の hash が一致してしまい、落ちる** (P1-1 の単射性) |
| 8 | 検証が三値 × 2 本 — **hash 無し完了行のみのとき `UNVERIFIABLE`** を具体 fixture で | `MATCH` に潰すと落ちる。**「より新しい hash 無し行がある MISMATCH」が `UNVERIFIABLE` に降格することも fixture で** (§3 の規則) |
| 9 | integration の hash 対象集合が `applySourceMetadata` の put 集合と**同じ定数**から来る | put だけに 12 個目を足すと落ちる (P2-6) |

## 7. やらないこと

- digest 式 (`LineageEventDigest`) の変更 — **(e) で 1 度だけ**
- content bytes の再検証 — **P1-2 fixity**
- アンカー・追記専用化 — **P1-3**
- 遡及計算
- `externalContext` の内容検査
