# NemakiWare AWS クイックスタート手順書 (3.2+)

3.2 から、**AWS の素の VM 上で WAR をビルドする必要がなくなりました**。公開
コンテナイメージ（`ghcr.io/aegif/nemakiware-core` / `-solr`）を **pull するだけ**で
フルスタックが起動します。ホストに Java / Maven / Node のツールチェーンは不要です。

この手順書は「どれだけ簡単に立ち上がるか」を実際に確認するための、最短〜本番までの
詳細ガイドです。より深い運用は [`docs/AWS-DEPLOYMENT-GUIDE.md`](AWS-DEPLOYMENT-GUIDE.md)、
資材の一覧は [`deploy/README.md`](../deploy/README.md) を参照してください。

---

## 0. 3行でわかる「簡単になった度」

| | 3.1 まで | 3.2 から |
|---|---|---|
| ホスト準備 | Java21 + Maven + Node を入れて **WAR を自前ビルド** | **Docker だけ**（ブートストラップが自動導入） |
| デプロイ操作 | 手順書を見ながら手作業で多段 | **`terraform apply` 1回** or **user-data を貼るだけ** |
| 所要時間 | 数十分〜 | **VM 起動〜CMIS 応答まで 5〜10 分**（あとは Setup Wizard 数分） |

> **重要（共通）**: 3.x はフレッシュ環境だと初回に **Setup Wizard** で管理者と
> データベースを作成します。`admin/admin` で即ログインはできません（§5 参照）。

---

## 1. 所要時間とコストの目安

- **構築時間**: Terraform / user-data 流し込み後、**5〜10 分**で `…/core` が応答。
  その後ブラウザで Setup Wizard を **2〜3 分**。
- **概算コスト（東京リージョン目安）**: `t3.large`（8 GB）約 $0.11/時 + gp3 50GB。
  検証なら使い終わったら削除（§9）すれば数十円〜数百円程度。
- **推奨スペック**: `t3.large`（8 GB）以上。RAG 検索（任意）も使うなら
  `t3.xlarge`（16 GB）以上。

---

## 2. 前提

- AWS アカウントと、EC2 / VPC / IAM を作成できる権限。
- ローカルに以下のいずれか:
  - **Terraform**（または OpenTofu）+ AWS 認証（経路A・推奨）
  - **AWS CLI**（経路C）
  - もしくは**ブラウザだけ**（経路B・マネジメントコンソール）
- リージョン例: `ap-northeast-1`（東京）。
- 公開イメージは **public** なので、VM 側で `docker login` は不要です。

---

## 3. 経路A: Terraform で一発（最速・推奨）

VM・ネットワーク・IAM をまとめて作成し、ブートストラップまで自動実行します。

```bash
# リポジトリを取得（手元のPCに）
git clone https://github.com/aegif/NemakiWare.git
cd NemakiWare/deploy/terraform/aws

# 変数ファイルを用意
cp terraform.tfvars.example terraform.tfvars
```

`terraform.tfvars` を編集（**最短で試すなら下記の3行を変えるだけ**）:

```hcl
region      = "ap-northeast-1"
http_public = true          # ← 検証用: 8080 を公開し、すぐブラウザで開けるように
key_name    = "my-key"      # ← SSH したいなら既存のキーペア名（任意）

# 既定のまま使える値（公開イメージ 3.2.0 を pull）
# nemaki_image_prefix = "ghcr.io/aegif/nemakiware"
# nemaki_version      = "3.2.0"
# instance_type       = "t3.large"
# allowed_cidr_https  = ["0.0.0.0/0"]   # 検証用。本番は自社IP/VPNに絞る
```

実行:

```bash
terraform init
terraform apply        # yes で承認
terraform output       # public_ip / core_url / ssh が表示される
```

出力例:
```
core_url  = "http://<EIP>:8080/core"
public_ip = "<EIP>"
ssh       = "ssh ec2-user@<EIP>"
```

→ 5〜10 分後、**`http://<EIP>:8080/core/ui/`** をブラウザで開く → **§5 の Setup
Wizard** へ。

> **Terraform が自動で行うこと**: 最新 Amazon Linux 2023 AMI を SSM から解決、
> Security Group（443、`http_public=true` なら 8080 も）、IMDSv2 必須・gp3 暗号化の
> EC2、Elastic IP、（`couchdb_secret_arn` 指定時のみ）Secrets Manager 読取に絞った
> IAM。ユーザーデータでブートストラップが走り、Docker 導入 → イメージ pull →
> compose 起動 → reboot 耐性の systemd 登録までを実施します。

主な変数（`variables.tf`）:

| 変数 | 既定 | 説明 |
|---|---|---|
| `region` | `ap-northeast-1` | リージョン |
| `instance_type` | `t3.large` | 8GB。RAG 用は `t3.xlarge` |
| `key_name` | （なし） | SSH 用キーペア名（任意） |
| `http_public` | `false` | true で 8080 公開 + 0.0.0.0 バインド（**検証用**） |
| `allowed_cidr_https` | `["0.0.0.0/0"]` | 443（+8080）を許可する CIDR。本番は絞る |
| `allowed_cidr_ssh` | `[]` | 22 を許可する CIDR。空なら SSH 閉 |
| `nemaki_version` | `3.2.0` | 起動するイメージタグ |
| `couchdb_secret_arn` | （空） | 指定で CouchDB パスワードを Secrets Manager から取得 |
| `assign_eip` | `true` | Elastic IP 付与 |

---

## 4. 経路B / C: Terraform を使わない場合

### 経路B: マネジメントコンソール（ブラウザだけ）

1. **EC2 → インスタンスを起動**。
2. AMI: **Amazon Linux 2023**、インスタンスタイプ: **t3.large** 以上。
3. キーペア: 任意（SSH するなら）。
4. ネットワーク設定 → セキュリティグループ:
   - 検証: **8080**（カスタム TCP）と必要なら **22** を自分の IP から許可。
   - 本番: **443** のみ許可（TLS リバースプロキシ前提、§7）。
5. 「高度な詳細」→「**ユーザーデータ**」に
   [`deploy/aws/user-data.sh`](../deploy/aws/user-data.sh) を貼り付け。
   - **検証で 8080 を直接開きたい場合**、スクリプト冒頭の CONFIG を
     `NEMAKI_HTTP_BIND="${NEMAKI_HTTP_BIND:-0.0.0.0}"` に変更（既定は安全側の
     `127.0.0.1`）。
6. 起動 → 数分後 `http://<パブリックIP>:8080/core/ui/` → **§5**。

### 経路C: AWS CLI

```bash
# 8080 を開けたいので user-data の NEMAKI_HTTP_BIND を 0.0.0.0 にして渡す例
sed 's/NEMAKI_HTTP_BIND:-127.0.0.1/NEMAKI_HTTP_BIND:-0.0.0.0/' \
  deploy/aws/user-data.sh > /tmp/nemaki-userdata.sh

aws ec2 run-instances \
  --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --instance-type t3.large \
  --key-name my-key \
  --security-group-ids sg-xxxxxxxx \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=50,VolumeType=gp3,Encrypted=true}' \
  --metadata-options 'HttpTokens=required' \
  --user-data file:///tmp/nemaki-userdata.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=nemakiware}]'
```

> user-data のデフォルト動作: Docker 導入 → `aegif/NemakiWare` の `v3.2.0` を clone
> → CouchDB パスワードを**ランダム生成**（`/opt/nemakiware/src/docker/.env` に保存）→
> `docker compose -f docker-compose-prod.yml pull && up -d` → systemd 登録。
> 進捗ログは VM 上で `sudo tail -f /var/log/nemaki-bootstrap.log`。

---

## 5. 初回セットアップ（Setup Wizard）— **全経路共通・必須**

フレッシュな CouchDB では NemakiWare は **Setup Mode** で起動し、管理者と
データベースは Setup Wizard が作成します。

1. 起動状態を確認（任意）:
   ```bash
   curl -s http://<IP>:8080/core/api/v1/setup/state
   # {"setupRequired":true,...,"serverVersion":"3.2.0",...} なら起動完了・未セットアップ
   ```
2. ブラウザで **`http://<IP>:8080/core/ui/`** を開く → Setup Wizard が表示される。
3. ウィザードに従って設定:
   - **CouchDB 接続**（自動入力されている内部接続でOK）
   - **管理者パスワード**の設定
   - **認証方式**（最低 1 つ有効化。まずはパスワード認証でOK。Google/Microsoft/
     SAML は後からでも可）
4. 完了すると `setupRequired:false` になり、設定した管理者で CMIS / UI が
   利用可能になります。

> 以後の確認: `curl -u <admin>:<password> http://<IP>:8080/core/atom/bedroom` が
> HTTP 200 + XML を返せば正常です。

---

## 6. 動作確認

```bash
# 公開エンドポイント（認証不要）— サービス一覧
curl -s http://<IP>:8080/core/api/v1/setup/state

# Setup 完了後、CMIS（設定した管理者で）
curl -u <admin>:<password> http://<IP>:8080/core/atom/bedroom   # 200 + XML
```

ブラウザ: `http://<IP>:8080/core/ui/` でログイン → ドキュメント一覧が見えれば成功。

---

## 7. 本番ハードニング（検証で確認できたら）

検証で `http_public=true` / `0.0.0.0` を使った場合、本番では必ず以下に切り替えます。

1. **TLS で前段を保護**: 8080 を直接公開せず、**ALB + ACM** か **nginx + Let's
   Encrypt** で HTTPS 終端。core は安全側の `127.0.0.1:8080` バインドに戻す
   （Terraform は `http_public=false`、手動は `NEMAKI_HTTP_BIND=127.0.0.1`）。
   `/opt/nemakiware/src/docker/.env` に `NEMAKI_PUBLIC_SCHEME=https` を設定。
   具体的な nginx / certbot 設定は [`docs/AWS-DEPLOYMENT-GUIDE.md` §10](AWS-DEPLOYMENT-GUIDE.md)。
2. **Security Group を絞る**: `allowed_cidr_https` を自社 IP / VPN に限定。SSH も
   `allowed_cidr_ssh` を限定（不要なら閉じる）。
3. **CouchDB パスワードを Secrets Manager に**:
   - Terraform: `couchdb_secret_arn = "arn:aws:secretsmanager:…"`（その Secret の
     `GetSecretValue` のみ許可する IAM が自動付与される）。
   - 手動: user-data の `COUCHDB_SECRET_ID` にシークレットIDを設定 + インスタンス
     ロールに `secretsmanager:GetSecretValue` を付与。
4. **CouchDB / Solr は内部限定**: 本番 compose（`docker-compose-prod.yml`）は
   この 2 つにホストポートを公開しません。この posture は維持。
5. **管理者の初期パスワード**は Setup Wizard で必ず強い値に。
6. **データ永続化**: `couchdb_data` / `solr_data` は named volume。EBS スナップ
   ショット（AWS Backup 等）で定期バックアップ。

---

## 8. 更新・運用

VM 上（`/opt/nemakiware/src/docker`）で:

```bash
cd /opt/nemakiware/src/docker
docker compose -f docker-compose-prod.yml ps          # 状態
docker compose -f docker-compose-prod.yml logs -f core # ログ

# バージョン更新（.env の NEMAKI_VERSION を書き換えてから）
docker compose -f docker-compose-prod.yml pull
docker compose -f docker-compose-prod.yml up -d
```

RAG（セマンティック検索）を有効化する場合:
```bash
docker compose -f docker-compose-prod.yml --profile rag up -d   # 16GB 以上推奨
```

---

## 9. 後片付け（削除）

```bash
# 経路A（Terraform）
cd NemakiWare/deploy/terraform/aws
terraform destroy

# 経路B/C（手動）: EC2 インスタンスを終了 + EBS / EIP を解放
```

---

## 10. トラブルシュート

| 症状 | 対処 |
|---|---|
| ブラウザで開けない | SG で 8080（または 443）が許可されているか / `http_public=true`（または `NEMAKI_HTTP_BIND=0.0.0.0`）か確認 |
| `setup/state` が返らない | 起動途中（最大数分）。`sudo tail -f /var/log/nemaki-bootstrap.log` を確認 |
| `admin/admin` でログインできない | 仕様。Setup Wizard（`/core/ui/`）で管理者を作成（§5） |
| イメージ pull で unauthorized | パッケージが private になっていないか確認（このリリースは public）。private 運用時は VM で `docker login ghcr.io`（[`deploy/README.md`](../deploy/README.md) 参照） |
| 起動が重い / OOM | `t3.xlarge`（16GB）へ。RAG（TEI）は ~4GB 追加で必要 |
| CouchDB パスワードを確認したい | VM 上 `/opt/nemakiware/src/docker/.env`（ランダム生成時） |

---

## 付録: 仕組みの要点

- 公開イメージ: tag `v*` を push すると CI（`.github/workflows/release-images.yml`）が
  `ghcr.io/<owner>/nemakiware-core` / `-solr` を発行（CouchDB / TEI は upstream）。
- 本番 compose: [`docker/docker-compose-prod.yml`](../docker/docker-compose-prod.yml)
  が `build:` ではなく公開イメージを参照。
- ブートストラップ: [`deploy/aws/user-data.sh`](../deploy/aws/user-data.sh)（AL2023）。
  Azure 版は [`deploy/azure/custom-data.sh`](../deploy/azure/custom-data.sh)。
- Terraform: [`deploy/terraform/aws/`](../deploy/terraform/aws/)（Azure は
  [`deploy/terraform/azure/`](../deploy/terraform/azure/)）。
