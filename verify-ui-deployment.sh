#!/bin/bash
#
# verify-ui-deployment.sh - UIコード反映を検証する包括的スクリプト
#
# 目的:
# - UIソースコードの変更がデプロイに正しく反映されることを保証
# - コンテナ再構築と初期化を含む完全なデプロイプロセスを実行
# - デプロイ後のUI動作を検証
#
# 使用方法:
#   ./verify-ui-deployment.sh              # フルデプロイ + 検証
#   ./verify-ui-deployment.sh --verify-only # 検証のみ（デプロイ済み環境）
#   ./verify-ui-deployment.sh --quick       # クイックデプロイ（イメージ再利用）
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UI_DIR="${SCRIPT_DIR}/core/src/main/webapp/ui"
DOCKER_DIR="${SCRIPT_DIR}/docker"
CORE_TARGET="${SCRIPT_DIR}/core/target"

# カラー出力
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 引数解析
VERIFY_ONLY=false
QUICK_MODE=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --verify-only) VERIFY_ONLY=true; shift ;;
        --quick) QUICK_MODE=true; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

echo "=============================================="
echo "NemakiWare UI デプロイ検証スクリプト"
echo "=============================================="
echo "実行日時: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# =============================================================================
# Step 1: 事前検証
# =============================================================================
verify_prerequisites() {
    log_info "Step 1: 事前検証"

    # Java 17確認
    JAVA_VERSION=$(java -version 2>&1 | head -1)
    if [[ ! "$JAVA_VERSION" =~ "17" ]]; then
        log_error "Java 17が必要です。現在: $JAVA_VERSION"
        exit 1
    fi
    log_success "Java 17確認: OK"

    # Docker確認
    if ! docker info > /dev/null 2>&1; then
        log_error "Dockerが起動していません"
        exit 1
    fi
    log_success "Docker確認: OK"

    # Node.js確認
    if ! command -v node &> /dev/null; then
        log_error "Node.jsがインストールされていません"
        exit 1
    fi
    NODE_VERSION=$(node -v)
    log_success "Node.js確認: $NODE_VERSION"

    echo ""
}

# =============================================================================
# Step 2: クリーンビルド
# =============================================================================
clean_build() {
    log_info "Step 2: クリーンビルド"

    # UIビルド成果物を削除
    log_info "UIビルド成果物を削除中..."
    rm -rf "${UI_DIR}/dist/"

    # Mavenターゲットを削除
    log_info "Mavenターゲットを削除中..."
    rm -rf "${CORE_TARGET}/"

    log_success "クリーンアップ完了"
    echo ""
}

# =============================================================================
# Step 3: Mavenビルド（frontend-maven-pluginがUIも自動ビルド）
# =============================================================================
maven_build() {
    log_info "Step 3: Mavenビルド（UIビルドを含む）"

    cd "${SCRIPT_DIR}"

    # Mavenビルド実行
    log_info "mvn package実行中（約3-5分）..."
    mvn package -f core/pom.xml -Pdevelopment -DskipTests -q

    # WARファイル確認
    if [ ! -f "${CORE_TARGET}/core.war" ]; then
        log_error "WARファイルが生成されませんでした"
        exit 1
    fi

    WAR_SIZE=$(ls -lh "${CORE_TARGET}/core.war" | awk '{print $5}')
    log_success "WARファイル生成: ${WAR_SIZE}"

    # アセットハッシュを取得
    ASSET_HASH=$(grep -o 'index-[A-Za-z0-9_-]*\.js' "${CORE_TARGET}/core/ui/index.html" 2>/dev/null | head -1 | sed 's/index-\(.*\)\.js/\1/')
    if [ -z "$ASSET_HASH" ]; then
        log_error "アセットハッシュを取得できませんでした"
        exit 1
    fi
    echo "  アセットハッシュ: ${ASSET_HASH}"

    # グローバル変数として保存
    export BUILD_ASSET_HASH="${ASSET_HASH}"

    echo ""
}

# =============================================================================
# Step 4: Dockerデプロイ
# =============================================================================
docker_deploy() {
    log_info "Step 4: Dockerデプロイ"

    # WARをDockerコンテキストにコピー
    log_info "WARファイルをDockerコンテキストにコピー..."
    cp "${CORE_TARGET}/core.war" "${DOCKER_DIR}/core/core.war"

    cd "${DOCKER_DIR}"

    if [ "$QUICK_MODE" = true ]; then
        # クイックモード: コンテナ再起動のみ
        log_info "クイックモード: コンテナ再起動..."
        docker compose -f docker-compose-simple.yml restart core
    else
        # フルモード: イメージ再構築
        log_info "コンテナを停止中..."
        docker compose -f docker-compose-simple.yml down

        log_info "イメージを再構築中..."
        docker compose -f docker-compose-simple.yml up -d --build --force-recreate
    fi

    # コンテナ起動を待機
    log_info "コンテナ起動を待機中（最大2分）..."
    for i in {1..24}; do
        STATUS=$(docker inspect --format='{{.State.Health.Status}}' docker-core-1 2>/dev/null || echo "not_found")
        if [ "$STATUS" = "healthy" ]; then
            log_success "コンテナ起動完了"
            break
        fi
        echo -n "."
        sleep 5
    done
    echo ""

    if [ "$STATUS" != "healthy" ]; then
        log_error "コンテナ起動タイムアウト（ステータス: $STATUS）"
        docker logs docker-core-1 --tail 30
        exit 1
    fi

    echo ""
}

# =============================================================================
# Step 5: デプロイ検証
# =============================================================================
verify_deployment() {
    log_info "Step 5: デプロイ検証"

    # 5.1: 基本接続確認
    log_info "5.1: 基本接続確認..."
    HTTP_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -u admin:admin http://localhost:8080/core/atom/bedroom)
    if [ "$HTTP_STATUS" != "200" ]; then
        log_error "CMIS接続失敗 (HTTP $HTTP_STATUS)"
        exit 1
    fi
    log_success "CMIS接続: OK (HTTP $HTTP_STATUS)"

    # 5.2: UI接続確認
    log_info "5.2: UI接続確認..."
    UI_STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/)
    if [ "$UI_STATUS" != "200" ]; then
        log_error "UI接続失敗 (HTTP $UI_STATUS)"
        exit 1
    fi
    log_success "UI接続: OK (HTTP $UI_STATUS)"

    # 5.3: アセットハッシュ検証
    log_info "5.3: アセットハッシュ検証..."
    DEPLOYED_HASH=$(curl -s http://localhost:8080/core/ui/ | grep -o 'index-[A-Za-z0-9_-]*\.js' | head -1 | sed 's/index-\(.*\)\.js/\1/')

    if [ -n "$BUILD_ASSET_HASH" ]; then
        if [ "$DEPLOYED_HASH" = "$BUILD_ASSET_HASH" ]; then
            log_success "アセットハッシュ一致: ${DEPLOYED_HASH}"
        else
            log_error "アセットハッシュ不一致!"
            echo "  ビルド時: ${BUILD_ASSET_HASH}"
            echo "  デプロイ後: ${DEPLOYED_HASH}"
            exit 1
        fi
    else
        log_info "デプロイ済みアセットハッシュ: ${DEPLOYED_HASH}"
    fi

    # 5.4: アセットファイル取得確認
    log_info "5.4: アセットファイル取得確認..."
    JS_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/core/ui/assets/index-${DEPLOYED_HASH}.js")
    CSS_STATUS=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/core/ui/assets/index-BaB_POPR.css")

    if [ "$JS_STATUS" = "200" ] && [ "$CSS_STATUS" = "200" ]; then
        log_success "アセットファイル: OK (JS: $JS_STATUS, CSS: $CSS_STATUS)"
    else
        log_error "アセットファイル取得失敗 (JS: $JS_STATUS, CSS: $CSS_STATUS)"
        exit 1
    fi

    # 5.5: OIDC/SAMLコールバック確認
    log_info "5.5: 認証コールバック確認..."
    OIDC_STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/oidc-callback.html)
    SAML_STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/saml-callback.html)

    if [ "$OIDC_STATUS" = "200" ] && [ "$SAML_STATUS" = "200" ]; then
        log_success "認証コールバック: OK (OIDC: $OIDC_STATUS, SAML: $SAML_STATUS)"
    else
        log_warn "認証コールバック警告 (OIDC: $OIDC_STATUS, SAML: $SAML_STATUS)"
    fi

    # 5.6: PDF Worker確認
    log_info "5.6: PDF Worker確認..."
    PDF_WORKER_STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/core/ui/pdf-worker/pdf.worker.min.mjs)
    if [ "$PDF_WORKER_STATUS" = "200" ]; then
        log_success "PDF Worker: OK (HTTP $PDF_WORKER_STATUS)"
    else
        log_warn "PDF Worker警告 (HTTP $PDF_WORKER_STATUS)"
    fi

    echo ""
}

# =============================================================================
# Step 6: 機能検証
# =============================================================================
verify_functionality() {
    log_info "Step 6: 機能検証"

    # 6.1: リポジトリ情報取得
    log_info "6.1: リポジトリ情報取得..."
    REPO_INFO=$(curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo")
    if echo "$REPO_INFO" | grep -q "bedroom"; then
        log_success "リポジトリ情報: OK"
    else
        log_error "リポジトリ情報取得失敗"
        exit 1
    fi

    # 6.2: ルートフォルダ取得
    log_info "6.2: ルートフォルダ取得..."
    ROOT_CHILDREN=$(curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/root?cmisselector=children")
    if echo "$ROOT_CHILDREN" | grep -q "objects"; then
        log_success "ルートフォルダ: OK"
    else
        log_error "ルートフォルダ取得失敗"
        exit 1
    fi

    # 6.3: タイプ定義取得
    log_info "6.3: タイプ定義取得..."
    TYPE_DEF=$(curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom/type?typeId=cmis:document&cmisselector=typeDefinition")
    if echo "$TYPE_DEF" | grep -q "cmis:document"; then
        log_success "タイプ定義: OK"
    else
        log_error "タイプ定義取得失敗"
        exit 1
    fi

    # 6.4: CouchDB接続
    log_info "6.4: CouchDB接続..."
    DB_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -u admin:password http://localhost:5984/bedroom)
    if [ "$DB_STATUS" = "200" ]; then
        DOC_COUNT=$(curl -s -u admin:password http://localhost:5984/bedroom | grep -o '"doc_count":[0-9]*' | cut -d: -f2)
        log_success "CouchDB接続: OK (ドキュメント数: $DOC_COUNT)"
    else
        log_error "CouchDB接続失敗 (HTTP $DB_STATUS)"
        exit 1
    fi

    # 6.5: Solr接続
    log_info "6.5: Solr接続..."
    SOLR_STATUS=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8983/solr/admin/cores?action=STATUS)
    if [ "$SOLR_STATUS" = "200" ]; then
        log_success "Solr接続: OK"
    else
        log_warn "Solr接続警告 (HTTP $SOLR_STATUS)"
    fi

    echo ""
}

# =============================================================================
# 結果サマリー
# =============================================================================
print_summary() {
    echo "=============================================="
    echo "検証完了サマリー"
    echo "=============================================="
    echo ""
    echo "デプロイ済みアセット:"
    echo "  ハッシュ: ${DEPLOYED_HASH:-N/A}"
    echo "  URL: http://localhost:8080/core/ui/"
    echo ""
    echo "検証結果: ${GREEN}ALL PASS${NC}"
    echo ""
    echo "手動確認事項:"
    echo "  1. ブラウザで http://localhost:8080/core/ui/ を開く"
    echo "  2. admin/admin でログイン"
    echo "  3. ドキュメント一覧が表示されることを確認"
    echo "  4. UIの変更が反映されていることを確認"
    echo ""
    echo "=============================================="
}

# =============================================================================
# メイン処理
# =============================================================================
main() {
    verify_prerequisites

    if [ "$VERIFY_ONLY" = true ]; then
        log_info "検証のみモード（デプロイをスキップ）"
    else
        clean_build
        maven_build
        docker_deploy
    fi

    verify_deployment
    verify_functionality
    print_summary
}

main
