# P1-1(d) — 証拠に個人データをどう載せるか

棚卸し §7 の 4 番目。[`p1-1b-v2-evidence-home.md`](p1-1b-v2-evidence-home.md) §6 が
「`chat.participants` を endpoint attribute にしない」と決めたところから先。

> **(b) が決めたのは「v2 の home を作らない」だけである。** それは
> `chat.participants` が外部カタログに**届かない**ことを意味しない — §1 のとおり、
> 今日すでに送信されている。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 個人データと宣言した事実は、**配送先スキーマが何を宣言していようと、
外部カタログへ送られない**。

**主張しない**:

- **個人データの網羅とは言わない。** 自由記述欄には何でも書ける。宣言できるのは
  「値が構造的に個人を指す」フィールドだけである
- **GDPR / 個人情報保護法への適合とは言わない。** これは配送の 1 経路を閉じる作業であって、
  法令適合の主張ではない
- **journal 側の保持を変えるとは言わない。** `lineage.retention.days` (既定 90) は従来どおり

---

## 1. 現状 (コードで確認)

`PurviewLineageSink.publish` は **v1 snapshot を丸ごと Process 属性へ写す**
([`:65-70`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/PurviewLineageSink.java#L65)):

```java
for (Map.Entry<String, String> entry : record.legacyEventAttributes().entrySet()) {
    processAttrs.put(entry.getKey(), entry.getValue());
}
```

**選別が無い。** したがって:

| | |
|---|---|
| いま起きていること | `chat.participants` は**送信されている**。`nemaki_import_process` が宣言していないので**到着時に落ちている**だけ |
| 守っているもの | **配送先のスキーマ**。つまり**我々の外**にある |
| 壊れ方 | 誰かが `nemaki_import_process` にその属性を宣言した日、あるいは属性を拒否しない配送先を足した日に、**設定変更ひとつで常駐が始まる** |
| 保持期限 | journal は `lineage.retention.days` (既定 90) で purge されるが、**カタログ側にその規則は無い** |

**これは fail-open である。** 「送っているが落ちている」を「守れている」と読むのは、
本作業が繰り返し踏んできた取り違えと同じ形をしている。

> `EndpointKindSchemaAlignmentTest` の `FORBIDDEN_ON_ARTIFACTS` は artifact kind にしか
> 掛からないので、自動では止まらない ((b) §6)。

---

## 2. 規則

**表が「個人データを運ぶ」と宣言した事実は、外部配送の payload に入れない。**

宣言は `CaptureEvidenceField` に置く — v1 キー・v2 home・`Assurance` と同じ場所である。
**足す人が 1 箇所だけ見ればよい**ようにするのが、この表が存在する理由そのもの。

```
Disclosure { INTERNAL_ONLY, EXTERNAL_OK }
```

`INTERNAL_ONLY` は **journal には載る**。載らないのは外部カタログへの payload だけである。
証拠そのものを削るのではなく、**証拠が出ていく先を絞る**。

---

## 3. どれを `INTERNAL_ONLY` にするか

| 事実 | 判定 | 理由 |
|---|---|---|
| `chat.participants` | **INTERNAL_ONLY** | 値が**構造的に個人名の列**である。(b) §6 が名指しした本体 |
| `chat.channelName` | **INTERNAL_ONLY** | `dm-ishii-otsuka` のような値を普通に取る。**証拠としての重みは `chat.channelId` が担っており**、表示名を外部に出す必要が無い |
| `chat.selectionReason` | **INTERNAL_ONLY** | **呼び出し側の自由記述**。何が入るか我々は決められない |
| `chat.evidenceScope` | **INTERNAL_ONLY** | 同上 |
| `executedBy` / `onBehalfOf` | **EXTERNAL_OK** (現状維持) | 個人名ではあるが、**運用者自身のアカウンタビリティ情報**で、データカタログに所有者・管理者として常駐するのは通常の運用である。かつ **(e) が最終判断を持つ** ((b) §5)。**ここで先回りしない** |
| それ以外 | **EXTERNAL_OK** | 識別子・digest・機械状態 |

> **`chat.channelId` は出す。** 会話の同一性は証拠の骨格で、これを落とすと来歴が
> 「どこかのチャットから来た」に退化する。**個人を指すのは名前であって id ではない** —
> id から人を引くには source 側の権限が要る。

---

## 4. どこで濾すか

| 案 | 判定 |
|---|---|
| **(A) sink の payload 組み立て時** | **採る**。外へ出る唯一の口で、`legacyEventAttributes` を写すその場所 |
| (B) snapshot を作る時に入れない | **採らない**。journal からも消える — §2 の「証拠を削るのではない」に反する |
| (C) 配送先スキーマ側で拒否 | **採らない**。それが**今の状態**であり、守っているものが我々の外にある (§1) |

**(A) で、かつ写している箇所は 1 つではない。** 調べた結果:

| 位置 | 何をするか | 濾過が要るか |
|---|---|---|
| [`PurviewLineageSink:66`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/PurviewLineageSink.java#L66) | snapshot を丸ごと Process 属性へ | **要る** |
| [`:96` → `:267`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/PurviewLineageSink.java#L96) | `LegacyName` の asset に同じ map を写す (v1 分岐)。`Typed` (v2) は endpoint 自身の属性を使うのでここを通らない | **要る** |
| [`:333` `addProcessTypeAttributes`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/journal/PurviewLineageSink.java#L333) | snapshot から**特定のキーだけ**引いて必須 Process 属性を埋める | 引くキーを見て判断。現状 chat 系は引いていない |
| `AtlasLineageSink` / `DataplexLineageSink` | `legacyEventAttributes` を**参照していない** | 不要 |
| [`LineageJournalController:450`](../../core/src/main/java/jp/aegif/nemaki/rest/controller/LineageJournalController.java#L450) | REST 応答に `snapshotAttributes` として出す | **不要** — これは journal を読む内部 API で、§2 の「journal には載る」側 |

**濾過を各 sink に書かせない。** 共有のヘルパを 1 つ置き、写す側はそれを呼ぶ。
`PurviewLineageSink` の 2 箇所を素通しに戻すと落ちるテストを AC 5 に置く。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `chat.participants` が**外部 payload に現れない** | 濾過を外すと落ちる |
| 2 | `chat.channelId` は**現れる** — 条件 1 の control。全部落とす実装だと落ちる | chat 全体を落とすと落ちる |
| 3 | `INTERNAL_ONLY` の事実が **journal には残る** | snapshot 生成時に落とす実装 ((B)) にすると落ちる |
| 4 | **配送先が属性を宣言しても現れない** — §1 の壊れ方そのもの | 「宣言されていれば送る」実装にすると落ちる |
| 5 | `PurviewLineageSink` の **2 箇所とも**濾過を通る (Process 属性・legacy asset) | どちらかを素通しに戻すと落ちる |
| 5b | **REST の `snapshotAttributes` には出る** — 条件 3 の別経路 control | journal 側まで濾すと落ちる |
| 6 | 表の全フィールドが `Disclosure` を**宣言している** | 足し忘れがコンパイルで止まる (コンストラクタ必須) |

---

## 6. 運用文書に書くこと

- **チャット参加者・チャンネル名・選定理由・証拠範囲は外部カタログへ送られない。**
  来歴 journal には残り、`lineage.retention.days` に従って purge される
- **チャンネル ID は送られる。** 会話の同一性は来歴の骨格である
- **これ以前に送信された値は、この変更では取り消せない。** カタログ側の削除が別途要る

---

## 7. やらないこと

- `executedBy` / `onBehalfOf` の判断 — **(e)** ((b) §5)
- journal 側の保持期間の変更
- 自由記述欄の内容検査。**宣言できるのは構造だけ**である (§0)
