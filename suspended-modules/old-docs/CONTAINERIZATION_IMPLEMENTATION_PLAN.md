# コンテナ化アプローチ実装計画書

## プロジェクト概要

NemakiWareのSolr 9.x+アップグレードをコンテナ化アプローチで実現する詳細実装計画です。

## アーキテクチャ設計

### 現在の構成
```
┌─────────────────────────────────────────────────────────┐
│                NemakiWare (Java 17)                    │
│  ┌─────────────────────────────┐ ┌─────────────────┐   │
│  │ Core Module                 │ │ Solr Module     │   │
│  │ (CMIS/Java + React SPA UI)  │ │ (Solr 9.x/Java) │   │
│  └─────────────────────────────┘ └─────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 目標構成（コンテナ化後）
```
┌─────────────────┐  ┌─────────────────────────────────────┐
│        Backend Containers (Java 17)                    │
│                                                         │
│ ┌─────────────────────────────┐ ┌─────────────────┐     │
│ │ Core Module                 │ │ Solr Module     │     │
│ │ (CMIS/Java + React SPA UI)  │ │ (Solr 9.x/Java) │     │
│ │ Java 17                     │ │ Java 17         │     │
│ └─────────────────────────────┘ └─────────────────┘     │
                     └─────────────────────────────────────┘
```

## コンテナ設計詳細

### 1. UIコンテナ (Java 8維持)
```dockerfile
# Dockerfile.ui
FROM openjdk:8-jdk-alpine

# Play Framework環境
RUN apk add --no-cache bash curl

WORKDIR /app/ui
COPY ui/ .
# Note: action and action-sample modules moved to suspended-modules/
# COPY suspended-modules/action/ /app/action/
# COPY suspended-modules/action-sample/ /app/action-sample/

# SBT設定
RUN curl -L -o sbt.tgz https://github.com/sbt/sbt/releases/download/v1.8.2/sbt-1.8.2.tgz && \
    tar -xzf sbt.tgz && \
    mv sbt /usr/local/

ENV PATH="/usr/local/sbt/bin:$PATH"

EXPOSE 9000
CMD ["sbt", "run"]
```

### 2. Coreコンテナ (Java 11+)
```dockerfile
# Dockerfile.core
FROM openjdk:11-jdk-slim

# 必要なツールインストール
RUN apt-get update && apt-get install -y \
    curl \
    maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app/core
COPY core/ .
COPY common/ /app/common/

# Maven依存関係解決
RUN mvn dependency:resolve

# アプリケーション設定
COPY core/src/main/webapp/WEB-INF/classes/*.properties ./config/
COPY core/src/main/webapp/WEB-INF/classes/*.xml ./config/

EXPOSE 8080
CMD ["mvn", "tomcat7:run"]
```

### 3. Solrコンテナ (Java 11+)
```dockerfile
# Dockerfile.solr
FROM openjdk:11-jdk-slim

# Solr 9.8.0インストール
ENV SOLR_VERSION=9.8.0
RUN curl -L -o solr.tgz https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz && \
    tar -xzf solr.tgz && \
    mv solr-${SOLR_VERSION} /opt/solr && \
    rm solr.tgz

# カスタムSolrモジュール
WORKDIR /app/solr
COPY solr/ .

# Maven依存関係解決
RUN apt-get update && apt-get install -y maven && \
    mvn dependency:resolve && \
    mvn clean package

# Solr設定
COPY solr/solr/ /opt/solr/server/solr/
COPY docker/solr/solr/ /opt/solr/server/solr/

ENV SOLR_HOME=/opt/solr/server/solr
EXPOSE 8983

CMD ["/opt/solr/bin/solr", "start", "-f"]
```

### 4. データベースコンテナ (変更なし)
```dockerfile
# 既存のCouchDB設定を使用
FROM couchdb:3.3
# 既存設定をそのまま使用
```

## Docker Compose設定

### docker-compose-containerized.yml
```yaml
version: '3.8'

services:
  # データベース (変更なし)
  couchdb:
    image: couchdb:3.3
    environment:
      - COUCHDB_USER=admin
      - COUCHDB_PASSWORD=password
    ports:
      - "5984:5984"
    volumes:
      - couchdb_data:/opt/couchdb/data
    networks:
      - nemaki-network

  # Solrサービス (Java 11+)
  solr:
    build:
      context: .
      dockerfile: Dockerfile.solr
    environment:
      - JAVA_OPTS=-Xmx2g -Xms1g
      - SOLR_JAVA_HOME=/usr/local/openjdk-11
    ports:
      - "8983:8983"
    volumes:
      - solr_data:/opt/solr/server/solr/data
      - ./solr/solr:/opt/solr/server/solr/configsets
    depends_on:
      - couchdb
    networks:
      - nemaki-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8983/solr/admin/ping"]
      interval: 30s
      timeout: 10s
      retries: 3

  # Coreサービス (Java 11+)
  core:
    build:
      context: .
      dockerfile: Dockerfile.core
    environment:
      - JAVA_OPTS=-Xmx1g -Xms512m
      - SPRING_PROFILES_ACTIVE=docker
      - SOLR_URL=http://solr:8983/solr
      - COUCHDB_URL=http://couchdb:5984
    ports:
      - "8080:8080"
    volumes:
      - ./core/src/main/webapp/WEB-INF/classes:/app/config
    depends_on:
      - couchdb
      - solr
    networks:
      - nemaki-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/core/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  # UIサービス (Java 8維持)
  ui:
    build:
      context: .
      dockerfile: Dockerfile.ui
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - CORE_URL=http://core:8080
      - PLAY_HTTP_SECRET_KEY=${PLAY_SECRET_KEY:-changeme}
    ports:
      - "9000:9000"
    volumes:
      - ./ui/conf:/app/conf
    depends_on:
      - core
    networks:
      - nemaki-network

volumes:
  couchdb_data:
  solr_data:

networks:
  nemaki-network:
    driver: bridge
```

## 実装フェーズ

### フェーズ1: 環境準備・依存関係更新 (1週間)

#### 1.1 Java 11依存関係更新
```bash
# Core module
cd core/
# AspectJ更新
sed -i 's/<version>1.8.4<\/version>/<version>1.9.19<\/version>/' pom.xml

# Logback更新
sed -i 's/<version>1.1.3<\/version>/<version>1.2.12<\/version>/' pom.xml

# Jersey Test Framework更新 (テスト環境のみ)
# 詳細な移行作業が必要
```

#### 1.2 Solr module依存関係更新
```bash
# Solr module
cd solr/
# Jersey Client 1.x → 2.x移行
# 詳細なコード変更が必要

# Restlet互換性検証
mvn dependency:tree | grep restlet
```

#### 1.3 Docker環境構築
```bash
# Dockerfileの作成
touch Dockerfile.ui Dockerfile.core Dockerfile.solr

# docker-compose設定
cp docker-compose-simple.yml docker-compose-containerized.yml
# 上記設定を適用
```

### フェーズ2: API移行・コード更新 (2週間)

#### 2.1 Jersey Client 1.x → 2.x移行
**影響ファイル**: `solr/src/main/java/jp/aegif/nemaki/tracker/CoreTracker.java`

```java
// 変更前 (Jersey 1.x)
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;

Client client = Client.create();
WebResource resource = client.resource(url);

// 変更後 (Jersey 2.x)
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

Client client = ClientBuilder.newClient();
WebTarget target = client.target(url);
```

#### 2.2 Restlet API更新
**影響ファイル**: `solr/src/main/java/jp/aegif/nemaki/NemakiCoreAdminHandler.java`

```java
// Restlet 2.1.1 → 2.4.x移行
// 詳細な互換性検証とAPI更新が必要
```

#### 2.3 AspectJ設定更新
**影響ファイル**: `core/src/main/webapp/WEB-INF/classes/applicationContext.xml`

```xml
<!-- AspectJ 1.9.x対応設定 -->
<aop:aspectj-autoproxy proxy-target-class="true"/>
```

### フェーズ3: コンテナ統合・テスト (1週間)

#### 3.1 コンテナビルド・起動テスト
```bash
# 個別コンテナテスト
docker build -f Dockerfile.ui -t nemaki-ui .
docker build -f Dockerfile.core -t nemaki-core .
docker build -f Dockerfile.solr -t nemaki-solr .

# 統合テスト
docker-compose -f docker-compose-containerized.yml up -d
```

#### 3.2 サービス間通信テスト
```bash
# ヘルスチェック
curl http://localhost:9000/health    # UI
curl http://localhost:8080/core/health # Core
curl http://localhost:8983/solr/admin/ping # Solr

# 統合機能テスト
# CMIS操作テスト
# Solr検索テスト
# ファイルアップロード・ダウンロードテスト
```

#### 3.3 パフォーマンステスト
```bash
# 負荷テスト
ab -n 1000 -c 10 http://localhost:9000/
ab -n 500 -c 5 http://localhost:8080/core/api/search

# メモリ使用量監視
docker stats
```

### フェーズ4: 本番対応・ドキュメント (1週間)

#### 4.1 本番環境設定
```yaml
# docker-compose-production.yml
version: '3.8'
services:
  ui:
    # 本番最適化設定
    deploy:
      replicas: 2
      resources:
        limits:
          memory: 1G
        reservations:
          memory: 512M
  
  core:
    deploy:
      replicas: 3
      resources:
        limits:
          memory: 2G
        reservations:
          memory: 1G
  
  solr:
    deploy:
      replicas: 2
      resources:
        limits:
          memory: 4G
        reservations:
          memory: 2G
```

#### 4.2 監視・ログ設定
```yaml
# Prometheus + Grafana監視
monitoring:
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
  
  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
```

#### 4.3 バックアップ・復旧手順
```bash
# データバックアップ
docker-compose exec couchdb couchdb-backup
docker-compose exec solr solr-backup

# 設定バックアップ
tar -czf nemaki-config-backup.tar.gz \
  core/src/main/webapp/WEB-INF/classes/ \
  solr/solr/ \
  ui/conf/
```

## リスク管理・対策

### 高リスク項目

#### 1. Jersey Client 1.x → 2.x移行
**リスク**: API変更によるコンパイルエラー・動作不良
**対策**: 
- 段階的移行（テスト環境で十分検証）
- 互換性レイヤーの一時的実装
- ロールバック計画の準備

#### 2. Restlet互換性問題
**リスク**: Java 11環境での動作不良
**対策**:
- 事前互換性検証
- 代替ライブラリ（Spring WebClient）への移行準備
- 機能分離による影響範囲限定

#### 3. コンテナ間通信問題
**リスク**: ネットワーク設定ミスによるサービス間通信障害
**対策**:
- 詳細なネットワーク設計
- ヘルスチェック機能の実装
- サービスディスカバリ機能の活用

### 中リスク項目

#### 1. パフォーマンス劣化
**リスク**: コンテナ化によるオーバーヘッド
**対策**:
- ベンチマークテストの実施
- リソース配分の最適化
- キャッシュ戦略の見直し

#### 2. メモリ使用量増加
**リスク**: 複数JVMによるメモリ消費増加
**対策**:
- JVMヒープサイズの最適化
- ガベージコレクション設定の調整
- メモリ監視の強化

## 成功基準

### 機能要件
- [ ] 全てのCMIS操作が正常動作
- [ ] Solr検索機能が正常動作
- [ ] ファイルアップロード・ダウンロードが正常動作
- [ ] ユーザー認証・認可が正常動作

### 非機能要件
- [ ] レスポンス時間が現行の120%以内
- [ ] メモリ使用量が現行の150%以内
- [ ] 99%以上のアップタイム維持
- [ ] ゼロダウンタイムデプロイメント実現

### 運用要件
- [ ] 監視・アラート機能の実装
- [ ] ログ集約・分析機能の実装
- [ ] バックアップ・復旧手順の確立
- [ ] 運用ドキュメントの整備

## 総合スケジュール

| フェーズ | 期間 | 主要作業 | 成果物 |
|----------|------|----------|--------|
| フェーズ1 | 1週間 | 環境準備・依存関係更新 | 更新されたpom.xml、Dockerfile |
| フェーズ2 | 2週間 | API移行・コード更新 | 移行されたJavaコード |
| フェーズ3 | 1週間 | コンテナ統合・テスト | docker-compose.yml、テスト結果 |
| フェーズ4 | 1週間 | 本番対応・ドキュメント | 本番設定、運用ドキュメント |

**総期間**: 5週間
**総工数**: 約120-150時間（1-2名体制）

## 次セッションでの実装開始点

### 優先実装項目
1. **AspectJ 1.9.19への更新** (core/pom.xml)
2. **Logback 1.2.12への更新** (core/pom.xml, solr/pom.xml)
3. **Dockerfile.core, Dockerfile.solrの作成**
4. **Jersey Client移行の開始** (solr/src/main/java/...)

### 準備済み項目
- 依存関係互換性分析完了
- コンテナ設計完了
- 実装計画詳細化完了
- リスク評価・対策完了

この計画に基づいて、次セッションから段階的な実装作業を開始できます。
