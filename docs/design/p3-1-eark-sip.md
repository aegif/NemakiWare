# P3-1 — E-ARK SIP エクスポート

作成 2026-08-25。ロードマップ §4 Phase 3 の 1 番目。
前提は P1-1 (捕獲の来歴) / P1-3 (証拠台帳) / P2-0〜2-2 (アンカー)。

---

## 0. 何を主張し、何を主張しないか

**主張する** (第 2 段まで): **保存されている記録 1 件を、本文・記述メタデータ・
真正性レポートごと SIP にでき、CSIP 2.2.0 のリファレンス検証器を通る**。
検証は CI のユニットテストで、外部プロセス無しで回る。
**admin 限定の REST エンドポイント `POST /core/api/v1/admin/eark/export` から呼べる。**

> **初稿の時点では呼べなかった** (2026-08-26 訂正・レビュー指摘)。
> `EarkSipExporter` は `@Component` を付けて `jp.aegif.nemaki.rest.eark` に置いたが、
> **そのパッケージを scan する context が本番に存在しなかった** —
> `applicationContext.xml` の `jp.aegif.nemaki.rest` scan は
> `NemakiApplicationContextLoader` が `configLocations`
> (**applicationContext.xml を含まない**) で refresh し直した時点で消える。
> つまり bean にすらならず、呼び出し元も 0 だった。
> **`@Component` が付いているぶん「配線済み」に見える**という点で、
> 以前 anchor 層で見つけた「本番呼び出し元のない型」より悪い。
> `serviceContext.xml` (refresh 後に生きる) に scan を足し、
> `EarkSipExportController` を置いた。

**主張しない**:

- **E-ARK 準拠とは名乗らない。** 通ったのは `commons-ip2` に同梱された検証器であって、
  DILCIS Board の公式サービスではない。版・profile・検証器の版を固定して
  **「この版のこの検証器を通った」とだけ言う**。
  **受入側の実機は RODA 6.3.0 の SIP→AIP プラグインだけ測った** (2026-08-27、
  [`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §10) — `EARKSIP2ToAIPPlugin` が
  AIP object を作った。**受入承認を含む ingest workflow は未実施**、
  **Archivematica は未測定**。1 つ通ったことは「E-ARK 準拠」でも
  「どの archive でも通る」でも「先方が保持する」でもない
- **「記録の真正性が移送先で保たれる」とは言わない。** 検証器が言うのは**容器が
  仕様に合っている**ことだけで、中身が真実かどうかについては何も言わない
- ~~**PREMIS はまだ 1 件も書いていない。**~~ **2026-08-26 訂正**: PREMIS は
  `writePremis` が preservation metadata として同梱している (LoC 語彙から弁護できる語だけ宣言)。
  クロスウォークは §4
- ~~**`.ots` / TSA トークン / inclusion proof はまだ同梱していない**~~ **2026-08-26 訂正**:
  inclusion proof と、それを封じた checkpoint は `evidencePackage` として同梱済み。
  **`.ots` / TSA トークンそのものと ERS は未同梱** (§4)
- **AIP / DIP は作らない** (ロードマップ §3.1 の「軽量 Archive」責務を決めてから)
- **エクスポートは custody chain に残らない。** この製品でいちばん取り消せない操作が、
  真正性レポートの custody 節からは見えない (`logger.info` だけ)。
  記録経路を通していないので、**「エクスポートされていない」と「記録が残っていない」が
  見分けられない** — custody 節自身の但し書きが言っているとおりの状態である。別増分
- **`dc.xml` のルート要素 `dc:record` は Dublin Core の語彙に無い。**
  DCMES 1.1 は 15 要素しか定義しておらず `record` は含まれない。
  `MDTYPE=DC` と宣言しているので、**DC スキーマで検証する受入側には通らない可能性が高い**。
  `dc:relation` に `key=value` を詰めているのも独自符号化である。
  正確には「**DC 名前空間の要素を使った独自 XML**」であって Dublin Core ではない。
  commons-ip2 の検証器は中身のスキーマ検証をしないので CI では捕まらない

---

## 1. 依存の決定 (2026-08-25 オーナー判断)

**`org.roda-community:commons-ip2:2.12.0` を core の依存に入れる。**

### 座標を間違えた記録

- **Maven Central にあるのは `org.roda-community`**。`org.roda-project` は Central で
  **404** である (前回セッションで「Central にある」と結論したが、group を取り違えていた)
- `artifactory.keep.pt` には `commons-ip2` があるが **2.0.0-alpha / 2.0.0-SNAPSHOT
  (2018 年) だけ**で、使えない
- **プロジェクトの README は GitHub Packages を案内している** (token が要る)。
  README のほうが古く、Central には 2.9.3〜2.12.0 が署名付きで載っている

### 実測した footprint (2026-08-26 に `dependency:tree -Dverbose` で測り直した)

> **初稿の数字は誤っていた** (レビュー指摘)。「新規 6 個」「重複バージョン無し」と
> 書き、同じ文書の 3 行差で `jdom2` と `picocli` を**「既に在る」側にも「新規」側にも**
> 載せていた。以下が実測である。

**真に新規なのは 4 個**: `gov.loc:bagit:5.2.0` /
`org.apache.commons:commons-configuration2:2.15.0` /
`com.github.joschi.jackson:jackson-datatype-threetenbp:2.18.2` /
`org.threeten:threetenbp:1.7.0`。

**`jdom2:2.0.6.1` と `picocli` は元から在った** — jdom2 は
`tika-parser-news-module → rome`、picocli は
`tika-parsers-standard-package → tika-parser-pdf-module → pdfbox-tools` 経由。

**`picocli` は 4.7.6 → 4.7.7 に上がった。** commons-ip2 のほうが依存の深さで勝つ。
patch bump なので実害は小さいが、**既存依存の版が黙って動いた**ことは
このプロジェクトが何度も焼かれてきた形なので明記する。
それ以外の既存依存は 1 つも動いていない (commons-io / slf4j / jakarta.xml.bind /
jaxb-runtime はいずれも既存版が勝って omitted)。

commons-ip2 のサブツリーは 25 ノード。**「27 個」は数え方を示せない数字だったので撤回する。**

**除外が 2 個** (`<exclusions>`) — 両方とも実効あり (ツリーで確認):

| 除外 | 理由 |
|---|---|
| `javax.activation:activation:1.1.1` | **Jakarta EE 10 の WAR に javax が混ざる**。`jakarta.activation-api` + `angus-activation` が既に同じ役目を果たしている。ツリーに不在を確認 |
| `commons-logging` | 既に `jcl-over-slf4j` が入っており、**1 クラスパスに JCL が 2 つ**は典型的なログ分裂。残るのは `spring-core` の `provided` のみで WAR には入らない |

**WAR は約 +2.3MB** (commons-ip2 912K + commons-configuration2 689K +
threetenbp 515K + bagit 140K)。CLI fat jar 10.9MB が要らない、という節約側だけを
書いていたので、実コストも書く。

`gov.loc:bagit` は commons-ip2 の**旧 v1 パッケージの BagIt 経路でしか使われず**、
E-ARK SIP 経路は触らない。除外できる可能性が高いが、**未検証なので入れたままにする**。

---

## 2. 第 1 段 — 最小の SIP を 1 本通す (実施 2026-08-25)

**先に検証を通してから設計を書いた。** ロードマップの受入条件は「METS を生成する」
ではなく「**出力がバリデータを通ることを CI テストとして固定する**」なので、
それがこのビルド・この JVM・素のユニットテストから届くかどうかが先に分かっていないと、
残りを作る意味が決まらない。

`EarkSipSpikeTest` — ペイロード 1 ファイルの SIP を組み、
`EARKSIPValidator` に渡す。**検証器はライブラリ本体に入っている**ので、
sidecar も 10.9 MB の CLI fat jar も要らない (CLI 経由を検討していたが不要だった)。

結果: **success 118 / warnings 4 / errors 0 / skipped 35 → 通過**。

### 通すまでに踏んだ MUST 違反 2 件

| 規則 | 症状 | 原因 |
|---|---|---|
| **SIP2** (`mets/@PROFILE`) | 「値が E-ARK-SIP.xml ではない」 | `EARKSIP(id, contentType, informationType)` の **3 引数版は既定 2.1.0** で、`E-ARK-SIP-v2-1-0.xml` を書く。2.2.0 の検証器は `-v2-2-0` を要求する。**版を渡す 4 引数版を使う** |
| **SIP15** (`metsHdr/agent`) | 「パッケージを提出した agent が無い」 | `addCreatorSoftwareAgent` は `TYPE="OTHER" OTHERTYPE="SOFTWARE"` を書く。規則は `ROLE="CREATOR"` かつ **TYPE が OTHER でない**ことを求める — **提出者は「誰か」でなければならない**。ソフトウェア agent とは別に組織 agent を足す |

> **エラーメッセージは版付きではなかった。** SIP2 の文言は版なしの定数ファイルから
> 出るので「E-ARK-SIP.xml であるべき」と読めるが、実際に照合しているのは
> `SipMetsValidator220` の `E-ARK-SIP-v2-2-0.xml` である。**メッセージを信じて
> 版なしにすると、今度は逆に落ちる。**

### 残った SHOULD 4 件 (通過を妨げない)

`CSIPSTR5` / `CSIPSTR7` / `CSIPSTR13` / `CSIPSTR16` — いずれも
`metadata` / `metadata/descriptive` / representation 配下の `metadata`・`documentation`
フォルダが無い、という指摘。**第 2 段でメタデータを載せると自然に埋まる**ものなので、
今は埋めない (空フォルダを作って警告だけ消すのは、中身があるように見せることになる)。

### 負のコントロール 2 本実測

| 壊した箇所 | 結果 |
|---|---|
| 版を渡さない (既定 2.1.0 に戻す) | `errors: 1` / `INVALID` で落ちる |
| 提出者 agent を消す | `errors: 1` / `INVALID` で落ちる |

**「通った」と言うテストは「通らない」とも言えなければならない。** 両方確認した。

---

## 3. 第 2 段 — 実際の記録を 1 件載せる (実施 2026-08-25)

`EarkSipExporter` — repositoryId + objectId を受け、

- **本文のバイト列**を `representations/rep1` に
- **Dublin Core の記述メタデータ**を identity 属性から
- **真正性レポート (JSON) を other-metadata として同梱**

### エクスポートは開示であり、しかも取り消せない

真正性レポートは既に、開示表が `INTERNAL_ONLY` と印を付けたもの (chat 参加者・
実行者) を admin が求めない限り伏せる。**同じ境界がここにも効き、しかも重い**:
レポートは 1 人の呼び出し元への応答だが、**SIP は他組織のアーカイブに手渡される**。
複製され、取込まれ、複製され、意図的に保存される。**送り返してもらう手段は無い。**

したがって:

- **既定は伏せる**。呼び出し元が明示的に opt-in する
- **伏せた件数は必ず返す**。黙って落とした package は「捕獲した内容の完全な記録」として読まれる
- **開示の判断はレポートから読む**。オブジェクトの aspect を直接読み直さない —
  そこが**2 つ目の、緩いほうの開示規則**が生まれる場所だから

### package は「これが何を establish しないか」を持って行く

記述メタデータだけだと**実際より強く読める**。identifier / title / date は
このリポジトリが引き受けている主張のように見えるが、大半は**取込時に取込元が
言ったこと**であって検証されていない。だから節ごとの limits を含む真正性レポートを
**同じ封筒の中**に入れる。受け取る側が主張と但し書きを同時に受け取る唯一の配置である。

### 空の representation は出さない

添付が読めないときは**拒否する**。空の representation を持つ SIP は検証器を
問題なく通り、「この記録の本文は保存された」と読める。**出せる中で最悪の結果**。

### 負のコントロール 4 本実測

| 壊した箇所 | 落ちたテスト |
|---|---|
| 真正性レポートを同梱しない | `thePackageCarriesItsLimits` |
| 記帳用フィールドを DC に出す | `personalDataIsWithheldAndTheOmissionIsDeclared` |
| **呼び出し側から `escapeXml` を外す** | `aHostileNameCannotBreakTheWrittenXml` |
| 添付が無くても空ファイルで出す | `aDocumentWithNoContentIsRefused` |

> **3 本目は最初発火しなかった。** `escapeXml` を直接呼ぶテストしか無く、
> **それを呼ぶ側が無防備でも緑のまま**だった (ヘルパを直して呼び出し側を裸にする、
> このプロジェクトが繰り返し見つけている形)。実際の export を通して
> **書かれた XML を読む**テストに替えてから測り直した。

### 第 2 段のレビューで見つかった「測ったと書いて測っていなかった」3 件 (2026-08-26)

初稿はこの 3 つを主張していたが、どれもテストが支えていなかった。

| 主張 | 生き残った本番編集 | 追加した測定 |
|---|---|---|
| 「既定は伏せる。呼び出し元が明示的に opt-in する」 | `options.includeInternalOnly()` を `true` 固定にする | `theDisclosureChoiceIsPassedThrough` — assembler に渡る boolean を捕まえる。stub が `anyBoolean()` だったので何を渡しても同じレポートが返っていた |
| 「identity 属性から Dublin Core を作る」 | `disclosableIdentity` を空 Map にする | `identityAttributesReachTheDublinCore` — **dc.xml への肯定的 assert**。それまでの dc.xml の assert は全部「X を含まない」という否定形だったので、identity が消えれば当然通っていた |
| 「開示の判断はレポートから読む。aspect を直接読み直さない」 | aspect 直読みを足す | `theExporterDoesNotGoBehindTheReport` — **レポートが伏せた aspect を持つ Document** を渡す。旧テストの Document は aspects が空だったので、直読み実装でも同じ空の結果になっていた |

**負のコントロール 6 本を実測** (上の 3 本 + 非文字フィルタを弱める / 非 ASCII を潰す /
CSIP 版を落とす)。**うち「非 ASCII を潰す」は 1 度目の細工が当たっておらず、
`sabotage: 2` という誤解を招く数字を見て「発火しなかった」と読んだ。**
細工が入ったことを grep で確認してから測り直した。

### 実装側で直したもの

- **U+FFFE / U+FFFF が通っていた** — 制御文字ではないので `c >= 0x20` を素通りし、
  **書かれた `dc.xml` が受け側で parse 不能になる**。エスケープが、
  エスケープの目的そのものを壊していた。XML 1.0 §2.2 の許容範囲で判定するようにした
- **対にならないサロゲート** — UTF-8 に符号化できず `Files.writeString` が投げ、
  **題名の 1 文字で export 全体が拒否**されていた。落とす
- **非 ASCII のファイル名が全滅していた** — `[A-Za-z0-9._-]` 以外を潰すので
  **日本語 ECM で `議事録.txt` が `___.txt` になり、`報告書.txt` と同じ名前に潰れる**。
  非 ASCII は保つ。代わりに実際に壊れるものだけを扱う (Windows 予約デバイス名・
  末尾のドットと空白・長さ上限 200 byte・NFC 正規化)
- **例外の cause を捨てていた** — `"could not be built: null"` になり得た
- **記帳キーが 2 か所でリテラル管理**だった — assembler が改名すると、
  記帳キーが受入側に公開され、かつ「伏せた件数」が 0 になって
  **伏せているのに伏せたと言わなくなる**。共有定数にした
- **opt-in 側に印が無かった** — 「伏せるものが無かった package」と
  「意図的に全部載せた package」が区別できなかった。note を足した

---

## 4. 次の段 (未着手)

1. **journal イベント → PREMIS イベントのクロスウォーク表**。語彙を確定してから書く。
   ロードマップ §4 Phase 3 が要求している成果物
2. **evidence package (`.ots` / TSA トークン / inclusion proof) の CSIP 上の置き場所**。
   正位置は仕様で要確認 (ロードマップも「着手時に要確認」と書いている)
3. ~~**RODA 実機受入試験**~~ **SIP→AIP プラグイン試験は実施済み** (2026-08-27、
   [`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §10)。RODA 6.3.0 では
   CSIP 2.x 用の `EARKSIP2ToAIPPlugin` が AIP object を作り、本文が
   `representations/rep1/data/` に入った。v1 用の `EARKSIPToAIPPlugin` は
   同じ package を拒否した。
   **ただしこの節が作っている `premis.xml` は、生成された AIP の PREMIS metadata に
   無かった** — `metadata/preservation/` に在ったのは RODA 自身の event 2 件
   (`wellformedness check` / `unpacking`) だけである。**「置き換えた」とは書かない**:
   測ったのは我々のものの不在と RODA のものの存在であって、変換や置換の機構は
   確かめていない。§4-1 のクロスウォークが**この受け手に届く保証は無い**。
   `metadata/other/` の JSON 2 本は `metadata/descriptive/` に在った。
   **`ers.der` は測った** (2026-08-27 追試): 初回の package には入っていなかったので、
   スタブの記録を注入して投げ直した。**`metadata/preservation` に置くと
   `Failed to load PREMIS` で package ごと rollback する** — CSIP のその位置は PREMIS の
   場所で、そこに ASN.1 の DER を置いていたのは我々の読み違いだった。
   **`metadata/other` へ移すと取り込まれ、記録も残った** (ただし AIP では
   `metadata/descriptive/ers.der` へ移されている — 受け取った側が `other/` を探しても
   見つからない)。本製品は同日 `metadata/other` へ変更した
   ([`p3-4-custody-transfer.md`](p3-4-custody-transfer.md) §11)。
   **未**: 受入承認を含む full ingest workflow、Archivematica、他版の RODA

---

## 5. 証拠パッケージと第三者検証 (P4-1 第 1 段・2026-08-26)

### SIP に何を入れるか

`metadata/other/nemaki-evidence.json`:

- **この文書を連鎖に結び付ける inclusion proof** (`leafHash` / `merkleRoot` / `auditPath`)
- その文書について台帳が持つエントリの一覧 (sequence / kind / payloadDigest /
  occurredAt / entryHash / prevEntryHash)
- 何を establish しないか (`limits`)

> **inclusion proof が入るまでに 1 つ足りなかった。** 台帳は sequence でしか引けず、
> **subject では引けなかった** (レビュー L7)。読み手が持っているのはオブジェクト id で
> あって sequence ではないので、書いたエントリを**誰も見つけられない**状態だった。
> `entries_by_domain_subject` view と `findBySubject` を足した。
> **既存配備では design document の signature が変わるので view が再構築される。**
>
> 最新 checkpoint だけを同梱する案は採らなかった。それは「このリポジトリの連鎖は
> ある時点で封じられた」としか言わず、**隣にある文書について何も言わない** —
> 証拠ではなく飾りになる。

### 検証規則 (第三者が再実装できるように書く)

`SipVerifier` はこの 2 つだけを使う。**ここに書いてあるのが仕様で、コードは実装である。**

1. **payload digest**: `SHA-256(bytes)` を hex 小文字で、PREMIS の
   `objectCharacteristics/fixity/messageDigest` と比較する
2. **audit path**: RFC 6962 型。
   - 葉: `SHA-256(0x00 || entryHash)`
   - 節: `SHA-256(0x01 || left || right)`
   - 奇数のときは**複製せず 1 段繰り上げ**る (CVE-2012-2459 型の可鍛性を持たせない)
   - `auditPath` を下から順に適用し、`siblingIsLeft` なら sibling が左

`leafHash` は**エントリの生ハッシュ**であって葉ハッシュではない。
最初の実装はここを取り違えていて、**本物のパッケージを全部「壊れている」と報告する**
ところだった (ラウンドトリップのテストが捕まえた)。

### 4 値で答える — 「調べられなかった」は「失敗」ではない

`PASSED` / `FAILED` / `NOT_PRESENT` / `UNAVAILABLE`。
未対応の digest algorithm を `FAILED` と報告すると
**「バイト列が違う」と言ったことになる**が、実際に起きたのは「調べていない」である。

そして **1 つも検査が走らなかったパッケージは `verified` にしない**。
「何も問題が無かった」と「何も調べていない」は、同じ文で意味が逆になる。

### 何を主張し、何を主張しないか

**主張する**: パッケージが**内部整合している** — バイト列は隣に記録された digest に
一致し、audit path は主張する Merkle root に到達する。**どちらも誰でも確かめられる。**

**主張しない**:

- **記録が本物であること。** ここで確かめた材料は**全部同じリポジトリから出ている**ので、
  **改竄されたリポジトリから作ったパッケージも完全に検証を通る**
- **checkpoint が書き換えられていないこと。** それにはチェックポイントのハッシュが
  リポジトリの管理外に存在する必要があり (= 外部アンカー)、**このパッケージはまだ
  アンカー受領証を運んでいない**
- **公開ツールとして配布していない。** `SipVerifier` は core の中にあり、
  第三者は現状 WAR を取らないと動かせない。**独立した配布物にするのは別増分**である。
  上の仕様は、それが無くても第三者が自分で実装できるように書いてある

負のコントロール 5 本実測 (digest 比較を常に真 / audit path を無条件受理 /
何も調べなくても verified / 葉ハッシュを飛ばす / 未対応 algorithm を FAILED にする)。
**export → verify のラウンドトリップも 1 本測っている** — 他は全部手組みの fixture
なので、それだけだと「自分の fixture しか検証できない検証器」でも全部緑になる。

---

## 10. capture の証明を object id から引く (2026-08-26)

### 診断を 1 度間違えた

「SIP の inclusion proof が capture 済み文書を object id で引けない」の原因を、
**「CMIS object id はどこにも記録されていない」と書いたのは誤り**だった (並行レビュー指摘)。
完了時に `withCaptureOutcome` が intent 行へ `objectId` を入れており、
`VIEW_CAPTURED_BY_OBJECT` と `listCapturedForObject` があり、
**真正性報告はずっとそれで capture 行を引いていた**。ingest のホットパスに触る話ではない。

### 閉じ方

exporter が報告と同じ道を辿る: object id → capture 行 → intentId →
`findBySubject(intentId)`。object 配下の entry (fixity / duplication / custody) は
これまでどおり別に引き、両方を並べる。

**保存済みの capture entry の subject を object id に書き換える案は採らない。**
既に発行した inclusion proof が全部壊れる。

### 証明の対象を名乗る

旧版は `entries.get(0)` を証明して「the capture」と書いていた。
そのリストは**object 自身の entry** なので、ラベルが capture と言いながら
証明していたのは最古の fixity / 複製 / 受領証だった。
**対象を取り違えた証明は、証明が無いより悪い** — 検証できるので信じられる。

`provesEntry` / `provesSubjectId` / `provesSequence` を証明に添え、
capture entry が無いときは「これは capture の証明ではない」と明記する
(CMIS から作られた文書には capture entry がそもそも無い)。

| 壊した箇所 | 落ちたテスト |
|---|---|
| capture 行を辿らない | `theCaptureIsFoundThroughItsIntent` |
| 証明に対象を書かない | `theProofNamesItsSubject` |

