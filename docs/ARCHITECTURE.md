# システムアーキテクチャ

NemakiWare 3.1.0 のシステムアーキテクチャ概要です。

## 目次

1. [システム構成図](#1-システム構成図)
2. [コンポーネント説明](#2-コンポーネント説明)
3. [データフロー](#3-データフロー)
4. [RAG パイプライン](#4-rag-パイプライン)
5. [認証フロー](#5-認証フロー)
6. [ディレクトリ構成](#6-ディレクトリ構成)
7. [データベース設計](#7-データベース設計)

---

## 1. システム構成図

### 全体構成

```
┌─────────────────────────────────────────────────────────────────────┐
│                         NemakiWare System                          │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Core (Tomcat 10.1 + JRE 17)               │   │
│  │                                                               │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │   │
│  │  │  React SPA   │  │  CMIS Server │  │    REST API      │   │   │
│  │  │  (Vite 7)    │  │  (OpenCMIS)  │  │  (JAX-RS)        │   │   │
│  │  │              │  │              │  │                    │   │   │
│  │  │  - Ant Design│  │  - AtomPub   │  │  - User/Group     │   │   │
│  │  │  - TypeScript│  │  - Browser   │  │  - Auth Token     │   │   │
│  │  │  - i18n      │  │  - WSDL      │  │  - Archive        │   │   │
│  │  └──────────────┘  └──────┬───────┘  │  - Type Mgmt      │   │   │
│  │                           │           │  - Cloud Sync      │   │   │
│  │  ┌────────────────────────┼───────────┴──────────────────┐   │   │
│  │  │              Spring Service Layer                      │   │   │
│  │  │                                                        │   │   │
│  │  │  ObjectService / RepositoryService / AclService        │   │   │
│  │  │  DiscoveryService / NavigationService / PolicyService  │   │   │
│  │  │  CloudAuthService / DirectorySyncService / RAGService  │   │   │
│  │  └───────────┬────────────────────────┬───────────────────┘   │   │
│  │              │                        │                        │   │
│  │  ┌───────────▼──────────┐  ┌─────────▼────────────────┐      │   │
│  │  │    DAO Layer         │  │    Cache (EhCache)       │      │   │
│  │  │    (CouchDB impl)   │  │    - Content Cache        │      │   │
│  │  │                      │  │    - ACL Cache            │      │   │
│  │  └───────────┬──────────┘  │    - Type Cache           │      │   │
│  │              │              └──────────────────────────┘      │   │
│  └──────────────┼────────────────────────────────────────────────┘   │
│                 │                                                     │
│  ┌──────────────▼──────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │    CouchDB 3.x      │  │  Solr 9.x    │  │   TEI Server      │   │
│  │                      │  │              │  │   (HuggingFace)   │   │
│  │  - bedroom (docs)   │  │  - Full-text │  │                    │   │
│  │  - bedroom_closet   │  │  - Solr Cell │  │  - multilingual    │   │
│  │  - canopy           │  │  - Tika 2.9  │  │    -e5-large       │   │
│  │  - nemaki_conf      │  │  - Dense     │  │  - 1024 dim        │   │
│  │                      │  │    Vector    │  │  - CPU/GPU         │   │
│  └──────────────────────┘  └──────────────┘  └───────────────────┘   │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

### Docker Compose サービス構成

```
docker-compose-simple.yml
│
├── couchdb (:5984)     ── CouchDB 3.3.3
├── solr    (:8983)     ── カスタム Solr 9.x
├── core    (:8080)     ── Tomcat 10.1 + Core WAR
│   depends_on: couchdb (healthy), solr (healthy)
│
└── [profile: rag]
    └── tei  (:8081)    ── HuggingFace TEI cpu-1.6
```

---

## 2. コンポーネント説明

### Core (CMIS サーバー + React UI)

NemakiWare のメインコンポーネント。Tomcat 10.1 上で動作する WAR アプリケーション。

| レイヤー | 技術 | 役割 |
|----------|------|------|
| **UI** | React 18, TypeScript, Vite 7, Ant Design 5 | ブラウザベースの管理インターフェース |
| **CMIS Binding** | Apache Chemistry OpenCMIS 1.1.0 | CMIS 1.1 プロトコル実装 (AtomPub, Browser, WSDL) |
| **REST API** | JAX-RS (Jersey) | ユーザー管理、認証、アーカイブ等の拡張 API |
| **Service** | Spring Framework 6 | ビジネスロジック (CMIS操作、権限、検索、同期) |
| **DAO** | CouchDB クライアント (Ektorp) | データアクセス抽象化 |
| **Cache** | EhCache 3 | コンテンツ・ACL・タイプ定義のキャッシュ |

### CouchDB

ドキュメント指向 NoSQL データベース。NemakiWare の全データを格納。

| データベース | 用途 |
|-------------|------|
| `bedroom` | メインドキュメントリポジトリ |
| `bedroom_closet` | bedroom のアーカイブ（削除されたドキュメント） |
| `canopy` | マルチリポジトリ管理 |
| `canopy_closet` | canopy のアーカイブ |
| `nemaki_conf` | システム設定 |

### Solr

Apache Solr 9.x ベースの全文検索エンジン。

- **Solr Cell** (ExtractingRequestHandler + Tika 2.9): PDF, Office 等のテキスト抽出
- **DenseVector**: RAG 用ベクトルフィールド（1024次元）
- コアごとにリポジトリのインデックスを管理

### TEI (Text Embeddings Inference)

Hugging Face のベクトル埋め込みサーバー。RAG セマンティック検索に使用。

- **モデル**: `intfloat/multilingual-e5-large` (1024次元)
- **用途**: ドキュメントチャンクのベクトル化、検索クエリのベクトル化
- **デプロイ**: Docker Compose `rag` プロファイルで有効化

---

## 3. データフロー

### ドキュメント作成フロー

```
Client (React UI / CMIS Client)
  │
  │  CMIS createDocument (Browser Binding POST)
  ▼
Core: NemakiBrowserBindingServlet
  │
  ▼
Core: ObjectServiceImpl.createDocument()
  │
  ├──▶ CouchDB: ドキュメントメタデータ保存
  │      (cmis:objectId, cmis:name, properties, ACL)
  │
  ├──▶ CouchDB: コンテンツストリーム保存
  │      (attachmentとしてバイナリ格納)
  │
  ├──▶ Solr: インデックス更新
  │      (メタデータ + Tika によるテキスト抽出)
  │
  └──▶ [RAG 有効時] TEI: ベクトル埋め込み生成
         (チャンク分割 → TEI API → Solr DenseVector 格納)
```

### 検索フロー

```
Client: 検索クエリ
  │
  ▼
Core: DiscoveryServiceImpl.query()
  │
  ├──▶ [通常検索] Solr: 全文検索 (CMIS SQL → Solr Query)
  │      結果: objectId のリスト
  │
  └──▶ [RAG 検索] TEI: クエリベクトル化
         │
         ▼
       Solr: ベクトル類似度検索 (kNN)
         結果: objectId + スコアのリスト
  │
  ▼
Core: CouchDB からドキュメントメタデータ取得
  │
  ▼
Core: 権限チェック (ACL 評価)
  │
  ▼
Client: 検索結果
```

---

## 4. RAG パイプライン

### インデックス時（ドキュメントアップロード）

```
Document Upload
  │
  ▼
Content Stream (PDF, Word, Excel, etc.)
  │
  ▼
テキスト抽出 (Tika / Solr Cell)
  │
  ▼
チャンク分割
  ├── max_tokens: 200
  ├── overlap_tokens: 50
  └── min_tokens: 50
  │
  ▼
TEI API: /embed (バッチ処理)
  ├── batch_size: 32
  └── model: intfloat/multilingual-e5-large
  │
  ▼
1024次元ベクトル生成
  │
  ▼
Solr DenseVector フィールドに格納
  └── チャンクごとに objectId + chunk_index + vector
```

### 検索時（セマンティック検索）

```
ユーザー検索クエリ
  │
  ▼
TEI API: /embed (クエリベクトル化)
  │
  ▼
Solr kNN 検索
  ├── topK: 10
  └── similarity_threshold: 0.7
  │
  ▼
類似チャンクのリスト (objectId + score)
  │
  ▼
重複除去 + ドキュメント単位に集約
  │
  ▼
ACL フィルタリング (権限のないドキュメントを除外)
  │
  ▼
検索結果
```

### 対応ファイル形式

| カテゴリ | MIME タイプ |
|----------|-----------|
| テキスト | text/plain, text/html, text/xml |
| PDF | application/pdf |
| Word | application/msword, application/vnd.openxmlformats-officedocument.wordprocessingml.document |
| Excel | application/vnd.ms-excel, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet |
| PowerPoint | application/vnd.ms-powerpoint, application/vnd.openxmlformats-officedocument.presentationml.presentation |

---

## 5. 認証フロー

### NemakiWare がサポートする認証方式

| 方式 | 設定キー | IdP |
|------|---------|-----|
| **Basic 認証** | (デフォルト) | NemakiWare 内蔵 |
| **Auth Token** | `capability.extended.auth.token=true` | NemakiWare 内蔵 |
| **Google OIDC** | `cloud.auth.google.enabled=true` | Google (直接) |
| **Microsoft OIDC** | `cloud.auth.microsoft.enabled=true` | Microsoft Entra ID (直接) |
| **Keycloak OIDC** | `oidc.enabled=true` | Keycloak |
| **Keycloak SAML** | `saml.enabled=true` | Keycloak |

### Google OIDC 認証フロー（Keycloakなし）

```
ブラウザ (React UI)
  │
  │  1. 「Googleでログイン」クリック
  ▼
Google OAuth 2.0 認証画面
  │
  │  2. ユーザーが Google アカウントで認証
  ▼
Google → NemakiWare コールバック
  │  Authorization Code 付きリダイレクト
  │  /core/rest/repo/bedroom/authtoken/oidc/callback
  ▼
Core: AuthTokenResource.oidcCallback()
  │
  │  3. Authorization Code → ID Token 交換
  │     (Google Token Endpoint へサーバーサイドリクエスト)
  ▼
Core: CloudAuthService.verifyGoogleIdToken()
  │
  │  4. ID Token 検証
  │     - 署名検証 (Google 公開鍵)
  │     - iss, aud, exp チェック
  ▼
Core: ユーザー検索 / 自動作成
  │  email をキーにユーザーを検索
  │  存在しない場合は自動作成 (isAutoCreateUser=true)
  ▼
Core: NemakiWare Auth Token 発行
  │
  ▼
ブラウザ: Auth Token を Cookie に保存
  │
  ▼
以降の API リクエストは Auth Token で認証
```

### Auth Token 認証フロー

```
ブラウザ
  │
  │  POST /core/rest/repo/{repoId}/authtoken
  │  Basic Auth: admin:admin
  ▼
Core: AuthTokenResource.createToken()
  │  - BCrypt パスワード検証
  │  - JWT Auth Token 生成 (有効期限: 24h)
  ▼
ブラウザ: Auth Token を Cookie/Header に設定
  │
  │  以降のリクエスト
  │  Authorization: Bearer <auth-token>
  │  または Cookie: NemakiWareToken=<auth-token>
  ▼
Core: NemakiAuthCallContextHandler
  │  - Auth Token 検証
  │  - ユーザー情報取得
  ▼
CMIS サービス実行
```

---

## 6. ディレクトリ構成

### プロジェクトルート

```
NemakiWare/
├── core/                        # メイン CMIS サーバーモジュール (WAR)
│   ├── src/main/java/jp/aegif/nemaki/
│   │   ├── cmis/               # CMIS サーバー実装
│   │   │   ├── factory/        # CMIS サービスファクトリ
│   │   │   │   └── auth/       # 認証ハンドラ (Basic, Token, OIDC)
│   │   │   ├── service/        # CMIS サービス実装
│   │   │   │   └── impl/       # ObjectService, AclService 等
│   │   │   └── servlet/        # CMIS サーブレット定義
│   │   ├── rest/               # REST API エンドポイント
│   │   │   ├── AuthTokenResource.java
│   │   │   ├── UserResource.java
│   │   │   ├── GroupResource.java
│   │   │   └── TypeResource.java
│   │   ├── dao/                # データアクセス層
│   │   │   └── impl/couch/     # CouchDB 実装
│   │   ├── model/              # ドメインモデル
│   │   ├── businesslogic/      # ビジネスロジック
│   │   ├── util/               # ユーティリティ
│   │   ├── patch/              # DB マイグレーション
│   │   └── webhook/            # Webhook ディスパッチャー
│   │
│   ├── src/main/webapp/
│   │   ├── ui/                 # React SPA フロントエンド
│   │   │   ├── src/
│   │   │   │   ├── components/ # React コンポーネント
│   │   │   │   ├── services/   # API サービス層
│   │   │   │   │   ├── auth.ts            # 認証サービス
│   │   │   │   │   ├── cmis.ts            # CMIS API クライアント
│   │   │   │   │   └── cloud-drive.ts     # Cloud Drive 連携
│   │   │   │   ├── i18n/       # 国際化 (ja, en)
│   │   │   │   └── types/      # TypeScript 型定義
│   │   │   ├── tests/          # Playwright E2E テスト
│   │   │   ├── package.json
│   │   │   └── vite.config.ts
│   │   └── WEB-INF/
│   │       └── classes/        # Spring XML 設定ファイル
│   │
│   └── pom.xml
│
├── common/                      # 共有ユーティリティ
├── solr/                        # Solr 設定・カスタマイズ
├── docker/                      # Docker Compose 構成
│   ├── docker-compose-simple.yml
│   ├── core/                   # Core コンテナ設定
│   │   ├── Dockerfile.simple
│   │   ├── nemakiware.properties
│   │   └── repositories.yml
│   ├── solr/                   # Solr コンテナ設定
│   └── secrets/                # シークレットファイル (Git 管理外)
├── lib/                         # Jakarta EE 変換済みカスタム JAR
├── docs/                        # ドキュメント
│   ├── ARCHITECTURE.md          # 本ドキュメント
│   ├── AWS-DEPLOYMENT-GUIDE.md  # AWS デプロイガイド
│   └── CLOUD_INTEGRATION.md     # クラウド統合設定ガイド
└── qa-test.sh                   # QA 統合テストスクリプト
```

### 主要ソースコードの場所

| 機能 | パス |
|------|------|
| CMIS サービス実装 | `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/` |
| 認証ハンドラ | `core/src/main/java/jp/aegif/nemaki/cmis/factory/auth/` |
| REST API | `core/src/main/java/jp/aegif/nemaki/rest/` |
| CouchDB DAO | `core/src/main/java/jp/aegif/nemaki/dao/impl/couch/` |
| ドメインモデル | `core/src/main/java/jp/aegif/nemaki/model/` |
| Spring 設定 | `core/src/main/webapp/WEB-INF/classes/*.xml` |
| React コンポーネント | `core/src/main/webapp/ui/src/components/` |
| CMIS API クライアント | `core/src/main/webapp/ui/src/services/cmis.ts` |
| 認証サービス | `core/src/main/webapp/ui/src/services/auth.ts` |
| i18n 翻訳 | `core/src/main/webapp/ui/src/i18n/locales/` |
| アプリ設定 | `docker/core/nemakiware.properties` |
| リポジトリ定義 | `docker/core/repositories.yml` |

---

## 7. データベース設計

### CouchDB データベース構成

```
CouchDB
├── bedroom            # メインリポジトリ
│   ├── _design/repo   # コンテンツクエリ用ビュー
│   ├── _design/user   # ユーザー/グループ管理用ビュー
│   └── _design/change # 変更ログ用ビュー
│
├── bedroom_closet     # アーカイブ（削除されたドキュメント）
├── canopy             # マルチリポジトリ管理
├── canopy_closet      # canopy アーカイブ
└── nemaki_conf        # システム設定
```

### ドキュメントタイプ

| タイプ | 説明 | 主要フィールド |
|--------|------|--------------|
| **cmis:document** | CMIS ドキュメント | objectId, name, contentStream, versionSeries |
| **cmis:folder** | CMIS フォルダ | objectId, name, parentId |
| **user** | ユーザー | userId, name, password (BCrypt), email |
| **group** | グループ | groupId, name, members[] |
| **type** | タイプ定義 | typeId, parentId, properties[] |
| **change** | 変更ログ | changeType, objectId, changeTime |
| **archive** | アーカイブ | originalId, deletedTime, restorable |

### アーキテクチャの設計原則

- **CMIS ネイティブ**: すべてのデータ操作は CMIS 仕様に準拠
- **Repository パターン**: DAO インターフェースによるデータアクセス抽象化
- **キャッシュ活用**: EhCache によるコンテンツ・ACL・タイプ定義のキャッシュ
- **非同期処理**: Solr インデックス更新、RAG ベクトル生成は非同期実行
- **自動初期化**: PatchService による DB マイグレーション・初期化の自動実行
