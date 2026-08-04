# 設計増分 A — 増分 changelog (Atlas lineage endpoints)

この文書は **履歴** です。現行の契約は
[`atlas-lineage-endpoints.md`](atlas-lineage-endpoints.md) にあり、そちらが正典です。

分離した理由: 契約文書の先頭 748 行が追記型の changelog になっており、読み手は §0 に
たどり着く前にその全部を通過していました。履歴は消さない — 撤回した設計とその理由は
同じ判断を二度しないために必要な記録です。ただし**次の変更を読む人が毎回払うコストでは
ない**ので、場所を分けます。

**乖離を見つけたら契約文書が正**です。ここは「いつ・なぜ変えたか」しか語りません。

---

## revision

- v2.3.29 — **増分 B: artifact 型の Atlas 型定義**。`EndpointKind.IMPORT_ARTIFACT` /
  `EXPORT_ARTIFACT` は A-1 からあったが、**受け側の Atlas 型が無かった** —
  `EndpointKindSchemaAlignmentTest.AWAITING_SCHEMA` がその負債を明示していた。
  - `nemaki_import_artifact` / `nemaki_export_artifact` を `PurviewSchemaPayloadFactory` に追加
    (schema version 13 → 14)。`AWAITING_SCHEMA` は `CMIS_FOLDER` のみに縮小。
  - 3 契約 (identity = operation / §2 の属性上限 / §4 の secret 非保持) を
    それぞれ test で pin した。詳細は §3 の「artifact 型の 3 契約」。
  - **secret 非保持は「置かない」ではなく「置けない」にした**: 場所や secret を思わせる
    名前を allowlist と entityDef の両方で禁止する。artifact 型は「source を足す」提案が
    最も自然に来る場所なので、規約ではなく検査にした。
  - manifest と payload の型一覧の突合せを追加。両者は別ファイルで、
    payload だけに型を足すと manifest hash が変わらず、適用済みデプロイは型を作らない。
    突合せが実際に落ちることを mutation で確認済み。

- v2.3.28 — **digest / 切詰めの整理** (仕様追加ではなく、既にあった 4 種類の呼び分けを
  名前と型で固定した)。`MessageDigest.getInstance("SHA-256")` の直接呼出しが 7 箇所・
  5 つの別名 (`hash` / `sha256` / `sha256Hex` / `shortDigest` / `evidenceDigest`) に散っており、
  全部が「小文字 hex」を返すので**取り違えても気づけない**状態だった。
  - `LineageDigests` に素の primitive を 1 つ置き、plain な呼出しを合流。
    **identity (`LineageCanonicalHash`) と distribution (`LineageBinaryDigest`) は合流させない** —
    domain 分離が契約そのもので、golden vector が凍結されている。
  - **redaction (12 hex) は evidence (64 hex) の prefix** である、という取り違えの経路を明示し、
    `isEvidenceDigest` の幅検査で塞いだ (`LineageDigestKindsTest` が pin)。
  - 切詰めも 2 種類あった。`LineageEventBuilder` の素の `substring` は**本番呼出しが無く**、
    surrogate pair を割り得た。削除し、snapshot 系を §2 の `truncationLength` に合流させた
    (**唯一の挙動変更**: 上限が surrogate 中央に落ちる名前/パスの保存値が、壊れた最終文字から
    well-formed な prefix になる。`_hash` は元値の evidence なので情報は失われない)。
  - 併せて `CouchLineageJournalStore` の責務分割 5/5 が完了 (3,441 → 1,943 行)。
    対応表は [`lineage-store-responsibilities.md`](lineage-store-responsibilities.md)。

- v2.3.27 — **4b preflight** (Codex 計画レビュー 3 巡 → proceed)。4b の受入条件のうち 3 つは
  「判断」ではなく**今のデプロイでは取れない測定**だった、という並行レビューの指摘への対応。
  activation は行わない。
  - **cursor 検査は厳密パース**。当初案の `stored == normalize(stored)` は **false-green** —
    `normalizeLine` は五フィールド形式でない行を意図的に素通しするので、未知の形の URL を持つ
    cursor が自分の正規化と一致し clean と出る。この検査が探しているのは正にそれ。全非空行が
    正確に 5 フィールドで URL スロットが空であることを要求し、未知形式・読取り失敗・inventory
    列挙失敗は全て **fail-closed**。
  - **inventory は和集合** (configured ∪ 永続 cursor 保持者)。設定から外れた repository は
    cursor を保持し続けるので、configured だけを見ると残渣を跨いでしまう。
  - **presence は 4 状態** (`ABSENT` / `PRESENT_EMPTY` / `PRESENT_VALUE` / `ERROR`)。
    `PurviewStateStore.getString` は欠損を `""` に潰すため、絶対に区別できない。`getRaw` を
    追加 (既存 API は不変)。`ERROR` は受入失敗 — 読めなかった cursor は検査されていない。
  - **cursor 値・URL・token・その断片は response にもログにも例外にも出さない**。残留 token の
    検査が、それを印字するものになってはいけない。テストで断片の非出現を固定。
  - **digest の循環論法を応答自身に書く**。`GET /barrier` が ACK ごとの `binaryDigest` と実測値を
    返すようにしたが、この route が返した値をこの route が返したという理由で承認するのは循環。
    `LineageBinaryDigest` に **CLI entry point** を足し、承認対象成果物から**本番と同一コード**で
    計算して照合する。別実装は「2 つのプログラムが一致する」ことしか証明しない。
  - **空の allowlist は production 受入失敗**。CAS 条件 9 は空リストのとき検査を課さないので
    `blockingConditions` は何も言わない。preflight が言う。
  - **spool は real path と FileStore を報告**。絶対パスは mount ではなく、どのボリュームに
    落ちるかを何も語らない。暗号化・鍵管理・再起動永続性は**検証不能項目として名指し**する
    (省略は「問題なし」と読まれる)。
  - **総合 verdict に `PASS` は無い** — `FAIL` / `EXTERNAL_EVIDENCE_REQUIRED` の 2 値。外部でしか
    測れない項目がある以上、アプリが PASS を宣言するのは検査していないことの主張になる。
  - 運用手順は [`docs/operations/lineage-4b-activation-checklist.md`](../operations/lineage-4b-activation-checklist.md)。
    旧 AP 不在・暗号化・鍵管理・backup 暗号化・2 回起動マーカー・digest 独立照合・片道性・
    rollback の限定的意味・**Purview での E-20 再実測必須**を含む。
- v2.3.26 — **§2 の属性別上限 (producer 側 slice) 実装** (Codex 計画レビュー 4 巡 → proceed)。
  §2 の 5 規則のうち「単独超過 → durable `UNRESOLVED(OVERSIZE)`」と「silent drop 禁止」は
  v2.3.22 の chunking で既に実装済み。今回入れたのは残る 2 つ = 事前上限と truncate 証跡。
  - **truncate は producer factory でのみ行う。** canonical constructor では**やらない** —
    spool / v2 の両 codec が constructor 経由で endpoint を再構築するため、そこで正規化すると
    **保存済みレコードを読んだ瞬間に書き換え**、永続化された `payloadDigest` /
    `creationPayloadDigest` の検証が壊れる。移行注記で済む話ではなく破壊である。
  - **必須値・identity 値は一切縮めない。** constructor で拒否する案は撤回した:
    `LineageFactEmission.emitSafely` が例外を握り潰すので、1 属性を守るために **fact 全体を
    失う**。超過分は planner に届き、既存の `UNRESOLVED(OVERSIZE)` + endpoint hash で終端する
    (§2 の規則そのもの)。さらに外側に `LineageFactSpool` の 32 MiB record 上限がある。
  - **証跡は `{name}OriginalSha256`、存在自体が marker。** §2 の字面 `nameTruncated=true` を
    **改訂**した: `EndpointAttribute.Type` に boolean が無く、1 flag のために凍結済み語彙を
    増やすのは割に合わない。また "Truncated" という名の field に digest を入れるのは嘘になる。
    値は**元値**の SHA-256 (UTF-8, lower hex)。
  - **companion は Atlas schema にも足す** (`nemaki_document` / `nemaki_archive`、additive)。
    型が宣言していない属性は到着時に落ちるので、schema に無ければ「縮めた証跡」こそが
    消える。`EndpointKindSchemaAlignmentTest` がこの条件を検査している。
  - **truncate 対象は 4 箇所だけ**: `CMIS_DOCUMENT.versionLabel` / `.folderPath`、
    `ARCHIVE.name` / `.versionLabel`。他は全て `PRESERVE` — `versionSeriesId` は version
    lineage を**識別**する値、`archiveState` は機械解釈される**状態**、`externalPath` は
    constructor が等値を要求する identity の**鏡**、artifact 2 種は Atlas 型が未定義
    (増分 B)。§2 の規則は「optional な**表示値**」に対するものなので、絞ると 4 つになる。
  - **冪等**: digest は常に元値から。長い値 + 既存 companion は再計算して**一致を要求**、
    短い値 + companion は両方保持 (再送 case。元値はもう無いので再 truncate 不能)、
    companion 自身は `PRESERVE`。`EndpointKind` は重複宣言を**例外**にした (従来は黙って上書き)。
  - **identity は動かない**: `processKey` / `deliveryId` / `spoolRecordId` は qualified name を
    hash しており、truncate 対象から qualified name は作られない。動くのは
    `creationPayloadDigest` / spool `payloadDigest` / `materializationPlanDigest` (V2・V3) の
    content digest 群。既存 golden vector に 1024 超の属性は無いので**既存の期待値は不変**。
  - `RetentionScheduler` が `new LineageEndpoint(...)` で archive endpoint を直に組んでいた
    (= 上限を素通り) ので factory 経由に変更。**production ソースを走査して
    `new LineageEndpoint(` を factory 群と 2 つの decode 経路以外で使っていたら落ちるテスト**を
    追加したので、次に同じことをすればビルドが落ちる。
- v2.3.25 — **Slice 4a 実装** (Codex 計画レビュー 8 巡 → proceed)。writer は v1 のままだが、
  **版対応の書込み経路を完成させた**ので 4b は「文書 1 件の CAS」だけになり、デプロイを伴わない。
  - **三値の barrier 読取り** (`LineageBarrierReader`): `Present` / `Pristine` (検証済み 404) /
    `Indeterminate` (読取不能、または **witness 付きの 404**)。emit・materializer・reader
    admission・admin status の 4 者が**同じ 1 つの判定**を使う (別々に読むと「片方は spool、
    片方は admit」が起きる)。
  - **witness 文書** (`lineage_barrier_witness`) を **barrier より先に**書く。barrier 文書を
    消しただけで pristine に戻る = fence を 1 文書の削除で無効化できる、を塞ぐ。
    **限界を明記**: `nemaki_lineage` を DB ごと復元すると witness も barrier も消え、node-local
    spool だけが残る — アプリは新規デプロイと区別できない。復元後に fence を作り直す/検証するのは
    **運用手順の責任**であってアプリの保証ではない。
  - **materializer は `Pristine` を v1 (generation 0) に収束させる**。一時障害中に spool した
    fact は、不在が証明された時点で**収束できなければならない**。`Indeterminate` だけが未決。
  - **emit の分岐**: reader 未配線 → v1 (4a 以前の構成)、`Pristine`/`Present(1)` → v1、
    `Present(2)`/`Indeterminate` → **fact を spool**。v2 を emit から append することは無い
    (chunking・決定文書・digest 収束・K 行 ACK は全て materializer 側)。**spool が無いときに
    v1 へ落ちない** — barrier が「この fact は v1 経路ではない」と言った以上、spool できない
    ことはそれを覆さない。`lineage.emit.dropped{reason=...}` を上げて捨てる。
  - **tick 順序を凍結**: admission → REFUSED は spool に触れず return → UNDETERMINED は
    **未解決 pin で scan** して return → ADMITTED で scan → `isActive()` → leader guard。
    `isActive()` の後ろに scan があると、最初の fact が spool された (= DB 未作成) デプロイで
    scan が永久に動かない罠になる。UNDETERMINED が保証するのは「**新規**決定を作らない」ことで、
    凍結済み決定の収束は続く (§6-a の crash convergence)。
  - **admission は全 v2 driver 境界**に適用 (loop・両 scan 入口・replay・replay-recovery・
    sequencer admin)。loop の guard は loop しか守らない。
  - **pin は scan 呼び出しスコープ** (`ThreadLocal` + `try/finally`)。bundle は共有だが scan は
    共有ではない (admin route は request thread) ため、bundle 全体の可変 pin は漏れる。
  - **spool は 1 bundle に統合** (`LineageSpoolMachinery`): emitter・readiness probe・scanner・
    materializer が同一 instance を使う。probe が測る volume と writer が書く volume が
    別物では probe の意味が無い。
  - **`BARRIER_BINARY_V1`** (新規凍結): `WEB-INF/lib` と `WEB-INF/classes` 配下の全通常ファイルの
    `hash(domain, LIST[MAP{path, sha256Hex}])`。`SecureDirectoryStream` で降り、**個別の
    regular-file 検査を置かない** — `NOFOLLOW_LINKS` 付きの open 自体が検査であり、hash するのは
    その open が通したオブジェクトそのもの (check/open race が構造的に無い)。symlink・
    測定不能はすべて `BINARY_DIGEST_UNAVAILABLE` で **ack 拒否**。部分 digest も `""` も作らない。
    **凍結値の改訂 (レビュー承認済み)**: 「起動時に 1 度計算」→「プロセスにつき 1 度・初回使用時」。
    barrier を使わないデプロイに毎起動のハッシュ計算を負わせない。安全性は不変。
  - **ack / activate は毎回 reread-recompute**。409 は**再計算からやり直す** (前の revision に
    対して計算した ACK を貼り直すと readiness・鮮度・世代が stale になる)。ack は red gate・
    測定不能 digest・状態/世代不一致で**文書を一切変更せずに**拒否する。
  - **`prepare` は membership を受け取らない**。v3.3 の規範形は単一 AP であり、`expectedNodes` は
    自 node 1 件。`approvedBinaryDigests` / 追加 capability は未指定なら**保持** (再 prepare で
    allowlist が静かに消えない)、明示的な空リストで消去。
  - **決定的テスト**: 50 (barrier/CAS/witness/memo/admission/golden vector) + 9 (emit 分岐) +
    実 CouchDB IT 3。IT のうち 1 つは **pre-4a 構成と 4a 構成を独立した 2 つの空 DB で走らせ、
    全文書を突き合わせる**差分テスト — 「barrier が無ければ 4a 以前と同じものしか永続化しない」は
    1 回走らせて文書集合を見ても証明にならない。
- v2.3.24 — **chunking 後追いレビュー対応** (P1 1 件 + P2 3 件)。
  **F1 — 途中 chunk の park が先行行を projectable のまま残していた。** K 行中 j 行目で
  CouchDB が拒否すると、0..j-1 行は既に PENDING で durable なのに park marker が出て
  fact が作業集合から外れる — **K-1 of K chunk が「完全な fact」として publish される**。
  park の前に先行行を**非 projectable 化**し、できなければ **park しない** (`FAILED` のまま
  次 pass が再試行。`lineage.materialization.partial_rows_escaped` を上げる)。見える wedge の
  方が静かな部分 lineage より害が小さい。
  終端は `PENDING → UNRESOLVED` (**§8-b 無 claim 表に追加**)。`DISCARDED` は使えない —
  durable reason を持てず、かつ **UNSEQUENCED 行では不変条件違反**になる
  (`LineageJournalRowV2` は unsequenced 行に creation-time 集合以外の status を拒否する)。
  これは**実 CouchDB IT が発見**した (mock は行不変条件を再検証しないので緑になっていた)。
  `UNRESOLVED` は unsplittable fact に対して creation 時に書く判定そのもので、違いは
  「行が既に在ってから判明した」ことだけである。
  **F2 — 書込み前の ceiling 検査。** planner は `chunkLimits.maxPayloadBytes` に対して
  chunk を詰めるが、store の上限は `maxDocumentBytes` という**別の設定**。全行を書込み前に
  測り、超過なら**1 行も書かずに** park する。F1 の終端経路は ruler と CouchDB 内部表現の
  差だけに残る。
  **F3 — readiness に関係式を追加**: `max-payload-bytes <= max-document-bytes`。個別範囲は
  検証していたが関係は見ていなかった。逆転すると全 plan が well-formed かつ unstorable。
  **F4 — 実 CouchDB parking IT** (`LineageParkingCouchIT`): node の `max_document_size` を
  一時的に下げ、①最初の chunk が拒否される fact は 1 行も書かずに park、②後続 chunk が
  拒否される fact は先行行が UNRESOLVED になってから park、を実測する。設定は
  `@AfterAll` で必ず復元し、失敗時は大声で落とす (元が未設定なら DELETE で戻す)。
- v2.3.23 — **§6-a の sign-off レビュー対応** (Codex: NO-GO → 指摘 Critical 2 + High 2 を反映)。
  §6-a は D-rest / chunking が着地する前に書かれており、実装に追い越されていた:
  ① **capability と readiness を両方要求する** — capability は「このバイナリに配線がある」
  という静的事実、readiness は「今この node で実際に動く」という動的事実。旧稿は前者しか
  見ておらず、**v2 書込みを開いたのに sequencer/projector が dormant** という状態が作れた。
  ACK に `drestReady` / `drestViolations` を追加し、CAS 条件 10 として要求 (ACK 受理時と
  `ACTIVE` CAS 時の**両方**で新鮮な評価)。`requiredCapabilities` は server-defined で
  弱められない (文書側で足すことはできる)。実装の capability 定数に **`read:v2` を追加**
  (§6-a の表は要求しているのに provider が持っていなかった)。
  ② **`spoolSchemaVersions が 2 を含む` は充足不能だった** — spool record の版は 1 だけで
  codec は他を拒否する。2 を要求すべきは「fact から materialize できる **event** の版」なので
  `materializeEventSchemaVersions` へ改名し、record 側は `spoolRecordSchemaVersions` として
  別に報告する。4a に spool schema 2 を捏造させない。
  ③ **v3.3 の規範形 = 単一 AP 状態機械を完全化** — multi-node の 11 条件と「単一 AP なので
  membership 推測も再提示も要らない」が並存して矛盾していた。v3.3 が実装する条件集合
  (1・3〜11、2 は適用外) と、**永続化契約を全て凍結** (barrier 文書は `nemaki_lineage` の
  `lineage_write_version` 1 件・`_rev` CAS・nodeId/bootId/binaryDigest の定義・
  membership digest は LineageCanonicalHash で JSON 文字列を hash しない・時刻は epoch
  millis・ACK TTL 300s)。決定的テスト #2 は将来 multi-node 側へ移す。
  ④ **決定 schema と 4a テスト表を chunking に同期** — 単一 `deliveryId` / `eventDigest` は
  撤回 (1 fact は K 行になり得る)。schema を実装形 (allocatedEventId / planDigestVersion /
  partitionVersion / chunkLimits / creationClassification / planEntries 列) に更新し、
  手順 3〜5 を「凍結 limits で再分割 → entry ごとに事前照合 → K 行 → **全 entry 再読検証後に**
  ACK」へ。テスト #8/#10/#11 の「1 件だけ」を「K 件ちょうど」に、#9b (K-1 行でクラッシュ) を
  追加、#4 を書き換え (活性化した新 reader は v2 を**意図して** claim する — 正しい不変条件は
  「v1 専用 6 view が v2 行を emit しない」)、#13b は実装の無い endpoint/metric を指していたので
  4a の完了条件から除外、readiness 否定テスト #18/#19 を追加。
  ⑤ **rollback の意味を明記** — `writeSchemaVersion` を 1 へ戻すのは「新しい ORIGINAL fact を
  どの版で材料化するか」だけを変える。凍結済み V3 決定の材料化と v2 replay は続くので
  「以後 v2 行は 1 件も作られない」保証ではない。
  ⑥ **E-19 / E-20 を実測して記録** (2026-08-03、Apache Atlas 2.3.0 OSS ローカル): 両方 PASS。
  E-20 は「同一 guid のまま `cloudFileUrl` が null になり token が残らない」ので、Atlas OSS
  では purge / 再作成 runbook は**不要**。Purview は別 backend なので再実測が要る。
  再実行可能な gate として `PurviewLiveAtlasSecretsIT` + 専用 CI job (skip 不能) を追加。
- v2.3.22 — **chunking 実装** (fact→v2 写像内)。Codex 計画レビュー 5 巡 (revise×4 →
  proceed) の確定事項:
  ① **分割は正準順序** — MANY 側を `LineageCanonicalHash.canonicalQualifiedNames` 順
  (digest が使う凍結正準化と同一) で走査。同一 fact の並べ替えは同じ spoolRecordId /
  payloadDigest を持つので、chunk 構成・deliveryId・plan digest も一致しなければならない
  (producer 走査順に identity が依存する — 旧案の「宣言順」は撤回)。
  ② **anchor を全 chunk に複製**し MANY 側だけを分割。全 shape は「片側 ×1 / 反対側 1..n」
  なので、×1 側を各 chunk に載せることで**各 chunk が独立に shape-valid・単独 publish 可能**。
  1→1 shape は分割不能。適合する fact は chunk(0,1) 1 枚で、**従来出力と byte-identical**。
  ③ **ruler は上界 pre-filter、権威は CouchDB** — `LineageDocumentSizeRuler` は JSON 型ごと
  に証明可能な上界 (string = 2+6×length: `\uXXXX` が最大幅かつ任意 code unit の UTF-8 長
  以上 / number = 20 / bool = 5 / null = 4 / 配列・objectは括弧+区切り) を返す **RULER で
  あって serializer ではない**。Jackson 設定に依存せず、corpus/property test は上界の
  **回帰証拠**であって証明ではない。CouchDB は `max_document_size` を内部表現で測るため
  JSON 側の上界は受理を証明できない — **`document_too_large` (413/理由文字列) の判定だけ**
  を決定的な "unstorable" として扱い、他の失敗は infra として伝播する。
  chunk 座標は固定幅 (20 byte) で計上し、計算中の chunkCount に依存しない。
  ④ **限界値は決定に凍結** — plan digest は新 domain **"MATERIALIZATION_PLAN_V3"** で
  spoolRecordId / factPayloadDigest / schema / allocatedEventId / **partitionVersion** /
  **chunkLimits** / **creationClassification** / plan entries を束縛。live config を読むと
  設定変更後に再分割が起きて drift fence に当たり fact が永久に詰まるため。
  `planDigestVersion` 不在または 2 = **既存 V2 決定をそのまま復号** (multi-entry 含め形を
  狭めない)、3 = chunk-aware schema-2 のみ、他は拒否。schema-1 決定は V2 のまま (v1 は
  chunk しない)。
  ⑤ **OVERSIZE は wedge させない** — 分割不能な fact は**全体で 1 つの terminal 行**
  (適合分だけ publish すると「完全だと主張する部分 process」になる)。`appendV2` は
  **PENDING 専用のまま**で、status と durable reason を 1 文書に原子的に書く
  `appendV2Classified` overload を新設 (D-rest-3 F5 の回収)。分類は決定に凍結され (atMs も
  決定の createdAtMs で固定)、409 収束と ACK 前再読の**両方**が status+reason 一致を検査
  する — 同じ key の PENDING 行は integrity 拒否。
  ⑥ **terminal-only repository の発見**: `v2_sequenced_repositories` view
  (`[target, repositoryId]`・SEQUENCED 行・terminal 含む) を ordered walk の discovery に
  union。既存の discovery は cursor と非 terminal 行しか見ないため、生成時 terminal の行
  しか無い repository は訪問されず cursor が前進できなかった。view 23 → 24。
  ⑦ **CouchDB が拒否した fact は parking** — `fact-{id}.oversize` marker (ACK と同じ
  決定的 bytes・hard-link create-if-absent・全束縛検証・破損時は同じ修復経路) で work set
  から外す。**破損 quarantine slot は流用しない** (あちらは「壊れた record」の意味で単数
  占有であり、canonical fact を残す挙動が wedge を再現するため)。fact file は証拠として残る。
  ⑧ 設定: `lineage.endpoint.max-per-event` (既定 1000・域 [2,10000])、
  `lineage.event.max-payload-bytes` (既定 1 MiB・域 [64 KiB,16 MiB])、
  `lineage.event.max-document-bytes` (既定 4 MiB・域 [1 MiB, **8,000,000** = CouchDB 3.x の
  既定 max_document_size]) — いずれも readiness が域検証。**guard rail であって保証ではない**。
  ⑨ **未了として明示**: §2 の属性別上限 + `truncate + SHA-256` 併記 + protected key 規則は
  producer 側の別 slice。本 slice は属性を**一切落とさず truncate もしない**ので、§2 が完全
  実装されるのはその slice 完了時である。
- v2.3.21 — **D-rest-4 実装** (収束 materializer・aggregate capability provider・scanner
  入口 — D-rest 最終 slice)。Codex 計画レビュー 4 巡 (revise×3 → proceed) の確定事項:
  ① **frozen identity**: v1EventDigest = H("SPOOL_V1_EVENT_V1", eventId, eventKey,
  repositoryId, processType.name(), inputs, outputs, snapshotAttributes, occurredAt,
  correlationId)。materializationPlanDigest は**新 domain "MATERIALIZATION_PLAN_V2"** =
  H(domain, spoolRecordId, factPayloadDigest, materializeSchemaVersion, **allocatedEventId**,
  LIST[entries]) — v2 の監査 eventId は plan entry に無いため digest が束縛しないと
  「行が書かれる前の決定改竄」が閉じない (V1 domain は未使用のまま凍結保持 — domain は
  再定義しない)。golden vector 4 件追加 (Java/Python 29 vector 一致)。
  ② **決定文書** lineage_materialization:{spoolRecordId}: typed decode が plan digest を
  **毎回再計算** — allocatedEventId/entries を改竄した決定は値にならない。create-if-absent
  409 は factPayloadDigest + planDigest 完全一致のみ成功 (STORED 決定の割当が凍結真実)。
  既存決定経路も**全束縛辺**を検査: _id / spoolRecordId / factPayloadDigest ==
  再計算した手元 fact digest (復元された自己整合な別 fact を拒否)。
  ③ **書く前に凍結 plan と照合** (A1): 再構成 (純関数 — v1 は record 直構築・clock 不読、
  v2 は pure builder) の (id, digest) が stored entry と不一致 = mapper drift →
  integrity 拒否、**何も書かない** (drift した id に unplanned 行は作れない)。最終再読 =
  durability fence (v2 は eventId == allocatedEventId も検査 — digest は eventId を
  含まないため)。v1 行は **strict 専用 decoder** (writer 実形: 空 map は省略 = 正準空、
  存在して型違いは拒否・runId/correlationId は必須 string・defaulting なし) + fenced
  allocator で sequence を write 時割当 (digest は sequence/targets を含まず、負け race の
  burn は許容 gap)。
  ④ **ACK = fact-{spoolRecordId}.ack** (凍結 layout どおり・4 field のみ・version field
  なし)。検証は fact↔decision↔ACK の**全辺・毎回** (存在だけで抑止しない — 偽造 ACK が
  仕事を抑止するのが ⑦ の名指しした攻撃)。壊れた ACK の収束修復は **hard-link のみ**:
  quarantine slot へ create-if-absent link (先着勝ち) → dir fsync → canonical unlink →
  dir fsync → 検証済み ACK を再公開。遅い repairer が有効 ACK を unlink し得るのは良性
  (次 pass が決定的 bytes を再公開)。
  ⑤ **scanner 入口**: node-local IO なので **leader guard より前・全 node で** 毎 poll 1 回。
  有限 fair budget {max-files 2000 / max-materializations 100 / max-millis 5000} +
  JVM-lifetime rotating cursor (restart は rotation を再開するだけ)。blank dir は Path 構築
  前に検査。手動 POST /spool-scan (gate 赤 = 409)。
  ⑥ **readiness spool 条項 (mode-aware)**: journaled mode (global または repo override) では
  lineage.spool.dir 必須 + 実 write/link/fsync probe 通過が条件 (unset/unwritable =
  NOT_READY)。direct/disabled は Path 構築も probe もせず通過。
  ⑦ **WriteVersionResolver seam**: 既定実装は unavailable (決定なし = fail-closed)。検証済み
  既存決定は resolver を再読しない。4a が §6-a barrier 実装を供給。
  ⑧ **capability provider**: 定数 immutable 集合 {sequencer:event-first, cursor:cas,
  replay:generation-cas, spool:v2} (部品の自己登録なし)。admin status に wired + active
  (= readiness) を表示。§6-a rollout ACK への公開は 4a。
  ⑨ production 既定での完全不活性を test で証明 (resolver 呼出/決定 IO/spool 列挙/ACK IO/
  journal 書込 = ゼロ)。後追いレビュー対応: strict v1 merge fetch の配線 pin (merge が
  Strict を呼び legacy を呼ばない・null result throw・非 clamp) + 本 revision 要約の
  verify キー略記を実装名に統一。
- v2.3.20 — **D-rest-3 実装** (8-d replay request CAS 機械 + crash 回収)。Codex 計画レビュー
  3 巡 (revise×2 → proceed) の確定事項:
  ① **保存形**: v2ReplayRequestsByTarget[target] = MAP{state: REQUESTED|CREATED|ACKED|FAILED,
  generation (>=1, Math.addExact で増加), requestId (UUID・request の所有 fence),
  requestedAtMs, updatedAtMs, reason (FAILED のみ必須)}。typed LineageReplayRequest として
  strict decode; replay request は **SEQUENCED 行にのみ合法** (未採番の配達は再演できない)。
  ② **前提条件** (strict 再読後): 対象 target は trim 済み・source 行の lifecycle map に
  存在・現在構成済み (未構成 target への補償は誰も claim できず readiness も sink を検証して
  いない); publish lifecycle は **terminal のみ再演可** (PENDING/FAILED は生きた機械の管轄、
  PROJECTING/**VERIFYING** は token-fenced claim の横取りになるため拒否 — 設計本文の
  PROJECTING に VERIFYING を追加)。§8-d 本文の「stale claim は reaper が FAILED に落として
  から replay 可能になる」は**撤回** — reaper の FAILED は §8-b 機械が自動再 claim する対象
  であり、replay 適格にはならない。
  ③ **期待集合は凍結どおり {不在, ACKED}**。FAILED request は恒久 durable (衝突診断の唯一の
  記録) で、監査付き repair surface (将来) まで新 request を拒否する。
  ④ **補償 event は純関数** f(original, target, generation): original の eventId/occurredAt
  を再利用 (clock 不読)、correlationId/legacyEventKey は verbatim、**spoolRecordId は複製
  しない** (この配達はその spool fact から材料化されていない — 複製は 1 決定に複数配達を偽束縛
  する)。同一入力 → 同一 deliveryId+digest → appendV2 の exact-match 409 が crash 再試行を
  収束させる。補償は UNSEQUENCED/PENDING で追加され、fenced sequencer が新 sequence を採番、
  §8-b 機械が通常配達する — 特別経路なし。
  ⑤ **reread 駆動収束**: 完了は遷移 boolean から推論せず、**観測された ACKED / durable
  FAILED のみ**を報告。CAS 敗北は「再読して見直す」であり、conflict budget 尽きたら
  INDETERMINATE (成功は捏造しない — durable request は次 poll の回収対象のまま)。衝突
  (digest 不一致) は execute / recovery 両経路で failReplay を reread 収束させ、**FAILED を
  観測してから** 500 を返す。
  ⑥ **purge fence**: REQUESTED/CREATED (補償の決定的再構成が source 行を要る) と FAILED
  (durable 診断) を含む行は purge 不可。ACKED のみは可 (補償行が durable に存在し自身の
  lifecycle を持つ)。
  ⑦ **crash 回収**: v2_replay_requests_unacked view (23 本目) を (documentId, target,
  requestId, generation) の examined identity で走査 (multi-target 行は複数 item)。自動回収は
  **leader guard 直後・empty-target 早期 return より前**に毎 poll 1 回 (B1 — 唯一の target が
  除去された孤立 request も毎 poll 訪問され loudly 拒否される; gate 赤なら service 内で完全
  dormant)。手動 POST /v1/admin/lineage-journal/replay-recovery も併設。
  ⑧ **admin 経路**: 既存 POST /events/{recordId}/replay を schema dispatch — v1 分岐は
  HTTP-200 失敗形まで byte-identical、v2 は ACKED=200 / NOT_READY・REFUSED・INDETERMINATE=
  409 / FAILED (衝突)=500。
- v2.3.19 — **D-rest-2 実装** (8-b v2 遷移 CAS・8-c 単調 cursor・schema 別 projector
  routing・無効化済み admin sequencer 入口)。Codex 計画レビュー 6 巡 (revise ×5 → proceed)
  の確定事項:
  ① **view の schema 完全分離** — SEQUENCED-allowlist の半端は撤廃。v1 機構が読む 6 view
  (by_target_status / by_target_status_time / non_terminal_by_target_repo /
  projecting_by_claimed_at / by_repository_and_sequence / by_occurred_at) を
  `doc.type === 'lineage_event'` 限定に狭め、v2 専用 5 view
  (v2_by_repository_and_sequence / v2_by_occurred_at / v2_non_terminal_by_target_repo /
  v2_claims_by_expiry / v2_verifying_by_since) を新設 (17→22)。**選択による隔離**が唯一
  旧バイナリも守る隔離である (旧バイナリは旧 view 名を照会し、下流に token 検証が無い)。
  新バイナリの router は 2 つの順序 view を sequence で merge (repo 毎に単一 counter なので
  全順序)。coverage bound: 満杯 batch を返した側は最終 sequence まで、少なく返した側は
  無限大までを保証し、min(bound) までを batchSize 上限で処理。3 引数 updatePublishStatus と
  discardEvent は v2 文書を拒否 (二重 fence)。purge の v2 半分 (v2_by_occurred_at) は
  **switch ON のときだけ**照会 — OFF の系は v2 行に一切触れない。
  ② **per-target lifecycle を typed envelope に統合**: publishStatusByTarget (v1 と共有名) +
  v2ClaimByTarget[target] = MAP{token, claimedAtMs, leaseExpiresAtMs, verifyingSinceMs,
  retryCount} + v2TerminalReasonByTarget[target] = MAP{reason, detail, atMs}。v2 の時刻は
  **全て epoch millis** (数値 view key — ISO 可変小数幅の整列欠陥を構造的に排除)。
  claim audit bundle {token, claimedAtMs, retryCount} は all-or-nothing、claim 発生以降
  不滅。**terminal への遷移は audit field を決して除去しない**; FAILED→PROJECTING (再 claim)
  だけが意図された per-attempt reset (token/claimedAt 更新・verifyingSince クリア・
  retryCount 保持)。state 別 decode 不変条件 (居住 claim = token+lease 必須、FAILED は
  verifyingSince の有無が段階 marker、PUBLISHED/UNPROJECTABLE は verifyingSince 必須、
  REJECTED は bundle 有無で gate 由来 / 生成時分類の 2 形、UNRESOLVED/生成時 REJECTED は
  bundle 無し、SKIPPED は v2 で違法)。
  ③ **遷移表の実装**: fenced (token 三重一致 + rev CAS): PROJECTING→VERIFYING /
  VERIFYING→PUBLISHED / VERIFYING→FAILED (max-age、retry 非消費) / PROJECTING→FAILED
  (観測された publish 失敗のみ retry 消費) / VERIFYING→UNPROJECTABLE (reason 必須) /
  **PROJECTING→REJECTED (v2 の §7 gate は 3 引数 v1 経路を絶対に通らない)**。unclaimed
  (expected-state CAS): PENDING→WAITING_FOR_CATALOG / WAITING_FOR_CATALOG→PENDING /
  WAITING_FOR_CATALOG→UNRESOLVED / PENDING→DISCARDED / FAILED→DISCARDED (bundle 保持) /
  **PENDING→UNRESOLVED (v2.3.24 F1。reason 必須)**。
  表外は IllegalArgumentException (呼び手のバグであって race ではない)。enum に VERIFYING /
  UNPROJECTABLE / WAITING_FOR_CATALOG / UNRESOLVED を追加 — UNPROJECTABLE / UNRESOLVED は
  terminal かつ **purge 不可** (REJECTED と同じ証拠論)。reaper / max-age の FAILED は
  retryCount を消費しない (retry 予算は観測された配信失敗の数)。
  ④ **VERIFYING の所有と再開**: claimant は自 token を **in-memory registry** に保持 —
  「同一 claimant の継続」の定義。encounter 毎の bounded poll (lineage.verify.timeout-seconds
  30 / interval-seconds 2 / max-age-minutes 10)、各 verify 呼出しは**残余 budget** を deadline
  として受け取る。poll 開始前 renew + 残 lease < max(2×interval, TTL/4) で renew。renew 失敗・
  token 不一致・CAS 敗北・malformed・構造 fault・reap 観測・終端遷移・shutdown の**全出口**で
  registry を除去 (per-row fence latch)。所有していない/期限切れの居住 claim は halt (期限
  切れは reaper 専管 — 自己 renew 資格なし)。JVM 再起動 = registry 空 = lease 期限 + reaper
  経由で回収。**再 POST は設計自身の crash 経路** (Atlas POST は qualifiedName upsert で
  at-least-once 収束)。
  ⑤ **verify capability は構造的 gate**: LineageTargetSink.supportsVerification() (不変・
  既定 false) + verify(record, deadline)。構成済み target の sink が検証不能なら readiness
  違反 — **sequencer は排水不能な ordered barrier を作ってはならない** (journal-only =
  target 無しは通過: 立ち往生する消費者が居ない)。runtime UNSUPPORTED は構造 fault (publish
  しない・loud halt)。UNSUPPORTED→PUBLISHED は存在しない (PUBLISHED = verify 成功の凍結
  意味を維持)。実 Atlas read-back verify は 4b の前提条件。
  ⑥ **単一 aggregate readiness gate** (LineageDrestReadiness): switch
  (lineage.drest.enabled、既定 false) + config 妥当性 (**claim-lease-seconds >
  verify.timeout-seconds + margin**, margin = max(2×interval-seconds, 10s)、違反は起動拒否でありクランプ
  しない) + **配備済み design doc の view 署名一致** (map と reduce の完全比較 — rolling
  window で旧バイナリが dual-schema view を書き戻した場合に活性化を拒否; 未配備 =
  "deployment pending" 違反であり pass でも crash でもない) + sink capability。sequencer
  admin POST と v2 branch / reaper が**同一の評価**を参照。readiness false の間 v2 branch は
  完全 dormant (claim/renew/verify/遷移/reap 皆無、registry は保持)。回復時は reaper 先行 →
  registry entry の strict 再読 (status/token/未期限 lease 全一致でのみ再開)。
  ⑦ **8-c**: ProjectionCursorStore.advanceCursorMonotonic — max() 意味論の rev CAS、格納値
  malformed (非整数/負) は 0 扱いせず拒否、409 は 1 回だけ再読して勝者が被覆していれば成功。
  switch ON の ordered walk は **v1 行を含む全 cursor 前進が monotonic CAS + 全 status
  永続化の確認後** (v1 PUBLISHED の persist 未確認は前進せず halt — §8-c zero-means-stop の
  一様適用)。switch OFF は v1 経路 byte-identical (保存 list ⑧ 維持)。
  ⑧ **reaper**: v2_claims_by_expiry を固定 cutoff で走査し、**mutation-safe pagination**:
  skip は使わず (reap 成功で anchor 行が view から消えると skip=1 は生存候補を飛ばす)、
  run 内 examined set で再提供行を重複排除、全 page で anchor を最終行へ前進 (全既検査の
  満杯 page も強制前進) — 検査済み行は結果に関わらず一度だけ処理され、corrupt 行が page を
  pin できない。各 hit は strict 再読後に reap-by-CAS (view 行は hint)。inclusive_end=false。
  ⑨ **admin 入口**: POST /v1/admin/lineage-journal/sequencer/{repo}/run (readiness 違反 =
  409 + 違反列挙; 成功 = RunSummary {health, finalized, reclaimed, backlog, lostLease})、
  GET 同 path (無効時も可; lease 不在 = bootstrap hint、infra 障害 = 503)。手動・node-local
  のみ。**活性化前提**: 全 AP が D-rest-2 以上 (v3.3 規範は single-AP; multi-AP は 4a の
  ACK fence 到達後)。切替で cursor が v2 行を通過済みの場合の回収は 8-d replay (D-rest-3)。
  ⑩ countNonTerminalByTarget は v1 semantics に固定 (PENDING/PROJECTING/FAILED の明示列挙 —
  values() 迭代は新 state を破壊的 v1 drain の算術に静かに混ぜる)。v2 backlog は v2 view
  のみが数える。
- v2.3.18 — **D-rest の実装前仕様凍結** (Codex 計画レビュー: proceed with named changes)。
  ① **`sequencerLeaseToken` 採用** — acquire ごとに暗号学的乱数 token を発行し、lease
  文書・SEQUENCING event・SEQUENCED event に保存。claim/reclaim/finalize と全 pre-write
  再確認は (owner, generation, sequencerLeaseToken) の三重一致 CAS。reclaim は
  generation と token の両方を更新。generation の単調性・lease 文書削除禁止は不変
  (token は復旧手順への依存を減らすが、自動再作成を正当化しない)。projector の
  per-target claim token とは名前で区別する。
  ② **v2 行の可変面は型付き envelope** — 不変の LineageEventV2 + _rev + state +
  sequencerGeneration + sequencerLeaseToken + target lifecycle (claim/retry/status) +
  replay request 群を運ぶ decoder/encoder を新設。decoder は不変部を正典 constructor で
  再検証し、可変 field の形と state 依存の必須性を strict に検査。更新は完全 envelope の
  直列化か、検証済み raw 文書への field-preserving patch。**appendV2 の初期行は
  `sequenceNumber == 0`** とし、finalize が sequenceNumber と state=SEQUENCED を単一 CAS
  で設定。409 収束は保存 digest 文字列を信用せず不変 payload を decode・再計算して比較。
  ③ **counter は v1 と同一の `lineage_seq:{repo}` 文書を継続使用** (別 counter は flip 時の
  high-watermark 移行問題を作るだけ)。ただし v1 helper (欠落時 auto-create・不正値 0 扱い)
  は使わず、**fenced v2 allocator** を新設: counter の存在必須・不正/欠落/負値拒否・
  finalized event と cursor の high-watermark 未満を拒否・CAS increment・auto-seed 禁止・
  不安全な失敗で worker latch を落とす。bootstrap: event も cursor も無い repo は seq=0 で
  作成、履歴があるのに counter 欠落/巻き戻りは activation 失敗 + 明示復旧。**Slice 4 で
  schema-1 系の経路が損傷した共有 counter を黙って auto-seed しないことを 4a 前提として
  記録**。
  ④ **WriteVersionResolver seam** — materializer の版束縛は resolver 経由。D-rest 既定
  実装は「unavailable」を返し (親決定が無ければ何も決めない fail-closed)、4a が barrier
  実装を供給。**既存の検証済み親決定がある fact は flag を再読せず決定から続行** (決定は
  版と barrier generation を凍結済み — 再読は crash 収束を弱める)。
  ⑤ **活性化境界** — 全 D-rest driver (sequencer/replay/materializer/scanner) は単一の
  集約 readiness switch (既定 false) + admin 認可の背後。sequencer の admin 入口は
  D-rest-2 (v2 projector routing) 完了まで公開しない — 旧 status/cursor 経路が finalize
  済み v2 行を処理する事故を防ぐ。
  ⑥ **capability は単一の不変 aggregate provider** (部品の自己登録は初期化途中の slice を
  広告するリスク)。sequencer:event-first は sequencer + 安全な v2 projection routing が
  揃って初めて、cursor:cas は本番 v2 projector が使って初めて、replay:generation-cas は
  request 回収配線後、spool:v2 は materializer + 検証付き ACK 完備後。ACK への公開は 4a。
  ⑦ **材料化決定の完全 freeze** (D-rest-4 の前提):
  親決定 `lineage_materialization:{spoolRecordId}` は plan digest だけでなく**不変の
  plan 全 entry を保存** (後続バイナリが写像変更後も元の決定に従えるため)。子決定文書は
  **作らない** — plan entry がそのまま chunk 単位の決定であり、journal 行は entry の
  deliveryId による create-if-absent + digest 完全一致 409 で収束する。
  plan entry (v2): MAP{chunkIndex: LONG, deliveryId: STRING, eventDigest: STRING
  (= creationPayloadDigest)}。plan entry (v1、chunk しない単一 entry):
  MAP{schemaVersion: 1, eventId: STRING (fact の presetEventId、無ければ親決定が一度だけ
  採番した presetV1EventId), v1EventDigest: STRING}。
  **v1EventDigest を新定義** (v1 には creationPayloadDigest が無く 13d が未定義だった):
  H("SPOOL_V1_EVENT_V1", eventId, eventKey, repositoryId, processType.name(),
  inputs, outputs, snapshotAttributes, occurredAt, correlationId) — LineageCanonicalHash 上、
  golden vector に追加して凍結。v1 材料化は eventKeyExists→append の race 経路ではなく
  **決定済み eventId 由来の `_id` (lineage:{eventId}) による create-if-absent**。409 は
  v1EventDigest 一致のみ成功。
  materializationPlanDigest = ~~H("MATERIALIZATION_PLAN_V1", spoolRecordId,
  factPayloadDigest, materializeSchemaVersion, LIST[plan entries])~~ →
  **v2.3.21 で改訂**: H("MATERIALIZATION_PLAN_V2", spoolRecordId, factPayloadDigest,
  materializeSchemaVersion, **allocatedEventId**, LIST[plan entries])。v2 plan entry は
  監査 eventId を持たないため、digest が束縛しないと「行を書く前の決定改竄」が閉じない。
  V1 domain は**未使用のまま凍結**する (domain は再定義しない)。決定文書の当該 field 名も
  実装では `allocatedEventId` (上の presetV1EventId は v1 の場合の由来を述べたもので、
  v2 の監査 id にも同じ「一度だけ採番」規則を適用する — v2.3.21 参照)。
  親決定の create 衝突は factPayloadDigest + plan digest の完全一致のみ成功。
  **ACK は素の marker ではなく束縛 payload**: MAP{spoolRecordId, factPayloadDigest,
  parentDecisionId, materializationPlanDigest} を持ち、scanner は ACK 検証で全束縛を
  照合 (破損/注入後の偽抑止を防ぐ)。ACK 書込みは**全 plan entry の journal 行を再読・
  digest 再検証して durable を確認した後**にのみ行う。
  ⑧ v1 保存不変 (flip まで byte-identical): append(LineageEvent) と eager counter・
  3 引数 updatePublishStatus・v1 claim/reaper/retry・v1 cursor 経路・v1 codec と保存
  field 文字列・v1 view の key/順序/多重度。D-rest は overload と新 interface を足し、
  schema version で dispatch する — in-place 置換をしない。
  ⑨ 実 CouchDB IT は「env-gated で local 実行」+ **専用 CI job が CouchDB を provision
  して必ず実行** (skip された IT は増分 D の gate を満たさない)。資格情報は環境変数。
- v2.3.17 — **D-spool 実装**と、その Codex 計画レビュー (proceed with named changes) で確定
  した仕様修正。golden vector 未凍結のうちに直す — 保存済み record は存在しない。
  ① **spool payload に `correlationId` と optional `legacyV1Projection` を追加** (v2.3.10-11
  の仕様は LegacyV1Projection 設計 (v2.3.14) より古く、v1 材料化に必要な v1 文字列を運べ
  なかった — typed endpoint からの推論は却下済みで、v1 eventKey は生文字列の hash)。
  legacy block は MAP{processType, inputs (宣言順・重複保持 — 正規化しない), outputs,
  snapshotAttributes, presetEventId}|NULL。**schema-1 材料化が正式に退役するまで**生成し
  続ける (最初の flip 完了時ではない — writeSchemaVersion は 1 へ戻りうる)。
  presetEventId 無しの v1 材料化では、D-rest が v1 eventId を決定文書内で一度だけ採番する
  (scanner 再試行ごとの UUID 生成は禁止)。
  ② **payloadDigest 式を訂正して凍結**: H("SPOOL_PAYLOAD_V1", spoolRecordId,
  spoolSchemaVersion, endpointRecords(inputs), endpointRecords(outputs), correlationId,
  legacyProjectionRecord|NULL)。endpointRecords は LineageEventDigest と共有 (再実装禁止)。
  golden vector 4 件 (spoolRecordId / digest 3 変種: minimal・correlation+legacy・
  legacy preset 付き) を identity-golden-vectors.json に凍結し、reference_hash.py が独立
  再導出 — Java / Python 双方 25 vector 一致を確認済み。
  ③ **ファイル公開は hard-link create-if-absent**: write-temp (対象 dir 内) →
  FileChannel.force(true) → Files.createLink (POSIX で原子的 fail-if-exists) → 親 dir
  fsync → temp 削除。ATOMIC_MOVE fallback は置かない (Java に同等に移植可能な
  create-if-absent が無く、置換は last-writer-wins になる)。hard link 非対応 FS は起動時
  readiness probe (実 write/link/fsync) で spoolReady=false とし、全書込みが
  fail-closed → 呼び手の dropped metric へ。FS 要件として文書化。
  ④ **quarantine は上書きしない**: 同一 id・内容相違は fact-{id}.quarantine.json へ保存し
  metric。quarantine 枠が既に埋まっていれば以後の変種は loudly drop (原本 + 最初の
  conflicter で診断証拠は足りる — 全変種保持はバグにディスクを溢れさせる)。既存 record が
  自己検証に失敗する場合は、破損 bytes を quarantine へ退避して検証済み retry を再公開
  (self-heal)。既存 record の照合は**保存 digest を信用せず両 hash を再計算**。
  ⑤ {yyyyMMdd} は payload の occurredAt から **UTC で**導出 (書込み時刻・JVM TZ 禁止 —
  retry が同一 path に収束するため)。producer レベル fact の chunk 座標は **0/1 固定**。
  版未確定の fact を chunk してはならない (v1 へ忠実に再構成できなくなる)。
  ⑥ **scanner / materializer の分割**: D-spool の scanner は列挙・自己検証 (13c: 壊れた
  fact は materializer に到達せず隔離)・SpoolMaterializer seam まで。収束 materializer
  (決定文書・版束縛・journal create-if-absent・ACK) は D-rest。**capability `spool:v2` の
  登録も D-rest** (「materialize できる」の表明であるため — capability 表の担い手を訂正)。
  scheduler 配線なし (活性化は D-rest)。D-spool の依存は A-1 + P-1 (LineageFact を変換
  入力に使うため「A-1 のみ」から訂正)。
  ⑦ **D-rest への仕様修正を先行記録: 決定文書は fact 単位の親 + chunk 単位の子**。単一の
  deliveryId/eventDigest では 1 fact → 複数 v2 chunk を表せない。親決定が schema 版と
  materialization-plan digest を凍結し、chunk ごとの決定/delivery record は決定的に導出、
  fact の ACK は**全 chunk の永続化後**にのみ書く。
- v2.3.16 — producer P-3 (ImportExportResource 5 箇所 + CHAT_MESSAGE_IMPORT) と、その
  Codex 計画レビュー (proceed with named changes) で確定した決定。
  ① **moved content は再帰的な全 object** (top-level root 案は却下 — folder endpoint は
  「移動した folder そのもの」であり、後から中身が変わる部分木の proxy ではない)。
  配管: ImportResult.createdObjects (importer 6 作成点、objectId dedupe・作成順) と
  ExportedObjectCollector / ExportResult.exportedObjects (exporter 再帰 + 選択 export の
  トップレベル)。**容れ物 root は typed には入れない** (legacy の id set と objectCount は
  従来値を維持 — ZipExporter は root を set に入れる歴史的挙動を含めて不変)。
  空 folder の ZIP export だけは v1 が emit する (set に root が入るため) ので、typed 入力
  は folder endpoint 自身に fallback する。
  ② **cap はしない** — 部分集合の fact は嘘になり、fact 放棄は v1 emission の現行動作を
  変える。将来の資源制限は「serialized event の chunking (flip 時)」か操作全体の制限で
  あって、lineage だけの切り詰めではない。producer heap は過渡的に許容。
  ③ artifactKind は **`ZIP` | `FILESYSTEM`** (§8-d の固定値。FILESYSTEM_DIRECTORY は誤り)。
  artifact name は実際の HTTP response fileName を 1 回だけ評価して流用 (selected は
  currentTimeMillis 入りだが display 属性であって identity ではない)。
  ④ **ZIP export の emit は `zos.finish()` 成功後** — artifact は central directory が
  書けて初めて存在する。v1 の emission タイミングも fact と共に後ろへ動く (finish 失敗時に
  従来は不完全 export の v1 event が残った — 正しい capture へ寄る変更として受容)。
  ⑤ **既存 direct-Purview publish* 5 ヘルパの fail-open 穴を閉鎖** — mode 判定
  (getModeForRepository) が業務 try の外で throw しうる。非 throw の
  journalOwnsLineage() に集約 (失敗時は「journal 所有」= direct publish skip 側へ倒す)。
  ⑥ **operationId の返却**: 5 endpoint 全てで `X-Nemaki-Operation-Id` header、同期 JSON
  応答 (import ×2・filesystem export) は body の `operationId` にも。streaming ZIP は
  header のみ。v1 runId には書かない (従来どおり)。
  ⑦ **CHAT_MESSAGE_IMPORT 新設 (19 種目)** — v2.3.13 confirmed bug 1 の実装。fact 分類は
  attachment → CHAT_ATTACHMENT_IMPORT / message → CHAT_MESSAGE_IMPORT に正し、
  LegacyV1Projection は歴史的な逆転ラベルを verbatim 維持 (eventKey に hash 済み)。
  E-2 分母は 18 positive + 1 生成拒否へ。
  ⑧ **restore 系 (ARCHIVE_RESTORE / COLD_RESTORE) は flip 後の最初の producer として延期**
  (Codex 確認済み) — flip までは全 fact が v1 行へ射影されるため、旧バイナリが知らない
  enum ラベルが v1 行に入ると混在環境の復号が壊れる (type=lineage_event_v2 の不可視化は
  v2 行にしか効かない)。CLOUD_SYNC_DOWNLOAD / FILE_SHARE_SYNC_DOWNLOAD の統一は見送り
  (sink 写像と v1 監査の連続性優先、増分 B で再訪)。
  ⑩ (diff 再レビュー対応) **operationId は失敗応答にも載る** — 発行済みなら同期 3
  endpoint の error body / header にも返す (mutation が始まった後の部分作成こそ相関 id が
  要る)。filesystem export の発行は createDirectories (最初の mutation) の前。
  **保存テストは本番構築を通す** — fact + LegacyV1Projection の構築全体を
  ImportExportResource の package-visible static factory 5 つに抽出し、endpoint の
  supplier は委譲のみ、テストは同じ factory を叩く (手組み複製は resource の drift を
  検出できない)。journalOwnsLineage は「意図的な不在 (bean 無し / context 無し) →
  direct publish 可」と「lookup 失敗 → journal 所有扱い (skip)」を区別。
  ⑨ **4a 前提の独立項目として記録: typed process attributes の v2 運搬経路が無い** —
  容れ物 folderId・sourcePath 等の process metadata は現状 v1 snapshot にしか載らない
  (importMode は IMPORT_ARTIFACT に既在、sourcePath は allowlist 外)。writer 起動前に
  LineageFact → v2 envelope/codec → digest 規則 → read model → sink の全層で typed
  process attributes を通す先行 slice が必要。rollout fence だけの 4a に埋めない。
- v2.3.15 — producer P-2c (CloudDriveResource ×3・IngestLineageEmitter) 変換と、その Codex
  レビュー (do-not-commit 7 件) への対応で確定した設計決定。
  ① **GENERIC_EXTERNAL_INGEST を新設 (18 種目)** — v2.3.13 ⑤ の予告を実装。shape は
  external ×1 → document ×1 (ingest family)。fact の分類は GENERIC_EXTERNAL_INGEST、
  LegacyV1Projection.processType は IMPORT_UPLOADED のまま (v1 eventKey にはラベルが
  hash されているため、flip まで両建てで運ぶ)。
  ② **LegacyV1Projection に optional な presetEventId** — ingest API 応答の
  `lineageEventId` は journal を引ける id でなければならない (operationId を返す案は
  互換破壊としてレビューで却下)。producer が preset した id を toV1Event が使い、
  無ければ従来どおり emission ごとに採番。preset は projection と共に flip で消える。
  ③ **emitSafely は boolean (emitter へ引き渡したか) を返す** — id を返す契約の producer
  が「emit されなかったのに解決不能な id を返す」ことを防ぐ。真 = 引き渡しであって
  durable ではない (emit 自身が fail-open で、journal 失敗は dead-letter が id を保存
  したまま replay する)。
  ④ **敵対的 external id の方針を確定 — 剥離しない**。`?` / `#` を含む id は
  ExternalAssetIdentity.parse が拒否し、facade が吸収する (capture 1 件の損失は意図的
  — qualified name は可逆 base64 なので署名付き URL は動く credential になる。v1 が
  平文で永続化していた方が欠陥)。OneDrive 様式の `!` は verbatim に通る。両方を
  テストで固定。
  ⑤ **fail-open の全面化** — 変換済み producer の業務経路に残る lineage コードは
  UUID 採番と emitSafely 呼出しのみ。名前読取り (Archive/Retention の削除前
  getDocument、cloud/ingest の最終名 read-back) と targets 解決は全て guard 済み
  または supplier 内。operationId は業務 mutation の前に採番 (§3)。
  ⑥ **ingest の documentName は最終 object の実名** — version-update 分岐では要求
  fileName と実名が異なりうる。実名は typed v2 endpoint にのみ載り、v1 文字列には
  参加しないので eventKey は不変。
  ⑦ (再レビュー指摘) **`isActive()` も fail-open 境界の内側** — emitter 実装への
  interface call なので、activity 検査が throw しても業務応答を壊せない。
  ⑧ (再レビュー指摘) **ingest の percent-encoding は検証の後** — ExternalSourceUri は
  encode する id segment (objectId / tenantId / messageStableId) の生値に
  `?` / `#` / `%3F` / `%23` (大文字小文字不問) があれば encode 前に拒否する。encode が
  先だと `?` が `%3F` になり ExternalAssetIdentity.parse の literal 検査を素通りして、
  署名付き URL が可逆に qualified name へ入る。非 encode segment の literal は下流の
  parse が拒否する。剥離ではなく拒否 (④ と同方針)。
  例外 (三・四巡目レビュー指摘): **mailbox 名は別ポリシー** — IMAP mailbox 名は任意の
  astring (RFC 9051 §9) であり、`#news.…` (§5.1.2.1 の namespace 慣行) も
  `Questions?` も合法名。文法が許す文字の一律拒否は、正規 mailbox の lineage を
  「import は成功し続けたまま」全損させる。mailbox segment の URL 判定は URL だけが
  持つ特徴 = scheme (`://`) のみとし (署名付き URL は query 持ちでも fragment 借用の
  OAuth token 持ちでも scheme を必ず持つ)、他は全て percent-encode して通す。
  scheme 無しの query 風 suffix は合法 mailbox 名と区別不能なので通す (encode 済みで
  不活性)。messageStableId / objectId / tenantId は厳格ポリシーのまま。
- v2.3.14 — Slice 2d-1 (無損失 journal entry + v2 codec) の設計決定 2 点。
  ① **v2 文書は `type=lineage_event_v2`** — 旧バイナリの view は `doc.type==='lineage_event'`
  でしか選択しない (撤回 2) ので、v2 行は旧バイナリから**構造的に不可視**になる。手順で
  禁じていた「混在環境への v2 投入」の帰結が、禁止から不可能へ変わる。fence は規範のまま。
  代償として 2d-2 以降の全 view / query は両 type を対象にし、それをテストで固定する。
  decoder は `schemaVersion=2` + `type=lineage_event` の偽装も拒否する。
  ② **shape 規則は v2 書込み開始後 widening-only** — decoder は正典 constructor 経由で
  復元するので保存済み行は読むたびに shape 表を通る。狭める変更は保存済み行を読めなくする
  ため、新しい schemaVersion としてのみ許す。
  他: 未知の publish status は v1 codec の「黙って PENDING」(publish 済み event の再 publish を
  招く) を継承せず fail-closed。ただし status は digest 外の可変状態なので、メッセージは
  「不変 payload は無事でありうる — 破棄でなく修復」と分類する。COUNT 属性は JSON 往復で
  Integer に狭まるため decode で Long へ正規化 (digest は動かないが record 等価性が壊れる)。
- v2.3.13 — 外部分析 (コンテンツ移動チェーンとしての再整理) を吟味し、採用分を反映。
  ① **import / export の v2 shape を「folder ではなく移動した内容」へ訂正** — folder は容れ物で
  あり、後から中身が変われば「その時点で何が動いたか」を復元できない。input/output は
  移動した document / folder 群 (chunk 単位 1..n)、容れ物 folder は Process 属性へ (§3)。
  これに伴い IMPORT_UPLOADED の第二 shape (external→document、archetype null fallback 由来)
  を削除 — fallback 自体を v2 では作らない (下記 ⑤)。
  ② **E-2 の分母を訂正** — 「producer のある 17 種」は誤り。producer があるのは 16 種で、
  FILE_SHARE_SYNC_UPLOAD は RESERVED。**16 positive + 1 生成拒否**が正しい受入条件。
  ③ **CHAT_CONTEXT の分類逆転を confirmed bug として記録** — 実 attachment が汎用
  EXTERNAL_ATTACHMENT_IMPORT になり、非 attachment が CHAT_ATTACHMENT_IMPORT になる。
  MESSAGE_CONTEXT のパターンと逆。producer 書換え slice で修正 (CHAT_MESSAGE_IMPORT 新設)。
  ④ **版の同一性を増分 B の必須範囲に昇格** — nemaki_document へ versionObjectId /
  changeToken / contentHash (mimeType / contentLength は既載。versionSeriesId は **schema に
  既在**だったので allowlist へ即時追加した — B を待つ理由が無い)、
  nemaki_external_asset へ sourceRevision / sourceModifiedAt / sourceContentHash /
  sourceContentLength、artifact 型へ manifest 系 (manifestDigest / totalObjectCount /
  totalByteLength / completedObjectCount / failedObjectCount / businessResult)。
  ⑤ **archetype null の IMPORT_UPLOADED 縮退を v2 で禁止** — 「分類できなかった connector
  取込」を「利用者の upload」と偽る。producer 書換え slice で GENERIC_EXTERNAL_INGEST を
  新設して正直に運ぶ (UNRESOLVED 化はチェーンを失うので採らない)。
  ⑥ **restore 方向の欠落を記録** — 監査には ARCHIVE_RESTORE があるが lineage に無い。
  ARCHIVE_RESTORE (archive→document) / COLD_RESTORE (cold→archive) を producer 書換え
  slice で追加。
  ⑦ **「業務 commit 成功後・lineage event 作成前」の crash 窓を §6-a 残余に明記** —
  spool は event (fact) を作った後しか救えない。恒久対策は операция単位の durable
  operation record (STARTED → CONTENT_COMMITTED → LINEAGE_ENQUEUED) で、増分 F として
  範囲外に切り出す。
  ⑧ 採らなかったもの: movementKind 軸への再編・CLOUD/FILE_SHARE enum 統一・Teams の
  文脈/実体分離は識別子と sink 写像に波及するため B 以降の設計判断として記録のみ。
  外部 identity canonicalization の欠陥列挙は**陳腐化** (A-1c〜A-1k で全て修正済み)。
- v2.3.12 — §6-a の**排他・回復可能性・fence の実在性**を訂正し、旧疑似コードとの矛盾を解消。
  ① materialize 決定をローカル sidecar に置いていたのは**排他になっていなかった** — atomic
  rename は「置換」であって「不在なら作成」ではなく、activation を跨いだ 2 つの scanner が
  別々の版を選べば `deliveryId` が違うので決定的 `_id` では潰れない。sidecar は node-local なので
  bundle 複製にも届かず、失った場合に「まだ決めていない」と「決めたが消えた」を見分ける規則も
  無かった。決定を **CouchDB の `lineage_materialization:{spoolRecordId}` へ移し
  create-if-absent で排他**にする (撤回 7)。torn write も検証規則も不要になる。決定文書は削除せず、
  fact の削除は ACK の後、という順序も明記。決定文書は版と `deliveryId` だけでなく
  `eventDigest` も固定するので、決定と materialize の間にバイナリが変わって fact → event の
  写像が変わっても、**同じ `_id` に別内容を書く前に**止まる。「ACK 前に fact を消した」は
  spool 走査からは見えない (対象ごと無い) ので、決定文書側から照合する unresolved 列挙を足した。
  ② barrier に **D-rest capability の検査が実在しなかった** — 本文は「4b は D-rest 配布済みを
  前提に含める」と書いていたが、文書にも CAS 条件にも無く、`binaryDigest` は誰とも比較して
  いなかった。`requiredCapabilities` / `approvedBinaryDigests` と CAS 条件 8・9 を追加。
  ③ rollback fence を **spool の大域走査に置いていたのは判定不能** — spool 列挙は node-local で
  集約 endpoint は v3.3 に無く、scale-to-one では v2 を書いた node が既に停止している。fence を
  `minReaderSchemaVersion` (CouchDB 1 文書・単調増加) に移し、spool 残留は安全条件ではなく
  **回収対象**とする (撤回 6)。
  ④ `payloadDigest` が**存在しない `snapshot`** を対象にしていた (§2 v2.1 で event-level snapshot は
  endpoint-local へ移行済み)。`endpointRecords(...)` を A-1 の型 tag だけで定義し直し、golden
  vector と `reference_hash.py` で凍結する。
  ⑤ `occurredAt` は `spoolRecordId` と `creationPayloadDigest` の両方に入るのに、現行
  `LineageEventBuilder.build()` は毎回 `Instant.now()` を読む。**一度だけ採番・`build()` は純関数**を
  §3 の契約にする。
  ⑥ §8-a の「409 = 冪等に成功」、§8-d / §9 の domain tag の無い hash 式、`originalEventId` 基点の
  replay 式、「二度 repair しても増えない」の曖昧さ、実装増分表の「D-rest は Slice 4 の後」を
  §3 / §6-a の正典に揃えた。identity の式は §3 にのみ置き、他節は参照だけにする。
- v2 — §4 §6 §8 §9 を全面改訂。v1 の採番一体化案・caller 例外案・raw URI QN 案・
  `upload://` 空 input 案・`REPLAYED` 上書き案は**撤回**。撤回理由は各節に残す。
- v2.1 — 7 判断の条件を反映し、endpoint-local snapshot と shell 排除 (§2 §10)、
  file dead letter の durable spool (§8 §9)、sequencer lease/fencing 状態表 (§8)、
  replay generation の CAS 状態機械 (§8)、endpoint 件数・payload size 上限 (§2) を追加。
- v2.2 — 状態機械の内部矛盾を訂正。lease は削除せず generation high-watermark を保持、
  stale `SEQUENCING` の世代付き reclaim 遷移を追加、`VERIFYING` を状態表に完全記載、
  spool の起動影響と file 安全性契約、chunk の sequence 連番要件を**撤回**、
  oversize 時の silent attribute drop を**禁止**、catalog obligation を lineage 専用 service に確定。
  **`sequence` を「sequencer finalization order」と再定義**した (§8 冒頭)。
- v2.3 — external endpoint の repository 境界を閉じ (§2 §7)、
  **logical `processKey` と journal `deliveryId` を分離** (§3)、lease を bootstrap 専用作成 +
  書込み直前再確認 (§8)、catalog obligation を `WAITING_FOR_CATALOG` として projector に接続 (§8)、
  **`DIRECT` を legacy best-effort と明記して release gate を `JOURNALED` に限定** (§8)、
  E-17 の kind 別必須属性と bulk 取得 (§10)、`base64url` が保護ではないことを明記 (§4)。
- v2.3.1 — ID 契約の曖昧さと旧仕様の残存を除去。`deliveryId` を tagged union 化して
  multi-target ORIGINAL を定義 (§3)、v2 経路から `eventKey` を全廃 (§3 §8 §10)、
  lease 欠落時の acquire 禁止と復旧手順 (§8)、typed endpoint は全 mode 共通・
  耐久機構のみ `JOURNALED` 限定 (§8)、状態表と kind 表の残存修正 (§2 §4 §8 §10)。
- v2.3.2 — **実装 (A-1〜A-1f) で確定した訂正を反映**。external stableKey の書式を既存 catalog
  sync に統一し `ExternalAssetIdentity` へ集約 (独自 scheme `cloud://` 等は撤回、§4)、
  snapshot allowlist を実 Atlas schema と機械的に整合 (§2)、ARCHIVE の同一性を `archiveId` に
  訂正 (§10)、D の依存を「A-2 に依存」に訂正、並び順を符号なし UTF-8 バイト辞書順に固定 (§3)、
  表示 URL (`cloudFileUrl`) を stableKey とは**別契約**として sanitize (§4)。
- v2.3.11 — §6-a の**順序・spool identity・収束プロトコル・rollout 範囲**を訂正。
  ① D-rest を 4b の後に置いていたのは逆順 — 直すと決まっている journal/projector 経路へ v2 を
  先に流す期間ができる。`deliveryId` は Slice 1〜3 で確定するので、D-rest は v1/v2 dual・非活性で
  **4a より前に**配布する。② `spoolRecordId` / `payloadDigest` を `LineageCanonicalHash` 上の
  canonical hash として規定 (再試行同一 ID、digest 不一致は上書きせず quarantine、`fact-{64 hex}`)。
  ③ 「一度だけ materialize」は**クラッシュ安全でない** — sidecar に版・generation・`deliveryId`・
  event digest を atomic 永続化 → 決定的 `_id` で create-if-absent → 409 は digest 完全一致のみ
  成功 → ACK、という**収束プロトコル**に置換。重複防止の最終境界は CouchDB の決定的 `_id` と
  digest であり、ローカル mapping ではない。④ membership revision の再提示では TOCTOU が閉じない
  (アプリは最新性を検証できず、取得後の変更も防げない) ため、**v3.3 の規範 rollout は単一 AP
  切替**とし、オンライン多重 AP barrier は control-plane 連携が入るまで**非保証**と明記。
- v2.3.10 — §6-a の barrier に**鮮度と原子性**を入れ、spool を**版非依存**にし、**D の依存順**を
  解いた。ACK を別文書から barrier 文書内へ移し (`bootId` / `binaryDigest` / `expiresAt` 付き)、
  `ACTIVE` への CAS が同一 `_rev` で全条件を検査し `writeSchemaVersion=2` と
  `minReaderSchemaVersion=2` を**同時に**設定する (従来は別書込みで、その間に v1-only reader が
  参加できる窓があった)。membership revision を activation 要求時に**再提示**して bind
  (でないと「確認後・CAS 前の membership 変更」テストが発火しない)。flag 読取不能時は event では
  なく**版非依存 business fact** を spool し、scanner が復旧後に一度だけ materialize して
  `deliveryId` を計算する — これに伴い §8-d の「spool file 名は常に `deliveryId`」を訂正。
  D を **D-spool / D-rest** に割り、A-2 Slice 4a との循環を解消。
- v2.3.9 — §6-a の**前提を撤回して書き直し**。v2.3.8 の契約 1・2 は「旧バイナリが新しい規約を
  守る」ことを前提にしていたが、旧バイナリは既にデプロイ済みでその guard を持たない
  (projector の view は `doc.type === 'lineage_event'` だけで選択し、schema version で分離
  していない)。旧バイナリの排除は**アプリ外の完了条件**とし、membership は推測せず
  `expectedNodeIds` を外から与え、`PREPARING(generation, digest)` → 各新版 AP の ACK →
  `ACTIVE` の二段階 barrier にする。版を `writeSchemaVersion` (可逆) と
  `minReaderSchemaVersion` (単調増加) の二軸に分離。spool readiness (永続 volume + fsync 検証) を
  4a の完了条件に含め、flag 読取不能時は版を推測せず spool へ送る。spool も失敗した場合は
  **repair 不能な残余**として明記。
- v2.3.8 — **§6-a を新設**。A-2 Slice 4 の停止条件が「新 reader が v1/v2 を読めるか」だけで
  片方向だった。危険なのは**旧 AP が v2 を読む**方で、全コードを 1 コミットで変えても
  デプロイが同時でない以上消えない。Slice 4 を 4a (dual reader を全 AP へ配布、writer は v1) と
  4b (全 AP の read capability を確認してから write-version flag を CAS で v2 へ) に分割し、
  v1-only AP の再参加拒否 / stale leader の claim 禁止 / rollback 手順 / 双方向の混在テスト /
  切替窓の欠落回収を契約として固定した。
- v2.3.7 — 壊れた `catalogName` の扱いを確定 (§4)。JSON null / 数値 / object / array / boolean /
  フィールド欠落は、従来 CMIS property ID を出力名に**流用**していた (誰も設定していない名前で
  投影される) が、互換契約が無いので**設定エラー**として当該 1 件のみ除外し WARN + counter。
  disabled mapping は投影しないので**そのまま保持**し、rejection に数えない (admin UI が表示・
  修正できる必要があるため)。
- v2.3.6 — mapping 検査の入口を 1 つに統合 (§4)。v2.3.5 は「3 箇所が同一述語」と書いたが
  **事実ではなく**、複合述語を使っていたのは load だけ、save は 2 つに分割、payload 境界は入力側
  のみだった (自ら指摘した述語分裂の再発)。`rejectionFor(入力ID, 出力名)` が理由 enum を返す
  唯一の入口となり、save / load / payload 境界の 3 箇所がこれだけを呼ぶ。
  `containsKey` は**別規則**として残す — 判定対象が mapping ではなく構築中の entity だから。
- v2.3.5 — custom property mapping の**入力側**も禁止集合で塞ぐ (§4)。v2.3.4 は出力名しか見て
  おらず、`nemaki:cloudFileUrl → legacyCloudUrl` は出力名が予約でも既存属性でもないため両ガードを
  通過し、生 URL が別名で Atlas に載った。`FORBIDDEN_SOURCE_PROPERTY_IDS` を新設し、
  save / load / payload 境界の 3 箇所が同一述語 `isUnusableMapping(入力ID, 出力名)` を使う。
- v2.3.4 — custom property mapping からの復活経路を閉鎖 (§4)。予約名・空白名を **load 時**にも
  拒否 (従来は `saveMappings` のみ = 旧設定 / restore / CouchDB 直接編集 / 破損設定に無効)、
  payload 最終境界で `containsKey` 検査 (`putIfAbsent` は null を上書きするため不可)。
  あわせて「null が既発行値を消す」という A-1g の主張を**撤回** — backend 依存かつ未検証で、
  除去は release gate の live Atlas E2E 課題とする。
- v2.3.3 — v2.3.2 の sanitize 方針を**撤回**。SharePoint の modern sharing link は token を
  **path に置く** (`/:x:/g/…TOKEN`、`CloudDriveResource` 自身が受理する形) ため、保存 URL の
  いかなる変形も secret-free を保証できない。Atlas 永続化境界は保存 URL を**一切運ばない**:
  document `cloudFileUrl` は null、process `targetDescription` は `externalFileId`。provider 別 canonical URL の
  `{provider, fileId}` からの再構成は増分 B (§4)。
scope: v3.3 内で Atlas 連携を完成させるための設計。実装は sign-off 後に A〜E の独立コミットで行う。
関連: [`docs/design/acl-epoch-fencing.md`](acl-epoch-fencing.md) (同じ outbox/cursor の考え方を使う)

実装状況: **A-1〜A-1k、A-2 Slice 1a〜3、producer P-1〜P-3c、D-spool、D-rest-1〜4
(全 slice) が `deps/v3.3-breaking-majors` に実装済み** (型体系・identity 符号化・命名集約・
schema 整合・identity CI / v2 型・read model・sink / admin / projector の版非依存化・
無損失 codec・store read の一斉切替・appendV2 + pre-sink gate / 全 12 producer の
LineageFact 化 (v1 文字列は LegacyV1Projection が verbatim 運搬) / 版非依存 fact spool +
scanner + golden vector 凍結 / §8-a fenced sequencer + bootstrap patch + 実 CouchDB IT /
§8-b v2 遷移 CAS + claim lease + reaper + §8-c 単調 cursor + view schema 分離 (v1-only 6 +
v2 専用 5) + aggregate readiness gate + 無効化済み admin sequencer 入口)。
**production writer は v1 のまま・spool と全 D-rest driver は非活性**
(lineage.drest.enabled 既定 false; v2 branch/reaper/purge-v2/admin POST は全て単一
readiness gate の背後)。残: §2 の属性別上限 + truncate-with-SHA-256 (producer 側 slice)・
`FILE_SHARE_SYNC_UPLOAD` 生成拒否の E2E・4a/4b (§6-a 再 sign-off・E-19/E-20 含む)。
Slice 4 (v2 書込みへの切替) は **§6-a の再 sign-off 待ち**。slice 単位の状態は
「A-2 の分割」の表が正である。
本文の規範記述は実装と同期させており、乖離を見つけたらどちらかが誤りである —
A-1 を再実装しないこと。


---

## 過去に閉じた指摘

以下は、増分 A の各 sign-off 段階で閉じた指摘の一覧です。契約そのものは
[`atlas-lineage-endpoints.md`](atlas-lineage-endpoints.md) 側にあります。

### v2.3.1 で閉じた点

| # | 指摘 | 反映 |
|---|---|---|
| 1 | `deliveryId` が multi-target で定義できない | §3 — **tagged union** 化 (`ORIGINAL` は `canonicalTargetSet`、`REPLAY` は `originalDeliveryId`+`target`+`replayGeneration`、`REPAIR` は `deadLetterId`+`repairGeneration`)。event-level `operationId` を全 v2 event で必須化 (endpoint 側とは別契約)。hash は長さ prefix 付き UTF-8 か canonical JSON、`null` と空文字を区別。endpoint は重複拒否・辞書順・null 拒否。`creationPayloadDigest` の対象を表で固定し可変フィールドを除外。**digest 不一致は通常 emit では 500 を返さず integrity 例外→spool+metric、管理 replay/repair のみ 500**。IT-33〜35 / IT-39 / IT-40 |
| 2 | 旧 `eventKey` 契約の残存 | §3 §8 §10 — 冪等判定を `deliveryId` のみに、event-first `_id` を `deliveryId` 由来に、**spool を `{deliveryId}.json` / `.ack`** に (v2.3.10 で一部訂正: 版が確定していない fact は `fact-{spoolRecordId}.json`、§6-a)、E-13 を `processKey` 表記に修正。`legacyEventKey` は v1 読取・監査専用と明記 |
| 3 | lease 欠落時の acquire が旧仕様 | §8 — acquire は **lease が存在する場合のみ**。不在では作らない。復旧は「durable 管理 flag で全 AP の acquire 禁止 → old worker 停止確認 → `max(event generation)+1`」。**あるいはランダム `leaseToken` を event に stamp** して generation 再利用を無効化。IT-36 / IT-37 |
| 4 | `DIRECT` の線引き | §8 — typed endpoint / canonical QN / artifact / snapshot / cross-repo 検証 / `processKey` は**全 mode 共通**。`JOURNALED` 限定は journal・spool・ordering・verify・obligation・replay・repair。**spool 検査は `JOURNALED` のみ**に訂正。E-18 に positive control を追加。IT-32 |
| 5 | 状態表・kind 表の残存 | §8 — `VERIFYING → FAILED` から semantic mismatch を削除 (`UNPROJECTABLE` と重複)。§10 — `PUBLISHED \| FAILED \| UNPROJECTABLE` に更新。§4 — stableKey 表の `FILESYSTEM_PATH` を `EXTERNAL_ASSET (filesystem)` に。§2 — `waitingTaskKeys` 全件解決を再開条件とし `waitingSince` は往復でリセットしない。IT-38 |

IT は 40 件になった。

---

### v2.3 で閉じた 7 点

| # | 指摘 | 反映 |
|---|---|---|
| 1 | external endpoint の repository 境界の矛盾 | §2 — `repositoryId` を**全 kind で必須**に。§7 の検査対象に external / cloud / cold を含め、`endpoint.repositoryId` と canonical QN 内の `{repositoryId}` の**両方**を検査。nullable は `objectId` のみ。`FILESYSTEM_PATH` は独立 kind にせず `EXTERNAL_ASSET` に統合。IT-30 |
| 2 | logical identity と delivery identity の混同 | §3 — **`processKey` (Atlas Process QN 用、chunk 座標含む) と `deliveryId` (journal `_id` / spool file 名、`deliveryKind`+`target`+`replayGeneration`+`chunkIndex` 含む) を分離**。冪等判定は `deliveryId`。409 は payload digest 完全一致のときのみ成功扱い。IT-27 / IT-28 |
| 3 | lease fencing が期限切れ直後の old leader を止めない | §8 — lease は **bootstrap patch でのみ作成**、運用中の欠落は fail-closed (`LEASE_MISSING`)、復旧は既存 event 中の最大 generation より大きい値で管理復旧。**claim / reclaim / counter allocate / finalize の直前ごとに** owner・generation・未期限切れを再確認。renew 失敗と期限切れ検出は**一方向 latch**。IT-25 / IT-26 |
| 4 | catalog obligation と projector が未接続 | §2 — **`WAITING_FOR_CATALOG`** を独立 target 状態として追加し §8 の状態表にも記載。target 別 task、retry 非消費、`RESOLVED` 後の再開、`SNAPSHOT_INCOMPLETE` の terminal 化、複数 event が同一 task を待つ場合の ACK 契約 (task 側 1 回 + 逆引き index) を明記。IT-29 |
| 5 | `DIRECT` mode に新保証が適用されない | §8 — **`JOURNALED` を唯一の reliable / supported mode** と宣言。`DIRECT` は legacy best-effort と明記し、本増分の新機能を実装しない。**Atlas-enabled release gate は `JOURNALED` のみ**を対象とし、`DIRECT` は E-18 のみ。IT-32 |
| 6 | E-17 と terminal 状態の不整合 | §10 — `CLOUD_OBJECT` は `provider`、`COLD_STORAGE` は `storageClass` に**必須属性を分離**。**bulk 取得か上限付き並列 + 全体 deadline + endpoint 集合の完全比較**を必須契約に。`UNRESOLVED_OVERSIZE` は `UNRESOLVED(reason=OVERSIZE)` に統一。決定的 semantic mismatch は再試行せず **`UNPROJECTABLE` (terminal)**。IT-31 |
| 7 | `base64url` は保護ではない | §4 — 可逆であることを明記。stableKey に認証情報・署名 URL・query・fragment を入れない、Atlas 閲覧権限の限定、spool / bundle の encryption at rest、「protected」= ログ非出力であって秘匿化ではない、秘匿性が要るなら HMAC/SHA-256 QN への移行が別途必要 (本増分では扱わない) |

IT は 32 件、E2E 受入は 18 件になった。

---

### v2.2 で閉じた 6 点

| # | 指摘 | 反映 |
|---|---|---|
| 1 | lease 削除で generation high-watermark が消える | §8 — release は `owner=null` / `expiresAt=過去` の CAS。`generation` 維持。**削除は拒否**。IT-17 / IT-18 |
| 2 | stale `SEQUENCING` を回収する遷移がない | §8 — `SEQUENCING(G_old) → SEQUENCING(G_new)` の reclaim 遷移を明記。crash 表の「`UNSEQUENCED` のまま」も `SEQUENCING(G_old)` に訂正。IT-19 / IT-20 |
| 3 | `VERIFYING` が状態表にない | §8 — 5 遷移を状態表に追加。lease renew、`verifyMaxAge` (既定 10m)、reaper の条件、metric。verify 項目は **kind 別**に分離 (artifact 系に `objectId` は無いため)。IT-21 |
| 4 | spool の起動影響・fsync・path・node 運用・bundle 署名 | §8 — Core は落とさず lineage subsystem を `NOT_READY`。strict は明示設定時のみ。path safe encode / `0700`・`0600` / NOFOLLOW / 同一 dir temp / file+parent fsync / digest 冪等 / サイズ上限 / ACK fsync。**集約 endpoint は v3.3 では実装しない**。bundle は HMAC + keyId + 期限 + nonce + 上限 + zip bomb/traversal 拒否 + 監査。IT-22 |
| 5 | chunk 連番要件と silent attribute drop | §2 — **連番要件を撤回**。再構成は `operationId`/`chunkIndex`/`chunkCount`。属性の事前上限、optional は truncate+hash、必須値は落とさない、単独超過は `UNRESOLVED_OVERSIZE`。**silent drop 禁止**。IT-23 |
| 6 | catalog obligation の実装形態 | §2 — **`LineageCatalogReconciliationService` を lineage 専用に新設**。task key / 4 state / 4 outcome (`SOURCE_EXISTS` / `SOURCE_PURGED` / `SOURCE_ERROR` / `SNAPSHOT_INCOMPLETE`)。既存 service とは CAS task/lease/backoff の低レベル部品のみ共有。IT-24 |

併せて **`sequence` を「sequencer finalization order」と再定義**した (§8 冒頭)。
domain commit の全順序も spool を跨ぐ因果順序も保証せず、`occurredAt` は表示・監査用で
fencing の根拠にしない。厳密な domain commit 順序は業務 DB と同一トランザクションの
outbox が要るため本増分の範囲外、と明記した。

IT は 24 件になった。

---

### v2.1 で閉じた 6 点

| # | 指摘 | 反映先 |
|---|---|---|
| 1 | endpoint-local snapshot と shell 排除条件 | §2 (kind 別 allowlist / catalog reconciliation obligation) / §10 E-17 verify 契約 |
| 2 | file dead letter の durable spool / import / ACK | §8 「durable spool」。既存 sink が SLF4J logger である事実を明記 |
| 3 | sequencer lease / fencing / crash 状態表 | §8 「sequencer lease / fencing 状態表」+ old leader 復活系列の証明 + crash 再開規則 |
| 4 | replay generation の CAS 状態機械 | §8 「replay generation の CAS 状態機械」 |
| 5 | E-1 / E-2 / E-9 の表記修正 | §10 (import artifact 追加 / 17 種に統一 / `UNRESOLVED` へ修正) |
| 6 | endpoint 件数・payload size 制限 | §2 「件数・サイズ上限」(1,000 / 1 MiB / chunking) |

7 判断の条件も反映済み: proxy の `sourceState=PURGED` と GC 条件 (§3)、
`operationId` のサーバー発行・公開 API 非必須 (§3)、`idempotencyKeyVersion` の永続化と
v1 replay が既存 Process を更新しないこと (§3)、counter 巻き戻し復旧手順 (§8)、
token の opaque / bind / 1回限り / CAS / TTL 設定可能 / CSRF・監査 (§9)、
E-17 の bounded poll と `VERIFYING` 状態 (§10)、`FILE_SHARE_SYNC_UPLOAD` の RESERVED 化 (§1 §10)。

---

### 実装着手 sign-off の前に

この文書の内容で A〜E に着手してよいか。着手後も各増分の完了時にレビューを受ける。

未確定点は残っていない。v2.1 で残していた catalog reconciliation obligation の実装形態は
lineage 専用 service に確定した (§2)。
