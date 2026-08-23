# P1-1(d) — 「どの型が証拠か」

棚卸し §7 の 3 番目。[`p1-1c-evidence-updatability.md`](p1-1c-evidence-updatability.md) §5.2 が
**開いたまま (d) に送った穴**で、A.1 の「分かち難く結合した保護属性」を名乗れるかどうかが
ここにかかっている。

> 前提: モデル本体 ([`p1-1d-evidence-data-model.md`](p1-1d-evidence-data-model.md)) が
> 通っていること。**「証拠の型」は「証拠とは何か」が決まってからでないと決められない。**

---

## 0. 何を主張し、何を主張しないか

**主張する**: **証拠型と宣言した secondary type**は、CMIS クライアントからは型ごと外せない。

**主張しない**:

- **「管理者でも消せない」とは言わない。** CouchDB に直接届く者は到達する
- **他の secondary type を外せなくするとは言わない。** 証拠でない型の付け外しは
  CMIS の正当な操作で、そのまま通す
- **これ以前に外された aspect は戻らない。** 遡って復元する手段は無い
- **複製した文書からは剥がせなくなる。** `buildCopyDocument` が aspect と `secondaryIds` を
  複写するので、copy / checkOut / checkIn の産物は**そのオブジェクトについては起きていない
  capture** を記述する証拠を持つ。今は secondary 型リストで剥がせるが、この変更後はできない
- **削除しても証拠は消えない。** §4.1 のとおり — 初稿はここを逆に書いていた

---

## 1. 現状 (コードで確認)

| | |
|---|---|
| `cmis:secondaryObjectTypeIds` の updatability | 既定で `readwrite` |
| 置換の実体 | `buildSecondaryTypes` ([`ContentServiceImpl:2550`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L2550)) が、リクエストに id リストが在れば**それを唯一の真**として aspect を組み直す |
| 呼び出し側 | `modifyProperties` ([`:2518`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L2518)) が `content.setAspects(secondary)` — **空リストでも設定する**。コメントに「isEmpty() チェックが secondary type の削除を妨げていた」と明記されている |
| 帰結 | `nemaki:chatContextMetadata` を含まないリストを **1 回送れば、その型に書けていた分がすべて aspect ごと消える**。プロパティ単位の READONLY は**ループがその型を訪れないので効かない** |
| 取込側 | `IngestMetadataService` は `ensureSecondaryType` + `contentService.update` を直接使い、`modifyProperties` を通らない。**影響を受けない** |

**削除が「できてしまう」のではなく、意図して実装されている。** だから塞ぐのは
「バグを直す」ではなく「例外を設ける」ことであり、その例外の境界を決めるのがこの文書である。

> 「11 個」は**上限であって実数ではない**。`applyArchetypeMetadata` は非空の値しか書かないので、
> 実際に消えるのは「その型に書けていた分」である。数を間違えた履歴があるので、
> **数ではなく範囲で書く** (外部レビュー)。

### 1.1 第 3 の経路 — **リクエストが型リストを含まなくても消える**

`buildSecondaryTypes` が aspect を組み直すのは
`td != null && baseTypeId == CMIS_SECONDARY` のときだけで、外れた分は握り潰され、
縮んだリストで上書きされる。そして `ids` は**リクエストに型リストが無ければ
`getSecondaryTypeIds(content)`**、つまり現在の aspect 名である。

**したがって `cmis:secondaryObjectTypeIds` を一切送らない、名前を変えるだけの更新でも、
型定義が解決できなければ証拠 aspect が消える。**
`TypeManagerImpl.getTypeDefinition` は型キャッシュが無いとき null を返し、
型削除中は例外を投げ、どちらも同じ catch に吸われる。

**ローリング再起動中の一過性キャッシュミスで起きる。** これは (c) §5.1 が
プロパティについて「型定義が読めないなら引き継ぐ — 読めない障害で証拠を失う方が悪い誤り」
と決めた方針の、**一段上での逆**である (外部レビュー)。

**したがって規則は 2 つ要る**: 型リストから外れていても保つ、**かつ**
型が解決できなくても保つ。前者だけ入れると、ガードは通るのに aspect は消える。

---

## 2. 「証拠の型」をどこに置くか

| 案 | 中身 | 判定 |
|---|---|---|
| **(i) 製品内の明示リスト** | `EvidenceTypes.PROTECTED` に型 id を並べる | **採る**。(c) の `EVIDENCE_PROPERTIES` と同じ形で、読めば分かる |
| (ii) 型定義に印を付ける | `NemakiTypeDefinition` に `evidence` フラグ | 正しい長期解だが、**型システム変更 + パッチ + 移行**が要る。(d) の範囲を超える |
| (iii) updatability から導く | 「全プロパティが READONLY な secondary type」を証拠とみなす | **採らない**。READONLY は「サーバが値を持つ」であって「証拠」ではない (`cmis:createdBy` が READONLY なのと同じ理由)。混在する型で答えが出ない |

### 2.1 リストに何を入れるか — **chat だけでは足りない**

初稿は「READONLY 化した証拠プロパティを持つ型 ⊆ PROTECTED」を機械的に固定する、と書いた。
**その規則だと、いちばん守るべき型が構造的に入らない** (外部レビュー)。

`nemaki:contentHash` は — 内容の完全性の証拠であり、かつ dedupe のキーでもある —
chat の aspect ではなく **`nemaki:externalIntegration`** に入る。同じ aspect に
`nemaki:sourceObjectId` / `sourceSystem` / `ingestionRunId` / `sourceUrl` も入る。
(c) はこれらを READONLY にしていない (「同じ問題が在る。この作業では chat だけ」と明記)。
規則が「READONLY 化したもの」を起点にする以上、**この型は永久にリストに入らない**。

**具体的な壊れ方**: `cmis:secondaryObjectTypeIds = ["nemaki:chatContextMetadata"]` を送る。
新しいガードは通り、`nemaki:externalIntegration` が丸ごと落ちる。`contentHash` が消え、
**次のポーリングが別バージョンとして再取込する**。

**したがって `PROTECTED` は明示的に 2 つ**:

| 型 | なぜ |
|---|---|
| `nemaki:chatContextMetadata` | (c) が READONLY 化した 11 個の家 |
| `nemaki:externalIntegration` | `contentHash` と source identity の家。**保護は無いが証拠である** |

### 2.2 足し忘れを機械的に止められるか — **できない。そう書く**

初稿は「テストで固定すれば落ちる」と書いた。**書けない** (外部レビュー):

- `EVIDENCE_PROPERTIES` は**プロパティ id の平坦なリスト**で、型情報を持たない
- 型↔プロパティの対応は `Patch_ChatContextMetadataSecondaryType` の
  **メソッド内ローカル配列**にあり、`TYPE_ID` も private。外から到達できない
- DB 側の `NemakiTypeDefinition.properties` は**プロパティ id ではなく detail ノード id**

**対処**: 型パッチの `TYPE_ID` と属性配列を `public static final` に上げ、
**静的に含意を固定できる形にしてから**テストを書く。上げないなら、
条件は「統合テスト」と明記して単体では主張しない。**「テストがある」と書いて
実際には書けない、が最悪である。**

---

## 3. 何を「保つ」のか — ここを間違えると空の殻が残る

`modifyProperties` は `secondaryIds` を**直前に組んだ aspect リストから導出する**
([`:2533`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L2533))。
したがって **aspect を戻せば id は自動的に戻る** — 初稿は「id も別に守る必要がある」と
書いていたが、**外せる部品が存在しない**ので、その負のコントロールは作れない (外部レビュー)。

**そして危険な向きは逆だった。** `CompileServiceImpl` は `secondaryIds` で回して
aspect から値を引くので、**id が在って aspect が無い**と「証拠型が付いていて中身が全部 null」
になる。「証拠が在ると称して中身が無い」で、真正性の上では**見えない状態より悪い**。

しかもこの向きは当時**現に作れた**: `setBaseProperties` は生のリクエスト値を `secondaryIds` に
無条件で書き、aspect は `if (!isEmpty(secondary))` のときしか書かなかった。
**→ 2026-08-23 に両方の製造元を閉鎖**: 作成時の `secondaryIds` は組み上がった aspect から
導出 (D-8、`SecondaryIdsMatchAspectsAtCreateTest`)。残っていた製造元は zip import 経路
(証拠型 id だけ通り、READONLY 値は作成時に落ちて殻になる)。正確には**細工または他製品の
archive に限る** — 製品自身の exporter は aspects も `cmis:secondaryObjectTypeIds` も
meta.json に書かないため、製品の export→import 往復では殻はできない (レビュー F-8)。これは
`ZipImporter.stripEvidenceAssertions` が import 前に**型 id ごと主張を除去して警告に名指し**する
ことで閉じた (D-6。archive restore との分裂の設計判断は同メソッドの javadoc)。

**したがって保つのは aspect であり、id はそれに従う。** 条件は「id が残ること」ではなく
**「id と aspect が食い違わないこと」**で書く。

---

## 4. 拒否か、沈黙か

| | 内容 | 代償 |
|---|---|---|
| **(A) 黙って保つ** | リストから外れていても aspect と id を残す。リクエストは成功する | クライアントは「外した」と思っている。**CMIS の「リストが真」という契約から外れる** |
| (B) 拒否する | `CmisConstraintException` を投げる | 証拠型を含まない更新が**全部落ちる** — 証拠に触るつもりのないクライアントまで巻き込む |

**(A) を採る。** 理由は 2 つ:

- (c) §5.1 が**プロパティについて既に (A) を選んでいる** — READONLY のプロパティは
  リクエストに載っていても引き継ぐ。型だけ (B) にすると、同じ「証拠を守る」動作が
  経路によって成功したり失敗したりする
- (B) は**証拠に無関係な更新を壊す**。`cmis:secondaryObjectTypeIds` を送るクライアントは
  自分が触っている型のことしか考えていない

**ただし沈黙はしない。** WARN を出し、運用文書に書く (§6)。

> **CMIS 仕様本文は確認していない。** OpenCMIS の add/remove secondary type は
> クライアント側の糖衣で、完全なリストを送る — **wire の挙動は確認した**。
> しかし外せない場合に仕様が `CmisConstraintException` を**要求している**なら、
> (A) は「選択」ではなく「逸脱」になる。**未確認と書いておく。**

### 4.1 逃げ道について — **初稿は事実を取り違えていた**

初稿は「逃げ道はオブジェクトごと消すことだけ」と書いた。**削除は証拠を消さない**
(外部レビュー):

- `archive.create.enabled` は**既定 true**。`deleteInternal` は archive を作ってから本体を消す
- `ArchiveDaoDelegate.createArchive` は**生の CouchDB 文書を丸ごと**複写する。
  コメントが対象を名指ししている — 「preserve ALL fields (… **secondaryIds, aspects** …)」
- `restoreContent` の除外リストに `aspects` / `secondaryIds` は**無い**。**restore で証拠ごと戻る**
- 本当に消すには `destroyArchive` という**別の操作**が要る

**さらに来歴イベントは別 DB (`nemaki_lineage`) に在り、削除は一切触らない。**
削除がするのは消去ではなく purge の**記録**で、しかも `destroyArchive` のときだけである。

**したがって「証拠はオブジェクトと運命を共にする」は二重に誤りだった。**
正しくは: **aspect は archive へ、来歴は原位置に残る。** 誤取込の後始末は
`destroyArchive` まで行う必要があり、PII を含む証拠は archive に残る
([`p1-1d-evidence-disclosure.md`](p1-1d-evidence-disclosure.md) に直結する)。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | 証拠型を**含まない** `cmis:secondaryObjectTypeIds` を送っても、aspect が**残る** | 保護を外すと落ちる |
| 2 | **型定義が解決できなくても**証拠 aspect が残る (§1.1 の第 3 の経路) | 解決失敗時の引き継ぎを外すと落ちる。`getTypeDefinition` に null を返させて確かめる |
| 3 | `secondaryIds` と aspect が**食い違わない** (更新経路) | `modifyProperties` は aspect リストから id を導出するので**構造的に真**。**弱い条件だと明記する**。作成経路 (`setBaseProperties`) は生のリクエスト値を id に書くので食い違いを作れるが、**作成時には守るべき既存の証拠が無い**ので、この増分では触らない |
| 4 | **証拠でない** secondary type は**従来どおり外せる** — 条件 1 の control | 全 aspect を保つ実装にすると落ちる |
| 5 | `nemaki:externalIntegration` も**外せない** (§2.1) | chat だけ守る実装だと落ちる。**`contentHash` が消えると次のポーリングが再取込する** |
| 6 | 証拠型の**プロパティ**は従来どおり保たれる ((c) §5.1) | 既存の条件 9・10 |
| 7 | 取込自身は**従来どおり aspect を付けられる** | 構造的に真 ((c) §3 の経路を通らない)。**弱い条件だと明記する** |

> 初稿の条件 7 (「削除は従来どおり通る」) は削除した。**この変更は削除に触れないので、
> 落ちる実装が想像できない** — 規則の言い換えである (外部レビュー)。

---

## 6. 運用文書に書くこと

- **証拠型は `cmis:secondaryObjectTypeIds` から外せない。** リクエストは 400 にならず、
  **その型だけが黙って残る**。プロパティの READONLY と同じ挙動 ((c) §7)
- **誤って取り込んだものを片付けるには `destroyArchive` まで必要である。**
  通常の削除は archive に証拠ごと複写し、restore で戻る (§4.1)
- **来歴イベントは削除では消えない。** 別 DB に残る
- **これ以前に外された aspect は戻らない**
- **複製した文書は、そのオブジェクトについては起きていない capture の証拠を持つ。**
  この変更後は剥がせない (§0)

---

## 6.1 実装 (2026-08-22)

- `EvidenceTypes.PROTECTED` に 2 型
- `buildSecondaryTypes` の末尾で `keepEvidenceAspects` — **2 経路とも**同じ場所で塞がる
  (リストから外れた場合も、型が解決できなかった場合も、「組み直した結果に居ない」で捕まる)
- `secondaryIds` は `modifyProperties` が aspect リストから導出するので**自動的に戻る**

負のコントロール: `keepEvidenceAspects` の呼び出しを外すと **6 件中 4 件**が落ちる
(named removal 2 件、型解決失敗 1 件、プロパティ保持 1 件)。

---

## 7. やらないこと

- 型定義への印 ((ii))。型システム変更を伴うので、必要になったときに別途
- 他の secondary type の保護。**証拠型だけ**が対象
- 削除・archive・`destroyArchive` の挙動の変更。**事実として記録するだけ** (§4.1)
- `bulkUpdateProperties` の `removeSecondaryTypeIds` — **現状そもそも読まれていない**
  (既存の不具合)。この作業では触らない
- 「分かち難く結合した保護属性」(A.1) の主張そのもの。**この作業が済んだら
  改めて範囲を確かめて名乗る** — 先に名乗らない
