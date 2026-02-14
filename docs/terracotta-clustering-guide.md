# NemakiWare Terracotta クラスタリング構成ガイド

## 概要

本ドキュメントは、NemakiWare の ehcache 3.x キャッシュ層を Terracotta Server で分散キャッシュ化し、
複数の NemakiWare Core ノードでキャッシュを共有・同期する手順を記載します。

### 現状のアーキテクチャ

```
[Core Node] → CacheService → CacheManager (ローカル heap のみ)
                                ↓
                         ehcache.yml (YAML設定)
```

各 Core ノードが独立した `CacheManager` を持ち、ノード間のキャッシュ同期はありません。

### 目標アーキテクチャ

```
[Core Node 1] → CacheManager ──┐
                                ├─→ [Terracotta Server (Active)]
[Core Node 2] → CacheManager ──┤       ↕ (自動フェイルオーバー)
                                └─→ [Terracotta Server (Passive)]
```

---

## 前提条件

| 項目 | 要件 |
|------|------|
| Java | 17以上 |
| ehcache | 3.10.8（導入済み） |
| ehcache-clustered | 3.10.8（導入済み、optional） |
| Terracotta Server | 3.10.8（Docker イメージ利用） |
| ネットワーク | Core ↔ Terracotta 間で TCP 9410 ポート疎通 |

---

## Step 1: nemakiware.properties の設定

NemakiWare はプロパティファイルの変更だけでクラスタリングを有効化できます。

### 設定プロパティ

| プロパティ | デフォルト | 説明 |
|-----------|-----------|------|
| `cache.clustering.enabled` | `false` | クラスタリングの有効/無効 |
| `cache.clustering.terracotta.url` | (なし) | Terracotta サーバー接続URI |
| `cache.clustering.offheap.mb` | `100` | 各キャッシュのオフヒープサイズ (MB) |

### 設定例: docker/core/nemakiware.properties

```properties
###Cache
cache.config=ehcache.yml
cache.clustering.enabled=true
cache.clustering.terracotta.url=terracotta://terracotta-active:9410/nemakiware
cache.clustering.offheap.mb=100
```

### Docker環境変数で上書き

Spring PropertyManager は `-D` システムプロパティでの上書きをサポートしています。
`docker-compose-simple.yml` の `CATALINA_OPTS` で上書き可能:

```yaml
environment:
  - CATALINA_OPTS=...
    -Dcache.clustering.enabled=true
    -Dcache.clustering.terracotta.url=terracotta://terracotta-active:9410/nemakiware
    -Dcache.clustering.offheap.mb=100
```

---

## Step 2: Terracotta Server の Docker 構成

### 2.1 tc-config.xml の作成

`docker/terracotta/tc-config.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tc-config xmlns="http://www.terracotta.org/config"
           xmlns:ohr="http://www.terracotta.org/config/offheap-resource">
  <plugins>
    <config>
      <ohr:offheap-resources>
        <ohr:resource name="primary-server-resource" unit="MB">256</ohr:resource>
      </ohr:offheap-resources>
    </config>
  </plugins>
  <servers>
    <server host="terracotta-active" name="active">
      <tsa-port>9410</tsa-port>
      <logs>terracotta/server-logs</logs>
    </server>
    <!-- Passive サーバー (HA構成時) -->
    <server host="terracotta-passive" name="passive">
      <tsa-port>9410</tsa-port>
      <logs>terracotta/server-logs</logs>
    </server>
  </servers>
</tc-config>
```

**offheap サイジングの目安:**

| キャッシュ対象 | 推定サイズ/エントリ | 推定エントリ数 | 推定合計 |
|---|---|---|---|
| contentCache | ~2 KB | 10,000 | ~20 MB |
| objectDataCache | ~5 KB | 10,000 | ~50 MB |
| aclCache | ~1 KB | 10,000 | ~10 MB |
| typeCache | ~10 KB | 100 | ~1 MB |
| userCache / groupCache | ~1 KB | 1,000 | ~2 MB |
| その他 | - | - | ~20 MB |
| **合計** | | | **~103 MB** |

256 MB あれば十分なマージンがあります。大規模環境では 512 MB 以上を推奨。

### 2.2 docker-compose への追加

`docker/docker-compose-simple.yml` に以下を追加:

```yaml
  # Terracotta Server (Active)
  terracotta-active:
    image: terracotta/ehcache-terracotta-server:3.10.8
    ports:
      - "9410:9410"
    volumes:
      - ./terracotta/tc-config.xml:/terracotta/server/conf/tc-config.xml:ro
    environment:
      - DEFAULT_ACTIVATE=true
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9430/tc-management-api/v2/agents"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  # Terracotta Server (Passive) - HA構成時のみ
  terracotta-passive:
    image: terracotta/ehcache-terracotta-server:3.10.8
    volumes:
      - ./terracotta/tc-config.xml:/terracotta/server/conf/tc-config.xml:ro
    environment:
      - DEFAULT_ACTIVATE=true
    depends_on:
      terracotta-active:
        condition: service_healthy
```

Core サービスに依存関係を追加:

```yaml
  core:
    depends_on:
      couchdb:
        condition: service_healthy
      solr:
        condition: service_healthy
      terracotta-active:
        condition: service_healthy
```

---

## Step 3: 内部動作の説明

### CacheService の設定駆動アーキテクチャ

`CacheService` コンストラクタは `cache.clustering.enabled` プロパティに基づいて動作を切り替えます:

- **`false`（デフォルト）**: 従来通りローカルヒープのみの `CacheManager` を構築
- **`true`**: リフレクション経由で `ClusteringServiceConfigurationBuilder` を呼び出し、Terracotta 接続付きの `CacheManager` を構築

リフレクションを使用するため、`ehcache-clustered` JAR がクラスパスになくてもスタンドアロンモードで動作可能です。

### フォールバック動作

Terracotta 接続に失敗した場合:
1. エラーログを出力
2. ローカルヒープのみのスタンドアロンモードにフォールバック
3. NemakiWare は正常に動作を継続

### リソースプール構成

| モード | 構成 |
|--------|------|
| スタンドアロン | `heap(N)` — ヒープのみ |
| クラスタリング | `heap(N).offheap(M, MB)` — ヒープ（L1） + オフヒープ（L2） |

---

## Step 4: シリアライゼーション対応（実装済み）

Terracotta クラスタリングではキャッシュオブジェクトのシリアライズが必要です。
以下のクラスに `Serializable` を実装済みです:

| クラス | 対応 |
|--------|------|
| `NodeBase` → 全サブクラス（Content, Document, Folder, UserItem, GroupItem 等） | `implements Serializable` + `serialVersionUID` |
| `Acl` | `implements Serializable` + `serialVersionUID` |
| `Ace` | `implements Serializable` + `serialVersionUID` |
| `Tree` | `implements Serializable` + `serialVersionUID` |

**注意**: `ObjectData` は OpenCMIS の `ObjectDataImpl` が `Serializable` を実装しています。

---

## Step 5: デプロイと検証

### 5.1 ビルド

```bash
cd core/src/main/webapp/ui && npm run build
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
cp core/target/core.war docker/core/core.war
```

### 5.2 起動

```bash
cd docker
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

### 5.3 Terracotta 接続確認

```bash
# Terracotta サーバーの起動確認
docker logs docker-terracotta-active-1 --tail 20

# Core ログでクラスタ接続を確認
docker logs docker-core-1 2>&1 | grep -i "terracotta\|cluster"
# 期待: "Ehcache clustering enabled: terracotta://..."
```

### 5.4 テスト

```bash
# QA テスト
./qa-test.sh

# TCK テスト
timeout 300s mvn test -Dtest=BasicsTestGroup,TypesTestGroup,ControlTestGroup,VersioningTestGroup \
  -f core/pom.xml -Pdevelopment

# キャッシュエッジケーステスト
bash /tmp/cache-edge-test.sh
```

### 5.5 マルチノード検証

2つの Core ノードを起動して以下を確認:

1. **ノード1でドキュメント作成 → ノード2で即座に取得可能か**
2. **ノード1でタイプ定義更新 → ノード2でキャッシュが更新されるか**
3. **ノード1でACL変更 → ノード2で即座に反映されるか**
4. **Terracotta Active停止 → Passive昇格後もキャッシュ継続するか**

---

## クラスタリング対象キャッシュの推奨構成

| キャッシュ | クラスタ | 理由 |
|-----------|---------|------|
| configCache | × | ノード起動時に1回読み込むのみ。eternal設定。 |
| objectDataCache | ○ | 読み取り頻度高。 |
| contentCache | ○ | ドキュメントメタデータ。更新時にキャッシュ無効化済み。 |
| typeCache | ○ | タイプ定義変更は全ノードで即座に反映すべき。 |
| aclCache | ○ | ACL変更はセキュリティに直結。 |
| userCache | ○ | ユーザー情報。 |
| usersCache | ○ | ユーザー一覧。 |
| groupCache | ○ | グループ情報。 |
| groupsCache | ○ | グループ一覧。 |
| joinedGroupCache | ○ | グループ所属情報。 |
| propertyDefinitionCache | ○ | プロパティ定義。 |
| versionSeriesCache | ○ | バージョン管理メタデータ。 |
| attachmentCache | × | バイナリ参照のみ。CouchDB直接取得でOK。 |
| treeCache | × | 現在無効化済み。 |
| changeEventCache | × | ノードローカルで十分。 |
| latestChangeTokenCache | × | ノードごとに独立管理。 |
| propertisCache | × | 用途限定的。ローカルで十分。 |

---

## トラブルシューティング

### Terracotta接続失敗

```
Ehcache clustering requested but ehcache-clustered JAR not found.
```

→ `ehcache-clustered:3.10.8` がクラスパスに含まれているか確認。
  pom.xml で `<optional>true</optional>` になっている場合、WAR内に含まれません。
  クラスタリング利用時は `<optional>false</optional>` に変更してください。

### シリアライゼーションエラー

```
java.io.NotSerializableException: jp.aegif.nemaki.model.Content
```

→ 対象モデルクラスに `implements Serializable` と `serialVersionUID` が追加済みか確認。

### offheap 不足

```
org.ehcache.clustered.common.internal.exceptions.ResourceBusyException
```

→ `tc-config.xml` の offheap サイズを増加。
→ `cache.clustering.offheap.mb` の値を確認。

### パフォーマンス劣化

クラスタリング有効化後に性能低下する場合:
- ローカル heap 層のサイズを増加（L1ヒット率向上）
- ネットワークレイテンシの確認
- 不要なキャッシュのクラスタリングを無効化

---

## 参考資料

- [Ehcache 3.10 Clustered Cache Documentation](https://www.ehcache.org/documentation/3.10/clustered-cache.html)
- [Terracotta Getting Started](https://www.terracotta.org/)
- [ehcache3-samples (GitHub)](https://github.com/ehcache/ehcache3-samples/tree/master/clustered)
- [Docker Hub - ehcache-terracotta-server](https://hub.docker.com/r/terracotta/ehcache-terracotta-server)
