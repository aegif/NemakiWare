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

**実効モードを直接返すエンドポイントはありません。**
`/api/v1/admin/lineage-journal/stats` は**グローバルの** `lineage.mode` と
「override が在るか」の真偽しか返さないので、`lineage.mode=disabled` +
`lineage.mode.override.bedroom=journaled` の環境では `"mode": "disabled"` と読めて
しまいます。設定値そのものを見てください。

```bash
# グローバル
curl -s -u admin:password "http://localhost:5984/nemaki_conf/_find" \
  -H 'Content-Type: application/json' \
  -d '{"selector":{"type":"configuration","key":{"$regex":"^lineage.mode"}}}'
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
| `200` + `entries: []` | **未解決は無い。** ただし後述の「初回起動直後」を除く |
| `503` | **境界が配線されていない。** 「無い」ではなく「答えられない」 |
| `500` | **view が読めなかった。** 索引の再構築中か DB 障害。**空の一覧として返さない**のは、それが「異常なし」と見分けられないため |

> **初回起動直後だけは例外です。** `_design/lineage_capture` の索引を CouchDB が構築して
> いる間、view は失敗せず**不完全な索引から答えます**。これは CouchDB の仕様で、製品側から
> 「構築中」と「空」を区別する手段はありません。**アップグレード直後の 1 回目の照会は、
> 空でも結論にしないでください。**索引が未配備の場合は 500 になります (これは区別できます)。

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
`lineage.capture-boundary.sweep.interval.minutes` を延ばしてください。

> **`lineage.leader-election.enabled` はこの掃引には効きません。** 既存の
> `LineagePurgeScheduler` は leader gate を見ますが、この掃引は**意図的に見ていません**
> (既定で無効、かつ無効時は全レプリカが leader になるため「leader が守っている」が
> 偽になる)。有効にしても掃引の本数は減りません。

**また、掃引の間隔だけは起動時に一度読まれます。** 変更には再起動が要ります。
staleness・batch・保持期間の 3 つは毎回読み直すので、再起動なしで効きます。

**掃引の速度には上限があります。** 1 回あたり `sweep.batch` 行 (既定 200) で、
レプリカを増やしても**並列にはなりません** (全レプリカが同じ最古の一群を舐めるため)。
大量取込中に落ちて数万件の intent が開いたままになると、一覧に出そろうまで
`(件数 ÷ batch) × interval` かかります (5 万件・既定値なら約 21 時間)。
急ぐときは `sweep.batch` を上げ、`sweep.interval.minutes` を下げてください
(batch は 2000 で頭打ちです)。

---

## 4. 保持期間 — 既定は無期限

| 設定 | 既定 | 起点 |
|---|---|---|
| `lineage.capture-boundary.retention.captured.days` | `0` = 無期限 | `capturedAtMs` |
| `lineage.capture-boundary.retention.unresolved.days` | `0` = 無期限 | `unresolvedAtMs` |

**既定で削除しないのは判断であって手抜きではありません。** これらの行は「いつ何を取り込んだか」
「何が失敗したか」の証拠で、運用者が明示的に求めない限り製品側で消すべきものではありません。

**代価**: `nemaki_lineage` が取込量に比例して単調増加します。

**「取込 1 件につき 1 行」ではありません。** 子操作 (mail の添付・生 `.eml`・note の添付) は
規則 4 によりそれぞれ自分の行を持ちます。添付 5 件のメールは**生 `.eml` を含めて 7 行**です。
1 行はおよそ 600〜1,000 バイト (完成時に 2 リビジョン) なので、150 万行で概ね 1〜1.5 GB
(compaction 前) です。

サイズ以上に効くのは**将来の view 再構築**です。`_design/lineage` は 30 個の view を持ち、
署名が変わると全 document に対して map を流し直します — 何も emit しない capture 行も
含めてです。`nemaki_lineage` の doc_count が倍になれば、その所要時間もおおむね倍になります。

**何が増えているかは専用の口で見てください。** `nemaki_lineage` の `doc_count` は lineage
イベント・dead letter・v2 行を混ぜて数えるので、**capture 行がどれだけ在るかは答えられません**。

```bash
curl -u admin:admin -H "X-Requested-With: XMLHttpRequest" \
  "http://localhost:8080/core/api/v1/admin/capture-intents/counts"
```

`captured` が無期限に増える対象です。`captureIntent` が**下がらない**場合は掃引が止まって
います。`truncated: true` は走査上限に達したという意味で、**数字は下限であって合計では
ありません** (`?scanLimit=` で広げられます。上限 10 万)。

DB 全体のサイズは併せて:

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
- **`nemaki_lineage` が無い環境では何も起きません。** 掃引は `isActive()` で止まり、
  未解決一覧は 500 を返します (**読み取りが DB を作ってしまわない**ようにしてあります)

---

## 6. 症状から引く

| 症状 | 見るところ |
|---|---|
| 取込が「証拠を書けなかった」と失敗する | `nemaki_lineage` への書込。これは fail-closed の設計どおりで、**変更を残さないための拒否**です |
| 一覧が常に空 | 掃引が動いているか (ログ `Capture intent sweeper started`)。`lineage.mode` が `journaled` か |
| 一覧が 503 | 境界の bean が配線されていない。`nemaki_lineage` の有無を確認 |
| 一覧が 500 | view の索引再構築中か DB 障害。**空と区別するために意図的に 500 にしています** |
| 取込は成功するが警告に「証拠の行を読めなかった」と出る | 文書は作られています。行が retention で消えたか、一時的な障害か、① の書込が成立していなかったか — **この 3 つは区別できません** |
