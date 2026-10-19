# 資格情報を利用者の実体に結ぶ（R170 の完全修正 — 3.4.1 のバッチ 19）

状態: **設計（2 名のレビューの前）**。実装はレビューが収束してから。

オーナーの判断（2026-10-15）: R170 は**完全修正**。範囲は「資格情報を利用者の実体（作成時刻か文書 id）に結ぶ」
「削除で API キーを取り消す」「パスワード変更で失効させる」（外部レビュー S3 の残り）。

---

## 1. いま何が起きるか（コードで確かめた事実、`072a3c00b`）

バッチ 17 で、要求のたびに利用者が居て `disabled` でないことを確かめるようにした（正典 R166）。
残る穴は、**資格情報が利用者の「名前」にだけ結ばれている**ことに由来する。

| 資格情報 | どこに在るか | 何で引くか | 削除で消えるか |
|---|---|---|---|
| セッションのトークン | 各 JVM のメモリ（`TokenServiceImpl` の `Map<app, Map<repo, Map<user, Token>>>`） | 利用者名 | **消えない**（期限 24 時間まで残る） |
| API キー | CouchDB（`ApiKey`・`CouchApiKey`） | `keyHash` → `userId` を返す | **消えない**（`ContentServiceImpl.deleteUser` は取り消さない。期限は任意で、付けなければ無期限） |
| MCP のセッション | 各 JVM のメモリ（`McpAuthenticationHandler.sessionTokens`） | 利用者名 | **消えない**（24 時間） |
| パスキー | CouchDB（`WebAuthnCredential`） | 利用者名（`getWebAuthnCredentialsByUserId`） | **消えない**（`deleteUser` は消さない — §6 の未決 4） |

したがって:

- **削除して同じ利用者 ID を作り直すと、消す前に発行した資格情報がその新しい利用者として通る。**
  `mayStillAct` は「その名前の利用者が居て `disabled` でない」しか見ないので、作り直した時点で再び true になる。
  乗っ取られた利用者を消して本人のために作り直す、退職者の ID を新入社員に使い回す、のどちらでも起きる。
- **パスワードを変えても、発行済みのトークンと API キーは止まらない。** どの資格情報も「いつ発行したか」を
  記録していないので、パスワードを変えた前後を区別できない。

重さ: 消した・乗っ取られた・退職した側の持ち主が、**別人のアカウントの権限**で読み書きできる（R170 を P1 とした根拠）。

## 2. 結ぶ鍵 — 何を「利用者の実体」とするか

`UserItem extends Item extends Content extends NodeBase` なので、次の 2 つが使える。

- **`getId()`** — CouchDB の文書 id。作り直すと必ず別の値になる。**これを採る。**
- `getCreated()` — 作成時刻。同じミリ秒の衝突が原理的に在り、更新で書き換わりうる。採らない。

加えて、「パスワードを変えた」を捕まえるために**世代**（generation）を使う。**時刻は使わない**（確認 7 巡目の
Codex P1 — 設計レビュー）: `issuedAt < credentialsNotBefore` は JVM の時計に依存し、**同じミリ秒**なら
変更前の資格情報でも等しくなって通り、レプリカ間の時計ずれがあれば変更前の発行時刻が未来になる。
「どのレプリカでも同じ答えになる」は時刻では成り立たない。

- 利用者の側: **`nemaki:credentialGeneration`** — 単調増加の整数。パスワードを変えるたびに +1。
- 資格情報の側: **`generation`** — 発行したときの利用者の `credentialGeneration`。

比べるのは整数の一致なので、時計も丸めも関係ない。ACL epoch（[`acl-epoch-fencing.md`](acl-epoch-fencing.md)）と
同じ考え方で、この木には既に前例がある。**どちらも利用者の側に在る値と比べる**ので、どのレプリカでも同じ
答えになる（バッチ 17 で、要求のたびに利用者を読んでいる — 新しい読みは増えない）。`issuedAt` は記録として
残すが**判定には使わない**（調査のため。判定に使うと上の罠が戻る）。

## 3. 規則（1 つに揃える）

利用者の側に 1 つ欄を足す:

- **`nemaki:credentialGeneration`** — 単調増加の整数。パスワードを変えたときに +1 する。

資格情報の側に 3 つ:

- **`accountId`** — 発行したときの `UserItem.getId()`。
- **`generation`** — 発行したときの `credentialGeneration`。
- **`issuedAt`** — 発行の時刻（記録のためだけ。判定には使わない）。

**どの判定がどの置き場に当たるか**（確認 7 巡目の Codex P2 — 「1 つの規則」と オーナーの回答 3 が
食い違っていた）:

| 置き場 | 発行時に**書く**欄 | **照合する**欄 | 欄が無いときの答え |
|---|---|---|---|
| セッションのトークン（`Token`） | `accountId`・`generation` | `accountId`・`generation` | どちらも**拒否** |
| MCP のセッション（`McpSession`） | `accountId`・`generation` | `accountId`・`generation` | どちらも**拒否** |
| API キー（`ApiKey`） | `accountId`・`generation` | `accountId` のみ | `accountId` 無し → **拒否**／`generation` は照合しないので答えに影響しない |
| パスキー（`WebAuthnCredential`） | `accountId`・`generation` | `accountId` のみ | 同上 |
| RSS のフィードのトークン（`RssToken`） | `accountId`・`generation` | `accountId` のみ | 同上 |

**このバッチに入れる置き場すべてが両方の欄を書く**（4 つ＝トークン・API キー・パスキー・MCP のセッション。RSS を入れるなら 5 つ目 — 未決 7。確認 14 巡目の subagent P2 で「5 つ全部」と無条件に書いていたのを未決 7 に合わせた。「書く」と「書けないなら発行しない」は同じ生産者側の規則）（確認 10 巡目の Codex P2 — パスキーの生産者が `generation` を書かず、RSS が
この表に無く、錠の計画が RSS を `accountId` の規則から外していた。生産者と消費者と錠が食い違っていた）。
照合しない置き場でも**書く**のは、(a) 調査のため、(b) 将来「管理者が 1 人の資格情報を全部無効にする」
操作（§7）を足すときに欄が揃っているため、(c) 書く側の規則が 1 つで済むため。
`generation` を照合しないのは、API キー（オーナー回答 3 — 自動処理が黙って止まる）、パスキーと RSS
（パスワードから導かれた資格情報ではない。失効させると正当に登録した人が入れない = 過剰拒否）。

`accountId` の照合は**全部**に当たる（R170 の本体）。`generation` の照合は「パスワードを変えた」ことを
根拠にするので、パスワードと結びのある資格情報だけに当てる。**乗っ取りの後始末**では API キーとパスキーを
明示的に取り消す手順を RELEASE_NOTES に書く（オーナー回答 3 と同じ趣旨）。

**世代の読み書きの規則**（確認 8 巡目、2 名 — これが無いと fail-closed が fail-open になる）:

- **「読めない」を 0 に丸めない**（確認 11 巡目の subagent P2 — 片側しか決めていなかった。
  確認 12 巡目の Codex P2 で**範囲を直した** — 「常にどちらの側でも」と書くと上の表と矛盾していた）。
  非負の整数として読めない値は、**世代を照合する判定**（トークンと MCP のセッション）では account 側でも
  credential 側でも**拒否**、**世代を保存する検査**（下げない・落とさない）では保存前でも保存する値でも
  **保存を拒否**。unknown を 0 に丸めると、パスワード変更で失効させたはずの資格情報が `0 == 0` で通る
  = fail-open（[[could-not-ask-vs-answered-no]]）。
  **世代を照合しない置き場（API キー・パスキー・RSS）の判定には当てない** — 上の表のとおり、そこでは
  `generation` は答えに影響しない。読めない世代だけで API キーを拒むのは過剰拒否で、オーナー回答 3
  （パスワード変更で自動処理を止めない）と逆向きになる。
- **発行のときは、このバッチに入れる置き場すべてで拒否する**（照合しない置き場も含む。
  **4 つ**＝トークン・API キー・パスキー・MCP のセッション。RSS を入れるなら 5 つ目 — 未決 7）。発行者は**真の値を書く**
  必要があり、account の世代が読めないまま 0 と書けば、それはこの版が作った嘘になる。損失は
  「壊れた利用者文書に対して新しい資格情報を作れない」ことで、**その場で見える失敗**なので採る。
  **欄が無いこと**だけが別扱いで、次の 2 行。
- **既存の利用者文書に欄が無い**ときは **0 とみなす**（互換のため。上げた直後に誰も入れない状態を避ける）。
  **資格情報の側は違う** — 発行時に読んだ値（0 でもよい）を**必ず明示的に書く**。
  **欄が無い・整数でない・負の数・読めない資格情報は拒否**する（**世代を照合する置き場だけ** —
  確認 13 巡目の subagent P3 で、上の範囲付けと食い違っていたのを揃えた。照合しない置き場では
  `generation` は答えに影響しないので、読めないことだけで拒むのは過剰拒否）（確認 9 巡目の Codex P2: 資格情報側の
  欠落も 0 とみなすと、書き込みが落ちた・壊れた資格情報が、世代 0 のアカウントに対して `0 == 0` で
  通ってしまう＝fail-open。この版より前のものは `accountId` が無いので既に拒まれており、資格情報側の
  欠落を許す互換上の理由は無い）。
- **資格情報の世代が利用者の世代より大きい**ときも**拒否**する（新しいレプリカが書いた値を古い木が
  読む形・壊れた値。`<` だけを見ると素通りする）。
- **3 つの役割を 3 つの層に分ける**（確認 10 巡目の Codex P2 — 「1 か所」と書いて 3 つの候補を並べていた。
  どれが何をするかを決めた）:

  | 役割 | 置き場 | 形 |
  |---|---|---|
  | **上げる**（+1） | `ContentService` の新しいメソッド **`stampPasswordChange(user, newHash, bumpGeneration)`**（**保存はしない**） | パスワードを書く 9 か所が**これだけ**を呼ぶ。MD5→BCrypt は `bumpGeneration=false`。一括是正は `true`（オーナー回答 6）。**実装のときに形を変えた**（2026-10-16、実装中に読んで確かめた）: 9 か所は**書いてから保存する**形で、保存は名前・グループなどほかの変更と**同じ 1 回**に乗っている（`UserResource:430` は `applyUserUpdate` が後で保存、`UserItemResource:840` は直後に `update`）。保存まで行う `changePassword` にすると**二重保存**になり、ほかの欄の変更が別の revision に分かれる。したがって上げる層は**在記憶の `UserItem` に 2 つの欄を刻むだけ**にし、保存は呼び出し側のまま。 |
  | **再試行**（409） | **呼び出し側の既存の保存経路**（上げる層は保存しないので） | `409` を受けたら**利用者を読み直して +1 を作り直す**。メモリの古い値から +1 にすると、同時に 2 回変えたとき 1 回分が失われる。解決できなければ**パスワードの変更を失敗させる**（上げられないまま変えるのは fail-open）。 |
  | **下げない・落とさない** | **couch の DAO** `ContentDaoServiceImpl#update(String, UserItem)` | PUT の直前に `_id` を読み直して（キャッシュを通さない）、保存する世代が**保存前の値以上**であることを確かめる。下がる・落ちるなら拒否。**cached の DAO には置かない**（cached は couch を包む decorator なので、両方に置くと読みが 2 回・判定が 2 回になる）。 |

  この分け方なら、DAO を直に呼ぶ経路（MD5→BCrypt）も**下げない側**は覆われ、**上げる側**は
  `changePassword` を呼ぶかどうかで明示される。`getUserItemByIdFresh` を `ContentService` に足す必要は
  **無い**（読み直しは couch の DAO の中で済む）。
- **文書全体を置き換える更新**（`UserItemResource.update` / `update-json` のような PUT）は、
  **世代の欄を落としても下げてもいけない**。落ちると失効させた資格情報が全部また通る。
  **どこで守るか**（確認 9 巡目の Codex P2 — 「1 か所」と書いて場所を決めていなかった。読んで確かめた）:
  - **場所は上の表のとおり couch の DAO**（`dao/impl/couch/ContentDaoServiceImpl#update(String, UserItem)`）。
    DAO に引くのは**呼び出し元が 2 件**あるからで（確認 9 巡目の subagent P2）、
    `ContentServiceImpl.updateInternal`（3415 行）と `AuthenticationServiceImpl`（518 行、MD5→BCrypt）。
    後者は `applyUserUpdate` も `updateInternal` も通らない。
  - **比べる述語**（確認 10 巡目の subagent P2 — 書き換えで落ちていた）: **両側とも欄が無ければ 0 とみなし、
    「保存する値 ≥ 保存前の値」を要求する**。欄を落とす更新は 0 になるので、保存前が 1 以上なら自動的に
    拒まれる（落とす形を別に書く必要は無い）。**保存前の値が整数として読めない**ときは比べられないので
    拒否する（unknown ≠ 0）。読み直しは couch の DAO の中で CouchDB から直に行うので、
    `getUserItemByIdFresh` を `ContentService` に足す必要は無い。保存ごとに 1 回の余分な読みが入る
    （利用者の更新は頻度が低いので許容と判断した。**測っていない**）。
  - **拒否したときキャッシュを捨てる**（確認 10 巡目の subagent P2。読んで確かめた）:
    `dao/impl/cached/ContentDaoServiceImpl.getUserItemById`（836 行）は**キャッシュの実体をそのまま返す**
    （`return v;`）ので、パスワードを変える経路は**キャッシュに載っているオブジェクトを書き換えてから**
    保存する。同じクラスの `update`（1469 行）は下層を呼んだ**後にだけ**キャッシュを置き直すので、下層が
    拒んで例外を投げると**キャッシュには世代 n+1、CouchDB には n** が残る。その状態で §3 の照合を当てると、
    世代 n で発行した**生きている資格情報を全部拒む**（eviction まで）= 過剰拒否で、この木では
    通してしまうのと同じ重さの欠陥。したがって cached の `update` は**例外のときに `userItemCache` と
    `contentCache` からその利用者を捨てて再 throw する**。409 の再試行の読み直しも**キャッシュを通さない**
    こと（通すと失われた更新になる）。**ここは「fresh を足す必要は無い」の例外**（確認 11 巡目の
    subagent P2 — 同じ節が矛盾していた）: キャッシュを通さない UserItem の読みは**当時どこにも無かった**
    （**2026-10-19 に `getUserItemByIdFromStore` として足した** — R207。`ContentService` と
    `ContentDaoService` の両方に在り、兄弟の `getGroupItemByIdFresh` と同じ fail-closed の形
    （配線を確かめ、読めなければ null ではなく throw）に揃えてあり、cached 実装は delegate の
    **同名**メソッドを呼ぶ）。
    下げない側の守りは couch の DAO の中で読み直すので要らない。**`changePassword` の 409 の再試行の
    層はそもそも入れていない**（下の §3 の訂正。保存の失敗はそのまま変更の失敗になる = fail-closed）
    ので、この読みを要求する呼び出し側はまだ無い — 入れる日には `getUserItemByIdFromStore` を使う。
    足した当時この段落は「利用者版が無い」と書いたままで、**メソッドが在るのに無いと言う 2 文が
    同じ段落に並んでいた**（バッチ 25、subagent P3-1）。
  - **外れるのは setup の 2 経路**（`SetupAdminResource:108`・`SetupApplyResource:484` — 利用者文書に
    BCrypt のハッシュを直接書く raw な PUT）。ここは **Setup Mode 限定**（`SetupModeGuardFilter` が
    Normal Mode では 403）。確認 9 巡目の subagent が読んで確かめたところ、`SetupAdminResource` は
    `adminDoc.deepCopy()` に `passwordHash` だけ差して全文を PUT するので**ほかの欄は保たれる** —
    つまり「落とさない」は満たすが、**「上げる」が抜ける**（admin のパスワードを変えても資格情報が
    失効しない = fail-open）。**両方を実装の作業項目にする**（世代を保ち、かつパスワードを変えたなら
    上げる）。「Setup Mode だから無視する」とは書かない。
- **MD5 → BCrypt の自動アップグレード**は世代を上げない（利用者が変えたのではない）。§4.4 の「1 か所に
  集める」はこの例外を**引数で**渡す（`bumpGeneration` の真偽）。集約したうえで例外を flag にしないと、
  ログインした本人のトークンがその場で無効になる（確認 8 巡目の subagent P3）。

判定（`AuthenticationUtil` に置き、`mayStillAct` の隣で同じ利用者の読みを使う）:

```
boundToThisAccount(credential, account):
  account が無い / disabled              → 拒否（バッチ 17 のまま）
  credential.accountId が無い             → 拒否（この版より前に発行したもの。オーナー判断 2026-10-16）
  credential.accountId が
      account.getId() と不一致            → 拒否（作り直し・ID の使い回し）
  この置き場が generation を照合する種類で
      かつ credential.generation !=
           account.credentialGeneration      → 拒否（古い＝パスワードを変えた後、
                                                大きい＝別のレプリカが書いた値か壊れた値）
      （account 側に欄が無ければ 0 とみなす。credential 側は**明示的な整数が無ければ拒否** —
        欠落・型違い・負の数・読めない値は、書き込みが落ちた資格情報を通すことになる）
  それ以外                                → 通す
```

**同じ利用者 ID の文書が 2 つ在りうること**（確認 10 巡目の subagent P3。製品のコメントが書いている —
`ContentServiceImpl.checkUserItemDeletion` 907-913 行「その利用者 ID が別の文書に解決するなら、利用者は
そちらで生きている」）。資格情報は**文書 id** に結ぶのに、引き当ては常に**名前**なので、名前の解決先が
別の文書に移ると、その人の資格情報は全部 `WRONG_ACCOUNT` で拒まれる。**向きは過剰拒否側**（パスワードで
入り直して再発行すれば戻る）であり、「前の持ち主の資格情報が新しい文書に通る」R170 の向きには倒れない。
名前が 2 つの文書に解決する状態自体は別の問題（重複の検出はこのバッチの範囲ではない）なので、
**この帰結を RELEASE_NOTES の「上げたときに止まるもの」に 1 行書く**だけにする。

**§3 の「再試行（409）」の層は入れていない**（2026-10-19 に訂正。9 か所のどこにも世代の作り直しは無く、
保存の失敗はそのまま変更の失敗になる — 向きは fail-closed。表はその層を記述したままだったので、ここに
書いておく。R170 の確認 1 巡目に subagent が見つけた P3-6）。

**`accountId` が無い資格情報は拒否する**（オーナー判断 2026-10-16、§6 の未決 1 — 推奨は「名前だけで通す」だったが
オーナーが fail-closed を選んだ）。したがって**この版に上げた瞬間に、既存の API キー・セッションのトークン・
パスキー・MCP のセッションは全部通らなくなる**。これは運用に見える変更なので:

- RELEASE_NOTES に**太字で**書く — 利用者は再ログイン、API キーは**再発行**、パスキーは**再登録**が必要。
- [`v3.4.0-upgrade-runbook.md`](../operations/v3.4.0-upgrade-runbook.md) に同じことを手順として書く
  （上げる前に、API キーで動いている自動処理の棚卸し）。
- 錠で表明する（`accountId` の無い資格情報が拒まれること。過剰拒否の向きではなく**意図した**拒否なので、
  「通る」と書いた錠が在れば書き換える）。

## 4. 置き場ごとの変更

### 4.1 API キー（いちばん durable。無期限のキーが在る）

- `ApiKey` と `CouchApiKey` に `accountId`・`issuedAt` を足す（`issuedAt` は既存の `getCreated()` で足りるなら再利用する — 実装時に確かめる）。
- 作成時に、そのときの `UserItem.getId()` を書く。
- `validateApiKey` が返すのは今も `userId` だけなので、**判定は呼び出し側（`AuthenticationServiceImpl.loginWithApiKey`・MCP）には置けない**。
  **オーナー回答 2: `ApiKeyService` の中で判定する**（戻り値の形は変えない）。通らなければ **null を返す**
  — 呼び出し側 3 か所は「null なら拒否」をすでに実装しているので、形が変わらない。
  **利用者の読みは `ContentDaoService`（この bean に既に注入されている — `serviceContext.xml`）から行う。
  `ContentService` を注入してはいけない**（確認 10 巡目の Codex P2）: `deleteUser` から `ApiKeyService` を
  呼ぶので、`ContentService → ApiKeyService → ContentService` の循環参照になり、Spring が起動を拒むか
  初期化途中の proxy を掴む。「`AuthenticationServiceImpl` が持っているから循環しない」と書いたのは誤り
  （循環は呼ぶ向きで決まる）。
- **アカウント単位の取り消しを足す**: いまの interface は `revokeApiKey(repositoryId, keyId)` だけなので、
  `deleteUser` と一括是正のために `int revokeApiKeysOf(String repositoryId, String userId)` を足す
  （**引き当ては `userId`** — 上記。戻り値の件数は `deleteUser` のログに使う。**一括是正の「失効数」には
  使わない** — オーナー回答 3 でパスワード変更は API キーを取り消さないので、一括是正が表示するのは
  世代 +1 で**失効させた利用者の数**。混ぜると `PrincipalBatchApplier` が API キーを取り消す実装になり、
  オーナーの判断に反して自動処理が止まる。確認 10 巡目の subagent P2）。
- `createApiKey`（2 形）が `accountId` と `generation` を書く（発行時に利用者を読む）。`listApiKeys` の
  表示には出さない（画面の契約を変えない）。
- **さらに、`deleteUser` がその利用者の API キーを取り消す。** ~~結びだけでは、この版より前のキー
  （`accountId` が無い）を守れない。~~ ~~取り消しは `deleteUser` を通らない削除に対する防御として残す。~~
  **この理由付けも誤りだった**（確認 10 巡目の subagent P2）: `deleteUser` の**中**に置いた取り消しは、
  `deleteUser` を**通らない**経路（`deleteUserItem` — CMIS からの削除）を守れない。R170 を閉じているのは
  §3 の `accountId` の照合で、取り消しは**置き場に使えない行を残さないための最良努力の掃除**
  （§4.7 の「順の理由」と同じ位置づけ）。だから**両方の入口**（`deleteUser` と `deleteUserItem`）に置く。
- **引き方**: 取り消しは **`userId`（既存の `getByUserId` の索引）で引く**。`accountId` で引くと、
  `accountId` を持たない古いキーが 1 件も見つからず、置き場に残り続ける（同 P3）。`accountId` は
  **照合**に使い、**引き当て**には使わない。

### 4.2 セッションのトークン

- `Token` に `accountId`・**`generation`**・`issuedAt` を足す。**世代を書くのを忘れないこと** — §3 の表で
  トークンは世代を**照合する**側なので、書き手が無いと「欄が無い＝拒否」の規則に当たって**全部のトークンが
  拒まれる**（確認 9 巡目の subagent P1: 生産者と消費者が逆になっていた。`a-vocabulary-with-no-producer`）。
- **`TokenServiceImpl` には既に `contentService` が注入されている**（`serviceContext.xml`）ので、`set()` の中で
  利用者を読んで `accountId` を書ける。**`setToken` の呼び出し側を 1 つも変えなくてよい** — 実測した内訳は
  `AuthResource` 3・`AuthTokenResource` 6・`WebAuthnResource` 1 の **10 か所**（探索語 `tokenService.setToken`・`ts.setToken(`、
  `core/src/main/java` 配下、interface と実装を除く）。**訂正**: 最初に書いた 12 か所には `SamlSignatureVerifier` の 2 件を
  数えていたが、あれは**コメント**で呼び出しではない（grep の件数をそのまま書いた誤り）。SAML のトークンは
  `AuthTokenResource` の 6 件のうちの 1 つで出る。
- 判定は `AuthenticationServiceImpl.loginWithToken`（バッチ 17 で利用者を読んでいる場所）。**ここで
  `Token` そのものを手に入れること**（確認 10 巡目の subagent P3）: `validateToken` が返すのは**利用者名（無効なら
  null）**で（確認 11 巡目の subagent P3 — 「真偽しか返さず」と書いたのは誤り）、新しい欄の持ち主である
  `Token` が判定点に無い。`TokenService.getToken(app, repositoryId, userName)` が
  在るのでそれで読む。`validateToken` の意味は変えない（呼び出し側が複数あり波及する）。
  **全部のトークンの扉がここを通る**ことは確かめた（`AuthenticationFilter` 241・282 行、
  `ApiAuthenticationFilter` 141・195 行 → `login()` → `loginWithToken`）ので、判定点は 1 つで足りる。

### 4.3 MCP のセッション

- `McpSession` に `accountId`・**`generation`**・`issuedAt` を足し、`login`・`loginWithApiKey`・
  `registerSessionToken` で書く（トークンと同じ理由で、世代は**照合する側だから書き手が必須**）。
- 判定は `authenticateSessionToken`（バッチ 17 の `mayStillAct` の隣）。拒んだセッションは今と同じに捨てる。

### 4.4 パスワード変更で失効させる

`nemaki:credentialGeneration` を +1 する場所を、パスワードを変える**すべての**経路に置く。
実測した書き込み元は **9 か所 / 5 ファイル**（探索語 `setPassowrd(`、`core/src/main/java` 配下、`model/` を除く）:

- `rest/UserItemResource.java` — 4（`changePassword`・`update`・`update-json` ほか）
- `api/v1/resource/UserResource.java` — 2
- `api/v1/principals/PrincipalBatchApplier.java` — 1（一括プリンシパル是正）
- `rest/controller/UserController.java` — 1（Spring MVC）
- `cmis/factory/auth/impl/AuthenticationServiceImpl.java` — 1（**MD5 → BCrypt の自動アップグレード。これは
  利用者が変えたのではないので、**世代を上げてはいけない** — 上げると、ログインした本人の
  トークンがその場で無効になる。集約した 1 か所へは `bumpGeneration=false` で渡す（§3 の規則））

**探索語の盲点（この棚卸しの限界）**: `setPassowrd(` だけで数えると、**利用者文書に BCrypt のハッシュを直接書く 2 つの経路が
落ちる** — `api/setup/resource/SetupAdminResource.java:108`（`POST /api/v1/setup/admin/change-password`。主リポジトリの admin 文書を
更新する）と `api/setup/resource/SetupApplyResource.java:484`（`changeAdminPassword`）。どちらも `UserItem.setPassowrd` を通らない。
`BCrypt.hashpw` で数えると 9 件が出て、そのうちこの 2 件が `setPassowrd` の外にある。**到達性**: `SetupModeGuardFilter` が
Normal Mode では 403 を返すので Setup Mode 限定。

**書く場所は 5 ファイルに散っており、さらに上の 2 つが別の語彙で書くので、1 か所に集めて、そこを通らない
パスワード変更が無いことを錠で測る。集約先は §3 の表のメソッド
`ContentService.changePassword(repositoryId, userId, newHash, bumpGeneration)` で、**「保存の手前」という
層ではない**（確認 10 巡目の subagent P2）— `AuthenticationServiceImpl`（518 行）は `ContentService` を
通らずに `contentDaoService.update` を直に呼ぶので、層に置く形ではこの経路を覆えない。錠は `setPassowrd` と `BCrypt.hashpw` の両方の語彙で
数えること** — 片方だけで数えた棚卸しから書いた錠は、偽の保証になる（確認 2 巡目の subagent P2）。

**API キーとパスキーはパスワード変更で失効させない**（§3 の表。オーナー回答 3 と、パスキーがパスワードから
導かれた資格情報ではないこと）。失効するのはトークンと MCP のセッションだけで、「API キーとパスキーは別の
資格情報で、明示的に取り消す」と RELEASE_NOTES に書く — 外部レビュー S3 は「パスワード変更・削除・無効化の
ときに、トークン、API キー、MCP セッションを失効させる」と書いているが、オーナーの判断でここは採らない。

### 4.5 パスキー（WebAuthn の資格情報）— オーナー判断 2026-10-16 でこのバッチに入れる

同じ穴が在ることをコードで確かめた（§6 の未決 4）: `getWebAuthnCredentialsByUserId(repositoryId, userId)` が
**利用者名の文字列**で引き、`ContentServiceImpl.deleteUser` は（グループからの除去と利用者の文書の削除だけで）
**パスキーを消さない**（そのメソッドに `WebAuthn` の語が 1 件も出てこない）。つまり削除して同じ ID を作り直すと、
**前の持ち主のパスキーで新しい利用者としてログインできる**。

- 資格情報の文書に `accountId`・**`generation`**・`issuedAt` を足し、**登録のときに**そのときの
  `UserItem.getId()` と世代を書く（§3 の表のとおり**5 つ全部が両方の欄を書く**。照合するのは `accountId`
  だけ。確認 10 巡目の subagent P3 — ここだけ `generation` が落ちており、§3 の散文「欄が無ければ拒否」を
  素直に読むと**新規登録のパスキーが全部拒まれる**）。
- 認証（`WebAuthnResource` の finish/assertion の入口）で §3 の同じ判定を通す。
- **`deleteUser` と `deleteUserItem` がそのアカウントのパスキーを取り消す**（API キーと同じ扱い・同じ理由
  — 拒否は §3 の照合が受け持ち、取り消しは置き場の掃除。§4.1 と §4.7 を参照。「`deleteUser` を通らない
  削除への防御」と書いていたのは逆だった）。
- **E2E で測れる**（確認 7 巡目の Codex P3 — 「実機の authenticator が要るので測れない」と書いたのは誤り）:
  `core/src/main/webapp/ui/tests/auth/passkey.spec.ts` は CDP の **virtual authenticator** を作って登録と
  永続化まで測っている。だから (a) 作り直した利用者が前の持ち主のパスキーで入れないこと、(b) 新しく登録した
  パスキーでは入れること を**既存の E2E を広げて**測る。単体の錠は登録と認証の入口（`WebAuthnResource`）に
  置き、R166 の「パスキーの入口の錠が無い」もこのバッチで閉じる。

### 4.6 RSS のフィードのトークン — **置き場の棚卸しで出た 5 つ目**（2026-10-16、確認 7 巡目の待ち時間に読んで確かめた）

設計書の §4 は 4 つの置き場しか挙げていなかったが、資格情報の置き場は 5 つある（探索語: `model/` の
`token|key|cred|session`、全 package の `class .*Token` / `class .*Credential` / `class .*Session`）。
`RssToken`（`rss/RssToken.java`・`CouchRssToken`）は `generateToken(repositoryId, userId, …)` で作られ
`getByUserId` で引かれる — **利用者の名前にだけ結ばれており、R170 と同じ形**（同じ利用者 ID を作り直すと、
前の持ち主のフィードのトークンが新しい利用者のものとして通りうる）。`createdAt` を持つので世代も足せる。

**いまは単独の資格情報として効かない**: R162（P2、記録済み）のとおりフィードの URL は認証フィルタの後ろに
在り、トークンだけでは読めない（フィルタが先に 401 にする）。だから穴は**いま不活性**だが、R162 を直して
「トークンだけで読める」ようにした瞬間に生きる。

- **このバッチに入れる**: `accountId` と `generation` を足し（`generation` の照合はしない — パスワードから
  導かれた資格情報ではないので §3 の表の API キー・パスキーと同じ扱い）、引き当てで `accountId` を照合し、
  `deleteUser` が取り消す。
- 入れないなら、**R162 を開けるときの前提条件**として残件に書く（「R162 を開けるなら、先に RSS のトークンを
  `accountId` に結ぶ」）。どちらでも黙って落とさない。

### 4.7 `deleteUser` の取り消し — 足す場所と順（読んで確かめた）

`ContentServiceImpl.deleteUser(repositoryId, userId, actor)` は `refuseProtectedUser` →
`getUserItemById` → `removeUserFromAllGroups` → `delete(SystemCallContext, …)` の 4 段で、**資格情報には
一切触れていない**（`WebAuthn`・`ApiKey`・`Token`・`RssToken` の語が 1 つも無い）。取り消しは **`delete` の後**（下の「順の理由」）。`user.getId()` は削除の前に取っておく（記録と件数のため。
引き当ては `userId` — §4.1）。取り消す対象:

1. API キー（`ApiKeyService` にアカウント単位の取り消し）— **入れた**
2. パスキー（`WebAuthnCredential` を利用者 ID で消す）— **入れていない**（2026-10-19 に実測して訂正。
   `revokeCredentialsOf` は API キーと RSS フィードトークンだけで、`ContentServiceImpl` に `WebAuthn` の語は
   1 件も無い。止めているのは照合（`WebAuthnResource` の `credentialBinding`）なので**認証は通らない**が、
   棚卸しには誰も使えない行が残る。残件 R198 — R170 の確認 1 巡目に subagent が見つけた）
3. RSS のトークン（§4.6）
4. セッションのトークンと MCP のセッションは**対象外**（各 JVM のメモリで、要求のたびの読みで既に拒まれる）

**CMIS の削除も同じことをする**（確認 10 巡目の Codex P2）: CMIS から利用者を消す経路は
`ContentServiceImpl.deleteUserItem`（923 行）で、**`deleteUser` を通らない**。両方に置く。置かないと
「削除で取り消す」という文がその経路で偽になる（認証は §3 の照合が拒むので security の穴ではなく、
置き場に使えない行が残る）。

**順の理由（確認 10 巡目の Codex P1 で逆だと分かった — 直した）**: **先に削除し、そのあとで取り消す。**

- 私が最初に書いた順（取り消し → 削除）は、**削除が失敗したときに利用者だけが残り、その人の API キーと
  パスキーが取り返しのつかない形で消える**。security の利得は無い。
- 逆の順（削除 → 取り消し）で取り消しが落ちても、**認証は戻らない**: §3 の照合が、利用者が居なければ
  `ACCOUNT_CANNOT_ACT` で拒み、同じ利用者 ID を作り直しても**文書 id が違う**ので `WRONG_ACCOUNT` で拒む。
  残るのは「CouchDB に使えない行が残る」ことだけで、それは掃除の問題。
- つまり取り消しは**最良努力の掃除**であって、R170 を閉じているのは §3 の照合。設計の §4.1 と §4.6 が
  「取り消しは `deleteUser` を通らない削除への防御」と書いていたのも同じ誤り（照合がそれを覆う）ので、
  取り消しの位置づけを「置き場に使えない行を残さないため」に書き直す。

### 4.8 アップグレードの波及（fail-closed の帰結。確かめた）

オーナーの回答 1（この版より前の資格情報は拒否）で止まるものを数えた:

- `qa-test.sh` は全部 `-u admin:admin`（毎回パスワードのログイン）→ **影響なし**。
- TCK は Basic 認証 → **影響なし**。
- Playwright は global setup でログインしてトークンを取る（`AUTH_TOKEN` を読む 2 本も run の中で取得）
  → **影響なし**。
- 止まるのは**既存のデータに残っている**資格情報。ただし**置き場で分けて考える**（確認 7 巡目の subagent
  P3 — 「4 つの置き場が全部止まる」は §1 の表と噛み合っていなかった）:
  - **API キー**（CouchDB。無期限がありうる）と**パスキー**（CouchDB）と（§4.6 を入れるなら）**RSS の
    フィードのトークン**（CouchDB）は**残っているので止まる** → 棚卸しと再発行・再登録が要る。
  - **セッションのトークンと MCP のセッション**は各 JVM のメモリなので、更新の再起動で**どのみち消える**
    （再ログインは更新のたびに起きていること）。棚卸しの対象ではない。
- 名指すべき出口（確認 7 巡目の subagent が挙げた）: [`docs/MCP-SERVER.md`](../MCP-SERVER.md) の
  `nemakiware_apikey_login` と、画面の API キーの管理（`core/src/main/webapp/ui/src/services/apiKey.ts`）。
- `PrincipalBatchApplier`（§4.4 の書き込み元の 1 つ）が世代を上げると、**是正がパスワードを実際に変えた
  利用者のトークンが失効する**（再ログインになる）。**オーナー回答 6（2026-10-16）: 上げる**（実際に変えた
  人だけが対象なので正しい帰結）。規模が見えるよう、**失効させた利用者の数を是正の結果と preview に出す**。
- したがって運用手順に: 上げる前に**API キーで動いている自動処理の棚卸し**、上げた後に**再発行**と
  パスキーの**再登録**。RELEASE_NOTES に太字で書く（§3）。

## 5. 錠（revert→fail で測る）

- 削除して**同じ利用者 ID で作り直す**と、前のトークン・API キー・MCP のセッションが拒まれる（3 本）。
- 作り直した**新しい**利用者が自分で取った資格情報は通る（過剰拒否の向き、3 本）。
- パスワードを変えると、変える前のトークンと MCP のセッションが拒まれ、**変えた後に取ったものは通る**（4 本）。
- **同じミリ秒**でも正しく拒まれる（世代の整数で比べているので時計に依存しない。1 本 — 時刻の比較に戻す
  細工で落ちる control を置く）。
- パスワードを変えても **API キーとパスキーは通る**（§3 の表。2 本）。
- `deleteUser` がその利用者の API キーを取り消し、**ほかの利用者のキーは取り消さない**（2 本）。
- **`accountId` が無い資格情報は拒まれる**（オーナー判断 2026-10-16 — fail-closed）。トークン・
  API キー・パスキー・MCP のセッションの **4 つ**でそれぞれ 2〜4 本、**RSS は §4.6 を入れるなら 5 つ目**
  （確認 11 巡目の Codex P2 で「5 つすべて」と書いたが、§4.6 自体がオーナー未決なので条件つきに戻した —
  確認 12 巡目の subagent P3。**未決 7** に出した）。**この錠は「意図した拒否」を書く**ので、
  同じ入力が通ることを表明している既存の錠が在れば書き換える（バッチ 17 の錠を `accountId` を持つ資格情報に
  直す必要が在るか、実装の前に grep で確かめる）。
- **パスキー**（オーナー判断 2026-10-16 — このバッチに入れる）: 作り直した利用者が前の持ち主のパスキーで
  入れない、登録のときに `accountId` が書かれる、`deleteUser` がそのアカウントのパスキーを取り消す（3〜4 本）。
  **E2E でも測る** — 既存の `passkey.spec.ts` は CDP の virtual authenticator を使っているので、作り直した
  利用者が前のパスキーで入れないことと、新しく登録すれば入れることを広げて測れる（§4.5）。
- **RSS のフィードのトークン**（§4.6 を入れるなら）: 作り直した利用者が前の持ち主のトークンで引けない（1 本）。
  **この 1 本では足りない**（確認 11 巡目の Codex P2）: `deleteUser` の掃除だけ実装して照合を入れなくても、
  作り直しの錠は掃除で緑になる（前の持ち主のトークンがもう無いので引けない）。**照合そのものを測る錠**を
  別に置く — 掃除を通さずに**名前の解決先が別の文書に移った**状態（古いトークンは残っている）で引いて
  拒まれること、および `accountId` が無いトークンが拒まれること（2 本）。
  [[a-sibling-arm-of-the-same-check-satisfies-the-lock]] の形。
- **`deleteUser` の順**: `delete` が失敗したときに「資格情報だけ消えて利用者が残る」側に倒れる（1 本 —
  順を入れ替える細工で落ちる）。
- 入口を通すこと: トークンと API キーは `AuthenticationServiceImpl.login`、MCP は `authenticate`。
  `a-control-runs-one-test-class-only` があるので、置き場ごとに control の ID を分ける。

直す前の製品（`072a3c00b`）に当てて、落ちる本数と内訳を**錠を足し終えた後に**測る
（`old-product-count-after-the-last-lock`）。

## 6. 未決（オーナーに） — **6 件すべて 2026-10-16 に回答済み**（1〜4 は確認 6 巡目、5・6 は確認 7 巡目に出て同じ日に回答）

1. **この版より前に発行した資格情報**（`accountId` が無い）をどうするか。
   - (a) 名前だけで通す（これまでと同じ。アップグレードで何も壊れない。作り直しの穴は `deleteUser` の取り消しで塞ぐ）
   - (b) 拒否する（fail-closed。**アップグレードした瞬間に、既存の API キーとトークンが全部止まる**）
   - 推奨は **(a)** だった（過剰拒否の害のほうが大きいという判断）。
   - **オーナーの回答（2026-10-16）: (b) 拒否する（fail-closed）。** 上げた瞬間に既存の API キー・トークン・
     パスキー・MCP のセッションが全部止まる。§3 に書いたとおり、RELEASE_NOTES と運用手順に太字で書き、
     上げる前の棚卸し（API キーで動いている自動処理）を手順にする。
2. **`validateApiKey` の形**を変えるか（`userId` ではなく `ApiKey` を返す）。呼び出し側は 3 か所
   （`AuthenticationServiceImpl`・MCP の 2 か所）。変えないなら `ApiKeyService` の中で利用者を読む（責務が増える）。
   - 推奨: `ApiKeyService` の中で判定する（呼び出し側の形を変えない。キーの持ち主を知っているのはキーの側）。
   - **オーナーの回答（2026-10-16）: `ApiKeyService` の中で判定する。**
3. **パスワード変更で API キーも取り消すか**（外部レビュー S3 はそう書いている / 自動処理が止まる）。
   - 推奨: **取り消さない**。トークンと MCP のセッションだけ。RELEASE_NOTES に明記し、乗っ取りの後始末の手順
     （キーを棚卸しして取り消す）を書く。
   - **オーナーの回答（2026-10-16）: 取り消さない。** 外部レビュー S3 の指摘はこの判断で閉じる（残件に理由を書く）。
4. **パスキー（WebAuthn の資格情報）も同じ規則に入れるか。同じ穴が在ることをコードで確かめた** —
   `getWebAuthnCredentialsByUserId(repositoryId, userId)` で**利用者名の文字列**で引いており、
   `ContentServiceImpl.deleteUser` は（グループからの除去と利用者の文書の削除だけで）**パスキーを消さない**
   （`WebAuthn` の語が 1 件も出てこない）。つまり削除して同じ ID を作り直すと、**前の持ち主のパスキーで
   新しい利用者としてログインできる**（実機では測っていない）。
   - 推奨: このバッチに入れる（結ぶ鍵は同じ `accountId`。`deleteUser` の取り消しも API キーと同じ扱い）。
     範囲が増えるので、入れるかはオーナーの判断。入れないなら残件に P1 として記録する。
   - **オーナーの回答（2026-10-16）: このバッチに入れる。** したがって §4 に 4.5（パスキー）を足す — 登録のときに
     `accountId` と `issuedAt` を書き、認証のときに同じ判定を通し、`deleteUser` がそのアカウントのパスキーを
     取り消す。**実機では測らない**（WebAuthn の登録は実機の authenticator が要る）ので、錠は登録と認証の
     入口を単体で通す形にし、「実機未測定」を文書に書く。

### 未決 5（確認 7 巡目の subagent P2）— multi-replica のローリング更新

`accountId`（と世代）の無い資格情報を拒むのは、**全部のレプリカが新しい版になってから**でないと、
古いレプリカが発行し続けるキーを新しいレプリカが拒む（更新中に作った API キーが使えない）。選択肢:

- (a) **全停止で更新する**ことを運用手順に書く（いまの手順はローリングを認めている）。
- (b) **2 段で入れる** — この版は欄を**書く**だけ（拒まない）、次の版で拒む。fail-closed の効き始めが
  1 版遅れる代わりに、更新中の窓が無い。
- (c) 拒否を**設定で切り替える**（既定は拒否。更新中だけ緩める）。設定が増える。

オーナーの回答 1（fail-closed）はそのままで、**いつから拒むか**だけの判断。推奨は (a)。
**オーナーの回答（2026-10-16）: (a) 全停止で更新する。** 運用手順（[`v3.4.0-upgrade-runbook.md`](../operations/v3.4.0-upgrade-runbook.md)
と 3.4.1 の手順）に「この版への更新はローリング不可 — 全レプリカを止めてから上げる」を書く。
**ローリングの手順そのものが書いてある [`docs/MULTI-REPLICA-DEPLOYMENT.md`](../MULTI-REPLICA-DEPLOYMENT.md)
（281・317 行）にも同じ断りを書く**（確認 10 巡目の subagent P2 — そこだけ読んだ運用者が順次入替をすると、
更新中に発行した資格情報が拒まれる）。
**逆向きの窓も同じ理由で閉じる**（確認 8 巡目の subagent P3）: 新しいレプリカが上げた世代を、欄を知らない
古いレプリカの**文書全体の更新**が落とす形。全停止ならどちらも起きない。

### 未決 6（確認 7 巡目の subagent P3）— 一括プリンシパル是正が世代を上げるか

`PrincipalBatchApplier` がパスワードを書くとき世代を上げると、**是正が触った利用者全員のトークンが失効**
する（その人たちは何もしていないのに再ログインになる）。選択肢: (a) 上げる（パスワードを変えたのだから
正しい）、(b) 上げない（是正は管理操作で、パスワードを変えていない利用者の資格情報を切らない）。
推奨は **(a)**。**オーナーの回答（2026-10-16）: (a) 上げる。** 是正がパスワードを**実際に変えた**利用者
だけが対象なので、再ログインは正しい帰結。一括なので規模が見えるよう、**是正の結果に「失効させた利用者の
数」を出す**（preview にも出す — 適用の前に規模が分かるように）。

### 未決 7（確認 12 巡目の subagent P3）— RSS のフィードのトークンをこのバッチに入れるか

§4.6 は「このバッチに入れる」と「入れないなら R162 の前提条件として残件に書く」の**両方**を並べたままで、
パスキー（未決 4）と違ってオーナーの判断の印が無い。§5 の錠の数（4 つの置き場か 5 つか）がこれで決まる。

- (a) **入れる** — `RssToken` に `accountId` と世代を足し、引き当てで照合し、削除で取り消す。錠は 5 つ目の
  置き場として 3 本（作り直し・`accountId` 無し・**掃除を通さない名前の解決先の移動**）。いま不活性な穴
  （R162 のとおりフィードの URL は認証フィルタの後ろ）を先に閉じる。
- (b) **入れない** — 残件に「R162 を開けるなら、先に RSS のトークンを `accountId` に結ぶ」と書いて止める。
  このバッチの出荷物は 4 つの置き場。

**推奨は (a)**（置き場ごとに規則が違うほうが後で間違えやすく、R162 を直す人がこの前提を読み落とす危険が
あるため）。ただしバッチの大きさは増える。

## 7. 入れないもの

- 共有のセッションの置き場、短命トークンとリフレッシュ、全員のセッションの一括取り消し — ロードマップ (1)
  （`zero-trust-roadmap.md`）。この設計は**いまの置き場のまま**で R170 を閉じる。
- `nemaki:credentialGeneration` を使った「管理者が 1 人の資格情報を全部無効にする」操作の API — 上と同じ。
