#!/bin/bash
# NemakiWare 簡易負荷テスト
# 複数ユーザーからの同時操作をシミュレート

BASE_URL="http://localhost:8080/core"
REPO="bedroom"
AUTH="admin:admin"
ROOT_FOLDER="e02f784f8360a02cc14d1314c10038ff"

# 結果保存用
RESULTS_DIR="/tmp/nemaki-load-test-$$"
mkdir -p "$RESULTS_DIR"

# 色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo "=============================================="
echo "NemakiWare 簡易負荷テスト"
echo "=============================================="
echo ""

# ベースライン測定（単一リクエスト）
echo -e "${BLUE}=== Phase 1: ベースライン測定 (単一リクエスト) ===${NC}"

measure_request() {
    local name="$1"
    local url="$2"
    local method="${3:-GET}"
    local data="$4"

    if [ "$method" = "POST" ]; then
        result=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" -X POST $data "$url")
    else
        result=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" "$url")
    fi

    http_code=$(echo "$result" | cut -d',' -f1)
    time_ms=$(echo "$result" | cut -d',' -f2 | awk '{printf "%.0f", $1 * 1000}')

    if [ "$http_code" = "200" ]; then
        echo -e "  $name: ${GREEN}${time_ms}ms${NC} (HTTP $http_code)"
    else
        echo -e "  $name: ${RED}${time_ms}ms${NC} (HTTP $http_code)"
    fi
    echo "$time_ms" >> "$RESULTS_DIR/baseline_${name//[^a-zA-Z0-9]/_}.txt"
}

echo "単一リクエストのレスポンス時間を測定..."
measure_request "リポジトリ情報" "$BASE_URL/browser/$REPO?cmisselector=repositoryInfo"
measure_request "ルートフォルダ" "$BASE_URL/browser/$REPO/root?cmisselector=children"
measure_request "ドキュメント検索" "$BASE_URL/browser/$REPO?cmisselector=query&q=SELECT%20*%20FROM%20cmis:document%20WHERE%20cmis:name%20LIKE%20%27%25%27&maxItems=10"
measure_request "タイプ定義" "$BASE_URL/browser/$REPO/type?typeId=cmis:document&cmisselector=typeDefinition"

echo ""

# 並列リクエスト関数
run_parallel_requests() {
    local concurrent="$1"
    local operation="$2"
    local url="$3"
    local method="${4:-GET}"
    local data="$5"

    local start_time=$(date +%s.%N)
    local pids=()
    local result_file="$RESULTS_DIR/parallel_${concurrent}_${operation//[^a-zA-Z0-9]/_}.txt"

    for i in $(seq 1 $concurrent); do
        (
            if [ "$method" = "POST" ]; then
                result=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" -X POST $data "$url")
            else
                result=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" "$url")
            fi
            echo "$result"
        ) >> "$result_file" &
        pids+=($!)
    done

    # 全プロセス完了待ち
    for pid in "${pids[@]}"; do
        wait $pid
    done

    local end_time=$(date +%s.%N)
    local total_time=$(echo "$end_time - $start_time" | bc)

    # 結果集計
    local success=0
    local failed=0
    local total_response_time=0
    local min_time=999999
    local max_time=0

    while IFS=',' read -r code time; do
        time_ms=$(echo "$time" | awk '{printf "%.0f", $1 * 1000}')
        if [ "$code" = "200" ]; then
            ((success++))
        else
            ((failed++))
        fi
        total_response_time=$((total_response_time + time_ms))
        if [ "$time_ms" -lt "$min_time" ]; then min_time=$time_ms; fi
        if [ "$time_ms" -gt "$max_time" ]; then max_time=$time_ms; fi
    done < "$result_file"

    local avg_time=$((total_response_time / concurrent))
    local throughput=$(echo "scale=2; $concurrent / $total_time" | bc)

    if [ "$failed" -eq 0 ]; then
        status="${GREEN}OK${NC}"
    else
        status="${RED}FAIL($failed)${NC}"
    fi

    printf "  %-20s | %3d並列 | 成功:%3d | 平均:%5dms | 最小:%5dms | 最大:%5dms | %s | %.1f req/s\n" \
        "$operation" "$concurrent" "$success" "$avg_time" "$min_time" "$max_time" "$status" "$throughput"
}

# Phase 2: 同一操作の並列実行
echo -e "${BLUE}=== Phase 2: 同一操作の並列実行テスト ===${NC}"
echo ""

for concurrent in 5 10 20 50; do
    echo -e "${YELLOW}--- $concurrent 並列リクエスト ---${NC}"
    run_parallel_requests $concurrent "リポジトリ情報" "$BASE_URL/browser/$REPO?cmisselector=repositoryInfo"
    run_parallel_requests $concurrent "フォルダ一覧" "$BASE_URL/browser/$REPO/root?cmisselector=children"
    run_parallel_requests $concurrent "ドキュメント検索" "$BASE_URL/browser/$REPO?cmisselector=query&q=SELECT%20*%20FROM%20cmis:document&maxItems=10"
    echo ""
done

# Phase 3: 混合操作の並列実行（実際のユーザー操作をシミュレート）
echo -e "${BLUE}=== Phase 3: 混合操作の並列実行（実際のユーザー操作シミュレーション）===${NC}"
echo ""

run_mixed_workload() {
    local users="$1"
    local result_file="$RESULTS_DIR/mixed_${users}.txt"

    echo -e "${YELLOW}--- $users ユーザー同時操作 ---${NC}"

    local start_time=$(date +%s.%N)
    local pids=()

    for i in $(seq 1 $users); do
        (
            # 各ユーザーは複数の操作を順番に実行
            local user_start=$(date +%s.%N)

            # 1. ログイン（リポジトリ情報取得）
            r1=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" "$BASE_URL/browser/$REPO?cmisselector=repositoryInfo")

            # 2. フォルダ一覧表示
            r2=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" "$BASE_URL/browser/$REPO/root?cmisselector=children")

            # 3. ドキュメント検索
            r3=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" "$BASE_URL/browser/$REPO?cmisselector=query&q=SELECT%20*%20FROM%20cmis:document&maxItems=5")

            # 4. タイプ定義取得
            r4=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" -u "$AUTH" "$BASE_URL/browser/$REPO/type?typeId=cmis:document&cmisselector=typeDefinition")

            local user_end=$(date +%s.%N)
            local user_total=$(echo "$user_end - $user_start" | bc)

            # 各操作の結果を記録
            echo "user$i,$r1,$r2,$r3,$r4,$user_total"
        ) >> "$result_file" &
        pids+=($!)
    done

    # 全ユーザー完了待ち
    for pid in "${pids[@]}"; do
        wait $pid
    done

    local end_time=$(date +%s.%N)
    local total_time=$(echo "$end_time - $start_time" | bc)

    # 結果集計
    local total_ops=0
    local success_ops=0
    local failed_ops=0
    local total_user_time=0

    while IFS=',' read -r user r1 r2 r3 r4 user_time; do
        for r in "$r1" "$r2" "$r3" "$r4"; do
            code=$(echo "$r" | cut -d',' -f1 2>/dev/null || echo "0")
            ((total_ops++))
            if [ "$code" = "200" ]; then
                ((success_ops++))
            else
                ((failed_ops++))
            fi
        done
        ut=$(echo "$user_time" | awk '{printf "%.0f", $1 * 1000}')
        total_user_time=$((total_user_time + ut))
    done < "$result_file"

    local avg_user_time=$((total_user_time / users))
    local throughput=$(echo "scale=2; $total_ops / $total_time" | bc)

    echo "  ユーザー数: $users"
    echo "  総操作数: $total_ops (成功: $success_ops, 失敗: $failed_ops)"
    echo "  平均ユーザーセッション時間: ${avg_user_time}ms"
    echo "  総実行時間: $(printf '%.2f' $total_time)秒"
    echo "  スループット: ${throughput} ops/sec"

    if [ "$failed_ops" -eq 0 ]; then
        echo -e "  ステータス: ${GREEN}全操作成功${NC}"
    else
        echo -e "  ステータス: ${RED}${failed_ops}件の失敗${NC}"
    fi
    echo ""
}

run_mixed_workload 5
run_mixed_workload 10
run_mixed_workload 20
run_mixed_workload 30

# Phase 4: 書き込み操作の負荷テスト
echo -e "${BLUE}=== Phase 4: 書き込み操作の負荷テスト ===${NC}"
echo ""

run_write_test() {
    local concurrent="$1"
    local result_file="$RESULTS_DIR/write_${concurrent}.txt"

    echo -e "${YELLOW}--- $concurrent 並列ドキュメント作成 ---${NC}"

    local start_time=$(date +%s.%N)
    local pids=()
    local created_docs=()

    for i in $(seq 1 $concurrent); do
        (
            local doc_name="load-test-doc-$$-$i-$(date +%s%N)"

            # ドキュメント作成
            result=$(curl -s -w "\n%{http_code},%{time_total}" -u "$AUTH" -X POST \
                -F "cmisaction=createDocument" \
                -F "folderId=$ROOT_FOLDER" \
                -F "propertyId[0]=cmis:objectTypeId" \
                -F "propertyValue[0]=cmis:document" \
                -F "propertyId[1]=cmis:name" \
                -F "propertyValue[1]=$doc_name" \
                "$BASE_URL/browser/$REPO")

            # レスポンスからobjectIdを抽出
            body=$(echo "$result" | head -n -1)
            meta=$(echo "$result" | tail -1)
            code=$(echo "$meta" | cut -d',' -f1)
            time=$(echo "$meta" | cut -d',' -f2)

            object_id=""
            if [ "$code" = "201" ]; then
                object_id=$(echo "$body" | grep -o '"cmis:objectId":{"value":"[^"]*"' | head -1 | sed 's/.*"value":"\([^"]*\)".*/\1/')
            fi

            echo "$code,$time,$object_id"
        ) >> "$result_file" &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait $pid
    done

    local end_time=$(date +%s.%N)
    local total_time=$(echo "$end_time - $start_time" | bc)

    # 結果集計
    local success=0
    local failed=0
    local total_time_ms=0
    local object_ids=()

    while IFS=',' read -r code time oid; do
        time_ms=$(echo "$time" | awk '{printf "%.0f", $1 * 1000}')
        total_time_ms=$((total_time_ms + time_ms))
        if [ "$code" = "201" ]; then
            ((success++))
            if [ -n "$oid" ]; then
                object_ids+=("$oid")
            fi
        else
            ((failed++))
        fi
    done < "$result_file"

    local avg_time=$((total_time_ms / concurrent))
    local throughput=$(echo "scale=2; $concurrent / $total_time" | bc)

    echo "  作成試行: $concurrent"
    echo "  成功: $success, 失敗: $failed"
    echo "  平均レスポンス時間: ${avg_time}ms"
    echo "  スループット: ${throughput} docs/sec"

    # クリーンアップ（作成したドキュメントを削除）
    if [ ${#object_ids[@]} -gt 0 ]; then
        echo "  クリーンアップ: ${#object_ids[@]} ドキュメントを削除中..."
        for oid in "${object_ids[@]}"; do
            curl -s -o /dev/null -u "$AUTH" -X POST \
                -d "cmisaction=delete&objectId=$oid" \
                "$BASE_URL/browser/$REPO" &
        done
        wait
        echo "  クリーンアップ完了"
    fi
    echo ""
}

run_write_test 5
run_write_test 10
run_write_test 20

# Phase 5: 持続的負荷テスト
echo -e "${BLUE}=== Phase 5: 持続的負荷テスト (30秒間) ===${NC}"
echo ""

sustained_load_test() {
    local concurrent="$1"
    local duration="$2"
    local result_file="$RESULTS_DIR/sustained_${concurrent}.txt"

    echo -e "${YELLOW}--- $concurrent 並列 × ${duration}秒間 ---${NC}"

    local start_time=$(date +%s)
    local end_target=$((start_time + duration))
    local total_requests=0
    local success_requests=0
    local failed_requests=0

    # バックグラウンドワーカーを起動
    for w in $(seq 1 $concurrent); do
        (
            while [ $(date +%s) -lt $end_target ]; do
                result=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" \
                    "$BASE_URL/browser/$REPO/root?cmisselector=children")
                echo "$result"
            done
        ) >> "$result_file" &
    done

    # 進捗表示
    while [ $(date +%s) -lt $end_target ]; do
        remaining=$((end_target - $(date +%s)))
        printf "\r  残り時間: %2d秒..." "$remaining"
        sleep 1
    done

    wait
    echo ""

    # 結果集計
    while read -r code; do
        ((total_requests++))
        if [ "$code" = "200" ]; then
            ((success_requests++))
        else
            ((failed_requests++))
        fi
    done < "$result_file"

    local throughput=$(echo "scale=2; $total_requests / $duration" | bc)
    local success_rate=$(echo "scale=2; $success_requests * 100 / $total_requests" | bc)

    echo "  総リクエスト数: $total_requests"
    echo "  成功: $success_requests, 失敗: $failed_requests"
    echo "  成功率: ${success_rate}%"
    echo "  平均スループット: ${throughput} req/sec"
    echo ""
}

sustained_load_test 10 15
sustained_load_test 20 15

# 結果サマリー
echo -e "${BLUE}=============================================="
echo "テスト結果サマリー"
echo "==============================================${NC}"
echo ""
echo "結果ファイル: $RESULTS_DIR/"
echo ""

# クリーンアップ
echo "テストデータをクリーンアップ中..."
rm -rf "$RESULTS_DIR"
echo -e "${GREEN}完了${NC}"
