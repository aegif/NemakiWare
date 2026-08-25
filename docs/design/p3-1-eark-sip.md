# P3-1 — E-ARK SIP エクスポート

作成 2026-08-25。ロードマップ §4 Phase 3 の 1 番目。
前提は P1-1 (捕獲の来歴) / P1-3 (証拠台帳) / P2-0〜2-2 (アンカー)。

---

## 0. 何を主張し、何を主張しないか

**主張する** (第 1 段の時点で): **CSIP 2.2.0 のリファレンス検証器を通る SIP を
このビルドで生成できる**。検証は CI のユニットテストで、外部プロセス無しで回る。

**主張しない**:

- **E-ARK 準拠とは名乗らない。** 通ったのは `commons-ip2` に同梱された検証器であって、
  DILCIS Board の公式サービスでも、受入側 (RODA / Archivematica) の実機受入でもない。
  版・profile・検証器の版を固定して**「この版のこの検証器を通った」とだけ言う**
- **「記録の真正性が移送先で保たれる」とは言わない。** 第 1 段が示したのは**容器が
  整っている**ことだけで、NemakiWare 側のメタデータは 1 バイトも載っていない
- **AIP / DIP は作らない** (ロードマップ §3.1 の「軽量 Archive」責務を決めてから)

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

### 実測した footprint

推移依存 27 個のうち **17 個は同一〜互換版が既に WAR に入っていた**
(logback / slf4j / commons-lang3 3.20.0 / commons-io / commons-beanutils 1.11.0 /
commons-collections 3.2.2 / jdom2 2.0.6.1 / jackson 2.x / joda-time 2.14.0 /
picocli / jakarta.xml.bind / jaxb-runtime / angus-activation / commons-text 1.15.0)。

**新規は 6 個**: `gov.loc:bagit` / `commons-configuration2` / `jdom2` / `picocli` /
`jackson-datatype-threetenbp` / `threetenbp`。

**除外が 2 個** (`<exclusions>`):

| 除外 | 理由 |
|---|---|
| `javax.activation:activation:1.1.1` | **Jakarta EE 10 の WAR に javax が混ざる**。`jakarta.activation-api` + `angus-activation` が既に同じ役目を果たしている |
| `commons-logging` | 既に `jcl-over-slf4j` が入っており、**1 クラスパスに JCL が 2 つ**は典型的なログ分裂 |

解決後に重複バージョンが出ていないことを確認済み (`jackson-databind` の 2.x/3.x
併存はこの変更の前からある)。残る `commons-logging` は **spring-core の `provided`** で、
WAR には入らない。

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

## 3. 次の段 (未着手)

1. **NemakiWare の記録を 1 件 SIP にする** — 本文 + `nemaki:*` の identity 属性。
   ここで初めて「記録が移送できる」に近づく
2. **journal イベント → PREMIS イベントのクロスウォーク表**。語彙を確定してから書く。
   ロードマップ §4 Phase 3 が要求している成果物
3. **evidence package (`.ots` / TSA トークン / inclusion proof) の CSIP 上の置き場所**。
   正位置は仕様で要確認 (ロードマップも「着手時に要確認」と書いている)
4. **RODA 実機受入試験** — `docker/docker-compose-roda.yml` は arm64 で動くことを
   確認済み。ただし**受入 profile / 版の対応表は未確認**なので、通ることは前提にしない
