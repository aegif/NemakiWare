# NemakiWare 3.0.0 展開ガイド

## 🎯 概要

このガイドは、NemakiWare 3.0.0を他の環境で確実に動作させるための完全な手順を提供します。

## ✅ 前提条件

### 必須環境
- **Java 17**: OpenJDK 17以上（必須）
- **Docker**: 20.10以上
- **Docker Compose**: 2.0以上
- **Maven**: 3.6以上
- **Git**: 2.0以上

### 環境確認コマンド
```bash
# Java 17の確認（必須）
java -version
# 出力例: openjdk version "17.0.12"

# Dockerの確認
docker --version && docker compose version

# Mavenの確認
mvn -version
```

## 🚀 クイックスタート（5分で起動）

### 1. ソースコードの取得
```bash
# Gitリポジトリからクローン
git clone https://github.com/aegif/NemakiWare.git
cd NemakiWare

# または配布パッケージを展開
# unzip NemakiWare-3.0.0.zip && cd NemakiWare-3.0.0
```

### 2. Java 17環境の設定（重要）
```bash
# Java 17をJAVA_HOMEに設定
export JAVA_HOME=/path/to/java17
export PATH=$JAVA_HOME/bin:$PATH

# 確認
java -version  # "17.x.x"が表示されることを確認
```

### 3. 完全なクリーンビルド
```bash
# 全モジュールのビルド（約3分）
mvn clean package -Pdevelopment

# ビルド成功の確認
ls -la core/target/core.war
ls -la cloudant-init/target/cloudant-init-1.0.0-jar-with-dependencies.jar
```

### 4. Docker環境の準備
```bash
# ビルド成果物をDockerコンテキストにコピー
cp core/target/core.war docker/core/core.war
cp cloudant-init/target/cloudant-init-1.0.0-jar-with-dependencies.jar docker/cloudant-init/cloudant-init.jar
cp cloudant-init/target/cloudant-init-1.0.0-jar-with-dependencies.jar docker/initializer/cloudant-init.jar
```

### 5. 環境の起動
```bash
cd docker
docker compose -f docker-compose-simple.yml up -d

# 起動待機（約2分）
sleep 120
```

### 6. 動作確認
```bash
# 基本的なCMISエンドポイントのテスト
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# 期待結果: HTTP 200、XML応答

# Browser Binding修正の確認
curl -u admin:admin -X POST \
  -F "cmisaction=query" \
  -F "q=SELECT * FROM cmis:folder" \
  -F "maxItems=1" \
  http://localhost:8080/core/browser/bedroom
# 期待結果: JSON形式でフォルダ情報が返される
```

## 🔧 詳細な検証手順

### Browser Binding修正の確認
```bash
# 重要：allowedChildObjectTypeIdsプロパティの動作確認
curl -u admin:admin -X POST \
  -F "cmisaction=query" \
  -F "q=SELECT cmis:allowedChildObjectTypeIds FROM cmis:folder" \
  -F "maxItems=1" \
  http://localhost:8080/core/browser/bedroom | jq '.results[0].properties["cmis:allowedChildObjectTypeIds"].queryName'

# 期待結果: "cmis:allowedChildObjectTypeIds"
# （修正前はnullでエラーになっていた）
```

### データベース初期化の確認
```bash
# CouchDBデータベースの確認
curl -u admin:password http://localhost:5984/_all_dbs
# 期待結果: ["bedroom","bedroom_closet","canopy","canopy_closet","nemaki_conf"]

curl -u admin:password http://localhost:5984/bedroom | jq '{db_name, doc_count}'
# 期待結果: {"db_name": "bedroom", "doc_count": 22}
```

## 🐛 トラブルシューティング

### よくある問題と解決方法

#### 1. Java Version不正
**症状**: `mvn clean package`でコンパイルエラー
**解決**: 
```bash
export JAVA_HOME=/path/to/java17
java -version  # 17.x.xを確認
```

#### 2. Docker権限エラー
**症状**: "permission denied"エラー
**解決**:
```bash
sudo docker compose -f docker-compose-simple.yml up -d
# または、ユーザーをdockerグループに追加
```

#### 3. ポート競合
**症状**: "port already in use"エラー
**解決**:
```bash
# 使用中のポートを確認
lsof -i :8080
lsof -i :5984
lsof -i :8983

# 必要に応じて既存プロセスを停止
```

#### 4. Browser Binding クエリエラー
**症状**: "No query name or alias for property"エラー
**原因**: 修正前のコードまたは不完全なビルド
**解決**: 完全なクリーンビルドを実行

## 📋 成功基準チェックリスト

### ✅ ビルド段階
- [ ] Java 17環境が設定されている
- [ ] `mvn clean package -Pdevelopment`が成功
- [ ] `core/target/core.war`が生成されている（約304MB）
- [ ] `cloudant-init-1.0.0-jar-with-dependencies.jar`が生成されている（約8.8MB）

### ✅ デプロイ段階
- [ ] 全Dockerコンテナが起動（4初期化コンテナは終了して正常）
- [ ] CouchDBが全5データベースを持つ
- [ ] bedroomデータベースに22ドキュメント存在

### ✅ 機能テスト段階
- [ ] CMIS AtomPub: `curl -u admin:admin http://localhost:8080/core/atom/bedroom` → HTTP 200
- [ ] CMIS Browser: フォルダクエリが動作
- [ ] `cmis:allowedChildObjectTypeIds`プロパティにqueryNameが設定されている

## 🎯 重要な修正点

### Browser Binding SELECT * クエリ修正
- **ファイル**: `core/src/main/java/jp/aegif/nemaki/cmis/aspect/impl/CompileServiceImpl.java`
- **修正内容**: PropertyDefinitionコンストラクターを使用してqueryNameを適切に設定
- **影響**: Browser BindingでのフォルダSELECTクエリが動作

### Jakarta EE 10統合
- **完全なjakarta.*名前空間対応**
- **OpenCMIS 1.1.0のJakarta変換版使用**
- **Tomcat 10+環境での動作保証**

## 🌐 アクセスURL

環境起動後、以下のURLでアクセス可能：

- **CMIS AtomPub**: http://localhost:8080/core/atom/bedroom
- **CMIS Browser**: http://localhost:8080/core/browser/bedroom
- **CouchDB管理**: http://localhost:5984/_utils (admin/password)
- **Solr管理**: http://localhost:8983/solr

## 📞 サポート

問題が発生した場合：

1. **ログ確認**: `docker logs docker-core-1`
2. **環境確認**: Java 17、Docker権限を確認
3. **完全クリーンビルド**: すべてのコンテナ停止→クリーンビルド→再起動

---

**重要**: このガイドはNemakiWare 3.0.0のJakarta EE 10統合版とBrowser Binding修正を含む最新バージョン向けです。古いバージョンからの移行時は互換性に注意してください。