# P1-4 — 真正性レポート (evidence package)

作成 2026-08-24。ロードマップ §4 Phase 1 の 4 番目。前提は P1-1〜P1-3。

---

## 0. この文書がいちばん気をつけること

ロードマップはこれを「**マーケの主砲**」と書いている。だからこそ、
**この成果物は本作業で最も誇張しやすい**。監査人と見込み客に見せる 1 枚に
「contentHash: abc…」と「custody chain: 5 events」が並んでいれば、
読む側はそれを「検証済み」と読む。**どちらも、それ自体は検証ではない。**

したがって設計の中心は集約ではなく**節ごとの但し書き**である:

> **すべての節が、自分の verdict と限界を自分で持つ。**
> レポート全体の「これは何を establish しないか」は**省略できない**。

`verdict` を落とせば数字は残るが、意味は変わる — 再索引の `COMPLETE` と
fixity の `PARTIAL` で 2 度学んだ形をここでも適用する。

---

## 1. 何を集めるか

| 節 | 出所 | その節が言えること | 言えないこと |
|---|---|---|---|
| **identity** | 証拠 aspect (chat 11 / source identity 9 / archetype) | **いま保存されている値**と、それが取込元の申告に帰属すること | 取込時から**変わっていない**こと (capture hash と突き合わせていない — 2026-08-25 訂正)。CMIS 経路に対する読み取り専用は**変更不能ではない**。そして真実であること |
| **content** | `nemaki:contentHash` + P1-2 の再検証 | 記録された digest と、**いま**読み直した digest の一致 | 改竄されていないこと (§0 / P1-2 §0)。digest も普通の保存プロパティである |
| **custody** | capture intent 行 (**lineage イベントは読んでいない** — 2026-08-25 訂正) | 記録された取込・配送の経過 | **記録が完全であること**。台帳に入る経路を通らなかった操作は無い |
| **integrity of the record** (JSON 上の節名は **`ledger`**) | P1-3 の連鎖検証 (**inclusion proof は出していない** — 2026-08-25 訂正) | 台帳自身の内部整合。checkpoint 以前の書き換え・削除・並べ替えの検出 | 外部からの独立性 (P2 のアンカー待ち) |
| **versions** | CMIS version series | **この文書が版系列のどこに居るか** (版系譜そのものではない) | 他の版を列挙すること。版の間で何が変わったか。版が消されていないこと。**読めなかったときは `ABSENT` ではなく `UNAVAILABLE`** (2026-08-28 訂正: `null instanceof Document` が false なので CouchDB 障害が「この記録は文書ではない」という所見になっていた) |
| **access** | 監査ログ | **何も言えない** (§3.5) — ログはプロセス外に出るため読み戻せない | 同左。`ABSENT` ではなく `UNAVAILABLE` |
| **environment** | barrier の binary digest | 動いていたバイナリの指紋 | **その値を我々自身が報告している**こと (循環) |

---

## 2. 形式 — JSON が本体、人間向けは HTML

**JSON が正典。** PDF は今回作らない。理由:

- **PDF 生成ライブラリが依存に無い。** 追加は可能だが、この節の価値は
  *内容* であって *容器* ではない
- **人間向けには HTML を返す。** ブラウザの印刷で PDF になり、しかも
  リンクが生きる (proof の検証手順や台帳のエントリへ飛べる)
- **byte 安定な PDF が要るのは保存パッケージ (P3-1) の要件**であり、
  そこは E-ARK / PREMIS の形式規定に従う別の仕事である。ここで独自 PDF を
  作ると、後で捨てる形式を 1 つ増やすことになる

---

## 3. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | 節ごとに `verdict` と `limits` が付く | どれか 1 つでも落とすと落ちる |
| 2 | レポート全体の「establish しないもの」が**必ず**入る | 省略可能にすると落ちる |
| 3 | content 節は P1-2 の 4 値をそのまま運ぶ (`NOT_RECORDED` を `UNVERIFIABLE` に潰さない) | 潰すと落ちる |
| 4 | custody 節は「記録が完全とは言えない」を必ず言う | 落とすと落ちる |
| 5 | environment 節は**自己申告であること**を言う | 落とすと落ちる |
| 6 | 読めなかった節は**空ではなく `UNAVAILABLE`** になる | 空に潰すと落ちる |
| 7 | 個人データ (participants 等) は既定で**含まれない** | 含めると落ちる |
| 8 | admin 以外は 403 | 認可を外すと落ちる |

---

## 3.5. 実装 (2026-08-24)

| 物 | 場所 |
|---|---|
| レポート本体 | `core/.../evidence/AuthenticityReport.java` |
| 集約 | `core/.../evidence/AuthenticityReportAssembler.java` |
| API | `GET /core/api/v1/admin/authenticity/report` (JSON) / `report.html` |
| テスト | `AuthenticityReportTest` (20) / `AuthenticityReportControllerTest` (4) |

負のコントロールは **17 本すべて実測**し、各々が意図したテストだけを落とすことを確認した
(C7 は environment 2 本、C12 は 10 本 — 後者は下記の欠陥が全節に及ぶことを示す)。

> **その 17 本では足りなかった (2026-08-25 訂正)。** レビューが「消しても通る
> production の編集」を 4 つ挙げ、いずれも実際に通った:
>
> - `case MISMATCH -> Verdict.VERIFIED` — **4 値目に一切テストが無かった**。
>   記録した digest と一致しないバイト列が VERIFIED として報告される
> - ledger 節の verdict を `VERIFIED` 固定 / truncation 分岐を削除 —
>   `ledgerStore` を配線するテストが 1 本も無く、`null` 分岐しか通っていなかった
> - `REPORT_LIMITS` を空にする — assert が**定数を定数自身と比べていた**ため恒真。
>   「省略できない」と宣言した当のものが無防備だった
>
> AC3 を潰す変異が測定集合に入っていなかった、ということである。
> 5 本足して測り直した。

### access 節は構造上 `UNAVAILABLE` である

監査証跡は SLF4J ロガー (`jp.aegif.nemaki.audit.AUDIT`) に書かれ、**プロセスの外へ出る**。
この API から読み戻す方法は無いので、access 節は常に `UNAVAILABLE` を返し、
「監査していない」ではなく「ここからは読めない」と述べる。`ABSENT` にすると
**遥かに強い (そして偽の) 主張**になる。読み戻せる形にするのは別増分。

### 実装中に見つかった欠陥 2 件 (どちらもテストが先に捕まえた)

1. **`Map.copyOf` が null 値を拒否**する。`NOT_RECORDED` は digest が null なので、
   本番で最初の「digest 未記録の文書」を要求した瞬間に NPE で落ちていた。
   ついでに**挿入順も破壊**していた (HTML 表の行順・JSON のキー順)。
   `Collections.unmodifiableMap(new LinkedHashMap<>(...))` に変更。
   null は「記録が無い」の正直な表現なので、キーごと落とすのではなく null のまま出す。
2. **`new LineageBinaryDigest()` は常に失敗する。** これは Spring bean で
   `LineageConfig` / `ServletContext` を注入で受け取るため、自前生成したものは
   両方 null になり `UnmeasurableException` しか投げられない。environment 節が
   **永久に UNAVAILABLE のまま、試したように見える**状態だった。注入に変更。

### レポートの上限は黙って切らない

custody は 20 行、ledger は直近 1000 エントリで打ち切る。**打ち切ったことを
content と limits の両方に書く** (`truncated: true`)。黙って切った一覧は
「これで全部」と読まれる。

---

## 4. やらないこと

- **PDF 生成** — §2
- **「真正である」という判定** — この製品は真正性を*判定*しない。
  証拠を集めて、その限界とともに示す。判定は人がする
- **監査ログの遡及補完**
- **個人データの既定同梱** — 必要な調査には別の明示的な要求で
