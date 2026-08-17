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

---

## §1 現状資産の棚卸し — 思っているより既に持っている

真正性 (authenticity) を InterPARES の枠組みで言えば **identity (その記録が何であるか
を示す属性) + integrity (完全で改変されていないこと)** であり、それを**保管の連鎖
(chain of custody) の証拠**で支える。この観点で v3.3.0 が既に持つもの:

| 資産 | 実装 | 真正性文脈での意味 |
|---|---|---|
| **チャット・クラウド取込コネクタ** | `rest/ingest/` — `chat/SlackConnectorAdapter` / `TeamsConnectorAdapter` / `MattermostConnectorAdapter`、`note/NotionConnectorAdapter`、`record/SalesforceConnectorAdapter`、IMAP、クラウドドライブ、webhook (HMAC) | 対象文書の入口。ユーザーの構想 (Slack 等からの共有文書) は既に入口がある |
| **取込時コンテンツハッシュ** | `CanonicalImportServiceImpl` が SHA-256 を計算し `nemaki:externalIntegration` aspect の **`nemaki:contentHash`** に保存 (:745,:1505)。再取込時に既存ハッシュと比較 (:1273) | integrity の起点。**取り込んだ瞬間の指紋が既に残っている** |
| **来歴属性** | 同 aspect の `nemaki:sourceArchetype` / `sourceSystem` / `sourceObjectType` / `sourceObjectId` / `sourceUrl` (:1478-1486) | identity の一部 (出所・恒久リンク) |
| **チャット文脈メタデータ** | `Patch_ChatContextMetadataSecondaryType` — `nemaki:chatWorkspaceId` / `chatChannelId(Name)` / `chatThreadId` / `chatMessageId` / **`chatParticipants`** / `chatSelectionReason` / `chatEvidenceScope` / `chatCapturedAt` / `chatCaptureWindowStart/End` | チャット由来記録の identity 属性として出色。**「誰が・どの文脈で・なぜこの範囲を」まで既に型がある** |
| **Lineage journal + 外部カタログ** | `rest/purview/journal/` 一式 — CouchDB 永続イベント (V2)、Atlas sink、カタログ publish/republish/reconciliation、dead letter、historical compensation | chain of custody の記録装置と、**NemakiWare の外にある独立検証点** (Atlas/Purview) |
| **環境同一性の証明** | `LineageBarrier` — 配布物 (WAR) のバイナリダイジェスト + ノード membership ダイジェスト、golden vector で式を凍結 | 「どのソフトウェアがその記録を処理したか」の証拠。InterPARES が求める手続き・システムの文書化に直結 |
| **保持・処分・長期保管** | retention (ACTIVE → ARCHIVED_LOCAL → ARCHIVED_COLD)、S3 Legal Hold、cold storage、削除アーカイブ | ライフサイクル管理と法的保全 |
| **アクセス制御と監査** | CMIS ACL + ACL-epoch fencing (収束保証つき)、audit (READ レベル選択式、WRITE/DELETE/ACL は常時) | 保護手続きとアクセス記録 |
| **バージョニング** | CMIS versioning (checkin/checkout、TCK 準拠) | 改変履歴 |
| **変換基盤** | jodconverter (LibreOffice)、Tika | 保存フォーマット変換 (PDF/A 化) の土台 |

**確認済みの不在 (= 作るもの)**: RFC 3161 タイムスタンプ (`rfc3161|tsa` で 0 件)、
BagIt / OAIS 型パッケージ (`bagit|oais` で 0 件)、定期 fixity 再検証ジョブ、journal の
改竄検知 (エントリ連鎖)、真正性レポート、InterPARES へのマッピング (`interpares` で 0 件)。

**重要な現状**: `lineage.mode` の既定は **`disabled`** (`IntegrationSettingsController:129`)。
真正性基盤としては「来歴を記録しない既定」は成立しないが、既定変更は破壊的なので §2 へ。

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

### 2-2. 残り 17 パッチの unprepared-return → throw 化

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

> **P0 タスク**: 下表の要求事項名は概要の言い換えであり、**原典 (InterPARES 1
> Authenticity Task Force 最終報告、および InterPARES Trust の後続成果) の条文確認が
> 最初の作業**。マッピング表 v1 はこの確認をもって確定する。「準拠」を名乗る認証制度は
> 無いので、成果物は**公開マッピング表 + 各行の検証手順**という形を取る (§5)。

| 要求 (概要の言い換え) | 現状 (v3.3.0) | ギャップ → 計画 (§4) |
|---|---|---|
| A.1 記録の属性と結合の表現 — identity/integrity を示す属性が記録に結び付いて表現される | `nemaki:externalIntegration` + `nemaki:chatContextMetadata` + CMIS プロパティ | 属性の**完全性** (作成者/宛先/日付/行為の型化) と、属性自体の保護 (P1-1, P1-3) |
| A.2 アクセス権限の定義と実施 | CMIS ACL + ACL-epoch (収束保証) + 監査 | ほぼ充足。証明可能な形の整理のみ |
| A.3 喪失・破損からの保護手続き | CouchDB 永続化、削除アーカイブ、バックアップ手順 (runbook) | **fixity 再検証が無い** (P1-2) |
| A.4 媒体・技術の陳腐化への保護手続き | 変換基盤 (LibreOffice/Tika)、cold storage | 保存フォーマット方針と**移行の証跡** (P3-2) |
| A.5 文書形式 (documentary forms) の確立 | タイプシステム + secondary types | チャット/クラウド由来記録の「記録形式」定義 (P1-1) |
| A.6 認証 (authentication) の手段 | 認証基盤 (BCrypt/OIDC/SAML)、HMAC webhook | 記録**そのもの**への時刻証明・署名 (P2-1, P2-2) |
| A.7 権威ある記録の特定 | バージョニング + latest 概念 | 「authoritative copy」の明示 (P1-4 レポートで表現) |
| A.8 除去・移転の文書化 | retention/処分、lineage | **処分証跡** (P3-3) |
| B.1 移転・維持・複製の管理 | lineage journal + Atlas 照合 | journal の改竄検知 (P1-3)、AIP/DIP (P3-1) |
| B.2 複製過程とその影響の文書化 | rendition はあるが証跡なし | 変換 = 複製イベントの記録 (P3-2) |
| B.3 アーカイブ記述 | CMIS メタデータ + Atlas カタログ | エクスポート時の記述パッケージ (P3-1) |

---

## §4 ケイパビリティ・ロードマップ

### Phase 1 — 証拠チェーンの成立 (3.4 と並走可、破壊なしで開始可能)

| ID | 何を | 具体 |
|---|---|---|
| **P1-1** | **Capture Provenance の完成** | 取込イベントを lineage journal に必ず刻む (contentHash・source*・chat* を含む capture イベント)。取込主体 (`ingestedBy`) と取込時刻を全コネクタで統一。既存: hash と属性は在る — **欠けているのは「取込」という出来事の journal 化の徹底** |
| **P1-2** | **Fixity service** | leader-gated の定期ジョブ (既存スケジューラパターン) が保存コンテンツの SHA-256 を再計算し `nemaki:contentHash` と照合。結果を journal に記録、乖離は隔離 + アラート。運用 API は再索引の verdict 型を踏襲 (`COMPLETE` の意味論の教訓をそのまま適用: 「検証した範囲」を常に言う) |
| **P1-3** | **Tamper-evident journal** | journal エントリをハッシュ連鎖化 (prev-hash)。日次アンカーダイジェストを **Atlas/Purview に publish** — 外部カタログが独立アンカーになり、「NemakiWare 単独では改竄を隠せない」構造を作る。`LineageBarrier` のダイジェスト機構と golden vector 文化を流用。**設計上の但し書き 3 点**: (1) 連鎖が固定するのは「記録された順序」— だから P1-1 (書き込み経路で必ず刻む) が先。(2) アンカー以前しか凍結されない — アンカー頻度 = 書き直され得る窓の長さ。(3) multi-replica では連鎖の構築を leader 固定の単一書き手にする (LeaderElection の既存パターン) |
| **P1-4** | **真正性レポート (evidence package)** | 文書 1 件について identity 属性・contentHash と fixity 履歴・custody チェーン (journal 抜粋)・アクセス監査・バージョン系譜・処理環境 (Barrier ダイジェスト) を 1 つの JSON + 人が読む PDF に集約する API/UI。**マーケの主砲** (§5) |

### Phase 2 — 信頼できる時刻 (3.5 候補)

**前提となる信頼のはしご** (2026-08-17 オーナー議論より)。時刻の第三者証明は原理的に
運用者の外の証人を要するが、「外部 = 有償契約」ではない。アンカー先をプラガブルにし、
顧客が段を選べる形にする:

| 段 | アンカー先 | 外部依存 | 費用 | 証明できること |
|---|---|---|---|---|
| 0 | ハッシュ連鎖のみ | なし | 0 | 内部の一貫性・記録順序 |
| 1 | + Atlas/Purview (P1-3) | なし (顧客自身の別システム) | 0 | 単一システム管理者の事後改竄の検知 |
| 2 | + **OpenTimestamps** (Bitcoin へのコミットメント集約) | あり・契約不要 | 0 | 組織外に対する「遅くとも時刻 T に存在」の第三者検証可能な証明 |
| 3 | + 認定タイムスタンプ (RFC 3161) | あり・有償 | 僅少 (下記) | 日本の制度上の裏付け |

**コスト設計の要**: ハッシュ連鎖があるため、タイムスタンプは文書ごとではなく
**連鎖のアンカーに 1 日 1 回**で全文書に継承される (時刻粒度は「その日中」。細かくする
なら毎時)。認定 TSA でも月 30 スタンプ程度で全リポジトリに効く。

| ID | 何を | 具体 |
|---|---|---|
| **P2-0** | **アンカー先のプラガブル化** | P1-3 の日次アンカー D の送出先を多重化: Atlas (段 1、既存 sink) / OpenTimestamps (段 2) / RFC 3161 TSA (段 3)。段ごとに独立に有効化 |
| **P2-1** | **OpenTimestamps アンカー** | D をカレンダーサーバへ送信 (HTTP POST のみ、鍵・ウォレット・暗号資産保有なし。外に出るのは 32 バイトのハッシュだけ)。**証明は二段階** — 送信直後は pending、Bitcoin ブロック確定後にジョブが `.ots` を upgrade (dead-letter/リトライの既存パターン)。複数カレンダー併用。検証は `.ots` + ブロックヘッダ列だけで**当社にもカレンダーにも依存せず**可能 → P4-1 と直結。時刻粒度は ±1〜2 時間 |
| **P2-2** | RFC 3161 タイムスタンプ (段 3) | 日次アンカー + 必要ならアーカイブ遷移時に TSA トークンを取得し保存。認定 TSA / フリー TSA をプラガブルに。細かい時刻粒度が要る要件はこちら |
| **P2-3** | 長期有効性 (再タイムスタンプ) | アルゴリズム失効前の積層再スタンプ運用。ERS (RFC 4998/6283) 採用可否を設計判断として比較。`.ots` は AIP (P3-1) に同梱して保全 |

**採らないもの**: Ethereum 系 (ガス代が発生)・プライベート/コンソーシアムチェーン
(信頼の依存先がコンソーシアムに戻り、アンカーの目的を壊す)。

### Phase 3 — 保存パッケージと移行 (3.5〜)

| ID | 何を | 具体 |
|---|---|---|
| **P3-1** | AIP/DIP エクスポート | BagIt (RFC 8493) パッケージ: manifest-sha256 + 来歴・記述メタデータ + evidence package 同梱。監査人・アーカイブ機関・後継システムへの引き渡し形式。既存 `ImportExportResource` を土台に |
| **P3-2** | 保存フォーマット複製の証跡化 | PDF/A 変換 (jodconverter) を「複製イベント」として journal に記録 (元 hash → 複製 hash、変換環境 = Barrier ダイジェスト)。B.2 対応 |
| **P3-3** | 処分証跡 | retention による削除を disposition イベントとして journal + Atlas に残す (何を・いつ・どの規則で) |

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
- **P1-3**: 「来歴ジャーナルはハッシュ連鎖 + **外部カタログ (Microsoft Purview / Apache
  Atlas) への日次アンカー**。管理者でも過去を静かに書き換えられない構造」
- **Phase 2**: 「RFC 3161 タイムスタンプで時刻を第三者証明」
- **Phase 4**: 「**NemakiWare を信用しなくても検証できる** — 公開 CLI と外部カタログの
  二系統照合」
- 通奏低音: 「InterPARES の真正性要求事項への**対応マッピングと検証手順を公開**」

### 禁じ手

- 「InterPARES **準拠**」— 準拠を認定する制度は無い。言えるのは「要求事項に対する
  対応表と根拠の公開」まで。
- 「電帳法**対応**」— JIIMA 認証を取るまでは「電帳法の保存要件を意識した設計」まで。
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

## §7 バージョン割当の提案 (オーナー決定事項)

| バージョン | 中身 |
|---|---|
| **3.3.1** | 非破壊パッチ ([`v3.3.1-plan.md`](v3.3.1-plan.md)) |
| **3.4** | §2 前提工事 (DAO fail-fast、17 パッチ、lineage 既定) + **Phase 1** |
| **3.5** | Phase 2〜3 (時刻証明・パッケージ) |
| 継続 | Phase 4 (検証 CLI・制度) |

## §8 直近アクション (P0)

1. **InterPARES 原典の条文確認** — §3 の表を原典の文言で確定 (最初の 1 週間の仕事)
2. **`lineage.mode=journaled` の実測** — 書き込みオーバーヘッドと journal 成長率
   (bedroom 規模 + 10 万規模)。2-3 と P1-1 のコスト根拠
3. **アンカー実装調査** — OpenTimestamps の Java クライアント (Eternity Wall 系) の
   成熟度確認 (送信側は HTTP POST のみなので自前実装も選択肢、検証側は参照実装に
   寄せる案を含む)。認定 TSA / フリー TSA の候補・コスト・可用性
4. **P1-4 のモック** — 真正性レポートの見た目 (JSON スキーマ + PDF 1 枚) を先に作り、
   オーナーとマーケ観点でレビューしてから実装に入る
