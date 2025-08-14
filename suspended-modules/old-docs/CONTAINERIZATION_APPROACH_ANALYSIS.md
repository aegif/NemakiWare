# コンテナ化アプローチ詳細分析

## 概要

コンテナ化アプローチでは、Solr関連サービスのみをJava 11+環境で実行し、他のコンポーネントはJava 8を維持する戦略です。しかし、詳細分析により、CoreモジュールもSolr 9.x依存のため、Java 11+が必要であることが判明しました。

## コンテナ構成分析

### 現在のNemakiWareアーキテクチャ
```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│   UI Module     │  │   Core Module   │  │  Solr Module    │
│   (Play/Scala)  │  │   (Java/CMIS)   │  │   (Solr/Java)   │
│                 │  │                 │  │                 │
│   Java 8 OK     │  │   Java 8 現在   │  │   Java 8 現在   │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

### コンテナ化後の必要構成
```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│   UI Module     │  │   Core Module   │  │  Solr Module    │
│   (Play/Scala)  │  │   (Java/CMIS)   │  │   (Solr/Java)   │
│                 │  │                 │  │                 │
│   Java 8 維持   │  │  Java 11+ 必須  │  │  Java 11+ 必須  │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

## Java 11+が必要なモジュール

### 1. Solrモジュール
**理由**: Solr 9.x直接使用
- `solr/pom.xml`: Solr 4.10.4 → 9.8.0
- 主要クラス: `CoreTracker.java`, `NemakiCoreAdminHandler.java`
- **Java 11+必須**

### 2. Coreモジュール ⚠️
**理由**: Solr Client API使用
- `core/pom.xml`: solr-solrj 4.0.0 → 9.8.0使用
- 主要クラス: 
  - `SolrUtil.java` - HttpSolrClient使用
  - `SolrQueryProcessor.java` - Solr検索処理
  - `SolrPredicateWalker.java` - Solr クエリ構築
- **Java 11+必須**

### 3. UIモジュール
**理由**: Solr依存なし
- Play Framework使用
- Solrとの直接的な依存関係なし
- **Java 8維持可能**

## コンテナ化戦略の詳細

### オプション1: 部分コンテナ化
```yaml
# docker-compose.yml
services:
  ui:
    image: openjdk:8-jdk
    # UIモジュールのみJava 8維持
    
  core-solr:
    image: openjdk:11-jdk
    # CoreとSolrを統合コンテナでJava 11+
    
  database:
    image: couchdb:latest
    # データベースは影響なし
```

**メリット**:
- UIモジュールはJava 8維持
- 統合コンテナで管理簡素化

**デメリット**:
- CoreとSolrの分離ができない
- デプロイメント複雑化

### オプション2: 完全分離コンテナ化
```yaml
# docker-compose.yml
services:
  ui:
    image: openjdk:8-jdk
    # UIモジュール
    
  core:
    image: openjdk:11-jdk
    # CoreモジュールのみJava 11+
    
  solr:
    image: solr:9.8.0
    # 公式Solr 9.xイメージ使用
    
  database:
    image: couchdb:latest
```

**メリット**:
- 各サービス完全分離
- 公式Solrイメージ活用
- スケーラビリティ向上

**デメリット**:
- ネットワーク設定複雑化
- サービス間通信オーバーヘッド

## 実装要件

### Java 11+移行が必要なファイル
```
core/pom.xml                           # Solr依存関係更新
core/src/main/java/.../SolrUtil.java   # SolrClient API
core/src/main/java/.../SolrQueryProcessor.java
core/src/main/java/.../SolrPredicateWalker.java
solr/pom.xml                           # Solr 9.x更新
solr/src/main/java/.../CoreTracker.java
solr/src/main/java/.../NemakiCoreAdminHandler.java
```

### Java 8維持可能なファイル
```
ui/                                    # UIモジュール全体
action/                                # アクションモジュール
action-sample/                         # サンプルアクション
setup/installer/                       # インストーラー
```

## リスク評価

### 技術的リスク
| リスク要因 | 確率 | 影響度 | 対策 |
|------------|------|--------|------|
| Core-Solr間通信問題 | 中 | 高 | 詳細なAPI設計とテスト |
| コンテナ間ネットワーク | 中 | 中 | Docker Compose設定最適化 |
| Java 11+互換性問題 | 低 | 高 | 段階的移行とテスト |
| パフォーマンス劣化 | 中 | 中 | ベンチマークテスト実施 |

### 運用リスク
| リスク要因 | 確率 | 影響度 | 対策 |
|------------|------|--------|------|
| デプロイメント複雑化 | 高 | 中 | 自動化スクリプト整備 |
| 監視・ログ管理 | 中 | 中 | 統合監視システム構築 |
| 障害切り分け困難 | 中 | 高 | 詳細なヘルスチェック |

## 実装タイムライン

### フェーズ1: 環境準備 (1-2週間)
- [ ] Docker環境でJava 11+テスト環境構築
- [ ] Core + Solrモジュールの依存関係分析
- [ ] コンテナ間通信設計

### フェーズ2: API移行 (2-3週間)
- [ ] SolrServer → SolrClient API移行
- [ ] 例外処理更新 (SolrServerException → SolrClientException)
- [ ] 設定ファイル更新 (LUCENE_40 → 9.8.0)

### フェーズ3: コンテナ化実装 (1-2週間)
- [ ] Dockerfileとdocker-compose.yml作成
- [ ] ネットワーク設定とサービス間通信
- [ ] 環境変数とシークレット管理

### フェーズ4: テスト・検証 (1-2週間)
- [ ] 単体テスト (各コンテナ)
- [ ] 統合テスト (サービス間通信)
- [ ] パフォーマンステスト
- [ ] 本番環境デプロイテスト

## 結論

コンテナ化アプローチでも、**CoreモジュールとSolrモジュールの両方でJava 11+が必須**です。

### 実際の効果
- **Java 8維持**: UIモジュールのみ (全体の約30%)
- **Java 11+必須**: Core + Solrモジュール (全体の約70%)

### 推奨判断基準
- **UIモジュールの独立性を重視** → コンテナ化アプローチ
- **システム全体の一貫性を重視** → 包括的Java 11+移行
- **段階的移行を重視** → 中間バージョン経由アプローチ

コンテナ化アプローチは部分的なJava 8維持を可能にしますが、主要な業務ロジック（Core）とデータ処理（Solr）はJava 11+移行が避けられません。
