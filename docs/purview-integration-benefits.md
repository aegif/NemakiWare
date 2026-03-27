# NemakiWare × Microsoft Purview 連携 — レコードマネージャ向けガイド

## この連携で何が変わるか

NemakiWare に保管されている文書・フォルダ・タイプ定義・アーカイブを、Microsoft Purview のデータカタログに自動的に登録・同期します。レコードマネージャは NemakiWare のコンテンツを **組織横断のガバナンスフレームワーク** の中で一元管理できるようになります。

NemakiWare の本来の強み — ACL、版管理、全文検索、ベクトル検索 — はそのまま維持しつつ、Purview が担うカタログ化・分類・系譜追跡をオーバーレイする設計です。

---

## 1. データカタログの統合 — 「見えない資産」をなくす

### 課題

NemakiWare に蓄積された文書やフォルダは、組織横断の資産台帳から抜け落ちがちです。SharePoint や Azure SQL には Purview の組み込みスキャナがありますが、CMIS リポジトリは対象外です。

### 解決

NemakiWare 専用のカスタムコネクタが、リポジトリ内のすべての文書・フォルダ・タイプ定義・アーカイブを Purview Data Map に自動投入します。

| NemakiWare 資産 | Purview エンティティ | 用途 |
|---|---|---|
| リポジトリ | `nemaki_repository` | リポジトリ単位の管理境界 |
| フォルダ | `nemaki_folder` | 文書階層の構造表現 |
| 文書 | `nemaki_document` | カタログ検索・分類・ガバナンスの主対象 |
| タイプ定義 | `nemaki_type_definition` | カスタムプロパティのスキーマ管理 |
| アーカイブ | `nemaki_archive` | 廃棄/保管状態のライフサイクル追跡 |

**効果**: Purview ポータルから NemakiWare 資産を **検索・閲覧・分類** できます。既存の Azure / M365 資産と並べて NemakiWare の文書が一覧に現れます。

---

## 2. 分類とガバナンスメタデータ — 組織ポリシーの一元適用

### 課題

機密文書の分類やビジネス用語の定義が、システムごとに分断されています。NemakiWare 上の契約書に「機密」ラベルが付いていても、組織のガバナンス台帳には反映されません。

### 解決

Purview で定義した **分類 (Classification)**、**ビジネス用語集 (Glossary Terms)**、**ラベル (Labels)** を NemakiWare の文書ビューアから直接参照できます。

#### NemakiWare 文書ビューアでの表示

文書を開くと「Purview」タブが表示され、以下を確認できます。

- **分類**: `Confidential`、`PII`、`Financial Record` など
- **用語集**: `Revenue Recognition`、`Customer Master Data` など
- **ラベル**: 組織定義のタグ
- **ビジネスメタデータ**: `repositoryId`、`objectId`、`path`、`mimeType`、`creator`、`aclSummary` 等

#### 検索結果でのガバナンスサマリ

検索結果画面では、ヒットした文書・フォルダの Purview ガバナンス情報をバルクで表示します。分類やラベルの有無を一覧から即座に把握できます。

**効果**: Purview を **ガバナンスの Single Source of Truth** として運用しつつ、NemakiWare のユーザーは自分が使っている画面から離れずにガバナンス情報を確認できます。

---

## 3. ライフサイクル管理 — 作成から廃棄まで追跡

### 課題

文書がいつ作成され、いつアーカイブされ、いつ完全廃棄されたかを、システム横断で追跡する仕組みがありません。

### 解決

NemakiWare のライフサイクル状態 (`ACTIVE` → `ARCHIVED` → `PURGED`) を Purview 上で自動追跡します。

| NemakiWare 操作 | Purview 反映 |
|---|---|
| 文書作成・更新 | `nemakiLifecycleState=ACTIVE` でエンティティ作成/更新 |
| 文書削除 → アーカイブ | `nemakiLifecycleState=ARCHIVED` + `nemaki_archive` エンティティ作成 |
| アーカイブ廃棄 | Purview からエンティティ物理削除（カタログの信頼性維持） |
| アーカイブ復元 | `nemakiLifecycleState=ACTIVE` に復帰 |

### 削除の安全な処理

「削除」イベントを受けても即座に Purview から消しません。猶予期間を設けて以下を確認します。

1. 実際にアーカイブされたのか
2. 完全に廃棄されたのか
3. 別の操作で復活したのか

これにより、NemakiWare 内部の削除→アーカイブの非同期処理タイミングのずれを吸収し、誤ったカタログ更新を防ぎます。

**効果**: レコードの保管期限やディスポジション判定に必要な **ライフサイクル証跡** が Purview 上で自動的に維持されます。

---

## 4. リネージュ (系譜) — データの出どころと行き先を可視化

### 課題

「この契約書はどこから取り込まれたのか」「このレポートはどこにエクスポートされたか」「このアーカイブはどこに保管されているか」を追跡できません。

### 解決

NemakiWare の主要なデータフローを Purview Lineage として自動記録します。

#### 対応するリネージュ

| データフロー | Purview プロセス | Source → Target |
|---|---|---|
| Cloud Drive 同期 | `nemaki_cloud_sync_process` | OneDrive/Google Drive ↔ NemakiWare 文書 |
| ファイルシステムインポート | `nemaki_import_process` | 外部ファイルパス → NemakiWare フォルダ |
| ファイルシステムエクスポート | `nemaki_export_process` | NemakiWare フォルダ → 外部ファイルパス |
| ZIPアップロード/ACPインポート | `nemaki_import_process` | (プロセスメタデータのみ) |
| ZIPダウンロード/選択エクスポート | `nemaki_export_process` | (プロセスメタデータのみ) |
| アーカイブ→コールドストレージ | `nemaki_archive_process` | NemakiWare 文書 → コールドストレージ |

#### 外部アセットの自動追跡

安定した識別キーを持つ外部対象（OneDrive の `fileId`、Google Drive の `fileId`、S3 の `bucket/key`）には `nemaki_external_asset` エンティティが自動生成されます。

**効果**: Purview のリネージュビューで、NemakiWare の文書が **どこから来て、どこに行ったか** を視覚的に把握できます。監査や規制対応で求められるデータフローの証跡に利用できます。

---

## 5. 継続的な同期 — 手動棚卸しからの解放

### 課題

資産台帳の更新が手動や定期バッチに依存しており、カタログが実態と乖離します。

### 解決

3層の同期メカニズムで、NemakiWare の変更を Purview に継続反映します。

#### 5a. フル同期

初回導入や大規模修復時に、リポジトリ全体を Purview に一括投入します。フォルダツリー走査、タイプ定義一覧、アーカイブ一覧を順次処理します。

#### 5b. 増分同期 (日次自動)

NemakiWare の変更ログ (Change Log) を定期ポーリングし、変更があった文書・フォルダのみを Purview に反映します。

- **cron スケジュール設定可能**: 管理画面から cron 式で実行タイミングを設定（デフォルト: 毎日 3:00 AM）
- **フォルダリネーム対応**: フォルダ名が変更されると、配下の全子孫のパス情報も自動で再計算・再同期
- **部分失敗の隔離**: 特定文書の同期に失敗しても、他の文書の同期は継続。失敗分は Dead Letter として隔離され、後から再試行可能

#### 5c. 補助リコンシリエーション

変更ログだけでは捕捉しにくい対象（タイプ定義の更新、アーカイブ状態の変化、クラウドメタデータの変動、コンテインメント関係の不整合）は、専用のリコンシリエーションジョブで補完します。

| リコンシリエーション種別 | 対象 |
|---|---|
| TYPE_RECONCILIATION | タイプ定義の追加・変更・削除 |
| ARCHIVE_RECONCILIATION | アーカイブの追加・復元 |
| CLOUD_METADATA_RECONCILIATION | Cloud Drive メタデータの付与・変更・除去 |
| CONTAINMENT_RECONCILIATION | フォルダ-文書の包含関係の不整合 |

**効果**: カタログが常に最新の状態を反映します。手動棚卸しの作業負荷を削減し、ガバナンス監査時に「いつ時点の情報か」を問われずに済みます。

---

## 6. 障害耐性 — Purview 障害が業務を止めない

### 設計原則

Purview 連携は **完全に非同期** です。NemakiWare ユーザーの文書作成・更新・削除は、Purview の可用性に一切依存しません。

| 状況 | NemakiWare の動作 | Purview 側の状態 |
|---|---|---|
| Purview 正常 | 通常どおり | 変更が自動反映される |
| Purview 一時障害 | 通常どおり | 次回同期で差分を回復 |
| Purview 長期障害 | 通常どおり | 復旧後にフル同期で完全修復可能 |
| Purview API 429/5xx | 通常どおり | Exponential backoff で自動リトライ |

**効果**: 「ガバナンス基盤の障害で業務文書が更新できない」というリスクがゼロです。

---

## 7. 管理画面 — IT部門の運用負荷を最小化

NemakiWare の管理画面に Purview 連携の専用セクションが組み込まれています。

### 設定管理 (Integration Settings)

- Purview/Atlas エンドポイント設定
- 認証方式選択（OAuth2 / Basic認証）
- コレクション名設定
- 増分同期スケジュール（cron 式）設定
- 接続テストボタン

### 運用管理 (Purview Management)

| 機能 | 説明 |
|---|---|
| スキーマ差分確認 | NemakiWare のタイプ定義と Purview 側スキーマの差分を表示 |
| スキーマ適用 | カスタムタイプ・ビジネスメタデータ定義を Purview に一括適用 |
| フル同期 | リポジトリ全体を Purview に投入 |
| 増分同期 | 手動での増分同期実行 |
| リコンシリエーション | タイプ/アーカイブ/クラウドメタデータ/コンテインメントの再整合 |
| 削除解決 | Tombstone の手動解決 |
| Dead Letter 管理 | 失敗したエンティティの確認と再試行 |
| ガバナンス検索 | オブジェクト ID による Purview ガバナンス情報の一括検索 |
| ジョブ/カーソル/ロック状態 | 同期状態のリアルタイムモニタリング |

**効果**: GUI ベースの操作だけで連携の設定・監視・トラブルシューティングが完結します。

---

## 8. 関係性の自動構築 — 構造を理解するカタログ

NemakiWare のコンテンツ構造を Purview 上でリレーションシップとして表現します。

| 関係性 | 意味 |
|---|---|
| `nemaki_repository_contains_folder` | リポジトリ → ルートフォルダ |
| `nemaki_folder_contains_folder` | 親フォルダ → 子フォルダ |
| `nemaki_folder_contains_document` | フォルダ → 文書 |
| `nemaki_document_has_type_definition` | 文書 → カスタムタイプ定義 |
| `nemaki_document_has_archive` | 文書 → アーカイブレコード |

**効果**: Purview ポータル上で NemakiWare のフォルダ階層を **ナビゲート** できます。「この文書はどのフォルダに属しているか」「このフォルダにはどんな文書があるか」を、Purview の統合ビューから確認できます。

---

## 9. ビジネスメタデータの同期 — CMIS プロパティをガバナンス属性に

NemakiWare の CMIS プロパティを Purview のビジネスメタデータとして同期します。

| Purview ビジネスメタデータ | NemakiWare 元データ |
|---|---|
| `repositoryId` | リポジトリ識別子 |
| `objectId` | オブジェクト固有 ID |
| `path` | フォルダパス |
| `baseTypeId` | CMIS 基本タイプ |
| `typeId` | カスタムタイプ |
| `versionSeriesId` / `versionLabel` | バージョン情報 |
| `mimeType` / `contentLength` | コンテンツ属性 |
| `creator` / `lastModifiedBy` | 操作者情報 |
| `aclSummary` | ACL 要約（ACE数、公開可否、所有者） |
| `cloudProvider` / `externalFileId` | クラウド連携情報 |
| `nemakiLifecycleState` | ライフサイクル状態 |

**効果**: CMIS プロパティが組織のデータカタログに **構造化された属性** として取り込まれ、Purview の検索・フィルタリング・ポリシー適用の対象になります。

---

## 10. Apache Atlas 互換 — オンプレミスでもクラウドでも

NemakiWare の Purview コネクタは、Microsoft Purview (クラウド) と Apache Atlas (オンプレミス) の両方に対応しています。

| 環境 | エンドポイント | 認証方式 |
|---|---|---|
| Microsoft Purview | `https://{account}.purview.azure.com` | OAuth2 (Entra ID) |
| Apache Atlas on-prem | `https://{host}:{port}` | Basic 認証 |

管理画面でエンドポイントと認証方式を切り替えるだけで、クラウドとオンプレミスどちらのメタデータガバナンス基盤にも接続できます。

**効果**: クラウド移行前のオンプレミス環境でも、ガバナンス統合を先行して開始できます。

---

## 導入の流れ

```
1. 管理画面 → Integration Settings → Purview/Atlas タブ
     ↓ エンドポイント・認証情報を設定して接続テスト
2. 管理画面 → Purview Management → スキーマ適用
     ↓ カスタムタイプ・ビジネスメタデータ定義を Purview に登録
3. 管理画面 → Purview Management → フル同期
     ↓ リポジトリ全体を Purview に初回投入
4. 管理画面 → Integration Settings → 増分同期スケジュール設定
     ↓ 以降は自動で日次増分同期
5. 文書ビューア → Purview タブ
     ↓ ユーザーが日常業務で分類・用語・ラベルを確認
```

---

## まとめ: レコードマネージャにとっての価値

| 従来の課題 | Purview 連携後 |
|---|---|
| NemakiWare の資産が組織カタログに載らない | 全資産が自動的に Purview に登録される |
| 分類やラベルがシステムごとにバラバラ | Purview の分類・用語集を NemakiWare から直接参照 |
| 文書のライフサイクルが追跡できない | ACTIVE → ARCHIVED → PURGED を自動追跡 |
| データの出どころ・行き先が不明 | Cloud Drive・インポート・エクスポート・アーカイブのリネージュを可視化 |
| カタログ更新が手動で遅延する | 日次増分同期 + リコンシリエーションで常に最新 |
| ガバナンス基盤障害が業務を止める | 完全非同期設計で業務影響ゼロ |
| 連携管理に専門知識が必要 | GUI だけで設定・監視・トラブルシューティングが完結 |

NemakiWare × Purview 連携により、レコードマネージャは **NemakiWare に保管された全コンテンツを、組織のガバナンスフレームワークの中で一元的に管理** できるようになります。文書管理とデータガバナンスの間にあった断絶を埋め、規制対応・監査・情報ガバナンスの実効性を高めます。
