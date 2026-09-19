# ADR: E1 — content 状態を台帳へ結ぶときの境界

2026-09-19。**決定: 案 B（先行する耐久 intent）。** 実測は
`core/src/test/java/jp/aegif/nemaki/evidence/spike/E1CommitmentSpikeTest.java`（8 本）。
計画 [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md) §8 の
「Phase 0 の architecture spike で案 A か案 B を fault injection で選ぶ」に対する答え。

---

## 1. 決める前に既にあったもの

**この問題の半分は 2026-08-20 に決着している。** [`capture-outbox.md`](capture-outbox.md)
（外部レビュー 11 巡 + 独立レビュー 3 体）が、取込の
**content commit → journal write** の区間に**先行 intent**を採り、実装されている:

- `rest/ingest/capture/CaptureIntent` / `CaptureState` / `CaptureIntentStore` / `CaptureScope`
- `rest/purview/journal/CaptureIntentSweeper` / `CouchCaptureIntentStore`

`CaptureState` は 3 値（`CAPTURE_INTENT` / `CAPTURED` / `UNRESOLVED`）で、
**`COMMIT_OBSERVED` は意図的に拒否されている**。理由はその javadoc に書いてある —
「在る文書がこの試行から来たのかを事後に決めるには、取込経路がまだ書いていない刻印が要る」
（設計 §3.3 / §5）。だから未完了の intent は `UNRESOLVED` のまま運用者に見せる。

**E1 が扱うのは、その §3.3 が切り出した当のものである。** ただし範囲は狭い —
E1 は「文書 bytes の状態」だけを結ぶ。

---

## 2. 何を選ぶのか

CouchDB と台帳 DB を跨ぐ transaction は無い（`capture-outbox.md` §2 が確認済み:
DB 跨ぎ transaction 無し、`_bulk_docs` の `all_or_nothing` は CouchDB 2.0 で廃止、
原子性は「1 文書 1 PUT」からしか得られない）。したがって content の書き込みと
statement の記録は必ず 2 段になり、**その間の窓をどう扱うか**が問いである。

| 案 | 形 |
|---|---|
| **A** outbox marker | content 文書**と同じ revision**に marker を書き、後で sweeper が statement を書いて marker を消す |
| **B** 耐久 intent | content を書く**前**に intent 行を書き、content を書いた**後**に解決する |

---

## 3. 実測（`E1CommitmentSpikeTest`、8 本）

> **初版は案 A を弱く作って比べていた。** 単数の marker と、marker の言い分をそのまま
> 書く sweeper。確認レビュー 2 名がそれぞれ「どちらも設計が強いる形ではない」と指摘した。
> **比べる相手を弱く作った比較は何も決めない**ので、A に両方を与えて測り直した —
> marker は**その場書き換えが持ち越すリスト**にし、sweeper は B の resolver と同じく
> **保管庫の bytes を hash し直して**から書く。以下は測り直した後の結果である。

5 つの選定条件（計画 §8）を、crash 注入と ledger 不応答で両案に当てた。

| 条件 | 案 A（強化後） | 案 B |
|---|---|---|
| C1 bytes と statement が別物を指さない | 通る | 通る |
| C2 write 成功後の gap が永続的に観測できる | 通る | 通る |
| C3 retry が同じ statement を二重記録しない | 通る | 通る |
| C4 失敗を業務の失敗へ誤変換しない | 通る | 通る |
| C5 次の版へ進んだあとに古い intent を新しい bytes で閉じない | 通る | 通る |

**その場書き換え（W3 / W7 / W9）でも差は出なくなった。** 初版で「決め手」としていた
「2 回目のその場書き換えが 1 回目の未処理 marker を消す」は、**リストにすれば消えない**。
`inPlaceRewriteNoLongerSeparatesThem` が両案とも gap を残すことを実測している
（**否定的な結果として残してある**。初版の決定は、A の書き方の産物だった）。

---

## 4. 決め手 — C6: 無関係な書き手

計画の 5 条件では分かれない。分かれるのは、**この製品が強いる 6 つ目の性質**である。

> **C6**: 同じ content 文書を書く**無関係な書き手**（改名、ACL 変更、version series の
> フラグ、そして content を書く 9 経路そのもの）が、記録の手がかりを落としてはならない。

marker は content 文書の上に載る。**文書の書き込みは文書を置き換える**ので、
marker を知らない書き手が 1 つでもあれば、そこで落ちる。

> **「それは案 A を不完全に実装しただけだ」という反論が 3 巡目に出た。** 保存 JSON 側には
> 未知フィールドの carrier がある（`CouchNodeBase` の `@JsonAnySetter` / `@JsonAnyGetter` →
> `additionalProperties`）ので、marker もそこに乗れば改名で落ちない、という指摘である。
>
> **コードを読んで確かめた結果、落ちる。** サービス層の更新は
> `ContentDaoServiceImpl.update(repositoryId, Document)`（:2379-2380）で
> **ドメインモデルから `new CouchDocument(document)` を組み直す**。そして
> ドメインの `NodeBase` / `Content` / `Document` に `additionalProperties` は**無い**
> （grep で 0 件）。carrier は「保存 JSON → Couch モデル → 保存 JSON」の往復しか守らず、
> 製品が実際に通る「保存 JSON → Couch モデル → **ドメインモデル** → Couch モデル → 保存 JSON」
> では消える。
>
> **この製品はその形で 1 度焼かれている。** `CouchContent` の :241-245 のコメント —
> 「model round-trip used to LOSE `contentIncarnation`（convert() never copied it and the model
> had no field）, so the mint below fired on EVERY update — each ordinary rename silently
> started a new "lifetime"」。**普通の改名が記録を壊した**という、C6 そのものの実例である。
> 直し方は「ドメインモデルにフィールドを足す」で、案 A の marker も同じことが要る —
> つまり **`Document` を新しく組む書き手すべて**（`buildCopyDocument` を含む）が
> marker を運ぶ責任を負う。

実測（`anUnrelatedWriterDoesNotDropTheGap`）:

```
content を書く → statement の前に落ちる（bytes は在る、statement は無い、gap は見える）
→ 改名 1 回 → 回復を走らせる

案 A: openGaps = 空。statement も無い。**改名がそれをやった**
案 B: statement 1 本。intent は別の store に在るので改名は触れない
```

**bytes は在り、記録されず、記録されなかったという事実も無い。** 計画 §8 が禁じている
silent gap で、引き金が「利用者が名前を直した」である。

この製品の content 文書は多くの場所から書かれる（Phase 0 の棚卸しで数えた content 書き込み
だけで **12 経路**、ほかにプロパティ更新・ACL・版フラグ）。**そのすべてに marker の持ち越しを
配線し、以後も落とさない**ことが案 A の前提になる。案 B の intent は別 DB の行なので、
content 文書を書く側は何も知らなくてよい。

---

## 5. 案 B の代償（測って書く）

- **書き込みが 1 回増える**（content の前に intent 行）。
- **content が来なかった intent が残る。** 「書かなかった」のか「書いたが記録できなかった」
  のかは**この段では判定しない** — `capture-outbox.md` が `UNRESOLVED` で止めたのと同じ
  理由である。実測 `anIntentWithNoContentIsNotAClaimThatContentExists`:
  statement は書かず、gap としては残す（黙って消さない）。
- **resolver は保管庫を読み直す**（digest の突き合わせ）。案 A も強化版では同じことをするので、
  これは差ではない。

そして `capture-outbox.md` §3.3 が刻印を要るとした問題への、E1 の範囲での答え:

> **content については、digest が刻印そのものである。**
> 識別子を content 文書に書き込まなくても、intent が持つ digest と保管庫の bytes を
> 突き合わせれば「今その状態が在る」と言える。

**ただし digest は「この試行が書いた」証明ではない。** 同じ bytes を書く再試行は区別できない。
E1 の statement が主張するのは**内容状態**であって書き込みの著者ではないので、
この区別は要らない — **要らないことを、要らないと書いておく**。
`capture-outbox.md` が刻印を必要としたのはメタデータ更新まで含む「取込が完了したか」の
判定で、そちらにはこの近道は効かない。

---

## 6. 決めていないこと

- **digest をどこで取るか。** 「書きながら 1 パス（`DigestInputStream`）」か
  「書いた後に読み直す」か。inventory §1 の 2 つ目の軸で、**この ADR では決めない**。
  読み直す側は**既に出荷されている** — `FixityVerifier` が保存 bytes を読み直して
  `SUBJECT_STORED_REVERIFIED` を返し、`FixityScanService` から配線済みである
  （確認レビューの指摘で訂正。初版は「どちらにせよ新設」と書いていたが誤り）。
  取込経路の `nemaki:contentHash` も**サーバが取得 bytes を hash した値**
  （`DigestSubject.INPUT`）で、申告値ではない。E1 が足すのは
  **CMIS 主経路のぶんと、その値を台帳へ結ぶ配線**である。
- **`RECORD_CONTENT_STATE` の正準化 field**（Phase 2 で凍結する）。
- **W7 の「どれが最終チャンクか」。** 製品が `isLastChunk` を使っていないので分からない。
  記録しないことを limits に書く（inventory §1）。
- **既存の `CaptureIntent` に相乗りするか、別の行にするか。** 形は同じだが、
  覆う区間が違う（capture は取込 1 件、E1 は content の書き込み 1 回）。Phase 3 で決める。

---

## 7. spike が測っていないこと

`E1CommitmentSpikeTest` はモデルである。**モデル化していないもの**: CouchDB の revision
競合とその再試行（案 A の marker を持ち越す書き込みは競合したときに読み直して併合する
必要があり、その手間はここに出ていない）、attachment PUT が文書と別に書かれること
（そのため「marker と bytes が同じ revision で原子的」という案 A の定義上の強みも、
逆にその PUT だけが落ちた場合の振る舞いも、ここでは測れない）、multi-replica の順序、
view の遅延、性能。したがってここで言えるのは**2 つの設計の形**についてであって、
製品の実測ではない。

**C6 は計画の条件ではない。** この製品の content 文書が多くの書き手を持つという事実から
足したもので、その事実は Phase 0 の棚卸し（content 書き込み 12 経路）で数えている。
書き手が 1 か所しかない製品なら C6 は効かず、決定も変わりうる。

書き込み順序だけは製品から読み出した（作る系は attachment → 文書、その場系は同じ行を
書き換えてから文書）。

---

## 8. 参照

- 取込側の境界（既決・実装済み）: [`capture-outbox.md`](capture-outbox.md)
- 経路と mapper の棚卸し: [`evidence-phase0-inventory.md`](evidence-phase0-inventory.md)
- 脅威モデル: [`../security/evidence-profile-v1-threat-model.md`](../security/evidence-profile-v1-threat-model.md)
- 計画: [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md) §8
