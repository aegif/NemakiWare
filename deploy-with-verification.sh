#!/bin/bash
#
# deploy-with-verification.sh - UIビルド・デプロイの整合性を保証するスクリプト
#
# 問題の再発防止策:
# 1. UIビルド後にアセットハッシュを記録
# 2. WARビルド後にWAR内のアセットハッシュを検証
# 3. デプロイ後にサーバー上のアセットハッシュを検証
# 4. 全てのハッシュが一致することを確認
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UI_DIR="${SCRIPT_DIR}/core/src/main/webapp/ui"
DOCKER_DIR="${SCRIPT_DIR}/docker"

echo "=============================================="
echo "NemakiWare デプロイ検証スクリプト"
echo "=============================================="
echo ""

# Step 1: UIビルド
echo "=== Step 1: UIビルド ==="
cd "${UI_DIR}"

# CRITICAL: 古いビルド成果物を完全に削除
echo "Cleaning old build artifacts..."
rm -rf dist/

npm run build 2>&1 | tail -5

# アセットハッシュを取得（index.htmlから参照されているファイル）
UI_ASSET_HASH=$(grep -o 'index-[A-Za-z0-9_-]*\.js' dist/index.html | head -1 | sed 's/index-\(.*\)\.js/\1/')
if [ -z "$UI_ASSET_HASH" ]; then
    echo "ERROR: index.html内にindex-*.js参照が見つかりません"
    exit 1
fi
echo "UIアセットハッシュ: ${UI_ASSET_HASH}"
echo ""

# Step 2: WARビルド
echo "=== Step 2: WARビルド ==="
cd "${SCRIPT_DIR}"

# CRITICAL: Mavenターゲットを完全に削除して古いアセットを根絶
echo "Cleaning Maven target directory..."
rm -rf core/target/

mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# WAR内のアセットを検証
WAR_FILE="${SCRIPT_DIR}/core/target/core.war"
if [ ! -f "$WAR_FILE" ]; then
    echo "ERROR: WARファイルが見つかりません: $WAR_FILE"
    exit 1
fi

# WAR内のindex.htmlからアセットハッシュを確認
WAR_ASSET_HASH=$(unzip -p "$WAR_FILE" "ui/dist/index.html" 2>/dev/null | grep -o 'index-[A-Za-z0-9_-]*\.js' | head -1 | sed 's/index-\(.*\)\.js/\1/')
if [ -z "$WAR_ASSET_HASH" ]; then
    echo "ERROR: WAR内のindex.htmlにアセット参照が見つかりません"
    exit 1
fi

if [ "$UI_ASSET_HASH" != "$WAR_ASSET_HASH" ]; then
    echo "ERROR: WARビルド不整合!"
    echo "  UIビルド: ${UI_ASSET_HASH}"
    echo "  WAR内: ${WAR_ASSET_HASH}"
    exit 1
fi
echo "WAR内アセットハッシュ: ${WAR_ASSET_HASH} (一致)"
echo ""

# Step 3: Dockerデプロイ
echo "=== Step 3: Dockerデプロイ ==="
cp "${WAR_FILE}" "${DOCKER_DIR}/core/core.war"

# WARファイルのタイムスタンプを確認
echo "WAR copied: $(ls -la ${DOCKER_DIR}/core/core.war)"

cd "${DOCKER_DIR}"

# 完全なクリーンアップ（キャッシュ根絶）
echo "Cleaning up Docker resources..."
docker compose -f docker-compose-simple.yml down -v 2>/dev/null || true
docker rmi docker-core 2>/dev/null || true

# 強制リビルド
echo "Building with --no-cache..."
docker compose -f docker-compose-simple.yml build --no-cache core 2>&1 | tail -5
docker compose -f docker-compose-simple.yml up -d --force-recreate 2>&1 | tail -10

echo ""
echo "=== Step 4: サーバー起動待機 (90秒) ==="
sleep 90

# コンテナ状態確認
HEALTHY_COUNT=$(docker ps --filter "health=healthy" --format "{{.Names}}" | wc -l | tr -d ' ')
echo "Healthyコンテナ数: ${HEALTHY_COUNT}"

if [ "$HEALTHY_COUNT" -lt 3 ]; then
    echo "WARNING: 一部のコンテナがhealthyではありません"
    docker ps --format "table {{.Names}}\t{{.Status}}"
fi

# Step 5: デプロイ検証
echo ""
echo "=== Step 5: デプロイ検証 ==="

# サーバーからアセットハッシュを取得
DEPLOYED_ASSET=$(curl -s http://localhost:8080/core/ui/ | grep -o 'index-[A-Za-z0-9_-]*\.js' | head -1)
DEPLOYED_HASH=$(echo "$DEPLOYED_ASSET" | sed 's/index-\(.*\)\.js/\1/')

if [ -z "$DEPLOYED_HASH" ]; then
    echo "ERROR: デプロイされたアセットを取得できません"
    exit 1
fi

echo "検証結果:"
echo "  UIビルド:     index-${UI_ASSET_HASH}.js"
echo "  WAR内:        index-${WAR_ASSET_HASH}.js"
echo "  デプロイ済み: ${DEPLOYED_ASSET}"

if [ "$UI_ASSET_HASH" != "$DEPLOYED_HASH" ]; then
    echo ""
    echo "ERROR: デプロイ不整合!"
    echo "  期待: ${UI_ASSET_HASH}"
    echo "  実際: ${DEPLOYED_HASH}"
    exit 1
fi

echo ""
echo "=============================================="
echo "SUCCESS: 全てのアセットハッシュが一致"
echo "=============================================="

# APIテスト
echo ""
echo "=== Step 6: 基本APIテスト ==="
API_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin "http://localhost:8080/core/atom/bedroom")
if [ "$API_STATUS" = "200" ]; then
    echo "CMIS API: OK (HTTP ${API_STATUS})"
else
    echo "ERROR: CMIS API failed (HTTP ${API_STATUS})"
    exit 1
fi

echo ""
echo "デプロイ完了!"
