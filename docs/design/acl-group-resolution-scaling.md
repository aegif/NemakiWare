# 大規模組織 (10 階層 / 4 万ユーザ) での ACL-in-Solr のスケーリング

検証: 2026-08-09、`nb33` スタック (3.3.0)。以下の数値はすべて稼働中のサーバでの実測です。

**結論を先に**: 書き込み側 (索引) は問題ありません。問題は読み取り側で、
非 admin の CMIS query 1 本ごとに全グループを深さ優先探索していました。
グループ 1,013 個で 830 ms / クエリ、キャッシュ無し。

> **解決済み (2026-08-09)**: 原因は「実装が 2 つあり、`ACLExpander` だけが遅い方を
> 使っていた」ことでした。§7 を参照。**グループ 2,908 個で 17 ms** になり、
> グループ数への依存も消えています。以下 §1–§6 は調査当時の記録です。

---

## 1. 書き込み側 — user トークンの埋め込みは起きない

`AclSemantics.readerTokens`
([AclSemantics.java](../../core/src/main/java/jp/aegif/nemaki/acl/AclSemantics.java))
は **ACE 1 件につきトークンを 1 個**しか出しません。判定順は

1. `cmis:anyone` / `cmis:anonymous` リテラル → `anyone` トークン
2. principal が **USER** として解決できる → `user:{repo}:{id}`
3. principal が **GROUP** として解決できる → `group:{repo}:{id}`
4. どちらでもない → **drop**

**group はメンバー展開されません。** 実測した `readers` の例:

```
['group:bedroom:GROUP_EVERYONE', 'user:bedroom:system', 'user:bedroom:admin']
```

4 万人が所属するグループに ACL を与えても、文書に載るのは
`group:bedroom:<そのグループ>` の**1 トークンだけ**です。

> RELEASE_NOTES が「旧ビルドの索引は member-expanded `user:` トークンを持つ」と
> 書いているのは **v3.3 以前**の話で、これが全再索引を必須にしている理由の 1 つです。
> 現行ビルドは展開しません。

したがって **索引サイズ・書き込み費用はユーザ数に依存しません。** 依存するのは
文書あたりの ACE 数だけです。

---

## 2. 読み取り側 — ここが問題

非 admin のクエリでは `ACLExpander.buildReaderTokenSet` が呼ばれ、その中で
`PrincipalServiceImpl.getGroupIdsContainingUser`
([PrincipalServiceImpl.java](../../core/src/main/java/jp/aegif/nemaki/businesslogic/impl/PrincipalServiceImpl.java))
が走ります。実装はこうです。

```java
List<Group> groups = getGroups(repositoryId);      // 全グループを列挙
for (Group g : groups) {
    if (containsUserInGroup(repositoryId, userId, g, new HashSet<String>())) {
        groupIds.add(g.getGroupId());
    }
}
```

- **全グループを列挙**し、各々を深さ優先で walk する
- `visited` は**トップレベルごとにリセット**されるので、同じ部分木を何度も辿る
- 再帰の各段で `getGroupById` を呼ぶ

計算量は O(G × S) (G = グループ数、S = 部分木サイズ)。**ユーザが 1 つのグループに
しか属していなくても、全グループを走査します。**

### 実測 — グループ数に対して線形

深さ 10 の鎖を横に並べ、`probeuser` を**最後の 1 本の葉**にだけ入れた状態
(同一クエリ `SELECT cmis:objectId FROM cmis:document WHERE cmis:name = '...'`):

| グループ数 | probeuser の p50 | admin の p50 |
|---|---|---|
| 13 | 20 ms | 5 ms |
| 263 | 210 ms | 5 ms |
| 513 | 444 ms | 5 ms |
| **1,013** | **830 ms** | 6 ms |

**約 0.82 ms / グループ**。admin は fq をバイパスするので影響を受けません。

外挿すると:

| グループ数 | 予想 p50 |
|---|---|
| 2,000 | ~1.6 s |
| 5,000 | ~4.1 s |
| 10,000 | ~8.2 s |

**クエリ 1 本ごとに、非 admin ユーザ全員が払います。**

### 費用は I/O ではなく CPU

グループ 1,013 個の状態で 1 クエリ (868 ms) の CouchDB 往復は **2 回だけ**:

```
1 POST /_view/userItemsById
1 POST /_view/groupItemsById     ← 全グループを 1 回で取得
```

つまりグループ情報は 1 回の view クエリで取れており、**830 ms は全部インメモリの
深さ優先探索**です。しかも **2 回目も 757 ms** で、結果はキャッシュされていません。

### 影響範囲は CMIS query だけ

グループ 1,013 個の状態での操作別 p50:

| 操作 | probeuser | admin |
|---|---|---|
| `getObject` | 5 ms | 6 ms |
| `getChildren` | 4 ms | 5 ms |
| **`query`** | **742 ms** | 4 ms |
| `repositoryInfo` | 2 ms | 2 ms |

フォルダ閲覧・文書取得は影響を受けません。**検索だけ**です。

---

## 3. 10 階層 / 4 万ユーザの組織で何が起きるか

ユーザ数そのものは効きません。効くのは**グループ数**です。

10 階層の組織で、各階層が平均 4 分岐なら 4^10 ≒ 100 万グループ — 現実にはそこまで
分岐しませんが、部・課・チーム・プロジェクト・権限グループを合わせて
**数千オーダー**になるのは珍しくありません。

- グループ 2,000 個 → 検索 1 本あたり **約 1.6 秒**
- グループ 5,000 個 → 検索 1 本あたり **約 4 秒**

これは Solr に問い合わせる**前**に消える時間で、同時実行すれば CPU を占有します。
16 並列の検索なら 16 コアを 4 秒間使い切る計算になります。

**深さ (10 階層) 自体は主因ではありません。** 主因はグループの総数です。
深さは `containsUserInGroup` の再帰段数に効きますが、支配項は
「全トップレベルグループを走査する」外側のループです。

---

## 4. 対策

### 4-1. 探索の向きを逆にする (推奨)

`joinedDirectGroupsByUserId` という view が**既に存在します**
([Patch_StandardCmisViews.java:134](../../core/src/main/java/jp/aegif/nemaki/patch/Patch_StandardCmisViews.java))。
これはユーザ ID から**直接所属するグループ**を引く view です。

現行は「全グループから下向きに user を探す」ですが、これを
「user から上向きに親グループを辿る」に変えれば、計算量は
O(G × S) から **O(ユーザが所属するグループ数 × 深さ)** になります。
10 階層なら 1 ユーザあたり高々数十ノードです。

注意点: 上向きに辿るには「あるグループを含む親グループ」を引く必要があり、
その逆引き view が要ります (現行の `joinedDirectGroupsByUserId` は
user → group であって group → 親 group ではない)。

### 4-2. 解決結果をキャッシュする (即効性がある)

`getGroupIdsContainingUser` の結果を短 TTL でキャッシュすれば、
2 回目以降が消えます。既に `VerifiedPasswordCache` で同じ形を入れており
(認証結果の短 TTL キャッシュ)、同じ作法が使えます。

失効の考慮: グループのメンバー変更・入れ子変更で無効化が要ります。
TTL を短く (30〜60 秒) すれば、失効の窓は認証キャッシュと同等になります。

### 4-3. `visited` をトップレベル間で共有しない設計の見直し

現行はコメントで「兄弟トップレベル間で共有すると別経路の一致を落とす」と
説明されており、これは正しい判断です。ただし
**「このユーザを含むグループ集合」を 1 回の走査で求める**アルゴリズムに変えれば、
共有・非共有の問題自体が消えます。

---

## 5. 追加実測 — 所属グループ数の上限は見つからなかった

「fq が GET の URL に載るので所属グループ 150 件程度で Solr のヘッダ上限に当たる」
という指摘を受けて実測しましたが、**1,200 グループまで壊れませんでした**。

| probeuser の直接所属グループ数 | 結果 |
|---|---|
| 50 | OK (869 ms) |
| 150 | OK (959 ms) |
| 300 | OK (843 ms) |
| 500 | OK (1,206 ms) |
| 800 | OK (1,401 ms) |
| **1,200** | **OK (1,976 ms)** |

所要時間はリポジトリ全体のグループ数 (この時点で 1,300〜2,200 個) に支配されており、
**所属数そのものはほとんど効いていません**。`maxBooleanClauses` にも URL 長にも
到達しませんでした。これ以上の規模は未確認です。

## 6. まだ確かめていないこと
- `GROUP_EVERYONE` の内部表現 (メンバー列挙か特別扱いか)。4 万人が列挙されていると
  `getGroups` が返す 1 オブジェクトが巨大になります
- 分岐のある実際の組織木 (本検証は深さ 10 の**鎖** 100 本)。分岐があると
  `containsUserInGroup` の walk はさらに増えます
- RAG / MCP 経由の検索が同じ経路を通るか

---

## 7. 解決 — 実装が 2 つあり、遅い方を使っていた (2026-08-09)

新しい view もアルゴリズムも要りませんでした。`getGroupIdsContainingUser` は
**2 つの独立した実装**を持っており、製品の大半は速い方を使っていたのに、
ACL-in-Solr の `ACLExpander` だけが遅い方を呼んでいました。

| | 実装 | 使っている場所 |
|---|---|---|
| 速い | `ContentService` → `UserGroupServiceDelegate` → `getJoinedGroupByUserId`。**逆引き view (`joinedDirectGroupsByGroupId`) で上向きに辿り、`joinedGroupCache` でキャッシュ** | `PermissionServiceImpl` (5 か所)、`CompileServiceImpl`、`NavigationServiceImpl`、`McpToolsProvider` |
| 遅い | `PrincipalService` → 全グループ列挙 + 各々を DFS。**どの層にもキャッシュ無し** | **`ACLExpander` のみ** (2 か所) |

§4-1 に「逆引き view の新設が要る」と書いたのは**誤り**でした。
`joinedDirectGroupsByGroupId` は `Patch_StandardCmisViews.java:136` に既存で、
上向き探索も `UserGroupDaoDelegate.checkIndirectGroup` に実装済みです。

`ACLExpander` は `contentService` を既に注入されているので、2 か所の呼び先を
差し替えるだけで済みました。

### 速度だけの問題ではない

**認可の実ゲートである `PermissionServiceImpl` は速い方を使っています。**
Solr の `readers` fq を別実装から計算していたということは、両者が食い違えば
「Solr が返すもの」と「in-memory が通すもの」がずれるということです。
整合性の観点でも、同じ実装を使うべきでした。

### 実測

| | グループ数 | probeuser の query p50 |
|---|---|---|
| 変更前 | 1,013 | 830 ms |
| **変更後** | **2,908** | **17 ms** |

グループ数が 2.9 倍の状態で 49 分の 1 です。外挿していた
「5,000 グループで約 4 秒」は解消しました。

10 階層の推移所属が維持されていることも確認済み (鎖の頂点にだけ read を与えた
文書が、葉に居るユーザから見える)。

---

## 追記 (2026-08-09): 「10 階層を確認済み」は当時は根拠が無かった

上の一文は**書いた時点では検証されていませんでした**。当時 fixture として作った
`chain-d0` … `chain-d9` は、CouchDB の `joinedDirectGroupsByGroupId` view の
`total_rows` が **0**、つまり**入れ子になっていませんでした**。10 個のグループを
作っただけで、親子関係が永続化されていなかったのです。

そのため 830 ms → 17 ms という数字は**フラットな 2,908 グループでの値**であり、
「ネストしても速い」ことは何も示していませんでした。ContentService 側の上向き
解決経路は一度も実走していません。

改めて、本当に連鎖したグループを作って測り直しました。

### 測り方

深さごとに独立した鎖を作ります。`d(i)` が `d(i+1)` を含み、ユーザは**最下位に
だけ**直接所属します。文書には**最上位グループにだけ** `cmis:read` を与え、
`breakInheritance` で継承を切り、`GROUP_EVERYONE` の ACE を除去します。

`GROUP_EVERYONE` を消し忘れると全員が読めてしまい、検査は何も証明しません
(この罠には過去 2 回はまっています)。**無関係なユーザを陰性対照に置く**ことで、
検査に識別力があることを毎回確かめています。

### 結果

| 鎖の深さ | 検索ヒット | getObject | 初回 (コールド) | 以降の中央値 |
|---|---|---|---|---|
| 1 | 1 | 200 | 106 ms | 8.6 ms |
| 10 | 1 | 200 | 247 ms | 7.6 ms |
| 30 | 1 | 200 | 623 ms | 7.9 ms |
| **55** | **0** | **403** | 1,044 ms | 6.8 ms |

陰性対照 (どの鎖にも属さないユーザ) は 0 件。Solr の `readers` は
`group:{repo}:{最上位グループ}` の **1 トークンのみ**で、メンバー展開はありません。

### 読み取れること

- **推移所属は本当に効いています。** 9 段上のグループに与えた read が、
  最下位のユーザに届き、getObject と検索の両方で一致します。
- **コールドコストは深さに比例します。** 1 段あたり約 18 ms。
  `UserGroupDaoDelegate.checkIndirectGroup` はフロンティアのグループ 1 個につき
  view クエリを 1 本発行し、しかもキーが `[groupId, 0..19]` の範囲なので
  **同じ親を 20 行受け取って 19 行捨てています**。10 階層なら 247 ms、
  一度解決すれば `joinedGroupCache` に載って 8 ms 前後に落ちます。
- **深さ 51 以上で静かに権限を失います。** `getJoinedGroupByUserId` の
  `maxIterations = 50` に達すると、`log.warn` を 1 行出すだけで**成功として
  返り**ます。呼び出し側は打ち切りを知る手段がありません。結果として上位
  グループの ACE が効かなくなり、検索は 0 件、getObject は 403 になります。
  getObject と検索が同じ実装を使うようになったので**両者は一致して**いますが、
  一致して間違っています。実務上 50 階層の組織は考えにくく、循環参照に対する
  歯止めとしては妥当なので、直すなら「打ち切ったことを呼び出し側に返す」方向
  でしょう。

### 10 階層 4 万人のクライアントについて

書き込み側は無関係です (ACE 1 個につきトークン 1 個、ユーザ展開なし)。
読み取り側は 1 ユーザあたり**初回 247 ms**、以降はキャッシュで 8 ms 前後。
効くのは**グループの階層の深さ**であって、ユーザ数でも所属数でもありません。
