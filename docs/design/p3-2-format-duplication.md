# P3-2 — 保存フォーマット複製の証跡化

作成 2026-08-26。ロードマップ §4 Phase 3 の 2 番目。前提は P1-3 (証拠台帳)。

---

## 0. 何を主張し、何を主張しないか

**主張する**: **rendition (PDF 変換) が `ContentServiceImpl.createPreview` 経由で
永続化されるとき、B.2 が求める「hash・日時・責任者・取得記録との関係・影響・
不完全性の開示」を証拠台帳に記録する**。

**ただしこの 1 文だけを読まないこと。** 6 項目のうち「取得記録との関係」は
外部取込由来の文書でしか埋まらず、通常の CMIS アップロードでは常に null になる
(下記 4 つ目)。「6 項目を記録する」と読めると、実運用の大半で 5 項目しか無いことが
消える。

**主張しない**:

- **PDF/A を作っていない。** 現行の変換経路は jodconverter 経由の LibreOffice で、
  **PDF/A profile の指定も検証もしていない**。出てくるのは**利便コピー**である
- **これは保存計画の代替ではない。** ロードマップが最初からそう書いている
- **veraPDF による検証は入れていない** (新規要素。§4)
- **証跡が付くのは `ContentServiceImpl.createPreview` 経由の rendition だけ**。
  同じく永続化する別経路が 4 本あり、まだ通っていない (§5)
- **「取得記録との関係」(`sourceDigest`) が入るのは外部取込由来の文書だけ。**
  読んでいるのは aspect `nemaki:externalIntegration` の `nemaki:contentHash` で、
  それを書くのは外部取込経路だけである。**通常の CMIS アップロードでは常に null** になり、
  複製の記録は「何から作ったか」を欠いたまま残る。null を捏造しないのは正しいが、
  B.2 の 6 項目のうち 1 つが**実運用の大半で埋まらない**ことは主張の側で言っておく

---

## 1. B.2 のうち、飛ばしやすいのはどれか

最初の 4 つ (hash・日時・責任者・関係) は記帳である。**5 つ目の「不完全性の開示」が、
その記録に意味があるかどうかを決める。**

開示なしで記録された派生コピーは、**元と同格の記録として読める**。
Word ファイルの隣に PDF があり、どちらにも digest があり、どちらも連鎖に入っていて、
「PDF は PDF/A profile を持たない変換器が作った閲覧用コピーである」とは
どこにも書いていない — 読み手は**記録が 2 つある**と受け取る。
そして**複製を記録する理由は、それが同格ではないことにある。**

したがって開示は:

- **変換器の性質**であって呼び出し元が渡す自由文ではない (`Converter` enum)。
  呼び出し元が渡せるなら、いつか空文字が渡る
- **変換器ごとに違う**。CAD 図面が 1 枚の絵に潰れることと、Word の再流し込みは
  違う損失である。1 文を共有した時点で、具体的な但し書きが一般論になる
- **描画の先頭に置く**。派生コピーの記述を斜め読みする人は、digest より先に
  「これは保存形式ではない」に当たらなければならない

---

## 2. 出力の hash は、通り過ぎるときに取る

変換後の PDF を hash するのに、バッファすると**変換済み文書 1 本分がメモリに載り**、
保存後に読み直すと**もう 1 往復**になる。どちらも要らない。

`DigestInputStream` で包んで `createRendition` に渡す。
**保存が読むのと同じ 1 回の通過で hash が取れる。**

SHA-256 が使えない環境では **producedDigest 無しで記録する** — 複製は起きたので、
弱い記録のほうが記録なしよりましである。

### 初稿には偽の記録を書く経路が 2 つあった (同日中に発見・修正)

**1. 誰も読まなかったストリームの digest。** `MessageDigest` は**食わせなくても
値を返す** — SHA-256 of empty input で、その値の見た目は digest そのものである。
DAO が添付書き込みを飛ばす経路 (`getStream()` が null 等) を通ると、
**変換済み文書の digest として `e3b0c442...` が記録される**。
missing な記録より偽の記録のほうが悪い。読んだバイト数を数え、
**0 なら digest を記録しない**ようにした。

**2. null ストリームを包むと NPE。** DAO は `getStream()` が null のとき添付書き込みを
飛ばすが、**ラッパーは「何も無い」を包んでも非 null** である。無条件に包むと
その優雅なスキップが null の read になる — **本文の無い文書で済んでいたところが
例外になる**。ストリームが在るときだけ包む。

`skip()` も読み飛ばさず読む (飛ばしたバイトは保存には届くのに digest に入らず、
**誰も持っていないものの digest** になる)。`markSupported()` は false —
reset するとストリームは巻き戻るが digest は巻き戻らず、両者が黙って食い違う。

負のコントロール 3 本実測 (読まれなくても digest を記録する / 無条件に包む /
元ストリームのほうを hash する)。

### さらにレビューが見つけた偽記録が 2 つ (2026-08-26)

**3. 変換器の帰属が固定値だった。** 呼び出し側が常に `JODCONVERTER_LIBREOFFICE` を
渡していたが、実際の dispatch は composite の **cad → diagram → jod** である。
つまり **CAD 図面の変換が「LibreOffice が変換した」と記録され**、
digest は converter id を含むので**偽の帰属に digest がコミット**し、
開示文は LibreOffice のフォント置換が出て、**CAD 本来の損失 (layers / 3D) は
製品コードから一度も出力されていなかった**。
`RenditionManager.converterIdFor(mimeType)` で**実際に走る変換器を聞く**ようにした。
知らない id は近いものに寄せず `UNKNOWN` にする — 寄せた時点で偽の帰属になる。

**4. 部分読みでも digest を記録していた。** `AttachmentDaoDelegate` は添付書き込みの
失敗を握り潰して id を返すので、**バイト列の一部しか保存されていない rendition が
成立し得る**。0 バイトだけを弾いていたので、途中で落ちた場合は
**部分 digest が「変換済み文書の digest」として台帳に載る**。宣言長と突き合わせる。

**5. 変換していないのに複製を記録していた。** PDF を入れると jodconverter は
**同じ ContentStream をそのまま返す**。変換は起きていないのに `FORMAT_DUPLICATION` が
1 件、しかも「フォントが置換される」付きで載っていた。同一オブジェクトなら記録しない。

負のコントロール追加 4 本実測 (帰属を固定に戻す / 未知 id を推測する /
部分読みでも digest を出す / 素通しでも記録する)。

---

## 3. fail-open — ここだけの理由

複製は**再生成できる**。触られていない元から作り直せる。
台帳が届かないときに拒否すると、**再生成可能なファイルの記録を守るために
文書プレビューを止める**ことになる。処分 (P3-3) が拒否するのは、
あちらで記録できないものが**何かを壊す**からで、ここは壊さない。

配線は `businesslogicContext.xml` の**明示 `<property>`**。annotation にすると、
null になったとき rendition は成功したまま**記録だけが静かに止まる** —
健全な配備と見分けが付かない。**このプロジェクトは同じ間違いを既に 1 度出荷している**
(`serviceContext.xml` の H1)。

---

## 4. 負のコントロール 4 本実測

| 壊した箇所 | 落ちたテスト |
|---|---|
| 全変換器で開示文を共有する | `eachConverterDisclosesItsOwnLosses` |
| 開示を識別子の後ろに回す | `theDisclosureIsFirst` |
| 出力 digest を entry digest から落とす | `theDigestCommitsToBothSides` / `theDigestIsDomainSeparated` |
| 拒否された追記を chained と報告する | `aRefusedAppendDoesNotFailTheCopy` |

### 4.1 2026-08-26 レビューで塞いだ 3 点 (負のコントロール 4 本追加)

いずれも「弱い事実が強く読める」型で、3 点とも**テストが無かった場所**にあった。

| 壊した箇所 | 落ちたテスト |
|---|---|
| 成功した delegate ではなく**名乗り出た** delegate を記録する | `theSucceedingDelegateIsReported` / `nothingConvertedIsNotAConversion` |
| 開示 section を報告から外す | `DuplicationSectionTest` 4 本全部 |
| 「何も報告が無い」を「保存された」と読む | `theStoredFlagDoesNotLeakToTheNextRendition` |
| 呼出し前に ThreadLocal を消さない | 同上 |

**(1) 変換器の帰属 — 名乗ることは変換することではない。**
`CompositeRenditionManagerImpl` は mime type を**名乗った**最初の delegate に投げるが、
名乗った delegate が null を返して次に落ちることがある (Diagram は PDF を作らない、
CAD は読めない拡張子で諦める)。旧 `converterIdFor(mimeType)` は名乗り手を答えていたので、
**LibreOffice 出力の digest の隣に CAD 経路が落とすものの説明**が並び得た。
`convertToPdfAttributed` が 1 回の走査で「実際に bytes を返した delegate」を返す形にし、
`convertToPdf` はそれに委譲する (2 度走査すると 2 度変換され、
測ったのは 1 度目の bytes で呼び手が持つのは 2 度目になる)。

**(2) 開示が読める成果物に乗っていなかった。**
変換器ごとの開示文は enum の中にあり、**本番の呼び出し元が 0 件**だった。
台帳 entry には digest しか入らないので、**読む人が開けるものには何も書かれていない**。
Word と PDF が両方 digest 付きで chain に居れば 2 つの記録に見える — 開示文が
防ぐはずだった、まさにその読み方である。`AuthenticityReportAssembler` に
`duplications` section を足し、`ledger` の直後・**開示文を識別子より先**に置いた。
台帳が読めないときは `ABSENT` ではなく `UNAVAILABLE`
(「見に行けなかった」と「無い」は別の答えで、記録について語っているのは後者だけ)。

**(3) 添付書込みが握り潰されたときの digest。**
`AttachmentDaoDelegate.createRendition` は添付書込みの失敗を握り潰して id を返す。
SDK が**全部読んでから**落ちることがあるので、バイト数では見分けが付かない。
delegate 側に `renditionContentStored` (ThreadLocal) を置き、入口で TRUE・
握り潰す catch で FALSE。読む側 (`createPreview`) は**呼出しの直前に消してから**読み、
finally で消す。沈黙は「delegate に届かなかった」であって「保存された」ではないので、
digest は記録しない。消さないと**前の文書の結果**が今回の判定になる。

---

## 5. まだ証跡が付いていない複製経路

**ここを正確に書く。** 「複製証跡を実装した」は下記が付くまで過大である。

| 経路 | 状態 |
|---|---|
| 永続化される rendition (`ContentServiceImpl.createPreview`) | **実装済み** |
| `rest/RenditionResource` / `rest/controller/RenditionController` / `api/v1/resource/RenditionResource` の 3 本 | **実装済み** (2026-08-26)。§7。かつて「永続化しないので対象外」と書いていたのは誤りで、3 本とも永続化していた |
| `AttachmentServiceDelegate.copyRenditions` (コピー時の rendition 複製) | **実装済み** (2026-08-26)。§8 |
| ~~cloud drive 側の変換~~ | **該当する経路が存在しない** (2026-08-26 確認)。`createRendition` を呼ぶ業務コードは `ContentServiceImpl` だけで、cloud drive 同期は rendition を作らない。`TextExtractionServiceImpl` は変換器を使うが**何も永続化しない** (索引用のテキストを返すだけ) ので複製ではない。ギャップ行のほうが陳腐化していた |
| **PDF/A 出力と veraPDF 検証** | **実装済み・未実測** (2026-08-26、§10)。要求は `JodRenditionManagerImpl` に配線済み (`rendition.pdfa.validate.flavour` が設定されたときだけ)、判定は veraPDF、判定は entry の digest にコミット。判定は rendition 行にも平文で入り、報告が出所付きで表示する (§11)。**LibreOffice を通した実測済み** (§12) — **2b / 3b は適合、1b は 1 件落ちる**。|

---

## 6. 自己レビューで出た 1 件 (2026-08-26) — 切り詰めが「複製は無い」に見えていた

`findBySubject` は **sequence 昇順**で返し、limit は view 側で当たる。つまり満杯で
返ってきたとき**落ちているのは新しいほうの entry** である (`ledgerSection` は
`range` で**最新の窓**を意図して取るので、向きが逆)。fixity 走査は走るたびに
entry を足すので、**長く生きた文書は何も壊れていなくても上限に達する** —
そして先週記録した複製は、読んだ範囲に居ない。

そこで `ABSENT` (「この記録の他形式の複製は連鎖に記録されていない」) を返すと、
**PDF が隣に在るのにそう答える**ことになる。この section が在る理由そのものの誤読。

- 読みが満杯 かつ 複製 0 → `UNAVAILABLE`。文面で「読めた範囲に無いだけで、
  読めていないのは新しいほう」と言う。
- 読みが満杯 かつ 複製あり → `REPORTED` のまま `truncated: true` を付け、
  「これより後の複製はこの一覧に無い」と限界に書く。

| 壊した箇所 | 落ちたテスト |
|---|---|
| `truncated` を常に false にする | `aTruncatedReadIsNotAnAnswer` / `aTruncatedReadWithCopiesSaysSo` |

---

## 7. 3 本の REST 経路 (2026-08-26) — 「記録せずに永続化できない」形にした

### 直したのは配線ではなく、配線し忘れられる形のほう

3 箇所に「変換して、それから記録も忘れずに」を書けば、必ずどれかが落ちる。実際に落ちていた。
そこで **`ContentService` から「変換済みストリームを渡して永続化する」メソッドを消した**。

- 旧: `createPreviewRendition(repo, doc, 変換済みstream, mime, title, actor, ctx)`
  — 変換は呼び出し側。だから変換器の名前も、格納されたバイト列の digest も、
  P3-2 の記録も、このメソッドの外に在った。
- 新: `createPreviewRendition(repo, doc, **元の**stream, target, title, actor, ctx)`
  — 変換・計測・記録が 1 箇所で起きる。**自分が作っていないコピーをこのメソッドに
  渡す方法がもう無い**ので、「永続化して記録し忘れる」が書けない。

`ContentServiceImpl.createPreview` (CMIS 経路) も同じ内部メソッドを通る。
`contentDaoService.createRendition` の呼び出し元は**全体で 1 つ**になった。

### SVG も対象になった — 開示文を出力形式込みで組み立て直した

3 本のうち 2 本は SVG も作る。SVG も派生コピーであり、記録の対象である。
ところが**変換器ごとの開示文は「PDF に変換した」を埋め込んでいた** — 「PDF/A profile を
要求していない」「PDF にレンダリングすると構造を失う」。SVG 経路が記録を始めた瞬間、
読む人は SVG のコピーについて **PDF の話**を読まされることになる。

開示を 3 つに分けて組み立てる形にした:

1. 共通の頭 (「これは利便コピーであって保存形式ではない」)
2. **変換器**が落とすもの (出力形式に依らない書き方に直した)
3. **出力形式**の但し書き — PDF は「PDF/A profile を要求も検証もしていない」、
   SVG は「archival profile がそもそも無い。テキストがアウトライン化されていれば
   もうテキストではない」、UNKNOWN は「形式を記録していないので何も言えない」

**digest は出力形式にもコミットする。** 同じ変換器・同じ元でも PDF と SVG は落とすものが
違うので、どちらだったかを entry が持たないと、読む人に見せる開示文が
「それが説明しているコピー」から切り離される。

### 負のコントロール 7 本

| 壊した箇所 | 落ちたテスト |
|---|---|
| 記録を通らない永続化経路を 1 本足す | `oneWayIn` |
| 記録の呼び出しを消す | `andItRecords` / `recordsTheDuplicationItJustMade` |
| SVG 要求を PDF 変換器に流す | `svgGoesThroughTheSvgConverter` |
| digest から出力形式を落とす | `theDigestCommitsToWhatWasProduced` ほか 2 |
| SVG の但し書きを PDF/A の文にする | `anSvgIsNotDescribedAsAPdf` / `everyConverterDisclosesWhatItIsNot` |

> `andItRecords` は**削除しか捕まえない**。`if (false)` で包むと緑のままなのを実測した。
> 到達可能性を測っているのは `recordsTheDuplicationItJustMade` のほう。

### まだ残っているもの

`AttachmentServiceDelegate.copyRenditions` (コピー時の rendition 複製) と
cloud drive 側の変換は**未**。PDF/A 出力と veraPDF 検証も**未**。

---

## 8. rendition のコピー (2026-08-26) — 記録の穴と、その下にあった孤児

チェックアウトは文書の rendition を作業コピーへ複製する。§7 の走査が
`ContentServiceImpl.java` 1 ファイルしか読んでいなかったので、
`AttachmentServiceDelegate.copyRenditions` が**別ファイルから
`createRendition` を呼んでいる**のを見逃していた。走査は全 `businesslogic` 配下に広げた
(DAO 層は除く — あそこが `createRendition` を呼ぶのは当たり前で、規則は
「業務ロジックが記録経路を迂回しない」ことのほう)。

**その下にもう 1 つあった。** 唯一の呼び出し元が**戻り値の id を捨てていた**ので、
チェックアウトのたびに「どの文書も参照していない rendition のコピー一式」が
保存されていた。孤児の行と孤児のバイト列である。

順序が記録にも効く。作業コピーが持っていない複製を「複製が在る」と記録することは
できないので、複製は**作業コピーが出来た後**に行い、その id を作業コピーに付ける
形にした (`ContentServiceImpl.copyRenditionsOnto`)。

### 変換していないものを変換器に帰属させない

コピーは**変換ではない**。`Converter.COPIED_RENDITION` を足し、開示は
「これを作るのに何も変換していない。別オブジェクトの rendition のバイト複製である。
その先行変換が落としたものはここでも落ちているが、**どのツールが行ったかを
この build は記録していない**ので、その損失を列挙できない」と言う。

出力形式は**コピー元の media type から引く** (`TargetFormat.forMediaType`)。
知らない media type は `UNKNOWN` で、PDF に丸めない。

### 負のコントロール 4 本

| 壊した箇所 | 落ちたテスト |
|---|---|
| コピーした id を文書に付けない | `theCopiesAreAttached` |
| コピーを変換器に帰属させる | `eachCopyIsRecorded` |
| 未知の media type を PDF に丸める | `anUnknownMediaTypeIsNotGuessed` |
| 報告の開示に PDF/A の文を戻す | `theDisclosureDoesNotGuessTheFormat` |

---

## 9. 報告の開示が出力形式を間違えていた (2026-08-26)

§7 で変換器側の開示は出力形式込みに直したが、**報告側は直っていなかった**。
`AuthenticityReportAssembler.DUPLICATION_DISCLOSURE` は
「This product converts to PDF without requesting or validating a PDF/A profile」で固定で、
REST が SVG を載せるようになった後は、**図面の SVG コピーについて PDF の話**を読ませていた。

報告は台帳から作られ、**entry は digest しか持たない**。だから形式も変換器も
復元できない。正直な形は「形式を名指ししない」ことである。

そのうえで、言えることは言う: entry は変換器と出力形式に**コミットしている**
(digest の入力である)。値を持っている者はこの entry を照合できる。
報告は復元できない。「昔は 1 つしか可能性が無かったほう」を選ぶより良い。

**平文で持たせる案は採らなかった。** `EvidenceLedgerEntry` にフィールドを足すと
entry hash の対象が変わり、保存済みの hash が全部無効になる。hash の**外**に足すのは
もっと悪い — 連鎖がコミットしていない注釈を、コミットしているものの隣に並べて
報告することになる。

---

## 10. PDF/A と veraPDF (2026-08-26)

### 要求することは得ることではない — だから開示は「見つけたこと」を言う

LibreOffice は `FilterData` の `SelectPdfVersion` を受け取り、**受け取ったうえで
出来るものを作る**。平坦化できない透過、埋め込めないフォント、profile が禁じる機能が
あれば、出力は PDF/A でないまま export は成功を返す。

したがって**要求を事実として記録しない**。`VeraPdfAValidator` がバイト列を検査し、
複製の開示は**検査結果**を述べる。要求を記録する製品は、自分で立てたフラグを根拠に
「この利便コピーは保存用レンディションです」と全読者に言うことになる。

判定は 3 値。`CONFORMS` / `DOES_NOT_CONFORM` / **`NOT_CHECKED`**。3 つ目が要点で、
「検査できなかった」は「PDF/A ではない」ではない。潰すと、検証を切った配備が
**誰も試していない標準に対して全コピーを不合格**と書くか、その逆をやる。

開示は**置き換える**、足さない。「PDF/A profile は要求も検証もしていない」を
検査結果の隣に残すと、読む人は悲観的なほうを取って検査が無意味になるか、
楽観的なほうを取って但し書きが嘘になる。1 つのコピーについて真なのは一方だけである。

**適合は形式についての言明である。** 「PDF/A だ」を「変換は忠実だった」と読まれるのが
この機能が招く誤読で、PDF/A が言うのは「後で描画するのに必要なものをファイルが
持っている」まで。変換が入口で何を落としたかについては何も言わない。

### 既定は off

PDF/A はコピーの見た目を変える (フォント埋め込み・透過の平坦化・カラー管理)。
これらはプレビューである。既存配備すべてで既定を変えると、全プレビューが変わり
全ファイルが太り、しかもその主張は配備側が検証しないといけない。決定なので設定にする。

### 測れていないこと (正確に)

**この build には LibreOffice が無いので、要求した profile が実際に通るかを測っていない。**
測ったのは veraPDF が実 PDF に判定を返すこと、3 値が崩れないこと、開示が
「見つけたこと」を述べること。**変換経路への配線と実測は未**であり、
fixture でそれらしく見せることはしない (変換について何も証明しない fixture になる)。

| 壊した箇所 | 落ちたテスト |
|---|---|
| 解析できないファイルを「不合格」と呼ぶ | `garbageIsNotAFailedFile` |
| 未知の flavour を 1b に丸める | `anUnknownFlavourIsNotGuessed` |
| NOT_CHECKED の但し書きを削る | `garbageIsNotAFailedFile` |
| 適合が内容を保証するかのように書く | `conformanceDoesNotVouchForContent` |
| 但し書きを置き換えず足す | `aValidatedCopyReportsTheFinding` |
| PDF の判定を SVG にも付ける | `aPdfFindingIsNotAboutAnSvg` |

---

## 11. 判定が読者に届くようにした (2026-08-26)

§10 の時点では、veraPDF の判定は**複製 entry の digest にコミットされるだけ**だった。
entry は平文を持たないので、**製品は PDF/A を検査していて、誰もその答えを読めなかった。**
これは本セッションで 3 度自分で指摘した「本番の呼び出し元が 0 件」と同じ型である。

### 平文をどこに置くか

台帳 entry に足す案は採らない — entry hash の対象が変わり、**保存済みの hash が
全部無効**になる。hash の**外**に足すのはもっと悪い (連鎖がコミットしていない注釈を、
しているものの隣に並べる)。

そこで **rendition 行**に置いた。連鎖は判定にコミットし (digest の入力)、行は平文で持つ。

**ただし「両方を持てば照合できる」は言い過ぎだった** (2026-08-26 訂正・レビュー指摘)。
V2 digest の入力は repository / object / sourceDigest / producedDigest / converter /
mediaType / outcome / flavour / actor である。報告の行が持つのは outcome・flavour・
mediaType と rendition id、entry が持つのは digest だけ。**converter・両 digest・actor は
どちらにも無い**ので、報告を開いた読み手には再計算する材料が無い。

書けるのは「**書込時の入力をまだ持っている者だけが照合できる**」まで。
`renditionsNowSource` はそう書き直した。「両方を持つ誰でも照合できる」は、
実際にはできない計算を根拠に、行を連鎖の証拠として扱わせる誘いである。

### 保存の順序

判定にはバイト列が要るので、素朴にやると「保存 → 判定 → 行を更新」になる。
更新は 2 度目の書き込みで、**2 度目の失敗機会**である。落ちれば「検査したのに
検査していないと報告される rendition」が残る。

そこで **PDF/A 有効時だけ、保存の前に読み切って判定し、判定を載せてから保存**する。
既定の経路は従来どおり stream する — 誰も訊いていない問いに答えるために全変換文書を
メモリに載せるのがこの設計の避けたい費用で、検証を有効にした配備は既にその
(上限付きの) 費用を受け入れている。

| 壊した箇所 | 落ちたテスト |
|---|---|
| 出所の文を値の後ろに回す | `theVerdictIsReadable` |
| 未検査を「検査済み」と出す | `anUncheckedCopyIsNotAFailedOne` |

---

## 12. 実測 (2026-08-26) — 要求は通るが、版によって結果が違う

コンテナ内の LibreOffice 24.2.7.2 に `SelectPdfVersion` を渡して変換し、
出た PDF を veraPDF に掛けた。デプロイはしていない (実行中の core コンテナで
`soffice` を直接動かし、出力を取り出して検証した)。

| 要求 | veraPDF の判定 | 失敗した検査 |
|---|---|---|
| 無し (素の PDF) | **DOES_NOT_CONFORM** | 7 件。XMP メタデータストリーム欠落、OutputIntent 無しの DeviceRGB、info 辞書と XMP の不一致 |
| `SelectPdfVersion=1` (PDF/A-1b) | **DOES_NOT_CONFORM** | **1 件**。§6.7.3 test 1 — info 辞書の `CreationDate` と XMP の `xmp:CreateDate` が「equivalent」でない |
| `SelectPdfVersion=2` (PDF/A-2b) | **CONFORMS** | 0 |
| `SelectPdfVersion=3` (PDF/A-3b) | **CONFORMS** | 0 |

ファイルサイズは素の PDF 10,014 バイトに対し PDF/A 版 23,988 バイト
(フォント埋め込みの分)。「PDF/A はコピーの見た目と大きさを変える」は実測どおり。

### 何が分かったか

**要求は効く。** LibreOffice はフォントを埋め込み、OutputIntent を付け、XMP を書く。
素の PDF が 7 件落ちるところを 1 件まで詰める。

**それでも 1b は適合しない。** 落ちるのは日付表現の不一致 1 点で、これは
LibreOffice 側の出力の問題である。**この 1 点があるので「PDF/A を要求した」を
「PDF/A である」と読んではいけない** — この設計が最初から言っていたことが、
主張ではなく測定になった。

### 直さない

出力に手を入れて日付を揃えれば 1b も通る。**やらない。** digest と記録は
「変換器が出したもの」にコミットしており、それを編集すると**記録自身の言明が嘘になる**。
利便コピーを 1 件のために書き換えるより、判定を正直に出すほうがこの製品の役目に合う。

### 運用への含意

**1b を設定すると、全コピーが正しく DOES_NOT_CONFORM になる** — 正直だが役に立たない。
2b か 3b を使うこと。`PropertyKey.RENDITION_PDFA_VALIDATE_FLAVOUR` の javadoc にも書いた。
後の LibreOffice で 1b が通るようになったらこの記述は陳腐化するので、
**信じずに測り直すこと**。

