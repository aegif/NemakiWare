# クエリのスキャン費用を一致件数から切り離す

状態: **提案 (未実装)** — 懐疑的レビュー済み、その結果を反映して第一候補を差し替えた
対象リリース: v3.4 (v3.3 のリリースは阻害しない)
撤退基準: 第 0 段だけで V1b (往復 600 未満) を満たしたら第 1 段以降は再評価する

実測環境: 2026-08-08, Apple Silicon 14 core。`nb33` は
`docker/docker-compose-simple.yml` を compose プロジェクト名 `nb33` で起動したもの
(3.3.0, bedroom 11,435 文書 / Solr 2,238)。

レビューの全文と却下理由: [`query-scan-cost-review.md`](query-scan-cost-review.md)

---

## 1. 何が起きているか

CMIS クエリ 1 本 — `SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE 'bench-doc-000%'`、
`maxItems=25`、一致 216 件 — が CouchDB に投げるリクエスト数:

| | 往復数 | 所要 |
|---|---|---|
| cold (core 再起動直後) | **1,318** | 2.4 s |
| warm (2 回目) | **0** | 0.1 s |

一致 1 件あたり 5 リクエスト、その内訳:

| リクエスト | 件数 | 一致件数比 | 正体 |
|---|---|---|---|
| `GET /bedroom/<id>` | 668 | ×3.09 | `getContent` 216 + **添付ノードの doc GET 216** + `getVersionSeries` 216 + 残差 ~20 |
| `GET /bedroom/<id>/content` | 432 | ×2.00 | **往復ではなく添付本体の全ダウンロード ×2** (§3 案 0) |
| `GET /bedroom/<id>?att_encoding_info=true` | 216 | ×1.00 | 添付ノードの再取得 |

**216 は一致件数そのもの**です。返すのは 25 行なのに、往復数はページ長ではなく
**一致件数に比例**します。`aclScanCap` の既定は 10,000 なので 6 万往復は到達しうる値で、
かつ **10,000 は機能上限でもあります** — それを超えるクエリは
`CmisInvalidArgumentException` で拒否されます (`SolrQueryProcessor.java:736-762`)。

EhCache が温まっていれば 0 往復です。ただし **objectDataCache の上限は 10,000
エントリ / リポジトリ**なので、作業集合がそれを超える環境では warm も成立しません。
「平常時は見えない」と言えるのは作業集合が 1 万件に収まる場合だけです。

冷えているとき — **再起動直後・ローリングデプロイ直後・キャッシュ追い出し後** —
16 並列のクエリを当てると 50–90 秒返らず、その間 CouchDB は 18,313 リクエストを
受けていました (ハングではなく、走り切れていない)。

---

## 2. どこで発生しているか

`SolrQueryProcessor.query()` ([SolrQueryProcessor.java:529-640](../../core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java)):

| # | 処理 | 対象件数 |
|---|---|---|
| 1 | Solr から最大 `aclScanCap` 件取得 | — |
| 2 | ヒットごとに `contentService.getContent()` | **全一致件数** |
| 3 | `permissionService.getFiltered()` | 全一致件数 |
| 4 | `compileService.sortContentsForSearchResult()` | **全一致件数** |
| 5 | ページ切り出し | — |
| 6 | `compileObjectDataListForSearchResult()` | ページ (25) |

ステップ 6 はページ限定ですが、**それが安いのはステップ 4 が
`objectDataCache` を温めているからにすぎません**。ステップ 4 を軽くすると
ステップ 6 の前提が崩れます (§5-4)。

増幅しているのはステップ 4 です。`sortContentsForSearchResult`
([CompileServiceImpl.java:708](../../core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java))
は認可済み全件に `getRawObjectData(...)` を掛けます。コード上のコメントはこれを
"lightweight" と呼びますが、実体は:

```
getRawObjectData
  └ compileProperties
      └ compileDocumentProperties
          └ setCmisAttachmentProperties
              └ ContentService.getAttachmentRef   ← 文書 1 件につき添付ノード取得
                                                     (さらにその中で本体 2 回)
```

`setCmisAttachmentProperties` は、型が content stream を許し `attachmentNodeId` が
あれば**要求プロパティに関係なく**添付を取りに行きます。上のクエリが要求したのは
`cmis:objectId, cmis:name` だけです。

### ACL 判定の現在地 (v3.3 で変わった)

**非 admin では ACL 判定は主として Solr の中で行われます。** readers による `fq` が
クエリに入り、`numFound` が既に認可済み件数です
(`SolrQueryProcessor.java:453-476`, `:492-498` のコメントが明言)。
in-memory の `permissionService.getFiltered` は defense-in-depth として残り、
実際に落とすのは admin (fq をバイパスする) と PWC の非所有者
(`PermissionServiceImpl.java:171-180`) に限られます。

### ステップ 4 が存在する理由 (消してはいけない)

ORDER BY と既定順序は**ページ切り出しの前に、認可済み全件に対して**適用する必要が
あります。Solr 側の並び (`modified desc`) でページを切ってから並べ替えると、
ページ 1 とページ 2 が全体順序と矛盾します (`maxItems=1` なら並べ替えが no-op)。
この設計判断は変更対象ではありません。

問題は「全件を並べ替えること」ではなく「並べ替えのために全件のプロパティを
完全に組み立てていること」です。

---

## 3. 案

### 案 0 — 添付メタデータ取得から本体ダウンロードを外す (最優先・他案と独立)

`AttachmentDaoDelegate.getAttachment` は `can.convert()` を呼び
([AttachmentDaoDelegate.java:44](../../core/src/main/java/jp/aegif/nemaki/dao/impl/couch/delegate/AttachmentDaoDelegate.java)),
その `CouchAttachmentNode.convert()` が内部で `client.getAttachment(getId(), "content")`
を実行して**本体を丸ごと取得**します
([CouchAttachmentNode.java:269](../../core/src/main/java/jp/aegif/nemaki/model/couch/CouchAttachmentNode.java))。
delegate はその直後に**もう一度**同じ本体を落とします (`:47`)。
`AttachmentNode.setInputStream` が 1 本目を `readAllBytes()` でヒープに全展開し、
2 本目は `contentBytes != null` に弾かれて**読まれも閉じられもしません**。

加えて `convert()` は repositoryId を受け取らず全リポジトリを順に試すため、
`repositories.yml` の順序 (canopy が先) によっては bedroom の添付 1 件ごとに
canopy への 404 プローブが先行します。

**filter の配管も objectDataCache の不変条件も要らず、全バインディングに効きます。**
`getContentStream` が `contentBytes` を当てにしているので、本体取得を
`getContentStream` 側へ明示的に移すこと。

### 案 B — Content から直接並べ替える (第一候補)

`Content` は `name` / `created` / `modified` / `creator` / `modifier` を既に持っています。
並べ替えキーがこの範囲なら ObjectData を作らずに比較できます。範囲外のキー
(カスタム型のプロパティ、stream 系) のときだけ現行の全件 compile にフォールバック。

**既定順序は `cmis:creationDate DESC`** (`nemakiware.properties:51` ほか 3 ファイルで一致)
で、これは `Content.getCreated()` そのものです。つまり ORDER BY を書かない
最も一般的なケースがそのまま適用範囲で、**compile が 1 件も走りません**。

- 効果: 案 0 と併せて cold は `getContent` 216 + ページ compile 25×2 + 残差 ≈ **286**。
- 安全性: compile の**産物を変えない**ので `objectDataCache` を汚染しえない。
  失敗しても「並び順が違う」で、決定的・再現可能・そのクエリの応答に閉じる。
- コスト: 比較規則の実体は `SortUtil.java:81-142` の 62 行。真のコストはキー解決側
  (`:47-68`, `:150-210`) だが、**それは案 A でも同額かかる**。

### 案 A — ソート用の compile を並べ替えキーに限定する (二次策)

**前提の訂正**: `getRawObjectData` に filter 引数は存在しますが
(`CompileServiceImpl.java:156-158`)、転送先の `compileObjectDataWithFullAttributes` は
それを**一度も参照しません** (`:175-188`)。**現状デッドパラメータ**であり、
「既存の filter 経路に乗せる」実装はできません。配管の新設が要ります。

**実装形を次に固定する**: ソート専用の compile は `getRawObjectData` を通さない
**別メソッド**とし、`objectDataCache` に **put も get もしない**。
これが無い案 A は採用不可 (§5-4)。

案 B の適用範囲外のキー (カスタム型プロパティ等) に対する二次策として残します。

### 案 F — Solr 側で ORDER BY してページングする (中期)

Solr 10 は `sort=content_length desc` / `sort=name asc` を docValues 無しで
処理できることを nb33 で確認済み (`status 0`, `QTime 0`)。索引側で並べ替えれば
全件走査そのものが不要になり、**`aclScanCap` の機能上限も外せます**。

- 前提: 索引可能なフィールドの whitelist と、範囲外キーのフォールバック。
- 未解決: `dynamic.*` が multiValued、`content_name` / `content_mimetype` が未索引、
  `sortMissing` の不整合、照合規則が Solr 側に移ることによる並び順の変化。

### 案 C — 添付メタデータを文書側に非正規化 / 案 D — 添付ノードのキャッシュ / 案 E — 何もしない

- **C**: 正しい方向だがマイグレーションを伴う。案 0 が同じ費用を配管変更なしで
  消すので、本件では不要。
- **D**: EhCache が既に同じことをしており warm は 0 往復。**cold の問題を解かない**。
- **E**: readiness は型定義を 1 個解決するだけで `objectDataCache` を温めません。
  また cap 10,000 == キャッシュ上限 10,000 なので、大規模クエリでは warm も
  成立しません。採れません。

検討したが採らない: `numItems` の概算化 / `aclScanCap` の引き下げ —
`numFound` は Solr が `rows=0` プローブで返すので、概算化しても往復は減りません。

---

## 4. 比較軸と順序

比較軸は「立証コスト」ではなく **失敗の検出可能性と波及範囲** です。

| | 失敗したときの症状 | 検出可能性 | 波及 |
|---|---|---|---|
| 案 A | プロパティ欠落 | **無音** | 全バインディング横断。`objectDataCache` に載ると最大 10,000 件・TTI 3600 秒・flush 経路なし・ノードローカル (multi-replica では 1 台だけ) |
| 案 B | 並び順の相違 | 決定的・再現可能 | そのクエリの応答に閉じる。V2/V3 が捕まえる |

**順序**:

1. **第 0 段** (どの案とも独立、先に単独コミット)
   - 0-a. 添付の二重ダウンロード除去 (案 0)
   - 0-b. `SortUtil` の INTEGER 比較を BigInteger に (§5-2)
   - 0-c. cache hit 経路の共有インスタンス書き換えを浅いコピーに
   - 0-d. `SortUtil.resolveSortKeys(repositoryId, orderBy)` の切り出し (§5-7)
2. **第 1 段** — 案 B
3. **第 2 段** — 案 A (範囲外キーの二次策、上記の実装形に固定)
4. **第 3 段** — 案 F (中期)

---

## 5. 実装した場合に壊れうるもの

1. **`ORDER BY cmis:contentStreamLength` は現在すでに HTTP 500**。
   `SortUtil.java:132` が `(Integer) val1` にキャストするが実体は BigInteger。
   稼働中のサーバで再現済み (`BigInteger cannot be cast to Integer`)。
   型定義は `orderable=true` を広告している。**0-b で先に直さないと V4 の基準が採れない。**
2. **既定順序 (`capability.extended.orderBy.default`)** は `cmis:creationDate DESC`。
   stream プロパティではないので既定構成では安全だが、`-D` / ENV / `nemaki_conf` で
   実行時に上書きできるため、**filter をビルド時定数にしてはいけない**。
3. **DESC で null が先頭に来る。** `SortUtil.java:104` のコメント
   「Null values are put to the last」は ASC でしか成立しない。
   commons-collections4 の `ComparatorChain.compare` は 0 以外の戻り値を `±1` に
   丸めて反転するため、null 番兵の `+1` / `-1` も反転される。
   既定順序が DESC なので、**ソートキーを欠いた ObjectData は 1 ページ目を占拠します**。
   → 不変条件: **ソートキーを持たない ObjectData を `SortUtil` に渡さない**。
   (既存挙動を変えるかは互換性判断。少なくとも現行を基準列として記録する)
4. **`objectDataCache`** のキーは `content.getId()` **のみ** (filter / ユーザ /
   includeXxx を含まない)、格納単位は完全な `ObjectDataImpl` 丸ごと。
   cache hit 経路は **properties を再構築せず**、ACL / AllowableActions /
   Relationships だけ作り直します (`CompileServiceImpl.java:161-171`)。
   応答段の `filterProperties` は既存キーを**削るだけ**で、`filter == null`
   (= `SELECT *`) のときはキャッシュ実体をそのまま返します (`:392-394`)。
   したがって **不完全な ObjectData を載せると、後続の無関係なリクエストが
   欠けたプロパティを返します**。
   **キーに filter を足す解決策は採れません** — 無効化の全経路が
   `objectId` 1 キーの `remove` に依存しており (`CacheService.java:299-313`)、
   キーを変えると applyAcl(PROPAGATE) の子孫 evict が取りこぼし、
   **権限剥奪が効かなくなります**。
5. **`numItems` の正確さ** — `ordered.size() != contents.size()` の safety net の
   挙動が変わらないこと。
6. **ページ内の `cmis:contentStreamLength`** は従来どおり埋まること (TCK / E2E が見る)。
7. **queryName と propertyId の 2 つの名前空間**。ORDER BY 文字列は queryName
   (`SolrQueryProcessor.java:771`)、SELECT 由来 filter は propertyId、ObjectData の
   キーは propertyId (`SortUtil.java:90-94`)。**両方が同時に必要**です。
   同じ構造のバグが `getChildren` に実在します — `compileObjectDataList` は
   `filterObjectDataInList` の**後に** `sortUtil.sort` を呼ぶため
   (`CompileServiceImpl.java:592` → `:601`)、`filter=cmis:objectId&orderBy=cmis:name`
   は並べ替えが黙って no-op になります。
8. **ソート用 compile は新しい `PropertiesImpl` を組むこと。** 既存インスタンスを
   mutate すると、応答経路が同じ実体を配っているため波及します。

---

## 6. 検証計画

| # | 何を | どう |
|---|---|---|
| V0 | 0-b の完了 | `ORDER BY cmis:contentStreamLength` が 200 を返すこと |
| V1a | 添付由来の往復 | 432 + 216 + 216 がページ長相当まで落ちること |
| V1b | 総往復数 | 1,318 → **600 未満** (当初の 300 は算術的に不能。撤回) |
| V1c | cold の wall clock | 25 ms sleep (`ContentServiceImpl.java:3595-3616`) の寄与を分離して計上 |
| V2 | 順序の同一性 | 改修前後の objectId 列を全ページ比較。**core 再起動直後の 1 リクエスト目で採る** |
| V3 | ページ境界 | `maxItems=1` 全ページ走査で重複・欠落なし。**cold 検証にはならない** (ページ 1 で全件がキャッシュに載る) ので単体テストで補う |
| V4 | stream プロパティ | `ORDER BY cmis:contentStreamLength` と、ページ内の値が非 null。**cold で採る** |
| V5 | TCK | Query 群を最初に実行した同一コンテナで Crud1/Crud2/Versioning を連続実行 |
| V6 | 全体回帰 | Maven 全件 |
| V7 | スループット | `--readonly` c=16 を**操作別 p50/p95** で。総 rps だけでは相殺を分離できない |
| V8 | cold burst | 16 並列が完走すること。**GC ログ・ヒープピーク・往復数・モニタ待ち本数を別々に**採る |
| V9 | 汚染の横断確認 | 大量ヒットのクエリを 1 本流した**後**、別リクエスト・別ユーザ・別バインディングで無関係な `getObject` / AtomPub エントリを叩き、プロパティが揃っていること |
| V10 | 順序の履歴依存 | `ORDER BY cmis:creationDate` の後に `ORDER BY cmis:name` を走らせ、再起動直後の単独実行と objectId 列が一致すること |
| V11 | 書込みレイテンシ | 16 並列クエリ中に別クライアントで createDocument / applyAcl を打ち p99 が劣化しないこと |

単体テスト (ソートキー解決 / null の順序 / filter 非汚染) は V ではなく**着手条件**とする。

---

## 7. 実装前に必ず取るべき基準データ

**採取は 0-b の後、それ以外のコード変更の前**に行うこと。

採取条件 — これを外すと全部無効になります:

- **凍結した bench 専用リポジトリで採る。bedroom は不可** (TCK / Playwright が母集合を変える)
- 採取〜比較の間に書込み・commit・再索引を挟まない
- 順序系は **core 再起動直後の 1 リクエスト目**で採る
- **先に tie-breaker を決める**。`SolrQueryProcessor.java:478` の sort は
  `modified desc` 単一キーで tie-breaker が無く、V2/V3 が改修と無関係に落ちうる
- 採取は `tools/` 配下にスクリプト化してコミットし、objectId 列を fixture として保存する

| # | 何を | なぜ後で採れないか |
|---|---|---|
| 1 | cold の CouchDB 全アクセスログ (db で絞らない) | canopy 404 プローブの有無を確定する唯一の機会。0-a を入れると消える |
| 2 | cold の wall clock を往復数と別に | 25 ms sleep の寄与を分離できるのは改修前だけ |
| 3 | 添付ノードが引けない文書の件数 | sleep の総量を後から按分できない |
| 4 | V2 の objectId 列 — 単一キー **ASC と DESC の両方** | DESC の null-first は改修で変わりうる。現行を記録しないと互換性判断ができない |
| 5 | 複数キー ORDER BY (昇降混在) の objectId 列 | 第 2 キー単独への縮退は 200 のまま出るので、基準が無いと気づけない |
| 6 | カスタム型 / セカンダリ型プロパティを ORDER BY した objectId 列 | 名前空間の取り違えは無音。WARN も出ない |
| 7 | 日本語・外字 (PUA)・絵文字・前後空白を含む `cmis:name` を 20 件以上混ぜた objectId 列 | 案 F を採るときの照合規則変更を検出する唯一の基準。ASCII では原理的に出ない |
| 8 | `ORDER BY cmis:contentStreamLength` の 200 応答と objectId 列 | **0-b の直後にしか採れない** |
| 9 | V3 のページ境界 | (cold 検証にはならないと明記のうえ) |
| 10 | warm の基準 2 種: (a) 216 件クエリ 2 回目 / (b) 1 万件近いクエリ直後の無関係な getObject | (b) が無いと「warm も成立しない」を後から立証できない |
| 11 | cold burst の GC ログ・ヒープピーク・スレッドダンプ | 律速が往復数かヒープ圧かモニタ待ちかを切り分ける唯一の機会 |
| 12 | `--readonly` c=16 の操作別 p50/p95 | 総 rps では相殺を分離できない |

---

## 8. ロールバック

- **フラグは作らない。** このプロジェクトは ACL-epoch で「切替スイッチを作らない」
  判断をしており、それに揃える。
- 巻き戻し単位は WAR の差し替え + `--build --force-recreate`。
- **`objectDataCache` を外から flush する経路が製品に無い**
  (`NemakiCachePool.clearAll()` / `clear()` は宣言と実装だけで呼び出し元 0 件)。
  汚染を起こした場合、巻き戻しには **core の再起動が必須**。

---

## 9. 未確認事項

- Solr 10 が docValues 無しフィールドをソートする内部機構と、searcher ごとの初回コスト
  (ソート自体が通ることは実測済み。**コスト特性は未確認**)
- readers 索引が PWC の所有者規則を同値に表現しているか
  (`PermissionServiceImpl.java:171-180` と索引の突き合わせ)
- Ehcache 3 ヒープ tier の `get` が同一参照を返すか
  (コード側が複製を作らないことは確定、Ehcache 側の保証は未確認)
- ベンチ `--readonly` シナリオに `getContentStream` が含まれるか
- CMIS 1.1 で `numItems` が optional か (効果ゼロなので実務上は無関係)

## 10. 本件と分離した別課題

- `bulkLock` が任意順でロックを取るためのデッドロック環の可能性
  (`ThreadLockServiceImpl.java:50-54`。未再現、本改修と独立に今日存在する)
- `SolrQueryProcessor.java:560` が `getFiltered` の**前**に全件ロックを取る
- `getChildren` の filter+orderBy 無音 no-op (§5-7。0-d で直る)
- DESC の null-first (§5-3) を修正するかどうかの互換性判断
