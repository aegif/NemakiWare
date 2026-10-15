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

加えて、**発行の時刻**（`issuedAt`）を資格情報に記録する。「作り直し」は id で捕まえ、「パスワードを変えた」は
時刻で捕まえる。どちらも**利用者の側に在る値**と比べるので、どのレプリカでも同じ答えになる
（バッチ 17 で、要求のたびに利用者を読んでいる — 新しい読みは増えない）。

## 3. 規則（1 つに揃える）

利用者の側に 1 つ欄を足す:

- **`nemaki:credentialsNotBefore`** — この時刻より前に発行した資格情報は受けない。パスワードを変えたときに「いま」を書く。

資格情報の側に 2 つ:

- **`accountId`** — 発行したときの `UserItem.getId()`。
- **`issuedAt`** — 発行の時刻。

判定（`AuthenticationUtil` に置き、`mayStillAct` の隣で同じ利用者の読みを使う）:

```
boundToThisAccount(credential, account):
  account が無い / disabled              → 拒否（バッチ 17 のまま）
  credential.accountId が在る
      かつ account.getId() と不一致       → 拒否（作り直し・ID の使い回し）
  account.credentialsNotBefore が在る
      かつ credential.issuedAt が
         それより前（または issuedAt 無し） → 拒否（パスワードを変えた後の古い資格情報）
  それ以外                                → 通す
```

**`accountId` が無い資格情報（この版より前に発行したもの）をどうするか**は §6 の未決 1。

## 4. 置き場ごとの変更

### 4.1 API キー（いちばん durable。無期限のキーが在る）

- `ApiKey` と `CouchApiKey` に `accountId`・`issuedAt` を足す（`issuedAt` は既存の `getCreated()` で足りるなら再利用する — 実装時に確かめる）。
- 作成時に、そのときの `UserItem.getId()` を書く。
- `validateApiKey` が返すのは今も `userId` だけなので、**判定は呼び出し側（`AuthenticationServiceImpl.loginWithApiKey`・MCP）には置けない**。
  `ApiKeyService` が利用者を読んで判定するか、`validateApiKey` が `ApiKey` を返す形にするかを選ぶ（§6 の未決 2）。
- **さらに、`deleteUser` がその利用者の API キーを取り消す。** 結びだけでは、この版より前のキー（`accountId` が無い）を
  守れない。取り消しなら、古いキーも含めて消える。両方やる（取り消しが主、結びは `deleteUser` を通らない削除
  — 利用者の文書を CMIS で直接消す経路 — に対する防御）。

### 4.2 セッションのトークン

- `Token` に `accountId`・`issuedAt` を足す。
- **`TokenServiceImpl` には既に `contentService` が注入されている**（`serviceContext.xml`）ので、`set()` の中で
  利用者を読んで `accountId` を書ける。**`setToken` の呼び出し側を 1 つも変えなくてよい** — 実測した内訳は
  `AuthResource` 3・`AuthTokenResource` 6・`WebAuthnResource` 1 の **10 か所**（探索語 `tokenService.setToken`・`ts.setToken(`、
  `core/src/main/java` 配下、interface と実装を除く）。**訂正**: 最初に書いた 12 か所には `SamlSignatureVerifier` の 2 件を
  数えていたが、あれは**コメント**で呼び出しではない（grep の件数をそのまま書いた誤り）。SAML のトークンは
  `AuthTokenResource` の 6 件のうちの 1 つで出る。
- 判定は `AuthenticationServiceImpl.loginWithToken`（バッチ 17 で利用者を読んでいる場所）。

### 4.3 MCP のセッション

- `McpSession` に `accountId`・`issuedAt` を足し、`login`・`loginWithApiKey`・`registerSessionToken` で書く。
- 判定は `authenticateSessionToken`（バッチ 17 の `mayStillAct` の隣）。拒んだセッションは今と同じに捨てる。

### 4.4 パスワード変更で失効させる

`nemaki:credentialsNotBefore` に「いま」を書く場所を、パスワードを変える**すべての**経路に置く。
実測した書き込み元は **9 か所 / 5 ファイル**（探索語 `setPassowrd(`、`core/src/main/java` 配下、`model/` を除く）:

- `rest/UserItemResource.java` — 4（`changePassword`・`update`・`update-json` ほか）
- `api/v1/resource/UserResource.java` — 2
- `api/v1/principals/PrincipalBatchApplier.java` — 1（一括プリンシパル是正）
- `rest/controller/UserController.java` — 1（Spring MVC）
- `cmis/factory/auth/impl/AuthenticationServiceImpl.java` — 1（**MD5 → BCrypt の自動アップグレード。これは
  利用者が変えたのではないので、`credentialsNotBefore` を書いてはいけない** — 書くと、ログインした本人の
  トークンがその場で無効になる）

**探索語の盲点（この棚卸しの限界）**: `setPassowrd(` だけで数えると、**利用者文書に BCrypt のハッシュを直接書く 2 つの経路が
落ちる** — `api/setup/resource/SetupAdminResource.java:108`（`POST /api/v1/setup/admin/change-password`。主リポジトリの admin 文書を
更新する）と `api/setup/resource/SetupApplyResource.java:484`（`changeAdminPassword`）。どちらも `UserItem.setPassowrd` を通らない。
`BCrypt.hashpw` で数えると 9 件が出て、そのうちこの 2 件が `setPassowrd` の外にある。**到達性**: `SetupModeGuardFilter` が
Normal Mode では 403 を返すので Setup Mode 限定。

**書く場所は 5 ファイルに散っており、さらに上の 2 つが別の語彙で書くので、1 か所（`ContentService` の保存の手前か専用の
ヘルパー）に集めて、そこを通らないパスワード変更が無いことを錠で測る。錠は `setPassowrd` と `BCrypt.hashpw` の両方の語彙で
数えること** — 片方だけで数えた棚卸しから書いた錠は、偽の保証になる（確認 2 巡目の subagent P2）。

**API キーはパスワード変更で取り消さない**（自動処理が黙って止まる）。トークンと MCP のセッションだけを失効させ、
「API キーは別の資格情報で、明示的に取り消す」と RELEASE_NOTES に書く — これは §6 の未決 3（外部レビュー S3 は
「パスワード変更・削除・無効化のときに、トークン、API キー、MCP セッションを失効させる」と書いている）。

## 5. 錠（revert→fail で測る）

- 削除して**同じ利用者 ID で作り直す**と、前のトークン・API キー・MCP のセッションが拒まれる（3 本）。
- 作り直した**新しい**利用者が自分で取った資格情報は通る（過剰拒否の向き、3 本）。
- パスワードを変えると、変える前のトークンと MCP のセッションが拒まれ、**変えた後に取ったものは通る**（4 本）。
- パスワードを変えても API キーは通る（§6 の未決 3 の答えどおりに書く）。
- `deleteUser` がその利用者の API キーを取り消し、**ほかの利用者のキーは取り消さない**（2 本）。
- `accountId` が無い資格情報の扱い（§6 の未決 1 の答えどおり、1〜2 本）。
- 入口を通すこと: トークンと API キーは `AuthenticationServiceImpl.login`、MCP は `authenticate`。
  `a-control-runs-one-test-class-only` があるので、置き場ごとに control の ID を分ける。

直す前の製品（`072a3c00b`）に当てて、落ちる本数と内訳を**錠を足し終えた後に**測る
（`old-product-count-after-the-last-lock`）。

## 6. 未決（オーナーに）

1. **この版より前に発行した資格情報**（`accountId` が無い）をどうするか。
   - (a) 名前だけで通す（これまでと同じ。アップグレードで何も壊れない。作り直しの穴は `deleteUser` の取り消しで塞ぐ）
   - (b) 拒否する（fail-closed。**アップグレードした瞬間に、既存の API キーとトークンが全部止まる**）
   - 推奨: **(a)**。この木の規則では「読めなかった」を「無い」と同じに扱わないが、ここは「この版より前に発行した」と
     確かに分かる（欄が無い）ので、過剰拒否の害のほうが大きい。RELEASE_NOTES に「既存のキーは作り直しの穴に対して
     `deleteUser` の取り消しだけで守られる。結び直すには再発行」と書く。
2. **`validateApiKey` の形**を変えるか（`userId` ではなく `ApiKey` を返す）。呼び出し側は 3 か所
   （`AuthenticationServiceImpl`・MCP の 2 か所）。変えないなら `ApiKeyService` の中で利用者を読む（責務が増える）。
   - 推奨: `ApiKeyService` の中で判定する（呼び出し側の形を変えない。キーの持ち主を知っているのはキーの側）。
3. **パスワード変更で API キーも取り消すか**（外部レビュー S3 はそう書いている / 自動処理が止まる）。
   - 推奨: **取り消さない**。トークンと MCP のセッションだけ。RELEASE_NOTES に明記し、乗っ取りの後始末の手順
     （キーを棚卸しして取り消す）を書く。
4. **パスキー（WebAuthn の資格情報）も同じ規則に入れるか。同じ穴が在ることをコードで確かめた** —
   `getWebAuthnCredentialsByUserId(repositoryId, userId)` で**利用者名の文字列**で引いており、
   `ContentServiceImpl.deleteUser` は（グループからの除去と利用者の文書の削除だけで）**パスキーを消さない**
   （`WebAuthn` の語が 1 件も出てこない）。つまり削除して同じ ID を作り直すと、**前の持ち主のパスキーで
   新しい利用者としてログインできる**（実機では測っていない）。
   - 推奨: このバッチに入れる（結ぶ鍵は同じ `accountId`。`deleteUser` の取り消しも API キーと同じ扱い）。
     範囲が増えるので、入れるかはオーナーの判断。入れないなら残件に P1 として記録する。

## 7. 入れないもの

- 共有のセッションの置き場、短命トークンとリフレッシュ、全員のセッションの一括取り消し — ロードマップ (1)
  （`zero-trust-roadmap.md`）。この設計は**いまの置き場のまま**で R170 を閉じる。
- `nemaki:credentialsNotBefore` を使った「管理者が 1 人の資格情報を全部無効にする」操作の API — 上と同じ。
