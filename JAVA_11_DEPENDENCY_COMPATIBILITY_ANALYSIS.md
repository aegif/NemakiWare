# Java 11 依存関係互換性分析

## 概要

NemakiWareのCore・SolrモジュールをJava 11+環境で動作させるための依存関係互換性分析結果です。

## Coreモジュール依存関係分析 (`core/pom.xml`)

### ✅ Java 11対応済み依存関係

| ライブラリ | 現在バージョン | Java 11対応状況 | 備考 |
|------------|----------------|-----------------|------|
| Jackson | 2.17.1 | ✅ 対応済み | 最新バージョン使用 |
| Jersey | 2.23.2 | ✅ 対応済み | Java 11対応バージョン |
| Spring Framework | 5.3.39 | ✅ 対応済み | 既にアップグレード済み |
| JUnit | 4.12 | ✅ 対応済み | Java 11互換 |
| SLF4J | 1.7.21 | ✅ 対応済み | Java 11互換 |
| Guava | 24.1.1-jre | ✅ 対応済み | JREバージョン使用 |
| Commons Collections4 | 4.4 | ✅ 対応済み | 最新バージョン |
| Joda Time | 2.9.3 | ✅ 対応済み | Java 11互換 |
| EhCache | 2.10.2 | ✅ 対応済み | Java 11対応 |
| Dom4j | 2.1.3 | ✅ 対応済み | 最新バージョン |
| Apache Tika | 1.28.5 | ✅ 対応済み | Java 11対応 |
| OpenCMIS | 1.1.0 | ✅ 対応済み | Java 11互換 |
| Solr/Lucene | 9.8.0 | ✅ 対応済み | Java 11+必須 |

### ⚠️ アップグレード推奨依存関係

| ライブラリ | 現在バージョン | 推奨バージョン | 理由 |
|------------|----------------|----------------|------|
| **Logback** | 1.1.3 | 1.2.12+ | Java 11最適化、セキュリティ修正 |
| **AspectJ** | 1.8.4 | 1.9.19+ | **Java 11必須要件** |
| **Logstash Logback Encoder** | 3.4 | 7.4+ | Java 11対応、機能向上 |
| **Commons Codec** | 1.10 | 1.15+ | Java 11最適化 |

### ❌ 潜在的問題依存関係

| ライブラリ | 現在バージョン | 問題 | 対策 |
|------------|----------------|------|------|
| **Jersey Test Framework** | 1.12 | 古いバージョン | 2.x系への移行検討 |
| **JVYaml** | 0.2.1 | 非常に古い | SnakeYAMLへの移行検討 |
| **JBCrypt** | 0.3m | 古いバージョン | 0.4+への更新 |

## Solrモジュール依存関係分析 (`solr/pom.xml`)

### ✅ Java 11対応済み依存関係

| ライブラリ | 現在バージョン | Java 11対応状況 | 備考 |
|------------|----------------|-----------------|------|
| **Solr/Lucene** | 4.10.4 | ❌ → 9.8.0必須 | Java 11+必須 |
| **Quartz** | 2.3.2 | ✅ 対応済み | Java 11互換 |
| **Jackson** | 2.8.11 | ✅ 対応済み | Java 11互換（古いが動作） |
| **Apache Tika** | 1.28.5 | ✅ 対応済み | Java 11対応 |
| **Commons Collections4** | 4.4 | ✅ 対応済み | 最新バージョン |
| **Commons IO** | 2.11.0 | ✅ 対応済み | Java 11対応 |
| **OpenCMIS** | 1.1.0 | ✅ 対応済み | Java 11互換 |
| **Ektorp** | 1.5.0 | ✅ 対応済み | Java 11互換 |

### ⚠️ アップグレード推奨依存関係

| ライブラリ | 現在バージョン | 推奨バージョン | 理由 |
|------------|----------------|----------------|------|
| **Logback** | 1.1.7 | 1.2.12+ | Java 11最適化 |
| **Jackson** | 2.8.11 | 2.15.0+ | セキュリティ、パフォーマンス向上 |
| **SLF4J** | 1.7.21 | 1.7.36+ | Java 11最適化 |
| **Commons Lang3** | 3.0.1 | 3.12.0+ | Java 11対応 |

### ❌ 重要な問題依存関係

| ライブラリ | 現在バージョン | 問題 | 対策 |
|------------|----------------|------|------|
| **Jersey Client** | 1.19 | **Java 11非対応** | **2.x系への移行必須** |
| **Restlet** | 2.1.1 | **Java 11対応不明** | **互換性検証必須** |
| **JSON Simple** | 1.1 | 非常に古い | Jackson使用への統一 |

## 重要な互換性問題

### 1. AspectJ 1.8.4 → 1.9.19+ (必須)
```xml
<!-- 現在 -->
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
    <version>1.8.4</version>
</dependency>

<!-- Java 11対応 -->
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
    <version>1.9.19</version>
</dependency>
```

### 2. Jersey Client 1.19 → 2.x系 (必須)
```xml
<!-- 現在 (Solrモジュール) -->
<dependency>
    <groupId>com.sun.jersey</groupId>
    <artifactId>jersey-client</artifactId>
    <version>1.19</version>
</dependency>

<!-- Java 11対応 -->
<dependency>
    <groupId>org.glassfish.jersey.core</groupId>
    <artifactId>jersey-client</artifactId>
    <version>2.40</version>
</dependency>
```

### 3. Restlet 2.1.1 互換性検証
- **問題**: Restlet 2.1.1のJava 11対応状況が不明
- **影響**: Solrモジュールで使用中
- **対策**: 
  1. Restlet 2.4.x系への更新検討
  2. 代替ライブラリ（Spring WebClient等）への移行検討

## アップグレード優先度

### 🔴 最高優先度 (Java 11動作に必須)
1. **AspectJ**: 1.8.4 → 1.9.19+
2. **Jersey Client**: 1.19 → 2.40+
3. **Restlet**: 2.1.1 → 2.4.x+ (互換性検証後)

### 🟡 高優先度 (推奨)
1. **Logback**: 1.1.3/1.1.7 → 1.2.12+
2. **Jackson (Solr)**: 2.8.11 → 2.15.0+
3. **Commons Lang3**: 3.0.1 → 3.12.0+

### 🟢 中優先度 (任意)
1. **SLF4J**: 1.7.21 → 1.7.36+
2. **Commons Codec**: 1.10 → 1.15+
3. **JBCrypt**: 0.3m → 0.4+

## 移行リスク評価

### 高リスク
- **Jersey 1.x → 2.x**: API変更あり、コード修正必要
- **Restlet**: バージョン互換性不明、動作検証必須

### 中リスク
- **AspectJ**: 設定変更の可能性
- **Jackson**: マイナーAPI変更の可能性

### 低リスク
- **Logback**: 後方互換性あり
- **Commons系**: 後方互換性あり

## 推奨移行戦略

### フェーズ1: 必須アップグレード
1. AspectJ 1.9.19+への更新
2. Jersey Client 2.x系への移行
3. Restlet互換性検証・更新

### フェーズ2: 推奨アップグレード
1. Logback 1.2.x系への更新
2. Jackson統一・更新
3. Commons系ライブラリ更新

### フェーズ3: 最適化
1. 古い依存関係の整理
2. 重複ライブラリの統一
3. セキュリティ更新

## 検証方法

### 1. 依存関係競合チェック
```bash
mvn dependency:tree
mvn dependency:analyze
```

### 2. Java 11互換性テスト
```bash
mvn clean compile -Dmaven.compiler.source=11 -Dmaven.compiler.target=11
```

### 3. 統合テスト
```bash
mvn clean test -Djava.version=11
```

## 結論

- **必須更新**: 3つの依存関係（AspectJ、Jersey Client、Restlet）
- **推奨更新**: 6つの依存関係（主にバージョンアップ）
- **総合リスク**: 中程度（Jersey 1.x→2.x移行が主要リスク）
- **移行期間**: 2-3週間（テスト含む）

Java 11移行は技術的に実現可能ですが、Jersey ClientとRestletの移行に注意深い対応が必要です。
