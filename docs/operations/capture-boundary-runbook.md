# 外部取込の capture 境界 — 運用 runbook

対象: 3.4 で入った capture outbox。設計は
[`docs/design/capture-outbox.md`](../design/capture-outbox.md)。

---

## 0. これは何を保証して、何を保証しないか

**保証すること**: 取込がリポジトリを変更する前に、`nemaki_lineage` に「これから取り込む」
という行が必ず在る。どこで落ちても行は残る。

**保証しないこと**:

- **`UNRESOLVED` の行は「文書が作られた」とも「作られなかった」とも言っていない。**
  在る文書がその試行由来かを判定するには、まだ書いていない刻印が要る (P1-1(e))。
  掃引は文書を探しに行かないし、一覧もそれを表示しない
- **lineage イベントが外部 (Purview / Atlas) へ配送されることは保証しない。**
  発行は従来どおりで、既存の outbox の担当
- **取込の入口が返った後に orchestrator が作る relationship は境界の外**にある

---

## 1. 有効になる条件

**リポジトリごとの実効 `lineage.mode` が `journaled` のときだけ効きます。**
`disabled` (既定) と `direct` は従来どおりで、挙動も費用も変わりません。

```bash
# 実効モードの確認 (リポジトリ単位の override が優先)
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/lineage-journal/stats"
```

有効化の判定は **intent を開く瞬間に 1 度だけ**行われ、その試行の中では固定されます。
取込の途中でリポジトリを `disabled` に切り替えても、**開いている intent は完成します**。

---

## 2. 未解決の一覧を見る

```bash
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/capture-intents/unresolved?limit=50"
```

| 応答 | 意味 |
|---|---|
| `200` + `entries: []` | **未解決は無い。** 掃引が動いていて、期限切れの取込が無い |
| `503` | **境界が配線されていない。** 「無い」ではなく「答えられない」 |
| `500` | **view が読めなかった。** 索引の再構築中か DB 障害。**空の一覧として返さない**のは、それが「異常なし」と見分けられないため |

各行には `repositoryId` / `sourceSystem` / `sourceObjectId` / `sourceObjectType` /
`requestId` / `connectorId` / `intentOpenedAtMs` / `executedBy` が入っています。
**何を取り込もうとしていたかが行だけで分かる**ようにしてあります。

### 行が出たときにすること

1. `sourceObjectId` と `sourceSystem` で、外部側にその項目がまだ在るか確かめる
2. リポジトリ側に対応する文書が在るか確かめる (**自動判定はしません** — §0)
3. 無ければ再取込。在れば重複しないか確認したうえで、行は証拠として残す

---

## 3. 掃引

**既定で動きます** (5 分ごと)。既存の purge scheduler を真似ると cron の既定が空で動かず、
`UNRESOLVED` が現れないので一覧が常に空になります。それは「問題が無い」と区別できません。

| 設定 | 既定 | 意味 |
|---|---|---|
| `lineage.capture-boundary.stale.minutes` | 15 | これを超えて開いたままなら `UNRESOLVED` |
| `lineage.capture-boundary.sweep.interval.minutes` | 5 | 掃引の間隔 |
| `lineage.capture-boundary.sweep.batch` | 200 | 1 回で触る行数の上限 |

**leader election には依存していません。** 既定で無効で、無効時は全レプリカが leader に
なるためです。安全は `_rev` CAS で取っています (各遷移は冪等)。

**ただし正しさと費用は別です。** 既定構成では N レプリカが同じ batch を 5 分ごとに舐め、
1 行あたり N 回の読みと N−1 回の弾かれた書込が出ます。batch に上限があり、初回実行は
JVM ごとにずらしていますが、**消えてはいません**。レプリカが多い環境では
`sweep.interval.minutes` を延ばすか、leader election を有効にしてください。

---

## 4. 保持期間 — 既定は無期限

| 設定 | 既定 | 起点 |
|---|---|---|
| `lineage.capture-boundary.retention.captured.days` | `0` = 無期限 | `capturedAtMs` |
| `lineage.capture-boundary.retention.unresolved.days` | `0` = 無期限 | `unresolvedAtMs` |

**既定で削除しないのは判断であって手抜きではありません。** これらの行は「いつ何を取り込んだか」
「何が失敗したか」の証拠で、運用者が明示的に求めない限り製品側で消すべきものではありません。

**代価**: `nemaki_lineage` が取込量に比例して単調増加します。**取込 1 件につき 1 行**が
永久に残るので、規模の大きい環境では DB サイズを監視対象にしてください。

```bash
curl -s -u admin:password http://localhost:5984/nemaki_lineage | \
  python3 -c "import sys,json;d=json.load(sys.stdin);print(d['doc_count'],d['sizes']['file'])"
```

**有限を選ぶときの注意**: lineage イベント本体は別途 `lineage.retention.days` (既定 90) で
消えます。期限を揃えないと片方だけ残ります。

- **開いたままの `CAPTURE_INTENT` は、どの retention でも消えません。** 必ず先に掃引が
  `UNRESOLVED` にします (retention の view の述語が `UNRESOLVED` のみ)。
  直接消せると「取込中に落ちた」証拠そのものが消えるためです

---

## 5. アップグレード時

- **既存の取込には intent 行がありません。** これは正常で、何も照合しません。
  境界はこの版以降の取込にだけ効きます
- **view は初回アクセス時に自動配備されます** (`_design/lineage_capture`)。既存の
  `_design/lineage` には足していないので、**既存の view group は再構築されません**
- **`nemaki_lineage` が無い環境では何も起きません。** 掃引は `isActive()` で止まります

---

## 6. 症状から引く

| 症状 | 見るところ |
|---|---|
| 取込が「証拠を書けなかった」と失敗する | `nemaki_lineage` への書込。これは fail-closed の設計どおりで、**変更を残さないための拒否**です |
| 一覧が常に空 | 掃引が動いているか (ログ `Capture intent sweeper started`)。`lineage.mode` が `journaled` か |
| 一覧が 503 | 境界の bean が配線されていない。`nemaki_lineage` の有無を確認 |
| 一覧が 500 | view の索引再構築中か DB 障害。**空と区別するために意図的に 500 にしています** |
| 取込は成功するが警告に「証拠の行を読めなかった」と出る | 文書は作られています。行が retention で消えたか、一時的な障害か、① の書込が成立していなかったか — **この 3 つは区別できません** |
