# Amazon Bedrock Embedding セットアップガイド

> **[BETA]** この機能はベータ版です。本番利用前に十分なテストを行ってください。

## 概要

NemakiWare のセマンティック検索（RAG）では、ドキュメントをベクトル（数値配列）に変換し、意味的な類似度で検索を行います。このベクトル変換（Embedding）を担う仕組みとして、2 つのプロバイダが選択できます。

| | TEI（デフォルト） | Amazon Bedrock |
|---|---|---|
| **仕組み** | 自前ホストの Docker コンテナ (Hugging Face) | AWS のフルマネージド AI 推論 API |
| **インフラ管理** | TEI コンテナの運用が必要（メモリ、スケーリング、モデル更新） | 不要。API 呼び出しのみ |
| **コスト** | EC2 メモリ/CPU（コンテナ常駐） | API 従量課金（リクエスト単位） |
| **レイテンシ** | 低（ローカル推論） | 中（ネットワーク API コール） |
| **モデル** | intfloat/multilingual-e5-large | Amazon Titan Text Embeddings V2 |
| **ベクトル次元数** | 1024 | 1024 |
| **多言語対応** | 100 言語以上 | 100 言語以上 |
| **SLA** | 自己管理 | AWS の SLA に基づく |
| **推奨ケース** | 大量ドキュメント、低レイテンシ重視、クラウド非依存 | AWS ネイティブ運用、コンテナ数削減、運用負荷軽減 |

Bedrock を選択すると TEI コンテナが不要になり、AWS のセキュリティ基盤・SLA の上で高品質なセマンティック検索が実現できます。

---

## AWS 側の準備

Bedrock Embedding を利用するには、以下の 3 ステップを AWS 側で完了する必要があります。

### Step 1: Amazon Bedrock のモデルアクセスを有効化

Bedrock のモデルはデフォルトでは利用不可です。AWS コンソールから明示的に有効化します。

1. **AWS Management Console** にサインイン
2. 上部のリージョン選択で **利用したいリージョン** を選択
   - 推奨: `ap-northeast-1`（東京）または `us-east-1`（バージニア北部）
   - Titan Text Embeddings V2 の対応リージョンは [AWS ドキュメント](https://docs.aws.amazon.com/bedrock/latest/userguide/models-regions.html) を参照
3. **Amazon Bedrock** サービスを検索して開く
4. 左メニューの **Bedrock configurations** → **Model access** をクリック
5. **Modify model access** をクリック
6. **Amazon** セクションから **Titan Text Embeddings V2** を探してチェック
7. **Next** → **Submit** をクリック
8. ステータスが **Access granted** になるまで待機（通常 1〜2 分）

> **注意**: 組織の AWS アカウントで Service Control Policy (SCP) が適用されている場合、Bedrock へのアクセスが制限されていることがあります。その場合は組織の AWS 管理者に相談してください。

### Step 2: IAM ポリシーの作成とアタッチ

NemakiWare が Bedrock API を呼び出すための IAM 権限を設定します。

#### 認証方式の選択

| 方式 | 設定場所 | セキュリティ | 推奨用途 |
|---|---|---|---|
| **EC2 インスタンスロール** | IAM ロール → EC2 | 高（認証情報なし） | EC2 上での本番運用 |
| **明示的アクセスキー** | NemakiWare 設定 | 中（鍵の管理が必要） | ローカル開発、非 EC2 環境 |
| **環境変数** | `AWS_ACCESS_KEY_ID` 等 | 中 | Docker 環境 |

#### 方式 A: EC2 インスタンスロール（推奨）

EC2 上で運用する場合、インスタンスロールを使うのが最もセキュアです。

1. **IAM コンソール** → **ロール** → EC2 にアタッチしているロールを選択（なければ新規作成）
2. **許可を追加** → **インラインポリシーを作成**
3. **JSON** タブに以下を貼り付け:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "NemakiWareBedrock",
            "Effect": "Allow",
            "Action": "bedrock:InvokeModel",
            "Resource": "arn:aws:bedrock:*::foundation-model/amazon.titan-embed-text-v2:0"
        }
    ]
}
```

4. ポリシー名: `NemakiWare-Bedrock-Embedding` → **ポリシーの作成**
5. EC2 インスタンスにロールがアタッチされていることを確認

> **Docker 環境の注意**: EC2 インスタンスロールを Docker コンテナ内から使用するには、EC2 メタデータサービス (IMDS) のホップ制限を 2 に設定する必要があります:
> ```bash
> aws ec2 modify-instance-metadata-options \
>   --instance-id i-xxxxxxxx \
>   --http-put-response-hop-limit 2
> ```

#### 方式 B: IAM アクセスキー（非 EC2 環境）

EC2 以外の環境（ローカル開発、オンプレミス等）では IAM ユーザーのアクセスキーを使用します。

1. **IAM コンソール** → **ユーザー** → **ユーザーを作成**
2. ユーザー名: `nemakiware-bedrock` → **次へ**
3. **ポリシーを直接アタッチ** → 上記の JSON ポリシーと同じものを作成してアタッチ
4. ユーザーを作成後、**セキュリティ認証情報** タブ → **アクセスキーを作成**
5. **アクセスキー ID** と **シークレットアクセスキー** を控える

> **重要**: シークレットアクセスキーは作成時にしか表示されません。安全な場所に保管してください。

### Step 3: 動作確認（AWS CLI）

設定が正しいことを AWS CLI で確認します。

```bash
# AWS CLI がインストールされていない場合
# https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html

# 認証情報の確認
aws sts get-caller-identity

# Bedrock Embedding テスト
aws bedrock-runtime invoke-model \
  --model-id amazon.titan-embed-text-v2:0 \
  --body '{"inputText": "テスト文書です"}' \
  --region ap-northeast-1 \
  --content-type "application/json" \
  --accept "application/json" \
  /dev/stdout | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'OK: {len(d[\"embedding\"])} dimensions')"
```

期待される出力:
```
OK: 1024 dimensions
```

このコマンドが成功すれば、AWS 側の準備は完了です。

---

## NemakiWare 側の設定

AWS 側の準備が完了したら、NemakiWare を Bedrock に接続します。2 つの方法があります。

### 方法 1: Setup Wizard（GUI）

NemakiWare の初回セットアップ時、または管理画面から Setup Wizard を使って設定できます。

1. Setup Wizard の **ベクトル検索設定** ステップに進む
2. **プロバイダ** で **Amazon Bedrock** を選択
3. 以下を入力:
   - **リージョン**: Step 1 で有効化したリージョン（例: `ap-northeast-1`）
   - **モデル ID**: `amazon.titan-embed-text-v2:0`（デフォルト）
   - **アクセスキー ID**: 方式 B の場合のみ入力（EC2 ロール使用時は空欄）
   - **シークレットアクセスキー**: 方式 B の場合のみ入力
4. **接続テスト** ボタンをクリック
   - 成功: `接続成功 (1024次元)` と表示
   - 失敗: エラーメッセージに従って設定を修正
5. **適用** をクリック

### 方法 2: nemakiware.properties（手動設定）

`nemakiware.properties` に以下を追加します:

```properties
# RAG セマンティック検索の有効化
rag.enabled=true

# プロバイダを Bedrock に設定
rag.embedding.provider=bedrock

# Bedrock 接続設定
rag.bedrock.region=ap-northeast-1
rag.bedrock.model.id=amazon.titan-embed-text-v2:0
rag.bedrock.vector.dimension=1024
rag.bedrock.timeout.ms=30000
rag.bedrock.max.input.chars=8000

# 明示的 AWS 認証情報（EC2 インスタンスロール使用時は空欄のまま）
#rag.bedrock.access.key.id=AKIAxxxxxxxxxxxxxxxx
#rag.bedrock.secret.access.key=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

設定後、NemakiWare を再起動してください。

### Docker Compose での起動

Bedrock 使用時は TEI コンテナが不要なため、`rag` プロファイルなしで起動します:

```bash
cd docker
# TEI なしで起動
docker compose -f docker-compose-simple.yml up -d --build --force-recreate
```

これにより TEI コンテナ分のメモリ（2〜4GB）が節約できます。

---

## 動作確認

### ログでの確認

NemakiWare 起動後、ログに以下が表示されることを確認:

```
RAGConfig initialized
RAG enabled: true
Embedding provider: bedrock
[BETA] Bedrock embedding provider is beta - requires AWS subscription
Bedrock region: ap-northeast-1
Bedrock model id: amazon.titan-embed-text-v2:0
Bedrock vector dimension: 1024
```

### セマンティック検索のテスト

1. NemakiWare にテスト用ドキュメントをアップロード
2. UI の検索バーでセマンティック検索を実行
3. 意味的に関連するドキュメントが返されることを確認

### ヘルスチェック

Bedrock の接続状態は NemakiWare 内部で定期的にチェックされます（成功時 30 秒、失敗時 10 秒間隔）。
ヘルスチェックは軽量な InvokeModel 呼び出しで実際の API 接続性を検証します。

---

## コスト

### Titan Text Embeddings V2 の料金

| 項目 | 料金（2026年3月時点の目安） |
|---|---|
| 入力トークン | $0.00002 / 1,000 トークン |
| 最小課金単位 | なし |
| 無料枠 | なし（Bedrock 初回利用時の無料トライアルを除く） |

### コスト試算例

| シナリオ | ドキュメント数 | チャンク数 | 月間検索数 | 推定月額 |
|---|---|---|---|---|
| 小規模 | 1,000 | 5,000 | 1,000 | < $1 |
| 中規模 | 10,000 | 50,000 | 10,000 | ~$5 |
| 大規模 | 100,000 | 500,000 | 50,000 | ~$30 |

> **注意**: 実際の料金は AWS の最新料金表を確認してください。上記は目安です。
> インデックス作成時（ドキュメント登録）と検索時の両方で API コールが発生します。

### TEI との比較

| | TEI (CPU) | Bedrock |
|---|---|---|
| 固定費 | EC2 メモリ 2-4GB 分 | なし |
| 変動費 | なし | API 従量課金 |
| 損益分岐 | ドキュメント数が多く検索頻度が高い場合に有利 | ドキュメント数が少ない〜中規模で有利 |

---

## トラブルシューティング

### `AccessDeniedException`

```
User: arn:aws:iam::123456789012:... is not authorized to perform: bedrock:InvokeModel
```

**原因**: IAM 権限が不足

**対処**:
1. IAM ポリシーが正しくアタッチされているか確認
2. ポリシーの `Resource` が正しいモデル ARN を指しているか確認
3. SCP による制限がないか確認

### `ResourceNotFoundException`

```
Could not resolve the foundation model from the provided model identifier
```

**原因**: モデルアクセスが未有効化、またはリージョンが未対応

**対処**:
1. Step 1 のモデルアクセス有効化を再確認
2. 選択したリージョンで Titan Text Embeddings V2 が利用可能か確認
3. モデル ID が正しいか確認（`amazon.titan-embed-text-v2:0`）

### `Connection refused` / タイムアウト

**原因**: ネットワーク接続の問題

**対処**:
1. EC2 の Security Group で HTTPS (443) のアウトバウンドが許可されているか確認
2. VPC にインターネットゲートウェイ / NAT ゲートウェイがあるか確認
3. Docker コンテナからインターネットにアクセスできるか確認:
   ```bash
   docker exec docker-core-1 curl -s https://bedrock-runtime.ap-northeast-1.amazonaws.com
   ```

### 次元数ミスマッチ警告

```
Unexpected embedding dimension: expected 1024, got 256
```

**原因**: モデルが返すベクトル次元数と `rag.bedrock.vector.dimension` の不一致

**対処**:
- Solr スキーマは `knn_vector_1024`（1024 次元）で固定されています
- Titan Text Embeddings V2 のデフォルトは 1024 次元で、そのまま利用できます
- 別のモデルを使用する場合は Solr スキーマの変更が必要です

### Setup Wizard の接続テストは成功するが実運用で失敗する

**原因**: Setup Wizard で保存した設定は CouchDB（nemaki_conf）に永続化され、`RAGConfig` が `PropertyManager` 経由で動的に読み込むため、**再起動なしで反映されます**。テスト接続は成功するのに実運用で失敗する場合、以下を確認してください:

**対処**:
- 認証情報の確認: 明示的なアクセスキーが保存されている場合はそのキーの権限を確認。IAM ロール（デフォルト認証チェーン）を使用する場合は、Setup Wizard でアクセスキー/シークレットキーを空欄にして保存してください
- モデルアクセス権限: AWS コンソールで Bedrock の「Model access」が有効になっているか確認
- リージョンの一致: Setup Wizard で保存したリージョンに対象モデルがデプロイされているか確認

---

## 参考リンク

- [Amazon Bedrock ユーザーガイド](https://docs.aws.amazon.com/bedrock/latest/userguide/)
- [Titan Text Embeddings モデルカード](https://docs.aws.amazon.com/bedrock/latest/userguide/titan-embedding-models.html)
- [Bedrock 対応リージョン一覧](https://docs.aws.amazon.com/bedrock/latest/userguide/models-regions.html)
- [Bedrock 料金](https://aws.amazon.com/bedrock/pricing/)
- [IAM ポリシーの作成](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_create.html)
- [EC2 インスタンスロール](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/iam-roles-for-amazon-ec2.html)
- [NemakiWare AWS デプロイガイド](AWS-DEPLOYMENT-GUIDE.md) — セクション 7 にも Bedrock 設定の記載あり
