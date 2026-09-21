# 設計 — 発行時の失効材料の収集を、ガードを通してから有効にする（R65）

2026-09-21。残件 R65（[`fail-closed-reads.md`](fail-closed-reads.md) §4）。
運用文書の現状は [`v3.4.0-upgrade-runbook.md`](../operations/v3.4.0-upgrade-runbook.md) §O5-2。

**2026-09-22 に実装した — 順序どおり（固定送信 → 錠 → つまみ）。** 所有者の決定（2026-09-21）: OCSP はこのバッチに入れない、CRL の検証もしない、上限は 8 MiB で始める、既定 `false`、on にしても P3 は `VERIFIED` に届かないと運用文書に残す、HTTPS の TCP-connect 窓は閉じたと書かない — すべて写した。

**2 巡目（2026-09-22、Codex + subagent、双方 NOT CONVERGED）で直したもの。** 両者が独立に同じ 2 点を指摘した: (a) **本文の読みに時間の上限が無かった** — `HttpRequest.timeout` はヘッダまでで、`ofInputStream` の後の `readNBytes` はどの timer にも掛かっていない。同じ木の `SubmittedDigestRecovery` に `BodyBudget`（watchdog が stream を閉じる）が既に在ったので **`jp.aegif.nemaki.rest.ingest.BodyBudget` に移して共有**した（複製しない）。(b) **テスト用プロパティ `nemaki.ingest.allowLocalhost=true` がガード全体を外していた** — 名前は localhost だが、検証も固定も丸ごと素通りしていた。**loopback だけを通す**ように狭めた（他の宛先は on でも検証・固定する）。加えて subagent から: 読みの上限（`readNBytes`）は錠が測っていなかった、`sendWithRetry` の 429/503 待ち（最大 120 s × 3）が管理 API の thread を止める、HTTP 固定分岐は JVM flag `jdk.httpclient.allowRestrictedHeaders=host` が前提で suite のどのテストも通していなかった、この文書の §1.2 / §3 が実装と食い違っていた。すべて下に写した。

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
| **2 巡目で判明**: 同メソッドは `nemaki.ingest.allowLocalhost=true`（JVM `-D`）で**解決の前に return** していた — 検証も固定も無し、宛先を問わず。`validateExternalUrl` も同じ | `AdapterHttpClient.java`（旧 :294 / :201） |
| **2 巡目で判明**: `ofInputStream()` の後の本文読みには timer が無い。`SubmittedDigestRecovery` は同じ罠を Temurin 21 で実測し `BodyBudget` を持っていた | `SubmittedDigestRecovery.java`（旧 :484） |
| **2 巡目で判明**: HTTP 固定の `Host` 上書きは JDK flag `jdk.httpclient.allowRestrictedHeaders=host` が要る。`Utils.DISALLOWED_HEADERS_SET` はクラス初期化時に 1 回だけ計算され、`AtlasLineageSink` が起動時に最初の `HttpClient` を作るので、`AdapterHttpClient` の static init で足すのは非 Docker 配備では手遅れ | `AdapterHttpClient.java:62-73` |

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

- **実装（2 巡目で文書を実装に合わせた）**: `pinRequestToValidatedAddress` は package-private のまま。
  収集経路は **`AdapterHttpClient.sendPinned(client, request, handler)`** を呼ぶ — 固定を内包した
  public の入口で、**一回送信・リトライ無し**（`sendWithRetry` は 429/503 で `Retry-After` を最大 120 s × 3 回
  眠る。コネクタの poll には正しく、管理 API の要求 thread 上で走るこの取得には誤り）。public 化も
  helper への移動も不要になった（初版はそのどちらかを要求していた — 採らなかった方式を「現行」として
  残していたのが 2 巡目の P2）
- **`enforce` は無条件** — 正確には、採った経路に `enforce` 引数は**無い**。無条件性は `sendPinned` の
  構造そのもの（固定を経ずに送る形が無い）。**唯一の例外はテスト用 JVM プロパティ
  `nemaki.ingest.allowLocalhost=true` で、これは loopback を通すだけ**（2 巡目まではガード全体を外していた。
  `isAcceptable(InetAddress)` の 1 か所でだけ読まれ、loopback 以外は on でも検証・固定される。最初に通した
  送信で WARN）。TSA 証明書の URL は運用者の入力ではない。運用者が「自分のネットワークでは要らない」と
  判断できる対象ではない
- **前提**: HTTP 固定は JVM に `-Djdk.httpclient.allowRestrictedHeaders=host` が要る。Docker 配備は設定済み。
  素の Tomcat では on にすると `http://` の DP は毎回 `UNAVAILABLE`（`restricted header name: "Host"`）。
  運用文書 §O5-2 に前提として書いた。**この分岐は loopback stub では通せない**と初版は考えていたが、
  プロパティを loopback-only に狭めたことで stub でも固定を**通る**ようになり、`localhost` 名で束縛した stub に
  対して CAPTURED になることを実測した（`materialIsCapturedThroughThePinnedPath`）
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

**時間の上限も置く（2 巡目）。** `HttpRequest.timeout(20 s)` はヘッダ到着までしか覆わない。ヘッダの後、
本文は `readNBytes` で**この node が**読み、そこには timer が無い — 1 byte 送って止まる DP は anchoring と、
それを待つ管理 API の thread を JVM kill まで止める。`BodyBudget`（`CRL_BODY_BUDGET` = 20 秒、watchdog が
stream を閉じる。時計を見るループでは `read()` の中で止まった thread に届かない）を掛け、切れたら
`UNAVAILABLE`（`CRL_READ_TIMEOUT`）。

### 1.5 3 値は変えない

`NOT_ATTEMPTED` / `UNAVAILABLE` / `CAPTURED` はそのまま。変わるのは
「`NOT_ATTEMPTED` しか出ない」状態が終わることだけ。`UNAVAILABLE` が
**運用者が直す問題**になるのは、つまみが在る版から。

---

## 2. 決めていないこと

| 未決 | なぜここで決めないか |
|---|---|
| OCSP も取るか | **決定: このバッチに入れない** |
| 上限の値 | **決定: 8 MiB で始める**（`Rfc3161AnchorTarget.MAX_CRL_BYTES`、錠が運用文書の数と突き合わせる） |
| CRL の**検証**（署名・有効期間） | **決定: しない**（収集と評価は別） |

---

## 3. 錠と control

- **順序の錠**（1.1）`TheGuardComesBeforeTheToggleTest#theToggleIsNeverWiredAheadOfTheGuard`: つまみの配線行と
  固定送信の呼び出し（`sendPinned` / `sendWithRetry` / `sendWithRedirectValidation` のいずれか）が**同時に**在るか、
  **同時に**無い。本体に素の送信（`newHttpClient(` / `newBuilder(` / `.send(` / `.sendAsync(` /
  `HttpURLConnection` / `openConnection(` / `openStream(`）が無い。文字列・文字リテラルを潰してから brace-match
  （2 巡目: 初版は 2 語しか見ず、リテラル中の `}` で本体が切れた）。**ソース読みは「死んだ固定呼び出しを残す」
  細工を見抜けない** — それは挙動の錠が持つ
- **`enforce` の錠**: 該当なし — 経路に `enforce` が無い（初版はこの錠と control を「在る」と書いていた。2 巡目の
  P2）。代わりに **escape の錠** `TheTestEscapeIsLoopbackOnlyTest`: プロパティ on でも 10/8・192.168・172.16・
  169.254.169.254 は `validateExternalUrl` と `pinRequestToValidatedAddress` の両方が拒否し、loopback 名は
  固定を**通って**（URI が IP リテラル、`Host` が元の名前）受け入れられる。off なら loopback も拒否（control）
- **上限の錠** `theFetchIsBounded`: 定数 8 MiB / 20 秒、本体に `readNBytes((int) MAX_CRL_BYTES + 1)` と
  `new BodyBudget(` が在る、運用文書 §O5-2 の「上限 8 MiB」「本文の上限 20 秒」が定数と一致（2 巡目: 初版は
  定数を literal と比べるだけで、`readAllBytes()` に戻しても運用文書を 16 MiB にしても緑だった）
- **挙動の錠**（`Rfc3161AnchorTargetTest.RevocationCollection`）: `localhost` 名の stub から CAPTURED（固定分岐を
  通る）／8 MiB + 1 は `CRL_TOO_LARGE`／escape off で loopback は `SecurityException` → `UNAVAILABLE`／
  1 byte 送って止まる stub は 500 ms の予算で `CRL_READ_TIMEOUT`（`assertTimeoutPreemptively` — 戻らない取得は
  待つテストを落とさない）／503 は 1.5 秒以内に `UNAVAILABLE`（リトライ経路なら最初の眠りだけで 2 秒）
- **control**: LY3 つまみを残して素の client に戻す／LZ3 既定 `true`／MA3 上限検査を外す／MB3 運用文書が HTTPS の窓を
  「閉じた」と書く／**MC3 watchdog を外す**／**MD3 プロパティで固定を丸ごと素通りに戻す**／**ME3 method reference で
  送る（固定でも素でもない — 順序の錠のもう一方の腕）**／**MF3 `readAllBytes()`**／**MG3 運用文書 16 MiB**／
  **MH3 `sendWithRetry` に戻す**。いずれも「ガードを通した」「有界」という文書の主張がコードより強くなる方向

---

## 4. この設計が主張しないこと

- HTTPS の TCP-connect SSRF が閉じるとは言わない（既存の残余と同じ）
- 集めた材料が**正しい** CRL であるとは言わない（評価は別 Phase）
- つまみを on にすれば P3 が `VERIFIED` に届くとは言わない — verifier 側の
  `token revocation` は材料が在っても `REVOCATION_NOT_PARSED` で `UNAVAILABLE`
  （[`evidence-verifier-release.md`](../operations/evidence-verifier-release.md) の表）
- テスト用プロパティが**無い**とは言わない。`nemaki.ingest.allowLocalhost=true` は loopback を通す（それだけ）。
  JVM の `-D` なので本番でも立てられる。立てれば WARN が出る、運用文書に書いた、までで、立てられなくはしていない
- 順序の錠のソース読みが、固定呼び出しを**死んだまま残す**細工を見抜くとは言わない。それは挙動の錠（escape off で
  loopback が拒否される）の仕事で、挙動の錠は一発検査と固定を**区別しない**（どちらも `SecurityException`）。
  区別はソース読み（素の送信が無い）が持つ。2 つで 1 つの錠
