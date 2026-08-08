# クエリのスキャン費用を一致件数から切り離す

状態: **提案 (未実装)**
実測環境: 2026-08-08, Apple Silicon 14 core, nb33 スタック (3.3.0, bedroom 11,435 文書 / Solr 2,238)

---

## 1. 何が起きているか

CMIS クエリ 1 本 — `SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE 'bench-doc-000%'`、
`maxItems=25`、一致 216 件 — が CouchDB に投げるリクエスト数:

| | 往復数 | 所要 |
|---|---|---|
| cold (core 再起動直後) | **1,318** | 2.4 s |
| warm (2 回目) | **0** | 0.1 s |

内訳 (CouchDB アクセスログ):

| リクエスト | 件数 | 一致件数比 |
|---|---|---|
| `GET /bedroom/<id>` | 668 | ×3.09 |
| `GET /bedroom/<id>/content` | 432 | ×2.00 |
| `GET /bedroom/<id>?att_encoding_info=true` | 216 | ×1.00 |

**216 は一致件数そのもの**です。返すのは 25 行なのに、往復数はページ長ではなく
**一致件数に比例**します。1 万件ヒットするクエリなら 6 万往復になります
(`aclScanCap` の既定は 10,000 なのでこれは到達しうる値です)。

EhCache が温まっていれば 0 往復なので、平常時は見えません。冷えているとき —
**再起動直後・ローリングデプロイ直後・キャッシュ追い出し後** — にだけ出ます。
実際、16 並列のクエリを冷えた状態に当てると 50–90 秒返らず、その間 CouchDB は
18,313 リクエストを受けていました (ハングではなく、走り切れていない)。

---

## 2. どこで発生しているか

`SolrQueryProcessor.query()` ([SolrQueryProcessor.java:529-640](../../core/src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrQueryProcessor.java))
の流れ:

| # | 処理 | 対象件数 |
|---|---|---|
| 1 | Solr から最大 `aclScanCap` 件取得 | — |
| 2 | ヒットごとに `contentService.getContent()` | **全一致件数** |
| 3 | `permissionService.getFiltered()` | 全一致件数 |
| 4 | `compileService.sortContentsForSearchResult()` | **全一致件数** |
| 5 | ページ切り出し | — |
| 6 | `compileObjectDataListForSearchResult()` | ページ (25) |

**ステップ 6 は既にページ限定です。** 増幅しているのはステップ 4 です。

`sortContentsForSearchResult` ([CompileServiceImpl.java:708](../../core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java))
は認可済み全件に対して `getRawObjectData(...)` を呼びます。コード上のコメントは
これを "lightweight ObjectData (properties only — no allowable actions /
relationships / ACL)" と説明していますが、**properties の組み立て自体が重い**:

```
getRawObjectData
  └ compileProperties
      └ compileDocumentProperties
          └ setCmisAttachmentProperties
              └ ContentService.getAttachmentRef   ← 文書 1 件につき CouchDB 往復
```

`setCmisAttachmentProperties` は、型が content stream を許し `attachmentNodeId` が
あれば**要求プロパティに関係なく**添付ノードを取りに行きます
(`cmis:contentStreamLength` / `contentStreamMimeType` / `contentStreamFileName` /
`contentStreamId` を埋めるため)。上のクエリが要求したのは
`cmis:objectId, cmis:name` だけです。

スレッドダンプでも同じ経路が見えています。冷えた 16 並列の最中、
`compileAllowableActions → ContentService.getVersionSeries` と
`setCmisAttachmentProperties → getAttachmentRef` に張り付いていました。

### ステップ 4 が存在する理由 (消してはいけない)

ORDER BY と既定順序は**ページ切り出しの前に、認可済み全件に対して**適用する必要が
あります。ACL 判定は Solr が返した後に行うため、Solr 側の並び (modified desc) で
ページを切ってから並べ替えると、ページ 1 の内容とページ 2 の内容が全体順序と
矛盾します (`maxItems=1` なら並べ替えが no-op になる)。この設計判断はコード内に
明記されており、**変更対象ではありません**。

問題は「全件を並べ替えること」ではなく「並べ替えのために全件のプロパティを
完全に組み立てていること」です。

---

## 3. 案

### 案 A — ソート用の compile を並べ替えキーに限定する (推奨)

`sortContentsForSearchResult` が `getRawObjectData` を呼ぶ際に、
**実際の ORDER BY キー (と既定順序キー) だけを含む filter** を渡す。
`compileDocumentProperties` 側は filter に stream 系プロパティが含まれないなら
`setCmisAttachmentProperties` をスキップする。

- 効果: 添付往復 (216 + 432) が消える。
- 残る費用: `getContent` (216) と `getVersionSeries`。
- 前提: `getRawObjectData` に filter を渡す経路が既にあること (要確認)。
- 危険: **filter が効いていない他の呼び出し元まで挙動が変わらないか**。
  `setCmisAttachmentProperties` の skip 条件を誤ると
  `cmis:contentStreamLength` が欠けた ObjectData が返る。

### 案 B — Content から直接並べ替える

`Content` モデルは既に `name` / `created` / `modified` / `creator` / `modifier` を
持っている。並べ替えキーがこの範囲なら ObjectData を作らずに比較できる。
範囲外のキー (カスタム型のプロパティ、`cmis:contentStreamLength` 等) のときだけ
現行の全件 compile にフォールバックする。

- 効果: 一般的なケースで全件 compile が丸ごと消える (`getContent` の 216 は残る)。
- 危険: 並べ替え結果が現行と**一致する保証**が要る。`SortUtil` の比較規則
  (null の扱い、型ごとの比較、既定順序の解決) を二重実装することになる。
  ここがこの案の最大の弱点。

### 案 C — 添付メタデータを文書側に持つ

`length` / `mimeType` / `fileName` を content 文書に非正規化して保持し、
添付ノードを引かずに済ませる。

- 効果: 添付往復が原理的に消える (ソート経路だけでなくページ compile でも)。
- 危険: **既存文書のマイグレーションが要る**。非正規化した値と実体がずれる経路
  (直接 CouchDB を触る運用、レプリケーション) が生まれる。ACL-epoch と同様の
  「二重管理を fence する」設計が要る。今回の目的に対しては過剰。

### 案 D — 添付ノードのキャッシュ

`getAttachmentRef` の結果をキャッシュする。

- 効果: 2 回目以降は速い。
- 危険: 現状 EhCache が既にそれをやっている (warm で 0 往復) ので、**cold の問題は
  解けない**。冷えているときが問題なので、これは的を外している。

### 案 E — 何もしない

warm では 0 往復であり、実運用の定常状態では見えない。

- 危険: ローリングデプロイのたびに顕在化する。readiness で LB からは隠せるが、
  replica が pool に入った直後の実ユーザは踏む。一致件数に比例するため、
  データが増えるほど悪化する。

---

## 4. 推奨

**案 A を第一候補、案 B を将来の拡張**とする。

案 A は「要求されていないものを取りに行かない」という是正であり、
並べ替えの意味論を変えない。案 B は効果が大きいが `SortUtil` の比較規則を
別経路で再現することになり、順序が現行と一致することの立証コストが高い。

案 C は正しい方向だが、マイグレーションを伴うため本件とは分けて判断すべき。

---

## 5. 実装した場合に壊れうるもの (レビュー対象)

1. **`ORDER BY cmis:contentStreamLength`** — stream プロパティを並べ替えキーに
   使うクエリ。案 A の filter にこれが含まれれば添付取得は残るはずだが、
   filter の伝播が 1 か所でも抜けると**黙って null 順**になる。
2. **既定順序 (`capability.extended.orderBy.default`)** — ORDER BY 無しのとき
   `SortUtil` が解決する。この既定値が stream プロパティだった場合、案 A の
   filter 計算が間違うと同じ問題。
3. **ページ境界の安定性** — 並べ替えが全件に対して行われる性質は維持されるが、
   compile を軽くしたことで**同値キーの並びが変わる**可能性。`SortUtil` が
   安定ソートでない場合、ページ 1 とページ 2 で重複・欠落が出る。
4. **`objectDataCache` との相互作用** — 現行はソート時の compile が
   `objectDataCache` を温め、ページ compile がそれを再利用する構造になっている
   (コード内コメント)。ソート compile を軽くすると**ページ compile が
   フルの仕事をやり直す**。25 件なので総量は減るはずだが、
   キャッシュのキーが「完全な ObjectData」前提なら**不完全なものが載って
   ページ compile が欠けたプロパティを返す**危険がある。ここが最も危ない。
5. **`numItems` の正確さ** — 認可済み総数は変わらないはずだが、
   compile が失敗した content を落とす safety net
   (`ordered.size() != contents.size()`) の挙動が変わらないこと。
6. **`cmis:contentStreamLength` の値** — TCK と E2E が見る。ページ内では
   従来どおり埋まる必要がある。
7. **secondary type / custom type のプロパティを ORDER BY するクエリ** —
   filter の組み立てが `queryName` ベースか `id` ベースかで取り違えると空振りする。

---

## 6. 検証計画

| # | 何を | どう |
|---|---|---|
| V1 | cold の往復数 | CouchDB アクセスログを 1 クエリぶん数える。1,318 → 目標 300 未満 |
| V2 | 順序の同一性 | 同一データで改修前後のクエリ結果の objectId 列を全ページ比較 |
| V3 | ページ境界 | `maxItems=1` で全ページを走査し、重複・欠落が無いこと |
| V4 | stream プロパティ | `ORDER BY cmis:contentStreamLength` と、ページ内の `cmis:contentStreamLength` が非 null |
| V5 | TCK | QueryTestGroup 6/6 |
| V6 | 全体回帰 | Maven 全件 |
| V7 | スループット | `--readonly` c=16。現行 129.2 rps から劣化しないこと |
| V8 | cold burst | 再起動 → 16 並列クエリが 90 秒でタイムアウトしないこと |

V2 と V3 は**改修前に取得した基準列との比較**が要る。先に基準を採取すること。

---

## 7. 未確認事項

- `getRawObjectData` に property filter を渡せるか (シグネチャ確認が必要)
- `objectDataCache` のキーと格納単位 (完全な ObjectData か、プロパティ単位か)
- `SortUtil.sort` が安定ソートか
- `capability.extended.orderBy.default` の既定値
- 668 の doc GET の内訳 (`getContent` 216 + `getVersionSeries` 216 + 残り 236 は何か)
