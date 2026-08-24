# 長期真正性基盤ロードマップ — InterPARES 要求事項への対応と 3.4 以降の計画

2026-08-17 起案。オーナー方針:

> Atlas (Purview) 連携を前提として、InterPARES の要求事項に応えて、コネクタ経由で
> Slack などのビジネスチャットや各種クラウドストレージから取り込まれた共有文書が
> **中長期的に真正性を判断できる状態**をつくるための基礎として NemakiWare を位置づける。

本書は 2 部構成。**§2 = 破壊的なので 3.3 から外したが、やった方がよいこと** (この基盤の
前提工事)、**§3 以降 = InterPARES の要求に対応付けた (mapped to) ケイパビリティの作り込みと
マーケティング**。**「InterPARES 準拠」とは書かない** — 準拠を認定する制度を**確認できておらず**、原文の枠組み上も
製品単体で満たすとは言えないため (§5 の禁じ手 / §9-1)。
3.3.1 (非破壊パッチ) は別文書 [`v3.3.1-plan.md`](v3.3.1-plan.md)。

**本書の現状記述は 2026-08-17 に v3.3.0 のコードで確認したもの** (file を併記)。
**ただし 2026-08-20 以降、3.4 で入った未リリースの実装を「現状」として書いている箇所が
混在する** — 公開マッピング ([`interpares-mapping.md`](interpares-mapping.md)) 側では
未リリース分に ⚑ を付けて区別している。
計画部分は計画であって約束ではない。着手時に現状を再確認すること。

## §0 前提: 標準規格スタック (2026-08-17 オーナー決定 — E-ARK を含め標準に沿って進める)

独自形式を作らない。各層で既存の標準を採用し、**機械検証できる層では検証を CI に固定する**。

| 層 | 採用する標準 | **検証できる範囲** (「準拠を名乗れるか」ではない) |
|---|---|---|
| 要求事項 | InterPARES benchmark/baseline (A.1〜A.8 / B.1〜B.3) | ✗ 認証制度は**確認できず** → 対応表 + 根拠の公開まで (§5 禁じ手) |
| 機能モデル・語彙 | OAIS (ISO 14721 — **現行は 2025 年版**。参照時は版を固定)、現用層は ISO 15489 | ✗ 参照モデル → 語彙として採用 (§3.1) |
| メタデータ | **METS** (構造) / **PREMIS** (保存イベント) / Dublin Core (記述) | △ スキーマ妥当性は機械検証可 |
| パッケージング | **E-ARK CSIP / SIP** (DILCIS Board 維持。**版を固定する: CSIP 2.2.0 / METS 1.12 / バリデータとルールセットの版込み** (CSIP が規定するのは「PREMIS in METS Guidelines 2017 年版」であって PREMIS の版番号ではない — §9-3) — 版なしの「validator pass」は再現不能)。BagIt (RFC 8493) は Archivematica 接続層の transfer 形式としてのみ使用 — **現行 RFC 8493 は serialization を規定しない**ので「bag の中に IP を封入して搬送」という語り方はしない (外部レビュー指摘) | **△ 生成した個々のパッケージについて「版を固定したバリデータを通った」という事実だけを示す予定**。**層や製品の準拠は名乗らない。現状は未実装 (Phase 3)** |
| 完全性・時刻 (**準拠は名乗らない**。SHA-256 は実装済み、RFC 3161 と OTS は**部品は実装済みだが本番結線は未了 (3.4)**、ERS は計画。**「部品が在る」と「機能として使える」を区別する**) | SHA-256 / RFC 3161 (**TSA プロトコルであって「認定タイムスタンプ」を自動的には意味しない**) / ERS (RFC 4998 = ASN.1/CMS、RFC 6283 = XMLERS — **積層ではなく表現形式の選択肢**) / OpenTimestamps (**デファクトであり正式規格ではない**と常に付記) | **成果物単位で暗号学的に検証できる** (「この token / この `.ots` が、定義した検証項目を通った」)。**層としての準拠は名乗らない** |
| 組織認証 | CoreTrustSeal / ISO 16363 | ✗ 対象は運用組織 → 顧客の取得支援 (§3.1) |

この表の含意: **機械検証を根拠に「通った」と言える見込みがあるのはパッケージング層 (E-ARK) と
暗号層だけ**。ただし言えるのは**成果物単位の事実**であって層や製品の準拠ではない (下記の制限)。他は「対応表を公開する」という形を取る。この非対称を崩さない。

> **言い方の制限 (2026-08-20 外部レビュー)**: この 2 層についても**製品としての準拠は
> 名乗らない**。言えるのは**成果物単位の事実**だけ — 「**この**パッケージは版を固定した
> バリデータを通った」「**この** RFC 3161 トークンは定義した検証項目を通った」。
> しかも E-ARK は**まだ実装していない (Phase 3)**。暗号層も実装済み・未結線・計画のみ・
> 標準外が混在しているので、一括りに「準拠」と言わない。

---

## §1 現状資産の棚卸し — 思っているより既に持っている

真正性 (authenticity) を InterPARES の枠組みで言えば **identity (その記録が何であるか
を示す属性) + integrity (完全で改変されていないこと)** であり、それを**保管の連鎖
(chain of custody) の証拠**で支える。この観点で v3.3.0 が既に持つもの:

| 資産 | 実装 | 真正性文脈での意味 |
|---|---|---|
| **チャット・クラウド取込コネクタ** | `rest/ingest/` — `chat/SlackConnectorAdapter` / `TeamsConnectorAdapter` / `MattermostConnectorAdapter`、`note/NotionConnectorAdapter`、`record/SalesforceConnectorAdapter`、IMAP、クラウドドライブ、webhook (HMAC) | 対象文書の入口。ユーザーの構想 (Slack 等からの共有文書) は既に入口がある |
| **取込時コンテンツハッシュ** | `CanonicalImportServiceImpl` が **バイトを取得できた取込に限り** SHA-256 を計算し、`nemaki:externalIntegration` aspect の **`nemaki:contentHash`** に保存 (:745,:1505)。再取込時に既存ハッシュと比較 (:1273)。**ただし証拠としては未完**: aspect 付与はコンテンツ作成後の別更新で失敗は warning 止まり (:1315)、型レベルの property definition が無い生プロパティ (**2026-08-22 追記**: 宣言が無いことが結果的に CMIS からの書き換え・消去を塞いでいる — `injectPropertyValue` は宣言済みしか回らず、`mergeAspectProperties` は未宣言キーを引き継ぐ。**意図した保護ではないので、そう名乗らない**)、空コンテンツも hash あり (**2026-08-20 に修正**: 空入力の SHA-256 は有効な digest)、対象は content bytes のみ (メタデータ・添付・会話範囲を含まない) | integrity の起点は在る。**「原子的な証拠取得」にするのが P1-1** |
| **来歴属性** | 同 aspect の `nemaki:sourceArchetype` / `sourceSystem` / `sourceObjectType` / `sourceObjectId` / `sourceUrl` (:1478-1486) | identity の一部 (出所・恒久リンク) |
| **チャット文脈メタデータ** | `Patch_ChatContextMetadataSecondaryType` — `nemaki:chatWorkspaceId` / `chatChannelId(Name)` / `chatThreadId` / `chatMessageId` / **`chatParticipants`** / `chatSelectionReason` / `chatEvidenceScope` / `chatCapturedAt` / `chatCaptureWindowStart/End` | identity 属性として出色の**型**。ただし `chatCapturedAt` は **2026-08-19 に取込時スタンプを実装済み** (P1-1(c) の設定部)。**更新制約も 2026-08-22 に実装済み** — 11 個とも READONLY で、消去も型ごとの取り外しも塞いだ。**A.1 全体は名乗らない** (2026-08-24 の逐条判定 — [`interpares-mapping.md`](interpares-mapping.md) A.1: office 系 b.i/b.ii が未充足、対象は取込経路に限る)。名乗れる**限定文**と残余は [`p1-1-remaining-plan.md`](p1-1-remaining-plan.md) §6。~~複製の偽証拠~~・~~実行起源の食い違い~~・~~capturedAt の第 2 の写し (D5)~~・~~0 バイト添付の無名 (D-2)~~・~~参照共有 (D-3)~~ は **2026-08-23/24 に解消済み** — 残る運用上の限界はローリング再起動のみ |
| **Lineage journal + 外部カタログ** | `rest/purview/journal/` 一式 — CouchDB 永続イベント (V2)、Atlas sink、カタログ publish/republish/reconciliation、dead letter、historical compensation | chain of custody の記録装置と、**NemakiWare の外にある照合先** (Atlas/Purview)。**「独立検証点」とは書かない** — 同一組織・同一 tenant の管理下なら同じ管理者が両方を直せるので独立ではない (§4) |
| **環境同一性の証明** | `LineageWriteVersionBarrier` / `LineageBarrierService` / `LineageBinaryDigest` — 配布物 (WAR) のバイナリダイジェスト + ノード membership ダイジェスト、golden vector で式を凍結 | **配布物とノード構成の同一性ゲート**であって、**「どのソフトウェアがその記録を処理したか」の記録単位の証拠ではない** — イベントの envelope に記録とバイナリを結ぶダイジェストが無い (2026-08-20 訂正)。記録単位に落とすのは P1-1(d) |
| **保持・処分・長期保管** | retention (ACTIVE → ARCHIVED_LOCAL → ARCHIVED_COLD)、S3 Legal Hold、cold storage、削除アーカイブ | ライフサイクル管理と法的保全 |
| **アクセス制御と監査** | CMIS ACL + ACL-epoch fencing、audit (READ レベル選択式。WRITE/DELETE/ACL は READ レベルに関わらず**対象**だが、**穴が 4 つある** — `AuditLogger` に到達しない書込経路・除外設定・ロガーのレベル設定・出力失敗。[`interpares-mapping.md`](interpares-mapping.md) A.2) | 保護手続きとアクセス記録 |
| **バージョニング** | CMIS versioning (checkin/checkout。**CMIS TCK の versioning テストを通過**) | 改変履歴 |
| **変換基盤** | jodconverter (LibreOffice)、Tika | 保存フォーマット変換 (PDF/A 化) の土台 |

**確認済みの不在 (= 作るもの)**: RFC 3161 タイムスタンプ (`rfc3161|tsa` で 0 件)、
BagIt / OAIS 型パッケージ (`bagit|oais` で 0 件)、定期 fixity 再検証ジョブ、journal の
改竄検知 (エントリ連鎖)、真正性レポート、InterPARES へのマッピング (`interpares` で 0 件)。

**重要な現状 3 点**:
1. `lineage.mode` の既定は **`disabled`** (`IntegrationSettingsController:129`)。既定変更は破壊的なので §2 へ。
2. **journal は既定 90 日で purge される** (`LineageConfig:120` の `lineage.retention.days:90`、
   `LineagePurgeScheduler` / `LineageJournalStore.purgeOlderThan`)。「journal は追記専用で残る」
   という素朴な前提は現行実装と**正面衝突**する — P1-3 の分離設計 (配送 journal / evidence
   ledger) で解く。
3. 取込 lineage snapshot は **PR #507 以前**、`sourceSystem/Archetype/ObjectId` 等のみで
   contentHash・chat*・取込主体を含まず、emit の失敗も warn して null を返す非致命経路だった。
   P1-1 の「journal 化の徹底」の実体はここ。**2026-08-19〜20 に解消**: 取り逃しは呼び出し元に
   届くようになり (PR #506)、snapshot は `contentStored` (`"true"` / `"false"` / `"unknown"`) /
   `contentHash` / 会話文脈 (`chat.workspaceId` / `channelId` / `channelName` / `threadId` /
   `messageId` / `participants` / `selectionReason` / `evidenceScope` / `captureWindowStart` /
   `captureWindowEnd`) / `executedBy`+`onBehalfOf` を運ぶ (PR #507)。**残る制約**は 2 つ:
   snapshot は **v1 projection 限定**で `LineageFact` の設計上 v2 に home が無いこと
   (**下表 P1-1(b)**、設計は [`p1-1b-v2-evidence-home.md`](p1-1b-v2-evidence-home.md))、および
   `nemaki:chatCapturedAt` は**オブジェクトに刻まれるが snapshot には入らない** — 刻印が emit の
   **後**に走るため、載せるには順序を変える必要がある。**後者は (b) から (d) へ移した**:
   刻印先の `nemaki:chatContextMetadata` aspect を作るのは `execute()` が**返った後**の
   wrapper なので、emit の直前に前倒しすると **aspect がまだ無く空振りする**。aspect 付与の
   位置は「どの事実がどの時点で確定するか」の帰結であり、それを決めるのは (d) である。刻印は
   **この取込が作ったオブジェクトに限る**: dedupe skip を含む毎回の chat 取込で走るため、
   既に在るオブジェクトに「今」を刻むと、何年も前から保管しているものが今日から保管開始に
   見えてしまう。かといって `cmis:creationDate` も答えではない (移行・アーカイブ復元で
   保存され、後の版は自分の作成時刻を持つ)。**分からないものは記録しない**。既存オブジェクト
   の保管開始を復元するには来歴イベントを読む必要がある (P1-1(d))。既に値がある場合も
   上書きしない。

---

## §2 破壊的なので 3.3 から外したが、やった方がよいこと (3.4 の前提工事)

3.3 のリリース判断で「正しいがリリース直前に入れる変更ではない」と据え置いたもの。
**いずれも真正性基盤の前提**になる — 「静かに欠ける」経路を持つシステムの上に
真正性の主張は築けない。

### 2-1. DAO のエラー握り潰しの総点検 (最優先・他の前提) — **第 1 段 実施 2026-08-24**

> **名指しされた 3 箇所は fail-fast にした。** 判断の基準は「**無い**」と「**読めなかった**」を
> 同じ答えにしないこと。leniency は `delete()` が既に使っていた規則をそのまま踏襲し、
> **startup phase だけ**従来どおり null を返す (パッチと provisioning は準備前の DB に
> 対して走るので、そこを硬い失敗にすると今まで上がっていた配備が上がらなくなる)。
>
> | 箇所 | 前 | 後 |
> |---|---|---|
> | `CloudantClientWrapper.update(Map)` | 全例外を握って null。**24 の呼び出し元のうち null を見ているものは 0 件** | startup 外は throw |
> | `CloudantClientWrapper.get(String)` | `NotFoundException` も他の例外も null。ログは常に「This is normal during initial startup」 | NotFound は null (正当な不在)、他は startup 外で throw |
> | `ContentDaoServiceImpl.getChildren` (2 経路) | 例外で**空リスト** = 「このフォルダに子は無い」という**事実の主張** | startup 外は throw |
>
> `getChildren` の空リストは v3.3 の「空索引が completed と報告された」事件の根でもある
> (再索引側にはあの時ガードを足した。これが根)。
>
> **第 2 段 (同日、外部レビュー)**: 例外経路を閉じても **null 経路が開いたまま**だった。
>
> | 箇所 | 前 | 後 |
> |---|---|---|
> | `queryView` の `NotFoundException` | startup 外でも null。呼び出し側は空フォルダと読む | startup 外は throw |
> | `getChildren` の `result == null` | 空リスト | throw (「答えなかった」と「空」は別) |
> | `getChildrenCount` | 例外で **0** = 「子は居ない」という事実の主張 | throw |
>
> **初稿の統制テストが穴の形を pin していた** — `queryView → null` を「空フォルダ」と
> して固定していた。空フォルダは**行 0 の ViewResult** であって null ではない。
> 判別テスト 6 本、統制で 4 件とも落ちることを実測。
> **残り**: `null` を Optional / 明示 NotFound に**型で**表す作業と、他の DAO の全数点検。

| 現状 (v3.3.0) | 何が問題か |
|---|---|
| `CloudantClientWrapper.update(Map)` は**あらゆる例外を握り潰して null を返す** | 書けたか書けていないか呼び出し側に分からない。V3 差し戻しの根本原因 |
| `CloudantClientWrapper.get(String)` は **404 以外のエラーでも null** | 「無い」と「読めなかった」の区別が消える |
| `ContentDaoServiceImpl.getChildren` は例外で**空リストを返す** (:1217) | 「子が居ない」と「列挙に失敗した」の区別が消える。空索引 completed 事件の根 (3.3 では再索引側にガードを足して防御した) |

- **やること**: fail-fast 化。エラーは投げる。「無い」は型で表現する (Optional / 明示の
  NotFound)。呼び出し側を全数レビューして「null を正常扱いしていた箇所」を洗う。
- **なぜ破壊的**: 今まで静かに欠けていた操作が**落ちるようになる**。挙動としては正しく
  なるが、失敗が新たに可視化される。
- **真正性文脈**: 記録の完全性を主張するには「書けなかったら分かる」が絶対条件。
  fixity・journal を積む前にここを塞ぐ。
- **これが済むと解ける据え置き**: V2 (作成直後の検証読み削減)・V5 (リトライ一本化) は
  「create/update の失敗が失敗として現れる」ことが前提だった。

### 2-2. 残り 16 パッチの unprepared-return → throw 化 — **機構を入れた 2026-08-24**

> **throw 化ではなく「第 3 の答え」にした。** 台帳は「正しい直し方は 16 本ではなく
> `apply()` 側 1 箇所だと思われる」と書いていた。実際そのとおりで、ただし
> **throw と正常復帰の間にもう 1 つ答えが要る**:
>
> `AbstractNemakiPatch.reportIncomplete(reason)` を呼ぶと、`apply()` は
> **履歴行を書かない**ので次回起動で再試行される。起動は止めない
> (これが「全部 throw」を破壊的にしていた理由 — 一部の catch は本当に許容可能な失敗を
> 握っており、全部を起動エラーにすると今まで上がっていた配備が上がらなくなる)。
> 呼ばないパッチの挙動は完全に従来どおり。
>
> 接続済み: 3 つの証拠型パッチ (note / message / businessRecord)。これで
> `Patch_ArchetypeMetadataEvidenceReadOnly` の「creator が握り潰すと恒久 wedge」が
> **収束するようになった** (同 javadoc の但し書きは撤回済み)。
> 判別テスト `PatchIncompleteWorkIsRetriedTest` (履歴を書かない / 正常時は書く統制 /
> リポジトリ間で漏れない / throw は従来どおり)。
> **残り**: 他の握り潰しパッチの接続 (機構は在るので 1 本ずつ判断できる)。

### 2-2 (原文). 残り 16 パッチの unprepared-return → throw 化 (件数は台帳 `v3.3-release-plan.md` の「残る 16 本」に一致させた)

`Patch_SystemFolderSetup` だけ 3.3 で実施済み (オーナー決定)。残りのパッチも
「準備できていないのに正常終了して履歴を焼く」形を持つ。2-1 と同じ思想。
破壊的: 初期化失敗が起動時エラーとして現れるようになる。

### 2-3. lineage.mode の既定を `journaled` へ — **ウィザード側 実施 2026-08-24**

> **2 つに分けた。** ウィザードの選択 (実測に依らない) と、**出荷既定の変更** (実測が要る)。
>
> - **実施**: Setup Wizard に「来歴記録」ステップを追加し、**推奨 ON** を既定選択にした。
>   ストレージ増と「来歴は後から遡って作れない」ことを**選ぶ前に**表示する。
>   API は `lineageJournaled`。**未指定は保存値を触らない** — null を既定で上書きすると、
>   有効にしていた配備を黙って無効にすることになり、それは復旧できない。
>   判別テスト: 未指定なら `lineage.mode` を書かない / 明示なら書く (統制)
> - **未実施**: `@Value("${lineage.mode:disabled}")` の出荷既定。ロードマップ自身が
>   「先に要る実測」として journaled の書込オーバーヘッドと journal 成長率
>   (bedroom 規模 + 10 万規模) を条件にしており、その実測はまだ無い

### 2-3 (原文). lineage.mode の既定を `journaled` へ

- **やること**: Setup Wizard に来歴記録の選択を追加し、**既定 on (journaled)** にする。
  既存環境は現状維持 (conf 保存値が勝つ)。ストレージ増 (journal + 保持期間) を
  ウィザードで明示。
- **なぜ破壊的**: 既定の書き込み量・ストレージ特性が変わる。
- **真正性文脈**: 来歴はあとから遡って作れない。「**既定で証拠を残そうとする**」が製品の
  前提になる。**「残る」と言い切れるのは outbox (P1-1(a)) の後** — 現状は対象経路に限られ、
  発行は fail-open で、journal は purge 対象である。
- **先に要る実測**: journaled モードの書き込みオーバーヘッドと journal 成長率
  (bedroom 規模 + 10 万規模)。

### 2-4. 往復シリーズの残り (B1-a / T2 / V2 / V5) と RX1

| ID | 内容 | 破壊的要素 | 判断 |
|---|---|---|---|
| B1-a | `replacePwc` プレビューの書き込み前バッファ | アップロード経路のメモリ特性が変わる (ストリーミング → バッファ) | プレビュー生成の**非同期化** (書いた後にジョブで生成) を対案として設計比較してから |
| T2 | 索引時 `ContentIncarnation.resolve` の GET 省略 | fence の正しさの入力。設計判断 | RX1 と同時に扱う (索引フェーズの約 24%) |
| V2 / V5 | 作成直後の検証読み・多重リトライの削減 | エラー意味論に依存 | **2-1 の後** |
| RX1 | 再索引スループット劣化 (10.3 → 2.6 docs/s、索引フェーズの約 88% が CouchDB 読み) | 直し方次第 (bulk read 化は読み方の再設計) | 2-1 と独立に調査可。`_all_docs?include_docs` バッチ読みの試作から |

### 2-5. その他の据え置き破壊的変更

- ~~**MD5 レガシー認証経路の廃止予告**~~ — **実施 2026-08-24**。MD5 照合時に
  **WARN** (従来は debug) を出し、3.4 で廃止予告・3.5 で削除と明言。
  棚卸しは `GET /api/v1/admin/security/legacy-password-hashes` (admin のみ)。
  **hash は返さない** — 弱い資格情報の一覧は攻撃者にとって名前の一覧より価値があり、
  運用者が動くのに必要でもない。**危険な母集団は「昇格機構の出荷以降サインインして
  いないアカウント」**で (照合成功時に BCrypt へ昇格するため)、それは障害を待っても
  見つからない — 障害は削除の後に一斉に来る。検出規則は照合側と同一
  (`[a-f0-9]{32}`、大文字 hex は対象外) であることをテストで固定。削除は 3.5
- ~~**`-Dnemakiware.properties` 幻フラグの削除**~~ — **実施 2026-08-24**。
  compose 4 本と Dockerfile 2 本から除去 (どこからも読まれていないことを確認済み)。
  リリースノート必須は変わらない
- **`rag.enabled` の焼き込み既定**: wizard が常に永続化するため実害は限定的。
  2-3 と同じタイミングで既定 off に揃える。

---

## §3 InterPARES 要求事項マッピング (作り込みの背骨)

InterPARES (International Research on Permanent Authentic Records in Electronic
Systems) の Authenticity Task Force が定義した **benchmark requirements (A.1〜A.8)** と
**baseline requirements (B.1〜B.3)** を、機能→根拠→検証手順の管理表として採用する。

**両者の違いは主体ではなく充足様式である** (§9-1 で原典を確認。以前ここに書いていた
「作成者側 / 保存者側」という説明は不正確だった):

- **Benchmark (A) は累積的** — 満たした数と充足度が高いほど真正性の**推定が強まる**。
  部分適合に意味がある。ただし**評価者は常に preserver** で、実施主体 (creator) と
  評価主体は分離している。
- **Baseline (B) は全件必須** — 全部満たさなければ preserver はコピーの真正性を attest
  **できない**。**「B を 80% 満たす」は原文の枠組み上そもそも成立しない主張**なので、
  適合度の書き方を A 系と B 系で分ける。
- 責務は固定ではない: 本書は **A.5 を Creator 専属**として扱う (**原典が「A.5 だけ」と
  定めているわけではない** — A.7/A.8 にも creator 側の義務が読める)。**A.2/A.3/A.4/A.6 は Preserver にも
  掛かる**。**A.6/A.7/A.8 は条件付き要求**である。

**マッピング表は独立文書に出した**: [`interpares-mapping.md`](interpares-mapping.md)。
公開を前提に、各行を「主張 / 検証手順 / 責務 (Creator・NemakiWare・導入組織) / 条件付きか /
ギャップ / 計画」に分けて書いてある。**P0-1 の残作業だった書き直しはこれで完了**
(2026-08-20)。

要点だけここに残す — **詳細と検証手順は上記文書を正とする**:

| | |
|---|---|
| **充足様式** | **A 系は累積的、B 系は全件必須** (原典 p.3)。「B を 80% 満たす」は枠組み上そもそも成立しない主張 |
| **B 系の現在地** | **NemakiWare を保存機構として用いる導入組織は、現状の製品機能だけを根拠に B 系の attest を行えない** (preserver は導入組織であって製品ではない)。「B.1〜B.3 が未充足」と断定はしない — 組織が外部機構や手続で満たす可能性を製品コードの確認だけでは否定できない。Phase 3 完了が前提 |
| **条件付き要求** | **A.6 / A.7 / A.8** は無条件ではない (**法制度または組織自身の必要**が authentication を要求する場合 / 複数コピーがある場合 / **active→semi-active の移行、および記録が電子システムから取り除かれる inactive への移行**がある場合)。A.2〜A.5 と同列に置かない |
| **責務** | 本書の整理では **A.5 を Creator 専属**として扱う (**原典が「A.5 だけ」と定めているわけではない** — A.7/A.8 にも creator 側の義務が読める)。A.2/A.3/A.4/A.6 は Creator と Preserver の双方に掛かる。**評価者は常に preserver** |
| **サブ要求** | 細目があるのは **A.1 (11) / B.1 (3) / B.2 (4) だけ**。A.2〜A.8・B.3 は各 1 文。**逐条マッピングは未作成** (P1-1(d) 待ち) |
| **達成率** | **出さない。** 1 行を「満たした」とする判定基準を我々が持っておらず、数値は根拠のない精度を装う |
| **製品単体** | ATF Report p.27-28 が自動化と手作業の組合せを前提としており、**製品単体で Benchmark を「満たす」とは原文の枠組み上言えない** |

**特に注意** (この領域で最も起きやすい誤り): **A.6 は利用者認証ではない。**
BCrypt / OIDC / SAML は A.6 に該当せず、現状 A.6 に対応する機能は無い。

### §3.1 OAIS (ISO 14721) — 語彙として採用する (2026-08-17 オーナー議論)

OAIS も参照モデルであって製品認証ではないが、この領域の**共通語彙**であり、
調達・監査は SIP/AIP/DIP・Producer/Archive の語彙で会話する。採用理由はもう 1 つあり、
**Phase 1 の機能群は OAIS の PDI (Preservation Description Information) の主要素に対する技術的対応物を持つ** (完全な再構成ではない — 外部レビュー指摘):

| PDI 分類 | NemakiWare 側 |
|---|---|
| Provenance | lineage journal + capture イベント (P1-1) |
| Context | `nemaki:chatContextMetadata` |
| Reference | `nemaki:sourceObjectId` / `sourceUrl` |
| Fixity | `nemaki:contentHash` + fixity service (P1-2) |
| Access Rights | CMIS ACL + ACL-epoch (**技術的アクセス制御のみ — PDI の Access Rights Information は法的・契約上の条件も含む**。そちらはメタデータで持つ設計が P3-1 に要る) |

**位置づけ**: NemakiWare は OAIS でいう **Producer〜軽量 Archive** (現用記録システム。
ISO 15489 の層)。本格的な保存処理は §4 Phase 3 (P3-4) の移管先に委ねる。**Producer である
以上、一次成果物は SIP** — AIP/DIP を名乗るのは「軽量 Archive」の責務範囲を定義してから。

**認証の但し書き**: InterPARES 側の認証制度は**確認できていない**が OAIS 系には在る —
ISO 16363 (重量級・正式認証は世界的にも稀) と **CoreTrustSeal** (軽量・現実的)。
ただし**認証対象は運用されるリポジトリ (組織) であってソフトウェアではない**。
言えるのは「顧客が CoreTrustSeal 等を目指す際に要求される技術的能力の提供と
要件対応表の公開」まで (§5 の禁じ手と同じ構図)。

実装語彙としては **PREMIS 3.0** を採る。journal のイベントは PREMIS の controlled
vocabulary に**クロスウォークで固定**する — `capture` / `fixity check` / `migration` は語彙に
在るが、処分・移管系は `deaccession` / `deletion` / `transfer` 等との**使い分けを表で確定**
してから使う (「ほぼ 1:1」という当て込みはしない — 外部レビュー指摘)。

---

## §4 ケイパビリティ・ロードマップ

### Phase 1 — 証拠チェーンの成立 (3.4。§2 前提工事と並走可、破壊なしで開始可能)

| ID | 何を | 具体 |
|---|---|---|
| **P1-1** | **Capture Provenance の原子化** — 「必ず刻む」を設計として実装する。**現在地 (2026-08-22)**: (a) outbox 実装済み。(b) v2 表現 実装済み。**(c) 更新制約 実装済み** — `Patch_ChatContextEvidenceReadOnly` が既存デプロイの定義を書き換え、`mergeAspectProperties` が消去も塞ぐ ([`p1-1c-evidence-updatability.md`](p1-1c-evidence-updatability.md))。**(d) 着手済み** — 棚卸し ([`p1-1d-scope-inventory.md`](p1-1d-scope-inventory.md))、モデル本体 ([`p1-1d-evidence-data-model.md`](p1-1d-evidence-data-model.md))、証拠型 ([`p1-1d-evidence-types.md`](p1-1d-evidence-types.md))、個人データ ([`p1-1d-evidence-disclosure.md`](p1-1d-evidence-disclosure.md))。**(d) の残りと (e)**: [`p1-1-remaining-plan.md`](p1-1-remaining-plan.md)。<br>**以下は 2026-08-20 時点の記述で、(c)・(d) の部分は上記に置き換わっている**: (a) の**可視化**は PR #506 で完了 (5 巡のレビューで NO BLOCKERS)。(b) snapshot は content 状態 (機械可読キーは `contentStored` = `"true"` / `"false"` / `"unknown"` — 「判定できない」を「保存していない」に潰さない) / contentHash / chat* / `executedBy`+`onBehalfOf` を運ぶ (PR #507)。(c) `chatCapturedAt` の取込時スタンプは実装済み。**未了**: (b) の残り (下記)、(c) の **更新制約** — `Patch_ChatContextMetadataSecondaryType` は既存型にプロパティ id を足すだけで updatability を書き換えない (`:50`) ので、コードを変えても新規デプロイにしか効かず構成が割れる。既存プロパティ定義を書き換える移行パッチと、我々自身の aspect 直接更新が阻まれないかの確認が要る、(d) 空コンテンツ・version ごとの hash・メタデータ hash の evidence data model。**(b) から移したもの**: `chatCapturedAt` の刻印位置 (aspect 付与を `execute()` に引き込む必要がある)、会話の**範囲** (`captureWindowStart/End` / `evidenceScope` / `selectionReason` — 取込元の性質ではなく取込の判断なので、再取込で上書きされる endpoint attribute には置けない)、`chat.participants` と `chat.channelName` (個人名。カタログには journal のような保持期限が無い)、**Process 属性の v2 供給** (`folderId` / `importMode` / `sourceDescription` / `externalStableKey` — v2 record では `legacyEventAttributes` が空になるため、**必須属性が既定値で埋まる**)、(e) 失敗時の隔離 + 再構築可能性 (実行起源の記録もここ — 委譲実行の executedBy は現在 admitted-unknown) |
| **P1-2** ✅ 第 1 段 2026-08-24 ([`p1-2-fixity.md`](p1-2-fixity.md)) — 検証器・走査・運用 API・`STORED_REVERIFIED` を実装。**定期ジョブは未配線** (全添付の再読みは重く、既定 on は規模別実測の後 — §2-3 と同じ前提)。スコープ付き走査 (1 件 / 1 フォルダ) は今日から使える | **Fixity service** | leader-gated の定期ジョブ (既存スケジューラパターン) が保存コンテンツの SHA-256 を再計算し `nemaki:contentHash` と照合。結果を journal に記録、乖離は隔離 + アラート。運用 API は再索引の verdict 型を踏襲 (`COMPLETE` の意味論の教訓をそのまま適用: 「検証した範囲」を常に言う)。**対象は cold 層 (S3) を含む** — 詳細は Phase 3 前提モデルの原則 3 |
| **P1-3** ✅ 第 1 段 2026-08-24 ([`p1-3-evidence-ledger.md`](p1-3-evidence-ledger.md)) — 追記専用エントリ・Merkle checkpoint・inclusion proof・連鎖検証 (改竄 / 削除 / 並べ替え / fork / genesis を別々に報告)。**永続化と定期 checkpoint は未配線、外部アンカーは P2**。連鎖ドメインは**リポジトリ単位**に決定 (代償: アンカー費用がドメイン数だけ掛かる。集約根は別増分) | **Tamper-evident evidence ledger** | **journal と evidence を分離する** (外部レビュー + purge 衝突の帰結): 配送用 journal は現行どおり purge 可 (`lineage.retention.days`)、**evidence ledger はアプリケーション層で追記専用**として扱い (物理的な不変性ではない — DB 管理者は到達できる)、期間・法的根拠別に保持し、purge 境界ごとに checkpoint + 外部アンカー + inclusion proof を残す。連鎖の構築は素朴な LeaderElection 流用ではなく、**既存の fenced sequencer (`CouchLineageSequencingStore` — lease generation CAS 持ち) を土台**に: chain domain (repo 単位か全体か) / sequence と連鎖の同一 CAS 確定 / failover 時の fork 検出 / **unsequenced backlog がある間の anchor 禁止** / purge 後 genesis / 「順序 = 確定 sequence 順であって時計順ではない」の明記。但し書き: 連鎖が固定するのは記録された順序 (P1-1 が先)、アンカー以前しか不整合を検出可能にできない (頻度 = 書き直され得る窓) |
| **P1-4** | **真正性レポート (evidence package)** | 文書 1 件について identity 属性・contentHash と fixity 履歴・custody チェーン (journal 抜粋)・アクセス監査・バージョン系譜・処理環境 (Barrier ダイジェスト) を 1 つの JSON + 人が読む PDF に集約する API/UI。**マーケの主砲** (§5) |

### Phase 2 — 信頼できる時刻 (3.4)

**前提となる信頼のはしご** (2026-08-17 オーナー議論より)。時刻の第三者証明は原理的に
運用者の外の証人を要するが、「外部 = 有償契約」ではない。アンカー先をプラガブルにし、
顧客が段を選べる形にする:

| 段 | アンカー先 | 外部依存 | 費用 | 証明できること |
|---|---|---|---|---|
| 0 | ハッシュ連鎖のみ | なし | 0 | **今ある連鎖の自己整合性と、そこに書かれている順序**まで。**「これが当時の台帳である」「実際の事象順である」ことは示さない** — 信頼できるチェックポイントか外部アンカーが無ければ、管理者が連鎖ごと作り直せる |
| 1 | + Atlas/Purview (P1-3) | なし (顧客自身の別システム) | 0 | **時刻証明ではなく、独立でもない。** 両方を直せる管理者に対しては恒久的・独立な検出が成立しない (職務分掌や Atlas 側の履歴保護があれば運用上の検出は成立しうる — §4) |
| 2 | + **OpenTimestamps** (Bitcoin へのコミットメント集約) | あり・契約不要 | 0 | **`.ots` commitment と信頼するブロックヘッダの対応**を、当社・カレンダー抜きで第三者が照合できる。読めるのは「**そのコミットメントがそのブロック時刻までに存在した**」まで — 記録そのものやメタデータの真実性の証明ではない |
| 3 | + RFC 3161 TSA (**認定 TSA を選んだ場合に**日本の制度上の裏付け — プロトコル自体は認定を意味しない) | あり・有償 | 僅少 (下記) | TSA トークンが、選んだ信頼・ポリシー検証のもとで、**その message imprint と `genTime` の結び付き**を示す。制度上の裏付けと細かい時刻粒度が得られるが、**記録やメタデータの真実性の証明ではない** |

**コスト設計の要**: ハッシュ連鎖があるため、タイムスタンプは文書ごとではなく
**連鎖のアンカーに 1 日 1 回**で、**そのアンカーに含まれた evidence ledger のエントリ全部**に
継承される (時刻粒度は「その日中」。細かくするなら毎時)。**「全文書」ではない** — 通常の
CMIS 作成は lineage を出さず、発行は現状 fail-open で outbox も未実装なので、**連鎖に実際に
入ったエントリだけ**が対象になる。エントリと記録の結び付けは P1-1(d)。認定 TSA でも **1 つの TSA 送付先・1 つのアンカードメインあたり**月 30 スタンプ程度で済む。
**P1-3 で連鎖のドメイン (リポジトリ単位か全体か) が未決**なので、単一の集約根を設計しない
限りこの数はドメインの数だけ掛かる。

| ID | 何を | 具体 |
|---|---|---|
| **P2-0** | **アンカー先のプラガブル化** | P1-3 の日次アンカー D の送出先を多重化: Atlas (段 1、既存 sink) / OpenTimestamps (段 2) / RFC 3161 TSA (段 3)。段ごとに独立に有効化 |

**段 2 の実装方式 (§9-5 を受けた設計判断、2026-08-18)**: 3 択のうち **(c) Python 公式
クライアントの sidecar 化を第一候補**とする。

| 方式 | 採否 | 理由 |
|---|---|---|
| (a) `java-opentimestamps` を採用 + 徹底 exclusion | ✗ | bitcoinj 0.14.7 経由で **H2 1.3.167 (RCE)** 等を WAR に持ち込み、しかも `OpenTimestamps` 本体が bitcoinj を import するため単純 exclude 不可。**製品の攻撃面を広げてまで得るものが無い** |
| (b) stamp/upgrade を Java で自前実装 | △ | 通信は `POST /digest` と `GET /timestamp/{commitment}` の 2 本だけで済むが、**`.ots` の形式仕様書が存在しない** (Python 参照実装が正典) ため、標準クライアントで検証できる形式を自前で再現する責任を負う。「第三者が独立に検証できる」ことが段 2 の存在理由なので、ここを自前実装で外すと価値が消える |
| **(c) Python 公式クライアントを sidecar 化** | **◯** | 形式の正しさを参照実装に委ねられる。TEI (RAG) で **sidecar パターンは既に本番構成にある**ので運用上の新規性も小さい。代償は**コンテナが 1 つ増えること** |

**着手時に確認する前提**: sidecar への入力はハッシュのみ (原文は渡さない) で足りること、
`.ots` の upgrade を定期実行するスケジューラをどちらに置くか、オフライン環境での縮退動作。
| **P2-1** | **OpenTimestamps アンカー** | D をカレンダーサーバへ送信 (HTTP POST のみ、鍵・ウォレット・暗号資産保有なし)。**公式クライアントは nonce 付き commitment を送る** — 生の D すら外に出ない (privacy 特性として明記・踏襲する)。**証明は二段階** — 送信直後は pending、Bitcoin ブロック確定後 (**数時間かかり得る**) にジョブが `.ots` を upgrade (dead-letter/リトライの既存パターン)。複数カレンダー併用。`.ots` の commitment とブロックヘッダの対応**だけ**は、`.ots` + 信頼できる Bitcoin ブロックヘッダ列で**当社にもカレンダーにも依存せず**照合できる。**それ以上は独立に検証できない** — 捕獲の網羅性・メタデータの真実性・最初のハッシュが正しく取られたことは、この照合では担保されない。証明の意味は「**そのコミットメントがそのブロック時刻までに存在した**」という上限側の存在証明であり、対称な誤差幅の時刻証明ではない。**主語はコミットメントであって記録ではない** |
| **P2-2** | RFC 3161 タイムスタンプ (段 3) | 日次アンカー + 必要ならアーカイブ遷移時に TSA トークンを取得し保存。認定 TSA / フリー TSA をプラガブルに。**TSA policy OID・証明書/失効情報 (CRL/OCSP)・nonce・accuracy の保存**まで含めて「検証可能なトークン」とする (長期検証情報は P2-3) |
| **P2-3** | 長期有効性 | **timestamp renewal と hash-tree renewal は発火条件が異なる別操作** — 「再タイムスタンプ」の一語で潰さない。ERS は RFC 4998 (ASN.1/CMS) と RFC 6283 (XMLERS) が**表現形式の選択肢**で、採用可否と形式を設計判断として比較。`.ots`・TSA トークンは SIP (P3-1) に同梱して保全 |

**採らないもの**: Ethereum 系 (ガス代が発生)・プライベート/コンソーシアムチェーン
(信頼の依存先がコンソーシアムに戻り、アンカーの目的を壊す)。

### Phase 3 — 保存パッケージと移行 (3.4)

| ID | 何を | 具体 |
|---|---|---|
| **P3-1** | **E-ARK SIP エクスポート** (Producer の一次成果物) | **E-ARK SIP (CSIP 2.2.0 を対象に生成し、成果物ごとに検証: METS 1.12 構造記述 + PREMIS in METS Guidelines 2017)** — 使用する仕様版・profile・バリデータとルールセットの**版を固定して宣言**する。journal イベント → PREMIS イベント (クロスウォーク表で語彙を確定)、チャット文脈 → 記述メタデータ。evidence package (`.ots`・TSA トークン含む) は CSIP 規約に従う置き場所に同梱 (正位置は着手時に要確認)。既存 `ImportExportResource` を土台に。**出力はバリデータ通過を CI テストとして固定** (§6)。AIP/DIP の生成は「軽量 Archive」責務を定義してから別途判断 (§3.1)。BagIt は **Archivematica 接続層の transfer 形式としてのみ** (P3-4) |
| **P3-2** | 保存フォーマット複製の証跡化 | PDF/A 変換を「複製イベント」として journal に記録 — B.2 の要求どおり **hash だけでなく日時・責任者・取得記録との関係・影響・不完全性の開示**まで。**現行 jodconverter は PDF/A profile の指定・検証を持たない** (rendition 基盤のみ) — PDF/A 出力と検証 (veraPDF 等) は新規要素。**これは利便コピーであって保存計画の代替ではない** (下記の非目標) |
| **P3-3** | 処分証跡 | retention による削除を disposition イベントとして残す (何を・いつ・どの規則で)。**置き場は evidence ledger 側** — 配送 journal は purge 対象、Atlas は独立して永続ではないので、そこだけでは証跡にならない。保持期間と inclusion proof も定義する |
| **P3-4** | **保存システムへの移管 (custody transfer プロトコル)** | 「双方向参照」は時系列で成立させる — **SIP 作成時点で先方 AIP ID は存在しない**ので、SIP には連鎖抜粋を入れ、先方の受領・AIP 生成**後**に受領証を journal へ追記して次アンカーに含める (以後の不整合が検出可能になる。凍結ではない)。状態機械で管理: `PACKAGE_CREATED → SENT → RECEIVED → VALIDATED → INGEST_ACCEPTED → AIP_CREATED → RECEIPT_VERIFIED → CUSTODY_TRANSFERRED → LOCAL_DISPOSITION`。受領証の中身は AIP checksum だけでなく **署名付き受領・submission ID・AIP ID・対象 SIP digest・検証結果・先方 agent** まで。失敗・再送・重複取込・部分受入・先方 AIP 再生成の扱いを submission agreement として明文化。**RODA は E-ARK 対応** (公式に「E-ARK SIP/AIP/DIP と 100% compatible」。ただし**受入 profile/版の対応表は未確認**で相互運用の保証には実機受入試験が要る — §9-3)。**Archivematica は E-ARK SIP を直接取り込めないことが確定** (transfer type は 8 種のみで E-ARK 相当が無い — §9-4) → **BagIt (`zipped bag`) に包む接続層が必須**。API 仕様と落とし穴は §9-4 |

#### Phase 3 の前提モデル: リテンション終端の 3 つの出口 (2026-08-17 オーナー議論)

現行の retention (ACTIVE → ARCHIVED_LOCAL → ARCHIVED_COLD、S3 + Legal Hold) は
**保管層の移動**であり、custody (管理責任) は NemakiWare に残る。Archivematica 移管は
**custody の移転**であり、両者は同じ「アーカイブ」という語でも別物。モデルは
「cold の先に移管」ではなく、**スケジュール終端の 3 択**:

| 出口 | いつ | custody |
|---|---|---|
| 継続保管 (cold 層、実装済み) | 法定保存期間中・低頻度アクセス・legal hold | NemakiWare のまま |
| アーカイブ移管 (P3-4) | 永年/歴史的価値、**保存期間がシステム寿命を超えるもの**、組織的分離 | 移転 |
| 廃棄 (P3-3) | 期間満了・保存価値なし | 消滅 (**証跡を残す設計にする** — 現行 P3-3 の「journal + Atlas」では journal が purge 対象・Atlas も独立して永続ではないため、**disposition エントリを保持側の evidence ledger に置き、保持期間と inclusion proof を定義する**のが条件) |

- **選別 (appraisal)**: どの記録を移管するかをリテンション規則の属性として持つ (P3-4 の設計要素)
- **移管後の NemakiWare 側**: 削除 + 処分証跡、または**非権威スタブ** (メタデータ +
  AIP 参照 + evidence package を残し検索可能性を保つ。A.7 のため「非権威」を明示マーク)。
  推奨はスタブ
- **用語の言い分け (実害あり)**: 現機能名 ARCHIVED_* と OAIS の Archive が衝突する。
  文書と UI では「保管層 (storage tier、custody 不変)」と「アーカイブ移管 (custody
  transfer)」を言い分けること

#### 移管時のリネージ 3 原則

1. **配送用 journal は移管で出ていかない** — ローカルに残る (**ただし purge 対象**。「追記専用で残り続ける」ではない)。SIP に入るのは
   **抜粋の複製** (当該文書の capture〜移管直前のイベント列を PREMIS に変換 +
   連鎖の該当区間 + アンカー証明)
2. **移管イベントが双方向の継ぎ目** — journal に「対象 + contentHash + 根拠規程 +
   承認者 + 宛先」を刻み、取込完了後に**先方 AIP 識別子と AIP チェックサムを追記**。
   次のアンカーに含まれると、**以後の不整合が検出可能になり** (証明を保持して検証した場合)、向こうのパッケージにこちらの連鎖が入り、こちらの
   連鎖に向こうの指紋が入る — 検証者は custody 境界をまたいで歩ける
3. **cold 層は custody 不変の内部イベント** — 移動先・移動前後の hash 照合・legal hold
   適用を同じ journal に刻む。**現行の cold move は移動前後の hash 照合を実装していない**
   (`RetentionScheduler:567` / `S3StorageAdapter:74` — S3 put + Legal Hold + 状態遷移のみ)。
   **fixity service (P1-2) は cold 層も対象** (S3 の multipart ETag は MD5 ではないため自前
   SHA-256 照合。S3 の checksum-sha256 メタデータ活用が設計点)。「遮断した層こそ誰も
   見ていないのだから fixity が要る」

**Atlas 側**: 移管した記録のエンティティは削除せず「移管済み (AIP=X)」状態へ遷移。
カタログは custody 境界をまたぐ横断地図として残る。**ただし独立性の限界を明記する**:
Atlas/Purview が同一組織・同一 tenant の管理下なら、**その管理者に対しては**恒久的・独立な
検出が成立しない (同じ管理者が両方を直せる) — ただし職務分掌や Atlas 側の履歴保護があれば
**運用上の検出は成立しうる**。それに依存しない検出が立つのは段 2 (組織外アンカー) 以上。
**なお段 2・段 3 でも「書き換えられない」にはならない** — 与えられるのは検出であって
防止ではない (外部レビュー指摘。§5 の禁じ手に従う)。外部アンカーの threat model (変更権限・anchor gap・fork) は
実装前に設計課題として起こす (§8)。

#### 消去要求との調停 (設計課題 — 実装前に確定させる)

journal・audit・Solr/RAG・Atlas/Purview・バックアップ・cold S3・移管先 AIP・evidence
package には**個人識別子 (参加者名等) が複製され得る**。「内容は消したが氏名や参照を
スタブに残す」は消去要求への回答にならない場合がある。少なくとも:

- 優先順位の規則: 法定保存義務・legal hold・公共アーカイブ例外 vs 本人の消去請求
- disposition イベントに残してよい**最小情報**の定義 (仮名化 tombstone と復元可能な
  個人情報の区別)
- Atlas・移管先への deletion/rectification の伝播
- WORM/バックアップ証拠の満了設計
- OTS へは nonce 付き commitment のみ (P2-1 — 生ハッシュも出さない)
- 「消去済みだが証跡あり」と「保存義務により消去拒否」を**別状態**として持つ

**非目標 (明記)**: フォーマット同定 (PRONOM)・保存用正規化・保存計画 (preservation
planning) は**作らない**。Archivematica カテゴリの本領であり、再実装は何年分もの
ニッチな蓄積の劣化コピーになる。NemakiWare が**目指す**位置は「**捕獲から移管までを証拠
イベントとして記録し、規定した移管ステートマシン内の未完了と失敗を検出できる** Producer」
(**Phase 3 の目標であって現状ではない**。「連鎖を切らさない」とは言わない — §5 の訂正。
ステートマシンの外 — source 側の捕獲欠落や対象外経路 — は検出できない)。

**実装ノート (E-ARK パッケージ生成)**: RODA エコシステムの Java ライブラリ
**keeps/commons-ip** (CSIP の生成 + 検証) が第一候補。**P0-5 で調査完了 (§9-3)**:
LGPL-3.0 / 最新 2.12.0 (2026-08-14) / 保守は活発 / Java 17+ / CSIP 2.0.4・2.1.0・**2.2.0** 対応。
**入手経路が最大の制約** — Maven Central には無く、`distributionManagement` は GitHub
Packages 単独で**公開パッケージでも匿名 GET は 401**。`<dependency>` で引くと
**fork PR の CI がシークレット不在で必ず落ちる**。→ **CI 検証は GitHub Release の
CLI fat-jar (匿名取得可) を CLI として叩く**。ライブラリとして生成に使う側だけ
GitHub Packages 認証を使うか社内ミラーに置く。**Python 版 eark-validator は
CSIP 2.2.0 ルールセット未同梱 + 偽陽性 issue 多数でゲートに使えない**。不採用の場合の代替は METS/PREMIS の直接生成 (JAXB — SOAP 経路で
保守している JAXB 資産がそのまま効く)。いずれでも**公式/参照バリデータでの検証を CI に
固定する**方針は変わらない。

### Phase 4 — 第三者検証 (継続)

| ID | 何を | 具体 |
|---|---|---|
| **P4-1** | **ベンダー非依存の検証 CLI** | evidence package + Atlas 参照だけで、NemakiWare 無しにハッシュ連鎖・タイムスタンプ・fixity 履歴を検証できる公開ツール。メッセージは「**NemakiWare を動かさずに検証できる**」まで — **「私たちを信用しなくてよい」とは言わない** (2026-08-20 訂正)。外部アンカーが減らせるのは「アンカー済み履歴の変更検出」における NemakiWare 依存だけで、**捕獲の網羅性・メタデータの真実性・最初のハッシュが正しく取られたことは、それでは担保されない** |
| **P4-2** | 制度への接続 | ISO 16363 (信頼できるデジタルリポジトリ) セルフアセスメントの公開。日本市場向けに JIIMA 認証 (電帳法) の取得検討。※いずれも要件調査から |

---

## §5 マーケティングメッセージの設計

**原則: この製品文化のまま外に出す。** 3.3 のリリース台帳がそうだったように、
**すべての主張に検証根拠を添える** — 実際に走らせて確かめられる**検証手順**か、
コードを読んで確認しただけの**設計上の期待**か、**どちらかを必ず明示する** (後者を前者に
見せない。2026-08-20 に規則を明確化)。これは制約ではなく差別化 — 「真正性」を扱う
製品が根拠なしの形容詞を使ったら、その一点で信用を失う。

### 言えるようになる文言 (Phase 到達ごと)

- **Phase 1**: 「Slack・Teams・クラウドストレージから取り込んだ瞬間に、**その取込経路が
  与えた範囲で**、SHA-256 指紋・
  取込文脈 (誰が・どのチャンネルで・どの範囲を)・処理環境まで記録。**ワンクリックで
  真正性レポート**」
- **P1-3**: 「来歴はハッシュ連鎖の evidence ledger + 外部カタログ (Microsoft Purview /
  Apache Atlas) への日次アンカー」— 言えるのは **「アンカー済みの履歴について、管理者による
  事後の書き換えを検出できる」まで**。条件は 2 つで、どちらも文言に含める: 段 2 (組織外
  アンカー) 以上であること (同一 tenant の Atlas だけでは成立しない)、および
  **直近アンカー以降の未アンカー区間は保護されない** — そこは管理者が連鎖ごと作り直せる
- **Phase 2**: 「**`.ots` commitment と信頼するブロックヘッダの対応**を、当社もカレンダーも
  介さずに第三者が照合できる — **無償構成 (OpenTimestamps) から**。**主語はコミットメントで
  あって記録ではなく、メタデータの真実性は示さない**。認定 TSA を
  足せば制度上の裏付けと細かい時刻粒度まで」
- **Phase 3**: 「**取込で得た証拠をパッケージまで持ち越し、`PACKAGE_CREATED` 以降の移管
  ステートマシン内の未完了と失敗を検出できる**」— **取込からパッケージ作成までの区間は
  この検出の外**で、「取り込んだ記録がパッケージ作成に一度も入らなかった」ことは
  現状のステートマシンでは検出できない (2026-08-20 訂正)。— 「**連鎖が一度も切れない**」とも「欠落を検出できる」とも
  言わない (2026-08-20 訂正)。**ステートマシンの外は検出できない** — source 側の捕獲欠落
  (Slack で消された発言など) と、そもそも lineage の対象外の経路がそれにあたる。
  RODA / Archivematica 等の保存システムへ、捕獲文脈・fixity 履歴・アンカー証明を携えた
  SIP を渡す」「**生成した個々のパッケージが、版を固定したバリデータ (CSIP 2.2.0) を通った**
  ことを機械検証つきで示す」— **「E-ARK 準拠の製品」とは言わない** (2026-08-20 訂正)。
  言えるのは**成果物単位の事実**だけで、層や製品の恒常的な準拠ではない
- **Phase 4**: 「**NemakiWare を動かさずに検証できる** — 公開 CLI と外部カタログの二系統照合」。
  **「NemakiWare を信用しなくてよい」とは言わない** (2026-08-20 訂正) — 組織外アンカー
  (段 2 以上) が無ければ、同じ管理者が証拠パッケージとカタログを揃えて書き換えられる。
  減らせるのは「**アンカー済み履歴の変更検出**」における NemakiWare 依存だけで、
  **捕獲の網羅性・メタデータの真実性・最初のハッシュの正しさは外部アンカーでは担保されない**
- 通奏低音: 「InterPARES の真正性要求事項への**対応マッピングと検証手順を公開**」
  「OAIS の語彙 (SIP・PDI) で説明可能。CoreTrustSeal 等を目指す組織の技術要件に
  対応表で応える」

### 禁じ手

- 「InterPARES **準拠**」— 準拠を認定する制度を**確認できていない** (§9-1 の未確認事項を参照)。言えるのは「要求事項に対する
  対応表と根拠の公開」まで。
- 「OAIS **認証**」「ISO 16363 **認証済み製品**」— 認証対象は運用組織であって
  ソフトウェアではない (§3.1)。
- 「電帳法**対応**」— JIIMA 認証を取るまでは「電帳法の保存要件を意識した設計」まで。
  **これは自主規制であって、JIIMA 認証が法的必須という意味ではない** (外部レビュー指摘)。
  電子取引データの真実性確保にはタイムスタンプ以外の経路 (訂正削除不能/履歴保全システム、
  事務処理規程) があり、**電子取引とスキャナ保存の要件を混同しない** — 文言確定前に
  国税庁の現行一問一答で経路ごとに確認する (P0)。
- 「**管理者でも書き換えられない**」— 段 2・段 3 を有効にしても**成立しない**。外部アンカーが
  与えるのは**防止ではなく検出**であり、しかも「証拠を保持していて、かつ検証した場合に
  不一致に気づける」という条件付き、かつ**アンカー済みの区間に限る** (直近アンカー以降は
  管理者が連鎖ごと作り直せる)。言えるのは「**アンカー済みの履歴について、事後の書き換えを
  検出できる**」まで (2026-08-20 訂正)。
- **検証根拠を書けない主張は出さない。** 手順が書けない場合でも「コードを読んで確認した
  設計上の期待」と明示すれば載せてよい。**手順が無いことを黙って隠すのが禁じ手。**

### 想定市場 (優先度はオーナー判断)

建設・製造の長期保存義務 / 製薬 (ALCOA+ とデータインテグリティ — P1 の語彙がそのまま
通じる) / 公文書・自治体 / 法務 (証拠保全・eDiscovery)。日本市場では電帳法 (電子取引
データ保存) が最も広い入口。

---

## §6 検証の作法 (全 Phase 共通)

- **反証可能な形で作る**: fixity は改竄を実際に注入して検出することをテストで固定。
  ハッシュ連鎖は途中改竄で必ず破れることを固定。golden vector 文化 (LineageBarrier) を
  踏襲し、証拠形式の式を凍結する。
- **「検証した範囲」を常に言う**: verdict 型 API の教訓 (`COMPLETE` ≠ 全部在る) を
  evidence の語彙に最初から入れる。
- マッピング表 ([`interpares-mapping.md`](interpares-mapping.md)) の各行は「機能がある」では
  なく「**この手順で確認できる**」または「**コードを読んで確認した (手順は書けない)**」で閉じる。
- **E-ARK 出力は公式/参照バリデータの通過を CI テストとして固定** (P3-1)。
  **成果物単位で「通った」と言う以上、その根拠が毎ビルド機械検証される状態を保つ。**

## §7 バージョン割当の提案 (オーナー決定事項)

| バージョン | 中身 |
|---|---|
| **3.3.1** | 非破壊パッチ ([`v3.3.1-plan.md`](v3.3.1-plan.md)) |
| **3.4** | §2 前提工事 (2-1〜2-3 は必須、2-5 の予告類。2-4 は設計判断が付いたものだけ) + **Phase 1〜3 の全部** (証拠チェーン成立 / 時刻証明 — アンカー多重化・OTS・TSA / E-ARK SIP・移管プロトコル・処分) |
| 継続 | Phase 4 (検証 CLI・制度) |

**オーナー決定 (2026-08-18): リリース番号上は Phase 1〜3 をすべて 3.4 に統合**。
旧割当 (3.4=Phase 1 / 3.5=Phase 2 / 3.6=Phase 3) は廃止。外部レビュー指摘
「時刻証明とパッケージングは独立したリリース境界を持つ」は、**リリース境界では
なく 3.4 開発サイクル内のマイルストーン境界として維持**する — 実装・受入の順序
(P1 証拠チェーン → P2 時刻証明 → P3 パッケージング/移管) と各 Phase の受入ゲート
は変えない。番号だけを一本化する。

## §8 直近アクション (P0)

**進捗 (2026-08-20)**: **1・2・3・5 は完了** (結果は §9)。P3-4 の前提だった Archivematica の
受入 API も先行調査済み (§9-4)。**4 は成果物が完成し、オーナー / マーケの内容確認待ち**
(製品側の作業は無い)。**6 は保留** — 実装後に回すというオーナー決定 (下記) による。
→ **P0 のうち製品側でやることは残っていない。** 4 と 6 はどちらも人の判断待ち。

### オーナー決定 (2026-08-18)

| 決定事項 | 決定 |
|---|---|
| **時刻証明をどこまで登るか** | **段 2 (OpenTimestamps) と段 3 (認定 TSA) の両方を実装する。** 段 2 は実装コストが高い (§9-5: java-opentimestamps は依存が毒で単純採用不可) が、無償・組織外で、**`.ots` commitment とブロックヘッダの対応を第三者が当社抜きに照合できる**という、段 3 で代替できない価値があるため見送らない |
| **真正性レポートの宛先** | **監査人向けから着手** (上記 P0-4) |
| **法務確認の順序** | **実装後に専門家向け照会レポートを書いて依頼** (上記 P0-6) |

**この決定の帰結**: 段 2 または段 3 を有効にした構成では、「**アンカー済みの履歴について、
管理者による事後の書き換えを検出できる**」と言えるようになる (**未アンカー区間は保護されない**) (§5 / §4 の条件付けはそのまま維持)。段 1 のみの構成に
ついては従来どおり言えない。

> **文言の訂正 (2026-08-20, 外部レビュー)**: 従来ここには「**管理者でも書き換えられない**」
> と書いていたが、これは段 2・段 3 を有効にしても**成立しない**。管理者はローカルの記録を
> 書き換えられる。外部アンカーが与えるのは**改変の防止ではなく検出**であり、しかも
> 「適切な証拠を保持していて、かつ検証を行った場合に、過去のアンカーとの不一致に気づける」
> という条件付きの検出である。しかも**アンカー済みの区間に限る** — 直近アンカー以降は
> 管理者が連鎖ごと作り直せる。**言い切れるのは「アンカー済みの履歴について検出できる」まで。**
> 「書き換えられない」は禁じ手に加える (§5)。


1. ✅ **InterPARES 原典の条文確認** (完了 — §9-1)。**表への反映も完了 (2026-08-20)** —
   成果物は [`interpares-mapping.md`](interpares-mapping.md) (公開前提の独立文書)。
   条件付き要求 (A.6/A.7/A.8)・責務境界 (C/N/O)・A 系と B 系の充足様式の違い・
   各行の検証手順を分けて書いた。**細目の逐条マッピングだけは未作成** — 判定基準が
   evidence data model に依存するため P1-1(d) 待ち
2. ✅ **`lineage.mode=journaled` の実測** (bedroom 規模のみ完了 — §9-2)。
   **+12.3% (ingest 経路・targets 空・bedroom 規模)** / journal 1 event = 生 JSON 約 755B。
   **10 万規模は未実測**。なお **通常の CMIS 作成経路は lineage を出さない**ことも判明
3. ✅ **アンカー実装調査** (完了 — §9-5)。結論: **java-opentimestamps は事実上停止**
   (master 2021-05-05 / Central 1.20 が最後) で、**本当の障害は bitcoinj 0.14.7 経由の
   依存の毒性** (H2 1.3.167 の RCE 等) かつ **bitcoinj は本体が import するため単純
   exclude 不可**。段 3 (TSA) は **BouncyCastle が既に WAR にある**ので追加依存ゼロで
   始められる (ただし bcprov/bcpkix の版ずれと `validate()` が拒否応答を素通しする件に
   注意)。nonce = `SHA256(D‖random16)` は本書の記述どおりで正しかった。認定 TSA 6 業務と
   コスト試算の裏付けも §9-5
4. ✅ **P1-4 のモック** (成果物は完成 — [`authenticity-report/`](authenticity-report/))。
   宛先はオーナー決定で「監査人」に確定 (2026-08-18)。JSON スキーマ + 人が読む 1 枚 +
   **「証明していないこと」(`notProven`) を必須要素**として実装。日英 2 言語を
   `example.json` 単一ソースから生成し、`check-mock.py` が schema 適合・全言語網羅・
   ページと JSON の一致・**誇張の不在** (撤回した `independentOfOperator` が復活したら落ちる)
   を機械検証する。**残るはオーナー / マーケの内容確認**で、これは人の判断
5. ✅ **E-ARK 実装調査** (完了 — §9-3) — keeps/commons-ip の入手経路・ライセンス・成熟度
   (Central に無いことは確認済み)。**CSIP 2.2.0 / E-ARK SIP profile / METS 1.12 /
   バリデータとルールセットの版を固定**して宣言する形を決める
6. ⏸ **消去要求との調停の法務確認** — **オーナー決定で実装後に回す** (2026-08-18)。
   一通り実装を終えてから**専門家向けの照会レポートを書いて依頼する**形にする
   (実装が固まる前に問うと、仮定だらけの質問になって回答も使えない)。
   論点は個人情報保護と保存義務の優先順位 (法務)、電帳法の経路別要件
   (電子取引 / スキャナ保存 — 税務)。**実装は法務確認を待たずに進めるが、
   §4 の「消去要求との調停」の設計課題は未確定のまま実装しないこと**

### 実装前に §に起こす設計課題 (外部レビュー 2026-08-17 より)

- **Slack 等の source-side 捕獲完全性**: API scope・編集/削除履歴・thread/添付・cursor gap・
  取得 request ID。**webhook HMAC は通信相手の検証であって Slack 内の記録内容の真正性
  証明ではない** — 「**evidence の対象範囲は取込時点から始まる**」という境界を evidence に明記する。**「取込時点以降を証明する」とは書かない** — fail-open・outbox 未実装・v2 write flip での属性喪失・対象外経路があり、取込後の網羅性や連続性を自動で証明する実装はまだ無い
- **Evidence data model**: hash アルゴリズム ID・バイト長・version/object ID・メタデータ
  hash・添付/表現グラフ・正規化・agent・event outcome・失敗証拠
- **Fixity policy**: 全件/標本・周期・初回 baseline・「読めない」と「不一致」の区別・
  修復ソース・隔離・S3 version ID・リストア後の全検証
- **外部アンカーの threat model**: Atlas の変更権限・別 tenant 構成・複数 anchor・
  anchor gap・fork・時計故障
- **Submission agreement** (P3-4): profile/版・許容 CITS・上限サイズ・暗号化・malware・
  PII・拒否条件・再送・受領証・custody acceptance
- **長期暗号運用**: algorithm deprecation registry、timestamp renewal / hash-tree renewal の
  発火条件
- **非権威スタブの詳細**: 最小メタデータ・アクセス制御・権威コピーへの解決・リンク切れ・
  消去時の破棄
- **CoreTrustSeal 支援の範囲**: 機能チェックリスト外 (組織統治・財務・designated
  community・承継計画・DR) は**顧客組織側の課題**と明示する

---

## §9 P0 調査結果 (2026-08-18)

一次資料に当たって確定した事実を記録する。**未確認は未確認と書く。**

### §9-1. InterPARES 原典 (P0-1)

**正典**: Authenticity Task Force, "Appendix 2: Requirements for Assessing and Maintaining
the Authenticity of Electronic Records", in *The Long-term Preservation of Authentic
Electronic Records: Findings of the InterPARES Project* (Duranti ed., Archilab, 2005),
pp.204-219。文書本体の日付は **2002 年 3 月**。
<https://www.interpares.org/display_file/interpares_book_k_app02.pdf>

**前提の訂正 2 件** (これまでの本書の書き方が誤っていた):

1. **これは InterPARES 1 の book の Appendix 2** であって IP2 のものではない。IP2 側の
   再録は Preserver Guidelines 冊子で、そちらは自ら "abridged version" と称する**要約版**
   なので正典に使わない。
2. **サブ要求が存在するのは A.1 と B.1・B.2 だけ。** A.2〜A.8 と B.3 に細目は無く、
   各 1 文の要求である。「A.1.a 等が全体にある」という想定で表を作ると破綻する。
   全数は A.1 + 配下 11 項目 + A.2〜A.8 の 7 項目 / B.1 (+3) + B.2 (+4) + B.3。

**Benchmark と Baseline の違いは主体ではなく充足様式** (原典 p.3 に明記):

| | 充足様式 | 意味 |
|---|---|---|
| Benchmark (A) | **累積的 (cumulative)** | 満たした数と充足度が高いほど真正性の**推定が強まる**。部分適合に意味がある |
| Baseline (B) | **全件必須 (all-or-nothing)** | 全部満たさなければ preserver はコピーの真正性を attest **できない**。**部分適合を「B に部分準拠」として示さない** — ただし「個々の統制に価値が無い」という意味ではなく、能力の充足状況は別枠で報告してよい |

→ **マッピング表の適合度表記を A 系と B 系で分けること。** 「B を 80% 満たす」は原文の
枠組み上、成立しない主張。

**責務境界** (原典 B.1 Commentary p.10 / IP2 Preserver Guidelines §1.5):

- **A.5 (documentary form の確立) を Creator 専属として扱う。** ただし**原典が「A.5 だけ」と
  明言しているわけではない** — A.7・A.8 にも creator 側の確立義務が読める。これは本書の
  責務モデル上の整理である (2026-08-20 訂正)。
- **A.2 (アクセス権限)・A.3 (喪失/破損対策)・A.4 (媒体・技術対策)・A.6 (authentication) は
  Creator と Preserver の双方に掛かる。** preserver は移管時に A.1 属性と A.8 文書の
  引継ぎを**検証**し、自らの保管下で A.2/A.3/A.4 を確立・実装・**定期監視**する。
- **A.6 / A.7 / A.8 は条件付き要求** (**法制度または組織自身の必要**が authentication を要求する場合 / 複数コピーが
  存在する場合 / **active→semi-active の移行、および記録が電子システムから取り除かれる
  inactive 状態への移行**がある場合)。無条件の A.2〜A.5 と同列に置かない。
  **条件の記述は 2026-08-20 に原典へ照らして訂正した** — A.6 を「法制度」だけ、A.8 を
  「active→semi-active」だけに絞っていたのは原典より狭かった (外部レビュー)。
- Benchmark の**評価者は常に preserver**。実施主体 (creator) と評価主体が分離している。

**実装上の含意**:

- A.1 の「属性と記録の link」は**概念的でよい** (原典 Commentary p.8: record profile でも
  topic map でも可)。CMIS プロパティ + 二次索引で満たす余地がある。ただし
  「**エクスポート・migrate・移管のときも属性が記録に結び付いたまま利用可能**」を要求して
  いるので、**エクスポート経路まで設計しないと落ちる**。
- A.2 の「実効的な実装」は**監査証跡による監視**を含む (閲覧を除く全相互作用の記録)。
- ATF Report p.27-28: InterPARES 要求は**自動化手段と手作業の組合せ**を前提にしている。
  → **製品単体で Benchmark を「満たす」とは原文の枠組み上そもそも言えない。**
  表では「製品が寄与しうる部分」と「導入組織の手続に残る部分」を必ず分ける。

**認証制度**: 組織・製品が準拠を名乗る公式な認証/適合性評価の仕組みは**確認できなかった** (「無い」と断定はしない — 下記の未確認事項)。
原典で certify が使われるのは「preserver が記録のコピーを certify する」意味のみ。
ATF Report は他標準 (ISO 15489 / DoD 5015.2 / MoReq) との対応付けでも「条項 X を満たせば
要求 Y をあらゆる点で満たす」という言い方を**意図的に回避**したと明記し、対応は
「一般的な類似」に留まるとしている。IP2 は監査枠組みとして外部の NARA/RLG チェックリスト
(後の TRAC → ISO 16363) を参照させており、自ら認証者として振る舞っていない。
→ **正しい言い方は「Benchmark/Baseline Requirements に対応付けた (mapped to)」
「に依拠して設計した (informed by)」。**「InterPARES 準拠」「InterPARES 認証」は不可
(§5 の禁じ手と一致)。

**未確認**: 「認証を行わない」と明示否認した公式文言は見つかっていない (上記は用法と
外部委譲からの推論)。書籍実物との頁対照も未検証。

### §9-2. `lineage.mode=journaled` の実測 (P0-2)

**環境**: ローカル nb33 (v3.3.1 WAR、CouchDB 3.3.3 / Solr 10 / TEI)、bedroom。

**まず捕獲経路の事実 — これを知らないと計測を誤る**:

- **通常の CMIS 作成経路 (`createDocument` 等) は lineage を一切出さない。** emitter を
  呼ぶのは **ingest 経路** (`CanonicalImportServiceImpl` → `IngestLineageEmitter`) と
  retention / import-export / cloud-drive / archive の各経路だけ。
  `ObjectServiceImpl` / `ContentServiceImpl` に emit は無い (`invalidateLineagePurge` は
  purge 台帳の話で別物)。
- 最初 CMIS 作成で計測して「journaled にしても journal が 1 件も増えない」ことに気づき、
  経路を `POST /api/v1/repo/{repo}/ingest` に変更して初めて実測になった。
  **「モードを有効にした」だけでは何も journal されない。**

**交絡の発覚 (A-B-A)**: disabled → journaled の単純前後比較では **+38%** に見えたが、
同一条件で disabled をもう一度走らせると journaled とほぼ同じ値になった。

| 順序 | モード | mean |
|---|---|---|
| 1 | disabled | 165.7ms |
| 2 | journaled | 228.5ms |
| 3 | **disabled (再)** | **225.0ms** |

→ 差の大半は**リポジトリ肥大による単調ドリフト**。前後比較は使えない。

**交互計測 (8 ラウンド × 25 件 ×2 モード、各 n=200)** — ドリフトを両条件に等しく載せる:

| モード | mean | p50 | p95 |
|---|---|---|---|
| disabled | 268.5ms | 263.6ms | 294.9ms |
| journaled | 301.5ms | 298.7ms | 339.4ms |
| **差** | **+33.0ms (+12.3%)** | +35.1ms | +44.5ms |

**journal 成長率** (ingest 1 件 = journal 1 文書):

| 指標 | 値 | 備考 |
|---|---|---|
| 生 JSON | **約 755 B/event** | 実イベント文書を直接測定 |
| CouchDB `active` | 約 1.0 KB/doc | 圧縮後の実効サイズ |
| CouchDB `file` | **約 15 KB/doc** | 未圧縮の占有。**定期 compaction が要る** |

**未実測**: 10 万規模 / 長期運用時の journal 成長と purge の効き / direct モードとの比較 /
`lineage.targets` を設定した場合 (今回は targets 空 = 投影先なしでの journal 書き込みのみ)。
**この +12.3% は「ingest 経路・targets 空・bedroom 規模」という条件付きの数字**である。

### §9-3. E-ARK 実装調査 (P0-5)

**仕様の現行版** (確認済み): **CSIP 2.2.0 / E-ARK SIP 2.2.0 (ともに 2024-05-17)**。
METS は **1.12** 準拠を要求。2.2.0 以降のリリースは無く約 2 年安定。

**本書の記述の訂正**: これまで「PREMIS 3.0」と書いてきたが、**CSIP 本文が規定しているのは
「PREMIS in METS Guidelines の 2017 年版」に従うこと**であり、「PREMIS 3.0」という版番号での
直接規定は見当たらない (2017 年版ガイドラインが PREMIS 3.0 を前提とする、という関係)。
RODA 側は「PREMIS 3」と明記している。**一次文書で要裏取り。**

**keeps/commons-ip**: LGPL-3.0 / 最新 **2.12.0 (2026-08-14)** / **保守は活発** (直近 1 年で
5 リリース、open issues 6) / Java **17+** (21 で可) / Maven 座標
`org.roda-community:commons-ip2` / CSIP は **2.0.4・2.1.0・2.2.0** に対応。

**配布経路がここの最大の落とし穴**:

- **Maven Central に存在しない** (`a:commons-ip2` → 0 件)。`distributionManagement` は
  **GitHub Packages 単独**で、**公開パッケージでも匿名 GET は 401** (PAT が要る)。
  → `<dependency>` で引くと **fork PR の CI がシークレット不在で必ず落ちる**。
- **回避策**: CLI fat-jar が **GitHub Release アセットとして匿名取得できる**
  (`commons-ip2-cli-2.12.0.jar` / 10,895,225 B、HTTP 200 実測)。
  → **CI 検証は fat-jar を CLI として叩く。** ライブラリとして SIP を「作る」側だけ
  GitHub Packages 認証を使うか、社内リポジトリにミラーする。

**Python 版 eark-validator は CI ゲートに使えない** (重要):

- 最新 1.1.3 (2024-09-11)、Python 3.10+、Apache-2.0。
- **同梱ルールセットは V2.0.4 と V2.1.0 のみで、2.2.0 が無い。**
- CSIP 要件の**偽陽性 issue が 15 件以上** open (CSIP17/63/76/114 等)。
- → 2.2.0 をゲートにするなら **commons-ip 一択**。

**ルールセットの版固定**: 両ツールともルールセットは**ツール本体に同梱**され、独立した
成果物は存在しない → **ツール版を固定すればルールセット版も固定される**。ただし
「どの CSIP 版で検証するか」は実行時引数なので **`--specification-version 2.2.0` を必ず明示**
(既定は 2.1.0 とされ、黙って滑る)。退行検知には `DILCISBoard/eark-ip-test-corpus` を
**コミット SHA 固定**で使う。

**RODA**: 公式ドキュメントが「E-ARK SIP/AIP/DIP と 100% compatible」「保存メタデータは
PREMIS 3」と明記。取込時に SIP 形式 (素のファイル / E-ARK / BagIt) を選ぶ設計。
IP 操作には commons-ip を使用。**未確認**: RODA の特定リリースがどの CSIP 版を受け入れるかの
対応表は取得できていない (同梱 commons-ip 版に依存するはず) — **相互運用の保証には実機受入試験が必要**。

### §9-4. Archivematica 受入 API (P3-4 の前提確認)

**版**: Archivematica **1.18.0** (2025-09-26) / Storage Service **0.24.0** (2025-10-07)。
docs に 1.19 ブランチはあるが tag 未リリース。

**E-ARK SIP の直接取込は不可 — 前提は正しかった**。転送 type は
`standard / zipfile / unzipped bag / zipped bag / dspace / maildir / TRIM / dataverse` の
**8 種のみ** (ソース `PACKAGE_TYPE_STARTING_POINTS` と公式 API リファレンスが一致)。
E-ARK/CSIP に相当するものは無く、未知の type は `ValueError` で拒否される。
→ **BagIt (`zipped bag`) に包む接続層が要る**という §4 の設計判断は裏付けられた。
なお「E-ARK 非対応」と明言した公式ステートメントは無く、上記は**型リストの網羅による消去法**。

**採用すべき API** (旧 `/api/transfer/start_transfer/` は transfer UUID を返さず追加往復が
要るので使わない):

```
POST /api/v2beta/package
  {name, path: base64("<location_uuid>:<絶対パス>"), type:"zipped bag",
   processing_config:"automated", auto_approve:true}
  → 202 {"id": "<transfer UUID>"}
```

認証は `Authorization: ApiKey <user>:<key>`。**Dashboard と Storage Service は別ユーザ空間
= 別 API キー**。Dashboard API は **CSRF 免除**。

**実装で必ず踏む落とし穴** (公式ドキュメント/ソースで確認済み):

1. **202 の `id` は「転送が開始した」ことを保証しない** — status で確認が要る。
2. **`status: COMPLETE` でも `sip_uuid` が無いことがある。** 「COMPLETE **かつ** `sip_uuid` が
   存在 **かつ** `"BACKLOG"` でない」の 3 条件を満たすまで再ポーリングする。
3. **`sip_uuid` がそのまま AIP UUID になる** (`store_aip.py`)。transfer UUID で Storage
   Service を引いてはならない (1 transfer から複数 SIP が生まれ得る)。
4. **AIP チェックサムは `/api/v2/file/{uuid}/` では返らない** — モデルには存在するが
   tastypie の `fields` に含まれていない。**pointer file (METS) の PREMIS
   `messageDigest`** から取る。**ただし pointer file は単一ファイル化された AIP にしか
   存在しない** (非圧縮ディレクトリ AIP では 404) → **受領証に checksum を載せるなら
   processing config で AIP 圧縮を有効にしておく必要がある**。
5. `check_fixity` の `success` は `true/false/null` の 3 値で、**`null` は「開始できなかった」**。
   `false` と混同しない。
6. **API allowlist** が非空だと未登録 IP は 403。接続層のホスト IP 登録が要る。
7. `zipped bag` は **bag 名が転送名になる** (API の `name` は実質無視)。受入形式は
   `.zip / .tgz / .tar.gz` のみ。

**受領証に載せられるもの**: transfer UUID / AIP UUID (= sip_uuid) / `status=="UPLOADED"` /
`stored_date` / size / 保存先パス / fixity 結果と実施時刻 / マイクロサービス粒度のジョブ記録。
**送った SIP 自体の checksum は API から返らない**ので接続層で保持する — ただし bag 検証に
失敗すると転送が FAILED になるため「COMPLETE したこと」自体がマニフェスト一致の証拠になり、
送った bag の `manifest-*.txt` は AIP 内 `metadata/` に保存されるので `extract_file` で回収できる。

**ポーリング回避**: Storage Service の **Service callbacks** (post-store AIP 等) で任意の
REST エンドポイントを叩ける (`<package_uuid>` / `<package_name>` がプレースホルダ置換)。
→ NemakiWare 側に受領 webhook を立てる設計が可能。

**未確認**: `/contents/` が AIP に対して per-file checksum を常に返すか / Enduro と
E-ARK ツールキットの 2026 年時点の状況 (参照資料が 2023 年) / AM 1.19 の API 変更 /
大規模 AIP での `check_fixity` の所要時間。

### §9-5. アンカー / OTS / TSA 実装調査 (P0-3)

#### 段 2 (OpenTimestamps): **Java 実装は事実上メンテ停止。依存の毒性が本当の問題**

| 項目 | 事実 (2026-08-18 実測) |
|---|---|
| リポジトリ | `eternitywall/java-opentimestamps` → `opentimestamps/java-opentimestamps` に移管済 (groupId は歴史的経緯で `com.eternitywall` のまま) |
| master HEAD | **2021-05-05** (5 年 3 ヶ月 停止)。GitHub Releases **0 件** |
| Maven Central | **1.20 / 2021-01-18 が最後**。**Central 上の Java 実装はこの 1 件のみ** (`q=opentimestamps` → 1 件) = 代替ライブラリは存在しない |
| ライセンス | **LGPL-3.0** |
| 傍証 | dependabot PR (json 20190722→20230227) が **2023-04-14 から未マージ** |

**採用の本当の障害は保守状況ではなく推移依存**。`java-opentimestamps:1.20` は
**`org.bitcoinj:bitcoinj-core:0.14.7` (2017 年)** を引き込み、そこから:

| 推移依存 | 既知 CVE | 主なもの |
|---|---|---|
| `com.h2database:h2:1.3.167` | 2 | **CVE-2021-42392 (RCE)** |
| `mysql:mysql-connector-java:5.1.33` | 8 | CVE-2019-2692 ほか |
| `guava:18.0` | 3 | CVE-2018-10237 ほか |
| `protobuf-java:2.6.1` | 3 | CVE-2024-7254 ほか |
| `spongycastle:core:1.51.0.0` | — | 古い BC フォーク。**WAR 内の BC 1.81 と二重化** |
| `slf4j-simple` (直接依存) | — | **ライブラリが binding を持ち込む** |

**bitcoinj は単純に exclude できない**: `OpenTimestamps.java` (メインエントリ) が
`org.bitcoinj.core.{DumpedPrivateKey, ECKey, NetworkParameters}` を直接 import している
(private calendar の ECDSA 署名用)。ブランケット exclusion は `NoClassDefFoundError` を招く。

→ **選択肢は 3 つ**: (a) 採用 + 徹底 exclusion + 自前検証、(b) **stamp/upgrade だけ自前実装**
(`POST /digest` と `GET /timestamp/{commitment}` の 2 本だけ。nonce は `SHA256(D‖random16)`)、
(c) Python `ots` を sidecar 化。**(b)(c) は `.ots` の形式仕様書が存在しない**ことを織り込む
必要がある — org 内に spec は無く、**Python 参照実装が事実上の正典** (Java 版は逐語移植で
Python のコメントがソースに残っている)。

**カレンダーサーバ** (全て HTTP 200 で生存確認):

- `alice/bob.btc.calendar.opentimestamps.org` (Peter Todd) / `a.pool.eternitywall.com`
  (Riccardo Casatta) / `btc.calendar.catallaxy.com` (Bull Bitcoin)
- **無料・アカウント不要・API キー不要**。ただし**利用規約も SLA も無い**。
- サーバ実装に**アプリ層のレート制限は無い** (`MAX_DIGEST_LENGTH = 64` のみ)。
- **Java クライアントは古い URL をハードコード** (`finney.calendar.eternitywall.com` を含む)
  → 使うなら URL は明示指定する。

**nonce の作法 (ロードマップの記述は正しかった)**: Python/Java とも
**ファイルごとに 16 バイトの乱数を append してから SHA-256**。カレンダーに渡るのは
`SHA256(D ‖ nonce16)` で、**元ダイジェスト D は外に出ない**。ただし README が明示する残余
リークは踏襲が要る: 作成時刻は記録される / **トランスポート層のプライバシーは無い**
(IP・タイミングは見える)。

**確定までの時間**: サーバ既定は `--btc-min-confirmations 6` / `--btc-min-tx-interval 6時間`。
カレンダーの実測 tx 平均間隔は **alice 1.13 時間 / catallaxy 8.84 時間**。
→ **アンカー送出から upgrade 可能まで数時間〜半日を「期待値 (SLO)」として設計する**。
**上限ではない** — カレンダーの tx 間隔も Bitcoin の承認時間も確率的で、カレンダー側に SLA も
無い。平均値から上限は導けないので、**タイムアウトと再試行の挙動として設計する**。
upgrade 後は、**`.ots` commitment とブロックヘッダの対応だけ**が、カレンダーにも当社にも
依存せず照合できる。**P4-1 が成立するのはこの照合の範囲に限る** — 捕獲の網羅性・
メタデータの真実性・最初のハッシュの正しさは、これでは担保されない (2026-08-20 訂正)。
**未確認**: カレンダーが pending commitment を保持する期間は実装にもドキュメントにも記載が
無い。**upgrade を無期限に先送りできる保証は無い**と見なすこと。

#### 段 2・段 3 の実装状況と残件 (2026-08-19)

**実装済み**: 段 2 (OpenTimestamps sidecar) / 段 3 (RFC 3161 TSA)。外部レビュー 5 巡を
経ており、その過程で**独立性の主張そのものを撤回**した (§9-5 末尾)。

**残件は 1 件だけで、P1-3 と一体である**:

| 残件 | なぜ単独でやらないか | どこでやるか |
|---|---|---|
| **本番の構築経路が無い** — `AnchorTarget` の実装は Spring から生成されておらず、trust anchor 付きコンストラクタはテストからしか呼ばれない。設定キー (`lineage.anchor.ots.url` / `lineage.anchor.tsa.url` / `.policyOid` / `.accreditation` / `.trustAnchorPem`) も未定義 | **呼び出し元が無い設定キーを先に作っても、読む主体がいない。** アンカーを起動するのは P1-3 の日次アンカー D であり、生成・設定・呼び出しは同じ変更で入れるのが自然 | **P1-3 (証拠台帳の分離 + 日次アンカー)** |

**P1-3 で同時に閉じるもの** (5 巡目レビューで挙がり、アンカー層だけでは閉じないと判断した点):

- `AnchorReceipt.confirmed()` を package-private にしたが、**レシートの生成元を fenced
  sequencer に限定する**のは台帳側の責務
- sidecar への信頼 (偽装 sidecar は `verified=true` を返せる)。Java 側で `.ots` を検証
  できないことが sidecar 採用の理由なので構造的であり、**だからこそ CONFIRMED を
  独立性の主張に使わない**設計にした

**解消済み** (当初「別作業」としたが、先送りできないと判断して実施):

- **中間 CA 経由の TSA** — 当初は「設定した証明書が直接の発行者か」の検査だった。
  商用の認定 TSA はまず中間 CA から発行するので、そのルートを anchor に設定すると
  fail-closed により**全アンカーが FAILED になる地雷**だった。PKIX パス検証に置換し、
  signer → intermediate → root を実際に立てた判別テストで固定した。
- **レポートのモックとの矛盾** — モックが `independentOfOperator` を持ったままで、
  先にマージすると同一リポジトリが矛盾した主張を持つ状態だった。`verificationPerformed`
  に置換済み (PR #503)。

#### 段 3 (RFC 3161 TSA): **実装済み・実機で相互運用を確認 (2026-08-18)**

`Rfc3161AnchorTarget` として実装し、**FreeTSA から実トークンを取得**して相互運用を確認した:

| 項目 | 実測値 |
|---|---|
| status | CONFIRMED |
| genTime | 2026-08-18T11:13:49Z |
| トークン長 | 4,631 バイト |
| policy OID | 1.2.3.4.1 |
| serial | 118394180 |
| **証明書チェーン同梱** | **true** (`certReq=true` が効いている) |
| accuracy | unspecified (FreeTSA は accuracy を入れない — EN 319 422 は必須とするが、これは EU 適格 TSA ではない) |

依存は**追加ゼロ** (BouncyCastle は既に WAR にあった)。版ずれ (prov 1.81 / pkix 1.81.1) は
1.81.1 に統一し enforcer 規則を追加した。**bcjmail には 1.81.1 が存在しない** (BC は
artifact ごとにパッチ版を出す) ためピンから除外している。

以下は実装前に整理した調査結果 (実装済みの現在も設計根拠として有効):



**BouncyCastle は既に WAR に入っている** (実測):

```
WEB-INF/lib/bcprov-jdk18on-1.81.jar
WEB-INF/lib/bcpkix-jdk18on-1.81.1.jar   ← org.bouncycastle.tsp はここ
WEB-INF/lib/bcutil-jdk18on-1.81.1.jar
```

経路は `tika-parsers-standard-package → tika-parser-crypto-module → bcjmail → bcpkix`。
**どの pom にも bouncycastle の宣言は 0 件 = 完全に Tika 経由の推移依存**。
→ **直接使うなら明示宣言しないと Tika の版変更で静かに消える。** さらに
**bcprov 1.81 と bcpkix 1.81.1 で版がずれている** — CLAUDE.md の HttpComponents 5 の教訓
(「ファミリで動く」) と同型のリスクで、prov/pkix/util は揃えるべき。

**BouncyCastle TSP の落とし穴** (bc-java の `main` を直読して確認):

1. **`TimeStampResponse.validate(request)` は拒否応答を素通しする。** status が rejection
   (トークン null) の場合、**例外を投げずに正常復帰**する。`getStatus()` / `getFailInfo()` /
   `getTimeStampToken() == null` を呼び出し側で必ず明示チェックしないと
   **「失敗したのに成功扱い」**になる。
2. **`validate()` は署名検証ではない。** 見るのは nonce 一致 / status / messageImprint /
   SigningCertificate 属性の存在 / reqPolicy 一致まで。CMS 署名検証は
   `TimeStampToken.validate(SignerInformationVerifier)` が別途必要で、**証明書パス構築と
   失効確認はどちらにも含まれない**。3 段階を全部やる。
3. **`certReq` の既定は false** (RFC 3161 §2.4.1)。`setCertReq(true)` を忘れると TSA は
   証明書を入れてこず、**後日の検証で鎖が張れない**。「TSA A では動いて B で壊れる」形で出る。
4. **nonce に `System.currentTimeMillis()` を使う流布したイディオムは誤り** — RFC 3161 は
   64bit 以上の乱数を求める。`SecureRandom` を使う。
5. TSA が DER でなく HTTP エラーページを返すと `TSPException` でなく `IOException` になる
   → `Content-Type: application/timestamp-reply` を確認する。
6. `reqPolicy` を設定すると `validate()` が**ポリシー OID の完全一致を要求**する。事業者の
   OID を正確に知らずに設定すると全件失敗する。

**日本の認定タイムスタンプ** (総務省「時刻認証業務の認定に関する規程」= 令和3年総務省告示
第146号。指定調査機関は日本データ通信協会のみ)。**令和8年3月現在の認定 6 業務**:

| 業務名 | 事業者 |
|---|---|
| セイコータイムスタンプサービス | セイコーソリューションズ |
| **タイムスタンプサービス DiaStamp** (2026-01-14 まで「MIND タイムスタンプサービス」) | 三菱電機デジタルイノベーション |
| アマノタイムスタンプサービス3161 | アマノ |
| 認定タイムスタンプ byGMO | GMO グローバルサイン |
| タイムスタンプサービス iScign | サイエンスパーク |
| ウイングアークタイムスタンプサービス | ウイングアーク1st |

**注意**: MIND → DiaStamp の改称で**検証用電子証明書ファイルも差し替わっている**。
総務省ページが業務ごとに配布する検証用証明書 zip が**唯一の公式な信頼アンカー配布経路**
なので、入手先を固定でハードコードすると壊れる。

**コスト前提は裏付けられた**: 日次アンカー = **1 つの TSA 送付先・1 つのアンカードメイン
あたり月 30 スタンプ** (**P1-3 でドメインをリポジトリ単位にするか全体で 1 本にするかが
未決なので、総数はドメイン数だけ掛かる**) は、アマノの一次見積
(2014 年・月額 ¥8,000 で 1,000 スタンプ/月) にも GMO の月額 1 万円プランにも**余裕で収まる**。
毎時アンカー (月 720) でも同様。ただし **GMO は API 帯域が既定「15 秒/1 スタンプ」**、
セイコー SSL は「最大 1 スタンプ/秒」なので、**バースト設計ではなく定常レート設計**が要る。

**長期検証のために保存すべきもの** (根拠つき):

| 保存物 | 根拠 |
|---|---|
| TSA トークン (DER) | RFC 3161 §2.4.2。ファイル名 **`.tst`** / Content-Type **`application/vnd.etsi.timestamp-token`** (EN 319 422 Annex C) |
| **完全な証明書チェーン** | `certReq=true` で TSU 証明書は同梱されるが、**中間・ルートは同梱される保証が無い** |
| policy OID | EN 319 422 §5.2.2 (policy field shall be present)。**どのポリシーを期待していたかは別途記録が要る** (認定業務であることの主張に直結) |
| **CRL / OCSP 応答 (取得時刻付き)** | **発行直後に取得しないと後追い不能**。FreeTSA が 2026-02 に証明書をローテートした実例あり (旧証明書は `tsa.crt_expired` に退避) |
| accuracy / genTime / nonce / serialNumber | EN 319 422 §5.2.2 は accuracy 必須・最低 1 秒精度 |
| TSA の CP/CPS の版と取得時のコピー | 同じ事業者でもポリシーが複数併存する (アマノは Type-T2 用 Ver2.00 と旧デ協認定 Ver1.16 を併掲) |
| 総務省配布の検証用証明書 zip (取得日付き) | 認定業務の唯一の公式信頼アンカー |

**RFC 4998 ERS の renewal 2 種** — ロードマップ P2-3 の認識は原文と一致していた:

- **Timestamp Renewal**: TSU 秘密鍵の危殆化、または**タイムスタンプ生成に使った**アルゴリズムが
  安全でなくなったとき。**アーカイブ対象データにアクセスせず**既存トークンに被せる。
- **Hash-Tree Renewal**: **ハッシュツリー構築に使った**ハッシュが安全性を失ったとき。
  **元データが要る**。
- RFC 4998 は「アルゴリズムの安全性は out-of-band で監視せよ、本文書の範囲外」と明記
  → **algorithm deprecation registry は仕様が肩代わりしてくれない**。

**版固定の注意**: **ETSI EN 319 422 は 2016 年版が現行だが改訂が必要と評価されており、
後継 TS の目標が 2027-05-31**。P3-1 と同じく版を固定して宣言する方針をここにも適用する。

**未確認 (決定前に潰す 3 点)**: (a) 認定 TSA の**接続認証方式** (クライアント証明書 / IP 制限)
はどの事業者も公開仕様書に無く契約後の接続仕様書でしか分からない、(b) OTS カレンダーの
pending 保持期限、(c) EN 319 422 の後継版の内容。
