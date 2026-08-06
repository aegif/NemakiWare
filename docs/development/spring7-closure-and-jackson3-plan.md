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

**決定 (2026-08-06, オーナー判断): v3.3 のリリースゲートは Part A + Part B の両方とする。**
当初案は Part B を v3.4 送りにしていたが、Jackson 3 を上げた状態で 3.3 をリリースする。
ゲートは「A 完了 + B 完了 + フルテスト (E2E 込み) all green」。

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

### 2.1 実施結果 (2026-08-06)

**A-1 完了 — ただし当初案の rule では検出できないことが判明した。**
計画は `dependencyConvergence` を org.springframework に限定する案だったが、これは
**この事故を検出できない**。convergence が見るのは「同一 artifact が複数版に分岐したか」で
あり、spring-tx が単独で 7.0.4 に居座る (他モジュールは 7.0.8) 状態は、各 artifact が
それぞれ単一版に収束しているため **PASS してしまう** — 実際に rule を入れて確認済み。
採用したのは `bannedDependencies` で「org.springframework:* を全面禁止し、
`${org.springframework.version}` のみ許可」する形。spring-tx を 7.0.4 に戻す負のテストで
落ちること、戻せば通ることの両方を実測した (core/pom.xml)。

**A-2 記録** — Spring 7 の挙動変更 (PathPattern 経路解釈・HttpHeaders・null-safety) は
本ブランチのフルスイート green を回帰の根拠とする。削除 API の使用ゼロは棚卸し済み。

**A-3 決定 — JSpecify は v3.3 では導入しない。** 既存コードへの一括アノテーションは
レビュー不能な差分になり、Jackson 移行と同一リリースに載せると原因切り分けが壊れる。
新規コードから漸進する。

**A-4 決定 — Spring の @Retryable は採用しない。** 既存 retry (PurviewHttpRetryHandler 等)
は budget 契約 (`worstCaseBackoffTotalMs`) と結合しており、fenced critical section の
予算計算がフレームワーク側の裁量に移ると budget が読めなくなる。

**A-6 完了 — 1 箇所ではなく 6 箇所あった。** 依存に存在するのは log4j-api 2.24.3 のみ
(実装 log4j-core も log4j 1.x も無し)、`log4j.properties` の中身は 1.x 構文
(`log4j.rootLogger=`)、実際のログは Logback (logback.xml) — つまりこのフラグを読む主体は
classpath 上に存在しない。Dockerfile / Dockerfile.simple と compose 4 本
(auth-test / ldap / prod / simple) の JVM opts から削除した。1 箇所だけ消して 5 箇所
残すのは、掃除としてはむしろ状態が悪い。`COPY log4j.properties` と
nemakiware-*.properties 内の同名エントリは無害な残骸として残置 (削除は別件)。

## 3. Part B — Jackson 2 → 3 移行 (v3.4 主題の提案)

### 3.1 事実関係

- Jackson 3 は groupId/namespace が `tools.jackson` に変わる。**annotations は
  `com.fasterxml.jackson.annotation` のまま**。正確には「3 系の同名パッケージ」ではなく、
  **Jackson 3 の BOM 自身が `com.fasterxml.jackson.core:jackson-annotations` 2.22 を
  pin している** — annotation 名前空間は設計として 2 系と共有される。
  つまり **annotation しか使わないコードは無変更** (実測 69 ファイル)。
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

### 3.6 実施結果 (2026-08-06)

**B-0/B-1/B-2/B-3 完了。** 実測で計画から動いた点だけ記す。

- **Jackson 3 は 3.2.1**。API 差分は javap で実物を確認して移行した (推測で書かない)。
  効いたのは `JsonMapper.builderWithJackson2Defaults()` — Jackson 2 互換プロファイルを
  builder の出発点にできるため、byte 互換の要が「設定を書き写す」ではなく
  「2 系の既定を引き継いで差分だけ書く」形になる。
- **mapper は不変化した** (`setVisibility` 等の mutator が消滅)。これを機に、歴史的に
  二重定義されていた `ObjectMapperFactory` と `JacksonConfig` を、前者に一本化して
  後者は薄い委譲にした。設定 drift の口を塞ぐのが目的 (§3.5 の三点目)。
- **移動した API の実測**: `JsonParseException` → `tools.jackson.core.exc.StreamReadException`
  (`.exc` パッケージ)、`TextNode` → `StringNode`、`JsonNode.fields()/fieldNames()` →
  `properties()/propertyNames()` (戻りが Iterator から Collection/Set へ)、
  `deepCopy()` の戻りが `JsonNode` (ObjectNode 代入は cast)、
  `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` → `cfg.DateTimeFeature` へ移動、
  `JsonParser.Feature.STRICT_DUPLICATE_DETECTION` → `StreamReadFeature`、
  `MapperFeature.USE_STD_BEAN_NAMING` は**削除** (std naming が常時有効)。
- **例外が unchecked 化した副作用**: `catch (IOException)` が「本体でスローされない」
  コンパイルエラーになる箇所が 9 つ出た (Purview client 群ほか)。機械的な import 置換では
  出ず、コンパイラが拾う類のもの。`tools.jackson.core.JacksonException` に置換した。
- **B-3 は pom では実現できない。** 計画は「2 系への直接依存を pom から排除」だったが、
  jackson-core/databind 2.x は Cloudant SDK・SolrJ・CXF・Jersey provider のために
  **正当に宣言されている**。したがって「pom に無いこと」では表現できず、
  `JacksonMigrationBoundaryTest` で**ソースレベル**の境界を pin した:
  `com.fasterxml.jackson.(databind|core|dataformat)` を参照してよいのは宣言済みの
  境界ファイルのみ (Jersey の `ContextResolver<com.fasterxml…ObjectMapper>` 2 本 —
  Jackson 2 型そのものが契約 — と Yubico POJO を読む WebAuthnResource)。
  新しい直接参照はテスト失敗になる。
### 3.7 golden が掘り当てた本質的な事実 — 「byte 一致」は JVM の保証を超えていた

B-1 の golden は生 byte を比較していた。**単独では通り、フルスイート内では落ちた。**
原因は Jackson ではなく JVM だった:

- HotSpot はクラスのメソッド配列を**メソッド名 Symbol のアドレス順**で保持し、Symbol は
  プロセス全体でロード順に intern される。したがって `isFolder()` を宣言する任意のクラスが
  `CouchNodeBase` より先にロードされると、`getDeclaredMethods()` は `isFolder` を
  `isDocument` より前に返す。**実証済み** — その順序はスイートで観測された並び替え
  (`document/folder` と `content/attachment` の対の入れ替わり) を正確に再現する。
- Jackson はアクセサ由来プロパティの順序をこの反射順から導く。**2 系でも 3 系でも同じ。**

つまり「兄弟キーの並び」は JVM が保証しておらず、本製品が持っていたこともない。
生 byte で固定することは、Jackson の挙動ではなくクラスロード履歴を固定することであり、
テストは中身と無関係に落ちる。golden は次の形に改めた:

- **Map 由来の文書 (lineage 形状) は byte 厳密**。順序は LinkedHashMap の挿入順で決定的。
- **POJO 由来は「path = 値」の多重集合で厳密比較**。キー集合・値・**符号化**
  (数値の文字列化 / 数値、日付表現、null 包含)・入れ子・**重複キーの個数**まで見る。
  落とすのは兄弟の並びだけ。
- Jackson 自身の順序判断である `SORT_PROPERTIES_ALPHABETICALLY`
  (3 系が既定を ON に反転した。enum のバイトコードで確認) は**直接 assert** する。
- 加えて、rewrite quirk の**安全条件**を明示的に assert する:
  重複キーは同じ値を持つ (だから last-wins が無害)。これは byte 順の assert では
  決して見えなかった性質で、旧版より強い。

**byte 互換の結論**: 内容・値・符号化・往復形状は 2 系と同一。並びのみ JVM 依存であり、
それは移行前から同じだった。

### 3.8 副作用 — Spring MVC の JSON コンバータが自動的に 3 系へ切り替わる

Spring 7 の `DefaultHttpMessageConverters` は `tools.jackson.databind.ObjectMapper` の
存在を検出すると `JacksonJsonHttpMessageConverter` (3 系) を優先する。既定 mapper は
`JsonMapper.builder()` — つまり **Jackson 3 素の既定**であり、
`builderWithJackson2Defaults()` ではない。したがって classpath に 3 系を載せた時点で
`/core/api/...` (Spring MVC 側) の応答が変わりうる。実応答を移行前後で採取して差分を取った:

| 観点 | 結果 |
|---|---|
| 日付表現 | **不変** (ISO-8601)。3 系の `WRITE_DATES_AS_TIMESTAMPS` 既定は false で、Spring の 2 系コンバータも元々無効化していた |
| 値・型 | **不変** |
| Map ベースの応答 (users / groups) | **完全一致** |
| POJO ベースの応答 (connectors / import-profiles) | **プロパティ順がアルファベット順に変化** |

**判断: 受け入れる。** 従来の並びは §3.7 のとおり JVM のクラスロード順依存で、
起動ごとに変わりうるものだった。アルファベット順は決定的であり、
JSON オブジェクトのキー順に意味はない。RELEASE_NOTES に明記した。
固定したい場合は `builderWithJackson2Defaults()` で組んだコンバータを
`spring-mvc-context.xml` に明示登録すればよい (今回は採らない)。

Jersey 側 (`/core/rest/...`, `/core/api/v1/cmis/...`) は
`ContextResolver<com.fasterxml…ObjectMapper>` 契約のため 2 系のままで、影響なし。

### 3.9 レビューが掘り当てた欠陥 (自己レビュー, 2026-08-06)

移行差分を敵対的にレビューして 3 件の実欠陥が出た。いずれも**コンパイルが通り、
フルスイート 4,768 件が green のまま**成立していた種類のもので、記録に値する。

**(1) CRITICAL — Jersey の ContextResolver を移行して黙って引き抜いていた。**
`NemakiJacksonProvider` を `ContextResolver<tools.jackson…ObjectMapper>` に書き換えていた。
Jersey は `getContextResolver(com.fasterxml…ObjectMapper.class, …)` で**総称型引数によって**
解決するため、これは「型名の変更」ではなく「**発見されなくなる**」ことを意味する。
Spring は @Component として注入し、例外もログも出ず、Jersey は自前の素の Jackson 2 mapper に
フォールバックする。結果、`/core/rest/*` の全応答が nemaki プロファイルを失い、
とりわけ `WRITE_NUMBERS_AS_STRINGS`(既存クライアントとの**ワイヤ契約**)が消える。
Jackson 2 に戻し、プロファイルを 2 系で書き直した。二重定義の drift は
`JerseyBoundaryMapperParityTest` が bytes 比較で禁じる。
実機確認: 修正後 `/core/rest/repo/{repo}/renditions/{id}` の `count` は `"0"` (文字列)。
`qa-test.sh` にこの end-to-end 番人を追加した — provider が外れる失敗は**単体では見えない**ため。

**(2) HIGH — `setSerializationInclusion(NON_NULL)` の意味を半分しか移していなかった。**
Jackson 2 の同メソッドは `JsonInclude.Value.construct(incl, incl)` に展開され、
value **と content 両方**の inclusion を設定する。移行は `withValueInclusion` だけを設定して
おり、content は `USE_DEFAULTS` のまま = **Map の中身の null が抑制されない**。
これは `CouchNodeBase` の `@JsonAnyGetter` を通って永続に届く: `additionalProperties` 内の
null が「不在」から「明示的 null」へ変わる。ACL-epoch のマーカーは `containsKey` で読まれ、
Mango の `{"$exists": true}` は present-null に**マッチする**ため、
読み書きしただけで quarantine/anomaly 判定と sweep 対象が動きうる。
`withContentInclusion` を追加。**golden がこれを見逃した理由も欠陥**であり、
「map 内 null」の fixture (`customAbsent`) を足した。負のテストで両ガードが落ちることを実測済み。

**(3) HIGH — `new ObjectMapper()` は Jackson 3 では意味が変わる。**
bulk 置換は型だけを書き換えたため、main 58 + test 31 箇所の素の構築が
**Jackson 3 の既定**を採用していた。`configureForJackson2()` が戻す 16 項目
(アルファベット順ソート、日付の ISO 化、`FAIL_ON_NULL_FOR_PRIMITIVES`、
`FAIL_ON_TRAILING_TOKENS`、`USE_GETTERS_AS_SETTERS`…) が一斉に反転する。
具体例: `ExternalIngestController` はクライアント JSON を primitive 持ちの bean に読むため、
`{"dryRun": null}` が `false` から**例外**に変わる (しかも 3 系の例外は unchecked なので
既存の `catch (IOException)` にも掛からない)。
`ObjectMapperFactory.createDefaultObjectMapper()` (= Jackson 2 互換既定) に統一し、
`JacksonMigrationBoundaryTest` で素の構築を**禁止**した。

**この 3 件の共通点**: 「コンパイルが通り、テストが緑で、レビューでは同じに読める」変更が
実際の振る舞いを変えていた。移行の危険は**書き換えた行ではなく、書き換えなかった行**にある。

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
