# P1-1(d) — 「どの型が証拠か」

棚卸し §7 の 3 番目。[`p1-1c-evidence-updatability.md`](p1-1c-evidence-updatability.md) §5.2 が
**開いたまま (d) に送った穴**で、A.1 の「分かち難く結合した保護属性」を名乗れるかどうかが
ここにかかっている。

> 前提: モデル本体 ([`p1-1d-evidence-data-model.md`](p1-1d-evidence-data-model.md)) が
> 通っていること。**「証拠の型」は「証拠とは何か」が決まってからでないと決められない。**

---

## 0. 何を主張し、何を主張しないか

**主張する**: 取込が刻んだ証拠は、**CMIS クライアントからは型ごと外すこともできない**。

**主張しない**:

- **「管理者でも消せない」とは言わない。** CouchDB に直接届く者、およびオブジェクトごと
  削除できる者は到達する。**証拠はオブジェクトと運命を共にする** — それがここでの境界
- **他の secondary type を外せなくするとは言わない。** 証拠でない型の付け外しは
  CMIS の正当な操作で、そのまま通す
- **これ以前に外された aspect は戻らない。** 遡って復元する手段は無い

---

## 1. 現状 (コードで確認)

| | |
|---|---|
| `cmis:secondaryObjectTypeIds` の updatability | 既定で `readwrite` |
| 置換の実体 | `buildSecondaryTypes` ([`ContentServiceImpl:2550`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L2550)) が、リクエストに id リストが在れば**それを唯一の真**として aspect を組み直す |
| 呼び出し側 | `modifyProperties` ([`:2518`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L2518)) が `content.setAspects(secondary)` — **空リストでも設定する**。コメントに「isEmpty() チェックが secondary type の削除を妨げていた」と明記されている |
| 帰結 | `nemaki:chatContextMetadata` を含まないリストを **1 回送れば、証拠 11 個が aspect ごと消える**。プロパティ単位の READONLY は**ループがその型を訪れないので効かない** |
| 取込側 | `IngestMetadataService` は `ensureSecondaryType` + `contentService.update` を直接使い、`modifyProperties` を通らない。**影響を受けない** |

**削除が「できてしまう」のではなく、意図して実装されている。** だから塞ぐのは
「バグを直す」ではなく「例外を設ける」ことであり、その例外の境界を決めるのがこの文書である。

---

## 2. 「証拠の型」をどこに置くか

| 案 | 中身 | 判定 |
|---|---|---|
| **(i) 製品内の明示リスト** | `EvidenceTypes.PROTECTED` に型 id を並べる | **採る**。(c) の `EVIDENCE_PROPERTIES` と同じ形で、読めば分かる |
| (ii) 型定義に印を付ける | `NemakiTypeDefinition` に `evidence` フラグ | 正しい長期解だが、**型システム変更 + パッチ + 移行**が要る。(d) の範囲を超える |
| (iii) updatability から導く | 「全プロパティが READONLY な secondary type」を証拠とみなす | **採らない**。READONLY は「サーバが値を持つ」であって「証拠」ではない (`cmis:createdBy` が READONLY なのと同じ理由)。混在する型で答えが出ない |

### 2.1 (i) の弱点と、その機械的な埋め方

明示リストは**足し忘れる**。埋め方は (c) が既に持っている:

**保護対象のプロパティを持つ型は、必ず証拠型リストに載っていなければならない。**

`Patch_ChatContextEvidenceReadOnly.EVIDENCE_PROPERTIES` の 11 個が
`nemaki:chatContextMetadata` に属することは型定義から導ける。したがって
「READONLY 化した証拠プロパティを持つ型 ⊆ `EvidenceTypes.PROTECTED`」を**テストで固定**すれば、
新しい証拠型を READONLY にしただけでリストに足し忘れると落ちる。

> **逆向き (`PROTECTED` ⊆ 証拠プロパティを持つ型) は固定しない。** 証拠だがまだ
> READONLY 化していない型を先にリストへ入れる、という順序があり得るため。

---

## 3. 何を「保つ」のか — ここを間違えると空の殻が残る

aspect を保つだけでは足りない。`modifyProperties` は **`secondaryIds` も同時に更新する**
([`:2524`](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/ContentServiceImpl.java#L2524))。
aspect だけ残して id を落とすと、**プロパティは在るのに型が付いていない**状態になり、
CMIS からは見えないまま CouchDB に残る — 「消えていない」と言えるが「証拠として読める」
とは言えない。

**したがって保つのは 2 つ:**

1. 証拠型の `Aspect` (プロパティごと)
2. `secondaryIds` の中のその型 id

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

> **逃げ道は「オブジェクトごと消す」だけである。** 誤って取り込んだものを片付ける手段は
> 残っている — そして証拠だけを外して中身を残す、はできない。**それが意図である。**

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | 証拠型を**含まない** `cmis:secondaryObjectTypeIds` を送っても、aspect が**残る** | 保護を外すと落ちる |
| 2 | そのとき **`secondaryIds` にも型 id が残る** — 条件 1 だけだと空の殻を通す | id の保護だけ外すと落ちる (§3) |
| 3 | **証拠でない** secondary type は**従来どおり外せる** — 条件 1 の control | 全 aspect を保つ実装にすると落ちる |
| 4 | 証拠型の**プロパティ**は従来どおり保たれる ((c) §5.1) | 既存の条件 9・10 |
| 5 | **READONLY 化した証拠プロパティを持つ型は、証拠型リストに載っている** | 型を足して リストに足さないと落ちる (§2.1) |
| 6 | 取込自身は**従来どおり aspect を付けられる** | 保護を「誰も触れない」に広げると落ちる |
| 7 | オブジェクトの**削除は従来どおり通る** — 逃げ道が塞がっていないことの control | 削除まで拒否すると落ちる |

---

## 6. 運用文書に書くこと

- **証拠型は `cmis:secondaryObjectTypeIds` から外せない。** リクエストは 400 にならず、
  **その型だけが黙って残る**。プロパティの READONLY と同じ挙動 ((c) §7)
- **誤って取り込んだものを片付ける手段はオブジェクトの削除**であって、証拠型を外すことではない
- **これ以前に外された aspect は戻らない**

---

## 7. やらないこと

- 型定義への印 ((ii))。型システム変更を伴うので、必要になったときに別途
- 他の secondary type の保護。**証拠型だけ**が対象
- 削除の制限。**証拠はオブジェクトと運命を共にする**のがここでの境界 (§0)
- 「分かち難く結合した保護属性」(A.1) の主張そのもの。**この作業が済んだら
  改めて範囲を確かめて名乗る** — 先に名乗らない
