# P1-1(c) — 証拠プロパティを書き換えられなくする

対象: [`authenticity-roadmap.md`](authenticity-roadmap.md) が P1-1(c) として残した更新制約。

> `Patch_ChatContextMetadataSecondaryType` は既存型にプロパティ id を足すだけで updatability を
> 書き換えないので、**コードを変えても新規デプロイにしか効かず構成が割れる**。既存プロパティ
> 定義を書き換える移行パッチと、我々自身の aspect 直接更新が阻まれないかの確認が要る。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 取込が刻んだ証拠プロパティが、**CMIS クライアントからは書き換えも消去も
できない**。既存デプロイでも効く。

> **初稿は「書き換えられない」しか主張していなかった。** レビューが、READONLY にするだけ
> では**書き換えの代わりに消去ができる**ことを示した (§5.1)。主張を狭めるのではなく、
> 消去の方も塞いだ。

**主張しない**:

- **「管理者でも書き換えられない」とは言わない。** これはアプリケーション層の制約で、
  CouchDB に直接届く者は到達できる。物理的な不変性ではない
- **`cmis:secondaryObjectTypeIds` から型ごと外す経路は塞いでいない** (§5.2)。
  プロパティ単位の updatability はその経路に効かない
- **管理 API の型更新と export/import は保護を巻き戻せる** (§5.3)。どちらも admin が要るが、
  **CouchDB への直接アクセスは不要**である
- **ローリング再起動中は、未再起動のレプリカが保護前の型定義で動く** (§5.4)
- **既に書き換えられた値は元に戻らない。** この変更以前に CMIS 経由で編集された値は
  そのまま残る。**遡って検証する手段は無い**
- **証拠が正しいことは言わない。** 書き換えを止めることと、書かれた値が事実であることは別
- **取込以外の書き手を止めるとは言わない。** 我々自身の aspect 直接更新は**意図的に**通る
  (§3)。それが通らないと取込そのものができない

---

## 1. 現状の事実 (コードで確認)

| | |
|---|---|
| 対象プロパティ | `nemaki:chatContextMetadata` の **11 個** (string 8 + datetime 3)。初稿は 10 と書いており、**数を検算していなかった**。全て `Updatability.READWRITE`、全て optional |
| パッチの挙動 | `mkStr` / `mkDt` は **既存プロパティなら早期 return** する (`if (c != null) { ... return d.get(0).getId(); }`)。コード側の `setUpdatability` は**新規作成時にしか通らない** |
| 帰結 | 3.4 で code を変えても、**既に動いているデプロイの updatability は READWRITE のまま**。同じ版のはずの 2 台で挙動が違う |
| updatability の保持場所 | `NemakiPropertyDefinitionDetail.updatability` (core ではなく **detail**) |
| 書き換え API | `TypeService.updatePropertyDefinitionDetail(repositoryId, detail)` |
| CMIS 更新経路の検査 | `ContentServiceImpl.injectPropertyValue` が `switch (pd.getUpdatability())` で **`READONLY` を `continue`** する = **値が無視される** |
| 取込の書込経路 | `IngestMetadataService` が `Property` を**直接**組んで `mergeAspect` → `contentService.update`。`injectPropertyValue` を**通らない** |

**したがって READONLY にすると、CMIS クライアントは書けず、取込は書ける。** これが
A.1 の「分かち難く結合した保護属性」に必要な形である。

---

## 2. なぜ READONLY か (他の 3 値ではなく)

| | なぜ採らないか |
|---|---|
| `READWRITE` | 現状。誰でも書き換えられる |
| `ONCREATE` | 作成時のみ。**取込はプロパティを作成後の別更新で書く**ので、これだと取込自身が書けない |
| `WHENCHECKEDOUT` | PWC のときだけ。証拠を書き換えるために checkout するという経路を**開いてしまう** |
| **`READONLY`** | CMIS 経由の書込を無視する。取込は別経路なので通る |

**`READONLY` の意味を誤解しないこと。** CMIS の `READONLY` は「サーバが値を決める」であって
「誰も書けない」ではない。`cmis:createdBy` や `cmis:creationDate` と同じ扱いになる。

---

## 3. 我々自身の書込が阻まれないことを確かめる

これが**この作業でいちばん壊しやすい所**である。READONLY にしたら取込が自分の証拠を
書けなくなる、という失敗が成立しうる。

コードで確認した事実:

- `injectPropertyValue` は **CMIS の `updateProperties` 経路**でしか呼ばれない
  (`buildSubTypeProperties` と `buildSecondaryTypes` の 2 箇所、どちらも `Properties` 型の
  リクエストを受け取る)
- `IngestMetadataService` は `Properties` を作らない。`Property` (単数形、モデル層) を
  組んで `Aspect` に入れ、`contentService.update(callContext, repositoryId, content)` を呼ぶ

**受入条件 3・4 (§6) がこれを固定する。** 「取込が書ける」と「CMIS が書けない」を
**同じテストで**確かめる — 片方だけだと、両方書けない実装でも片方は通る。

---

## 4. 移行パッチ

新しいパッチ `Patch_ChatContextEvidenceReadOnly` を足す。**既存パッチは変えない** —
`mkStr` / `mkDt` の早期 return は「既に在るなら作らない」という正しい挙動で、
そこに updatability の書き換えを混ぜると、**プロパティ作成と権限変更が同じ分岐に入る**。

パッチがすること:

1. 対象 10 プロパティについて `getPropertyDefinitionCoreByPropertyId` → detail を取得
2. `updatability` が既に `READONLY` なら**何もしない** (冪等)
3. `READWRITE` なら `READONLY` に書き換えて `updatePropertyDefinitionDetail`
4. 型キャッシュを invalidate

**このパッチは 1 度しか走らない。** `AbstractNemakiPatch.apply` は `isApplied` を見て、
`applyPerRepositoryPatch` が例外を投げずに戻れば **applied を恒久記録**する。初稿の
「毎起動で走りうる」は誤り。

> したがって固定すべきは冪等性ではなく「**何も出来なかったときに applied を記録しない**」
> ことである。プロパティ定義は view 経由で読むので、**view が沈黙していれば core は null に
> 見え**、パッチは「対象なし」で正常終了して**恒久的に READWRITE のまま残る**。

**修正**: 1 つも見つからなかった場合と 1 つでも失敗した場合は**例外を投げる**。例外が
次回起動での再試行を買う。冪等性も維持する (復元環境で全定義のリビジョンを上げないため)。

**detail を書き換える。core は触らない。** `TypeService` の javadoc が
「Property cores may be globally shared across types」と警告している — core を書き換えると
**同じ property id を使う他の型にも波及**する。

---

## 5. 何が壊れうるか

| | |
|---|---|
| **既存の CMIS クライアントが 400 を受けるようになる** | ならない。`injectPropertyValue` は例外ではなく `continue` するので、**値が黙って無視される**。書けたつもりで書けていない、という状態になる |
| **その沈黙は問題ではないか** | 問題である。ただし `cmis:createdBy` 等の既存 READONLY プロパティと**同じ挙動**であり、ここだけ例外にすると CMIS の一貫性が壊れる。**運用文書に書く** (§7) |
| **管理画面から証拠を直せなくなる** | そのとおり。それがこの作業の目的である。誤った証拠は**取込をやり直して**上書きするのが正しい経路 |
| **他の secondary type にも同じ問題が在る** | 在る。`nemaki:noteMetadata` / `nemaki:messageMetadata` / `nemaki:businessRecordMetadata` / `nemaki:externalIntegration`。**この作業では chat だけを対象にする** — roadmap が (c) として名指ししているのが `Patch_ChatContextMetadataSecondaryType` だからで、他は §8 に残す |

---

### 5.1 READONLY だけでは「消去」ができてしまう (**レビューで発覚・修正済み**)

`injectPropertyValue` は READONLY を `continue` で落とすが、**その id はリクエストに載って
いる**。`mergeAspectProperties` は既存値を「リクエストに**無い**とき」だけ引き継ぐので、
落とされた id は「明示的にクリアされた」と読まれ、**保存済みの証拠が消える**。

> 書き換えを禁じたつもりが、**より安価な消去手段**を与えることになっていた。しかも沈黙する。

**修正**: 型が READONLY と宣言したプロパティは、**リクエストに載っていても引き継ぐ**。
型定義が読めない場合も引き継ぐ (証拠を失う方が悪い誤りだから)。

§2 が引き合いに出した `cmis:createdBy` は **primary type の別経路**で、単に更新しないだけ。
「同じ挙動」ではなかった。

### 5.2 型ごと外す経路は塞いでいない — **残る穴**

`cmis:secondaryObjectTypeIds` は既定で `readwrite` で、`modifyProperties` は
`buildSecondaryTypes` の戻り値で aspect を**丸ごと置換**する。クライアントが
`nemaki:chatContextMetadata` を外したリストを 1 回送れば、**プロパティを何個 READONLY に
していても aspect ごと消える**。

**この作業では塞がない。** secondary type の付け外しは CMIS の正当な操作で、証拠を持つ型
だけ拒否するには「どの型が証拠か」を型システムに持ち込む必要がある。**P1-1(d)** に送る。

### 5.3 保護を巻き戻せる 2 経路 — **残る穴**

| | |
|---|---|
| `PUT /rest/repo/{repo}/type/update/{typeId}` | admin 権限で detail を上書きでき、JSON に `updatability` が無ければ **READWRITE に落ちる**。パッチは applied 済みなので戻らない |
| export / import | `ZipImporter` は `updatability` 欠落時に **READWRITE** で detail を作る。適用前のエクスポートを流し込むと保護が解ける |

どちらも admin が要るが、**CouchDB への直接アクセスは不要**である。**P1-1(d)** に送る。

### 5.4 multi-replica でいつから効くか

`isApplied` はリポジトリ単位で記録されるので、**実走するのは最初に起動した 1 台だけ**。
`invalidateTypeCache` / `refreshTypes` もその JVM にしか効かない。**ローリング再起動中、
未再起動のレプリカは READWRITE の型定義をキャッシュしたまま更新を受け付ける。**

---

## 6. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | パッチ後、対象 10 プロパティの updatability が `READONLY` | パッチを外すと落ちる |
| 2 | **既に READWRITE で存在するプロパティ**が書き換わる (新規作成だけでなく) | 既存パッチと同じ「在るなら何もしない」にすると落ちる |
| 3 | `injectPropertyValue` が `READONLY` のプロパティを**値ごと落とす** | `READONLY` を `break` にすると落ちる |
| 4 | **取込の aspect 書込は通る** — 同じプロパティに値が入る | 取込を CMIS 経路に変えると落ちる |
| 5 | パッチが**冪等** — 2 度走らせても型定義のリビジョンが上がらない | 無条件に update すると落ちる |
| 6 | パッチが **detail のみ**を書き換え、core を触らない | core を書き換えると落ちる |
| 7 | 対象外のプロパティ (`nemaki:noteMetadata` 等) は **READWRITE のまま** | 対象を広げると落ちる |
| 8 | **1 つも見つからなければ例外**を投げる (applied にしない) | 静かに return すると落ちる |
| 9 | **READONLY のプロパティは、リクエストに載っていても消えない** | 引き継ぎ条件を戻すと落ちる |
| 10 | **READWRITE のプロパティはリクエストに載れば消える** — 条件 9 の control | 全部引き継ぐと落ちる |

**条件 3 と 4 は対になっていなかった。** 取込は `injectPropertyValue` を通らないので、
条件 4 は **READONLY にしようがしまいが常に通る** — 負のコントロールとして機能していない。
実際に機能しているのは**条件 9 と 10 の対**で、こちらは「消えない」と「消える」を
同じ経路で分ける。

---

## 7. 運用文書に書くこと

- **証拠プロパティは CMIS からは更新できない。** 更新リクエストは 400 にならず、
  **その値だけが黙って無視される** (`cmis:createdBy` 等と同じ)
- **誤った証拠を直す経路は取込のやり直し**であって、管理画面からの編集ではない
- **これ以前に書き換えられた値は元に戻らない**
- **`cmis:secondaryObjectTypeIds` から型ごと外せば aspect ごと消える** (§5.2)。未対応
- **管理 API での型更新と、古いエクスポートの取り込みは保護を解く** (§5.3)。未対応
- **ローリング再起動中は、未再起動のレプリカが保護前の型定義で動く** (§5.4)

---

## 8. やらないこと

- 他の secondary type (`noteMetadata` / `messageMetadata` / `businessRecordMetadata` /
  `externalIntegration`) の更新制約。同じ問題が在るが、roadmap が (c) として名指ししたのは
  chat であり、**まとめて変えると「何を証拠として保護したか」の境界がぼやける**
- 既に書き換えられた値の検出・復元。来歴イベントを読む必要があり **P1-1(d)**
- 物理的な不変性。**P1-3 の tamper-evident ledger**
