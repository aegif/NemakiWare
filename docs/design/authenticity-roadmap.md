# 長期真正性基盤ロードマップ — InterPARES 要求事項への対応と 3.4 以降の計画

2026-08-17 起案。オーナー方針:

> Atlas (Purview) 連携を前提として、InterPARES の要求事項に応えて、コネクタ経由で
> Slack などのビジネスチャットや各種クラウドストレージから取り込まれた共有文書が
> **中長期的に真正性を判断できる状態**をつくるための基礎として NemakiWare を位置づける。

本書は 2 部構成。**§2 = 破壊的なので 3.3 から外したが、やった方がよいこと** (この基盤の
前提工事)、**§3 以降 = InterPARES 準拠ケイパビリティの作り込みとマーケティング**。
3.3.1 (非破壊パッチ) は別文書 [`v3.3.1-plan.md`](v3.3.1-plan.md)。

**本書の現状記述は 2026-08-17 に v3.3.0 のコードで確認したもの** (file を併記)。
計画部分は計画であって約束ではない。着手時に現状を再確認すること。

## §0 前提: 標準規格スタック (2026-08-17 オーナー決定 — E-ARK を含め標準準拠で進める)

独自形式を作らない。各層で既存の標準を採用し、**機械検証できる層では検証を CI に固定する**。

| 層 | 採用する標準 | 準拠を名乗れるか |
|---|---|---|
| 要求事項 | InterPARES benchmark/baseline (A.1〜A.8 / B.1〜B.3) | ✗ 認証制度なし → 対応表 + 根拠の公開まで (§5 禁じ手) |
| 機能モデル・語彙 | OAIS (ISO 14721 — **現行は 2025 年版**。参照時は版を固定)、現用層は ISO 15489 | ✗ 参照モデル → 語彙として採用 (§3.1) |
| メタデータ | **METS** (構造) / **PREMIS** (保存イベント) / Dublin Core (記述) | △ スキーマ妥当性は機械検証可 |
| パッケージング | **E-ARK CSIP / SIP** (DILCIS Board 維持。**版を固定する: CSIP 2.2.0 / PREMIS 3.0 / バリデータとルールセットの版込み** — 版なしの「validator pass」は再現不能)。BagIt (RFC 8493) は Archivematica 接続層の transfer 形式としてのみ使用 — **現行 RFC 8493 は serialization を規定しない**ので「bag の中に IP を封入して搬送」という語り方はしない (外部レビュー指摘) | **◯ 「準拠」を機械検証つきで名乗れる層** — バリデータによるパッケージ単位の適合性検証がある |
| 完全性・時刻 | SHA-256 / RFC 3161 (**TSA プロトコルであって「認定タイムスタンプ」を自動的には意味しない**) / ERS (RFC 4998 = ASN.1/CMS、RFC 6283 = XMLERS — **積層ではなく表現形式の選択肢**) / OpenTimestamps (**デファクトであり正式規格ではない**と常に付記) | ◯ 暗号学的に検証可 |
| 組織認証 | CoreTrustSeal / ISO 16363 | ✗ 対象は運用組織 → 顧客の取得支援 (§3.1) |

この表の含意: **マーケティングで「準拠」と言い切れるのはパッケージング層 (E-ARK) と
暗号層だけ**。他は「対応表を公開する」という形を取る。この非対称を崩さない。

---

## §1 現状資産の棚卸し — 思っているより既に持っている

真正性 (authenticity) を InterPARES の枠組みで言えば **identity (その記録が何であるか
を示す属性) + integrity (完全で改変されていないこと)** であり、それを**保管の連鎖
(chain of custody) の証拠**で支える。この観点で v3.3.0 が既に持つもの:

| 資産 | 実装 | 真正性文脈での意味 |
|---|---|---|
| **チャット・クラウド取込コネクタ** | `rest/ingest/` — `chat/SlackConnectorAdapter` / `TeamsConnectorAdapter` / `MattermostConnectorAdapter`、`note/NotionConnectorAdapter`、`record/SalesforceConnectorAdapter`、IMAP、クラウドドライブ、webhook (HMAC) | 対象文書の入口。ユーザーの構想 (Slack 等からの共有文書) は既に入口がある |
| **取込時コンテンツハッシュ** | `CanonicalImportServiceImpl` が SHA-256 を計算し `nemaki:externalIntegration` aspect の **`nemaki:contentHash`** に保存 (:745,:1505)。再取込時に既存ハッシュと比較 (:1273)。**ただし証拠としては未完**: aspect 付与はコンテンツ作成後の別更新で失敗は warning 止まり (:1315)、型レベルの property definition が無い生プロパティ、空コンテンツは hash なし、対象は content bytes のみ (メタデータ・添付・会話範囲を含まない) | integrity の起点は在る。**「原子的な証拠取得」にするのが P1-1** |
| **来歴属性** | 同 aspect の `nemaki:sourceArchetype` / `sourceSystem` / `sourceObjectType` / `sourceObjectId` / `sourceUrl` (:1478-1486) | identity の一部 (出所・恒久リンク) |
| **チャット文脈メタデータ** | `Patch_ChatContextMetadataSecondaryType` — `nemaki:chatWorkspaceId` / `chatChannelId(Name)` / `chatThreadId` / `chatMessageId` / **`chatParticipants`** / `chatSelectionReason` / `chatEvidenceScope` / `chatCapturedAt` / `chatCaptureWindowStart/End` | identity 属性として出色の**型**。ただし `chatCapturedAt` を設定する取込コードは無く、全プロパティ optional・READWRITE (:82) — A.1 の「分かち難く結合した保護属性」にするには P1-1 での設定 + 更新制約が要る |
| **Lineage journal + 外部カタログ** | `rest/purview/journal/` 一式 — CouchDB 永続イベント (V2)、Atlas sink、カタログ publish/republish/reconciliation、dead letter、historical compensation | chain of custody の記録装置と、**NemakiWare の外にある独立検証点** (Atlas/Purview) |
| **環境同一性の証明** | `LineageWriteVersionBarrier` / `LineageBarrierService` / `LineageBinaryDigest` — 配布物 (WAR) のバイナリダイジェスト + ノード membership ダイジェスト、golden vector で式を凍結 | 「どのソフトウェアがその記録を処理したか」の証拠。InterPARES が求める手続き・システムの文書化に直結 |
| **保持・処分・長期保管** | retention (ACTIVE → ARCHIVED_LOCAL → ARCHIVED_COLD)、S3 Legal Hold、cold storage、削除アーカイブ | ライフサイクル管理と法的保全 |
| **アクセス制御と監査** | CMIS ACL + ACL-epoch fencing (収束保証つき)、audit (READ レベル選択式、WRITE/DELETE/ACL は常時) | 保護手続きとアクセス記録 |
| **バージョニング** | CMIS versioning (checkin/checkout、TCK 準拠) | 改変履歴 |
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
3. 現行の取込 lineage snapshot は `sourceSystem/Archetype/ObjectId` 等のみで
   **contentHash・chat*・取込主体を含まず** (`IngestLineageEmitter:51`)、emit の失敗は
   warn して null を返す非致命経路 (:84)。P1-1 の「journal 化の徹底」の実体はここ。

---

## §2 破壊的なので 3.3 から外したが、やった方がよいこと (3.4 の前提工事)

3.3 のリリース判断で「正しいがリリース直前に入れる変更ではない」と据え置いたもの。
**いずれも真正性基盤の前提**になる — 「静かに欠ける」経路を持つシステムの上に
真正性の主張は築けない。

### 2-1. DAO のエラー握り潰しの総点検 (最優先・他の前提)

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

### 2-2. 残り 16 パッチの unprepared-return → throw 化 (件数は台帳 `v3.3-release-plan.md` の「残る 16 本」に一致させた)

`Patch_SystemFolderSetup` だけ 3.3 で実施済み (オーナー決定)。残りのパッチも
「準備できていないのに正常終了して履歴を焼く」形を持つ。2-1 と同じ思想。
破壊的: 初期化失敗が起動時エラーとして現れるようになる。

### 2-3. lineage.mode の既定を `journaled` へ

- **やること**: Setup Wizard に来歴記録の選択を追加し、**既定 on (journaled)** にする。
  既存環境は現状維持 (conf 保存値が勝つ)。ストレージ増 (journal + 保持期間) を
  ウィザードで明示。
- **なぜ破壊的**: 既定の書き込み量・ストレージ特性が変わる。
- **真正性文脈**: 来歴はあとから遡って作れない。「既定で証拠が残る」が製品の前提になる。
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

- **MD5 レガシー認証経路の廃止予告**: 照合成功時に BCrypt へ自動昇格する仕組みが
  長く動いているので、3.4 で「廃止予告 + 残存 MD5 アカウントの棚卸しコマンド」、
  3.5 で削除。CodeQL の weak-crypto アラートの根本解消。
- **`-Dnemakiware.properties` 幻フラグの削除**: どこからも読まれないフラグが
  CATALINA_OPTS に残っている。消すだけだが「設定が変わった」ように見えるため
  リリースノート必須。
- **`rag.enabled` の焼き込み既定**: wizard が常に永続化するため実害は限定的。
  2-3 と同じタイミングで既定 off に揃える。

---

## §3 InterPARES 要求事項マッピング (作り込みの背骨)

InterPARES (International Research on Permanent Authentic Records in Electronic
Systems) の Authenticity Task Force が定義した **benchmark requirements (作成者側の
真正性推定の根拠 A.1〜A.8)** と **baseline requirements (保存者側の真正な複製の根拠
B.1〜B.3)** を、機能→根拠→検証手順の管理表として採用する。

> **P0 タスク**: 下表の要求事項名は概要の言い換え。**2026-08-17 の外部レビュー (原典
> ip1_authenticity_requirements.pdf と照合) の訂正を反映済み**だが、確定は P0-1 の原典確認
> をもって行う。成果物は**サブ要求 (A.1.a 等) まで展開し、Creator / NemakiWare / Preserver
> の責務を分離した公開マッピング表 + 各行の検証手順** (§5)。「準拠」を名乗る認証制度は無い。

| 要求 (概要の言い換え) | 現状 (v3.3.0) | ギャップ → 計画 (§4) |
|---|---|---|
| A.1 記録の属性と結合の表現 — identity/integrity を示す属性が記録に結び付いて表現される | `nemaki:externalIntegration` + `nemaki:chatContextMetadata` + CMIS プロパティ | 属性の**完全性** (作成者/宛先/日付/行為の型化) と、属性自体の保護 (P1-1, P1-3) |
| A.2 アクセス権限の定義と実施 | CMIS ACL + ACL-epoch (収束保証) + 監査 | ほぼ充足。証明可能な形の整理のみ |
| A.3 喪失・破損からの保護手続き | CouchDB 永続化、削除アーカイブ、バックアップ手順 (runbook) | **fixity 再検証が無い** (P1-2) |
| A.4 媒体・技術の陳腐化への保護手続き | 変換基盤 (LibreOffice/Tika)、cold storage | 保存フォーマット方針と**移行の証跡** (P3-2) |
| A.5 文書形式 (documentary forms) の確立 | タイプシステム + secondary types | チャット/クラウド由来記録の「記録形式」定義 (P1-1) |
| A.6 記録の authentication — **どの記録を・誰が・どの手段で真正と宣言する権限を持つかの規則** | 利用者認証 (BCrypt/OIDC/SAML) は**これではない** (外部レビュー訂正)。該当する仕組みは現状なし | 宣言主体と手段の規則化 + evidence への宣言者記録 (P1-4) + 運用規程テンプレート。時刻証明 (P2 系) は**補助証拠**。電子署名は現時点で計画外と明記 |
| A.7 authoritative record の特定 — 複数コピーがあるとき**どれが権威かを決める手続・責任部署・記録クラスとの結合** (目的別に複数あり得る) | バージョニング + latest 概念 (**それだけでは不足** — 外部レビュー訂正) | 識別手続の規則化 + P1-4 での表現 + 非権威スタブの明示マーク (§4 Phase 3) |
| A.8 除去・移転 — **真正性判断に必要な関連文書を記録と一緒に移す手続** | retention/処分、lineage | **主対応は P3-1/P3-4** (evidence を SIP に同梱して移す)。P3-3 (処分証跡) はその一部 (外部レビュー訂正) |
| B.1 移転・維持・複製の管理 | lineage journal + Atlas 照合 | journal の改竄検知 (P1-3)、SIP (P3-1)。**B 系は保存者側の最低条件で全項目が必要** — 移管後は移管先の責務。NemakiWare が軽量 Archive を務める範囲でのみ自ら負う (責務境界表を P0-1 で確定) |
| B.2 複製過程とその影響の文書化 | rendition はあるが証跡なし | 変換イベントの記録 (P3-2)。**hash だけでは不足** — 日時・責任者・取得記録との関係・形式/内容/アクセス性/利用への影響・不完全な複製の開示まで (外部レビュー訂正) |
| B.3 アーカイブ記述 | CMIS メタデータ + Atlas カタログ (**単票中心で、fonds の階層的 archival description は無い**) | エクスポート時の記述 (P3-1)。階層記述は移管先の記述体系に委ねるか自前で持つかを P0-1 で整理 |

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

**認証の但し書き**: 認証制度は InterPARES には無いが OAIS 系には在る —
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
| **P1-1** | **Capture Provenance の原子化** | 「必ず刻む」を設計として実装する: (a) content commit と evidence commit の**トランザクション境界** (outbox パターン — 現状は aspect 付与が後追い更新で失敗 warning、emit は握り潰し `IngestLineageEmitter:84`。2-1 の fail-fast と同族)。(b) snapshot に contentHash・chat*・取込主体 (`ingestedBy` — **新規項目**) を追加 (`:51` に現状無し)。(c) `chatCapturedAt` 等を取込コードが実際に設定し、**更新制約** (READWRITE をやめる) を掛けて A.1 の保護属性にする。(d) 空コンテンツ・version ごとの hash・メタデータ hash の扱いを evidence data model として定義。(e) 失敗時は隔離 + 再構築可能性 |
| **P1-2** | **Fixity service** | leader-gated の定期ジョブ (既存スケジューラパターン) が保存コンテンツの SHA-256 を再計算し `nemaki:contentHash` と照合。結果を journal に記録、乖離は隔離 + アラート。運用 API は再索引の verdict 型を踏襲 (`COMPLETE` の意味論の教訓をそのまま適用: 「検証した範囲」を常に言う)。**対象は cold 層 (S3) を含む** — 詳細は Phase 3 前提モデルの原則 3 |
| **P1-3** | **Tamper-evident evidence ledger** | **journal と evidence を分離する** (外部レビュー + purge 衝突の帰結): 配送用 journal は現行どおり purge 可 (`lineage.retention.days`)、**evidence ledger は immutable** で期間・法的根拠別に保持し、purge 境界ごとに checkpoint + 外部アンカー + inclusion proof を残す。連鎖の構築は素朴な LeaderElection 流用ではなく、**既存の fenced sequencer (`CouchLineageSequencingStore` — lease generation CAS 持ち) を土台**に: chain domain (repo 単位か全体か) / sequence と連鎖の同一 CAS 確定 / failover 時の fork 検出 / **unsequenced backlog がある間の anchor 禁止** / purge 後 genesis / 「順序 = 確定 sequence 順であって時計順ではない」の明記。但し書き: 連鎖が固定するのは記録された順序 (P1-1 が先)、アンカー以前しか凍結されない (頻度 = 書き直され得る窓) |
| **P1-4** | **真正性レポート (evidence package)** | 文書 1 件について identity 属性・contentHash と fixity 履歴・custody チェーン (journal 抜粋)・アクセス監査・バージョン系譜・処理環境 (Barrier ダイジェスト) を 1 つの JSON + 人が読む PDF に集約する API/UI。**マーケの主砲** (§5) |

### Phase 2 — 信頼できる時刻 (3.4)

**前提となる信頼のはしご** (2026-08-17 オーナー議論より)。時刻の第三者証明は原理的に
運用者の外の証人を要するが、「外部 = 有償契約」ではない。アンカー先をプラガブルにし、
顧客が段を選べる形にする:

| 段 | アンカー先 | 外部依存 | 費用 | 証明できること |
|---|---|---|---|---|
| 0 | ハッシュ連鎖のみ | なし | 0 | 内部の一貫性・記録順序 |
| 1 | + Atlas/Purview (P1-3) | なし (顧客自身の別システム) | 0 | 単一システム管理者の事後改竄の検知 |
| 2 | + **OpenTimestamps** (Bitcoin へのコミットメント集約) | あり・契約不要 | 0 | 組織外に対する「遅くとも時刻 T に存在」の第三者検証可能な証明 |
| 3 | + RFC 3161 TSA (**認定 TSA を選んだ場合に**日本の制度上の裏付け — プロトコル自体は認定を意味しない) | あり・有償 | 僅少 (下記) | 制度上の裏付け + 細かい時刻粒度 |

**コスト設計の要**: ハッシュ連鎖があるため、タイムスタンプは文書ごとではなく
**連鎖のアンカーに 1 日 1 回**で全文書に継承される (時刻粒度は「その日中」。細かくする
なら毎時)。認定 TSA でも月 30 スタンプ程度で全リポジトリに効く。

| ID | 何を | 具体 |
|---|---|---|
| **P2-0** | **アンカー先のプラガブル化** | P1-3 の日次アンカー D の送出先を多重化: Atlas (段 1、既存 sink) / OpenTimestamps (段 2) / RFC 3161 TSA (段 3)。段ごとに独立に有効化 |
| **P2-1** | **OpenTimestamps アンカー** | D をカレンダーサーバへ送信 (HTTP POST のみ、鍵・ウォレット・暗号資産保有なし)。**公式クライアントは nonce 付き commitment を送る** — 生の D すら外に出ない (privacy 特性として明記・踏襲する)。**証明は二段階** — 送信直後は pending、Bitcoin ブロック確定後 (**数時間かかり得る**) にジョブが `.ots` を upgrade (dead-letter/リトライの既存パターン)。複数カレンダー併用。検証は `.ots` + 信頼できる Bitcoin ブロックヘッダ列で**当社にもカレンダーにも依存せず**可能 → P4-1 と直結。証明の意味は「**そのブロック時刻までに存在した**」という上限側の存在証明であり、対称な誤差幅の時刻証明ではない |
| **P2-2** | RFC 3161 タイムスタンプ (段 3) | 日次アンカー + 必要ならアーカイブ遷移時に TSA トークンを取得し保存。認定 TSA / フリー TSA をプラガブルに。**TSA policy OID・証明書/失効情報 (CRL/OCSP)・nonce・accuracy の保存**まで含めて「検証可能なトークン」とする (長期検証情報は P2-3) |
| **P2-3** | 長期有効性 | **timestamp renewal と hash-tree renewal は発火条件が異なる別操作** — 「再タイムスタンプ」の一語で潰さない。ERS は RFC 4998 (ASN.1/CMS) と RFC 6283 (XMLERS) が**表現形式の選択肢**で、採用可否と形式を設計判断として比較。`.ots`・TSA トークンは SIP (P3-1) に同梱して保全 |

**採らないもの**: Ethereum 系 (ガス代が発生)・プライベート/コンソーシアムチェーン
(信頼の依存先がコンソーシアムに戻り、アンカーの目的を壊す)。

### Phase 3 — 保存パッケージと移行 (3.4)

| ID | 何を | 具体 |
|---|---|---|
| **P3-1** | **E-ARK SIP エクスポート** (Producer の一次成果物) | **E-ARK SIP (CSIP 2.2.0 準拠: METS 構造記述 + PREMIS 3.0)** — 使用する仕様版・profile・バリデータとルールセットの**版を固定して宣言**する。journal イベント → PREMIS イベント (クロスウォーク表で語彙を確定)、チャット文脈 → 記述メタデータ。evidence package (`.ots`・TSA トークン含む) は CSIP 規約に従う置き場所に同梱 (正位置は着手時に要確認)。既存 `ImportExportResource` を土台に。**出力はバリデータ通過を CI テストとして固定** (§6)。AIP/DIP の生成は「軽量 Archive」責務を定義してから別途判断 (§3.1)。BagIt は **Archivematica 接続層の transfer 形式としてのみ** (P3-4) |
| **P3-2** | 保存フォーマット複製の証跡化 | PDF/A 変換を「複製イベント」として journal に記録 — B.2 の要求どおり **hash だけでなく日時・責任者・取得記録との関係・影響・不完全性の開示**まで。**現行 jodconverter は PDF/A profile の指定・検証を持たない** (rendition 基盤のみ) — PDF/A 出力と検証 (veraPDF 等) は新規要素。**これは利便コピーであって保存計画の代替ではない** (下記の非目標) |
| **P3-3** | 処分証跡 | retention による削除を disposition イベントとして journal + Atlas に残す (何を・いつ・どの規則で) |
| **P3-4** | **保存システムへの移管 (custody transfer プロトコル)** | 「双方向参照」は時系列で成立させる — **SIP 作成時点で先方 AIP ID は存在しない**ので、SIP には連鎖抜粋を入れ、先方の受領・AIP 生成**後**に受領証を journal へ追記して次アンカーで凍結する。状態機械で管理: `PACKAGE_CREATED → SENT → RECEIVED → VALIDATED → INGEST_ACCEPTED → AIP_CREATED → RECEIPT_VERIFIED → CUSTODY_TRANSFERRED → LOCAL_DISPOSITION`。受領証の中身は AIP checksum だけでなく **署名付き受領・submission ID・AIP ID・対象 SIP digest・検証結果・先方 agent** まで。失敗・再送・重複取込・部分受入・先方 AIP 再生成の扱いを submission agreement として明文化。**RODA は E-ARK 対応** (受入 profile/版の互換は要確認)、**Archivematica は BagIt transfer 対応が確認済み** — E-ARK 直接取込の対応度は着手時に要確認、不足なら接続層で変換 (一次形式は変えない) |

#### Phase 3 の前提モデル: リテンション終端の 3 つの出口 (2026-08-17 オーナー議論)

現行の retention (ACTIVE → ARCHIVED_LOCAL → ARCHIVED_COLD、S3 + Legal Hold) は
**保管層の移動**であり、custody (管理責任) は NemakiWare に残る。Archivematica 移管は
**custody の移転**であり、両者は同じ「アーカイブ」という語でも別物。モデルは
「cold の先に移管」ではなく、**スケジュール終端の 3 択**:

| 出口 | いつ | custody |
|---|---|---|
| 継続保管 (cold 層、実装済み) | 法定保存期間中・低頻度アクセス・legal hold | NemakiWare のまま |
| アーカイブ移管 (P3-4) | 永年/歴史的価値、**保存期間がシステム寿命を超えるもの**、組織的分離 | 移転 |
| 廃棄 (P3-3) | 期間満了・保存価値なし | 消滅 (証跡は残る) |

- **選別 (appraisal)**: どの記録を移管するかをリテンション規則の属性として持つ (P3-4 の設計要素)
- **移管後の NemakiWare 側**: 削除 + 処分証跡、または**非権威スタブ** (メタデータ +
  AIP 参照 + evidence package を残し検索可能性を保つ。A.7 のため「非権威」を明示マーク)。
  推奨はスタブ
- **用語の言い分け (実害あり)**: 現機能名 ARCHIVED_* と OAIS の Archive が衝突する。
  文書と UI では「保管層 (storage tier、custody 不変)」と「アーカイブ移管 (custody
  transfer)」を言い分けること

#### 移管時のリネージ 3 原則

1. **journal は移管で出ていかない** — 追記専用でローカルに残る。SIP に入るのは
   **抜粋の複製** (当該文書の capture〜移管直前のイベント列を PREMIS に変換 +
   連鎖の該当区間 + アンカー証明)
2. **移管イベントが双方向の継ぎ目** — journal に「対象 + contentHash + 根拠規程 +
   承認者 + 宛先」を刻み、取込完了後に**先方 AIP 識別子と AIP チェックサムを追記**。
   次のアンカーで凍結されると、向こうのパッケージにこちらの連鎖が入り、こちらの
   連鎖に向こうの指紋が入る — 検証者は custody 境界をまたいで歩ける
3. **cold 層は custody 不変の内部イベント** — 移動先・移動前後の hash 照合・legal hold
   適用を同じ journal に刻む。**現行の cold move は移動前後の hash 照合を実装していない**
   (`RetentionScheduler:567` / `S3StorageAdapter:74` — S3 put + Legal Hold + 状態遷移のみ)。
   **fixity service (P1-2) は cold 層も対象** (S3 の multipart ETag は MD5 ではないため自前
   SHA-256 照合。S3 の checksum-sha256 メタデータ活用が設計点)。「遮断した層こそ誰も
   見ていないのだから fixity が要る」

**Atlas 側**: 移管した記録のエンティティは削除せず「移管済み (AIP=X)」状態へ遷移。
カタログは custody 境界をまたぐ横断地図として残る。**ただし独立性の限界を明記する**:
Atlas/Purview が同一組織・同一 tenant の管理下なら「管理者でも書き換えられない」は
成立しない — その主張が立つのは段 2 (組織外アンカー) 以上 (外部レビュー指摘。§5 の
文言もこの条件付けに従う)。外部アンカーの threat model (変更権限・anchor gap・fork) は
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
ニッチな蓄積の劣化コピーになる。NemakiWare の価値は「移管の瞬間まで証拠の連鎖を
切らさない Producer」であること。

**実装ノート (E-ARK パッケージ生成)**: RODA エコシステムの Java ライブラリ
**keeps/commons-ip** (CSIP の生成 + 検証) が第一候補。ただし **Maven Central には無い**
(2026-08-17 に検索して確認 — 出てくるのは無関係の同名ライブラリのみ)。GitHub 配布のため
入手経路 (GitHub Packages / self-build — OpenCMIS で確立済みのパターン) とライセンス・
成熟度の確認が P0。不採用の場合の代替は METS/PREMIS の直接生成 (JAXB — SOAP 経路で
保守している JAXB 資産がそのまま効く)。いずれでも**公式/参照バリデータでの検証を CI に
固定する**方針は変わらない。

### Phase 4 — 第三者検証 (継続)

| ID | 何を | 具体 |
|---|---|---|
| **P4-1** | **ベンダー非依存の検証 CLI** | evidence package + Atlas 参照だけで、NemakiWare 無しにハッシュ連鎖・タイムスタンプ・fixity 履歴を検証できる公開ツール。「私たちを信用しなくても検証できる」がメッセージになる |
| **P4-2** | 制度への接続 | ISO 16363 (信頼できるデジタルリポジトリ) セルフアセスメントの公開。日本市場向けに JIIMA 認証 (電帳法) の取得検討。※いずれも要件調査から |

---

## §5 マーケティングメッセージの設計

**原則: この製品文化のまま外に出す。** 3.3 のリリース台帳がそうだったように、
**すべての主張に検証手順を添える**。これは制約ではなく差別化 — 「真正性」を扱う
製品が根拠なしの形容詞を使ったら、その一点で信用を失う。

### 言えるようになる文言 (Phase 到達ごと)

- **Phase 1**: 「Slack・Teams・クラウドストレージから取り込んだ瞬間に、SHA-256 指紋・
  取込文脈 (誰が・どのチャンネルで・どの範囲を)・処理環境まで記録。**ワンクリックで
  真正性レポート**」
- **P1-3**: 「来歴はハッシュ連鎖の evidence ledger + 外部カタログ (Microsoft Purview /
  Apache Atlas) への日次アンカー」— **「管理者でも書き換えられない」と言うのは段 2
  (組織外アンカー) を有効にした構成についてのみ** (同一 tenant の Atlas だけでは
  成立しない — この条件ごと公開する)
- **Phase 2**: 「組織外への存在証明は**無償構成 (OpenTimestamps) から**。認定 TSA を
  足せば制度上の裏付けと細かい時刻粒度まで」
- **Phase 3**: 「**Slack での共有からアーカイブ移管まで、証拠の連鎖が一度も切れない**。
  RODA / Archivematica 等の保存システムへ、捕獲文脈・fixity 履歴・アンカー証明を携えた
  SIP を渡す」「パッケージは **E-ARK (CSIP 2.2.0) 準拠 — 版を固定したバリデータの機械
  検証つき**。この層だけは『準拠』を根拠付きで言い切れる (§0 の非対称)」
- **Phase 4**: 「**NemakiWare を信用しなくても検証できる** — 公開 CLI と外部カタログの
  二系統照合」
- 通奏低音: 「InterPARES の真正性要求事項への**対応マッピングと検証手順を公開**」
  「OAIS の語彙 (SIP・PDI) で説明可能。CoreTrustSeal 等を目指す組織の技術要件に
  対応表で応える」

### 禁じ手

- 「InterPARES **準拠**」— 準拠を認定する制度は無い。言えるのは「要求事項に対する
  対応表と根拠の公開」まで。
- 「OAIS **認証**」「ISO 16363 **認証済み製品**」— 認証対象は運用組織であって
  ソフトウェアではない (§3.1)。
- 「電帳法**対応**」— JIIMA 認証を取るまでは「電帳法の保存要件を意識した設計」まで。
  **これは自主規制であって、JIIMA 認証が法的必須という意味ではない** (外部レビュー指摘)。
  電子取引データの真実性確保にはタイムスタンプ以外の経路 (訂正削除不能/履歴保全システム、
  事務処理規程) があり、**電子取引とスキャナ保存の要件を混同しない** — 文言確定前に
  国税庁の現行一問一答で経路ごとに確認する (P0)。
- 検証手順を添えられない主張は出さない。

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
- マッピング表 (§3) の各行は「機能がある」ではなく「この手順で確認できる」で閉じる。
- **E-ARK 出力は公式/参照バリデータの通過を CI テストとして固定** (P3-1)。
  「準拠」を名乗る層は、名乗りの根拠が毎ビルド機械検証される状態を保つ。

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

1. **InterPARES 原典の条文確認** — §3 の表を**サブ要求 (A.1.a 等) まで展開し、
   Creator / NemakiWare / Preserver の責務境界列を付けて**確定 (最初の 1 週間の仕事)
2. **`lineage.mode=journaled` の実測** — 書き込みオーバーヘッドと journal 成長率
   (bedroom 規模 + 10 万規模)。2-3 と P1-1 のコスト根拠
3. **アンカー実装調査** — OpenTimestamps の Java クライアント
   (`com.eternitywall:java-opentimestamps` — **Central に 1.20 まで存在することは確認済み**、
   残るは保守状況)。nonce 付き commitment の踏襲。認定 TSA / フリー TSA の候補・コスト・
   可用性、TSA policy OID / 失効情報の保存設計
4. **P1-4 のモック** — 真正性レポートの見た目 (JSON スキーマ + PDF 1 枚) を先に作り、
   オーナーとマーケ観点でレビューしてから実装に入る
5. **E-ARK 実装調査** — keeps/commons-ip の入手経路・ライセンス・成熟度
   (Central に無いことは確認済み)。**CSIP 2.2.0 / E-ARK SIP profile / PREMIS 3.0 /
   バリデータとルールセットの版を固定**して宣言する形を決める
6. **消去要求との調停の法務確認** — 個人情報保護と保存義務の優先順位、電帳法の
   経路別要件 (電子取引 / スキャナ保存) を国税庁一問一答で確定

### 実装前に §に起こす設計課題 (外部レビュー 2026-08-17 より)

- **Slack 等の source-side 捕獲完全性**: API scope・編集/削除履歴・thread/添付・cursor gap・
  取得 request ID。**webhook HMAC は通信相手の検証であって Slack 内の記録内容の真正性
  証明ではない** — 「NemakiWare が証明するのは取込時点以降」という境界を evidence に明記
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
