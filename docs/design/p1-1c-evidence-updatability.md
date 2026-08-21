# P1-1(c) — 証拠プロパティを書き換えられなくする

対象: [`authenticity-roadmap.md`](authenticity-roadmap.md) が P1-1(c) として残した更新制約。

> `Patch_ChatContextMetadataSecondaryType` は既存型にプロパティ id を足すだけで updatability を
> 書き換えないので、**コードを変えても新規デプロイにしか効かず構成が割れる**。既存プロパティ
> 定義を書き換える移行パッチと、我々自身の aspect 直接更新が阻まれないかの確認が要る。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 取込が刻んだ証拠プロパティが、**CMIS クライアントからは書き換えられない**。
既存デプロイでも効く。

**主張しない**:

- **「管理者でも書き換えられない」とは言わない。** これはアプリケーション層の制約で、
  CouchDB に直接届く者は到達できる。物理的な不変性ではない
- **既に書き換えられた値は元に戻らない。** この変更以前に CMIS 経由で編集された値は
  そのまま残る。**遡って検証する手段は無い**
- **証拠が正しいことは言わない。** 書き換えを止めることと、書かれた値が事実であることは別
- **取込以外の書き手を止めるとは言わない。** 我々自身の aspect 直接更新は**意図的に**通る
  (§3)。それが通らないと取込そのものができない

---

## 1. 現状の事実 (コードで確認)

| | |
|---|---|
| 対象プロパティ | `nemaki:chatContextMetadata` の 10 個。全て `Updatability.READWRITE`、全て optional |
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

**冪等性は必須。** パッチは毎起動で走りうるので、2 度目に走ったとき型定義のリビジョンを
無駄に上げない。

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

## 6. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | パッチ後、対象 10 プロパティの updatability が `READONLY` | パッチを外すと落ちる |
| 2 | **既に READWRITE で存在するプロパティ**が書き換わる (新規作成だけでなく) | 既存パッチと同じ「在るなら何もしない」にすると落ちる |
| 3 | `injectPropertyValue` が `READONLY` のプロパティを**値ごと落とす** | `READONLY` を `break` にすると落ちる |
| 4 | **取込の aspect 書込は通る** — 同じプロパティに値が入る | 取込を CMIS 経路に変えると落ちる |
| 5 | パッチが**冪等** — 2 度走らせても型定義のリビジョンが上がらない | 無条件に update すると落ちる |
| 6 | パッチが **detail のみ**を書き換え、core を触らない | core を書き換えると落ちる |
| 7 | 対象外のプロパティ (`nemaki:noteMetadata` 等) は **READWRITE のまま** | 対象を広げると落ちる (§5 の限定を守る) |

**条件 3 と 4 が対になっている。** 片方だけだと「両方書けない」実装でも通る。

---

## 7. 運用文書に書くこと

- **証拠プロパティは CMIS からは更新できない。** 更新リクエストは 400 にならず、
  **その値だけが黙って無視される** (`cmis:createdBy` 等と同じ)
- **誤った証拠を直す経路は取込のやり直し**であって、管理画面からの編集ではない
- **これ以前に書き換えられた値は元に戻らない**

---

## 8. やらないこと

- 他の secondary type (`noteMetadata` / `messageMetadata` / `businessRecordMetadata` /
  `externalIntegration`) の更新制約。同じ問題が在るが、roadmap が (c) として名指ししたのは
  chat であり、**まとめて変えると「何を証拠として保護したか」の境界がぼやける**
- 既に書き換えられた値の検出・復元。来歴イベントを読む必要があり **P1-1(d)**
- 物理的な不変性。**P1-3 の tamper-evident ledger**
