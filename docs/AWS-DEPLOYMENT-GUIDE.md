# AWS デプロイガイド

NemakiWare 3.1.0 を AWS 上に本番デプロイするための手順書です。
Google OIDC 認証（Keycloakなし）+ TEI/RAG セマンティック検索構成を対象としています。

## 目次

1. [概要と構成図](#1-概要と構成図)
2. [前提条件](#2-前提条件)
3. [AWS インフラ構築](#3-aws-インフラ構築)
4. [Docker 環境セットアップ](#4-docker-環境セットアップ)
5. [NemakiWare ビルド・デプロイ](#5-nemakiware-ビルドデプロイ)
6. [RAG 検索 (TEI) 設定](#6-rag-検索-tei-設定)
7. [[BETA] RAG 検索 (Bedrock Embedding) 設定](#7-beta-rag-検索-bedrock-embedding-設定)
8. [Google OIDC 認証設定](#8-google-oidc-認証設定keycloakなし)
9. [Google Workspace ディレクトリ同期設定](#9-google-workspace-ディレクトリ同期設定)
10. [SSL/TLS + リバースプロキシ](#10-ssltls--リバースプロキシ)
11. [データ永続化とバックアップ](#11-データ永続化とバックアップ)
12. [[BETA] S3 コールドストレージ設定](#12-beta-s3-コールドストレージ設定)
13. [運用・監視](#13-運用監視)
14. [トラブルシューティング](#14-トラブルシューティング)

---

## 1. 概要と構成図

### システム構成

NemakiWare は以下の4サービスで構成されます。すべて Docker Compose で管理します。

```
                        ┌─────────────────────────────────────────────┐
                        │              EC2 Instance                   │
                        │              (t3.xlarge)                    │
                        │                                             │
   Internet             │  ┌─────────┐    ┌────────────────────────┐  │
   ───────────┐         │  │  Nginx  │    │  Docker Compose        │  │
              │         │  │  :443   │───▶│                        │  │
   HTTPS :443 │         │  │  :80    │    │  ┌──────┐  ┌────────┐  │  │
              ▼         │  └─────────┘    │  │ Core │  │CouchDB │  │  │
   ┌──────────────┐     │                 │  │ :8080│  │ :5984  │  │  │
   │     ALB      │────▶│                 │  └──┬───┘  └────────┘  │  │
   │  (optional)  │     │                 │     │                   │  │
   └──────────────┘     │                 │  ┌──┴───┐  ┌────────┐  │  │
                        │                 │  │ Solr │  │  TEI   │  │  │
                        │                 │  │ :8983│  │  :8081 │  │  │
                        │                 │  └──────┘  └────────┘  │  │
                        │                 └────────────────────────┘  │
                        │                                             │
                        │  EBS gp3 50GB+                              │
                        └─────────────────────────────────────────────┘

    Google Cloud Platform
   ┌───────────────────────┐
   │  OAuth 2.0 / OIDC     │◀── ID Token 検証
   │  Admin SDK API        │◀── ディレクトリ同期
   └───────────────────────┘
```

### コンポーネント一覧

| サービス | イメージ | ポート | 説明 |
|----------|---------|--------|------|
| **Core** | tomcat:10.1-jre17 (カスタム) | 8080 | CMIS サーバー + React SPA UI |
| **CouchDB** | couchdb:3.3.3 | 5984 | ドキュメントデータベース |
| **Solr** | カスタムビルド | 8983 | 全文検索エンジン |
| **TEI** | ghcr.io/huggingface/text-embeddings-inference:cpu-1.6 | 8081 | ベクトル埋め込みサーバー (RAG) |

---

## 2. 前提条件

### 必要なアカウント・リソース

- **AWS アカウント** — EC2, VPC, EBS, (オプション) ALB, ACM, Route 53 の操作権限
- **Google Cloud Console プロジェクト** — OAuth クライアント ID 発行済み（[CLOUD_INTEGRATION.md](CLOUD_INTEGRATION.md) 参照）
- **独自ドメイン** — HTTPS 必須のため（例: `nemakiware.example.com`）
- **ローカル開発環境** — Java 17, Maven 3.6+, Node.js 18+, Docker

### 推奨スペック

| 項目 | 最小 | 推奨 |
|------|------|------|
| インスタンスタイプ | t3.large (2 vCPU, 8GB) | t3.xlarge (4 vCPU, 16GB) |
| EBS ストレージ | 30GB gp3 | 50GB+ gp3 |
| OS | Amazon Linux 2023 / Ubuntu 24.04 | Amazon Linux 2023 |

> **Note**: TEI（ベクトル埋め込みサーバー）はメモリを多く消費します（最大4GB）。RAG を有効にする場合は 16GB 以上のインスタンスを推奨します。

---

## 3. AWS インフラ構築

### 3-1. VPC + サブネット + Security Group

既存の VPC を使用するか、新規に作成します。

#### Security Group 設定

| ルール | ポート | ソース | 説明 |
|--------|--------|--------|------|
| Inbound | 443 (HTTPS) | 0.0.0.0/0 | Web アクセス |
| Inbound | 22 (SSH) | 管理者 IP | SSH 管理用 |
| Inbound | 80 (HTTP) | 0.0.0.0/0 | HTTPS リダイレクト用 |
| Outbound | All | 0.0.0.0/0 | 外部通信（Google API 等） |

> **重要**: CouchDB (5984), Solr (8983), TEI (8081) のポートは **外部に公開しないでください**。Docker 内部ネットワークでのみ通信します。

```bash
# AWS CLI での Security Group 作成例
aws ec2 create-security-group \
  --group-name nemakiware-sg \
  --description "NemakiWare Security Group" \
  --vpc-id vpc-xxxxxxxx

# Inbound ルール追加
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp --port 443 --cidr 0.0.0.0/0

aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp --port 80 --cidr 0.0.0.0/0

aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp --port 22 --cidr YOUR_IP/32
```

### 3-2. EC2 インスタンス

#### インスタンス起動

```bash
aws ec2 run-instances \
  --image-id ami-xxxxxxxx \
  --instance-type t3.xlarge \
  --key-name your-key-pair \
  --security-group-ids sg-xxxxxxxx \
  --subnet-id subnet-xxxxxxxx \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":50,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=nemakiware-prod}]'
```

#### Elastic IP の割り当て（推奨）

```bash
aws ec2 allocate-address --domain vpc
aws ec2 associate-address --instance-id i-xxxxxxxx --allocation-id eipalloc-xxxxxxxx
```

### 3-3. (オプション) ALB + ACM 証明書

ALB を使用する場合は、ACM で SSL 証明書を発行し、ALB のリスナーに設定します。

```bash
# ACM 証明書のリクエスト
aws acm request-certificate \
  --domain-name nemakiware.example.com \
  --validation-method DNS

# ALB 作成 → Target Group → EC2 登録
# Target Group: ポート 8080, ヘルスチェック /core
```

ALB を使用する場合、Nginx は不要です（ALB が SSL 終端を担当）。

### 3-4. (オプション) ECS でのコンテナ運用

より本格的なコンテナオーケストレーションが必要な場合は、ECS (Fargate) への移行を検討できます。

- **ECS Fargate**: サーバーレスコンテナ実行。インフラ管理が不要。
- **ECS EC2**: EC2 上でコンテナを実行。GPUインスタンス（TEI高速化）が使用可能。

> 本ガイドでは EC2 + Docker Compose 構成を対象としています。ECS 構成は別途設計が必要です。

---

## 4. Docker 環境セットアップ

### 4-1. EC2 への SSH 接続

```bash
ssh -i your-key.pem ec2-user@<ELASTIC_IP>
```

### 4-2. Docker CE + Docker Compose インストール

#### Amazon Linux 2023

```bash
# Docker インストール
sudo dnf install -y docker
sudo systemctl enable docker
sudo systemctl start docker
sudo usermod -aG docker ec2-user

# Docker Compose プラグインインストール
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 確認
docker --version
docker compose version
```

#### Ubuntu 24.04

```bash
# Docker 公式リポジトリからインストール
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

sudo usermod -aG docker ubuntu
```

> **Note**: `usermod` 後、一度ログアウト・再ログインしてください。

### 4-3. プロジェクトの配置

```bash
# プロジェクトをクローン（またはビルド済み成果物を転送）
git clone https://github.com/your-org/NemakiWare.git /opt/nemakiware
cd /opt/nemakiware
```

### 4-4. シークレットディレクトリの準備

```bash
mkdir -p /opt/nemakiware/docker/secrets
chmod 700 /opt/nemakiware/docker/secrets

# Google サービスアカウントキーを配置
scp google-service-account.json ec2-user@<IP>:/opt/nemakiware/docker/secrets/

# Microsoft Entra ID 用環境変数ファイル（使用しない場合は空ファイル）
touch /opt/nemakiware/docker/secrets/microsoft-entra.env
```

---

## 5. NemakiWare ビルド・デプロイ

### 5-1. ビルド環境の準備（EC2 上でビルドする場合）

```bash
# Java 17 インストール
sudo dnf install -y java-17-amazon-corretto-devel  # Amazon Linux 2023
# または
sudo apt-get install -y openjdk-17-jdk  # Ubuntu

# Maven インストール
sudo dnf install -y maven  # Amazon Linux 2023
# または
sudo apt-get install -y maven  # Ubuntu

# Node.js 18+ インストール
curl -fsSL https://rpm.nodesource.com/setup_18.x | sudo bash -
sudo dnf install -y nodejs  # Amazon Linux 2023
# または
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt-get install -y nodejs  # Ubuntu
```

### 5-2. UI ビルド

```bash
cd /opt/nemakiware/core/src/main/webapp/ui
npm install
npm run build
```

### 5-3. Core WAR ビルド

```bash
cd /opt/nemakiware
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
```

### 5-4. WAR 配置と Docker 起動

```bash
cp core/target/core.war docker/core/core.war
cd docker
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

### 5-5. 起動確認

```bash
# 起動完了まで待機（90秒程度）
sleep 90

# ヘルスチェック
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# HTTP 200 + XML レスポンスが返れば正常
```

> **重要**: コード変更後の再デプロイ時は必ず `--build --force-recreate` を使用してください。`docker compose restart` では古い WAR のまま動作します。

---

## 6. RAG 検索 (TEI) 設定

### 6-1. RAG プロファイルでの起動

TEI サービスは Docker Compose の `rag` プロファイルに含まれています。

```bash
cd /opt/nemakiware/docker

# 通常サービス + RAG サービスを起動
docker compose -f docker-compose-simple.yml --profile rag up -d --build --force-recreate
```

### 6-2. TEI 設定パラメータ

`nemakiware.properties` での RAG 関連設定:

```properties
### RAG (Retrieval-Augmented Generation) Configuration
# RAG セマンティック検索の有効化
rag.enabled=true

# TEI (Text Embeddings Inference) Service Settings
rag.tei.url=http://tei:80
rag.tei.timeout.connect=5000
rag.tei.timeout.read=120000
rag.tei.batch.size=32
rag.tei.retry.max=3
rag.tei.retry.delay=1000

# Chunking Settings (tokens)
rag.chunking.max.tokens=200
rag.chunking.overlap.tokens=50
rag.chunking.min.tokens=50

# Vector Search Settings
rag.search.topK=10
rag.search.similarity.threshold=0.7

# Indexing Settings
rag.indexing.batch.size=100
rag.indexing.async=true

# Supported MIME types for RAG indexing
rag.supported.mimetypes=text/plain,text/html,text/xml,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-powerpoint,application/vnd.openxmlformats-officedocument.presentationml.presentation
```

### 6-3. 使用モデル

- **モデル**: `intfloat/multilingual-e5-large`
- **次元数**: 1024
- **言語**: 多言語対応（日本語・英語を含む100以上の言語）
- **用途**: ドキュメントのセマンティック検索用ベクトル埋め込み

### 6-4. CPU vs GPU モード

| モード | イメージタグ | メモリ | 推奨用途 |
|--------|-------------|--------|----------|
| **CPU** | `cpu-1.6` | 4GB | 小〜中規模（ドキュメント数千件まで） |
| **GPU** | `1.6` | GPU メモリ依存 | 大規模（GPU インスタンス: g4dn.xlarge 等） |

GPU モードを使用する場合は `docker-compose-simple.yml` の TEI セクションを修正:

```yaml
tei:
  image: ghcr.io/huggingface/text-embeddings-inference:1.6  # GPU版
  # platform: linux/amd64  ← 削除
  deploy:
    resources:
      reservations:
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]
```

### 6-5. TEI ヘルスチェック

```bash
# TEI サーバーの状態確認
curl http://localhost:8081/health
# {"status":"ok"} が返れば正常

# 埋め込み生成テスト
curl -X POST http://localhost:8081/embed \
  -H 'Content-Type: application/json' \
  -d '{"inputs": "テスト文書です"}'
# 1024次元のベクトル配列が返れば正常
```

### 6-6. メモリ制限

`docker-compose-simple.yml` での TEI メモリ設定:

```yaml
deploy:
  resources:
    limits:
      memory: 4G    # 最大メモリ
    reservations:
      memory: 2G    # 予約メモリ
```

> **注意**: `intfloat/multilingual-e5-large` モデルは初回起動時に Hugging Face Hub からダウンロードされます（約1.2GB）。ダウンロード完了まで数分かかります。ダウンロード済みモデルは `tei_cache` ボリュームにキャッシュされます。

---

## 7. [BETA] RAG 検索 (Bedrock Embedding) 設定

> **Beta 機能**: この機能はベータ版です。本番環境での使用前に十分なテストを行ってください。

### 7-1. 概要

Amazon Bedrock の Embedding モデルを使用して、TEI の代わりにベクトル埋め込みを生成できます。
AWS 環境で完結するため、TEI コンテナの運用が不要になります。

| 項目 | TEI (セクション6) | Bedrock Embedding |
|------|-------------------|-------------------|
| **インフラ** | TEI コンテナ (CPU/GPU) | AWS Bedrock API |
| **コスト** | EC2 メモリ/CPU 消費 | API 従量課金 |
| **メモリ使用量** | 2-4GB | なし（API コール） |
| **レイテンシ** | 低（ローカル推論） | 中（API コール） |
| **モデル** | intfloat/multilingual-e5-large | amazon.titan-embed-text-v2:0 |
| **ベクトル次元数** | 1024 | 1024 |
| **多言語対応** | 100言語以上 | 100言語以上 |
| **推奨ケース** | 大量ドキュメント、低レイテンシ重視 | AWS ネイティブ運用、コンテナ削減 |

### 7-2. 前提条件

1. **AWS アカウント**: Bedrock へのアクセス権限
2. **モデルアクセスの有効化**: AWS Console → Amazon Bedrock → Model access で `Titan Text Embeddings V2` を有効化
3. **IAM ロール/認証情報**: EC2 インスタンスロールまたはアクセスキー

### 7-3. IAM ポリシー

EC2 インスタンスロールに以下のポリシーをアタッチします:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:InvokeModel"
            ],
            "Resource": [
                "arn:aws:bedrock:*::foundation-model/amazon.titan-embed-text-v2:0"
            ]
        }
    ]
}
```

### 7-4. nemakiware.properties の設定

```properties
### RAG Embedding Provider
# "tei"（デフォルト）または "bedrock"
rag.embedding.provider=bedrock

### Bedrock Embedding Settings
rag.bedrock.region=ap-northeast-1
rag.bedrock.model.id=amazon.titan-embed-text-v2:0
rag.bedrock.batch.size=25
rag.bedrock.max.input.chars=10000
rag.bedrock.timeout.ms=30000
rag.bedrock.vector.dimension=1024
```

### 7-5. Docker Compose での起動

Bedrock を使用する場合、TEI サービスは不要です。`rag` プロファイルなしで起動できます:

```bash
cd /opt/nemakiware/docker

# TEI なしで起動（Bedrock 使用時）
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

> **Note**: EC2 インスタンスロールを使用する場合、Docker コンテナから EC2 メタデータサービスにアクセスできる必要があります。`docker-compose-simple.yml` でネットワークモードが `bridge` の場合、IMDSv2 のホップ制限を `2` に設定してください:
>
> ```bash
> aws ec2 modify-instance-metadata-options \
>   --instance-id i-xxxxxxxx \
>   --http-put-response-hop-limit 2
> ```

### 7-6. 動作確認

```bash
# RAG 検索が動作することを確認（ドキュメント登録後）
curl -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children"
# ドキュメントが正常に返れば、Bedrock 経由の埋め込み生成が動作しています

# Bedrock API の直接テスト（EC2 上で）
aws bedrock-runtime invoke-model \
  --model-id amazon.titan-embed-text-v2:0 \
  --body '{"inputText": "テスト文書です"}' \
  --region ap-northeast-1 \
  /dev/stdout | jq '.embedding | length'
# 1024 と表示されれば正常
```

---

## 8. Google OIDC 認証設定（Keycloakなし）

NemakiWare 3.1.0 では Keycloak を介さず、Google の OIDC エンドポイントに直接接続して認証できます。

### 8-1. Google Cloud Console での設定

OAuth クライアント ID の作成手順は [CLOUD_INTEGRATION.md の「Google 統合設定」](CLOUD_INTEGRATION.md#google-統合設定) を参照してください。

リダイレクト URI には本番ドメインを追加します:

```
https://nemakiware.example.com/core/rest/repo/bedroom/authtoken/oidc/callback
```

### 8-2. nemakiware.properties の設定

```properties
### Cloud Authentication (Google direct OIDC)
cloud.auth.google.enabled=true
cloud.auth.google.clientId=YOUR_CLIENT_ID.apps.googleusercontent.com
cloud.auth.google.clientSecret=YOUR_CLIENT_SECRET

### SSO UI Configuration
# OIDC ログインボタンを表示
sso.oidc.enabled=true
```

### 8-3. Keycloak 関連設定の無効化

Google 直接認証を使用する場合、Keycloak 経由の OIDC/SAML は無効にします:

```properties
### OIDC (Keycloak) — 無効化
oidc.enabled=false

### SAML (Keycloak) — 無効化
saml.enabled=false
sso.saml.enabled=false
```

### 8-4. Docker 環境変数での設定

`docker-compose-simple.yml` の `CATALINA_OPTS` に以下を追加するか、`.env` ファイルで設定します:

```bash
# docker/.env
CLOUD_AUTH_GOOGLE_ENABLED=true
CLOUD_AUTH_GOOGLE_CLIENT_ID=YOUR_CLIENT_ID.apps.googleusercontent.com
```

> **Note**: クライアントシークレットは `.env` ファイルに記載するか、`nemakiware.properties` に直接設定します。`.env` ファイルは Git にコミットしないでください。

---

## 9. Google Workspace ディレクトリ同期設定

Google Workspace のユーザー・グループを NemakiWare に自動同期します。

### 9-1. 事前準備

サービスアカウントの作成とドメイン全体の委任設定は [CLOUD_INTEGRATION.md の Step 1〜5](CLOUD_INTEGRATION.md#3-ディレクトリ同期-google-workspace) を参照してください。

### 9-2. nemakiware.properties の設定

```properties
### Cloud Directory Sync Configuration
cloud.directory.sync.enabled=true
cloud.directory.sync.providers=google
cloud.directory.sync.cron=0 0 2 * * ?
cloud.directory.sync.window.size=100

# Google Workspace
cloud.directory.sync.google.serviceAccountKey=/usr/local/tomcat/secrets/google-service-account.json
cloud.directory.sync.google.domain=your-domain.com
cloud.directory.sync.google.adminEmail=admin@your-domain.com
```

### 9-3. サービスアカウント JSON のボリュームマウント

`docker-compose-simple.yml` では `./secrets` ディレクトリが `/usr/local/tomcat/secrets` にマウントされています:

```yaml
volumes:
  - ./secrets:/usr/local/tomcat/secrets:ro
```

サービスアカウント JSON を配置:

```bash
cp google-service-account.json /opt/nemakiware/docker/secrets/
chmod 600 /opt/nemakiware/docker/secrets/google-service-account.json
```

### 9-4. 同期スケジュール

| 設定 | 値 | 説明 |
|------|------|------|
| `cloud.directory.sync.cron` | `0 0 2 * * ?` | 毎日午前2時に同期（cron式） |
| `cloud.directory.sync.window.size` | `100` | 1回の API 呼び出しで取得するユーザー/グループ数 |

手動同期は NemakiWare 管理画面から実行できます:
**管理** → **クラウドディレクトリ同期** → **今すぐ同期**

---

## 10. SSL/TLS + リバースプロキシ

本番環境では HTTPS が必須です。以下の2パターンから選択してください。

### 10-1. パターン A: ALB + ACM 証明書

AWS のマネージドサービスを使用する最もシンプルな構成です。

```
Internet → ALB (:443, SSL終端) → EC2 (:8080, HTTP)
```

1. **ACM 証明書を発行**: Route 53 で DNS 検証
2. **ALB を作成**: HTTPS:443 リスナー → Target Group (EC2:8080)
3. **HTTP → HTTPS リダイレクト**: ALB の HTTP:80 リスナーでリダイレクトルール設定

この構成では EC2 上の Nginx は不要です。

### 10-2. パターン B: Nginx + Let's Encrypt

EC2 に直接アクセスする場合の構成です。

```
Internet → Nginx (:443, SSL終端) → Core (:8080, HTTP)
```

#### Nginx インストール

```bash
# Amazon Linux 2023
sudo dnf install -y nginx

# Ubuntu 24.04
sudo apt-get install -y nginx
```

#### Certbot (Let's Encrypt) インストール

```bash
# Amazon Linux 2023
sudo dnf install -y certbot python3-certbot-nginx

# Ubuntu 24.04
sudo apt-get install -y certbot python3-certbot-nginx
```

#### SSL 証明書の取得

```bash
sudo certbot --nginx -d nemakiware.example.com
```

#### Nginx 設定例

```nginx
# /etc/nginx/conf.d/nemakiware.conf

server {
    listen 80;
    server_name nemakiware.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name nemakiware.example.com;

    ssl_certificate /etc/letsencrypt/live/nemakiware.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/nemakiware.example.com/privkey.pem;

    # SSL セキュリティ設定
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # プロキシヘッダー設定
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # アップロードサイズ制限
    client_max_body_size 100m;

    # NemakiWare Core
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 300s;
        proxy_connect_timeout 60s;
    }
}
```

```bash
# Nginx 設定テスト & 再起動
sudo nginx -t
sudo systemctl restart nginx
sudo systemctl enable nginx
```

#### 証明書の自動更新

```bash
# cron で自動更新（certbot が自動設定する場合もあり）
echo "0 0,12 * * * root certbot renew --quiet" | sudo tee /etc/cron.d/certbot-renew
```

### 10-3. Google OIDC リダイレクト URI の更新

SSL 設定後、Google Cloud Console でリダイレクト URI を本番 URL に更新してください:

- **承認済みの JavaScript 生成元**: `https://nemakiware.example.com`
- **承認済みのリダイレクト URI**: `https://nemakiware.example.com/core/rest/repo/bedroom/authtoken/oidc/callback`

---

## 11. データ永続化とバックアップ

### 11-1. Docker Volume の EBS マッピング

Docker Volume はデフォルトで `/var/lib/docker/volumes/` に保存されます。EBS ボリュームに配置されていることを確認してください。

```bash
# ボリューム確認
docker volume ls
# nemaki-network_couchdb_data
# nemaki-network_solr_data
# nemaki-network_tei_cache

# ボリュームの実体パス
docker volume inspect nemaki-network_couchdb_data
```

### 11-2. CouchDB バックアップ

CouchDB は NemakiWare の全データ（ドキュメント、メタデータ、ユーザー、権限）を保持する最重要コンポーネントです。

#### 方法 1: EBS スナップショット（推奨）

```bash
# EC2 インスタンスのボリューム ID を確認
aws ec2 describe-instances --instance-ids i-xxxxxxxx \
  --query 'Reservations[].Instances[].BlockDeviceMappings[].Ebs.VolumeId'

# EBS スナップショット作成
aws ec2 create-snapshot \
  --volume-id vol-xxxxxxxx \
  --description "NemakiWare backup $(date +%Y%m%d)"

# 定期スナップショット（AWS Backup または cron + aws cli）
```

#### 方法 2: CouchDB レプリケーション

```bash
# CouchDB のデータベース一覧取得
curl -u admin:password http://localhost:5984/_all_dbs

# 外部 CouchDB へのレプリケーション設定
curl -X POST -u admin:password http://localhost:5984/_replicate \
  -H 'Content-Type: application/json' \
  -d '{
    "source": "bedroom",
    "target": "http://backup-user:password@backup-couchdb:5984/bedroom",
    "continuous": true
  }'
```

### 11-3. バックアップ優先度

| データ | 優先度 | バックアップ方法 | 理由 |
|--------|--------|------------------|------|
| **CouchDB** | 高 | EBS スナップショット / レプリケーション | 全データの永続ストア |
| **Solr** | 低 | 不要（再構築可能） | CouchDB から再インデックス可能 |
| **TEI キャッシュ** | 低 | 不要 | モデルは再ダウンロード可能 |
| **設定ファイル** | 高 | Git 管理 | nemakiware.properties, repositories.yml |

---

## 12. [BETA] S3 コールドストレージ設定

> **Beta 機能**: この機能はベータ版です。本番環境での使用前に十分なテストを行ってください。

### 12-1. 概要

アーカイブされたドキュメントを Amazon S3 へコピーまたは移動し、長期保存できます。
S3 Object Lock を使用して、コンプライアンス要件に対応した改ざん防止保存が可能です。

#### COPY モードと MOVE モード

| モード | ローカルコンテンツ | アーカイブ状態 | NemakiWare からのアクセス |
|--------|-------------------|---------------|-------------------------|
| **COPY** | 保持 | `ARCHIVED_LOCAL` のまま | ダウンロード可能 |
| **MOVE** | 削除 | `ARCHIVED_COLD` に遷移 | メタデータのみ（コンテンツ不可） |

**COPY モードの状態遷移**:
```
ARCHIVED_LOCAL → [S3コピー] → ARCHIVED_LOCAL（coldArchivedAt・contentRef 記録済み）
```

**MOVE モードの状態遷移**:
```
ARCHIVED_LOCAL → [S3移動] → ARCHIVED_COLD（メタデータのみ、ローカルコンテンツ削除済み）
```

#### S3 コンテンツの管理スコープ

S3 に格納されたコンテンツは **NemakiWare の管理スコープ外** となります。
コンテンツの閲覧・ダウンロード・廃棄（Disposition）は、すべて AWS 側で直接管理してください。

- **コンテンツアクセス**: AWS S3 Console または AWS CLI で直接取得
- **Disposition（廃棄）**: S3 Lifecycle Policy で自動削除を設定
- **Object Lock**: S3 側で保持期間と削除保護を管理

NemakiWare は S3 へのコンテンツの書き込み（put）と Object Lock の設定（enforceImmutability）のみを行い、
書き込み後のコンテンツ管理には関与しません。

### 12-2. S3 バケット作成

```bash
# バケット作成（Object Lock 対応）
aws s3api create-bucket \
  --bucket nemakiware-cold-storage \
  --region ap-northeast-1 \
  --create-bucket-configuration LocationConstraint=ap-northeast-1 \
  --object-lock-enabled-for-bucket

# バージョニング有効化（Object Lock 必須）
aws s3api put-bucket-versioning \
  --bucket nemakiware-cold-storage \
  --versioning-configuration Status=Enabled

# デフォルト Object Lock 設定（オプション）
aws s3api put-object-lock-configuration \
  --bucket nemakiware-cold-storage \
  --object-lock-configuration '{
    "ObjectLockEnabled": "Enabled",
    "Rule": {
      "DefaultRetention": {
        "Mode": "GOVERNANCE",
        "Days": 365
      }
    }
  }'
```

### 12-3. IAM ポリシー

EC2 インスタンスロールに以下のポリシーをアタッチします:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:HeadObject",
                "s3:PutObjectRetention",
                "s3:GetObjectRetention",
                "s3:GetBucketVersioning"
            ],
            "Resource": [
                "arn:aws:s3:::nemakiware-cold-storage",
                "arn:aws:s3:::nemakiware-cold-storage/*"
            ]
        }
    ]
}
```

> **注意**: NemakiWare は S3 からの読み取り（`s3:GetObject`）を行いません。
> S3 コンテンツへのアクセスが必要な場合は、AWS Console や CLI を使用する
> ユーザー/ロールに別途 `s3:GetObject` 権限を付与してください。

### 12-4. nemakiware.properties の設定

```properties
### Long-term Storage Configuration
longterm.storage.type=s3

### S3 Settings
longterm.s3.bucket=nemakiware-cold-storage
longterm.s3.region=ap-northeast-1
longterm.s3.prefix=archives/

### Retention Settings
# リテンション期間（日数）- アーカイブからコールド移行までの待機日数
retention.cold.move.after.days=30
# ローカルコピーを保持するか（true=COPY, false=MOVE）
retention.cold.keep.local.copy=false
# Object Lock モード: COMPLIANCE（管理者でも削除不可） or GOVERNANCE（特権で削除可）
retention.s3.object.lock.mode=GOVERNANCE
# Object Lock リテンション期間（日数）
retention.s3.object.lock.days=365
```

### 12-5. Object Lock モード

| モード | 特徴 | 推奨用途 |
|--------|------|----------|
| **GOVERNANCE** | `s3:BypassGovernanceRetention` 権限で削除可能 | テスト環境、一般的な保存要件 |
| **COMPLIANCE** | リテンション期間中は誰も削除不可 | 法令遵守、監査要件 |

> **注意**: COMPLIANCE モードでは、設定した期間中はオブジェクトの削除やリテンションの短縮ができません。本番環境で設定する前に、GOVERNANCE モードでテストすることを推奨します。

### 12-6. S3 Lifecycle Policy による廃棄（Disposition）

ROT レコードの廃棄は S3 Lifecycle Policy で管理します。
NemakiWare は廃棄処理を実装しません。

```bash
# Lifecycle Policy の設定例: 7年後に自動削除
aws s3api put-bucket-lifecycle-configuration \
  --bucket nemakiware-cold-storage \
  --lifecycle-configuration '{
    "Rules": [
      {
        "ID": "archive-disposition",
        "Prefix": "archives/",
        "Status": "Enabled",
        "Expiration": {
          "Days": 2555
        }
      }
    ]
  }'
```

> **注意**: Object Lock が COMPLIANCE モードの場合、Lifecycle Policy による削除は
> リテンション期間が満了するまで実行されません。

### 12-7. 動作確認

```bash
# 1. ドキュメントをアーカイブ
curl -u admin:admin -X POST \
  -F "cmisaction=deleteObject" \
  -F "objectId=DOCUMENT_ID" \
  "http://localhost:8080/core/browser/bedroom"

# 2. アーカイブ一覧を確認
curl -u admin:admin "http://localhost:8080/core/rest/repo/bedroom/archive/index"

# 3. コールド移行は retention.cold.move.after.days 経過後に自動実行
# 手動確認: S3 バケット内のオブジェクトを確認
aws s3 ls s3://nemakiware-cold-storage/archives/ --recursive

# 4. Object Lock リテンション確認
aws s3api get-object-retention \
  --bucket nemakiware-cold-storage \
  --key archives/ARCHIVE_ID

# 5. コンテンツを S3 から直接ダウンロード（NemakiWare 経由不可）
aws s3 cp s3://nemakiware-cold-storage/archives/ARCHIVE_ID ./downloaded-file
```

---

## 13. 運用・監視

### 13-1. ヘルスチェック

```bash
# Core CMIS サーバー
curl -f http://localhost:8080/core
# HTTP 200 → 正常

# CouchDB
curl -f -u admin:password http://localhost:5984/_all_dbs
# JSON 配列 → 正常

# Solr
curl -f "http://localhost:8983/solr/admin/cores?action=STATUS"
# JSON ステータス → 正常

# TEI (RAG 有効時のみ)
curl -f http://localhost:8081/health
# {"status":"ok"} → 正常
```

### 13-2. ヘルスチェックスクリプト

```bash
#!/bin/bash
# /opt/nemakiware/healthcheck.sh

ERRORS=0

# Core
if ! curl -sf http://localhost:8080/core > /dev/null 2>&1; then
  echo "CRITICAL: Core is down"
  ERRORS=$((ERRORS + 1))
fi

# CouchDB
if ! curl -sf -u admin:password http://localhost:5984/_all_dbs > /dev/null 2>&1; then
  echo "CRITICAL: CouchDB is down"
  ERRORS=$((ERRORS + 1))
fi

# Solr
if ! curl -sf "http://localhost:8983/solr/admin/cores?action=STATUS" > /dev/null 2>&1; then
  echo "WARNING: Solr is down"
  ERRORS=$((ERRORS + 1))
fi

# TEI (optional)
if docker compose -f /opt/nemakiware/docker/docker-compose-simple.yml ps tei 2>/dev/null | grep -q "running"; then
  if ! curl -sf http://localhost:8081/health > /dev/null 2>&1; then
    echo "WARNING: TEI is down"
    ERRORS=$((ERRORS + 1))
  fi
fi

if [ $ERRORS -eq 0 ]; then
  echo "OK: All services healthy"
fi

exit $ERRORS
```

```bash
chmod +x /opt/nemakiware/healthcheck.sh

# cron で5分ごとにチェック
echo "*/5 * * * * root /opt/nemakiware/healthcheck.sh >> /var/log/nemakiware-health.log 2>&1" \
  | sudo tee /etc/cron.d/nemakiware-health
```

### 13-3. CloudWatch Agent でのログ転送

```bash
# CloudWatch Agent インストール
sudo dnf install -y amazon-cloudwatch-agent  # Amazon Linux 2023

# 設定ファイル作成
sudo tee /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/lib/docker/containers/**/*-json.log",
            "log_group_name": "/nemakiware/containers",
            "log_stream_name": "{instance_id}"
          },
          {
            "file_path": "/var/log/nemakiware-health.log",
            "log_group_name": "/nemakiware/healthcheck",
            "log_stream_name": "{instance_id}"
          }
        ]
      }
    }
  }
}
EOF

# CloudWatch Agent 起動
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json -s
```

### 13-4. ディスク容量監視

```bash
# ディスク使用量チェック（cron で毎時実行）
echo '0 * * * * root df -h / | tail -1 | awk "{if (\$5+0 > 80) print \"WARNING: Disk usage \" \$5}" >> /var/log/nemakiware-health.log' \
  | sudo tee /etc/cron.d/disk-check
```

### 13-5. 定期メンテナンス

#### CouchDB Compaction

CouchDB は更新・削除時にデータを追記するため、定期的な compaction が必要です。

```bash
# 手動 compaction
curl -X POST -u admin:password http://localhost:5984/bedroom/_compact \
  -H 'Content-Type: application/json'

# cron で毎週日曜深夜に実行
echo '0 3 * * 0 root curl -sX POST -u admin:password http://localhost:5984/bedroom/_compact -H "Content-Type: application/json" >> /var/log/couchdb-compact.log 2>&1' \
  | sudo tee /etc/cron.d/couchdb-compact
```

#### Docker ログローテーション

```bash
# /etc/docker/daemon.json
sudo tee /etc/docker/daemon.json << 'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
EOF

sudo systemctl restart docker
```

---

## 14. トラブルシューティング

### TEI モデルダウンロード失敗

**症状**: TEI コンテナが起動せず、ログに `Failed to download model` と表示される。

```bash
# ログ確認
docker compose -f docker-compose-simple.yml logs tei

# 対処法 1: ネットワーク確認（Hugging Face Hub への接続）
curl -I https://huggingface.co

# 対処法 2: Hugging Face Hub のミラー設定
# docker-compose-simple.yml の tei 環境変数に追加
# HF_HUB_URL=https://hf-mirror.com （中国リージョンの場合）

# 対処法 3: モデルを手動ダウンロードしてボリュームにコピー
docker run --rm -v nemaki-network_tei_cache:/data \
  -e HF_HUB_URL=https://huggingface.co \
  ghcr.io/huggingface/text-embeddings-inference:cpu-1.6 \
  --model-id intfloat/multilingual-e5-large --download-only
```

### CouchDB 接続エラー

**症状**: Core 起動時に `Connection refused` または `Cannot connect to CouchDB`。

```bash
# CouchDB コンテナの状態確認
docker compose -f docker-compose-simple.yml ps couchdb
docker compose -f docker-compose-simple.yml logs couchdb --tail 50

# CouchDB への直接アクセス確認
curl -u admin:password http://localhost:5984/

# 対処法: CouchDB を再起動
docker compose -f docker-compose-simple.yml restart couchdb
sleep 30
docker compose -f docker-compose-simple.yml restart core
```

### Google OIDC redirect_uri_mismatch

**症状**: Google ログイン時に `Error 400: redirect_uri_mismatch` エラー。

**対処法**:
1. Google Cloud Console → **API とサービス** → **認証情報** → OAuth クライアント ID を開く
2. **承認済みのリダイレクト URI** に以下が登録されていることを確認:
   ```
   https://nemakiware.example.com/core/rest/repo/bedroom/authtoken/oidc/callback
   ```
3. プロトコル (`https` vs `http`)、ドメイン、パスが完全一致していることを確認
4. 変更後、反映まで数分かかる場合があります

### メモリ不足 (TEI OOM)

**症状**: TEI コンテナが突然停止する。`docker inspect` で `OOMKilled: true` が表示される。

```bash
# OOM 確認
docker inspect $(docker compose -f docker-compose-simple.yml ps -q tei) | grep OOMKilled

# 対処法 1: メモリ制限を引き上げ
# docker-compose-simple.yml の deploy.resources.limits.memory を増やす
# 例: 4G → 6G

# 対処法 2: EC2 インスタンスのメモリを増やす
# t3.xlarge (16GB) → r6i.xlarge (32GB) 等

# 対処法 3: バッチサイズを下げる
# nemakiware.properties: rag.tei.batch.size=16 (デフォルト 32 → 16)
```

### Solr インデックス破損

**症状**: 検索結果が返らない、Solr のステータスが異常。

```bash
# Solr のステータス確認
curl "http://localhost:8983/solr/admin/cores?action=STATUS"

# 対処法: インデックスを再構築
# 1. Solr のデータをクリア
docker compose -f docker-compose-simple.yml down solr
docker volume rm nemaki-network_solr_data
docker compose -f docker-compose-simple.yml up -d --build solr

# 2. Core を再起動（自動的にインデックス再構築が開始）
docker compose -f docker-compose-simple.yml restart core
```

### コンテナが起動しない

```bash
# 全コンテナのステータス確認
docker compose -f docker-compose-simple.yml ps

# 特定コンテナのログ確認
docker compose -f docker-compose-simple.yml logs core --tail 100
docker compose -f docker-compose-simple.yml logs couchdb --tail 100

# 完全リセット
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

---

## 参考リンク

- [CLOUD_INTEGRATION.md](CLOUD_INTEGRATION.md) — Google / Microsoft クラウド統合の詳細設定
- [ARCHITECTURE.md](ARCHITECTURE.md) — システムアーキテクチャ概要
- [AWS EC2 ドキュメント](https://docs.aws.amazon.com/ec2/)
- [Docker Compose ドキュメント](https://docs.docker.com/compose/)
- [Hugging Face TEI ドキュメント](https://huggingface.co/docs/text-embeddings-inference/)
