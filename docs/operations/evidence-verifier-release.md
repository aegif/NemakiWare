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
| **SBOM** | `cyclonedx-maven-plugin` が**このマシンのローカルリポジトリに無い**ため、オフラインでは配線できない。ネットワークのある環境で `org.cyclonedx:cyclonedx-maven-plugin` を追加して `makeAggregateBom` を回す |
| **detached signature** | **鍵は持っていない。** 署名はリリース担当者が自分の鍵で行う作業で、自動化してはならない（鍵を CI に置くことと同義になる） |
| **result schema** | CLI の `--json` 出力の JSON Schema。Phase 7 の段階レポートと同じ語彙にするため、そこで書く |

---

## 手順

### 1. ビルド

```bash
mvn -q install -f evidence-verifier-core/pom.xml
mvn -q package  -f evidence-verifier-cli/pom.xml
```

### 2. 動作確認（成果物を信じる前に）

```bash
java -cp evidence-verifier-cli/target/classes:evidence-verifier-core/target/classes:$(ls ~/.m2/repository/org/bouncycastle/bcpkix-jdk18on/1.85/bcpkix-jdk18on-1.85.jar) \
  jp.aegif.nemaki.verifier.cli.Verify verify <sip.zip> --profile RECORD_LEDGER_V1
```

**exit code を見ること。** `0` は `VERIFIED` だけで、`3`（`INDETERMINATE`）は成功ではない。

### 3. SHA-256SUMS

```bash
cd evidence-verifier-cli/target && shasum -a 256 *.jar > SHA-256SUMS
```

### 4. 署名（リリース担当者の作業）

```bash
gpg --armor --detach-sign --output SHA-256SUMS.asc SHA-256SUMS
```

**鍵を CI に置かないこと。** 自動化できないことが署名の値打ちである。

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
| `requireRevocationAtIssuance` | **省略時 true** | false にすると、材料が無くても `REVOCATION_NOT_REQUIRED` として先へ進む。**弱くする側の既定を沈黙で作らない**ためこうしてある |

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

jar、`SHA-256SUMS`、`SHA-256SUMS.asc`、profile spec、vectors。

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
