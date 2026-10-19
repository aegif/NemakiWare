# R175 + R168 — SSO のログインが既存のアカウントに入れる形（設計、2026-10-19）

オーナー指示（2026-10-19）: **R175 と R168 は 1 つの設計**。制約 4 つ:

- (a) `isAuthMethodAllowed` の「未設定 ⇒ 許可」は**変えない**。変えるのは **IdP の 4 腕**と、
  **クラウドディレクトリ同期が利用者を作る場所**。
- (b) 既存アカウントに入れるのは、**すでにその issuer+subject に結ばれている**か、
  **管理者が方式を明示した**ときだけ。**未設定 ≠ 採用**。
- (c) SAML・Google・Microsoft・ディレクトリ同期が作った利用者には**経路の印**を付け、
  **汎用 OIDC の最初のログインがそれを結んではいけない**（結ぶのは管理者の操作）。
- (d) 結びの書き込みは**読み戻して突き合わせる**。空の `oidcSubject` は**拒否**であって「未結合」ではない。

## いまの形（読んで確かめた。file:line は 2026-10-19 の `a6238fcf4`）

| 経路 | 入口 | 既存アカウントに入る条件 | 作るときに書く印 |
|---|---|---|---|
| 汎用 OIDC（旧 REST） | `AuthTokenResource:~640`（`/authtoken/oidc/convert`） | `OidcLogin.refusal` → **その後** `isAuthMethodAllowed(userItem,"cloud")`（`:676`） | `allowedAuthMethods=cloud` ＋ `nemaki:oidcSubject` |
| 汎用 OIDC（api/v1） | `AuthResource:~481`（`unlinked` → 結びを書く → `:495` の `isAuthMethodAllowed`） | 同（写し） | 同 |
| SAML | `AuthTokenResource:532` | **`isAuthMethodAllowed(userItem,"cloud")` のみ** → 未設定で true（R175） | `allowedAuthMethods=cloud`（結びなし） |
| Google | `AuthTokenResource:787` | 同 | 同 |
| Microsoft | `AuthTokenResource:936` | 同 | 同 |
| クラウドディレクトリ同期 | `CloudDirectorySyncServiceImpl:359/414/681/727` | 作成 **と更新** | `allowedAuthMethods=cloud`（結びなし。乱数のパスワード） |

**R175**: 3 腕は `getOrCreateUser(repositoryId, userName)`（2 引数）が既存をそのまま返し、唯一の扉が
未設定で true になるので、組み込みの `admin`・サービスアカウント・パスワードで作った利用者に入れる。

**R168**: 汎用 OIDC の「`cloud` が明示されていれば採用」は、**`cloud` を書くのが管理者だけではない**
ので成り立たない。しかも**同期は既存のアカウントの方式も書き換える**（`:399` / `:713` が
`existingItem` に `allowedAuthMethods` を渡す）— つまりパスワードで作った利用者が同期で `cloud` に
なり、そこから採用の対象になる。**「作る場所」だけでは足りない**（オーナー指示の (a) の範囲を、
読んだ結果として「作る場所と、方式を書き換える場所」に広げる必要がある — ここはオーナーに報告する点）。

## 設計

1. **経路の印**: `nemaki:authRoute` ∈ {`oidc`, `saml`, `google`, `microsoft`, `directory-sync`}。
   **作る場所と、方式を `cloud` に書き換える場所**が書く。既存のアカウントには無い（移行前）。

   **経路ごとの結び**（`nemaki:<route>Subject`。OIDC は互換のため `nemaki:oidcSubject` のまま）。
   値の出どころは全部もう在る（読んで確かめた）:

   | route | issuer | subject |
   |---|---|---|
   | oidc | `OidcLogin.binding(outcome)`（`iss|sub`、既存） | 同 |
   | google | `payload.getIssuer()` | `payload.getSubject()` |
   | microsoft | `claims.getIssuer()` | `claims.getStringClaim("oid")`（`sub` でも可。`oid` はテナント内で安定） |
   | saml | 署名済み subtree の `Issuer` 要素 | 同 subtree の `NameID`（`extractUserNameFromSAMLElement` の段 2 が既に読んでいる） |

   SAML だけ抽出関数が無いので 2 つ足す（各 8 行ほど。**署名済み subtree からだけ読む** — 既存の
   コメントが言う「未署名の兄弟 assertion の注入」を避けるため）。

2. **1 つの規則に寄せる**（`OidcLogin.refusal` を経路つきに広げる、または `SsoLogin.refusal`）:

   ```
   refusal(account, route, binding):           // binding = issuer|subject、無ければ null
     disabled                                   → 拒否
     この route の結びが在る                     → 一致のみ許可（不一致は拒否）
     ほかの route の結びが在る                   → 拒否（結び直しは管理者の操作）
     authRoute が在り、この route と違う          → 拒否（(c)）
     allowedAuthMethods に cloud が明示          → 許可（採用。管理者の判断、または移行前の値）
     未設定                                     → 拒否（(b)。ここが R175）
   ```

   `isAuthMethodAllowed` は触らない（(a)）。5 腕はこの規則を呼ぶ。

3. **移行前のアカウント**（印が無く `cloud` が明示）は**採用を許す**。「管理者が付けた」と
   「古い経路が付けた」を木の中では区別できず、ここで拒むと既存の配備の SSO が止まる（過剰拒否）。
   **RELEASE_NOTES に書く**: 印の無い `cloud` のアカウントは、最初に名乗った IdP に結ばれうる。
   棚卸し（`nemaki:authRoute` が無く `cloud` の利用者を一覧する）の手順を添える。
   — **未決（オーナー判断）**: ここを拒否側に倒すか。倒すと、同期で作った利用者が上げた直後に
   全部ログインできなくなる（印は上げた後の同期で付く）。

4. **結びの書き込みは読み戻す**（(d)）: 書いた後に `getUserItemById` で読み直し、**空でない**ことと
   **書いた値と一致**することを確かめ、一致しなければログインを拒否する。
   いまは `applyUserUpdate` の後に何も確かめていない — その保存は `ProtectedPrincipals` で拒否されうる
   （残件 R196: 拒否された保存は**キャッシュの実体**に書き込みを残す）ので、**保存されていない結びを
   「結んだ」として通す**形が在る。空の `subject` は拒否（採用に落ちない）。

5. **錠**（本番の入口を通す）: 腕ごとに「未設定では入れない」「`cloud` 明示なら入れる」「ほかの経路の
   印が在れば入れない」「結びが一致すれば入れる・違えば入れない」、汎用 OIDC が
   `directory-sync` の印の利用者を結ばない、書き戻しの検査（読み戻しが別の値／空を返す fixture で拒否）、
   同期が**既存のアカウント**に `cloud` を書くときも印を書く。
   **control**: `refusal` の各行 1 本以上、印の生産者を消す細工、読み戻しの検査を外す細工。

## 範囲外（この設計に入れない）

- `isAuthMethodAllowed` の意味（(a)）。
- R169（LDAP 同期の利用者を汎用 OIDC が拒む後退と、結びを解く手段が無いこと）。2 の規則で
  「ほかの route の結び → 拒否」を入れると、解く管理操作がいよいよ要る。**未決**: 管理 API を足すか、
  残件に残すか（R169 は open のまま）。
- R196（拒否された保存がキャッシュを毒す）は 4 の読み戻しで**この経路だけ**救われるが、
  残件そのものは閉じない（ほかの 3 か所が残る）。
