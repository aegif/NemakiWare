# ADR: E1 — content 状態を台帳へ結ぶときの境界

2026-09-19（4 巡目のレビューを受けて 2026-09-20 に §4 / §5 を書き直した）。
**決定: 案 B（先行する耐久 intent）。ただし spike は 2 案を分けなかった** — 決定は
測定ではなく §5 の判断による。実測は
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

## 4. spike は**決めなかった** — 2 度、決め手を取り下げた

**初版の決め手**「その場書き換えが未処理 marker を消す」は、案 A を単数 marker で
モデル化した産物だった（2 巡目の指摘、§3）。

**2 版目の決め手 C6**「同じ文書を書く無関係な書き手（改名）が marker を落とす」も
**取り下げる**（4 巡目の指摘）。取り下げの根拠:

- ドメイン `Content` には **verbatim carrier がある** — `aclEpochFields`
  （`model/Content.java:65-76`）。そのコメントが理由をそのまま書いている:
  「The DAO update path builds a FRESH CouchDocument from this model object, so any stored
  field the model does not carry is ERASED by an ordinary rename/property update.
  **These carriers exist ONLY so that unrelated updates stop being destructive**」
- 往復は閉じている: 読み `CouchContent.readAclEpochFieldsVerbatim`（:82-118）→ ドメイン
  （`convert()` :389-394）→ 書き（:253-256 で `additionalProperties` に戻す）。
  コピー構築子も明示的に運ぶ（`Content(NodeBase)` :86-97、「Copy-constructor chains
  must not drop the verbatim carriers」）
- したがって E1 の marker を足す費用は **キー一覧に 1 行**であって、
  「13 経路すべての配線」ではない

**私はここを「ドメインに carrier は無い（grep で 0 件）」と書いていた。探索語が
`additionalProperties` だけで、`aclEpochFields` を探していない。**
[[count-the-whole-inventory-not-one-file]] と同じ誤り方である。

> **`contentIncarnation` の先例の読み方も片面だった。** 「普通の改名が記録を壊した」は
> 事実だが、製品はその後 **同じ問題を carrier 方式で一般化して解いている**。
> 片面だけ引いて「だから A は危うい」と書いたのは、証拠の選び方の誤りである。

**結論: 5 条件でも、その場書き換えでも、無関係な書き手でも、2 案は分かれない。**
spike が言えるのは「**どちらも成立する**」までで、決定は spike の外にある。

---

## 5. それでも B を採る理由（spike の外。測定ではなく判断）

1. **同じ class の問題を、この製品は既に B の形で解いて出荷している。**
   取込の content commit → journal write は先行 intent で閉じてあり
   （`capture-outbox.md`、外部レビュー 11 巡、`CaptureIntent` / `CaptureState` /
   `CaptureIntentSweeper` が実装済み）。A を採ると、**同じ製品の中に同じ class の問題に
   対する 2 つ目の仕組み**が生まれる — 別の sweeper、別の失敗様式、別の運用手順。
2. ~~**A は carrier という不変条件に依存する。** carrier は在るが、**それを守らせる錠は無い**~~
   **これも取り下げた（3 度目、2026-09-20）。** `CouchContentEpochRoundTripTest` が
   「保存 JSON → ドメインモデル → 改名 → 保存 JSON」の実経路を再現して carrier を固定して
   いる（6 本。`aRenameKeepsEveryEpochFieldVERBATIM` ほか）。**今度は `main/` だけを
   `aclEpochFields` で探して `test/` を探していない。** §4 で自分が告白した誤りと同じ形を、
   その訂正の中で繰り返した。
   **したがって「§5 の 2 が弱まる条件」は、これを書いた時点で既に満たされていた。**
3. **A の marker は content 文書の revision を 1 つ余分に使う**（書いて、掃いて、消す）。
   B の intent 行は別 DB なので content 文書の revision 履歴を増やさない。

**判断であって測定ではない、と明記する。** 残っているのは 1（一貫性）と 3（revision 数）
だけで、**どちらも A を失格にしない**。**A を選んでも 5 条件は満たせる** — その事実を
ここに残しておく。決め手を 3 度取り下げた末に残ったのがこの 2 つである、ということも。

### 何が決定を覆すか

- capture-outbox の先行 intent が取り下げられる（そのとき 1 の根拠は消える）
- ~~carrier の不変条件に錠が付く~~ **書いた時点で既に満たされていた**（上の 2）
- content 文書の revision 数が問題にならないと分かる（そのとき 3 の根拠が消える。
  逆に問題になる規模が出れば 3 は**強まる** — 覆す条件ではない、と 5 巡目に指摘された）

---

## 5.1 案 B の代償（測って書く）

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

**C6 は計画の条件ではなく、決め手でもなくなった**（§4）。spike に残してあるのは、
**carrier に載っていない marker が無関係な書き込みで落ちる**ことの実測としてであり、
それは「この製品が carrier を作った理由」そのものである。決定の根拠として読まないこと。

書き込み順序だけは製品から読み出した（作る系は attachment → 文書、その場系は同じ行を
書き換えてから文書）。

---

## 8. 参照

- 取込側の境界（既決・実装済み）: [`capture-outbox.md`](capture-outbox.md)
- 経路と mapper の棚卸し: [`evidence-phase0-inventory.md`](evidence-phase0-inventory.md)
- 脅威モデル: [`../security/evidence-profile-v1-threat-model.md`](../security/evidence-profile-v1-threat-model.md)
- 計画: [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md) §8
