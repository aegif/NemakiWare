# 独立 verifier のリリース手順（Phase 5）

受け取る組織が動かすのは `evidence-verifier-cli` である。**彼らは NemakiWare を持っていない**
という前提が成果物の形を決める。

計画 §10 が要求する成果物: jar、SHA-256SUMS、detached signature、SBOM、result schema、
profile spec、vectors。

---

## 今できていること

| 成果物 | 状態 |
|---|---|
| jar（core / cli） | `mvn package` で出る。**依存は BouncyCastle のみ**（core / Spring / CouchDB / CMIS / Jackson はゼロ、錠が pom と import を読んで測る） |
| profile spec | [`evidence-profile-v1.md`](../design/evidence-profile-v1.md) |
| vectors | [`docs/evidence-profile/v1/vectors/`](../evidence-profile/v1/vectors/)。**3 実装が同じ 1 ファイルを読む** |
| SHA-256SUMS | 下の手順で作る |

## まだできていないこと（残件。成功扱いにしない）

| 成果物 | なぜ |
|---|---|
| **SBOM**（タグのもの） | ~~`cyclonedx-maven-plugin` が**このマシンのローカルリポジトリに無い**ため、オフラインでは配線できない。~~ **2026-09-23 から `tools/sbom/make-sbom.sh` で作れる**（Maven の全 module を aggregate した CycloneDX 1.6 — verifier の 2 module も入る — と、UI の npm 依存）。**ネットワークが要る** — plugin と `@cyclonedx/cyclonedx-npm` を取り寄せるまでは、ネットワークの無い機械では毎回落ちる。版を固定してあるのは Maven の plugin（2.9.3）だけで、npm 側の道具は取り寄せたときの版になる（09-23 の 561 component と比べるなら道具の版も記録する）。**タグの SBOM はまだ無い** — この作業コピーで出したものは今の HEAD の依存であって、タグの依存ではない。リリース時に git worktree で切った tagged tree から作る。script は署名しない（下の detached signature） |
| **detached signature** | ~~**鍵は持っていない。**~~ **3.4.0 からリリース担当者の鍵で署名する**（2026-10-07）。鍵は RSA 4096、fingerprint `DEE5 2327 1849 9934 2FC7  C689 C321 AD4E D1A2 0918`。署名は自動化しない（方針。鍵を CI に置かない）。**3.4.0 の署名はリリース担当者の開発機で行う** — その機械で担当者と同じ OS ユーザーとして動くもの全部を信頼することが、この署名の前提になる（手順 4）。**残件**: 署名専用の機械かハードウェアの鍵での署名、GitHub の外での鍵の公開、手順 4 の書き漏れ 2 件（同じ OS ユーザーで動くものに、外部につながる Codex の exec-server と cursor-agent がある／この機械の `commit.gpgsign=true` は、どのリポジトリの commit もこの鍵で署名させる — 2026-10-07 の確認 5 巡目で見つけ、止め方に従い手順 4 は直していない） |
| ~~**result schema**~~ | **書いた（2026-09-22）**: `docs/evidence-profile/v1/verifier-result.schema.json`。閉じた schema、`reasonCode` は登録簿 `Outcome.Check.REASON_CODES`（27 値）と両方向で一致、`limits` 必須。**verifier は schema を読まない** — 受け取る側が自分の validator で検証する。`SHA-256SUMS` に載せる（下記） |

---

## 手順

### 1. ビルド

```bash
mvn -q install -f evidence-verifier-core/pom.xml
mvn -q package  -f evidence-verifier-cli/pom.xml
```

### 2. 動作確認（成果物を信じる前に）

```bash
java -jar evidence-verifier-cli/target/evidence-verifier-cli-3.4.0.jar verify <sip.zip> --profile RECORD_LEDGER_V1
```

`package` が `target/lib/` に 4 つの jar（`evidence-verifier-core` と BouncyCastle 1.85 の `bcpkix` /
`bcprov` / `bcutil`）を複写し、CLI の jar の manifest がそこを `Class-Path` で指す。**配布物は jar と
`lib/` の組**で、同じディレクトリに置けば `java -jar` で動く（2026-10-06 に実測: golden の
`product-sip-v1-section.zip` を `RECORD_LEDGER_V1` で exit 0）。shaded jar にはしない —
BouncyCastle の jar を Maven Central の checksum と照合できるままにする。それまで RELEASE_NOTES は
`java -jar evidence-verifier-cli.jar` と書き、ビルドはそう動く jar を作っていなかった（9-6 の独立レビュー、P1）。

**exit code を見ること。** `0` は `VERIFIED` だけで、`3`（`INDETERMINATE`）は成功ではない。

**遷移文の package**（`ledger-entry.json` の `subjectKind` が `RECORD_CONTENT_TRANSITION` — 内容が archive へ移った・cold へ移った・
消された版）は **P1 以上で `VERIFIED` に届かない**（`content binding` が `NOT_PRESENT`）。**この版の NemakiWare はそういう package を
書き出さない**（書き出しが payload を要求する）— 読む側の備えであって、この製品が出すものの説明ではない。`transition continuity` は
写した prior を `prior/` の出所と突き合わせ、`TRANSITION_PRIOR_NOT_IN_PACKAGE` は「出所を運んでいない」であって「写しが誤り」ではない。

### 3. SHA-256SUMS

```bash
cd evidence-verifier-cli/target && cp ../../docs/evidence-profile/v1/verifier-result.schema.json . && shasum -a 256 evidence-verifier-cli-3.4.0.jar lib/*.jar verifier-result.schema.json > SHA-256SUMS
```

### 4. 署名（リリース担当者の作業）

```bash
cat SHA-256SUMS && shasum -a 256 -c SHA-256SUMS
gpg --armor --detach-sign --local-user DEE52327184999342FC7C689C321AD4ED1A20918 --output SHA-256SUMS.asc SHA-256SUMS
gpg --verify SHA-256SUMS.asc SHA-256SUMS
gpg --armor --export DEE52327184999342FC7C689C321AD4ED1A20918 > nemakiware-release-key.asc
```

**この署名が言えるのは、「fingerprint `DEE5…0918` の鍵を使える環境が、この `SHA-256SUMS` に署名した」まで**
である。誰が・どの機械で、の部分は次の事実に依る（3.4.0、2026-10-07）:

- 署名のコマンドはリリース担当者の開発機で打つ。passphrase を求められたら担当者が pinentry に入力する（gpg-agent が
  覚えていれば求められない — 下記）。pinentry は**何に**署名するかを示さない
- その機械では、担当者と同じ OS ユーザーで、agent（この版の作業では Claude Code と Codex）とビルドの道具（Maven・
  WAR のビルドの `npm install`・SBOM の `npx`）が動き、鍵もそのユーザーの `~/.gnupg` にある。gpg と pinentry-mac は
  同じユーザーが書き換えられる `/opt/homebrew` にある
- gpg-agent は passphrase を覚える（既定で 10 分、使うたびに延びて最長 2 時間）。pinentry-mac は「キーチェーンに保存」を
  出し（gpg-agent.conf に `no-allow-external-cache` が無い間）、保存すれば期限なく覚える。覚えている間は、同じユーザーで
  動くものが prompt なしに署名できる。**この機械の `~/.gitconfig` はこの鍵を git の commit の署名鍵にもしている**ので、
  署名付きの commit も passphrase を覚えさせる（このブランチの commit は署名していない）
- 同じユーザーで動くものは、gpg・pinentry・gpg-agent の設定そのものも書き換えられる。gpg-agent.conf の
  `ignore-cache-for-signing` と `no-allow-external-cache` は、cache を経由した署名を prompt に変えるが、同じユーザーの
  process はこの設定ごと外せる

したがって、この署名を信頼することは、その機械で同じユーザーとして動いていたもの全部を信頼することでもあり、
**この署名は、人の手で出たことの保証ではない**。署名の後に担当者が `gpg --verify` と `SHA-256SUMS` の中身を確かめる。
**より強い保証が要る版では、鍵を作るところから、署名専用の機械かハードウェアの鍵で行う**（残件）。

**鍵を CI に置かないこと。** 署名は自動化しない（方針）。

公開鍵（`nemakiware-release-key.asc`）を Release に添え、**fingerprint は Release の本文とこの文書に書く**。
受け取る側は、添付の鍵を信頼する前に、**Release の添付物とは別の経路で得た** fingerprint と照合する
（同じ Release のページの本文と照合しても、鍵と本文を一緒に差し替えたものには一致してしまう）。
この文書を **GitHub の aegif/NemakiWare から読んで** fingerprint を得ていれば、ミラーから取った配布物の鍵の差し替えも、
添付物だけの差し替えも、照合で見つけられる。見つけられないのは、この文書も一緒に差し替わる場合（この文書をミラーから
読んだとき、リポジトリに書ける者が文書と添付物を両方変えたとき）。照合は、したときだけ効く検出である。
GitHub の外の経路（鍵サーバ・組織の Web など）での公開は、まだしていない（残件）。

---

## trust profile のファイル形式

verifier に `--trust-profile <file>` で渡す。**package の中からは絶対に来ない** —
package が自分の trust anchor を名乗れるなら、署名を差し替えた package は差し替え先の
発行者を名乗って通る。

```json
{
  "anchors": ["<PEM または base64 DER>", "..."],
  "policyOids": ["1.2.3.4", "..."],
  "requireRevocationAtIssuance": true
}
```

| 欄 | 既定 | 意味 |
|---|---|---|
| `anchors` | **必須**。空だと**ファイルごと拒否**される | PKIX path の終点。ここに無い発行者の token は `token pkix` が FAILED |
| `policyOids` | 省略可 | 省略すると `token policy` は `NOT_PRESENT`（照合する基準が無いので、通ったとは言わない） |
| `requireRevocationAtIssuance` | **省略時 true** | false にすると理由が `REVOCATION_NOT_CAPTURED` から `REVOCATION_NOT_REQUIRED` に変わる。**verdict は変わらない**（下記）。**弱くする側の既定を沈黙で作らない**ためこうしてある |

### この版で `VERIFIED` に到達できるのは **P0・P1・P2** です

profile は**積み上げ**で、上位は下位の必須検査を全部含みます。そして
**必須検査のうち 3 つは、この版では `PASSED` になる分岐を持っていません**
（`anchor commits root` は 2026-09-22 から RFC 3161 token を読み、root を commit していれば `PASSED`。
それまでは材料の有無しか見ず、P2 も必ず exit 3 だった）。

| 必須検査 | どの profile から入るか | なぜ `PASSED` が無いか |
|---|---|---|
| `token revocation` | `TRUSTED_RFC3161_V1` 以上 | 失効材料を**評価しない**。材料が在ることは、その検証ではない |
| `ots parse` / `ots commits root` / `ots attestation` | `ANCHORED_OTS_V1` | block header の入手元が無く、socket も開かない |

したがって:

| profile | この版の最良の結果 |
|---|---|
| `PACKAGE_INTEGRITY_V1` | **`VERIFIED` に到達できる**（exit 0） |
| `RECORD_LEDGER_V1` | **`VERIFIED` に到達できる**（exit 0） |
| `ANCHORED_CHECKPOINT_V1` | **`VERIFIED` に到達できる**（exit 0）— RFC 3161 token が anchor target の root を commit しているとき。OTS / ERS / Atlas の材料しか無い package は exit 3（この profile はそれらを読まない） |
| `TRUSTED_RFC3161_V1` | **必ず exit 3** |
| `ANCHORED_OTS_V1` | **必ず exit 3** |
| `LONG_TERM_ERS_V1` | **必ず exit 3** |

**`requireRevocationAtIssuance: false` を渡しても exit 0 にはなりません。**
変わるのは理由コードだけです。**node 側で収集を on にしても同じ**（`anchor.rfc3161.revocation.collect-at-issuance`）— 集めた材料をこの版は評価しない。

**exit 3 を「実質 OK」として script で畳まないでください。** 畳んだ時点で、
この版が積み上げた拒否は最後の一歩で全部無効になります。**P3 以上を合否判定に
使う運用は、この版では成立しません** — 使えるのは P0〜P2 です。P2 の `VERIFIED` が言うのは
「誰かが発行した RFC 3161 token が root を commit している」までで、**その誰かを信頼してよいかは P3 の問い**（P3 は届かない）。
上位 profile の出力は、**どこまで確かめられたかの内訳**としてのみ読んでください。

**anchor が 1 つも無い profile ファイルは拒否される。** 空の profile を返すと
「渡さなかった」場合とまったく同じに振る舞い、それを verdict から気づくことになる。

**認定の有無をこのファイルから読み取らない。** anchor に置いた証明書が認定事業者のもので
あることは、契約と登録簿の事実であって、ファイルに書いたから真になるものではない。

## ERS の更新（renewal）

**この版は評価するだけで、更新しない。** `LongTermValidityService` が「何が古びつつあり、
どちらの renewal が要るか」を答える（dry-run 側）。**明示実行は入っていない**。

理由は 2 つある。

- hash-tree renewal は**保管済みオブジェクトを全部読む**。いつ走らせるかは運用の判断で、
  製品が決めると組織のコストを製品が決めることになる
- timestamp renewal は **token の署名アルゴリズム**が弱るときに発火するが、
  どの rung もそれを記録していないので、**この版は署名由来の renewal を評価していない**
  （評価結果にもそう書いてある）

**自動更新は入れない。** 入れるときは dry-run と明示実行を分け、明示実行には
どの記録を対象にするかを運用者が選ぶ手順を付ける。

## 受け取る側に渡すもの

jar（と `lib/` の 4 つ）、`SHA-256SUMS`、`SHA-256SUMS.asc`、署名の公開鍵（`nemakiware-release-key.asc` — fingerprint は上の表）、profile spec、vectors、**result schema**（`verifier-result.schema.json` — `SHA-256SUMS` が参照するので、無いと照合できない）。

**verifier のダウンロード URL を package に埋め込まない**（計画 §10）。package が
「これで私を検証してください」と指す先を自分で名乗れるなら、差し替えた package は
差し替えた verifier を指す。

## 受け取る側に言ってよいこと

CLI が `VERIFIED` を出したとき言えるのは、**その profile の必須検査が全部通った**ことだけである。
限界文は CLI が毎回印字する（成功時も、JSON 出力にも）。特に:

- 取込前の内容が真実であること、漏れなく取り込まれたこと、提示 checkpoint が最新であること、
  管理者が全部を作れなかったこと — **どれも言えない**
- 独立性は外部 anchor が与えるもので、`ANCHORED_CHECKPOINT_V1` 以上を、**verifier が選んだ
  trust profile と一緒に**通したときに初めて意味を持つ
