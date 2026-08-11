# folder reindex が ACL-epoch fence を黙って外す

検証: 2026-08-09、`nb33` スタック (3.3.0, bedroom / Solr 4,793 doc)。
以下はすべて稼働中のサーバでの実測です。

**要旨**: `POST /search-engine/reindex/folder/{folderId}` は対象サブツリーの
`effective_acl_epoch` を**消します**。`readers` は再計算されるので検索の認可自体は
壊れませんが、**そのサブツリーは ACL-epoch fence の外に出ます**。
そして **自動では戻りません** (§5 で確定)。

**現状 (2026-08-12)**:

- verdict は検出します (§2-1)。
- 応答に警告を出すようにしました (`note`)。手順も「必須」に格上げ済み
  (`.claude/skills/acl-epoch/SKILL.md`)。初出時は**警告なし・手順にも未組込**でした。
- **根本修正 (batch 経路を fenced writer に通す) は未了**です。上記は緩和であって
  修正ではありません — reindex を打つたびに stamp を打ち直す運用が要ります。

---

## 1. 実測

対象: `bench-folder-00` (子 50 件)。事前に
`POST /v1/admin/acl-epoch/migration/bedroom` で初期 epoch を stamp 済み。

| | epoch を持つ子 | readers |
|---|---|---|
| folder reindex 前 | **50 / 50** (`epoch=0`) | あり |
| folder reindex 後 | **0 / 50** | あり |

同じ状態で**単一文書**の reindex を実行すると:

| | epoch | readers |
|---|---|---|
| `POST /search-engine/reindex/document/{objectId}` 後 | **0 のまま保持** | あり |

**batch 経路だけが fence を通っていません。**

---

## 2. なぜ問題か

### 2-1. 全再索引とは扱いが違う

> **訂正 (2026-08-09)**: 初出時、下の JSON を「全再索引の応答」と書きましたが誤りです。
> これは **`POST /v1/admin/acl-epoch/migration/{repositoryId}`** (初期 epoch stamp の起動)
> の応答本体です (`AclEpochMigrationController.java:71-76`)。全再索引の応答には
> この警告はありません。**警告が出るのは stamp 側だけ**、という点はむしろ主張を補強します。

epoch stamp の API は応答で次のように警告します:

```json
{"status":"started","note":"Run this AFTER the mandatory full reindex —
 a later reindex leaves the documents unfenced again. Poll GET for progress."}
```

そして運用手順は「全再索引 → 初期 epoch stamp」と文書化されており
([CLAUDE.md](../../CLAUDE.md) / [`acl-epoch/SKILL.md`](../../.claude/skills/acl-epoch/SKILL.md))、
stamp の verdict で完了を確認します。**手順に組み込まれています。**

**folder reindex にはそれがありません。**

- 応答に警告なし
- 運用手順に「folder reindex の後に stamp し直す」という記述なし
- ~~`GET /v1/admin/acl-epoch/migration/{repo}` の verdict は 50 件が抜けても
  `COMPLETE` から動かない可能性がある~~ → **誤り。verdict は検出します。**
  `remainingUnfenced` を前回 run のカウンタではなく**毎回 Solr から生で数え直す**ので
  (`AclEpochMigrationController.java:92-101`)、folder reindex で epoch が消えれば
  verdict は `COMPLETE` に戻りません。**検出はされる — されないのは「警告」と
  「手順への組み込み」です。**

### 2-2. 日常操作である

folder reindex は「このフォルダだけ索引がおかしい」ときに管理者が押す、
再起動より軽い日常的な回復操作です。全再索引と違って計画的な作業ではありません。

### 2-3. 何が失われるか

`readers` は「残る」のではなく、batch 経路が `expandToReaders` から
**毎回再計算して上書き**します (`SolrUtil.java:1384-1396`)。結果として値は
正しいので**検索の認可は壊れません** (fail-closed も維持)。
失われるのは **epoch fence** — すなわち

- 並行する `applyAcl` の fenced write を、CAS も epoch 比較も持たない batch add が
  **後勝ちで踏み潰せる**
- 古い書き込みを拒否する仕組みが、そのサブツリーだけ無効になる。
  ただし**恒久的ではなく「その文書の次の ACL 書き込みまで」**です
  (applyAcl / reconcile の書き込み経路は bootstrap 許容で epoch を再付与する)

CLAUDE.md は「applyAcl / move / reconcile re-drive は**必ず** epoch fence を通ります。
切替スイッチはありません」と書いていますが、**batch reindex はその「必ず」の外**です。

---

## 3. 補足 — 全再索引後の状態

参考として、全再索引直後 (stamp 前) の状態も測りました。

| | 件数 |
|---|---|
| `readers` を持つ | 4,669 / 4,669 |
| `effective_acl_epoch` を持つ | **0** / 4,669 |

これは文書化された順序 (再索引 → stamp) と整合しており、**期待どおり**です。
stamp を実行すると 80 秒ほどで 4,720 件に付きました。

---

## 4. 対策

1. **batch 経路を fenced writer に通す** (`AclEpochIndexWriter`)。最低限、
   add の前に realtime GET → `ContentWriterFence.preserveAclGroup` → `_version_` CAS。
   **落ちているのは ACL group だけではありません** — `content_incarnation` /
   `content_generation` を stamp するのは `applyContentFence` (`SolrUtil.java:942-945`)
   だけで、batch が通る `createSolrDocument` の 2 引数版はこれも通りません。
2. 当面の運用回避: **folder reindex の直後に
   `POST /v1/admin/acl-epoch/migration/{repo}` を再実行し、verdict を確認する**。
   これを手順書に書く。
3. `reindex/folder` の応答にも全再索引と同じ警告を入れる。

---

## 5. まだ確かめていないこと

- ~~epoch scanner / reconciliation キューが自動で stamp し直すか。
  観測した範囲 (数分) では復旧しなかった~~ → **確定: 自動では戻らない (2026-08-12)**

  「数分では戻らなかった」は「まだ戻っていないだけ」とも読めたので、
  運用手順を「必須」と書くか「推奨」と書くかが決まらなかった。**コードから答えが出る**:

  `AclEpochScanScheduler` は 300 秒ごとに `AclEpochFinalizationService.scan` を呼ぶが、
  その**全パスの選択条件は CouchDB 側の** `aclEpochState`
  (PENDING_EPOCH / FINALIZED_NEEDS_RECONCILE) か `aclEpochMutationId` の存在である
  (`AclEpochFinalizationService.java:321-360`)。folder reindex が消すのは
  **Solr の `effective_acl_epoch` フィールド**であって、CouchDB 文書は一切触られない
  — 状態は settled のまま。**したがってどのパスもその文書を選ばない。**

  実測で裏取り (`tools/acl-probe/epoch_folder_reindex_recovery.py`):
  子 236 件のうち 186 件が fenced のフォルダを reindex →
  **5 秒で 0/236**、以後 **961 秒 (スキャナ周期 300 秒 × 3 回超) 経っても 0/236**。
  観測期間中のピークも 0。遅いのではなく**構造的に戻らない**。

  **したがって §4-2 の運用回避は「推奨」ではなく「必須」。** 応答にも警告を入れた
  (`SearchEngineResource.reindexFolder` が `note` を返す。
  `FolderReindexEpochWarningTest` で固定)。
- ~~RAG 索引側の folder reindex が同じ挙動か~~ → **別経路**。
  `RAGIndexMaintenanceServiceImpl` → `ragIndexingService.indexDocument` で
  書き込む Solr コアも文書も異なるため、この欠陥の対象外 (要再確認)
- 「後勝ちで踏み潰せる」ことの実演 (並行 applyAcl と folder reindex のレース)
