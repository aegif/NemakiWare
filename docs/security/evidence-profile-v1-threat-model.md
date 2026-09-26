# 証拠 profile v1 の脅威モデル

2026-09-19。`BASE_SHA` = `df6c1f2ad`。計画
[`../design/v3.4.0-evidence-and-residuals-plan.md`](../design/v3.4.0-evidence-and-residuals-plan.md) §5 の成果物。
経路の棚卸しは [`../design/evidence-phase0-inventory.md`](../design/evidence-phase0-inventory.md)。

> **行番号は引かない。** 同じバッチの修正で 2 度ずれ、1 度は訂正の向きが逆だった
> （5 巡目の指摘）。メソッド名で引く。

**この文書は設計の意図と、現在のコードが実際にしていることを分けて書く。**
「Phase N で入る」と書いてあるものは**今は無い**。今あるものには file:line を付ける。
分けない書き方をすると、この文書自体が「弱い事実を強い事実として読ませる」ものになり、
計画 §4 が禁じている当のことをやることになる。

---

## 1. 何を守るのか

**守る対象は「記録がある時点でその内容だったこと」を後から示せる能力**であって、
記録そのものの秘密性でも可用性でもない。具体的には:

- 台帳 entry と checkpoint の連鎖
- checkpoint を外部に固定した anchor receipt
- 書き出した証拠パッケージ (E-ARK SIP / EvidenceBundle)
- それらを第三者が再検証するための規則と材料

可用性・機密性・アクセス制御は**この文書の対象外**である (別の層で扱う)。

---

## 2. 想定する敵

| # | 敵 | できること | できないと仮定すること |
|---|---|---|---|
| A1 | **リポジトリの管理者** | CouchDB の文書・添付・台帳行・checkpoint 行を任意に書き換える。再索引を走らせる。設定を変える | 外部 anchor 先 (TSA / OTS / 外部保管した checkpoint) を遡って書き換えること |
| A2 | **パッケージの保管者** | 受け取った SIP を改変・再圧縮・差し替える。一部を捨てる | anchor 先の記録を書き換えること |
| A3 | **記録の提示者** | 古いが正当な checkpoint を「最新」として出す。別文書の proof を組み合わせる。package 内の root を信頼根拠として提示する | 外部で保持された expected checkpoint を書き換えること |
| A4 | **経路上の攻撃者** | TSA の URL を差し替える。応答を偽造する | trust anchor の秘密鍵を得ること |
| A5 | **検証器への攻撃者** | ZIP bomb、path traversal、巨大 entry、entry 名の重複を含む package を渡す | 検証器の実行環境を奪うこと |

**A1 が中心である。** 他の ECM が改ざんを防げると言えるのは、A1 を前提から外しているからである。
ここでは外さない — 外した瞬間、台帳は「管理者が書き換えていないなら正しい」という
循環になる。

---

## 3. 信頼するもの / しないもの

### 信頼する

- **入手経路を確認した verifier の実行ファイルとその digest** (package の中の verifier ではない)
- **package の外から与えた trust profile** (どの TSA / どの root を信じるか)
- **profile 1 の凍結済み正準化** (版で固定した規則。後から解釈を変えない)

### 信頼しない

- **SIP 内の自己申告** (「これは検証済み」「この operator は認定を受けている」等の文字列)
- **package の中にしか無い Merkle root** — それを信頼根拠にすると、package を作った者が
  自分で自分を証明したことになる
- **repository の `latest`** — 提示時点の最新は、当時の最新ではない
- **operator の accreditation 文字列**
- **未アンカー区間** — checkpoint の連鎖は外部固定まで届いて初めて A1 に耐える
- **`nemaki:contentHash` 単独** — これはサーバが計算した値だが、**取込が取得した bytes**
  についての digest（`DigestSubject.INPUT`）であって、**保存された bytes** の digest では
  ない。保存 bytes の読み直しは `FixityVerifier` の `SUBJECT_STORED_REVERIFIED` が別に持つ
  ([`evidence-phase0-inventory.md`](../design/evidence-phase0-inventory.md) §1)

### 証明しない (どの profile でも)

capture の完全性 / metadata の真実性 / source の真正性 / endpoint の組織的独立 /
法令適合 / **package が最新であること** (package の外に expected checkpoint があれば
rollback は検出するが、それは「最新である」証明ではない)。

---

## 4. 脅威と、今のコードが実際にしていること

**「今」= `df6c1f2ad` 以降のこのブランチ。**

| # | 脅威 | 今のコード | 閉じるのは |
|---|---|---|---|
| T1 | A1 が anchor の後に文書 bytes を差し替える | **台帳では検出できない。** 台帳 entry の `payloadDigest` は取込事実と適用済みメタデータの hash で、**文書 bytes の digest を含まない** (`EvidenceLedgerRecorder.recordCaptureCompleted` → `captureDigest`:231)。fixity scan は保存 bytes を読み直して `nemaki:contentHash` と突き合わせるが、**それは取込した文書だけ**で、CMIS 経由で書かれた content には比較対象が無い | **E1** (Phase 3) |
| T2 | A2 が SIP の payload を差し替える | `SipVerifier` の payload digest 検査が PREMIS の記録値と突き合わせる (`SipVerifier.payloadDigestCheck`) | 済 (ただし T7 / T8 の穴あり) |
| T3 | A2 が payload と PREMIS の digest を**同時に**差し替える | **検出できない** — 両方とも package の中にあり、外の材料と結び付いていない。inclusion proof がある package なら、proof の対象 entry と結び付けることで検出できるはずだが、**今は payload digest と audit path を別々に見ているだけで、両者が同じ entry を指すことを見ていない** (計画 §3) | **Phase 4 / 5** (binding) |
| T4 | A3 が古い正当 checkpoint を提示する | **検出できない。** package 外の expected checkpoint を受け取る口が無い | **Phase 5** (P2 の rollback 検出) |
| T5 | A3 が package 内の root を信頼根拠にさせる | `SipVerifier` の `limits` が「独立性には外部 anchor が要る / この package はそれを運んでいない」と**書いてはいる** (`SipVerifier.LIMITS`)。ただし**文言であって検査ではない** | **Phase 5** (profile で必須にする) |
| T6 | A3 が別文書の proof を組み合わせる | **検出できない** (T3 と同じ理由) | **Phase 4 / 5** |
| T7 | A5 が ZIP bomb / 巨大 entry を渡す | **無防備。** `SipVerifier.read`（メソッド `read(Path)`）は全 entry を `ByteArrayOutputStream` に**無制限に**読み込む。件数・サイズ・圧縮率のどれも見ていない | **Phase 5** (`RESOURCE_LIMIT`) |
| T8b | A5 が**証拠 JSON の中で**キーを重複させる | **塞いだ (2026-09-20)**。`STRICT_DUPLICATE_DETECTION` を有効にしたので重複キーは parse error → `UNAVAILABLE`。以前は Jackson 既定の**後勝ち**で、先勝ちの読み手と答えが割れた (手組み時代は先勝ち。向きが反転していた) | 済 |
| T8 | A5 が entry 名を重複させる / 未参照の entry を足す | **検出できない。** `read` の `entries.put(entry.getName(), ...)` は `Map#put` なので**後勝ちで黙って上書き**する。`textOf` は suffix 一致の**最初の 1 件**を返す。`payloadDigestCheck` は payload の**どれか 1 つ**が記録 digest に一致すれば PASSED を返す（`payloadDigestCheck` の payload ループ）ので、**無関係な payload を並べても通る** | **Phase 2 / 5** (P0 の一対一) |
| T9 | A4 が TSA を差し替える / 応答を偽造する | 署名検証と trust anchor 時の PKIX はある。**発行時の失効材料 (CRL / OCSP) を保存していない** (`AnchorReceiptCodec` に `revocation` / `OCSP` / `CRL` の語が 0 件) ので、後から「発行時に有効だったか」を offline で言えない | **Phase 6** |
| T10 | 時刻の主張を過大に読む | `AnchorKind` が `TimeSemantics` を型で持ち (`NOT_A_TIME_PROOF` / `UPPER_BOUND_ONLY` / `BIDIRECTIONAL_WITHIN_ACCURACY`)、`AnchorService` が限界文をそこから導く。**テキストで渡させない** | 済 |
| T11 | 検証器が「確かめられなかった」を成功として返す | 検査ごとに 4 値、全体は 3 値 (`VERIFIED` / `FAILED` / `INDETERMINATE`)。必須検査は payload digest と audit path の**両方** (`SipVerifier.Result.verdict()`)。`verified` は `VERIFIED` のときだけ true。`auditPath` は「無い」「読めない」「在って空」を分け、**空は `UNAVAILABLE`**（空の path は leaf と root を比べるだけで、どちらも package が書いた値） | 済 (2026-09-19。ただし T8 が開いている間は、entry 名の重複で本物の証拠ファイルを隠せる) |
| T12 | 検証器を package の中から供給する | `SipVerifier` は core の中にあり、**第三者は WAR を取らないと動かせない**。配布物ではない | **Phase 5** (独立 CLI) |

---

## 5. この文書が主張しないこと

- **「脅威を網羅した」とは書かない。** ここにあるのは 2026-09-19 時点で列挙できたものだけで、
  列挙できなかった脅威は残件であり、無いことの証明ではない。
- **T1 / T3 / T4 / T6 / T7 / T8 は今のコードでは閉じていない。** 「計画にある」は
  「実装した」ではない。Phase が終わるまで、この表の「閉じるのは」列は**予定**である。
- **profile v1 の正準化はまだ凍結していない** (Phase 2)。凍結前の規則に対して
  「第三者が再実装できる」とは書かない。

---

## 6. 参照

- 計画: [`../design/v3.4.0-evidence-and-residuals-plan.md`](../design/v3.4.0-evidence-and-residuals-plan.md)
- 経路・mapper・anchor の棚卸し: [`../design/evidence-phase0-inventory.md`](../design/evidence-phase0-inventory.md)
- SIP の検証規則 (正典): [`../design/p3-1-eark-sip.md`](../design/p3-1-eark-sip.md) §5
- 引き渡しの正典: [`../design/p3-4-custody-transfer.md`](../design/p3-4-custody-transfer.md)
