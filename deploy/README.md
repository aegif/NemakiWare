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

1. **Setup Wizard を完了する（初回必須）**: NemakiWare 3.x のフレッシュ
   インストールは初回起動時に **Setup Mode** に入り、データベースと管理者
   アカウントは Setup Wizard が作成します（`admin/admin` で即ログインは
   できません）。ブラウザで `https://<your-domain>/core/ui/`（デモ構成なら
   `http://<host>:8080/core/ui/`）を開き、ウィザードに従って CouchDB 接続・
   管理者パスワード・認証方式を設定してください。状態は
   `GET /core/api/v1/setup/state`（`setupRequired:true` = 未完了）で確認可。
   完了後に作成した管理者で CMIS / UI が利用可能になります。
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

## フル構成の使い捨て検証環境（Atlas + Bedrock RAG + クラウド認証 + HTTPS）

`terraform apply` **一発**で、Setup Wizard 完了・**Bedrock RAG**（インスタンス
IAM ロール、静的キー不要）・**Apache Atlas**（コンテナ + ATLAS カタログバック
エンド選択）・**Microsoft / Google サインイン**・**Caddy + Let's Encrypt の
HTTPS 前段**まで揃った検証環境を立ち上げ、`terraform destroy` で落とす — という
上げ下げを**何度でも繰り返せる**構成です。自動化本体は
[`deploy/aws/nemaki-full-config.sh`](aws/nemaki-full-config.sh)（stock
ブートストラップの後に連結実行）。

### なぜ HTTPS + 固定ホスト名が要るか

- **HTTPS 必須**: ブラウザは `window.crypto.subtle`（MSAL / GIS が PKCE に使う）を
  **セキュアコンテキスト（HTTPS か localhost）でのみ**公開します。平文 HTTP の
  生 IP では MSAL が `crypto_nonexistent` で失敗します。→ Caddy が
  `nip_host`（例 `<eip>.nip.io`）に Let's Encrypt の正規証明書を発行して解決。
- **固定ホスト名**: OAuth のリダイレクト URI / 生成元は provider 側に登録が要り、
  ホスト名が変わるたびに再登録が要ります。**永続 Elastic IP を 1 個確保して
  再利用**すれば `<eip>.nip.io` が不変になり、**登録は最初の 1 回だけ**で以降の
  destroy/apply で触らずに済みます（nip.io なので DNS レコード管理も不要）。

### 手順

1. 永続 EIP を 1 個確保（destroy でも解放されない専用）:
   ```bash
   aws ec2 allocate-address --domain vpc \
     --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=nemaki-persistent-test}]'
   # 返る AllocationId と PublicIp を控える。ホスト名は <PublicIp>.nip.io
   ```
2. `terraform.tfvars` に full-config 変数を設定（[`terraform.tfvars.example`](terraform/aws/terraform.tfvars.example)
   の該当節参照）: `enable_full_config=true` / `eip_allocation_id` / `nip_host` /
   `cloud_auth_microsoft_client_id` / `cloud_auth_microsoft_tenant_id` /
   `cloud_auth_google_client_id`。**`terraform.tfvars` は gitignored** — clientId 等は
   ここにだけ置きます。
3. provider 側にホストを **1 回だけ**登録:
   - Microsoft（Entra アプリ）: 認証 → **SPA プラットフォーム** →
     リダイレクト URI `https://<nip_host>/core/ui/auth-popup.html`
   - Google（OAuth クライアント）: **承認済み JavaScript 生成元**
     `https://<nip_host>`
4. 上げ下げ:
   ```bash
   cd deploy/terraform/aws
   tofu apply -auto-approve     # ~12分でフル構成完成（Atlas は初回起動が重く自動 restart）
   # → 出力 core_url = https://<nip_host>/core/ui/index.html
   tofu destroy -auto-approve   # 落とす（永続 EIP は state 外なので保持）
   ```

### セキュリティ上の注意（機微情報の扱い）

- **クライアントシークレットは不要・存在しません**: UI は MSAL（SPA/PKCE）と
  Google Identity Services（生成元ベース）＝いずれも public client。認証に
  client secret を使いません（同期など confidential フロー専用）。
- `terraform.tfvars` / `*.tfstate` / `.terraform/` は **`.gitignore` 済み**。
  clientId / tenantId（半公開の識別子）・EIP・自 IP はここにだけ置き、リポジトリ
  には**コミットしません**（この README・`.example`・`.tf` にも実値は入れない）。
- `enable_full_config=true` は Caddy の Let's Encrypt 用に **80/443 を 0.0.0.0/0** に
  開けます（`allowed_cidr_https`）。使い捨て検証専用の posture で、`admin/admin`
  初期資格のまま長期公開しないこと。検証が済んだら `destroy` を。
- Bedrock は**インスタンス IAM ロール**（`bedrock:InvokeModel` のみ）で呼びます。
  静的アクセスキーは置きません。

### 既知の落とし穴（自動化で対処済み）

- **Atlas all-in-one（sburn/apache-atlas:2.3.0）は初回起動で NPE 失敗** →
  スクリプトが 1 回自動 restart して復旧させます。
- **RAG プロバイダは `-D` で注入**: Spring は `classpath:nemakiware.properties`
  を読み、そこでは `rag.embedding.provider` がコメントアウト（既定 `tei`）。
  RAG は admin-managed key でもないため、`/conf` へのファイルマウントや
  integration-settings API では効きません。スクリプトは Tomcat の `setenv.sh` で
  `-Drag.embedding.provider=bedrock` 等を CATALINA_OPTS に注入します。

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

## アーキテクチャ（amd64 / arm64）

公開イメージのデフォルトは **linux/amd64** です（tag push 時）。Graviton /
Ampere などの **arm64** は実験的扱い: `release-images.yml` を
`workflow_dispatch` で `platforms=linux/amd64,linux/arm64` を選んで手動発行すると
multi-arch イメージを publish できます（LibreOffice の arm64 ビルドが
エミュレーションで遅いため、本番採用前に実機検証してください）。なお RAG の
`tei` イメージは upstream が amd64 中心のため、arm64 ホストでは `--profile rag`
が動かない可能性があります（core / solr / couchdb は multi-arch）。

## ローカル / オンプレでこの compose を使う

クラウドでなくても、イメージを pull できる任意の Docker ホストで同じ手順が使えます。

```bash
cd docker
cp .env.prod.example .env      # 編集（COUCHDB_PASSWORD を強い値に）
docker compose -f docker-compose-prod.yml up -d
```
