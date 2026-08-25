# P3-3 — 処分証跡

作成 2026-08-26。ロードマップ §4 Phase 3 の 3 番目。前提は P1-3 (証拠台帳)。

---

## 0. 何を主張し、何を主張しないか

**主張する**: **cold move の MOVE モードでローカル本文を削除するとき、
削除の前に「何を・いつ・どの規則で」を証拠台帳に追記し、
追記できなければ削除しない**。

**主張しない**:

- **これは処分の全経路ではない。** 現時点で証跡が付いているのは
  **cold move の MOVE モードによるローカル削除 1 か所だけ**である。
  CMIS の通常削除・archive の物理削除・`lineage.retention.days` の purge は
  この経路を通っていない (§4)
- **台帳に在ることは「削除が正しかった」を意味しない。** 台帳が言うのは
  「この規則の下でこの削除が行われたと記録された」までで、
  **規則そのものが妥当だったかどうかについては何も言わない**
- **物理的な不可逆性ではない。** P1-3 §0 と同じ — CouchDB に直接届く者は行を書き換えられる

---

## 1. なぜ規則を fail-CLOSED にするのか — capture の逆

`EvidenceLedgerRecorder` は capture が**既に永続化された後**に走るので、
台帳に届かなくても**取込を失敗させてはならない**。失敗させると、
その記録が対象にしていた当のものが消える。

**処分は鏡像であり、規則もそこで反転する。**

| | capture | disposition |
|---|---|---|
| 記録の位置 | 事後 (行は既に在る) | **事前** |
| 台帳に書けないとき | **続行する** (穴は報告する) | **拒否する** |
| 拒否の代償 | 取得済みの内容が失われる | **遅延だけ**。内容はそのまま、次回また走る |
| 続行の代償 | 連鎖に穴 (対象は残る) | **削除されたのに、削除されたことがどこにも無い** |

最後の行が判断の全部である。capture の穴は「オブジェクトは在るが記録に無い」で、
**後から気づける**。処分の穴は「何も無い」で、**気づく手がかりが残らない**。

なお **capture 境界が intent を先に書くのと同じ理屈**でもある: 本文と証拠は
別の DB にあり、跨るトランザクションが無い。**後に書くほうが落ちる**。

### 拒否したときの状態 (2026-08-26 訂正 — 初稿は嘘をついていた)

> **初稿は「COPY モードに印を付け直して次回また試みる」と書いた。後半が偽だった。**
> `updateArchiveState` は `coldArchivedAt` を打ち、候補抽出は
> `coldArchivedAt != null` を飛ばす。つまりその archive は**候補プールから永久に外れ**、
> 戻す API も無い (CouchDB を直接触るしかない)。
> 設計文書・javadoc・**運用者がログで読む文言**「The content is untouched and the next
> run will try again.」の 3 つが揃って偽で、しかも**その文言を assert するテストが
> 嘘を固定していた**。

cold へのコピーは既に書かれて immutable である。したがって拒否時は、
**隣の「ローカル削除に失敗した」経路がすでにやっているのと同じ後始末**をする —
`removeProtection` → `adapter.delete` → `resetColdMoveMetadata`。
状況が同じ (cold は書けた、ローカル削除はしていない) なので処理も同じでよい。
これで初めて「次回また試みる」が真になる。

### 拒否は SUCCESS ではない

`computeStatus` は `failed == 0` なら SUCCESS を返し、skipped は見ていなかった。
**全 archive が拒否された実行が「SUCCESS」**になる。これは CLAUDE.md が繰り返し
警告している `verdict: COMPLETE` の罠そのもので、「全部順調」と読める状態表示の下で
**保持処理が一切動いていない**。

`refused` を skipped と別に数え、1 件でもあれば `REFUSED_NOT_RECORDABLE` を返す。
skip は「ここには何もすることが無かった」、拒否は「することが在って、記録できないので
しなかった」であり、同じ数字に畳んではいけない。

---

## 2. エントリが規則にコミットする

「これは削除された」は処分証跡ではない。B.2 が求めるのは
**何を・いつ・どの規則で**である。したがって digest は

```
H(LEDGER_DISPOSITION_V1, repositoryId, act, subjectId, flatten(rule))
```

- `act` は enum (`LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE`)。自由文にすると比較できない
- `rule` は**この実行が実際に読んだ設定値** — 後から誰かが見たときの設定ではない
- `flatten` は **key/value を長さ前置で連結**する。

  > **初稿の例は誤っていた** (2026-08-26 訂正)。`{"ab":"c"}` と `{"a":"bc"}` は
  > **区切り文字 `=` `;` がある以上、前置が無くても衝突しない** — つまり
  > あの例を使ったテストは長さ前置を測っていなかった (実測: 前置を全部外しても緑)。
  > 実際に前置が防ぐのは「**値が区切り文字を含む**」形で、
  > `{"a":"b;c=d"}` と `{"a":"b","c":"d"}` はどちらも `a=b;c=d;` に潰れる。
  > **1 つの設定が 2 つのふりをする。** 例を直して測り直した

- **キー名は `PropertyKey` の定数を使う。** 初稿は
  `retention.longterm.storage.type` と書いていたが、**この製品にそんな設定は無い**
  (実物は `longterm.storage.type`)。値は正しく**ラベルだけが何も指していない**状態で、
  「設定ファイルと設計文書だけ見て外部検証者が digest を再計算できる」という
  この digest の存在理由をそのまま壊していた
- **閾値は「実際に適用された値」を書く。** `retention.archive.cold.after.days` が
  parse できないとき job は 90 にフォールバックするので、生文字列を記録すると
  **適用されなかった規則を記録する**ことになる
- `TreeMap` で並べ替える。Map の反復順は commit している事実の一部ではないので、
  それで変わる digest は再計算できない

**subject は archive 行の id ではなく元オブジェクトの id**。
「この記録に何が起きたか」を探す人が持っているのはそちらだから。

---

## 3. 負のコントロール 4 本実測

| 壊した箇所 | 落ちたテスト |
|---|---|
| 拒否を無視して削除する | `testMoveMode_unrecordableDisposition_doesNotDeleteLocalContent` |
| 記録を削除の**後**に移す | 同上 + `testMoveMode_dispositionIsRecordedBeforeTheDelete` |
| 台帳が拒否しても許可を返す | `anUnrecordedDispositionIsRefused` |
| 規則を digest から落とす | `theDigestCommitsToTheRule` / `theRuleFlatteningIsInjective` |

> **配線が本物であることは、足す前に一度示されている。** recorder を注入せずに
> `moveToCold` を叩く既存テスト 2 本が、この変更だけで落ちた
> (`testMoveMode_stateTransitionsToArchivedCold` /
> `testMoveMode_deleteLocalFails_...`)。飾りの配線なら緑のままだった。

---

## 4. まだ証跡が付いていない処分経路

**ここを正確に書いておく。** 「処分証跡を実装した」は、下記が付いていない状態では
過大である。

| 経路 | 状態 |
|---|---|
| cold move (MOVE) のローカル削除 | **実装済み** |
| CMIS の `deleteObject` / `deleteTree` | **未** — 利用者操作であって保持規則による処分ではないが、記録の消滅であることに変わりはない |
| archive の物理削除 | **未** |
| `lineage.retention.days` による journal purge | **未**。P1-3 §5 は「purge しても連鎖は切れない」と言うが、**purge したこと自体**は記録していない |
| 削除失敗後のクリーンアップで cold 側を消す経路 | **未** (`adapter.delete` — 補償動作なので処分ではないが、cold のバイト列は消える) |
| `deleteContentStream` — オブジェクトを残したまま**添付の実体だけ**破棄する | **未**。CMIS の `deleteObject` とは別操作で、上の行では覆えない |
| `deleteAttachment` | **未** |
| **capture 記録の purge** (`purgeUnresolvedOlderThan` / `purgeCapturedOlderThan`) — 取込境界の**証拠そのもの**を消す | **未** |
| `retentionLogDaoService.deleteOldLogs(repositoryId, 100)` — 上限 100 で古い job log を消す。**「処分が拒否された」ことの唯一の永続記録**がこれなので、自己言及的に効く | **未** |

保持期間と inclusion proof の定義もまだである (ロードマップ P3-3 の後半)。
台帳のエントリは P1-3 の checkpoint に載るので inclusion proof は**機構としては
既に在る**が、「処分証跡の保持期間」を製品として決めてはいない。
