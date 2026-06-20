# NemakiWare Terraform modules (AWS / Azure)

`terraform apply` 一発で 1 VM 構成の NemakiWare を立ち上げます。内部では
[`deploy/aws/user-data.sh`](../aws/user-data.sh) /
[`deploy/azure/custom-data.sh`](../azure/custom-data.sh) を **唯一の真実**として
再利用し、Terraform は VM・ネットワーク・IAM だけを用意します。デプロイ座標
（イメージ prefix / version / repo / ref / HTTP bind / シークレット参照）は
ブートストラップ先頭に env として決定的に注入されるため、タグ伝播レースは
ありません。

```
deploy/terraform/
├── aws/      # EC2 + SG + (任意)EIP + IAM。Amazon Linux 2023 (SSM 最新AMI)
└── azure/    # Linux VM + VNet/Subnet + NSG + Public IP。Ubuntu 22.04
```

前提: 対応する**公開イメージ**が registry に存在すること
（`v*` タグ push で [`release-images.yml`](../../.github/workflows/release-images.yml)
が発行）。`nemaki_version` / `nemaki_ref` は発行済みタグに合わせてください。

---

## AWS

```bash
cd deploy/terraform/aws
cp terraform.tfvars.example terraform.tfvars   # 編集
terraform init
terraform apply
terraform output core_url
```

- AMI は公開 SSM パラメータから **最新 Amazon Linux 2023** を自動解決（ハードコードなし）。
- `vpc_id` / `subnet_id` 未指定ならデフォルト VPC を使用。
- `http_public=false`（既定）では core は `127.0.0.1:8080` バインド。443 のみ開放
  し、別途 TLS リバースプロキシ / ALB を前段に。検証用に即アクセスしたいなら
  `http_public=true`（8080 開放 + 0.0.0.0 バインド）。
- IMDSv2 必須、root EBS は gp3 暗号化。
- `couchdb_secret_id` を指定すると、その Secret への `GetSecretValue` のみ許可する
  IAM ポリシーが付与され、ブートストラップがパスワードを Secrets Manager から取得。
  未指定ならホスト上でランダム生成（`/opt/nemakiware/src/docker/.env` に保存）。

## Azure

```bash
cd deploy/terraform/azure
cp terraform.tfvars.example terraform.tfvars   # ssh_public_key を必ず設定
terraform init
terraform apply
terraform output core_url
```

- Ubuntu 22.04 LTS (gen2) を使用。`ssh_public_key` は必須。
- `use_existing_rg=true` で既存リソースグループに配置可。
- `couchdb_keyvault_secret_uri` を指定すると VM にシステム割り当て ID が付与され、
  `identity_principal_id` を output。**その ID に Key Vault の `get` 権限を付与**して
  ください（モジュール外。ボールトの所有関係に依存するため意図的に分離）。

---

## 共通の注意

- **起動後すぐにやること**: 管理 UI の初期ログイン `admin/admin` を変更、TLS 前段を
  構成、CouchDB/Solr の volume をスナップショット。詳細は
  [`deploy/README.md`](../README.md) のハードニング checklist。
- **バージョン更新**: `nemaki_version`（と必要なら `nemaki_ref`）を変更して
  `terraform apply`。`user_data` が変わるため**インスタンスは置換**されます
  （`user_data_replace_on_change=true`）。データは named volume にあるため、
  同一ホストの in-place 更新を望む場合は VM 上で
  `docker compose pull && up -d` を直接実行してください（README 参照）。
- **プライベートイメージ**: registry が private の場合は VM 側で
  `docker login` が必要。[`deploy/README.md`](../README.md) の該当節を参照。
- **state**: 例はローカル state。本番は S3+DynamoDB / azurerm backend など
  リモート state を設定してください。
