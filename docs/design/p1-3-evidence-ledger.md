# P1-3 — Tamper-evident evidence ledger

作成 2026-08-24。ロードマップ §4 Phase 1 の 3 番目。前提は P1-1 (捕獲の来歴) と
P1-2 (保存バイト列の再検証)。

---

## 0. 何を主張し、何を主張しないか

**主張する**: 台帳に**入った**エントリについて、**checkpoint より前の書き換え・削除・
並べ替えを検出できる**。エントリごとに O(log n) の inclusion proof を出せる。

**主張しない**:

- **改変の防止ではない。** アプリケーション層の追記専用であって物理的な不変性ではない。
  CouchDB に直接届く者は行を書き換えられる。この作業が与えるのは**検出**である
- **checkpoint 以降は保護されない。** 直近 checkpoint から現在までの区間は、
  まだ何とも突き合わせられていない。**頻度 = 書き直され得る窓**
- **外部への独立性は、まだ無い。** checkpoint を我々自身の DB に置いている限り、
  管理者は checkpoint ごと書き換えられる。**独立性は外部アンカー (P2) が与える**。
  この作業はアンカーする対象を作るところまで
- **「全部が台帳に在る」とは言わない。** 台帳に入れる経路を通らなかった事実は
  台帳には無い。何が入るかは §2

> **順序は「確定 sequence 順」であって時計順ではない。** 分散した書き手の壁時計は
> 揃わない。台帳が固定するのは **sequencer が確定した順序**であり、`occurredAt` は
> エントリの中身であって順序の根拠ではない。

---

## 1. なぜ journal と分けるのか

配送用 journal (`lineage_event` / `_v2`) は **purge 可**である
(`lineage.retention.days`)。証拠の台帳が同じ寿命だと、**保持期限が来た瞬間に
連鎖が切れる**。

| | journal | evidence ledger |
|---|---|---|
| 目的 | カタログへの**配送**の作業台 | **証拠**そのもの |
| 保持 | `lineage.retention.days` で purge | 期間・法的根拠別。purge しても**連鎖は切れない** (§5) |
| 書き換え | publish status など**進行**を書く | **追記のみ**。既存行は変えない |
| 順序 | 配送の都合 | sequencer が確定した順序 |

---

## 2. 何が台帳に入るのか

**digest だけを入れる。** エントリは「何が起きたか」の本文を持たず、
**その事実の正準 digest** と、突き合わせに必要な最小の識別子だけを持つ。

理由が 3 つある:

1. **PII を連鎖に固定しない。** 本文を入れると、削除できない場所に個人データが
   恒久的に residing する。disclosure §7 の判断と一貫させる
2. **journal が purge されても連鎖は成立する。** digest は本文の生死に依らない
3. **エントリが小さいほど anchor と proof が安い**

したがってエントリは:

```
EvidenceLedgerEntry {
  domain          // 連鎖のドメイン = repositoryId (§3)
  sequence        // sequencer が確定した順序。時計順ではない
  subjectKind     // CAPTURE_COMPLETED / LINEAGE_EVENT / FIXITY_RESULT / DISPOSITION
  subjectId       // その事実を後から引くための id (intentId / eventId / objectId)
  payloadDigest   // その事実の正準 digest (mh1 / creationPayloadDigest / fixity digest)
  occurredAt      // 中身。順序の根拠ではない
  prevEntryHash   // 直前エントリの entryHash
  entryHash       // H(LEDGER_ENTRY_V1, domain, sequence, subjectKind, subjectId,
                  //   payloadDigest, occurredAt, prevEntryHash)
}
```

---

## 3. 連鎖のドメインは **リポジトリ単位**

| 案 | 判定 |
|---|---|
| **リポジトリ単位** | **採る**。sequencer が既にリポジトリ単位で、そこに乗せれば
順序の確定と連鎖の確定を**同じ CAS** に入れられる |
| 全体で 1 本 | 採らない。**新しい単一競合点**を作る。全リポジトリの書き込みが
1 つの allocator に並ぶ |

**代償は明示する**: アンカー (P2) の費用は**ドメインの数だけ掛かる**。
リポジトリが 10 個あれば 1 日 10 アンカーである。ロードマップ §Phase2 の
「単一の集約根を設計しない限りこの数はドメインの数だけ掛かる」はこの判断のこと。
集約根 (全ドメインの checkpoint をさらに 1 本にまとめる) は**別増分**として残す。

---

## 4. checkpoint と inclusion proof — なぜ線形連鎖では足りないか

線形のハッシュ連鎖だけだと、「エントリ E が checkpoint C の時点で台帳に在った」
ことを示すのに **E から C までの全エントリ**が要る。100 万件の台帳では
proof が 100 万件になる。

したがって **期間ごとの Merkle tree** にする (Certificate Transparency と同じ形):

- 期間内のエントリの `entryHash` を葉として Merkle tree を作る
- checkpoint = `H(CHECKPOINT_V1, domain, periodEnd, merkleRoot, prevCheckpointHash)`
- **inclusion proof** = 期間内の audit path (O(log n)) + checkpoint 連鎖の抜粋
- **アンカーするのは checkpoint hash** (P2)

**葉の重複と 2 の冪でない木の扱いを明示する**: 葉が奇数のとき最後の 1 つを
**複製しない** (複製する実装は CVE-2012-2459 型の可鍛性を持つ)。奇数のときは
その葉を 1 段繰り上げる。

---

## 5. purge しても連鎖が切れない理由と、genesis

evidence ledger は purge しない。**journal を purge しても**、台帳のエントリは
digest しか持っていないので影響を受けない。

台帳そのものを保持規則で削る必要が出た場合 (法的要請など) は、
**削除した区間の直後を genesis にする** — `prevEntryHash = null` の新しい起点を作り、
「ここより前は別の理由で失われた」ことをエントリとして残す。
**黙って詰めない**: 連鎖が繋がって見えるのに実際は欠けている、が最悪である。

---

## 6. fork の検出

failover で 2 つの書き手が同じ sequence を主張しうる。

- **書き込み時**: sequence を `_id` に含めた行の作成が CAS で 1 つしか通らない
- **検証時**: 同じ sequence に異なる `entryHash` の行が在れば **fork**。
  検出したら**その旨をエントリとして残す**。黙って片方を採らない

**未確定 backlog が在る間は anchor しない。** 確定していない sequence の
先にある checkpoint は、後から中身が変わりうる。

---

## 7. 受入条件 (負のコントロールつき)

| # | 条件 | 負のコントロール |
|---|---|---|
| 1 | エントリ 1 件の書き換えで、その後の全 `entryHash` と checkpoint が変わる | 連鎖から `prevEntryHash` を外すと落ちる |
| 2 | エントリの削除 (詰め) が検出される | sequence の連続性検査を外すと落ちる |
| 3 | 並べ替えが検出される | `sequence` を hash から外すと落ちる |
| 4 | inclusion proof が正しい葉でだけ検証を通る | audit path の検証を素通しにすると落ちる |
| 5 | 奇数葉の Merkle root が**複製方式と一致しない** | 最後の葉を複製する実装に変えると落ちる (可鍛性) |
| 6 | golden vector が python 参照実装と一致 | 式のどこを変えても落ちる |
| 7 | 同一 sequence の別 hash が **fork として報告される** | 片方を黙って採ると落ちる |
| 8 | genesis (prevEntryHash = null) は連鎖の**切断として報告される**、成功ではない | 詰めると落ちる |

---

## 7.5. 永続化 (2026-08-24 追加)

`CouchEvidenceLedgerStore` — **専用データベース `nemaki_evidence_ledger`**。
journal と同居させると `lineage.retention.days` の purge を共有し、
**保持期限が来た日に連鎖が切れる** (§1 がまさにそれを避けるための節)。
このクラスに purge 経路は 1 つも無い。

view は **4 本** (`entries_by_domain_sequence` / `checkpoints_by_domain_to` に加え、
アンカー受領証の `receipts_by_checkpoint` / `receipts_pending`。P2-3 で
`receipts_confirmed` を足して 5 本)。**同じ design document に置く** —
`createOrUpdateView` は view ごとに get-modify-put するので、別々に配ると
CouchDB が直前に作った index を捨てる (journal 側で学んだ形)。
**1 回の put にまとめた (2026-08-25)。** 5 回の get-modify-put は design document を
5 回書き、view の集合が変わる場面では CouchDB が直前に作った index を捨てる。
再構築中の view は不完全な index のまま 200 を返す — 台帳を確認しに来たまさにその
瞬間に「空」と読める。journal 側で一度学んだ形を、1 本ずつ書いたせいで繰り返していた。

> **初稿は「起動のたびに全 view を再構築」と書いた。過大である** (2026-08-25 訂正)。
> view group の signature は **view の定義**から計算され、design document の `_rev` は
> 入らない。5 本とも同一内容で既に入っているデプロイでは、5 回書いても内容は毎回
> 同じなので signature は変わらず、**index は捨てられない**。実際に効くのは
> **初回配備と、view を足すアップグレードのとき**である。
> journal 側の記録 (`CouchLineageJournalStore`) も "on first start" と限定しており、
> 私はそれを「起動のたび」に格上げしていた。変更 (冪等な 1 回 put) 自体は改善だが、
> 正当化を強くしすぎた。

**checkpoint は「請求する範囲」と「実際に読めた行」を突き合わせる。**
verifier は渡された列の**内部**関係しか見ないので、view の遅延で短く返ると
0..50 の Merkle を 0..100 の checkpoint として封じられた。端点と件数を照合する。
`_id` は `evidence_ledger:{domain}:{19桁 sequence}` で、同 sequence の 2 人目は
create-if-absent で **409 に負ける**。

**戻り値も検査する** (2026-08-25 訂正)。初稿は例外の有無だけを見ており、
`create(String id, Map)` が起動中に既定で返す `null` を成功と読んでいた。
しかもその時点で当のオーバーロードは**全例外を握っていた**ので、
`append()` は常に `true` を返していた — テストはモックに実物と違う契約を
教えていただけだった (レビュー 3 者が同時に指摘)。

**409 と outage を区別する。** 両方を `false` にすると、DB 停止中に
「誰かが sequence n を書いた」と読んで n+1 へ進み、**n は永久に空**のまま
以後の検証がそこで break を報告し続ける。conflict だけが `false`、他は throw。

負のコントロール 3 本実測 (L1 全失敗を conflict 扱い / L2 同 sequence の重複を潰す /
L3 空ドメインを 0 と報告) — いずれも意図したテストを落とす。

---

## 7.6. 生産者 (2026-08-25)

**台帳に書く経路が無かった。** store も checkpoint もアンカーの梯子も検証器も
あって、**テスト以外から append する者が誰もいなかった**。レポートの ledger 節が
常に ABSENT だったのも、空の span への checkpoint が何にもコミットしないのも
すべて同じ原因である。**誰も食わせない連鎖は足場であって証拠ではない。**

`EvidenceLedgerRecorder` を置き、capture の完了時に
`CAPTURE_COMPLETED` を追記する。

### 追記の失敗をどう扱うか — 相反する 2 つを両立させる

| 規則 | 理由 |
|---|---|
| **操作を失敗させない** | この時点で capture 行は既に永続化されている。2 本目の記録が書けないことで取込を失敗させると、**その記録が対象にしていた当のものが消える** |
| **黙ってもいけない** | fail-open の教訓そのもの。**誰にも告げられない連鎖の穴は、連鎖が無いより悪い** — 連鎖は信じられるから |

したがって結果は boolean ではなく **flag + warning** を返し、
`CaptureResult.capturedWithGap(...)` として呼び出し元に届く。
`captured` は true のまま — 実際に captured だからである。

### 遡らない

backfill は**作らない** (§8)。この class より前の取込は連鎖に入っていない。
遡って作った証拠は証拠ではないので、レポートは「連鎖は起きたこと全部の記録ではない」
と言い続ける。

### payload digest

本文は入れず、**再計算できる digest** だけを入れる (§2)。
`LEDGER_CAPTURE_COMPLETED_V1` でドメイン分離し、
repositoryId / intentId / connectorId / sourceObjectId / 適用済みメタデータ hash から作る。
**保存された capture 行から検証者が再計算できる**ことが要点で、
wrapper が付けた自由文は pass ごとに変わるので入れない。

負のコントロール 4 本実測 (穴を握り潰す / 失敗を伝播させる /
digest が適用済み hash を無視する / 未配線でも毎回警告する)。

---

## 8. やらないこと (この増分では)

- **外部アンカー** — P2。この作業はアンカーする対象 (checkpoint) を作るところまで
- **署名** — P2 (TSA トークン) / P3
- **ドメイン横断の集約根** — 別増分 (§3 の代償を減らす作業)
- **既存 journal 行の遡及取り込み** — 台帳は今日から前を向いて積む。
  遡って作った証拠は証拠ではない
