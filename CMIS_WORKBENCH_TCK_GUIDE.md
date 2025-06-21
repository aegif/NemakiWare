# CMIS Workbench TCK実行ガイド

## CMIS Workbenchとは
CMIS WorkbenchはApache Chemistryプロジェクトが提供するGUIツールで、CMISリポジトリのテストやTCK（Test Compatibility Kit）の実行が可能です。

## CMIS WorkbenchでTCKを実行する手順

### 1. CMIS Workbenchのダウンロード
```bash
# Apache Chemistry公式サイトからダウンロード
# https://chemistry.apache.org/java/developing/tools/dev-tools-workbench.html
wget https://www.apache.org/dyn/closer.lua/chemistry/opencmis/1.1.0/chemistry-opencmis-workbench-1.1.0-full.zip
unzip chemistry-opencmis-workbench-1.1.0-full.zip
```

### 2. CMIS Workbenchの起動
```bash
cd chemistry-opencmis-workbench-1.1.0
# Mac/Linuxの場合
./workbench.sh

# Windowsの場合
workbench.bat
```

### 3. NemakiWareへの接続設定
Workbenchが起動したら、以下の接続情報を入力：

- **URL**: `http://localhost:8080/core/atom/bedroom`
- **Binding**: AtomPub
- **Username**: admin
- **Password**: admin
- **Repository**: bedroom

### 4. TCKテストの実行
1. メニューから「TCK」→「Run TCK」を選択
2. テスト設定ダイアログで以下を設定：
   - Test Groups: 実行したいテストグループを選択
   - Test Parameters: 必要に応じて調整
3. 「Run」ボタンをクリックしてテスト実行

### 5. TCKレポートの生成
1. テスト完了後、「TCK」→「Create Report」を選択
2. レポート形式を選択：
   - **HTML Report**: Webブラウザで閲覧可能な詳細レポート
   - **XML Report**: プログラムで処理可能なXML形式
   - **Text Report**: シンプルなテキスト形式
3. 保存先を指定してレポートを出力

## TCKレポートの内容

### HTML Report
- **Summary**: テストの概要（成功率、実行時間など）
- **Test Groups**: 各テストグループの結果
- **Test Details**: 個別テストの詳細結果
- **Failures**: 失敗したテストの詳細情報

### レポートの見方
```
Test Summary
============
Total Tests: 500
Passed: 485
Warnings: 10
Failures: 5
Success Rate: 97%

Test Groups
===========
✓ Basics Test Group: 100% (50/50)
✓ CRUD Test Group: 98% (147/150)
⚠ Query Test Group: 95% (95/100)
✗ Versioning Test Group: 90% (90/100)
```

## NemakiWare固有の注意点

1. **Solr連携**: 検索機能のテストにはSolrが起動している必要があります
2. **権限設定**: 一部のテストは管理者権限が必要です
3. **タイムアウト**: 大量データのテストでタイムアウトする場合は設定を調整

## トラブルシューティング

### 接続できない場合
```bash
# NemakiWareが起動しているか確認
curl -u admin:admin http://localhost:8080/core/atom/bedroom

# Dockerコンテナの状態確認
docker ps | grep core
```

### テストが失敗する場合
1. NemakiWareのログを確認
2. Workbenchのエラーメッセージを確認
3. ネットワーク設定やファイアウォールを確認

## 参考情報
- [Apache Chemistry CMIS Workbench](https://chemistry.apache.org/java/developing/tools/dev-tools-workbench.html)
- [CMIS 1.1 Specification](https://docs.oasis-open.org/cmis/CMIS/v1.1/CMIS-v1.1.html)