# Spring 7 閉鎖と Jackson 3 移行 — 計画

status: **v1 — 2026-08-06 起票。フルテスト all green を確認した状態から出発する:
Maven 全 suite 4,755 件 0 失敗 0 skip (TCK 38 テスト込み・実 CouchDB/Atlas IT 込み) /
QA 94/94 / Playwright 929 passed・0 failed・104 skipped (1 flaky は retry 通過)。**

## 0. 前提の訂正 — 「Spring 7 化」は既に済んでいる

v3.3 のリリース条件は「Spring Framework 7.0 へのアップグレード完了」と置かれていたが、
棚卸しの結果、**Framework 本体の 7.0 化はこのブランチでも master でも完了済み**である
(7.0.8。WAR 内の全 spring jar が 7.0.8、Spring 7 で削除された API の使用ゼロ、
jakarta 移行漏れゼロ、`RestTemplate` 使用ゼロ)。

したがって本計画は「アップグレード計画」ではなく、次の 2 部construction からなる:

- **Part A — Spring 7 の完了宣言に必要な閉鎖項目** (小粒・v3.3 リリースゲート)
- **Part B — Jackson 2 → 3 移行** (Java 側で残る唯一の破壊的メジャー。v3.3 を block しない)

推奨: **v3.3 のリリースゲートは Part A 完了 + フルテスト green とし、Part B は
次リリース (v3.4) の主題に置く**。Spring 7 は Jackson 2 統合を deprecated にしたが
削除はしていないので、v3.3 を Jackson 3 に blocking させる技術的必然は無い。

## 1. 棚卸し (2026-08-06 実測)

| 領域 | 現在 | 判定 |
|---|---|---|
| Spring Framework | 7.0.8 (全モジュール同版) | ✅ 完了 |
| Jersey / Jakarta REST | 4.0.0 | ✅ 完了 |
| Solr server / SolrJ | 10.0.0 | ✅ 完了 |
| Tomcat / Java | 11.0 / JDK 21 | ✅ 完了 |
| JUnit / Mockito | 6.1.2 / 5.22 | ✅ 完了 |
| CXF | 4.2.0 | ✅ 現行メジャー |
| SLF4J / Logback | 2.0.18 / 1.5.38 | ✅ 現行 |
| React / antd / Vite | 19 / 6.5 / 7.2 | ✅ 現行 |
| **Jackson** | **2.22.1** | **← 唯一の残メジャー (3.x GA 済み)** |
| HK2 | 4.0.0-M3 (Jersey 4.0.0 同梱) | 上流 GA 待ち |
| OpenCMIS | 1.1.0-nemakiware | 意図的凍結 (方針) |
| Keycloak / openldap (test) | 24.0 / 1.5.0 | テスト基盤のみ、任意 |
| Atlas (統合先) | 2.3.0 | 任意 |
| CouchDB / TEI (image) | 3.3.3 / cpu-1.6 | マイナー追従のみ |

## 2. Part A — Spring 7 閉鎖項目 (v3.3 ゲート)

| # | 項目 | 内容 | 規模 |
|---|---|---|---|
| A-1 | Spring モジュール版の enforcer 化 | spring-tx が 7.0.4 で transitive 混入した事故が pom コメントに記録されている。maven-enforcer (`requireUpperBoundDeps` または dependencyConvergence を org.springframework に限定) で「全 Spring モジュール同一版」を機械的に強制し、手動 align の再発を防ぐ | 小 |
| A-2 | Spring 7 挙動変更の検証記録 | 削除 API の使用ゼロは確認済み。挙動変更 (PathPattern 経路解釈・HttpHeaders・null-safety) について「確認した」ことを本文書に記録し、以後は full suite green を回帰の根拠とする | 記録のみ |
| A-3 | JSpecify null-safety の採否決定 | Spring 7 は JSpecify を採用。自コードへの導入は**任意**。採否だけ決めて記録する (推奨: v3.3 では見送り、新規コードから漸進) | 決定のみ |
| A-4 | resilience 機能 (@Retryable 等) の採否決定 | 既存の retry は PurviewHttpRetryHandler 等の自前実装で、budget 契約 (worstCaseBackoffTotalMs) と結合している。Spring の @Retryable へ置換すると budget が読めなくなるため**採用しない**ことをここに記録する | 決定のみ |
| A-5 | HK2 4.0.0 GA 追従 | Jersey 4.0.x が GA の HK2 を取り込んだ版を出したら追従。watch 項目 | 待ち |
| A-6 | 化石フラグ掃除 | docker/core/Dockerfile の `-Dlog4j.configuration` (log4j 1.x 様式。実体は Logback で無効・無害) を削除 | 極小 |

## 3. Part B — Jackson 2 → 3 移行 (v3.4 主題の提案)

### 3.1 事実関係

- Jackson 3 は groupId/namespace が `tools.jackson` に変わる。**annotations は
  `com.fasterxml.jackson.annotation` のまま** (jackson-annotations 3 系も同パッケージ) —
  つまり **annotation しか使わないコードは無変更**。
- 2 系と 3 系は**classpath 共存可能** (名前空間が違うため)。段階移行が成立する。
- Spring Framework 7 は Jackson 3 を一級サポートし Jackson 2 統合を deprecated にした。
  削除は 7.x の将来版以降。

### 3.2 実測した移行面積 (2026-08-06)

| 対象 | 件数 |
|---|---|
| com.fasterxml.jackson を import するファイル | 135 |
| うち annotations のみ (**無変更**) | 69 |
| うち databind/core API 使用 (**移行対象**) | 72 |
| ObjectMapper 使用ファイル | 82 |
| Spring XML 設定 | spring-mvc-context.xml の ObjectMapper bean、jacksonContext.xml |

### 3.3 推移依存の現実 (2 系が残る理由)

Cloudant SDK・SolrJ・CXF databinding・logstash-logback-encoder・OpenCMIS fork は
Jackson 2 系を要求する。これらが 3 対応するまで 2 系 jar は WAR に残る。
**着地形は「自コード + Spring MVC 統合を 3 へ、推移依存は 2 のまま共存」**であり、
「2 を消す」ことを目標にしない (できない)。

### 3.4 段階計画

| Phase | 内容 | 完了条件 |
|---|---|---|
| B-0 調査 | 依存ライブラリ各版の Jackson 3 対応状況を確定。**シリアライズ挙動差の棚卸し** (Jackson 3 の default 変更: FAIL_ON_UNKNOWN 系・JSR310・null 扱い) を、CouchDB 文書 decode 経路に対して洗う | 対応表 + 挙動差リスト |
| B-1 golden 固定 | 移行**前**に、CouchDB 永続文書 (lineage v2・obligation・barrier・cursor 等 CAS/identity に触れる形式) の serialize/deserialize golden テストを 2 系で固定する。LineageCanonicalHash は Jackson 非依存 (自前実装・golden vectors 固定済み) だが、**文書 codec は Jackson 経由**なので、挙動差が identity を動かさないことをここで担保する | golden green |
| B-2 自コード移行 | 72 ファイルの `com.fasterxml.jackson.(databind\|core\|…)` → `tools.jackson`、ObjectMapper → JsonMapper。Spring MVC converter を Jackson 3 系へ。B-1 の golden を 3 系で再実行 | full suite + golden green |
| B-3 縮退 | 2 系への直接依存を pom から排除し、enforcer で直接依存を禁止 (推移のみ許容)。WAR 内 2 系 jar は推移由来のみに | enforcer green |
| B-4 検証 | full suite + TCK + QA + Playwright + 実 CouchDB/Atlas IT。4b barrier 環境での lineage end-to-end 再走 | all green |

### 3.5 リスクと拒否事項

- **CouchDB 文書の識別子・CAS に触れる挙動差が最大リスク**。B-1 の golden を先に
  置かない移行は行わない (「テストが通ったから同じ」ではなく「同じ byte 列が出るから同じ」)。
- Jersey 側 provider (jackson-jakarta-rs-json-provider) の 3 系対応が無い場合、
  REST 層は 2 のまま残す。**混在は許容**、無理な一斉置換はしない。
- 移行中に 2/3 両系へ同じ設定を「二重に」持つ期間が生じる。設定 drift を防ぐため、
  共通設定は 1 箇所の factory に集約してから二重化する。

## 3.9 all-green 確保の過程で判明した所見 (参考)

Phase 1 の検証自体が製品とテスト基盤の欠陥を掘り当てた。Jackson 移行とは独立だが、
この計画の検証段 (B-4) でも同じ罠を踏むため記録しておく:

1. **型更新が propertyDefinitionDetail を leak する** (backlog chip 起票済み)。
   updateTypeDefinition は新文書を保存するだけで、properties から外れた旧 detail を
   削除しない。TCK/E2E の型チャーンで bedroom/canopy に各 ~5,000 件の孤児が蓄積し、
   type/list (全 detail をロードする) が 5.6 秒まで劣化 → E2E の networkidle 待ちを
   間欠的に破っていた。掃除で 0.32 秒に回復。
2. **/archive/index の既定応答が無界** (backlog chip 起票済み)。テスト残渣 68,606 件の
   蓄積で 29.5MB / 19 秒になり、QA script の curl --max-time 10 を殺していた。
3. **E2E の degradation 分岐は「入った瞬間落ちる assert」を持ってはならない**。
   tab 不在を検出した分岐がその tab の visible を assert していた (修正済み)。
4. **輻輳下の検索テストは受動待ちではなく再送信で耐える** (修正済み)。fixture が
   索引済みでも、負荷窓に単発クエリを撃つ設計は非決定になる。

## 4. 実施順序

1. Part A (A-1, A-6 は即日規模。A-2〜A-4 は記録・決定のみ) → **v3.3 リリースゲート充足**
2. B-0 調査 → GO/NO-GO 判断 (対応表が悪ければ B は延期し、判断を本文書に記録)
3. B-1 → B-2 → B-3 → B-4

## 5. このブランチの運用

- ブランチ: `deps/spring7-closure-jackson3` (master 派生ではなく `test/v3.3-arm64-full` 派生 —
  v3.3 の全成果の上で作業する)
- Part A のコミットは v3.3 へ merge 可能な粒度で切る (リリースゲート項目のため)
- Part B は本ブランチ内で B-1 golden が green になるまで v3.3 系へ merge しない
