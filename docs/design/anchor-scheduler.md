# 設計 — 台帳 checkpoint の自発封入（TSA / OTS スケジューラ）と管理画面

2026-09-28。計画 [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md)
§20 トラック C の C-3（スケジューラ）と C-4（管理画面）。旧「3.4.1」に置いていた機能を、
2026-09-28 のオーナー判断で 3.4.0 に入れる。**製品コードは 2026-09-28 に入った**（C-3 / C-4）— 設計から動いた点は §10。
anchor の段と受領の意味は [`p2-0-anchor-targets.md`](p2-0-anchor-targets.md) が正典で、この文書は
**いつ送るか**だけを足す。「現行コードの事実」は 2026-09-28 に読んだ行番号（着手時に再確認）。

---

## 1. 現行コードの事実（2026-09-28）

### 1.1 手動 API だけがある

`rest/controller/AnchorController.java`（`@RequestMapping("/v1/admin/anchor")` :63、全部 admin 限定、
bean が無ければ 503）:

| Method / path | 呼ぶもの |
|---|---|
| `POST /core/api/v1/admin/anchor/checkpoint-and-anchor?repositoryId` | `ledgerService.closeCheckpoint(repositoryId, now)`（:122）→ `ledgerStore.latestCheckpoint`（:155）→ `anchorService.anchor(checkpoint)`（:199） |
| `POST …/retry-unsettled?repositoryId` | `ledgerStore.latestCheckpoint` → `anchorService.retryUnsettled(latest)`（:250） |
| `POST …/upgrade-pending?repositoryId&limit=100` | `anchorService.upgradePending(repositoryId, limit)`（:279） |
| `GET …/status?repositoryId` | 台帳・receipt の状態（:312-532） |
| `GET …/long-term-validity?repositoryId&asOf` | `validityService.assess`（:678） |

`closeCheckpoint` の戻りは `status` = `success` / `noop`（新しい entry が無い）/ `error`（未確定の
backlog などで封じられない）で、controller は `noop` を 200、`error` を 409 で返し、封入は
`success` のときだけ（:103-208）。`AnchorService.anchor` の `refusedReason`（`evidence/anchor/AnchorService.java:209-261`）は**送る前**と**送った後**に分かれる。送る前（何も送っていない）: checkpoint が null（:209-212、`refusalFor` の外の独立した拒否）、そして `refusalFor`（:574-603）の 5 つ — Merkle root が無い、自分の内容に hash しない（改変か別版）、台帳が未配線、台帳の head が読めない、
台帳が checkpoint の先へ進んでいる。送った後の 2 つ: **receipt を保存できなかった**（`lost`）と、**構成された段が全部 FAILED**。`lost` は `persist()` が false を返した段の集合で（:494-530 — 保存の例外は FAILED 以外の receipt を全部
`lost` にする）、構成された段の CONFIRMED / PENDING なら commitment は成立しており再送は回復ではなく新しい commitment を作る。
**未構成の段の `NOT_CONFIGURED` receipt の保存失敗も `lost` に入る**（外部の commitment は無い — c35 Codex P3）。

### 1.2 定期実行は意図して無い

core/src/main に `@Scheduled` / `TaskScheduler` / Quartz / `Timer` は無く、`ScheduledExecutorService`
を持つ 18 クラス（`LineagePurgeScheduler`、`RetentionScheduler`、`IngestSchedulerService`、
`LeaderElection` …）のどれも `AnchorService` / `closeCheckpoint` / `retryUnsettled` / `upgradePending`
を呼ばない。呼び手は `AnchorController` の 4 か所だけ。無いことは意図で、
`AnchorController.java:51-54`、`AnchorService.java:292-301`、`p2-0-anchor-targets.md:251-255, 354-365, 402`
に理由が書いてある（頻度＝未封入の窓であり、運用者が決める）。`anchor.schedule` という語は
リポジトリのどこにも無い。

### 1.3 送り先は起動時に固定される `@Value`

`rest/purview/anchor/AnchorWiringConfig.java`。**`PropertyManager` を通らず、nemaki_conf は読まれない。**
`nemakiware.properties` にも無い（Spring placeholder — properties file、system property、環境変数）。

| キー | 読む行 | 既定 |
|---|---|---|
| `anchor.opentimestamps.sidecar.url` | :57 | 空（段 2 off） |
| `anchor.rfc3161.tsa.url` | :61 | 空（段 3 off） |
| `anchor.rfc3161.policy.oid` | :68 | 空 |
| `anchor.rfc3161.accreditation` | :76 | 空（`NONE`） |
| `anchor.rfc3161.trust-anchor.path` | :84 | 空（PEM の path） |
| `anchor.rfc3161.revocation.collect-at-issuance` | :95 | false（R65） |

**PEM の path が設定されていてファイルが無いと起動を拒む**（`loadTrustAnchor` :135-154）。しかも
`rfc3161AnchorTarget()` bean（:118）が無条件に呼ぶので、**`tsa.url` が空でも**拒む。

段の on / off: 段 2 は `sidecarUrl != null`（空文字は null に落とす。`OpenTimestampsAnchorTarget.java:77-90`）、
段 3 は `tsaUrl != null`（`Rfc3161AnchorTarget.java:152, 163-165`）。段 1（catalog）は
`CatalogAnchorPublisher` bean が core に無いので**常に off**（`CatalogAnchorTarget.java:73-78`、
`AnchorWiringConfig:161-169`）。未構成の段は `NOT_CONFIGURED` の receipt になる（`AnchorService:533-537`）。

TSA と OTS sidecar への POST は素の `HttpURLConnection` で、SSRF の検査も pin も無い
（`Rfc3161AnchorTarget.java:476-477`、`OpenTimestampsAnchorTarget.java:222-223`）。**だから送り先を
管理画面から書き換え可能にしない**（§4）。

### 1.4 leader election

`rest/purview/journal/LeaderElection.java`。`isLeader(String role)`（:77-87）は nemaki_conf の
`lineage_leader:<role>` 文書への CAS。**election が無効なら常に true**。heartbeat スレッドは
**起動時に有効だったときだけ**始まる（:43-63）ので、稼働中に有効へ切り替えると leader が heartbeat を
更新せず、TTL ごとに leader が入れ替わる（:135-137）。設定は `lineage.leader-election.enabled /
heartbeat-seconds / ttl-seconds`（`LineageConfig.java:251-258`、管理画面の lineage 群から変更可）。
利用者は `LineageProjectionLoop`、`LineagePurgeScheduler`、`SearchIndexReconciliationScheduler`、
`AclEpochScanScheduler`、`IngestSchedulerService`、`CloudDirectorySyncScheduler`、`RetentionScheduler`。

### 1.5 OTS sidecar

`docker/docker-compose-simple.yml` の `ots:`（:175、`profiles: [anchor]` :179-180、`127.0.0.1:8082:8082`
:183、`OTS_CALENDARS` :186）。**simple compose だけ**にあり、本番 compose には無い。どの compose も
`anchor.opentimestamps.sidecar.url` を設定していない。`AnchorWiringConfig.java:56` の javadoc の例は
`http://ots:8080` だが sidecar は 8082 で聴く（`docker/ots/Dockerfile:24-25`）— 実装時に直す。

### 1.6 設定の先後

`util/PropertyManager.java` の `isAdminManagedDynamicKey`（:121-128）は `cloud.auth.` / `cloud.drive.` /
`sso.` / `oidc.` / `saml.` の 5 prefix。この prefix のキーだけ **nemaki_conf の保存値が system
property より先**に読まれる（:41-57）。それ以外は system property → 環境変数 → nemaki_conf →
properties file（:60-98）。管理画面の設定の書き手は `PUT /core/api/v1/admin/integration-settings/<group>`
（`IntegrationSettingsController.java`、群ごとに固定 path、キーは allow-list、`handleUpdate` :656-729 →
`IntegrationSettingsService.writeSettings` :212-264）。

---

## 2. 何を足すか

1. **`AnchorRunService`**（新設、`evidence/anchor`）: `AnchorController` の 3 つの POST の本体
   （封じて送る／再送／upgrade）を controller から**そのまま**移し、controller とスケジューラの
   両方がこれを呼ぶ。HTTP の status への写像は controller に残す（既存の応答は変えない）。
   「駆動は既存 API の内部呼び出し」とは**この 1 つのメソッドを呼ぶこと**で、自分に HTTP を打つことではない。
2. **`AnchorScheduler`**（新設）: tick ごとに §3 の規則で `AnchorRunService` を呼ぶ。
3. **設定キー `anchor.schedule.*`**（§4）: リポジトリごと、nemaki_conf、管理画面から変更。
4. **`GET / PUT /core/api/v1/admin/anchor/schedule?repositoryId`**（`AnchorController` に追加、§5）。
5. **管理画面「証拠と時刻証明」**（§6）。

変えないもの: 段の実装、receipt の意味、`closeCheckpoint` の拒否条件（未確定 backlog があるあいだは
封じない — そのまま）、送り先のキーとその先後（system property のまま）。

---

## 3. 発火の規則

**時間と件数の OR。** 時間だけだと忙しい日は窓（未封入の期間）が伸び、件数だけだと暇な日に少数が
残り続ける。

### 3.1 用語

- **封入済みの上端 A**: 構成された段のうち **1 つ以上で受領が保存されている**（TSA は `CONFIRMED`、
  OTS は `PENDING` か `CONFIRMED`）最新の checkpoint の `toSequence`。`ledgerStore.latestCheckpoint`
  から `checkpointEndingBefore` で遡り、各 checkpoint に `receiptStore.forCheckpoint(domain, toSequence)`
  を当てて最初に見つかったもの。1 つも無ければ A = 0。遡りは上限 20 checkpoint で止め、届かなければ
  「A は分からない」= **その tick は封入しない**で status に `UNAVAILABLE` を出す（読めなかったを
  0 と言わない）。
- **未封入の件数** = `ledgerStore.highestSequence(domain) − A`。
- **最古の未封入の時刻** = `ledgerStore.range(domain, A+1, A+1, 1)` の 1 件目の `occurredAt`。
  entry が読めなければ「分からない」（上と同じ扱い）。
- **前回の封入時刻** = `latestCheckpoint.createdAt`（新しい状態は持たない。再起動や leader 交代を
  跨いでも同じ値が読める）。

### 3.2 tick（60 秒固定、ノードごと）

```text
for each repositoryId（既存の repository 設定にあるもの）:
  s = anchor.schedule.* を PropertyManager から読む（毎 tick。cache しない）
  if !s.enabled                          → idle（理由: disabled）
  if s.intervalMinutes が空              → idle + WARN（保存側でも拒むが、-D で来た値の穴を塞ぐ）
  if 構成された段が 0                     → idle（理由: no rung configured。enabled=true でも動かない）
  if !leaderElection.isLeader("anchor")  → idle（理由: not leader）
  if !ledgerStore.isActive()             → idle（理由: ledger inactive）
  A / 件数 / 最古 を読む。読めなければ     → idle + status UNAVAILABLE
  due = (件数 ≥ 1 かつ 最古からの経過 ≥ interval)
        or (s.maxUnanchoredEntries があり 件数 ≥ それ)
  if !due                                → upgrade / retry の timer だけ見る（下）
  if now − 前回の封入時刻 < s.minInterval → idle（理由: min interval）
  AnchorRunService.checkpointAndAnchor(repositoryId) を呼び、結果を status に記録
    success  → 何もしない（次の tick で件数 0 になる）
    noop     → 記録だけ（件数 ≥ 1 なのに noop なら WARN — 台帳と checkpoint の読みが食い違っている）
    error    → 記録。次に試すのは minInterval 後（拒否を 60 秒ごとに繰り返さない）
    refused  → 記録。理由で分ける（AnchorService の腕ごと。手順は着手時に確定）:
               送る前の拒否（null checkpoint／root 無し／自分の内容に hash しない／台帳未配線／head が読めない／台帳が進んだ）は
               何も送っていないので、次の tick で再判定するだけ（「台帳が進んだ」は次の checkpoint で解消する）。
               全段 FAILED なら retryUnsettled の timer に任せる。lost に構成された段の receipt が含まれるなら
               再送しない — 再送は回復ではなく新しい commitment を作る。lost が NOT_CONFIGURED の保存失敗だけなら
               store の障害として記録し、次の tick の判定に任せる
```

- **`upgrade-pending`**: 段 2（OTS）が構成されていて、`receiptStore.pending(domain, 1)` が空でないとき、
  `upgradeInterval`（既定 60 分）ごとに `AnchorRunService.upgradePending(repositoryId, 100)`。
  封入の interval とは**別の timer**（同じ周期にする理由が無い。calendar の確定は数時間単位）。
- **`retry-unsettled`**: `retryUnsettledInterval` が設定されているときだけ、その周期で
  `AnchorRunService.retryUnsettled(repositoryId)`。**下限 60 分**（短い周期は保存を拒む — 失敗した TSA へ
  毎分買いに行かない）。
- 空 span（件数 0）は `closeCheckpoint` が `noop` を返すので、due にならない（件数 ≥ 1 を条件に含む）。
- **同じ tick で 2 リポジトリ以上が due なら順に送る**。費用はリポジトリ数ぶん掛かる（§7）。

### 3.3 leader とレプリカ

- role は `"anchor"`。`isLeader` は election が無効なら常に true なので、**複数レプリカで election を
  無効にしたまま enabled にすると N 台が同じ根を送る**。これは製品では止められない（レプリカ数を知る
  手段が無い）。runbook と管理画面に書く: 「複数レプリカでは `lineage.leader-election.enabled=true`
  にすること」。N 台が同時に `closeCheckpoint` を呼んだときの後段は既存の CAS 次第で、二重 stamp
  （同じ根に TSA を 2 度買う）は起こりうる — §8 の限界 1。
- 稼働中に election を有効にすると heartbeat が始まらない既存の癖（§1.4）は、この機能が受ける。
  実装時に `LeaderElection` の heartbeat 開始を「有効になったとき」へ直せるなら直す（別バッチでも可）。

---

## 4. 設定キー

### 4.1 頻度 — `anchor.schedule.*`（新設、リポジトリごと、管理画面から変更）

キー名の単位は既存の流儀（`lineage.leader-election.heartbeat-seconds` / `backlog.max-retry-age-hours`）
に合わせて**分**を suffix に書く。

| キー | 既定 | 意味 | 保存時の検査 |
|---|---|---|---|
| `anchor.schedule.enabled` | `false` | true でも段が 0 なら動かない（§3.2） | `true` にするなら `interval-minutes` が必須 |
| `anchor.schedule.interval-minutes` | 空（必須） | 最古の未封入からの経過でこの分に達したら封入 | 5 以上 |
| `anchor.schedule.max-unanchored-entries` | 空（任意） | 未封入がこの件数に達したら封入 | 1 以上 |
| `anchor.schedule.min-interval-minutes` | `5` | 前回の封入からこの分は送らない（バーストで TSA を連続購入しない） | 1 以上、`interval-minutes` 以下 |
| `anchor.schedule.upgrade-interval-minutes` | `60` | OTS の `upgrade-pending` の周期 | 5 以上 |
| `anchor.schedule.retry-unsettled-interval-minutes` | 空（任意） | FAILED の再送の周期 | 60 以上 |

提案値（ヒント。**黙って課さない**）: 封入は 1 日 1 回（`interval-minutes=1440`）／ドメイン、
upgrade は 1 時間。1 回の封入で買う TSA token は構成された段ごとに 1 つ。

**置き場と先後（2026-09-28 オーナー決定）**: `anchor.schedule.` を `PropertyManager.isAdminManagedDynamicKey`
の prefix に**足す** — nemaki_conf の保存値が `-D` / 環境変数より**先**に読まれる（`sso.` と同じ規則）。
つまり管理画面で一度保存すると `-D` は効かなくなる。この落とし穴は CLAUDE.md の既存の項
（「`sso.` / `oidc.` / … は `-D` も ENV も効きません」）に **`anchor.schedule.` を足して**書く（実装時）。
読みは `propertyManager.readValue(repositoryId, key)`（リポジトリ値 → 全体値）。未設定のリポジトリは
走らない（初期はリポジトリごと。全体 1 つの間隔は入れない）。

### 4.2 送り先 — 既存のまま（system property が勝つ）

`anchor.rfc3161.*` / `anchor.opentimestamps.sidecar.url` は**変えない**。`@Value` のまま起動時固定で、
管理画面は**表示だけ**（§6）。理由: 送り先はデプロイの信頼境界であり、POST に SSRF の検査が無い
（§1.3）。表示にも PEM の中身は出さない（path の有無と、読めているかだけ）。

---

## 5. API

`AnchorController` に追加（admin 限定、`CsrfInterceptor` を自動で通る）:

```
GET /core/api/v1/admin/anchor/schedule?repositoryId=bedroom
PUT /core/api/v1/admin/anchor/schedule?repositoryId=bedroom   body: {"anchor.schedule.enabled":"true", …}
```

GET の応答:

```json
{
  "repositoryId": "bedroom",
  "settings": {"anchor.schedule.enabled": "true", "anchor.schedule.interval-minutes": "1440", …},
  "effective": {"enabled": true, "intervalMinutes": 1440, "minIntervalMinutes": 5, …},
  "rungs": [{"kind": "RFC3161", "configured": true}, {"kind": "OPENTIMESTAMPS", "configured": false}, {"kind": "CATALOG", "configured": false}],
  "destinations": {"tsaUrl": "https://…", "policyOid": "…", "trustAnchorConfigured": true, "otsSidecarUrl": null},
  "runtime": {
    "leader": true, "nodeId": "…", "leaderElectionEnabled": false,
    "lastTickAt": "…", "lastSealAttemptAt": "…", "lastOutcome": "success|noop|error|refused|idle",
    "lastIdleReason": "not leader", "nextEligibleAt": "…",
    "unanchored": {"count": 12, "oldestAt": "…", "anchoredUpTo": 4711, "status": "OK|UNAVAILABLE"}
  },
  "limits": "…（AnchorController の STATUS_LIMITS と同じ文）"
}
```

PUT は §4.1 の検査を全部通してから `IntegrationSettingsService.writeSettings` と同じ経路
（repositoryId 付きの configuration 文書）で書き、次の tick から効く（保存に「再起動が要る」を残さない）。
検査に落ちたら 400 で、どのキーがなぜかを返す。`enabled=true` で `interval-minutes` が空は 400。
既存の `GET /status` は変えない（管理画面は両方を呼ぶ）。

「読めなかった」は「無い」と同じ値にしない: `runtime.unanchored.status = UNAVAILABLE` のとき
`count` は出さない（0 を出さない）。

---

## 6. 管理画面（C-4）

「連携設定」（取込・ディレクトリ同期）には入れない。**管理 → 証拠と時刻証明**（i18n
`navigation.evidenceAnchoring`）を新設し、`App.tsx` で `AdminRoute` に包む（`/integration-settings` が
包んでいない理由は委譲タブのため — この画面に委譲は無い）。メニューは `Layout.tsx` の admin 群に足す。

画面の要素:

- 有効／無効のスイッチ（**間隔が空のままでは保存できない**。保存ボタンが無効になり理由を出す）
- 間隔・件数上限・最短間隔・upgrade 間隔・再送間隔（分）。検査の失敗は欄の横に出す
- 段の状態: `NOT_CONFIGURED` / 最後の `CONFIRMED` / `PENDING` / `FAILED` の時刻、未封入の件数と最古の時刻
  （`UNAVAILABLE` は「読めていない」と出す。0 と出さない）
- 送り先の表示: TSA URL・policy OID・trust-anchor の**有無**（PEM の中身は出さない）、
  OTS sidecar URL（空なら「段 2 は使わない — このノードに sidecar URL が無い」と明記）。
  **編集欄は無い**（system property で設定する旨と、その理由の 1 行）
- 「今すぐ 1 回」= 既存 `POST /checkpoint-and-anchor`（結果をそのまま表示）
- 最後の実行時刻と結果、leader かどうかと nodeId、election が無効で複数レプリカのときの注意文
- 呼び出しは新しい `core/src/main/webapp/ui/src/services/anchorSchedule.ts`（`AuthService.getAuthHeaders()`）
- Playwright: `tests/admin/evidence-anchoring.spec.ts` — 間隔空で保存できない、保存後に GET が同じ値を
  返す、段 0 のとき「動かない」と出る、を UI から

OTS sidecar の compose `profiles: [anchor]` は既定起動にしない。

---

## 7. 名乗ること・名乗らないこと

名乗る: 「構成した段（TSA / OTS）へ、設定した時間または件数の条件で、leader ノードが台帳の
checkpoint を封じて送る。送った結果と未封入の件数を管理画面で読める」。

名乗らない（計画 §4.2 の禁じ手は文言のまま避ける）:

- 頻度の設定が**未封入の窓の上限**であること — 送れなかった分（error / refused / not leader）の間、
  窓は伸びる。上限ではなく「この条件で試みる」
- 独立 verifier が P3 以上で `VERIFIED` に届くこと（この版の CLI は P3〜P5 で構造的に exit 3。3.4.0 の残件のまま。
  P2 は RFC 3161 token が根を commit していれば `VERIFIED` に届く — 2026-09-28 に訂正、この行は P2 と書いていた）
- 発行時失効収集の on（R65。既定 `false` のまま）
- 文書 1 件ごとの TSA（段 3 は台帳 checkpoint）
- 既定で公開 calendar へ送ること（OTS は sidecar URL を置いたときだけ）
- 提示 checkpoint が最新であること（計画 §18 の「必ず併記」と同じ）

---

## 8. 既知の限界（実装時に正典の残件表へ行として写す）

1. **election 無効 × 複数レプリカ**で二重 stamp が起こりうる（§3.3）。製品は止められず、runbook の規則。
2. **PEM 欠落は `tsa.url` が空でも起動を拒む**（§1.3、既存）。スケジューラの導入で「TSA を切っているのに
   起動しない」の問い合わせが増えうる。直すなら `rfc3161AnchorTarget()` で `tsaUrl` が空なら読まない —
   別判断（起動失敗という fail-closed を弱める向きなので、この設計では触らない）。
3. **`anchor.schedule.` を admin-managed にした結果、保存後は `-D` が効かない**（§4.1）。意図した先後だが
   落とし穴なので CLAUDE.md に書く。
4. **送り先は画面から変えられない**（§4.2、意図）。TSA / OTS の POST に SSRF 検査が無いあいだは変えない。
5. OTS の javadoc の port（8080）と sidecar の実際（8082）の食い違い（§1.5）— 実装時に直す。
6. 稼働中に leader election を有効にすると heartbeat が始まらない（§1.4、既存）。

---

## 9. 錠と control（C-3 / C-4 で書く。ID は着手時に割り当てる）

| 錠（自分の assertion で落ちる形） | control が壊すもの |
|---|---|
| `enabled=false` なら tick は `AnchorRunService` を呼ばない（`verify(never())`） | enabled の検査を外す |
| 段が 0 なら enabled でも呼ばない | 段の数の検査を外す |
| leader でなければ呼ばない（`isLeader("anchor")` が false の stub） | leader の検査を外す |
| `interval-minutes` が空なら呼ばない、かつ WARN を 1 度出す | 空を 0 分と読む |
| 時間だけで due（件数上限なし）／件数だけで due（時間未到達）— 両腕を別々に | OR を AND にする |
| `min-interval` 内は due でも呼ばない | min-interval の検査を外す |
| A が読めない（receipt store が例外）なら呼ばず `UNAVAILABLE`、`count` を出さない | 例外を A=0 に潰す |
| 件数 0 なら呼ばない（noop を作らない） | 件数 ≥ 1 の条件を外す |
| `retry-unsettled` は 60 分未満を保存で拒む（400） | 下限を外す |
| `upgrade-pending` は段 2 が構成されていないと呼ばない | 段 2 の検査を外す |
| PUT の保存値が次の tick で読まれる（tick 内で `readValue` を呼び、cache しない） | 起動時に 1 度読んで固定する |
| 送り先のキーは PUT で受け付けない（400）、GET に PEM の中身が無い | allow-list に `anchor.rfc3161.tsa.url` を足す |
| controller の 3 つの POST は移した後も同じ HTTP status を返す（既存の錠を維持） | — |

錠は本番の入口（`AnchorScheduler.tick` と `AnchorController`）を通す。`AnchorRunService` だけを直接叩く
錠で済ませない（[[lock-the-entry-point-not-the-collaborators]] の規則）。

---

## 10. 実装で設計から動いた点（2026-09-28、C-3 / C-4）

- **封じる domain は repository ＋ `record-content`**。§3.2 は「for each repositoryId」と書いていたが、書き出す package の
  証拠 section が読む checkpoint と anchor は `record-content` domain（E1 の記録内容の状態、全リポジトリ共通）にある
  （`RecordContentStateRecorder.DOMAIN`、`EvidenceBundleAssembler`）。repository だけを回すと、計画 §0 が名指す連鎖は
  手押しのまま残る。設定の API と画面は `repositoryId=record-content` を受ける（既存の手動 API と同じく id は台帳の domain）。
- **設定の読みは PropertyManager の cache を通らない**。§4.1 は `propertyManager.readValue(repositoryId, key)` と書いていたが、
  (1) configuration cache は eternal で、別レプリカの保存はそのレプリカの cache しか無効にしない（leader が別ノードだと
  「次の tick で効く」が偽）、(2) cache pool は repository ごとで、未登録の id（`record-content`）は nemaki_conf の全体設定と
  **同じ 1 行を共有する** — その id で読むと全体設定を返すか、上書きしうる。domain 自身の保存値は tick ごとに nemaki_conf から
  直接読み（`IntegrationSettingsService.readRepositorySettings`）、無いキーだけ全体の値（`PropertyManager.readValue(key)`:
  全体の nemaki_conf → `-D` → 環境変数 → properties）に落ちる。**保存値が読めなければその tick は何もしない**（保存された
  「無効」を `-D` の「有効」で置き換えない）。`anchor.schedule.` は admin-managed prefix に足した（全体の保存値も `-D` に勝つ）。
- **封入済みの上端 A**: 時刻証明になる受領（RFC 3161 の `CONFIRMED`、OpenTimestamps の `PENDING` / `CONFIRMED`）が
  1 つでもある最新の checkpoint。いま構成されている段かどうかは問わない（過去に得た token は今も証拠）。何も無ければ −1
  （sequence は 0 始まりなので、§3.1 の「A = 0」は 1 件少なく数える）。**20 checkpoint 遡って見つからなければ UNAVAILABLE に
  せず下限にする** — 件数は「以上」、最古の時刻は遡った最古の checkpoint の封入時刻（entry は封じる checkpoint より前に
  追加される）で上から押さえる。§3.1 のとおり止めると、anchor 無しで封じてきた配備は二度と封じなくなる（過剰拒否）。
  受領が読めない（例外・`lastQueryFailed`・読めない行）ときは従来どおり UNAVAILABLE で、件数を出さない。
- **due でも、未封入の entry が全部すでに封じた checkpoint の中なら呼ばない**。そこで `closeCheckpoint` を呼んでも noop に
  なるだけで、その checkpoint の anchor を取り戻す道は retry-unsettled（自身の周期、または運用者）。これで §3.2 の
  「件数 ≥ 1 なのに noop なら WARN」は本当の食い違い（別の書き手が先に封じた等）のときだけになる。
- **error の後は最短間隔だけ待つ**（`nextEligibleAt`、ノードのメモリ）。封じられなかった場合 checkpoint の時刻は動かないので、
  §3.2 の最短間隔の判定だけでは毎分繰り返す。
- **再送の保留**: 封入の anchor でも再送でも、commitment を作ったのに受領を保存できなかった段があれば（`Outcome.unstored`、
  このバッチで `AnchorService` に足した — HTTP の応答は変えていない）、その checkpoint の自動再送を止める（ノードの
  メモリ。再起動で消える — 残件 R124）。同じ tick で封じた直後は再送しない（失敗した段を 2 度叩かない）。
- **WARN は理由が変わったときだけ**（毎分同じ行を書かない）。
- **PUT の検査**: 送った値をいまの値に重ねて、scheduler と同じ規則（`AnchorScheduleSettings.parse`）で検査する。空にしたキーは
  「未設定」として判定する（空にすると現れる全体の値が有効かどうかは仮定しない — 保守的）。知らない domain は 400。
  保存の失敗は #1410 の規則（固定文 + `incidentId`）。
- **画面**（C-4）: `管理 → 証拠と時刻証明`（`/evidence-anchoring`、`AdminRoute`）。対象は「このリポジトリ」と `record-content`。
  段 0 のときは「有効にしても動かない」と出し、受領が読めない件数は「読めていません」と出す（0 と出さない）。送り先は表示だけ
  （TSA URL は `@` を含めば表示しない — その送り先は拒む、R132。PEM は有無だけ）。「今すぐ 1 回」は既存の `POST /checkpoint-and-anchor`。**その結果・保存する受領・送り先のログも同じ規則** — 受領の `tsaUrl` / `sidecarUrl` とログが URL をそのまま持ち、画面は結果の JSON をそのまま出していた（c41、Codex P1、正典 R129）。規則は送り先の側（`Rfc3161AnchorTarget.withoutUserInfo`）に置き、要求だけが設定どおりの URL を使う。
- **§1.5 の port**: `AnchorWiringConfig` の javadoc の例を sidecar の実際（8082）に直した。
- **c41 の確認レビューで動いた点**（2026-09-29）: upgrade の判定は、保留の一覧が空でも store が decode できない行を落としていれば「保留なし」でなく UNAVAILABLE と出す（`upgradePending` と同じ規則）。domain ごとの設定の読み書き（`readRepositorySettings` / `writeRepositorySettings`）と、全体の設定が読めない腕に、mock を通さない錠を置いた（`RepositorySettingsAreReadFailClosedTest`、`AnchorSchedulerTest#anUnloadedGlobalConfigurationIsUnavailable`）。運用の窓 5 件は正典 R130（P3）に記録した。**c42**: URL を組む所で parse の失敗の文言を渡さない、authority の外に `@` がある URL は表示しない（正典 R131）。**c43**: 文字列から user-info を読み戻す規則を捨て、`@` を含む送り先 URL は拒む — 送らず、理由とログに URL を書かず、表示もしない（正典 R132 — この領域の 3 度目の P1 を利用者の判断で置き換えた。c42 の `parsedUrl` は届かない腕になったので戻した）。
- **錠と control**（C-3 の時点の本数。その後 c41〜c43 で錠を足した — 本数はどの錠も読まないので、ここでは書き直さない）: `AnchorSchedulerTest` 23（入口は `tick`）、`AnchorScheduleEndpointTest` 9（入口は controller の GET / PUT）、
  `AnchorServiceTest` +2、`AnchorControllerTest` +1（retry の 409 — 本体を移したので status の写像を読む錠が要った）、
  `PropertyManagerConfigTest` +1。control は MM4〜NZ4（正典 §5）。**件数 0 のとき封じない**ことは 3 つの仕組み（時間の腕は
  最古が無い、件数の腕は上限 ≥ 1、封じる entry が無い）が重ねて守るので、1 点の細工では発火しない — control は置かない。
  「tick の中で設定を cache しない」も保持する場所の無い構造なので細工で作れない（`ND4` は「保存値を読まない」で測る）。
- **§8 の限界は正典の残件表 R120〜R125 に写した**。

