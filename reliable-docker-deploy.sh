#!/bin/bash

# NemakiWare 確実なDockerデプロイスクリプト
# Docker問題根絶のための完全手順

set -e  # エラー時に停止

# 色付きログ出力
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 開始時刻記録
START_TIME=$(date +%s)
log_info "NemakiWare確実デプロイ開始: $(date)"

# ========================================
# Step 1: 環境確認
# ========================================
log_info "Step 1: 環境確認"

# Java 17確認
if [ ! -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
    log_error "Java 17 OpenJDK not found. Please install OpenJDK 17."
    exit 1
fi

export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$PATH"

JAVA_VERSION=$(java -version 2>&1 | head -n 1)
log_success "Java環境: $JAVA_VERSION"

# プロジェクトディレクトリ確認
if [ ! -f "core/pom.xml" ]; then
    log_error "core/pom.xmlが見つかりません。プロジェクトルートで実行してください。"
    exit 1
fi

# ========================================
# Step 2: 完全クリーンアップ
# ========================================
log_info "Step 2: 完全クリーンアップ"

# Docker環境停止
log_info "Docker環境停止中..."
cd docker
docker compose -f docker-compose-simple.yml down --remove-orphans 2>/dev/null || true

# Dockerイメージ削除 (core関連)
log_info "Docker core関連イメージ削除中..."
docker image rm $(docker images -q --filter "reference=*core*") 2>/dev/null || true
docker image rm $(docker images -q --filter "reference=docker_core") 2>/dev/null || true
docker image rm $(docker images -q --filter "reference=nemakiware/core") 2>/dev/null || true

# Docker system prune (キャッシュ完全削除)
log_info "Dockerキャッシュ完全削除中..."
docker system prune -f >/dev/null 2>&1

log_success "完全クリーンアップ完了"

# ========================================
# Step 3: 最新ソース修正からのOpenCMIS JAR完全リビルド
# ========================================
log_info "Step 3: 最新ソース修正からのOpenCMIS JAR完全リビルド"

cd /home/ubuntu/repos/NemakiWare

# Maven Localリポジトリの古いJARを削除（今日何度も発生している問題の根本対策）
log_warning "Maven Localリポジトリの古いJAR削除中（古いJAR問題根絶）..."
rm -rf ~/.m2/repository/org/apache/chemistry/opencmis/chemistry-opencmis-server-bindings/1.1.0-nemakiware/ 2>/dev/null || true

# 最新ソース修正からOpenCMIS JARリビルド
log_info "最新ソース修正からOpenCMIS JAR完全リビルド..."
OPENCMIS_BUILD_DIR="/home/ubuntu/repos/NemakiWare/lib/nemaki-opencmis-1.1.0-jakarta"

if [[ ! -d "$OPENCMIS_BUILD_DIR" ]]; then
    log_error "OpenCMIS build directory not found: $OPENCMIS_BUILD_DIR"
    exit 1
fi

# OpenCMIS server-bindings JAR完全リビルド
log_info "OpenCMIS server-bindings完全リビルド実行中..."
cd "$OPENCMIS_BUILD_DIR"
mvn clean package -f chemistry-opencmis-server/chemistry-opencmis-server-bindings/pom.xml -DskipTests -q

# 修正版JAR確認
FIXED_JAR="$OPENCMIS_BUILD_DIR/chemistry-opencmis-server/chemistry-opencmis-server-bindings/target/chemistry-opencmis-server-bindings-1.1.0-nemakiware.jar"

if [[ ! -f "$FIXED_JAR" ]]; then
    log_error "最新JAR生成失敗: $FIXED_JAR"
    exit 1
fi

# JAR情報確認
FIXED_JAR_SIZE=$(ls -la "$FIXED_JAR" | awk '{print $5}')
FIXED_JAR_TIMESTAMP=$(ls -l "$FIXED_JAR" | awk '{print $6, $7, $8}')
log_info "最新JAR情報: サイズ=$FIXED_JAR_SIZE bytes, タイムスタンプ=$FIXED_JAR_TIMESTAMP"

# ソースファイルタイムスタンプと比較
SOURCE_TIMESTAMP=$(stat -c "%Y" "$OPENCMIS_BUILD_DIR/chemistry-opencmis-server/chemistry-opencmis-server-bindings/src/main/java/org/apache/chemistry/opencmis/server/impl/browser/MultipartParser.java")
JAR_TIMESTAMP=$(stat -c "%Y" "$FIXED_JAR")

if [ $JAR_TIMESTAMP -gt $SOURCE_TIMESTAMP ]; then
    TIME_DIFF=$((JAR_TIMESTAMP - SOURCE_TIMESTAMP))
    log_success "JAR更新確認: JARファイルはソースより ${TIME_DIFF}秒新しい"
else
    log_error "JAR更新失敗: JARファイルがソースより古い"
    exit 1
fi

# Maven repository に強制インストール（完全に新しいJAR）
log_info "最新JAR の Maven リポジトリ強制インストール..."
cd /home/ubuntu/repos/NemakiWare
mvn install:install-file \
    -Dfile="$FIXED_JAR" \
    -DgroupId=org.apache.chemistry.opencmis \
    -DartifactId=chemistry-opencmis-server-bindings \
    -Dversion=1.1.0-nemakiware \
    -Dpackaging=jar \
    -q

# Maven Localリポジトリの最新JAR検証
LOCAL_JAR_TIMESTAMP=$(stat -c "%Y" ~/.m2/repository/org/apache/chemistry/opencmis/chemistry-opencmis-server-bindings/1.1.0-nemakiware/chemistry-opencmis-server-bindings-1.1.0-nemakiware.jar)
if [ $LOCAL_JAR_TIMESTAMP -eq $JAR_TIMESTAMP ]; then
    log_success "Maven Localリポジトリ最新JAR確認完了"
else
    log_error "Maven Localリポジトリ更新失敗"
    exit 1
fi

log_success "最新ソース修正からの完全JAR統合完了"

# Maven clean package（最新JAR使用）
log_info "最新JAR使用でNemakiWare WAR完全リビルド..."
mvn clean package -f core/pom.xml -Pdevelopment -DskipTests -q

# WAR存在確認
if [ ! -f "core/target/core.war" ]; then
    log_error "core.warの生成に失敗しました"
    exit 1
fi

WAR_SIZE=$(ls -la core/target/core.war | awk '{print $5}')
log_success "WAR生成完了: core.war (${WAR_SIZE} bytes)"

# WAR内の修正版JAR検証
log_info "WAR内の修正版JAR統合検証..."
cd core/target
mkdir -p war_verification && cd war_verification
jar -xf ../core.war WEB-INF/lib/chemistry-opencmis-server-bindings-1.1.0-nemakiware.jar >/dev/null 2>&1

if [[ -f "WEB-INF/lib/chemistry-opencmis-server-bindings-1.1.0-nemakiware.jar" ]]; then
    WAR_JAR_SIZE=$(ls -la WEB-INF/lib/chemistry-opencmis-server-bindings-1.1.0-nemakiware.jar | awk '{print $5}')
    log_info "WAR内JAR情報: サイズ=$WAR_JAR_SIZE bytes"
    
    if [[ "$WAR_JAR_SIZE" == "$FIXED_JAR_SIZE" ]]; then
        log_success "✅ WAR内に修正版JAR(${FIXED_JAR_SIZE} bytes)が正常に統合されています"
    else
        log_error "❌ WAR内JAR(${WAR_JAR_SIZE} bytes) != 修正版JAR(${FIXED_JAR_SIZE} bytes)"
        log_error "修正版JAR統合が失敗しています。ビルドプロセスを確認してください。"
        exit 1
    fi
else
    log_error "WAR内に修正版JARが見つかりません"
    exit 1
fi

cd /home/ubuntu/repos/NemakiWare
rm -rf core/target/war_verification

# ========================================
# Step 4: タイムスタンプ検証付きコピー
# ========================================
log_info "Step 4: タイムスタンプ検証付きコピー"

# ソースファイルタイムスタンプ
SOURCE_TIMESTAMP=$(stat -c "%Y" core/src/main/java/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.java)
log_info "ソースファイルタイムスタンプ: $SOURCE_TIMESTAMP"

# WARファイルコピー
cp core/target/core.war docker/core/core.war
WAR_TIMESTAMP=$(stat -c "%Y" docker/core/core.war)
COPY_TIME=$(date)

log_info "WARコピー完了: $COPY_TIME"
log_info "Dockerコンテキスト WAR タイムスタンプ: $WAR_TIMESTAMP"

# タイムスタンプ検証
if [ $WAR_TIMESTAMP -gt $SOURCE_TIMESTAMP ]; then
    TIME_DIFF=$((WAR_TIMESTAMP - SOURCE_TIMESTAMP))
    log_success "タイムスタンプ検証OK: WARファイルはソースより ${TIME_DIFF}秒新しい"
else
    log_error "タイムスタンプ検証失敗: WARファイルがソースより古い"
    exit 1
fi

# ========================================
# Step 5: 強制リビルド
# ========================================
log_info "Step 5: 強制リビルド"

cd docker

# Docker Compose強制リビルド
log_info "Docker Compose強制リビルド実行中..."
docker compose -f docker-compose-simple.yml up -d --build --force-recreate

log_success "Docker環境起動完了"

# ========================================
# Step 6: 必須検証
# ========================================
log_info "Step 6: 必須検証 (90秒待機)"

# 起動待機
sleep 90

# 検証1: コンテナ状態確認
log_info "コンテナ状態確認..."
CONTAINER_STATUS=$(docker ps --filter "name=docker-core-1" --format "{{.Status}}")
if [[ $CONTAINER_STATUS == *"Up"* ]]; then
    log_success "コンテナ正常稼働: $CONTAINER_STATUS"
else
    log_error "コンテナ起動失敗: $CONTAINER_STATUS"
    exit 1
fi

# 検証2: 修正版JAR デプロイ確認
log_info "修正版JAR デプロイ確認..."
CONTAINER_JAR_SIZE=$(docker exec docker-core-1 ls -la /usr/local/tomcat/webapps/core/WEB-INF/lib/chemistry-opencmis-server-bindings-1.1.0-nemakiware.jar | awk '{print $5}')
log_info "コンテナ内JAR情報: サイズ=$CONTAINER_JAR_SIZE bytes"

if [[ "$CONTAINER_JAR_SIZE" == "$FIXED_JAR_SIZE" ]]; then
    log_success "✅ 修正版JAR(${FIXED_JAR_SIZE} bytes)コンテナデプロイ確認完了"
else
    log_error "❌ 修正版JAR デプロイ失敗"
    log_error "期待: $FIXED_JAR_SIZE bytes, 実際: $CONTAINER_JAR_SIZE bytes"
    log_info "コンテナログ確認:"
    docker logs docker-core-1 --tail 20
    exit 1
fi

# 検証3: デバッグコードデプロイ確認
log_info "デバッグコードデプロイ確認..."
if docker exec docker-core-1 grep -a "CRITICAL STACK TRACE" \
   /usr/local/tomcat/webapps/core/WEB-INF/classes/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.class >/dev/null 2>&1; then
    log_success "✅ デバッグコードデプロイ確認完了"
else
    log_error "❌ デバッグコードデプロイ失敗"
    log_info "コンテナログ確認:"
    docker logs docker-core-1 --tail 20
    exit 1
fi

# 検証4: CMIS基本動作確認
log_info "CMIS基本動作確認..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin http://localhost:8080/core/atom/bedroom)
if [ "$HTTP_STATUS" = "200" ]; then
    log_success "✅ CMIS基本動作確認完了 (HTTP $HTTP_STATUS)"
else
    log_error "❌ CMIS基本動作確認失敗 (HTTP $HTTP_STATUS)"
    exit 1
fi

# 検証5: System Property設定確認
log_info "System Property設定確認..."
if docker logs docker-core-1 2>&1 | grep -q "nemaki.debug.disable.multipart=true" || \
   docker exec docker-core-1 ps aux | grep -q "nemaki.debug.disable.multipart=true"; then
    log_success "✅ System Property設定確認完了"
else
    log_warning "⚠️ System Property確認不完全 (後でランタイム確認推奨)"
fi

# ========================================
# Step 7: デプロイ完了報告
# ========================================
END_TIME=$(date +%s)
TOTAL_TIME=$((END_TIME - START_TIME))

log_success "========================================="
log_success "🎉 確実デプロイ完了!"
log_success "========================================="
log_success "実行時間: ${TOTAL_TIME}秒"
log_success "環境: http://localhost:8080/core/"
log_success "認証: admin:admin"
log_success "デバッグコード: 確実にデプロイ済み"
log_success "システム: Jakarta EE 10 + OpenCMIS 1.1.0-nemakiware"

# ========================================
# Step 8: 次のステップ案内
# ========================================
log_info ""
log_info "次のステップ:"
log_info "1. NemakiBrowserBindingServletデバッグ実行:"
log_info "   curl -u admin:admin -X POST -F 'cmisaction=createDocument' -F 'folderId=e02f784f8360a02cc14d1314c10038ff' -F 'propertyId[0]=cmis:objectTypeId' -F 'propertyValue[0]=cmis:document' -F 'propertyId[1]=cmis:name' -F 'propertyValue[1]=debug-test-\$(date +%s).txt' -F 'content=Debug test' 'http://localhost:8080/core/browser/bedroom'"
log_info ""
log_info "2. デバッグログ確認:"
log_info "   docker logs docker-core-1 --tail 50 | grep -A10 -B5 'CRITICAL STACK TRACE'"
log_info ""
log_info "3. 問題が発生した場合このスクリプトを再実行:"
log_info "   ./reliable-docker-deploy.sh"
log_info ""
