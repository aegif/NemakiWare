# Playwright テスト失敗分析 - 引き継ぎドキュメント

## 1. エグゼクティブサマリー

### 現在の状況
- **総テスト数**: 972
- **合格**: 522 (53.7%)
- **失敗**: 174
- **不安定**: 一部のテストで断続的な失敗

### 最近の成果
- ✅ **ACL継承解除**: 4/4合格（テストを修正してREST ACLエンドポイントを使用、非標準のcmis:aclInheritedプロパティを使用しない）
  - コミット: 56e8f8ac3
  - 新規ユーティリティ: `tests/utils/acl.ts`
  - 修正テスト: `tests/permissions/acl-inheritance-breaking.spec.ts`
- ✅ **初期コンテンツセットアップ**: 以前のセッションで修正済み（クリーンビルド・Dockerボリューム削除で解決）

### 現在の焦点
カテゴリ別の修正アプローチ（ターゲットテスト実行: 20-30分/カテゴリ）を使用して、システム再起動による中断を回避

### ブランチ・PR情報
- **ブランチ**: `feature/react-ui-playwright`
- **PR**: https://github.com/aegif/NemakiWare/pull/393
- **Devinセッション**: https://app.devin.ai/sessions/1c7a4a548ee74d73a66349db32491956
- **最新コミット**: 56e8f8ac3

---

## 2. 環境とテスト実行戦略

### なぜターゲット実行を使用するのか
- **問題**: フルスイート実行（約3.6時間）は定期的なシステム再起動によって中断されるリスクがある
- **解決策**: カテゴリ別のターゲット実行（20-30分）で完了を保証

### テスト実行コマンド

```bash
# UIディレクトリに移動
cd core/src/main/webapp/ui

# 単一カテゴリの実行例（chromiumのみ）
npx playwright test tests/documents/error-recovery.spec.ts --project=chromium

# リトライ制限・最大失敗数の設定
npx playwright test tests/documents/error-recovery.spec.ts --project=chromium --retries=0 --max-failures=10

# HTMLレポートの表示
npx playwright show-report
```

### レポーターとアーティファクト
- **HTMLレポート**: `playwright-report/index.html`
- **永続化ログ**: リポジトリ内の`playwright-report/`（`/tmp`ではない）
- **履歴失敗データ**: `core/src/main/webapp/ui/failing-tests.txt`（1926行）

### Docker・アプリケーション基本情報
- **Docker Compose**: `docker/docker-compose-simple.yml`
- **WARファイル**: `core/target/core.war`
- **ベースURL**: `http://localhost:8080/core/ui/`（テストは`/core/ui`をターゲット）
- **CMISベース**: `http://localhost:8080/core`
- **認証情報**: `admin:admin`

### プリフライトチェック（一般的な不安定性対策）

```bash
# コンテナの健全性確認
docker compose -f docker/docker-compose-simple.yml ps

# 問題がある場合は再起動
docker compose -f docker/docker-compose-simple.yml restart

# 約30秒待機
sleep 30
```

### 重要な注意事項
- レポートをリポジトリ内に書き込んで再起動後も保持
- `/tmp`にログを書き込まない（再起動で失われる）

---

## 3. グローバルな教訓と決定事項

### Browser Binding vs REST vs AtomPub
- **変更操作（POST）**: Browser Binding
- **読み取り操作**: 多くの場合AtomPub（リッチなメタデータのため）
- **テストでのACLチェック**: ステータスが必要な場合（継承状態等）は既存のREST ACLエンドポイントを使用、非標準プロパティは使用しない

### テスト修正ポリシー
- **承認済み**: 非標準のバックエンド動作（例: cmis:aclInheritedプロパティ）や脆弱なセレクタに依存するテストの修正は承認済み
- **優先事項**: 標準ベースのエンドポイントを優先

### WebKit不安定性の軽減
- 以前の環境回避策が存在
- 再発した場合: デバッグのためにまずwebkitを除外したターゲット実行を検討、その後再有効化

### ナビゲーション
- UIパスは一貫して`/core/ui/`を使用
- `/ui/`単独への退行を避ける

### 実行を短く、永続的に
- カテゴリ別に実行
- 各カテゴリ修正後にコミット・プッシュ
- 中間失敗リストを`failing-tests.txt`に保存

---

## 4. カテゴリ別分析と推奨事項

### 4.1 Error Recovery（エラーリカバリー）- 142件の失敗

#### 概要
- **テストファイル**: `tests/documents/error-recovery.spec.ts`（7テスト）
- **現在の状況**: chromium実行で2/7合格、4/7失敗

#### 現在の動作 vs 期待される動作
**テストが期待する動作**:
- ネットワーク失敗時のユーザー向けエラー通知
- 500エラー時のオプションのリトライフロー
- 一時的なネットワーク喪失後のセッション状態の維持
- 不正なレスポンスの適切な処理

**観察された動作**:
- `message.error()`はDocumentList.tsxのアップロードエラー時に呼び出されている（357-360行目）
- しかし、テストは`.ant-message-error`/`.ant-notification-error`を見つけられない

#### 根本原因の仮説
1. **セレクタの不一致**: AntD v5の`message.error()`は通常`.ant-message .ant-message-notice-content`の下にレンダリングされ、`.ant-message-error`ではない。テストが存在しないクラスを探している可能性がある。

2. **リトライUIが未実装**: テストは500エラー時のリトライボタンを期待しているが、現在のUIは提供していない可能性が高い。

3. **セッション永続性**: AuthContext経由で問題ない可能性が高いが、テストがネットワーク復元後の再レンダリングを待機していない可能性がある。

#### 推奨アプローチ
**テスト修正**:
- セレクタを堅牢なパターンに更新（`role=alert`または`.ant-message .ant-message-notice-content`）
- または、モーダルフロー中のエラー発生時に`.ant-alert-error`をミラーする軽量なErrorBannerコンポーネントを追加

**UI改善（オプション）**:
- クイックウィン: アップロードモーダル内に失敗時のインラインAntD Alertを表示し、テストが確実に見つけられるようにする
- リトライ: 500アップロードエラー後にモーダル内に「リトライ」プライマリボタンを実装するか、テストを手動リトライ（モーダルを閉じて再開）を受け入れるように変更

#### 主要ファイル
- **UI**: `core/src/main/webapp/ui/src/components/DocumentList/DocumentList.tsx`
- **サービス**: `core/src/main/webapp/ui/src/services/cmis.ts`（createDocumentエラーパス、handleHttpError）
- **テスト**: `core/src/main/webapp/ui/tests/documents/error-recovery.spec.ts`

#### リスク
- テストセレクタの変更は、UIがエラーを明確に表示しない場合、実際のUX問題を隠蔽する可能性がある
- 可能な限り、可視的なインラインアラートの追加を優先

#### 検証手順
```bash
cd core/src/main/webapp/ui
npx playwright test tests/documents/error-recovery.spec.ts --project=chromium
```

#### ステータスと次のアクション
- 4/7失敗（chromium）
- スクリーンショットとerror-context.mdは`test-results/*`ディレクトリを参照
- **TODO**: セレクタ調整またはインラインアラート追加を決定し、リトライUX vs テスト変更を文書化

---

### 4.2 Property Display（プロパティ表示）- 138件の失敗

#### 概要
- **テストファイル**: `tests/documents/property-display.spec.ts`
- **推定失敗数**: 138

#### 仮説
- AtomPub解析またはBrowser Bindingレスポンスでプロパティが欠落し、UIレンダリングにマッピングされていない
- 日付/数値フォーマットの違いとロケール仮定

#### 推奨事項
- CMISService.getChildren/getObjectで解析されたCMISプロパティとproperty-displayが期待するものを比較
- buildCmisObjectFromBrowserDataが使用されるすべてのプロパティを抽出することを確認

#### 主要ファイル
- `core/src/main/webapp/ui/src/services/cmis.ts`（buildCmisObjectFromBrowserData、getChildren、getObject）
- `core/src/main/webapp/ui/src/components/DocumentList/DocumentList.tsx`
- `tests/documents/property-display.spec.ts`

#### 検証手順
```bash
npx playwright test tests/documents/property-display.spec.ts --project=chromium
```

---

### 4.3 Internationalization（国際化）- 135件の失敗

#### 概要
- **テストファイル**: `tests/documents/internationalization.spec.ts`
- **推定失敗数**: 135

#### 仮説
- テキストコンテンツの不一致: 日本語 vs 英語ラベル、またはAntDデフォルト
- 日付/数値のロケールフォーマットの違い

#### 推奨事項
- コンポーネント内のラベル（検索、アップロード、パンくず、テーブル列等）をテスト期待値と照合
- 文字列を一元化

#### 主要ファイル
- `core/src/main/webapp/ui/src/components/**`（ラベル）
- `tests/documents/internationalization.spec.ts`

#### 検証手順
```bash
npx playwright test tests/documents/internationalization.spec.ts --project=chromium
```

---

### 4.4 Permission Management UI（権限管理UI）- 127件の失敗

#### 概要
- **テストファイル**: `tests/permissions/permission-management-ui.spec.ts`
- **推定失敗数**: 127

#### 既知のコンテキスト
- テストは現在REST ACLエンドポイントを優先
- cmis.tsがUI表示用のACL取得時にRESTルートを使用することを確認

#### 推奨事項
- PermissionManagement.tsxがREST エンドポイントとテストが期待するURLパターンに整合していることを確認
- UI コードがテストとの乖離を避けるために`tests/utils/acl.ts`と同様のユーティリティを検討

#### 主要ファイル
- `core/src/main/webapp/ui/src/components/PermissionManagement/PermissionManagement.tsx`
- `core/src/main/webapp/ui/src/services/cmis.ts`（getACL/setACL）
- `tests/permissions/permission-management-ui.spec.ts`

#### 検証手順
```bash
npx playwright test tests/permissions/permission-management-ui.spec.ts --project=chromium
```

---

### 4.5 Folder Hierarchy Operations（フォルダ階層操作）- 92件の失敗

#### 概要
- **テストファイル**: `tests/documents/folder-hierarchy-operations.spec.ts`
- **推定失敗数**: 92

#### 仮説
- 最近のFolderTree変更によるパンくずまたはフォーカスフォルダモデルのギャップ
- テストが使用するARIAロール/ラベルの不一致

#### 主要ファイル
- `core/src/main/webapp/ui/src/components/FolderTree/FolderTree.tsx`
- `core/src/main/webapp/ui/src/components/DocumentList/DocumentList.tsx`

#### 検証手順
```bash
npx playwright test tests/documents/folder-hierarchy-operations.spec.ts --project=chromium
```

---

### 4.6 Login（ログイン）- 85件の失敗

#### 概要
- **テストファイル**: `tests/auth/login.spec.ts`
- **推定失敗数**: 85

#### 仮説
- 断続的な認証フロー、`/core/ui/login?repositoryId=bedroom`へのリダイレクト
- Basic認証 vs トークンを期待するテスト

#### 主要ファイル
- `core/src/main/webapp/ui/src/contexts/AuthContext`
- `core/src/main/webapp/ui/src/services/auth.ts`
- `tests/auth/login.spec.ts`

#### 検証手順
```bash
npx playwright test tests/auth/login.spec.ts --project=chromium
```

---

### 4.7 Document Management（ドキュメント管理）- 71件の失敗

#### 概要
- **テストファイル**: `tests/documents/document-management.spec.ts`
- **推定失敗数**: 71

#### 仮説
- CRUD操作のタイミング問題
- モーダル/フォームの検証エラー

#### 主要ファイル
- `core/src/main/webapp/ui/src/components/DocumentList/DocumentList.tsx`
- `tests/documents/document-management.spec.ts`

---

### 4.8 Backend Versioning API（バックエンドバージョニングAPI）- 69件の失敗

#### 概要
- **テストファイル**: `tests/backend/versioning-api.spec.ts`
- **推定失敗数**: 69

#### 仮説
- CMIS バージョニング操作のエンドポイント/レスポンス形式の問題
- PWC（Private Working Copy）の検出ロジック

#### 主要ファイル
- `core/src/main/webapp/ui/src/services/cmis.ts`（checkOut、checkIn、cancelCheckOut）
- `tests/backend/versioning-api.spec.ts`

---

### 4.9 その他のカテゴリ

以下のカテゴリも失敗が報告されています（詳細な分析は次のエージェントが実施）:

- **Type Definition Upload** (60): タイプ定義のアップロード機能
- **Document Properties Edit** (56): ドキュメントプロパティの編集
- **Advanced Search** (50): 高度な検索機能
- **Group Management CRUD** (50): グループ管理のCRUD操作
- **Property Editor** (46): プロパティエディタ
- **Document Viewer Auth** (46): ドキュメントビューアの認証
- **Group Management** (41): グループ管理
- **User Management** (39): ユーザー管理
- **User Management CRUD** (37): ユーザー管理のCRUD操作
- **Custom Type Creation** (32): カスタムタイプの作成
- **Basic Connectivity** (30): 基本的な接続性
- **Document Versioning** (29): ドキュメントバージョニング
- **ACL Management** (24): ACL管理
- **Bulk Operations** (22): 一括操作
- **Access Control** (20): アクセス制御
- **Type Management** (20): タイプ管理
- **PDF Preview** (16): PDFプレビュー
- **Verify 404 Redirect** (15): 404リダイレクトの検証
- **Large File Upload** (15): 大容量ファイルアップロード
- **Debug Upload** (13): デバッグアップロード
- **Verify CMIS 404 Handling** (12): CMIS 404ハンドリングの検証
- **Debug Auth** (10): デバッグ認証
- **Type Definition Upload Debug** (4): タイプ定義アップロードデバッグ

---

## 5. 完了した作業の詳細

### ACL継承テストの修正
- **アプローチ**: 非標準のcmis:aclInheritedプロパティの代わりにREST ACLエンドポイントを使用
- **新規ユーティリティ**: `core/src/main/webapp/ui/tests/utils/acl.ts`
  - `getAclInheritedViaRest()` 関数を提供
  - REST API経由でACL継承ステータスを取得
- **更新されたテスト**: `core/src/main/webapp/ui/tests/permissions/acl-inheritance-breaking.spec.ts`
  - Browser Binding APIの代わりにREST APIを使用
  - `waitForResponse`を追加してACL適用操作の完了を保証
- **コミット**: 56e8f8ac3

### REST ACLエンドポイントの動作
- **バックエンド修正**: `core/src/main/java/jp/aegif/nemaki/rest/PermissionResource.java`
  - `breakInheritance`パラメータのサポートを追加
  - aclオブジェクト内に`aclInherited`フラグを返却
- **コミット**: 9dcdb0c69

### UI修正
- **PermissionManagement.tsx**: ACL継承フラグを使用して継承解除ボタンの表示を制御
- **コミット**: 34ab097bb

---

## 6. 優先順位付けされたバックログ（影響度 vs 労力）

### 高影響度、中程度の労力
1. **Permission Management UI** (127) - REST エンドポイントに整合; 主にUI配線/セレクタ
2. **Property Display** (138) - CMIS解析/フォーマット修正

### 中程度の影響度、中程度の労力
3. **Internationalization** (135) - ラベル/フォーマットのレビュー
4. **Error Recovery** (142) - テストセレクタ修正とインラインアラートの最小限のUI追加; リトライはオプション

### その後の進行
5. **Folder Hierarchy Operations** (92)
6. **Login** (85)
7. **Document Management** (71)
8. **Backend Versioning API** (69)
9. その他のカテゴリ

---

## 7. テストと検証のプレイブック

### 常にカテゴリ別のターゲット実行を最初に使用
```bash
cd core/src/main/webapp/ui
npx playwright test tests/<category>.spec.ts --project=chromium
```

### カテゴリがローカルで合格したら
- 時間が許せば、そのカテゴリをすべてのプロジェクト（firefox、webkit）で実行
- 複数のカテゴリがクリアされ、一晩実行の準備ができたときのみフルスイートを実行

### 出力のキャプチャ
- JUnit/JSON出力をリポジトリ内にキャプチャ
- `failing-tests.txt`を更新

---

## 8. 既知の落とし穴と軽減策

### 再起動サイクルが長時間実行を中断
- **軽減策**: 必要になるまでフルスイートを避ける; リポジトリ内にレポートを永続化

### Ant Designメッセージセレクタ
- **問題**: `.ant-message-error`を探すテストが失敗する可能性がある
- **軽減策**: `role=alert`または`.ant-message .ant-message-notice-content`を優先

### 非標準CMISプロパティ
- **ポリシー**: 追加しない
- **代替案**: RESTエンドポイントまたはBrowser Bindingのレスポンスアセンブリレイヤー内の拡張のみ（絶対に必要な場合）

---

## 9. 付録

### コマンド
```bash
# UIディレクトリに移動
cd core/src/main/webapp/ui

# カテゴリ別テスト実行
npx playwright test tests/<category>.spec.ts --project=chromium

# HTMLレポート表示
npx playwright show-report
```

### アーティファクト
- **failing-tests.txt**: `core/src/main/webapp/ui/failing-tests.txt`
- **HTMLレポート**: `core/src/main/webapp/ui/playwright-report/index.html`

### リンク
- **PR #393**: https://github.com/aegif/NemakiWare/pull/393
- **最新コミット**: 56e8f8ac3
- **セッションURL**: https://app.devin.ai/sessions/1c7a4a548ee74d73a66349db32491956

### 主要ファイルマップ
- **UI**: `src/components/**`
- **サービス**: `src/services/cmis.ts`
- **バックエンドREST**: `core/src/main/java/jp/aegif/nemaki/rest/**`
- **テスト**: `core/src/main/webapp/ui/tests/**`

---

## 10. 次のエージェント向けTODOチェックリスト

- [ ] 環境が健全であることを確認（docker-compose-simple.yml）
- [ ] ターゲットError Recovery（chromium）を実行、セレクタ調整/インラインアラート追加; リトライUX vs テスト変更の決定を文書化
- [ ] Permission Management UIに移行; REST ACL使用とURLを検証
- [ ] Property Display解析/フォーマットに対処; 仕様で検証
- [ ] failing-tests.txtを更新し、クリアされたカテゴリをマーク
- [ ] 各カテゴリ修正後に簡潔なメッセージでコミット; feature/react-ui-playwrightにプッシュ

### 注意事項
- システム再起動が予想される場合はフルスイートを実行しない; 実行を30分未満に保ち、リポジトリ内に出力を永続化
- 非標準CMISプロパティの導入を避ける; RESTエンドポイントまたは標準が許可し、スコープが限定されている場合のみバインディング固有の拡張を使用
- テストを変更する前に、プロダクトオーナーに確認（ACLの前例あり; 必要に応じて根拠を複製）
- AntDバージョンが重要; テストのセレクタは実際のDOMと一致する必要がある。AntD v5を使用している場合、`.ant-message-error`は存在しない可能性がある。堅牢なパターン（`role=alert`または`.ant-message`）を優先
- カテゴリ別に小さく頻繁にコミット; 無関係な修正を混在させない

---

## 11. 不足している情報

次のエージェントが調査すべき項目:

1. **Ant Designバージョン**: UIが使用している正確なAntDバージョンを確認（message.errorと通知の正しいセレクタを確認するため）

2. **Error Recoveryリトライフロー**: リトライフローが製品要件か、純粋にテストアーティファクトかを確認; 必要でない場合、テストを調整すべき

3. **最新の失敗テスト数**: 最新の変更後のカテゴリ別の最新の失敗テスト数（1926行のfailing-tests.txtには重複と古い実行が含まれている可能性がある）

4. **CI設定**: PR #393に、E2E安定性に影響する保留中のチェックがあるかを確認

5. **テスト修正の制約**: ACL以外のカテゴリでテストを修正することに関する明示的な制約（前例あり; テスト変更が許容される場所と許容されない場所を記録することは依然として有用）

---

## 12. 参考ドキュメント

- **AGENTS.md**: エージェント向けの一般的なガイドライン
- **docs/e2e-test-environment.md**: E2Eテスト環境のセットアップと防止策
- **Makefile**: ビルドとテストのターゲット
- **scripts/validate-test-env.sh**: テスト環境の検証スクリプト

---

**作成日**: 2025年11月24日  
**作成者**: Devin AI  
**セッション**: https://app.devin.ai/sessions/1c7a4a548ee74d73a66349db32491956
