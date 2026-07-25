---
name: build-deploy
description: NemakiWare のビルドとデプロイ。UI (npm/vite) → Core (Maven WAR) → Docker compose の手順、ヘルスチェック、コンテナ起動失敗や「UI が更新されない」ときの切り分け。WAR を作り直す・再デプロイする・デプロイが反映されないときに読む。
---

# ビルド・デプロイ

## UI ビルド

```bash
cd core/src/main/webapp/ui
npm install
npm run build
```

## Core ビルド

```bash
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q
```

## Docker デプロイ

**`docker compose restart` は使用禁止。** WAR はイメージビルド時にコピーされるため
`restart` では古い WAR のまま動作します。必ず `--build --force-recreate`。

**必須 env**: 全 compose 構成で `COUCHDB_USER` / `COUCHDB_PASSWORD` を設定してください
(RC13 以降 `${VAR:?...}` で fail-fast)。LDAP / Keycloak profile では
`LDAP_ADMIN_PASSWORD` / `LDAP_CONFIG_PASSWORD` も必須。

```bash
cp core/target/core.war docker/core/core.war
cd docker

export COUCHDB_USER=admin
export COUCHDB_PASSWORD=password   # 本番では必ず強いパスワードに

# 全コンテナ再構築 (初回・完全リセット)
docker compose -f docker-compose-simple.yml down
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

# core のみ再構築 (通常のデプロイ)
docker compose -f docker-compose-simple.yml up -d --build --force-recreate core

sleep 90  # 起動待機
```

`down -v` は禁止です (初期化済み DB が消えます)。復旧は `ci-complete-setup.sh`。

## ヘルスチェック

```bash
curl -u admin:admin http://localhost:8080/core/atom/bedroom
# HTTP 200 + XML が正常
```

## トラブルシューティング

### コンテナ起動問題

```bash
docker logs docker-core-1 --tail 50
curl -u admin:password http://localhost:5984/_all_dbs
```

### UI が更新されない

1. `npm run build`
2. WAR 再ビルド
3. Docker 再起動 (`--force-recreate`)
4. ブラウザキャッシュクリア

## UI 開発サーバー

```bash
cd core/src/main/webapp/ui
npm run dev  # http://localhost:5173
```

- 翻訳ファイル: `src/i18n/locales/{ja,en}.json` (既定は日本語、
  localStorage キー `nemakiware-language`)
- 主要コンポーネント: `Layout.tsx` / `AuthContext.tsx` / `cmis.ts`

## 本番デプロイ

公開イメージ + cloud bootstrap は [`deploy/README.md`](../../../deploy/README.md)、
AWS は [`docs/AWS-DEPLOYMENT-GUIDE.md`](../../../docs/AWS-DEPLOYMENT-GUIDE.md)。
常設デモ環境 avenue.aegif.jp の更新手順は自動メモリ `avenue-deployment` を参照。
