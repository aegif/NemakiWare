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
**数時間かかり得る** (ロードマップ P2-1)。これを `SUBMITTED` = 成功にすると、
「アンカーした」と言えるのに**まだ何も証明していない**期間が数時間できる。

| 状態 | 意味 | 「証明」として使えるか |
|---|---|---|
| `NOT_ATTEMPTED` | この段は有効化されていない | — |
| `SUBMITTED` | 送出は成功。**確認はまだ** | **使えない** |
| `CONFIRMED` | 外部側で確定し、証明が手元にある | 使える (段ごとの限界つき) |
| `FAILED` | 送出または確定に失敗 | 使えない |

`SUBMITTED` と `CONFIRMED` を分けない設計は、fixity の
`NOT_RECORDED` / `UNVERIFIABLE` を潰すのと**同じ誤り**である。

---

## 2. 段は自分の主張文を持ち歩く

段 1 (Atlas)・段 2 (OTS)・段 3 (TSA) は**証明できることが違う**。
`AnchorTarget` は `claimLimits()` を持ち、これは**空にできない**
(P1-4 の `Section.limits` と同じ強制)。レポートはアンカーの verdict と
一緒にこの文を必ず載せる。

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

`AnchorService` は checkpoint の `toSequence` が
`store.highestSequence(domain)` と一致することを確認してから送る。
一致しなければ**送らずに理由を返す** — 送って後で謝るのではなく。

---

## 4. 段の有効化は独立

`anchor.<tier>.enabled`。1 つが落ちても他は進む。**1 段でも
`CONFIRMED` があれば「アンカーあり」だが、レポートは
どの段が確認済みかを必ず列挙する** — 「アンカー済み」の 1 語に
まとめると、段 1 だけの顧客が段 3 の文言を使える。

---

## 5. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | `SUBMITTED` は「確認済み」に数えられない | 成功に含めると落ちる |
| 2 | 段ごとの `claimLimits()` は空にできない | 空を許すと落ちる |
| 3 | 1 段の失敗が他段を止めない | 例外を伝播させると落ちる |
| 4 | 未確定 backlog があるとアンカーしない | 検査を外すと落ちる |
| 5 | 無効な段は `NOT_ATTEMPTED` で、`FAILED` ではない | 潰すと落ちる |
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

`status: CONFIRMED` なのに proof が無い / anchoredAt が無い行は、**FAILED として
読む**。CONFIRMED として復元すると、**DB を編集できる者が、どの機関も発行して
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

---

## 6. やらないこと (この増分では)

- **OTS の実送信** — P2-1 (sidecar が要る)
- **TSA トークンの取得と検証** — P2-2
- **定期実行** — checkpoint の定期化と同じ増分で決める
- **段をまたぐ集約根** — P1-3 §3 の別増分
