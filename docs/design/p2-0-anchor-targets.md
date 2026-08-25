# P2-0 — アンカー先のプラガブル化

作成 2026-08-24。ロードマップ §4 Phase 2 の 1 番目。前提は P1-3 (checkpoint)。

---

## 0. この増分が守る 1 行

> **確認されていないアンカーを「アンカー済み」と読ませない。**

P1-3 の checkpoint は**我々の DB の中**にある。外部アンカーの存在理由は、
その checkpoint を**我々が書き換えられない場所**にも置くことである。したがって
この機構の失敗の形は「送れなかった」ではなく、

- **送ったが確認されていないものを、確認済みと同じ棚に置くこと**
- **段が違うものを、同じ「アンカー済み」という 1 語で呼ぶこと**

の 2 つである。どちらも**強い方に倒れる**誤りで、レポート (P1-4) は
アンカーの状態をそのまま載せるので、ここで潰すと下流全部が誇張される。

---

## 1. 4 つの状態 — `PENDING` を成功に含めない

OpenTimestamps は**送信直後は pending** で、Bitcoin ブロック確定まで
**数時間かかり得る** (ロードマップ P2-1)。これを `PENDING` = 成功にすると、
「アンカーした」と言えるのに**まだ何も証明していない**期間が数時間できる。

| 状態 | 意味 | 「証明」として使えるか |
|---|---|---|
| `NOT_CONFIGURED` | この段は有効化されていない | — |
| `PENDING` | 送出は成功。**確認はまだ** | **使えない** |
| `CONFIRMED` | 外部側で確定し、証明が手元にある | 使える (段ごとの限界つき) |
| `FAILED` | 送出または確定に失敗 | 使えない |

`PENDING` と `CONFIRMED` を分けない設計は、fixity の
`NOT_RECORDED` / `UNVERIFIABLE` を潰すのと**同じ誤り**である。

> **名前の訂正 (2026-08-25)**: 初稿はこの表を `NOT_ATTEMPTED` / `SUBMITTED` と
> 書いていた。**実装にその名前は存在しない** (`AnchorStatus` は 2026-08-18 から
> `CONFIRMED` / `PENDING` / `FAILED` / `NOT_CONFIGURED`)。この増分の中心規則を
> 定義している表が、架空の識別子で書かれていた。

---

## 2. 段は自分の主張文を持ち歩く

段 1 (Atlas)・段 2 (OTS)・段 3 (TSA) は**証明できることが違う**。
**`AnchorTarget` に `claimLimits()` は無い** (初稿はそう書いたが実装と違う —
2026-08-25 訂正)。実際は `AnchorService.claimLimitsFor()` の**網羅 switch**で、
`AnchorKind.TimeSemantics` から導く。enum なので空にできず、新しい意味を足すと
コンパイラが文を要求する。散文フィールドより強い。

**引くのは kind ではなく receipt の意味である。** pending / failed の受領証は
kind によらず `NOT_A_TIME_PROOF` を持ち、accuracy の無い RFC 3161 トークンは
発行時に `UPPER_BOUND_ONLY` へ格下げされる。初稿は `kind.timeSemantics()` から
引いており、**まだ何も確定していない TSA の試行の隣に確定済みトークンの文**が
出ていた (2026-08-25 修正)。レポートはアンカーの状態と一緒にこの文を必ず載せる。

| 段 | tier id | 言えること | 言えないこと |
|---|---|---|---|
| 1 | `catalog` | 顧客自身の別システムにも同じ値がある | **時刻証明ではなく独立でもない**。両方に届く管理者には無力 |
| 2 | `opentimestamps` | commitment がそのブロック時刻**までに**存在した | 記録そのもの・メタデータの真実性。**主語は commitment であって記録ではない** |
| 3 | `rfc3161` | message imprint と `genTime` の結び付き (選んだ信頼・ポリシー検証のもとで) | 同上。**プロトコル自体は認定を意味しない** |

---

## 3. 未確定の backlog があるうちはアンカーしない

ロードマップ P1-3 の「**unsequenced backlog がある間の anchor 禁止**」を
この層で実装する。理由: 確定していない sequence が後から埋まると、
アンカーした Merkle root が**その時点の台帳の root ではなくなる**。
「この root は当時の台帳である」という主張が最初から偽になる。

`AnchorService` は `store.highestSequence(domain)` が checkpoint の
`toSequence` を**追い越していないこと**を確認してから送る。追い越していれば
**送らずに理由を返す** — 送って後で謝るのではなく。

**「一致」ではなく「≤」である** (初稿は「一致」と書いていた — 2026-08-25 訂正)。
台帳の head より**先**の sequence を主張する checkpoint が通り得るが、
それが起こるのは checkpoint 側が壊れているときだけなので、本筋は自己検証である。

**`selfVerifies()` を拒否条件に入れた (2026-08-25)。** メソッドは存在するのに
**本番の呼び出し元が 1 つも無かった**。root や範囲を書き換えた行も何かにはハッシュ
するので、自己整合を見ないと**改竄された値を外部に固定できてしまう**。

**受領証の proof と proofDigest の一致も見る。** 部分書き込みや壊れた blob を捕まえる。
**DB を編集できる者が整合するペアを書くのは止められない**ので、そう書いてある。

**工場と codec の規則を一致させた。** OTS の sidecar は `bitcoinBlockHeight` を返すが
**ブロック時刻は返さない** (Bitcoin ノードが要る)。工場は `anchoredAt` が null の
CONFIRMED を作り、codec はそれを拒んでいたので、**再起動したかどうかで verdict が
変わっていた**。時刻を発明せず、null のまま CONFIRMED を受け入れる。

> **「但し書きが言う」も初稿の時点では嘘だった** (2026-08-25 訂正)。付いていたのは
> `UPPER_BOUND_ONLY` の定型文で、本文は「**その時刻**より後ではない」と、
> **存在しない時刻を指していた**。しかも応答は `String.valueOf(null)` で
> **文字列 `"null"`** を返しており、時刻として読めた。両方直した —
> null は JSON null で出し、時刻を持たない確定済み受領証には但し書きを付ける。
>
> **その但し書きも段を選んでいなかった** (2026-08-25 再訂正)。上の文は
> OpenTimestamps 向けで、実装は kind を見ずに全段へ同じ 1 文を付けていた。
> CATALOG 受領証に付くと存在しない proof と block を発明する。段ごとに分けた
> (下記「§5.7 但し書きは 1 つの段のもの」)。
>
> **`null` が JSON null で出ることは、まだシリアライズ後の body では測っていない。**
> テストは Java の `Map` に対して assert している。MVC の `serializationInclusion` は
> コメントアウト済みなので現状は出るが、**測ってはいない**。

---

## 4. 段の有効化は独立

**`anchor.<tier>.enabled` というキーは無い** (初稿の誤り — 2026-08-25 訂正)。
実際は段 2/3 が URL の有無、段 1 が publisher bean の有無で決まる。
正しい一覧は §5.6 の設定キー表。1 つが落ちても他は進む。**1 段でも
`CONFIRMED` があれば「アンカーあり」だが、レポートは
どの段が確認済みかを必ず列挙する** — 「アンカー済み」の 1 語に
まとめると、段 1 だけの顧客が段 3 の文言を使える。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `PENDING` は「確認済み」に数えられない | 成功に含めると落ちる |
| 2 | 段ごとの `claimLimits()` は空にできない | 空を許すと落ちる |
| 3 | 1 段の失敗が他段を止めない | 例外を伝播させると落ちる |
| 4 | 未確定 backlog があるとアンカーしない | 検査を外すと落ちる |
| 5 | 無効な段は `NOT_CONFIGURED` で、`FAILED` ではない | 潰すと落ちる |
| 6 | 結果は段ごとに分かれて出る (単一の boolean にしない) | 潰すと落ちる |

---

## 5.5. 実装 (2026-08-25)

### 既存の抽象に載せた (重複を作った訂正)

**段の型は 2026-08-18 の `7997bfd57` で既に存在していた** — 探さずに書き始めて
`jp.aegif.nemaki.evidence.anchor` に 2 つ目の抽象を作ってしまい、統合した。
既存側の方が優れている:

- `AnchorKind` が**独立性と時刻の意味を型で運ぶ** (`NOT_A_TIME_PROOF` /
  `UPPER_BOUND_ONLY` / `BIDIRECTIONAL_WITHIN_ACCURACY`)。散文の
  `claimLimits` フィールドはいずれ**最も必要な段で空になる**が、enum は空にできない
- `AnchorTarget.anchor(String hexDigest)` は **digest しか受け取らない** —
  「原文もメタデータも識別子も外に出ない」がインタフェースの性質になっている
- 段 2 (OTS) と段 3 (RFC 3161) は実装済み

したがって本増分の**新規は 2 つだけ**である。

| 物 | 場所 |
|---|---|
| 送出 (台帳を見る 2 つの拒否 + 段ごとの封じ込め) | `core/.../evidence/anchor/AnchorService.java` |
| 段 1 の実装 | `core/.../rest/purview/anchor/CatalogAnchorTarget.java` |
| テスト | `AnchorServiceTest` (13) |

`claimLimitsFor(AnchorKind)` は `TimeSemantics` の**網羅 switch** (default 無し)。
新しい意味を足すとコンパイラが文を要求する。

### 負のコントロール 8 本実測

B1 PENDING を成功に含める / B2 全段に同じ文 / B3 段の例外を伝播 /
B4a 陳腐化 root をアンカー / B4b 読めない head を settled 扱い /
B5 未配線カタログを configured と報告 / B6 entity id 無しを CONFIRMED /
B7 proof を digest 自身にする。

**B6 は最初落ちなかった。** `AnchorReceipt.confirmed` が既に空 proof を
拒否していて、私のチェックではなく**既存の不変条件**がテストを支えていた。
理由文 (`entity id`) を assert する形に直して測り直した。

### 段 1 の proof は entity 参照であって digest 自身ではない

アンカー対象の値そのものを proof にすると、**アンカーされる当の値から
アンカーを証明する**ことになる。検証者に要るのは「どこへ行けば同じ digest が
見えるか」である。

### 未確認

`7997bfd57` のコミットメッセージは `AnchorReceipt.supportsIndependenceClaim()`
に言及しているが、**現在のコードに該当メソッドは無い**。意図が実装に落ちて
いないのか、後で別名になったのかは未確認。

---

## 5.6. 受領証の永続化と配線 (2026-08-25)

**段の実装は 2026-08-18 に揃っていたが、誰も呼んでいなかった**のが実体だった。
本増分でその環を閉じた。

| 物 | 場所 |
|---|---|
| 受領証の契約 | `evidence/anchor/AnchorReceiptStore.java` |
| 受領証の CouchDB 実装 | `evidence/anchor/CouchAnchorReceiptStore.java` |
| 受領証の符号化 | `rest/purview/anchor/AnchorReceiptCodec.java` |
| 段の Spring 配線 | `rest/purview/anchor/AnchorWiringConfig.java` |
| 運用 API | `rest/controller/AnchorController.java` |
| テスト | `AnchorReceiptPersistenceTest` (8) |

### 永続化が段 2 の要である

OTS は PENDING を返し、数時間後に確定する。`upgrade()` には**保留中の proof の
バイト列**が要る。メモリだけに持つと**再起動のたびに全消滅**し、カレンダーは
commitment を持ちブロックは確定しているのに、こちらは proof を出せなくなる。
段 2 が「設定済みに見えて何も生まない」= **装飾**になる、最も静かな失敗の形。

受領証は台帳と**同じ DB / 別 type**。ただし `EvidenceLedgerStore` には**入れない** —
あちらは意図的に update を持たない。受領証は pending → confirmed と**その場で
変わる**ので、追記専用インタフェースの裏に可変行を置くと、嘘のインタフェースか
「台帳の行も更新してよい」という誤学習のどちらかになる。

### 再読込で受領証が**強くなってはならない**

`status: CONFIRMED` なのに **proof が無い**行は、**FAILED として読む**。
**anchoredAt が無いのは正常である** (OTS は sidecar からブロック時刻を得られない —
§5.5 参照。初稿はこれも FAILED としており、同じ文書の中で規則が矛盾していた。
2026-08-25 訂正)。CONFIRMED として復元すると、**DB を編集できる者が、どの機関も発行して
いないアンカーを製造できる**。`timeSemantics` が壊れている場合も kind の既定に
戻さず `UPPER_BOUND_ONLY` に落とす — accuracy の無い RFC 3161 トークンの
意図的な格下げが、再読込のたびに取り消されてしまうため。

### `upgrade()` が別の段を返したら無視する

実装中にテストが捕まえた。別 kind を保存すると**もう一方の段のキーに行が書かれ**、
元の行は永久に pending のまま残る — 確定済みの proof が、誰も見に行かない名前で
1 行隣に座っている状態になる。

### 段は既定で全部 off

既定のエンドポイントを持たない。アンカー先は「証拠を誰に見せるか」の決定であり、
既定を置くと段 2 では**公開カレンダーへ黙って commitment を送る**ことになる。

### 設定キー

| キー | 既定 | 意味 |
|---|---|---|
| `anchor.opentimestamps.sidecar.url` | (空) | 空なら段 2 は off |
| `anchor.rfc3161.tsa.url` | (空) | 空なら段 3 は off |
| `anchor.rfc3161.policy.oid` | (空) | 設定時のみ送る (完全一致要求なので、誤値は全 stamp を拒否させる) |
| `anchor.rfc3161.accreditation` | `NONE` | 運用者の申告。**検査しない** — 認定は契約の事実であって検出するものではない |
| `anchor.rfc3161.trust-anchor.path` | (空) | PEM。未設定なら「応答者自身が持ってきた証明書」に対する内部整合の確認まで |

**trust anchor は設定したのに読めなければ起動を止める。** 黙って「anchor 無し」に
落とすと、運用者は自分で選んだ機関に対して検証していると信じたまま、受領証だけが
誰も読まない弱い検査を報告し続ける。**成功と見間違えようのない結果は起動失敗だけ**
である。逆に**未設定は正常** — 「anchor 無し」は受領証が既に説明している選択で、
「壊れた anchor」は間違いだから。負のコントロール 1 本実測 (握り潰して null を返すと
2 本落ちる)。

### 定期実行はまだ入れない

アンカーの頻度は**台帳がまだ書き直せる窓の幅**である。運用者に代わって決めるのは
リスクを代わりに決めることなので、API を用意して cron / runbook / 人が駆動できる形に
留めた。既定は checkpoint の定期化と同じ増分で決める。

負のコントロール **7 本実測** (P1 受領証を保存しない / P2 upgrade を書き戻さない /
P3 孤児 pending を failed にする / P4 kind ガードを外す / P5 proof 無し CONFIRMED を
復元 / P6 semantics を kind 既定に戻す / P7 proof バイト列を落とす)。

> **測っている層の訂正 (2026-08-25)**: この 7 本はいずれも**メモリ実装と codec に
> 対する測定**であり、`CouchAnchorReceiptStore` には一度も触れていない。
> 「永続化が段 2 の要である」と書いた当の層が、確認されていなかった
> (レビュー指摘)。Couch 側で確認済みなのは
> `CouchEvidenceLedgerStore` の実オーバーロード経路のみ
> (`theRealExplicitIdCreateFailsFast` / `aNotOkResultIsNotSuccess`)。
> ~~**受領証 store の実装そのものの実測は未了**として残す。~~
>
> **これは 2026-08-25 に解消した** (この注記だけが取り残されていた — レビュー指摘)。
> `CouchAnchorReceiptStoreTest` が 10 本あり、`create` / `update` の戻り値検査、
> CAS を失った再読込、単調性 (CONFIRMED / PENDING の両方)、有界再試行の
> 拒否、段ごとの行分けを実測している。`AnchorReceiptPersistenceTest` 側にも
> 「実際の強制は `CouchAnchorReceiptStoreTest` で測っている」と書いてあり、
> **同じ commit 範囲の 2 つの文書が逆のことを言っていた**。

### 単調性は **store の compare-and-set の中**にある (2026-08-25)

**CONFIRMED は CONFIRMED でしか置き換えられない。** `persist` は無条件 save、
store はその場で置換していたので、TSA が一瞬落ちただけの FAILED が既存トークンを
消し、同じ checkpoint の再アンカーが Bitcoin まで上がった `.ots` を PENDING に
戻していた。どちらも cron 1 回分の距離にある。**アンカーは証拠であり、
足すことはできても黙って取り去ってよいものではない。**

**判断の位置が要点である。** 初稿は `AnchorService` 側に「読んでから書く」形で
置いたが、それでは窓が残る — 弱い書き手が PENDING を読み、並行する書き手が
CONFIRMED を書き、弱い書き手がその上に被せる。**サービス層では閉じない。**

**データ形式は変わっていない。** CouchDB の `_rev` が既に compare-and-set であり、
store は以前からそれを送っていた。欠けていたのは (a) 判断を同じ窓の中で下すこと、
(b) `_rev` が陳腐化していたら読み直して**もう一度判断する**こと、の 2 つだけ。
移行も新フィールドも旧版との非互換も無い。

再試行は有界 (5 回) で、**尽きたら書き込みを強行せず拒否する** — 競合している当の
ものがアンカーなのだから、諦めて上書きするのはこの機構が防ごうとしている結末そのもの。

`save()` は `STORED` / `KEPT_STRONGER` を返す。**「書かないことにした」と
「書けなかった」は別の答え**で、boolean はそれを混ぜてしまう。実の失敗は throw。

負のコントロール 3 本実測 (判定を常に false / 再試行 1 回 / 尽きたら強行)。
**このとき mock した例外を実物に差し替えた** — mock は `getStackTrace()` が null で、
それが伝播すると surefire が NPE でクラスごと落ち、**どのテストが落ちたのか
読めない赤**になっていた。測定として弱い。

### 単調性は CONFIRMED だけを守っていた — PENDING も守る (2026-08-25)

上の判定は `candidate` が CONFIRMED でなく `stored` が CONFIRMED のときだけ拒否していた。
**proof を失うのはそこではない。**

**現状の段はどれもこれを起こせない** (2026-08-25 訂正 — 初稿は「起きている proof
喪失」として書いていた。**起きていない障害を書くのは、この層が防ごうとしている
過大表現そのもの**である。同じ増分で `range` が null を返す本番実装は無いと自己訂正
しながら、隣で同じ罠に落ちていた)。製品の `upgrade()` は**すべて**、渡された receipt
か CONFIRMED のどちらかを返す — インタフェース既定が `return pending;` で、
`OpenTimestampsAnchorTarget` が FAILED を組むのは `anchor()` の中だけである。

守っているのは**次に足す段**である。`upgradePending` は rung が返した receipt を
そのまま store に渡し、`pending()` は **PENDING 行しか返さない**。したがって一過性の
再確認失敗 (calendar が 500、timeout) を **FAILED receipt として返す段**を足した日に、
それが PENDING 行を上書きし、その commitment は**二度と再確認されない**。
calendar は今も持っていて block もやがて確定するのに、**この配備が訊くのをやめただけ**で、
どこにもエラーは出ない。ここで不可能にするのは安く、他所で気づくのは高い。

したがって順序を `CONFIRMED > PENDING > FAILED > NOT_CONFIGURED` とし、
**弱くなる書き込みを一律に拒否する**。逆向きの代償は、本当に死んだ commitment に
対する 1 回分の無駄な再確認と、永久に PENDING と読める行だけ。PENDING は
`NOT_A_TIME_PROOF` を伴うので、待っている間に何かを主張することはない。

負のコントロール 4 本実測: CONFIRMED のみに戻す → `aFailedRecheckDoesNotKillAPendingCommitment` /
すべての上書きを拒否する → `aPendingCommitmentReplacesAFailedAttempt` /
`NOT_CONFIGURED` を FAILED と同値にする → `anUnconfiguredReceiptDoesNotReplaceAFailedOne` /
FAILED が NOT_CONFIGURED を置けなくする → `aFailedAttemptReplacesAnUnconfiguredRow`。

> **初稿は「2 本実測」で 4 段すべてを主張していた** (2026-08-25 訂正)。実際に測れて
> いたのは `PENDING > FAILED` の 1 段だけで、`NOT_CONFIGURED` を使うテストが 1 本も
> 無かった。`NOT_CONFIGURED` は到達可能である — 段は必ず生成され `isConfigured()` は
> 設定で答えるので、URL を外す・publisher bean がまだ上がっていない、で
> 生存中に NOT_CONFIGURED 受領証が出る。

### 封じた checkpoint のアンカーをやり直す道が無くなっていた (2026-08-25)

`checkpoint-and-anchor` は「封じる」と「アンカーする」を 1 回で行う。
新しいエントリが無い実行を noop にして何もしないようにしたのは正しい
(余分な cron 実行が確定済み commitment を打ち直すのを止めた) が、
**封じたが**アンカーが FAILED だった checkpoint (TSA が 1 時間落ちていた等) に
**戻る道も同時に消えた** — 封は打ち直せず、`upgrade-pending` は PENDING 行しか
見ず、次の実行はまた「新しいものが無い」。その段は永久に FAILED のまま。

`AnchorService.retryUnsettled` を足した。**受領証を持たない段だけ**に接触する。
CONFIRMED はもちろん **PENDING も「持っている」に数える** — 送信済みで block を
待っているものにもう一度出すのは再試行ではなく二重の commitment だから。
**未設定の段にも接触しない**。**受領証 store が無ければ拒否する** — どの段が確定済みか
分からないことは、全部に接触してよい理由にならない。

### これを cron 経路に置いたのは間違いだった (同日中に訂正)

初稿は `checkpoint-and-anchor` の **noop 分岐から呼んでいた**。これは 3 つ壊した:

1. **未設定の段は永久に「持っていない」**。既定配備は 3 段とも生成されるが未設定なので、
   毎分の cron が **NOT_CONFIGURED 行を 3 本書き直し続ける** (1 回 7 往復、
   1 日 4,320 write、情報は 1 bit も増えない)
2. **失敗した段を無 backoff で叩き続ける**。それが段 3 なら**毎分 TSA トークンを買う**
3. **健全な定常状態が `refused: true` になる**。全段が settled なら receipts が空で、
   空を refusal として返していた — 運用者が `refused` を見なくなる作り方そのもの

`POST /v1/admin/anchor/retry-unsettled` に切り出した。**再試行は commitment を作り、
段によっては課金される。timer ではなく人が押す場所に置く。** backoff は無いので、
間隔は呼ぶ側が決める。空の結果は refusal ではなく、**「打つべき段が無かった」のか
「拒否した」のかは controller の `message` が言う**。

負のコントロール 6 本実測 (noop が retry する / 確定済みも打ち直す /
未設定の段も打つ / PENDING も再送信する / stale root を retry 経路が通す /
error 経路が前の checkpoint をアンカーする)。**最後の 1 本は最初発火しなかった** —
テストが `ledgerStore` を張っておらず、`never()` を支えていたのは私のコードでは
なく fixture の欠落だった。張ってから測り直した。

### 5.7. 但し書きは 1 つの段のもの (2026-08-25)

CONFIRMED かつ `anchoredAt` が無い receipt に付けていた但し書きは、
**kind を問わず同じ 1 文**だった。その文は OpenTimestamps 向けに書かれており
「proof は完全で、第三者は commit 先の block から時刻を読める」と言う。

これが **CATALOG receipt に付くと、存在しない proof と存在しない block を発明する**。
しかもその段自身の limits は「これは時刻の証明では**ない**」で始まる。
**弱い事実が強く読めるのを止めるための但し書きが、その当人になっていた。**

到達経路は「保存行の `anchoredAt` が読めない」場合である。
`AnchorReceiptCodec.instant()` は解析失敗を握って null を返し、
`confirmedOrRefuse` は proof が揃っていれば CONFIRMED として組み直すので、
**壊れた 1 行が catalog receipt を OTS の文で飾る**。

段ごとに分けた: CATALOG は但し書き無し (基文が既に全部言っている)、
OTS は block を指す、RFC 3161 は **token の中**を指す (block ではない)。

負のコントロール 2 本実測 (全 kind に 1 文 / 但し書きを消す)。

---

## 6. やらないこと (この増分では)

- **OTS の実送信** — P2-1 (sidecar が要る)
- **TSA トークンの取得と検証** — P2-2
- **定期実行** — checkpoint の定期化と同じ増分で決める
- **段をまたぐ集約根** — P1-3 §3 の別増分
