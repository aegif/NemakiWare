# 設計 — 一括プリンシパル是正（CSV / JSON、preview と実行）

2026-09-28。計画 [`v3.4.0-evidence-and-residuals-plan.md`](v3.4.0-evidence-and-residuals-plan.md)
§20 トラック C の C-1（API）と C-2（管理画面）。旧「3.4.1」に置いていた機能を、2026-09-28 の
オーナー判断で 3.4.0 に入れる。**C-1（API）は 2026-09-28 に実装した** — `api/v1/resource/PrincipalBatchResource` と
`api/v1/principals/`（`PrincipalBatchEngine` / planner / applier / `PrincipalCsv` / plan store）。C-2（画面）は未着手。
「現行コードの事実」は 2026-09-28 に読んで確かめた行番号で書き、着手時（09-29）に再読して一致を確かめた。
実装で設計から動いた点は **§13** にまとめた。

---

## 1. 目的と範囲

ユーザー・グループ・所属の**不整合を是正する**手段を、管理者に 1 つ与える。棚卸し（何がずれて
いるか）は既存の一覧 GET を外側で突き合わせて行う — この機能は突合の結果を**適用する側**だけを持つ。

- 1 ジョブは **1 種類（kind）× 1 操作（operation）**。複数 kind を 1 トランザクションにしない。
- 入力は CSV（UTF-8）または JSON。1 リクエスト 1 ファイル。上限 **5,000 行 / 2 MiB**（超過は 413）。
- **preview は何も書かない。** 実行は「preview の `planId` を渡す確認あり」と「即実行」の 2 経路。
- 書き込みは**現行の正準経路だけ**（§7）。REST 3 層への写しは作らない。
- ファイルが欠落している ID の**自動削除はしない**（欠落は棚卸しの側の事実で、この機能は削除を
  指示された行しか削除しない）。
- LDAP / クラウドのディレクトリ同期とは**別の経路**。同期の書き戻しはしない。

対象外（この版で入れないもの）: 欠落 ID の自動削除、SCIM、LDAP 書き戻し、複数 kind の
1 トランザクション、真正性台帳（証拠 ledger）への必須記録、自動ロールバック。

---

## 2. 現行コードの事実（2026-09-28）

### 2.1 REST は 3 層ある

| 層 | 場所 | パス | CSRF |
|---|---|---|---|
| legacy Jersey（**UI が使う**） | `rest/UserItemResource.java`（`@Path("/repo/{repositoryId}/user/")`）、`rest/GroupItemResource.java` | `/core/rest/repo/{repo}/user/…`、`…/group/…` | 各メソッドが `ResourceBase.validateCsrfProtection` を手で呼ぶ |
| Spring MVC | `rest/controller/UserController.java`（`/v1/repo/{repositoryId}/users`）、`GroupController.java`（`…/groups`、`/{groupId}/members`） | `/core/api/v1/repo/{repo}/…` | `CsrfInterceptor`（`spring-mvc-context.xml` の `/v1/**`） |
| Jersey api/v1 | `api/v1/resource/UserResource.java`（`/repositories/{repositoryId}/users`）、`GroupResource.java`（`…/groups`、`/{groupId}/members`） | `/core/api/v1/cmis/repositories/{repo}/…` | `api/v1/filter/ApiCsrfFilter`（`ApiV1Application.java:123` で登録） |

UI は legacy 層を呼ぶ（`core/src/main/webapp/ui/src/services/cmis.ts:363, 1832-2203`）。`UserManagement.tsx:218-219` は
「No bulk operations」と明記している。**一括・CSV の取込は server にも UI にも無い**（唯一の一括
書き手は LDAP / クラウド同期）。`principals` という path も無い。

### 2.2 正準の書き込みメソッド（`businesslogic/impl/ContentServiceImpl.java`）

| メソッド | 行 | 備考 |
|---|---|---|
| `validateNewGroup` / `buildAndCreateGroup` | :674 / :691 | |
| `applyGroupUpdate` | :711-717 | 入れ子サイクルの検査は `update()` 内の `assertNoNestedGroupCycle`（:729） |
| `deleteGroup` | :772-780 | 他グループからの入れ子参照を外す（:786-828） |
| `buildAndCreateUser` | :831-853 | `hashPassword`（:856、BCrypt）。`.system/users` の下に作る |
| `applyUserUpdate` | :861-865 | |
| `deleteUser` | :868-876 | 所属を外す（:882-909） |

所属の編集は `businesslogic/GroupMembershipEditor.java` の唯一の公開メソッド
`static EditResult edit(List<String> current, List<String> targets, boolean add, Set<String> validIds, String selfId)`
（:104-135）。結果は `Outcome` = `ADDED / REMOVED / ALREADY_MEMBER / NOT_MEMBER / NOT_FOUND / GROUP_ITSELF`。
3 層がこれを呼び（`GroupResource:522/537/606`、`GroupItemResource:567/603`、`GroupController:368/428`）、
その結果を `applyGroupUpdate` で書いている。

パスワード方針は `util/PasswordPolicyService.java` の `validate(password, repositoryId)`（:150、
`password.policy.minLength`、既定 0）。

### 2.3 同期由来の印は無い

`User` / `Group` に `syncSource` / `externalId` の類の欄は**無い**（`model/User.java:44-52`、
`model/Group.java:43-46`）。由来は ID から**推定**するしかない:

- LDAP グループ: `directory.sync.group.prefix`（既定 `ldap_`）。**LDAP ユーザーは既定 prefix が空で、
  印が無い**（`DirectorySyncServiceImpl.java:349-400`）。
- クラウド: グループは `cloud-google:` / `cloud-microsoft:`（`CloudDirectorySyncServiceImpl.java:471, 794`）。
  ユーザーは userId がメールで `nemaki:allowedAuthMethods` が `cloud`（:359）— ただし
  just-in-time のクラウドログインでも同じ値が付く（`AuthResource.java:937`）ので同期の印としては
  一意でない。

### 2.4 built-in の保護は無い

`admin` と Solr ユーザー（`solr.nemaki.userid`、`PropertyKey.java:30`）の**削除を止める guard は
どの層にも無い**（`ContentServiceImpl.deleteUser:868`、`UserItemResource.delete:1035-1068`、
`UserController.deleteUser:367-395`、`UserResource.deleteUser:454-497`）。在るのは、legacy 層の
「自分自身の admin 剥奪」拒否（`UserItemResource.java:924-930`）と、非 admin が system user を触れない
`checkAuthorityForUser`（:1313-1318、`isSystemUser` :1328-1344）だけ。`mcp-service`
（`Patch_McpServiceAccount.java:44`）も保護されていない。

---

## 3. API

Jersey api/v1 層（§2.1 の 3 行目）に **`PrincipalBatchResource`** を 1 つ置く。`ApiCsrfFilter` と
`AuthenticationFilter` を自動で通る。admin 以外は 403。

```
POST /core/api/v1/cmis/repositories/{repo}/principals/batch/preview
POST /core/api/v1/cmis/repositories/{repo}/principals/batch/execute
```

### 3.1 入力

`multipart/form-data`（`file` = CSV、`kind`、`operation`、`onUnexpected`）か、`application/json`
（同じ欄と `rows`）。

| 欄 | 値 | 必須 |
|---|---|---|
| `kind` | `users` / `groups` / `memberships` | 必須 |
| `operation` | `create` / `update` / `delete`（users, groups）、`add` / `remove` / `replace`（memberships） | 必須 |
| `onUnexpected` | `abort`（既定）/ `skip` | execute の両経路（2026-09-28 から確認あり経路も — §6.1 の 3。§13） |
| `planId` | preview が返した id | execute の確認あり経路だけ。`planId` と `file` の**両方を渡したら 400** — **例外は users の create / update で `passwordPresent` の plan**: パスワードは plan に保存しないので（§6.1）同じファイルの再送を**要求**し、`fileDigest` が plan と一致しなければ 409。それ以外（`passwordPresent` でない plan）で `file` が付いていれば 400。`file` を再送するときは `kind` / `operation` も送る（CSV の列検査に要る。plan と照合され、違えば 400） |

### 3.2 preview の応答

```json
{
  "planId": "…", "snapshotHash": "…", "expiresAt": "…",
  "kind": "users", "operation": "update",
  "counts": {"rows": 120, "expected": 117, "unexpected": 2, "forbidden": 1},
  "rows": [
    {"line": 2, "id": "u001", "verdict": "expected"},
    {"line": 3, "id": "u002", "verdict": "unexpected", "reason": "NOT_FOUND"},
    {"line": 4, "id": "admin", "verdict": "forbidden", "reason": "BUILT_IN_ADMIN"}
  ],
  "passwordPresent": true
}
```

- **パスワードは値を返さない。** `passwordPresent` だけ。plan の保存にも値を持たない（§6.1）。
- `verdict` は 3 値。`expected` = 想定どおり適用できる、`unexpected` = §5 の表の状態、
  `forbidden` = §5.2 の対象（**skip でも適用しない**）。
- `reason` は登録簿の値（§5）。自由文は `message` に別に入れ、UI はそれを表示する。

### 3.3 execute の応答

```json
{
  "jobId": "…", "status": "applied" | "partial" | "refused",
  "counts": {"applied": 117, "skipped": 2, "forbidden": 1, "notApplied": 0},
  "stoppedAt": null,
  "rows": [ {"line": 2, "id": "u001", "outcome": "applied"}, … ]
}
```

| 状況 | HTTP | `status` |
|---|---|---|
| 全部適用（skip / forbidden を含んでよい） | 200 | `applied` |
| `abort` で想定外が 1 行でもある | 409 | `refused`（**0 件書いた**、`counts.applied = 0`） |
| 適用中に失敗した | 500 | `partial`。`stoppedAt` に止まった行、`rows` は `applied` / `notApplied` / `failed` を行ごとに |
| `planId` の期限切れ・snapshot 不一致・別ノードの plan・再送ファイルの不一致 | 409 | `refused`、`reason` = `PLAN_EXPIRED` / `SNAPSHOT_CHANGED` / `PLAN_UNKNOWN` / `FILE_DIGEST_CHANGED` |
| 上限超過 | 413 | |
| CSV が読めない（列不足・重複 ID・不正 UTF-8） | 400 | 行番号つきの理由 |

「読めなかった」と「無かった」は分ける（この branch の規則）。対象の存在確認で store が答えなかったら
その行は `unexpected` ではなく **plan 全体を 503** にする（読めないまま `NOT_FOUND` と言わない）。

---

## 4. CSV

UTF-8（先頭の BOM は許す）、RFC 4180 の引用、**1 行目はヘッダ必須**。列名は下表のとおりで、
知らない列は 400（黙って捨てない）。1 ファイル内で同じ ID が 2 度出たら 400。

| kind / operation | 列 |
|---|---|
| users / create | `userId`, `name`, `firstName`, `lastName`, `email`, `password`, `admin`(true/false), `groups` |
| users / update | `userId` + 変えたい列だけ（`password` を含めてよい） |
| users / delete | `userId` |
| groups / create | `groupId`, `name`, `users`, `groups` |
| groups / update | `groupId` + 変えたい列だけ |
| groups / delete | `groupId` |
| memberships / add, remove | `groupId`, `memberId`, `memberType`(user/group) |
| memberships / replace | `groupId`, `members`（`user:u001;group:g002` の形。**空文字は「所属を空にする」**） |

**`update` で列が空のときの意味は 1 つ — 「その列は触らない」**（2026-09-28 オーナー決定）。
`groups` 列が空でも所属は変わらない。所属を空にしたいときは `memberships / replace` に空の
`members` を渡す。理由: 列を落とした CSV で全員の所属が消える事故を、意味の側で塞ぐ。
JSON でも同じ（欄が無い / `null` = 触らない。`""` も users / groups の update では触らない。
memberships / replace の `members: []` だけが「空にする」）。

`admin` 列は `true` / `false` の 2 値。空は触らない。**`admin` を `true` にする行は preview で
`expected` だが、`counts` に `adminGrants` を別に出す**（見落としを防ぐ表示のため）。

---

## 5. preview が数えるもの

### 5.1 想定外（`unexpected`）

| kind | operation | 想定外 | `reason` |
|---|---|---|---|
| users / groups | create | 同じ ID が既にある | `ALREADY_EXISTS` |
| users / groups | update | 対象が無い | `NOT_FOUND` |
| users / groups | delete | 対象がもともと無い | `NOT_FOUND` |
| memberships | add | グループ／メンバーが無い、既に所属 | `GROUP_NOT_FOUND` / `MEMBER_NOT_FOUND` / `ALREADY_MEMBER` |
| memberships | remove | グループが無い、所属していない | `GROUP_NOT_FOUND` / `NOT_MEMBER` |
| memberships | replace | グループ／指定メンバーが無い、入れ子サイクル | `GROUP_NOT_FOUND` / `MEMBER_NOT_FOUND` / `NESTED_CYCLE` |
| users | create / update | パスワード方針違反 | `PASSWORD_POLICY` |
| 全部 | delete / remove | **同期由来と推定される ID**（§2.3 の prefix） | `LOOKS_DIRECTORY_SYNCED` |

`LOOKS_DIRECTORY_SYNCED` は**推定**である（LDAP ユーザーは印が無いので推定できない — §2.3）。
文書と UI は「同期由来らしい」と書き、「同期由来である」とは書かない。即実行の既定 `abort` は
この行があれば跳ねる。`skip` でも、この行は**適用しない**（skip = 想定外を飛ばす、の意味のまま）。

### 5.2 禁止（`forbidden`、skip でも適用しない）

- `admin` の delete、`admin` の `admin=false`
- Solr ユーザー（`solr.nemaki.userid`）と `mcp-service` の delete、`admin=false`
- **実行者自身**の delete と `admin=false`（legacy 層の既存規則と同じ向き）
- 上の 3 者を `memberships / remove` / `replace` で管理者グループから外す行は**禁止しない**
  （グループ所属は admin flag ではない）。preview の `message` に注意だけ出す（**未実装** — §13）

この guard は**この機能の validator に置く**。既存 3 層に同じ guard を足すかは別の判断
（§12 の限界 1）。

---

## 6. 実行

### 6.1 確認あり（`planId`）

preview は `Plan`（kind / operation / 行の正規化結果 / `snapshotHash` / 作成ノード id / 期限）を
**メモリに TTL 10 分**で保存する。`snapshotHash` は preview 時点の対象 principal の
`(id, 更新時刻または _rev)` を並べた digest。execute は

1. `planId` が無い・期限切れ → 409 `PLAN_UNKNOWN` / `PLAN_EXPIRED`
1'. `passwordPresent` の plan で `file` が無い → 400。あって `fileDigest` が plan と違う → 409 `FILE_DIGEST_CHANGED`（**0 件書く**）
2. 同じ行をもう一度判定し、`snapshotHash` の再計算値**と行ごとの verdict の列**（verdict / reason / message）が preview と
   一致しなければ → 409 `SNAPSHOT_CHANGED`（**0 件書く**）。snapshot は対象の `_rev` しか持たないので、行が名指す参照先
   （`memberId` / `groups` / `users` / `members`）の消失、入れ子サイクルの発生、**別の実行者**による forbidden の変化は
   verdict の側で捕まる（c39、2 名一致の P1）
3. 一致したうえで、`unexpected` か `forbidden` の行が 1 行でもあり `onUnexpected` が `abort`（既定）なら → 409
   `UNEXPECTED_ROWS`（**0 件書く**、plan は残す — 同じ `planId` を `onUnexpected=skip` で確認し直せる）
4. それ以外 → 再判定した verdict で行を順に適用（`skip` なら `unexpected` は飛ばし、`forbidden` はそれでも書かない）

plan はノードローカルなので、複数レプリカで別ノードに当たると `PLAN_UNKNOWN`（§12 の限界 3）。
パスワードは plan に**平文でも hash でも保存しない** — 確認あり経路では execute に同じ CSV を
もう一度渡す（`planId` + `file`。§3.1 の表に書いたとおり、`passwordPresent` の plan では `file` を**要求**し、
`snapshotHash` に加えて `fileDigest` の一致も見る。無ければ 400、違えば 409）。

### 6.2 即実行（`onUnexpected`）

- `abort`（既定）: preview と同じ判定を先に全行に掛け、`unexpected` か `forbidden` が 1 行でもあれば
  **何も書かず 409**。
- `skip`: `unexpected` と `forbidden` を飛ばし、`expected` だけ適用。UI では別ボタンで、既定にしない。

### 6.3 適用の順序と障害

行の順に 1 件ずつ正準メソッドを呼ぶ。1 件が例外で失敗したら**そこで止め**、`applied` /
`notApplied` / `failed` を行ごとに返す（`partial`）。自動ロールバックはしない — 途中まで適用された
状態は、返した `rows` を見て**同じ CSV を再度 preview** すれば「既に適用済み = 想定外」として
見える（べき等の代わりに、再実行が安全に跳ねる形）。

memberships / replace は「現在の所属を読む → `GroupMembershipEditor.edit` で差分 → `applyGroupUpdate`」
を 1 グループにつき 1 回。同じグループが CSV に 2 行あるのは 400（§4）。

---

## 7. 書き込み経路

| 操作 | 呼ぶもの |
|---|---|
| users / create | `PasswordPolicyService.validate` → `ContentServiceImpl.buildAndCreateUser`（`admin=true` は読み直して `applyUserUpdate`。`groups` 列があれば該当グループごとに `GroupMembershipEditor.edit` → `applyGroupUpdate`） |
| users / update | `applyUserUpdate`（password 列があれば `validate` → hash は既存経路。`groups` 列があれば create と同じ所属の書き — 列は所属の**全リスト**で、列に無いグループからは外れる。§13） |
| users / delete | `deleteUser`（所属の除去は既存の :882-909） |
| groups / create | `buildAndCreateGroup`（`validateNewGroup` の 3 検査 — id / name の欠落、既存 — は planner が 400 / `ALREADY_EXISTS` で先にする） |
| groups / update | `applyGroupUpdate`（サイクル検査は既存の :729） |
| groups / delete | `deleteGroup` |
| memberships / * | `GroupMembershipEditor.edit` → `applyGroupUpdate` |

新しい書き込みメソッドは作らない。キャッシュの無効化・監査ログは各メソッドが今しているとおり。
バッチ自身が足す監査は 1 ジョブ 1 行: `jobId`、kind / operation、`counts`、実行者、開始・終了時刻、
経路（preview 確認 / 即実行 / skip）。**行の中身（名前・メール・パスワード）は監査に書かない。**

---

## 8. 管理画面（C-2）

- ユーザー管理・グループ管理の両画面に「一括」ボタン。押すと 4 段の modal:
  1. kind / operation を選ぶ（グループ画面なら kind は groups / memberships）
  2. CSV をアップロード（テンプレート CSV のダウンロードリンクを同じ段に）
  3. **preview 表**（`verdict` で色分け、`reason` と `message`、`counts`、`adminGrants`、
     `passwordPresent`）。想定外が 1 行でもあれば「確認して実行」ボタンは有効のまま、ただし
     実行時に `abort` で跳ねることを段に書く
  4. 「確認して実行」（`planId`。`passwordPresent` の plan だけは §6.1 のとおり同じファイルを再送し、それ以外は `planId` のみ）
- 「想定外を飛ばして実行」は**別ボタン**で、確認ダイアログ付き、既定にしない。
- 結果表: 行ごとの `outcome`。`partial` のときは `stoppedAt` を先頭に。
- 呼び出しは新しい `core/src/main/webapp/ui/src/services/principalBatch.ts`（`AuthService.getAuthHeaders()` を付ける —
  `X-Requested-With` が CSRF の条件）。legacy `cmis.ts` には足さない。
- i18n は `ja.json` / `en.json` の `principalBatch.*`。
- Playwright: `tests/admin/principal-batch.spec.ts` — preview が書かないこと（preview 後に一覧が
  変わらない）、abort、skip、forbidden、`groups` 空欄で所属が残ること、を UI から。

---

## 9. 錠と control（C-1 / C-2 で書く。ID は着手時に割り当てる）

| 錠（自分の assertion で落ちる形） | control が壊すもの |
|---|---|
| preview は書き込みメソッドを 1 つも呼ばない（`verify(never())` を 7 メソッド全部に） | preview が 1 行だけ `applyUserUpdate` を呼ぶ |
| `abort` + 想定外 1 行 → 適用 0、409 | 想定外を数える腕を落として全部 `expected` にする |
| `forbidden` は `skip` でも適用しない | skip の腕が `forbidden` を `unexpected` と同じに扱う |
| `planId` の snapshot ずれ → 409、適用 0 | snapshot の比較を外す |
| 応答・plan・監査・ログにパスワードの値が無い（値を `assertFalse(contains)` で。ログは applier と resource の logger を捕捉し、store の例外が値を echo しても消えていること — 2026-10-06 の 9-6 の領域 C の Codex まで、ログは読んでいなかった） | 応答に `password` を写す／incident の warn に `row.cells()` を足す／redaction を外す／resource の 2 つの catch を素通し／cause 鎖を落とす／置換で消えなかった値を見ない |
| `update` の空欄は触らない（`groups` 空欄の後、所属が同じ） | 空欄を「空にする」と読む |
| `memberships / replace` の空 `members` は所属を空にする | 空を「触らない」と読む（上の逆） |
| 同期由来の prefix を持つ delete は `LOOKS_DIRECTORY_SYNCED` | prefix の表から `ldap_` を落とす |
| 5,001 行 / 2 MiB 超は 413、0 件書く | 上限を外す |
| 対象の読みが失敗したら 503（`NOT_FOUND` にしない） | 例外を `NOT_FOUND` に潰す |
| 非 admin は 403、`X-Requested-With` 無しは 403 | （filter の既存錠が覆う。無ければ足す） |
| 同じ ID の 2 行は 400 | 重複検査を外す |

runner の規則どおり: 錠は本番の入口（`PrincipalBatchResource`）を通す。協力者クラスだけの錠で
済ませない。控えの control は helper でなく呼び出し側を壊す。

---

## 10. 名乗ること・名乗らないこと

名乗る: 「管理者が、CSV / JSON で指示した範囲のユーザー・グループ・所属を、preview で確認した
うえで、既存と同じ経路で作成・更新・削除できる」。

名乗らない: 「ディレクトリと一致した状態にする」（棚卸しは外側）、「同期由来を判別する」（推定）、
「途中で失敗しても元に戻る」（ロールバック無し）、「複数レプリカで plan を共有する」。

---

## 11. 受け入れ（計画 §18 に足す条件）

- preview が書かない。`abort` + 想定外で 0 件適用。`planId` の snapshot ずれは 409。
- `forbidden`（built-in 3 者と実行者自身）は `skip` でも書かない。
- `update` の空欄は触らない（決定 2026-09-28）。

---

## 12. 既知の限界（実装時に正典の残件表へ行として写す）

1. **既存 3 層に built-in 保護が無い**（§2.4）。この機能の guard は一括の入口だけを守る。
   正準メソッドへ guard を下ろすかは、既存 API の挙動変更なので別判断。
2. **同期由来は推定**（§2.3）。LDAP ユーザーは印が無く、`LOOKS_DIRECTORY_SYNCED` に掛からない。
3. **plan はノードローカル**（§6.1）。複数レプリカでは同じノードに当たらないと 409。
4. **行単位の適用でトランザクションが無い**（§6.3）。`partial` の後始末は再 preview。
5. パスワードを持つ確認あり経路は**ファイルの再送**が要る（§6.1）。

---

## 13. 実装で設計から動いた点（2026-09-28、C-1）

- **§2.2 の訂正**: 3 層の所属の入口は `applyGroupUpdate` ではなく `update()` を直接呼ぶ（修正印を自分で押す）。尾は同じなので、
  バッチは `GroupMembershipEditor.edit` → `applyGroupUpdate` で書く。正準の検索は `getUserItemById` / `getGroupItemByIdFresh`
  （欠落は null、store が答えられないときは例外 — バッチはそれを 503 `STORE_UNAVAILABLE` にする。§2.3 の `User` / `Group` は
  deprecated で、正準は `UserItem` / `GroupItem`）。
- **§7 の訂正**: 既存の principal の書き込みは監査ログを出していない（AOP の pointcut は `cmis.service..*Impl` だけ）。
  「各メソッドが今しているとおり」は無い。バッチは 1 ジョブ 1 行（`AuditOperation.PRINCIPAL_BATCH`、kind / operation / mode / counts。
  行の cell は書かない）を `AuditEmitSupport.safeEmit` で出す。
- **`groups / create` の ID がユーザーの ID と同じ**: `createGroupItem` が拒む（`validateNewGroup` は見ない）ので、preview で
  `ALREADY_EXISTS`（「a user with this id exists」）にした。逆（`users / create` にグループの ID）も同じ。
- **JSON の `fileDigest`**: 確認あり実行の JSON は `planId` を運ぶので、本文全体の digest では前後で一致しない。digest は
  `rows` 配列を再直列化した bytes に取る（CSV は file の bytes）。
- **plan は 400 で消費しない**: `planId` を渡して `file` が無い／違う（400 / 409 `FILE_DIGEST_CHANGED`）とき plan は残り、
  同じファイルで再送できる。消費するのは適用したとき（1 回だけ）と `SNAPSHOT_CHANGED` のとき（世界が動いたので捨てる）。
  期限切れは `PLAN_EXPIRED`、知らない id は `PLAN_UNKNOWN`（別ノードの plan もこれ）。
- **multipart**: jersey-media-multipart は api/v1 に登録済みなので `@FormDataParam("file")` で受ける。JSON は `rows` 配列で受け、
  配列の値は `;` 区切りに畳む（空配列は空文字 = 「空にする」は `memberships / replace` の `members` だけが読む）。
- **500 の規則**: リソースが全部の応答を自分で組む。`PrincipalBatchRequestException` は 400 / 409 / 413 / 503 の本文に、
  それ以外の `RuntimeException` は 500 `{status:error, message: 固定文, incidentId}`（`ApiExceptionMapper` に届かせない —
  届けば `getMessage()` が写る）。適用中の失敗は行の outcome `FAILED`、reason `INCIDENT_<id>`、全体は 500 `partial`。
- **plan の記録**: 既知の限界 §12 の 1〜5 は正典の残件表 R115〜R119 に写した（R115 は P2、他は P3）。
- **admin=true の create は 2 書き**: `buildAndCreateUser` は admin=false で書くので、読み直して `setAdmin(true)` → `applyUserUpdate`。
- **実行者**: `CallContext.getUsername()`（`HttpServletRequest.getUserPrincipal()` は常に null なので使わない）。
- **`memberships / replace` の `members` 列は必須（2026-09-28、c39 前の自己確認）**: 列を落とした CSV、JSON で欄が無い / `null` の行は
  400 で、plan にも apply にも進まない。「空にする」は**述べた空**（空文字・`[]`）だけ。§4 が「意味の側で塞ぐ」と書いた
  「列を落とした CSV で全員の所属が消える事故」は、`update` の空欄では塞がっていたが `replace` の欄の欠落では開いていた —
  読み手 2 つ（CSV / JSON）が同じ `requiredColumnsFor` を読む。`add` / `remove` の `memberId` / `memberType` も同じ扱い。
- **plan の消費は原子的（同日）**: 同じ `planId` の確認 2 つが同時に snapshot 検査を通っても、`Store.remove` が true を
  返した 1 つだけが書き、もう 1 つは 409 `PLAN_UNKNOWN`（0 件）。「1 回だけ」は peek → remove の間でも成り立つ。
- **c39 の確認レビュー（2 名とも NOT CONVERGED）で直した点（2026-09-28）**:
  - **再判定の verdict を捨てていた**（Codex P1 = subagent P1-2、独立に同じ穴）: 確認あり実行は snapshot だけ比べ、preview 時の
    verdict を適用していた。§6.1 の 2 を「snapshot と verdict の列の両方が一致」に改め、適用するのは再判定の verdict。
  - **plan は repository に縛る**: 別の repository の path で `planId` を渡すと 409 `PLAN_UNKNOWN`（plan は残る）。
  - **`memberships / remove` の `LOOKS_DIRECTORY_SYNCED`**（§5.1 は delete / remove）: planner に腕が無く届いていなかった。
  - **`groups / create` の `name` 欠落は 400**（`memberId` 欠落と同じ扱い）。`NOT_FOUND` の流用をやめた。
  - **CSV の行番号は物理行**: 複数行セルの後の行も、ファイルのその行が始まる番号。
  - **2 MiB 超 → 413 に錠が無かった**（subagent P2、3 腕）: 錠を足した。製品は変えていない。
  - §3.1 / §7 の表を実装に合わせた（`file` 再送時の `kind` / `operation`、users の `groups` 列の所属の書き、`validateNewGroup` は呼ばない）。
- **c40 の確認レビュー（2 名とも CONVERGED、P3 のみ）で写した点（2026-09-28）**:
  - §5.2 の「管理者グループから外す行は preview の `message` に注意だけ出す」は**未実装**（`expected` の行は message を持たない。
    生産者が無い文だった）。禁止しない判断は変わらない。注意を出すなら C-2 の画面側で `memberId` が built-in / 実行者かを見る（別判断）。
  - §7 の users / create・update の `groups` 列は**所属の全リスト**（製品の `updateUserGroups` と同じ差分適用）— 列に無いグループからは
    外れる。この暗黙の remove は `LOOKS_DIRECTORY_SYNCED` を通らない（§5.1 の表は delete / remove 操作だけ）。`ldap_` グループの所属を
    保ちたい行はそのグループも列に書く。
  - `readBounded` は 8 KiB 単位で読むので、読む量の上限は `MAX_BYTES + 8192`（javadoc の「+ 1」を直した。錠はこの境界で等号）。
  - MH4 / ML4 の錠は入口でなく `PrincipalCsv.parse` に直接掛かる（入口の `readBounded` が超過を渡さないので、parse 自身の 413 は
    二重防御。入口側の腕は MF4 / MG4 が測る）— 「錠は入口を通す」規則の例外として記録。
- **確認あり経路も既定 abort（2026-09-28、C-2）**: §6.1 は plan の verdict をそのまま適用し、想定外の行を黙って飛ばしていた。
  §8 の画面は「確認して実行」が想定外で abort に跳ね、飛ばすのは確認ダイアログ付きの別ボタン、と書いていて、§6.1 の
  API ではそれを実装できなかった。`onUnexpected` を確認あり経路にも効かせた（既定 `abort` → 409 `UNEXPECTED_ROWS`、
  plan は残す。`skip` で確認し直すと expected だけを適用し、forbidden はそれでも書かない）。錠
  `aConfirmedPlanWithAnUnexpectedRowIsRefusedUnlessSkip`、control OA4。
- **画面（C-2）**: ユーザー管理・グループ管理の見出しに「一括」。4 段の dialog（種類と操作 → CSV とテンプレート → preview 表 →
  結果）。preview 表は verdict で色分けし、想定外・禁止の行があれば「確認して実行は何も書かずに止まる」と段に書く。
  「想定外を飛ばして実行」は別ボタンで確認ダイアログ付き。パスワードを含む plan は実行時に同じファイルを再送する
  （§6.1 の 1'）。呼び出しは `services/principalBatch.ts`（`AuthService.getAuthHeaders()`）。Playwright は
  `tests/admin/principal-batch.spec.ts`（preview が書かない・abort・skip・forbidden・groups 空欄で所属が残る）。

