# ADR: E1 — content 状態を台帳へ結ぶときの境界

2026-09-19。**決定: 案 B（先行する耐久 intent）。** 実測は
`core/src/test/java/jp/aegif/nemaki/evidence/spike/E1CommitmentSpikeTest.java`（7 本）。
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

## 3. 実測（`E1CommitmentSpikeTest`）

5 つの選定条件（計画 §8）を、crash 注入と ledger 不応答で両案に当てた。

| 条件 | 案 A | 案 B |
|---|---|---|
| C1 bytes と statement が別物を指さない | 通る | 通る |
| C2 write 成功後の gap が永続的に観測できる | 通る（**ただし §4**） | 通る |
| C3 retry が同じ statement を二重記録しない | 通る | 通る |
| C4 失敗を業務の失敗へ誤変換しない | 通る | 通る |
| C5 次の版へ進んだあとに古い intent を新しい bytes で閉じない | 通る | 通る |

**新しい attachment を作る経路（W1 / W2 / W4 / W6 / W8）では両案とも 5 条件を満たす。**
差が出るのは次の 1 点だけで、それが決定を決めた。

---

## 4. 決め手 — その場で書き換える経路

`setContentStream`（非 versionable、`ContentServiceImpl:1588-1591`）と
`appendContentStream`（同 :4528）は**同じ attachment 行を書き換える**。新しい行も新しい版も
作らない（[`evidence-phase0-inventory.md`](evidence-phase0-inventory.md) §1）。

marker は content 文書の上に載る。**文書の書き込みは文書を置き換える**ので、
2 回目のその場書き換えは、1 回目の未処理 marker を消す。実測:

```
v1 を書く（記録済み）→ v2 を書いて doc-written の直後に落ちる（v2 は在る、statement は無い）
→ v3 を書く → 回復を走らせる
案 A: openGaps = 空。v2 の statement は無い。**何も残っていない**
案 B: openGaps = v2 の intent が残る（SUPERSEDED として）
```

**v2 は実在し、記録されず、その後「記録されなかった」という事実まで消える。**
計画 §8 が禁じている silent gap そのもので、回復では見つけられない。

> **案 A の変種（marker を単数でなくリストにする）は測っていない。**
> リストにすれば gap は残ると考えられるが、**測っていないので通ったとは書かない**。
> 測ったとしても採らない理由が別にある（§5）。

---

## 5. なぜ B か — 「書いたつもり」と「今も見えている」の違い

案 A の sweeper は **marker が言っていること**を台帳に書く。案 B の resolver は
**保管庫に今ある bytes を hash し直して**、intent が名指した digest と一致したときだけ書く。
一致しなければ `SUPERSEDED` にして、**statement は書かない**。

証拠台帳にとってこの差は決定的である。A は「書こうとした状態」を記録しうる — 添付 PUT が
実は永続していなかった場合でも marker は文書の上に在るので、sweeper は在りもしない状態を
記録する。**証拠が実際より強くなる**。B はそれができない。

そしてこれが `capture-outbox.md` §3.3 が刻印を要るとした問題への、E1 の範囲での答えである:

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
  主経路は現在 bytes を hash していないので、どちらにせよ新設である。
- **`RECORD_CONTENT_STATE` の正準化 field**（Phase 2 で凍結する）。
- **W7 の「どれが最終チャンクか」。** 製品が `isLastChunk` を使っていないので分からない。
  記録しないことを limits に書く（inventory §1）。
- **既存の `CaptureIntent` に相乗りするか、別の行にするか。** 形は同じだが、
  覆う区間が違う（capture は取込 1 件、E1 は content の書き込み 1 回）。Phase 3 で決める。

---

## 7. spike が測っていないこと

`E1CommitmentSpikeTest` はモデルである。**モデル化していないもの**: CouchDB の revision
競合とその再試行、attachment PUT（本体が文書と別に書かれること）、multi-replica の順序、
view の遅延、性能。したがってここで言えるのは**2 つの設計の形**についてであって、
製品の実測ではない。

書き込み順序だけは製品から読み出した（作る系は attachment → 文書、その場系は同じ行を
書き換えてから文書）。

---

## 8. 参照

- 取込側の境界（既決・実装済み）: [`capture-outbox.md`](capture-outbox.md)
- 経路と mapper の棚卸し: [`evidence-phase0-inventory.md`](evidence-phase0-inventory.md)
- 脅威モデル: [`../security/evidence-profile-v1-threat-model.md`](../security/evidence-profile-v1-threat-model.md)
- 計画: [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md) §8
