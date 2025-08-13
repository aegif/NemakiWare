# OpenCMIS 1.2.0-SNAPSHOT Build Workspace Architecture

## Status: プロジェクトの重要な構成要素（外部ライブラリではない）

### 重要性
- **Jakarta EE 10対応**: javax.* → jakarta.* 完全変換済み
- **カスタムパッチ適用**: NemakiWare固有の修正とデバッグログが含まれる
- **プロダクション依存**: NemakiWareの正常動作に不可欠

### ディレクトリ構造
```
build-workspace/chemistry-opencmis/
├── chemistry-opencmis-client/          # CMIS クライアント API
│   └── chemistry-opencmis-client-bindings/
│       └── src/main/java/.../RepositoryServiceImpl.java  # HTTP通信層
├── chemistry-opencmis-server/          # CMIS サーバー実装
├── chemistry-opencmis-commons/         # 共通ライブラリ
└── built-jars/                        # ビルド成果物（JAR）
```

### カスタマイズ内容
1. **Jakarta EE 10 Namespace**: 全ファイルでjavax.* → jakarta.*変換
2. **HTTP Request Logging**: Browser Binding通信のデバッグ機能
3. **NemakiWare Integration**: 特定の動作調整とバグ修正
4. **Build Configuration**: Jakarta EE環境向けビルド設定

### Git管理ポリシー
- **ソースコード**: git管理対象（重要なプロジェクト資産）
- **ビルド成果物**: built-jars/配下のJARファイルも含む
- **除外対象**: target/ディレクトリ、*.classファイルのみ

### セキュリティ考慮
- Claude Codeのセキュリティポリシーにより直接編集が制限される場合がある
- Bashコマンド経由での編集が必要な場合がある
- 重要な変更は必ずバックアップを作成してから実行する

### メンテナンス方針
- OpenCMIS公式の更新があった場合、Jakarta変換を再実行
- カスタムパッチは文書化してバージョン管理
- テスト実行時のログ出力で動作確認を継続