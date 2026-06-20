# NemakiWare IaaS クイックスタート (3.2+)

公開コンテナイメージ + ブートストラップスクリプトで、AWS / Azure の **1 VM に
ほぼワンステップ**で NemakiWare を立ち上げるための資材です。ホスト上で
Java / Maven / Node のビルドは不要 — イメージを **pull するだけ**です。

```
deploy/
├── terraform/aws/          # terraform apply で EC2 一式 (推奨)
├── terraform/azure/        # terraform apply で Azure VM 一式 (推奨)
├── aws/user-data.sh        # EC2 user-data (Amazon Linux 2023) — 手貼り経路
└── azure/custom-data.sh    # Azure VM custom-data (Ubuntu 22.04/24.04) — 手貼り経路
docker/
├── docker-compose-prod.yml # 公開イメージを参照する本番 compose
└── .env.prod.example       # 環境変数テンプレート
```

**2 つの経路があります**:
1. **Terraform（推奨）** — VM・ネットワーク・IAM ごと `terraform apply` 一発。
   → [`deploy/terraform/README.md`](terraform/README.md)
2. **手貼り** — 既存の VM 作成フローの user-data / custom-data 欄にスクリプトを
   貼る（以下の手順）。

どちらも内部で同じブートストラップスクリプト + `docker-compose-prod.yml` を使います。

ブートストラップは内部で `docker-compose-prod.yml` を起動します。
構成: **CouchDB + Solr + Core**（RAG/TEI は `--profile rag` で任意追加）。

---

## 前提

- **公開イメージ**が registry に存在すること。タグ `vX.Y.Z` を push すると
  `.github/workflows/release-images.yml` が
  `ghcr.io/<owner>/nemakiware-core:X.Y.Z` と `...-solr:X.Y.Z` を発行します。
- スクリプト冒頭の `NEMAKI_IMAGE_PREFIX` / `NEMAKI_VERSION` / `NEMAKI_REPO` /
  `NEMAKI_REF` を自分の owner・バージョンに合わせること。
- イメージが **private** の場合、VM 側で `docker login ghcr.io` が必要
  （スクリプトはデフォルトで public 前提。private の場合は §プライベート参照）。

推奨スペック: 8 GB RAM 以上（RAG 有効時は 16 GB 以上）。

---

## AWS (EC2)

### マネジメントコンソール
1. EC2 → インスタンス起動。AMI = **Amazon Linux 2023**、タイプ = `t3.large` 以上。
2. Security Group: 443（と、リバースプロキシ未使用の検証なら 80/8080）を許可。
3. 「高度な詳細」→「ユーザーデータ」に [`aws/user-data.sh`](aws/user-data.sh)
   を貼り付け（先頭の CONFIG を編集）。
4. 起動。数分後 `http://<EIP>:8080/core` が応答します。

### CLI
```bash
aws ec2 run-instances \
  --image-id ami-xxxxxxxx \                 # Amazon Linux 2023
  --instance-type t3.large \
  --key-name my-key \
  --security-group-ids sg-xxxxxxxx \
  --user-data file://deploy/aws/user-data.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=nemakiware}]'
```

タグで上書きも可能（CONFIG を編集せずに）:
`NemakiVersion` / `NemakiRef` / `NemakiImagePrefix` / `NemakiHttpBind`。

**CouchDB パスワード**: デフォルトはランダム生成（`/opt/nemakiware/src/docker/.env`
に保存）。Secrets Manager から取りたい場合はスクリプトの `COUCHDB_SECRET_ID`
を設定し、インスタンスロールに `secretsmanager:GetSecretValue` を付与。

進捗ログ: `sudo tail -f /var/log/nemaki-bootstrap.log`

---

## Azure (VM)

```bash
az vm create \
  --resource-group nemaki-rg \
  --name nemakiware \
  --image Ubuntu2204 \
  --size Standard_B2ms \
  --admin-username azureuser \
  --generate-ssh-keys \
  --custom-data deploy/azure/custom-data.sh \
  --assign-identity                      # Key Vault を使う場合
az vm open-port --resource-group nemaki-rg --name nemakiware --port 443 --priority 1001
```

**CouchDB パスワード**: デフォルトはランダム生成。Key Vault から取る場合は
スクリプトの `COUCHDB_KEYVAULT_SECRET_URI` にシークレット URI を設定し、
VM のマネージド ID に Key Vault の `get` 権限を付与（`--assign-identity` 必須）。

進捗ログ: `sudo tail -f /var/log/nemaki-bootstrap.log`

---

## 起動後にやること（重要）

1. **管理者パスワード変更**: 初期ログインは `admin / admin`。即変更してください。
2. **TLS で前段を保護**: 本番は 8080 を直接公開せず、nginx / ALB / Application
   Gateway などで HTTPS 終端する構成を推奨。core は**安全側のデフォルトで
   `127.0.0.1:8080` バインド**（ブートストラップ／compose とも既定 127.0.0.1）。
   本番ではこのままリバースプロキシを前段に置き、`.env` の
   `NEMAKI_PUBLIC_SCHEME=https` を設定してください。
   詳細なリバースプロキシ + Let's Encrypt 手順は
   [`docs/AWS-DEPLOYMENT-GUIDE.md` §10](../docs/AWS-DEPLOYMENT-GUIDE.md) を参照。

   > **検証用に即アクセスしたい場合のみ**: スクリプト CONFIG の
   > `NEMAKI_HTTP_BIND=0.0.0.0`（Terraform なら `http_public=true`）にして
   > 8080 をセキュリティグループ/NSG で開放。`admin/admin` を平文 HTTP で
   > 露出するため、検証以外では使わないこと。
3. **CouchDB / Solr は内部ネットワーク限定**: prod compose はこの 2 つに
   ホストポートを公開しません（compose ネットワーク内のみ）。この posture は
   維持してください。
4. **データ永続化**: `couchdb_data` / `solr_data` は named volume です。
   EBS / Managed Disk のスナップショットでバックアップしてください。

---

## 運用コマンド

```bash
cd /opt/nemakiware/src/docker
docker compose -f docker-compose-prod.yml ps         # 状態
docker compose -f docker-compose-prod.yml logs -f core
docker compose -f docker-compose-prod.yml pull && \
  docker compose -f docker-compose-prod.yml up -d     # バージョン更新
```

バージョン更新は `.env` の `NEMAKI_VERSION` を書き換えてから上記 pull + up。

---

## RAG（セマンティック検索）を有効化する

```bash
cd /opt/nemakiware/src/docker
docker compose -f docker-compose-prod.yml --profile rag up -d
```

TEI は ~4 GB RAM を要します。RAG を使う場合は 16 GB 以上の VM を。
`nemakiware.properties` 側の RAG 設定は
[`docs/AWS-DEPLOYMENT-GUIDE.md` §6](../docs/AWS-DEPLOYMENT-GUIDE.md) を参照。

---

## プライベートイメージを使う場合

イメージを private package にしている場合、VM 起動後に GHCR ログインが必要です。
ブートストラップ前に CONFIG で対応するか、起動後に手動で:

```bash
echo "$GHCR_PAT" | docker login ghcr.io -u <user> --password-stdin
docker compose -f docker-compose-prod.yml pull
docker compose -f docker-compose-prod.yml up -d
```

`GHCR_PAT` は `read:packages` スコープの PAT。AWS なら Secrets Manager、Azure なら
Key Vault に置き、起動スクリプトを拡張して取得するのが安全です。

---

## ローカル / オンプレでこの compose を使う

クラウドでなくても、イメージを pull できる任意の Docker ホストで同じ手順が使えます。

```bash
cd docker
cp .env.prod.example .env      # 編集（COUCHDB_PASSWORD を強い値に）
docker compose -f docker-compose-prod.yml up -d
```
