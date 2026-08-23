# P1-1(d) — 証拠に個人データをどう載せるか

棚卸し §7 の 4 番目。[`p1-1b-v2-evidence-home.md`](p1-1b-v2-evidence-home.md) §6 が
「`chat.participants` を endpoint attribute にしない」と決めたところから先。

> **(b) が決めたのは「v2 の home を作らない」だけである。** それは
> `chat.participants` が外部カタログに**届かない**ことを意味しない — §1 のとおり、
> 今日すでに送信されている。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 個人データと宣言した事実は、**配送先スキーマが何を宣言していようと、
外部カタログへ送られない**。経路は 2 つあり、**両方**を閉じる (§1.2)。

**主張しない**:

- **個人データの網羅とは言わない。** 自由記述欄には何でも書ける。宣言できるのは
  「値が構造的に個人を指す」フィールドだけである
- **GDPR / 個人情報保護法への適合とは言わない。** これは配送の 1 経路を閉じる作業であって、
  法令適合の主張ではない
- **journal 側の保持を変えるとは言わない。** `lineage.retention.days` (既定 90) は従来どおり
- **endpoint の identity に埋まっている値は、この仕組みでは止められない。**
  `chat.channelId` は canonical source URI の一部として qualifiedName /
  `externalStableKey` / `externalPath` に入る。属性を落としても値は出ていく (§3.1)
- **保持期限が実際に効くとは言わない。** `lineage.purge.cron` は**既定が空**で、
  purge は既定配備では**走らない** (§1.3)

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

### 1.2 第 2 の経路 — **在るが、初稿の脅威文は誇張だった** (2026-08-23 訂正)

初稿は「取込が書いた値が mapping 設定ひとつでカタログへ入る」と書いた。**誇張である**
(外部レビュー): 取込が書くのは **aspect** であって `subTypeProperties` ではなく、
`appendCustomPropertyValues` が読むのは `content.getSubTypeProperties()` **だけ**。
`nemaki:chatParticipants → chatParticipants` の mapping を有効にしても、
取込が書いた値は **null として投影され、漏れない**。

**それでも門は要る。** 実在する残余経路は: 管理者が**同じ property id を宣言する
primary subtype** を定義すると、その値は `subTypeProperties` に載り、mapping が
外へ運ぶ。また mapping の拒否は**保存時と payload 組立時の両方**で効くので、
このルール以前に保存された mapping・restore・手編集も塞がる。そして:

[`PurviewEntityPayloadFactory.appendCustomPropertyValues`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/payload/PurviewEntityPayloadFactory.java#L193)
が、**管理者が設定した mapping に従って `subTypeProperties` を `nemaki_document` に写す**。
mapping は `nemaki_conf` の `catalog.sync.propertyMappings.{repositoryId}` にあり、
**管理画面から有効化できる**。

**`nemaki:chatParticipants → chatParticipants` を有効にした次の同期で、個人名が
カタログに入る。** lineage sink の濾過はこの経路を一切見ない。

**そしてこの経路には既に門がある** —
[`CatalogPropertyMappingResolver.FORBIDDEN_SOURCE_PROPERTY_IDS`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/payload/CatalogPropertyMappingResolver.java#L56)
は `nemaki:cloudFileUrl` 1 件を持ち、javadoc が
「custom mapping がその値への第 2 の扉になってはならない」と書いている。
**同じ理由が chat の個人データにそのまま当てはまる。**

> **したがって §2 の「1 箇所だけ見ればよい」を成立させるには、この集合を表から導く。**
> 表に足した人が sink 側だけ守られて mapping 側が開いている、という状態にしない。

### 1.3 既定では動いていない — **初稿の切迫感は言い過ぎ**

| | 既定 |
|---|---|
| `lineage.mode` | `disabled`。DISABLED では `NoopLineageEmitter` |
| `purview.enabled` / `atlas.enabled` | どちらも false。両方 false なら sink は `isAvailable()` が偽 |
| `lineage.targets` | 空 |
| `lineage.purge.cron` | **空** — `@Value("${lineage.purge.cron:}")`。purge タスクは armed されない |

**正しい言い方は「lineage とカタログ backend を有効にした配備では、参加者は既に
送信されている」である。** 既定配備では送信されない。

**そして purge は既定では走らない。** `lineage.retention.days` は `executePurge()` の中でしか
読まれず、cron が空なら呼ばれない。初稿の「journal は 90 日で purge されるが、カタログ側に
その規則は無い」という対比は、**既定配備では両方とも消えない**ので成立しない (§6)。

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
| `executedBy` / `onBehalfOf` | **EXTERNAL_OK** (現状維持) | **NemakiWare の principal id** であって、取り込まれた人物の名前ではない (委譲実行では `"unknown: …"`、webhook では `"service: …"`)。ただし「運用者」とは限らない — 取込を POST できる認証済み主体なら誰でもここに載り、LDAP / OIDC では email であることが多い。**(e) が最終判断を持つ** ((b) §5) ので先回りしない |
| それ以外 | **EXTERNAL_OK** | 識別子・digest・機械状態 |

| `chat.captureWindowStart` / `End` | **INTERNAL_ONLY** | snapshot へ入る経路では**検証されない呼び出し側の文字列**である (aspect へ書く経路だけが `Instant.parse` する)。§3 の規則を自分で破らないために入れる |

### 3.1 `chat.channelId` — **出す。ただし初稿の理由は誤りだった**

初稿は「個人を指すのは名前であって id ではない」と書いた。**これは範疇の誤り**で、
そのまま押せば利用者 id を出してよいことになる。製品自身の javadoc が
「direct message では channelName は相手の名前」と書いており、**DM の channel id は
2 人組の安定した識別子**である。

**本当の理由はもっと単純で、もっと不都合である: この仕組みでは止められない。**
`chat.channelId` は canonical source URI
(`{system}://{workspace}/channels/{channelId}/messages/{messageId}`) の一部で、
asset の `qualifiedName`・`externalStableKey`・`externalPath`、および Process の
`externalStableKey` として payload に入る。**属性を落としても値は出ていく。**

> **したがって `Placement.IDENTITY` の事実に `INTERNAL_ONLY` を宣言させてはならない。**
> 宣言できてしまうと、キーは落ちるのに値は qualifiedName で出ていく —
> §1 が非難しているのと同じ形の fail-open になる。**テストで拒否する。**

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
| `PurviewLineageSink:289` `legacyAssetEntity` | ~~同じ map を asset に写す~~ **写さない。** `name` / `originalId` / `repositoryId` の **3 キーを `getOrDefault` で引くだけ** | **不要** — 初稿はここを「要る」と書いていた。**誤り** (外部レビュー)。この行を根拠に AC を書くと、**濾過を外しても payload が 1 バイトも変わらない**負のコントロールになる |
| `PurviewLineageSink:333` `addProcessTypeAttributes` | snapshot から**特定のキーだけ**引く (`importMode` / `provider` / `sourceSystem` / `targetFolderId` / `sourceArchetype` / `sourceObjectId`) | 不要。chat 系は引いていない |
| **[`CatalogPropertyMappingResolver`](../../core/src/main/java/jp/aegif/nemaki/rest/purview/payload/CatalogPropertyMappingResolver.java#L56)** | 管理者設定の mapping で `subTypeProperties` をカタログへ (§1.2) | **要る。第 2 の経路** |
| **`CouchLineageDeadLetterStore:56`** | `snapshotAttributes` を `nemaki_lineage` の DLQ 行に**そのまま保存**。purge の view は `type === 'lineage_event'` しか見ないので**届かない** | **保持の話**。§6 に書く |
| `LineageSpoolCodec:188` | spool ファイルに snapshot を書く。`lineage.spool.dir` 既定は空 | 同上 (休眠) |
| `AtlasLineageSink` / `DataplexLineageSink` | `legacyEventAttributes` を**参照していない** (共有 builder も汎用シリアライズも無い) | 不要。**回帰を止めるテストが無い**ので置く |
| `LineageJournalController:450` | REST 応答に `snapshotAttributes` として出す。admin 認証必須 | **不要** — journal を読む内部 API |

**したがって濾過が要るのは 2 箇所**: lineage sink の Process 属性と、custom property mapping。
**共有のヘルパを 1 つ置き、両方がそれを呼ぶ。**

> **既に「最後の門」を名乗るものが在る。** `CatalogSecretBoundary.sealed()` は
> 「entity payload がカタログへ出る前の最後の門」と自称し、他の builder 14 箇所以上が
> 呼んでいる。**`PurviewLineageSink` だけが呼んでいない。** そこへ第 2 の門を足すのは
> §2 の「1 箇所だけ見ればよい」に反する。**ただし `sealed()` は sink の
> `qualifiedName` (非 `nemaki://` scheme) を許可属性に持たないので、そのまま通すと
> 毎回投げる。** 統合は別作業として記録し、この増分では**同じヘルパを両経路が呼ぶ**形に
> とどめる。
>
> ついでに分かったこと: `chat.selectionReason` / `chat.evidenceScope` は
> **検証されない呼び出し側の自由記述**のまま Purview の Process 属性へ入り、
> **secret 検査を一度も通っていない**。§3 で INTERNAL_ONLY にすると、この穴も同時に閉じる。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `chat.participants` が **lineage sink の Process 属性に現れない** | 濾過を外すと落ちる |
| 2 | **キー `chat.channelId` は現れる** — 条件 1 の control。**値ではなくキーを見る**: 値は qualifiedName にも入っているので、chat を全部落としても値の検査は通ってしまう (§3.1) | chat 全体を落とすと落ちる |
| 3 | `INTERNAL_ONLY` の事実が **journal には残る** | snapshot 生成時に落とす実装 ((B)) にすると落ちる |
| 3b | **REST の `snapshotAttributes` には出る** — 条件 3 の別経路 control | journal 側まで濾すと落ちる |
| 4 | `INTERNAL_ONLY` のプロパティを **custom property mapping の source に指定できない** (§1.2) | 禁止集合を表から導くのをやめると落ちる |
| 5 | `nemaki_import_process` の型定義が **chat 属性を 1 つも宣言していない** | 宣言を足すと落ちる。**配送先スキーマ側の実在する条件**で、初稿の「宣言しても現れない」は `PurviewLineageSink` に「宣言されているか」を見る分岐が無いので**負のコントロールが作れなかった** |
| 6 | `Placement.IDENTITY` の事実に `INTERNAL_ONLY` を**宣言できない** (§3.1) | 許すと落ちる。**キーは落ちるのに値が qualifiedName で出る** fail-open を止める |
| 7 | 表の全フィールドが `Disclosure` を**宣言している** | 足し忘れがコンパイルで止まる (コンストラクタ必須) |
| 8 | `AtlasLineageSink` / `DataplexLineageSink` の payload に **snapshot のキーが 1 つも現れない** | どちらかが snapshot を写すようになると落ちる。**今日の性質を回帰から守るだけ**で、この増分が変えるものではない |

---

## 6. 運用文書に書くこと

- **チャット参加者・チャンネル名・選定理由・証拠範囲・取得窓は外部カタログへ送られない。**
  lineage sink からも、custom property mapping からも
- **チャンネル ID は送られる。** 会話の同一性が canonical source URI に埋まっており、
  **属性を落としても qualifiedName として出ていく** (§3.1)。止めたいなら別の作業になる
- **`lineage.retention.days` は既定では効いていない。** `lineage.purge.cron` が空だと
  purge タスクが起動しない。**保持期限を当てにするなら cron を設定すること**
- **purge を設定しても、届かなかったイベントは残る。** DLQ 行 (`lineage_dead_letter`) は
  `snapshotAttributes` を保存し、purge の view は `lineage_event` しか見ない。
  カタログが落ちていた間の取込ほど残る
- **これ以前に送信された値は、この変更では取り消せない。** カタログ側の削除が別途要る
- **オブジェクト側の `nemaki:chatParticipants` はこの作業の対象外。** Solr にも索引され、
  RAG 索引も読む。**文書の生存期間に従う**のであって journal の保持期限には従わない

---

## 6.1 実装 (2026-08-22)

- `CaptureEvidenceField.Disclosure`。**3 引数コンストラクタは無い** — 既定 EXTERNAL_OK に
  すると、次に事実を足す人が「出してよいか」を決めずに通ってしまう
- `internalOnlyV1Keys()` を `PurviewLineageSink` の Process 属性写しが引く
- `internalOnlyCmisPropertyIds()` を `CatalogPropertyMappingResolver` の
  `FORBIDDEN_SOURCE_PROPERTY_IDS` が引く (**既存の `nemaki:cloudFileUrl` と合わせて 1 つの集合**)
- `Placement.IDENTITY` に `INTERNAL_ONLY` を宣言するとコンストラクタが投げる

負のコントロール: sink の濾過を外すと sink payload テストが
`[… chat.participants …] ==> expected: <false> but was: <true>`、
表からの派生をやめると mapping の拒否テストが
`expected: <FORBIDDEN_SOURCE_PROPERTY> but was: <null>`。

> **1 巡目は sink 側を外しても何も落ちなかった。** payload を実際に組み立てるテストが
> 無かったためで、`LineageSinkRecordContractTest` に足した。

---

## 6.2 これは「個人名が製品から消えた」ではない

`nemaki:chatContextMetadata` は `fulltextIndexed=true`、各プロパティは `queryable=true`。
`SolrUtil` は aspect の値を全部 `dynamic.property.{key}` に載せる。
**既定配備でも `nemaki:chatParticipants` は検索に出る。** → **2026-08-24 閉鎖**: 索引時に INTERNAL_ONLY 6 件を除外 (`search.evidence.internalonly.indexing.enabled=false` 既定、第 3 の口も同じ表)。露出経路は CONTAINS でなくプロパティ検索だった (copyField 不在)。**読み出しは対象外のまま** — オブジェクトを読める者は値を見る。

この作業が閉じたのは**外部カタログへの 2 経路だけ**である。オブジェクト側と検索索引は
対象外で、そちらは**文書の生存期間**に従う (journal の保持期限ではない)。

---

## 7. やらないこと

- `executedBy` / `onBehalfOf` の判断 — **(e)** ((b) §5)
- journal 側の保持期間の変更
- 自由記述欄の内容検査。**宣言できるのは構造だけ**である (§0)
