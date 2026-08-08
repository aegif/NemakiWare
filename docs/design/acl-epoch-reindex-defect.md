# folder reindex が ACL-epoch fence を黙って外す

検証: 2026-08-09、`nb33` スタック (3.3.0, bedroom / Solr 4,793 doc)。
以下はすべて稼働中のサーバでの実測です。

**要旨**: `POST /search-engine/reindex/folder/{folderId}` は対象サブツリーの
`effective_acl_epoch` を**消します**。`readers` は残るので検索の認可自体は
壊れませんが、**そのサブツリーは ACL-epoch fence の外に出ます**。
警告も verdict の変化もありません。

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

全再索引 (`POST /search-engine/reindex`) は API 自身が応答で警告します:

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
- `GET /v1/admin/acl-epoch/migration/{repo}` の verdict は
  リポジトリ全体を見るので、50 件が抜けても `COMPLETE` から動かない可能性がある
  (本検証では stamp 直後が `INCOMPLETE` だったため、この点は**未確認**)

### 2-2. 日常操作である

folder reindex は「このフォルダだけ索引がおかしい」ときに管理者が押す、
再起動より軽い日常的な回復操作です。全再索引と違って計画的な作業ではありません。

### 2-3. 何が失われるか

`readers` は残るので**検索の認可は壊れません** (fail-closed も維持)。
失われるのは **epoch fence** — すなわち

- 並行する `applyAcl` の fenced write を、CAS も epoch 比較も持たない batch add が
  **後勝ちで踏み潰せる**
- 古い書き込みを拒否する仕組みが、そのサブツリーだけ無効になる

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
2. 当面の運用回避: **folder reindex の直後に
   `POST /v1/admin/acl-epoch/migration/{repo}` を再実行し、verdict を確認する**。
   これを手順書に書く。
3. `reindex/folder` の応答にも全再索引と同じ警告を入れる。

---

## 5. まだ確かめていないこと

- verdict がこの欠落を検出するか (folder reindex 後に `GET migration` の verdict が
  `COMPLETE` のままか)。本検証では stamp 直後から `INCOMPLETE` だったため未確認
- epoch scanner / reconciliation キューが自動で stamp し直すか。
  観測した範囲 (数分) では復旧しなかった
- RAG 索引側の folder reindex (`/rag/reindex/folder/{folderId}`) が同じ挙動か
- 「後勝ちで踏み潰せる」ことの実演 (並行 applyAcl と folder reindex のレース)
