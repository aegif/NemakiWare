# 設計 — 発行時の失効材料の収集を、ガードを通してから有効にする（R65）

2026-09-21。残件 R65（[`fail-closed-reads.md`](fail-closed-reads.md) §4）。
運用文書の現状は [`v3.4.0-upgrade-runbook.md`](../operations/v3.4.0-upgrade-runbook.md) §O5-2。

**これは設計であって実装ではない。順序が本体である: ガードが先、つまみが後。逆順は禁止。**

---

## 0. 何が問題か（コードで確かめた事実）

| 事実 | どこ |
|---|---|
| `setCollectRevocationAtIssuance` は**宣言しか無く、呼び出し側が無い**。properties キーも管理 API も無く、bean を組む `AnchorWiringConfig` も呼ばない | `Rfc3161AnchorTarget.java:295`、`AnchorWiringConfig.java:104` |
| したがってどの配備でも `NOT_ATTEMPTED`。「既定 off」ではなく**到達不能** | 同上 |
| 収集経路は **TSA 証明書の** CRL distribution point の URL へ、**素の `HttpClient`** で出る | `Rfc3161AnchorTarget.java:331-336` |
| この製品の他の外向きは全部 `SsrfGuard` を通している | `HttpWebhookDispatcher` / `AdapterHttpClient` / `IntegrationSettingsController` / setup の `UrlValidator` |
| `SsrfGuard.assertOutboundUrlAllowed(url, enforce, what)` は**検査時に 1 回解決するだけ**で、`enforce` は運用者の opt-in（既定 no-op） | `SsrfGuard.java:283` |
| `AdapterHttpClient.pinRequestToValidatedAddress` は**送信時に再解決**し、HTTP なら検証済み IP リテラルに書き換えて `Host` を保つ。HTTPS は TLS 境界まで（残余リスク明記済み） | `AdapterHttpClient.java:281`（**package-private**） |

**今 live でないのは、つまみが無いおかげである。** つまみを先に配線した瞬間、
攻撃者が影響できる URL（TSA 証明書の中身）へ無検査で接続する経路が出荷される。

---

## 1. 決めたこと

### 1.1 順序

1. **ガード**: 収集経路を送信時固定の経路に載せ替える（1.2）。錠と control で測る。
2. **つまみ**: 1 が錠で守られてから、設定キーを配線する（1.3）。
3. 運用文書 §O5-2 を「有効にできる」に戻すのは **2 の後**。

**この順序を錠にする**: `AnchorWiringConfig` が `setCollectRevocationAtIssuance` を呼ぶ行が
在るなら、`collectRevocationMaterial` が固定送信の経路を呼んでいなければならない
（片方だけの木を CI が拒否する）。

### 1.2 ガード — 一発の検査ではなく、送信時固定

`assertOutboundUrlAllowed` を 1 回呼ぶ形は採らない。検査と接続の間に DNS が
書き換わる窓（R53 と同型）が残るし、`enforce` が既定 no-op のままでは
「ガードを通した」が偽になる。

- `AdapterHttpClient.pinRequestToValidatedAddress` を **public** にするか、
  同じ実装を `security` 側の共有 helper に**移して**両方から呼ぶ（複製はしない —
  `SsrfGuard.isAddressSafe` を複製から共有に直した履歴が同じファイルに残っている）
- **`enforce` は無条件**。TSA 証明書の URL は運用者の入力ではない。運用者が「自分の
  ネットワークでは要らない」と判断できる対象ではない
- HTTPS の残余（TCP-connect SSRF の窓）は `AdapterHttpClient` の javadoc が既に述べている
  とおり。**この設計で新たに閉じるとは言わない**。CRL の distribution point はほぼ `http://`
  だが、`https://` なら同じ残余を負う。運用文書に書く

### 1.3 つまみ — 既存の pattern に揃える

`AnchorWiringConfig` の `@Value("${anchor.rfc3161.*}")` に倣い、
**`anchor.rfc3161.revocation.collect-at-issuance`（既定 `false`）**。
管理 API は作らない — anchor の他の設定と同じく、配備時の設定であって実行時の切替ではない。

### 1.4 資源上限

CRL は数 MB になり得る。**上限を持たない取得は `RESOURCE_LIMIT` の欠陥**（verifier 側で
既に採っている規律）。上限（例 8 MiB）を超えたら `UNAVAILABLE`（`CRL_TOO_LARGE`）で、
切り詰めた材料を `CAPTURED` にしない。

### 1.5 3 値は変えない

`NOT_ATTEMPTED` / `UNAVAILABLE` / `CAPTURED` はそのまま。変わるのは
「`NOT_ATTEMPTED` しか出ない」状態が終わることだけ。`UNAVAILABLE` が
**運用者が直す問題**になるのは、つまみが在る版から。

---

## 2. 決めていないこと

| 未決 | なぜここで決めないか |
|---|---|
| OCSP も取るか | 「発行時の材料」として CRL と OCSP は等価ではない（OCSP は問い合わせ時刻の答え）。別の設計 |
| 上限の値 | 実在する TSA の CRL サイズを測ってから |
| CRL の**検証**（署名・有効期間） | この版の verifier は材料を「評価しない」と明言している（`REVOCATION_NOT_PARSED`）。収集と評価は別の Phase |

---

## 3. 錠と control

- **順序の錠**（1.1）: つまみの配線行と固定送信の呼び出しが**同時に**在るか、**同時に**無い
- **`enforce` の錠**: 収集経路が `enforce=false` で呼べない（呼び出しに定数 `true` を要求するか、
  引数の無い overload だけを公開する）
- **上限の錠**: 上限超えが `UNAVAILABLE` になり、`CAPTURED` にならない
- **control**: 送信時固定を外して一発検査に戻す細工／`enforce` を false にする細工／
  上限を外す細工。いずれも「ガードを通した」という文書の主張がコードより強くなる方向

---

## 4. この設計が主張しないこと

- HTTPS の TCP-connect SSRF が閉じるとは言わない（既存の残余と同じ）
- 集めた材料が**正しい** CRL であるとは言わない（評価は別 Phase）
- つまみを on にすれば P3 が `VERIFIED` に届くとは言わない — verifier 側の
  `token revocation` は材料が在っても `REVOCATION_NOT_PARSED` で `UNAVAILABLE`
  （[`evidence-verifier-release.md`](../operations/evidence-verifier-release.md) の表）
