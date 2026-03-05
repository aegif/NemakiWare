#!/bin/bash
# OOM Stress Test for NemakiWare
# Targets identified OOM hotspots with memory monitoring
#
# Usage: bash tests/load/oom-stress-test.sh
#
set -euo pipefail

BASE_URL="${NEMAKI_BASE_URL:-http://localhost:8080/core/browser/bedroom}"
AUTH="${NEMAKI_AUTH:-admin:admin}"
COUCHDB_AUTH="${COUCHDB_AUTH:-admin:password}"
COUCHDB_URL="${COUCHDB_URL:-http://localhost:5984}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-docker-core-1}"
LOAD_FOLDER_NAME="oom-load-test-$(date +%s)"
RESULTS_DIR="test-results/load-test"
mkdir -p "$RESULTS_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $*"; }
ok()  { echo -e "${GREEN}[PASS]${NC} $*"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $*"; }
fail(){ echo -e "${RED}[FAIL]${NC} $*"; }

# Memory snapshot — gracefully handles missing jcmd or non-G1GC collectors
mem_snapshot() {
    local label="$1"
    local mem_line=$(docker stats --no-stream "$DOCKER_CONTAINER" --format "{{.MemUsage}}" 2>/dev/null || echo "N/A")
    local heap_used=0
    local heap_total=0
    local heap_info
    heap_info=$(docker exec "$DOCKER_CONTAINER" bash -c 'jcmd 1 GC.heap_info 2>/dev/null' 2>/dev/null || true)
    if [ -n "$heap_info" ]; then
        # Try G1GC (garbage-first), then fallback to generic "used/total" pattern
        heap_used=$(echo "$heap_info" | grep -oE "used [0-9]+K" | head -1 | grep -oE "[0-9]+" || echo "0")
        heap_total=$(echo "$heap_info" | grep -oE "total [0-9]+K" | head -1 | grep -oE "[0-9]+" || echo "0")
    fi
    local heap_used_mb=$((${heap_used:-0} / 1024))
    local heap_total_mb=$((${heap_total:-0} / 1024))
    echo -e "  ${YELLOW}[$label]${NC} Container: ${mem_line} | JVM Heap: ${heap_used_mb}MB / ${heap_total_mb}MB"
    echo "$(date +%H:%M:%S),$label,$mem_line,$heap_used_mb,$heap_total_mb" >> "$RESULTS_DIR/memory-log.csv"
}

# Force GC — no-op if jcmd unavailable
force_gc() {
    docker exec "$DOCKER_CONTAINER" bash -c 'jcmd 1 GC.run 2>/dev/null' > /dev/null 2>&1 || true
}

# Get root folder ID dynamically
get_root_folder_id() {
    curl -s -u "$AUTH" "${BASE_URL}?cmisselector=repositoryInfo" | python3 -c "import sys,json; print(json.load(sys.stdin)['bedroom']['rootFolderId'])"
}

echo ""
echo "============================================================"
echo "  NemakiWare OOM Stress Test"
echo "  Target: http://localhost:8080"
echo "  JVM: -Xms1g -Xmx3g"
echo "  changesByToken rows: $(curl -s -u "$COUCHDB_AUTH" "${COUCHDB_URL}/bedroom/_design/_repo/_view/changesByToken?limit=0" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('total_rows','N/A'))" 2>/dev/null || echo 'N/A (CouchDB unreachable)')"
echo "============================================================"
echo ""

echo "timestamp,label,container_mem,heap_used_mb,heap_total_mb" > "$RESULTS_DIR/memory-log.csv"

ROOT_FOLDER_ID=$(get_root_folder_id)
log "Root folder ID: $ROOT_FOLDER_ID"

# ============================================================
# TEST 1: getLatestChange() — 修正済みポイント
# 78,495行のchangesByTokenビューに対して連続呼び出し
# 修正前: 全行メモリロード → OOM
# 修正後: descending=true, limit=1 → 数KB
# ============================================================
echo ""
log "========== TEST 1: getLatestChange() stress (78k+ change events) =========="
mem_snapshot "T1-before"

# Force GC before test
force_gc

T1_START=$(date +%s)
T1_SUCCESS=0
T1_FAIL=0

for i in $(seq 1 50); do
    # getContentChanges triggers getLatestChange internally
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 10 -u "$AUTH" \
        "${BASE_URL}?cmisselector=contentChanges&changeLogToken=0&maxItems=1" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        T1_SUCCESS=$((T1_SUCCESS + 1))
    else
        T1_FAIL=$((T1_FAIL + 1))
    fi
done
T1_END=$(date +%s)
T1_ELAPSED=$((T1_END - T1_START))

mem_snapshot "T1-after-50calls"

if [ $T1_FAIL -eq 0 ]; then
    ok "TEST 1: 50x getContentChanges (78k events) — ${T1_SUCCESS}/50 success, ${T1_ELAPSED}s"
else
    fail "TEST 1: 50x getContentChanges — ${T1_SUCCESS}/50 success, ${T1_FAIL} failures, ${T1_ELAPSED}s"
fi

# ============================================================
# TEST 2: getChildren() on folder with many children
# Create 200+ children in a single folder, then query without pagination
# ============================================================
echo ""
log "========== TEST 2: getChildren() — many children in single folder =========="
mem_snapshot "T2-before"

# Create load test folder
LOAD_FOLDER_ID=$(curl -s -u "$AUTH" -X POST \
    -F "cmisaction=createFolder" \
    -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:folder" \
    -F "propertyId[1]=cmis:name" -F "propertyValue[1]=$LOAD_FOLDER_NAME" \
    -F "succinct=true" \
    "${BASE_URL}?objectId=${ROOT_FOLDER_ID}" | python3 -c "import sys,json; print(json.load(sys.stdin)['succinctProperties']['cmis:objectId'])")
log "Created load test folder: $LOAD_FOLDER_ID"

# Create 200 documents in the folder
log "Creating 200 documents..."
T2_CREATED=0
for i in $(seq 1 200); do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u "$AUTH" -X POST \
        -F "cmisaction=createDocument" \
        -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:document" \
        -F "propertyId[1]=cmis:name" -F "propertyValue[1]=load-doc-${i}.txt" \
        -F "succinct=true" \
        -F "content=@/dev/null;type=text/plain;filename=load-doc-${i}.txt" \
        "${BASE_URL}?objectId=${LOAD_FOLDER_ID}" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "201" ]; then
        T2_CREATED=$((T2_CREATED + 1))
    fi
    if [ $((i % 50)) -eq 0 ]; then
        log "  Created $i/200 documents..."
    fi
done
log "Created ${T2_CREATED}/200 documents"
mem_snapshot "T2-after-create"

# Query children without pagination (hits the non-paged getChildren path)
T2_START=$(date +%s)
T2_SUCCESS=0
for i in $(seq 1 20); do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 30 -u "$AUTH" \
        "${BASE_URL}?objectId=${LOAD_FOLDER_ID}&cmisselector=children&maxItems=1000" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        T2_SUCCESS=$((T2_SUCCESS + 1))
    fi
done
T2_END=$(date +%s)
T2_ELAPSED=$((T2_END - T2_START))

mem_snapshot "T2-after-20queries"

if [ $T2_SUCCESS -eq 20 ]; then
    ok "TEST 2: 20x getChildren (200 items, maxItems=1000) — all success, ${T2_ELAPSED}s"
else
    fail "TEST 2: 20x getChildren — ${T2_SUCCESS}/20 success, ${T2_ELAPSED}s"
fi

# ============================================================
# TEST 3: getDescendants() — recursive tree loading
# Deep folder hierarchy stress test
# ============================================================
echo ""
log "========== TEST 3: getDescendants() — recursive tree =========="
mem_snapshot "T3-before"

# Create nested folders (5 levels deep, 3 children each = ~363 nodes)
PARENT_ID="$LOAD_FOLDER_ID"
log "Creating 5-level nested folder structure..."
declare -a LEVEL_IDS=("$PARENT_ID")
for level in $(seq 1 5); do
    declare -a NEW_LEVEL_IDS=()
    for parent in "${LEVEL_IDS[@]}"; do
        for child in $(seq 1 3); do
            CHILD_ID=$(curl -s -u "$AUTH" -X POST \
                -F "cmisaction=createFolder" \
                -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:folder" \
                -F "propertyId[1]=cmis:name" -F "propertyValue[1]=level${level}-child${child}" \
                -F "succinct=true" \
                "${BASE_URL}?objectId=${parent}" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('succinctProperties',{}).get('cmis:objectId',''))" 2>/dev/null)
            if [ -n "$CHILD_ID" ]; then
                NEW_LEVEL_IDS+=("$CHILD_ID")
            fi
        done
    done
    LEVEL_IDS=("${NEW_LEVEL_IDS[@]}")
    log "  Level $level: ${#NEW_LEVEL_IDS[@]} folders created"
done

mem_snapshot "T3-after-tree-create"

# Query descendants (full tree)
T3_START=$(date +%s)
T3_SUCCESS=0
for i in $(seq 1 10); do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 30 -u "$AUTH" \
        "${BASE_URL}?objectId=${LOAD_FOLDER_ID}&cmisselector=descendants&depth=-1" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        T3_SUCCESS=$((T3_SUCCESS + 1))
    fi
done
T3_END=$(date +%s)
T3_ELAPSED=$((T3_END - T3_START))

mem_snapshot "T3-after-10descendants"

if [ $T3_SUCCESS -eq 10 ]; then
    ok "TEST 3: 10x getDescendants (depth=-1, ~500+ nodes) — all success, ${T3_ELAPSED}s"
else
    fail "TEST 3: 10x getDescendants — ${T3_SUCCESS}/10 success, ${T3_ELAPSED}s"
fi

# ============================================================
# TEST 4: Large content stream — readAllBytes stress
# Upload and download files of increasing size
# ============================================================
echo ""
log "========== TEST 4: Content stream — large file handling =========="
mem_snapshot "T4-before"

# Create test files of increasing size: 1MB, 10MB, 50MB, 100MB
for SIZE_MB in 1 10 50 100; do
    FILENAME="load-test-${SIZE_MB}MB.bin"
    log "  Uploading ${SIZE_MB}MB file..."

    # Generate file and upload
    T4_UP_START=$(date +%s)
    dd if=/dev/urandom of="/tmp/${FILENAME}" bs=1048576 count=${SIZE_MB} 2>/dev/null
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 120 -u "$AUTH" -X POST \
        -F "cmisaction=createDocument" \
        -F "propertyId[0]=cmis:objectTypeId" -F "propertyValue[0]=cmis:document" \
        -F "propertyId[1]=cmis:name" -F "propertyValue[1]=${FILENAME}" \
        -F "succinct=true" \
        -F "content=@/tmp/${FILENAME};type=application/octet-stream" \
        "${BASE_URL}?objectId=${LOAD_FOLDER_ID}" 2>/dev/null || echo "000")
    T4_UP_END=$(date +%s)
    T4_UP_ELAPSED=$((T4_UP_END - T4_UP_START))

    if [ "$HTTP_CODE" = "201" ]; then
        log "  Upload ${SIZE_MB}MB: OK (${T4_UP_ELAPSED}s)"

        # Download (triggers readAllBytes in AttachmentNode)
        T4_DL_START=$(date +%s)
        DOC_ID=$(curl -s -u "$AUTH" \
            "${BASE_URL}?objectId=${LOAD_FOLDER_ID}&cmisselector=children&filter=cmis:objectId,cmis:name&succinct=true" 2>/dev/null \
            | python3 -c "
import sys,json
data=json.load(sys.stdin)
for obj in data.get('objects',[]):
    props=obj.get('object',{}).get('succinctProperties',{})
    if props.get('cmis:name')=='${FILENAME}':
        print(props['cmis:objectId'])
        break
" 2>/dev/null)

        if [ -n "$DOC_ID" ]; then
            DL_SIZE=$(curl -s -o /dev/null -w "%{size_download}" -m 120 -u "$AUTH" \
                "${BASE_URL}?objectId=${DOC_ID}&cmisselector=content" 2>/dev/null || echo "0")
            T4_DL_END=$(date +%s)
            T4_DL_ELAPSED=$((T4_DL_END - T4_DL_START))
            DL_SIZE_MB=$((DL_SIZE / 1048576))
            log "  Download ${SIZE_MB}MB: ${DL_SIZE_MB}MB received (${T4_DL_ELAPSED}s)"
        fi
    else
        warn "  Upload ${SIZE_MB}MB: HTTP $HTTP_CODE"
    fi

    mem_snapshot "T4-after-${SIZE_MB}MB"
    rm -f "/tmp/${FILENAME}"
done

# ============================================================
# TEST 5: Concurrent API requests — mixed workload
# Simulates multiple users hitting different endpoints simultaneously
# ============================================================
echo ""
log "========== TEST 5: Concurrent mixed API requests =========="
mem_snapshot "T5-before"

T5_START=$(date +%s)

# 20 concurrent requests across different endpoints
log "Launching 20 concurrent requests..."
for i in $(seq 1 20); do
    (
        case $((i % 5)) in
            0) # getChildren on root
                curl -s -o /dev/null -w "" -m 15 -u "$AUTH" "${BASE_URL}/root?cmisselector=children&maxItems=100" 2>/dev/null
                ;;
            1) # getContentChanges
                curl -s -o /dev/null -w "" -m 15 -u "$AUTH" "${BASE_URL}?cmisselector=contentChanges&changeLogToken=0&maxItems=10" 2>/dev/null
                ;;
            2) # CMIS query
                curl -s -o /dev/null -w "" -m 15 -u "$AUTH" "${BASE_URL}?cmisselector=query&q=SELECT+*+FROM+cmis:document&maxItems=50&succinct=true" 2>/dev/null
                ;;
            3) # getObject
                curl -s -o /dev/null -w "" -m 15 -u "$AUTH" "${BASE_URL}/root?cmisselector=object&succinct=true" 2>/dev/null
                ;;
            4) # getDescendants on load folder
                curl -s -o /dev/null -w "" -m 15 -u "$AUTH" "${BASE_URL}?objectId=${LOAD_FOLDER_ID}&cmisselector=descendants&depth=2" 2>/dev/null
                ;;
        esac
    ) &
done
wait

T5_END=$(date +%s)
T5_ELAPSED=$((T5_END - T5_START))

mem_snapshot "T5-after-20concurrent"

# Another round: 50 concurrent
log "Launching 50 concurrent requests..."
for i in $(seq 1 50); do
    (
        case $((i % 5)) in
            0) curl -s -o /dev/null -m 15 -u "$AUTH" "${BASE_URL}/root?cmisselector=children&maxItems=100" 2>/dev/null ;;
            1) curl -s -o /dev/null -m 15 -u "$AUTH" "${BASE_URL}?cmisselector=contentChanges&changeLogToken=0&maxItems=10" 2>/dev/null ;;
            2) curl -s -o /dev/null -m 15 -u "$AUTH" "${BASE_URL}?cmisselector=query&q=SELECT+*+FROM+cmis:document&maxItems=50&succinct=true" 2>/dev/null ;;
            3) curl -s -o /dev/null -m 15 -u "$AUTH" "${BASE_URL}/root?cmisselector=object&succinct=true" 2>/dev/null ;;
            4) curl -s -o /dev/null -m 15 -u "$AUTH" "${BASE_URL}?objectId=${LOAD_FOLDER_ID}&cmisselector=descendants&depth=2" 2>/dev/null ;;
        esac
    ) &
done
wait

T5_END2=$(date +%s)
T5_ELAPSED2=$((T5_END2 - T5_END))

mem_snapshot "T5-after-50concurrent"

ok "TEST 5: 20+50 concurrent requests completed (${T5_ELAPSED}s + ${T5_ELAPSED2}s)"

# ============================================================
# TEST 6: Sustained load — repeated getContentChanges
# Simulates polling client hitting contentChanges continuously
# ============================================================
echo ""
log "========== TEST 6: Sustained getContentChanges polling (200 requests) =========="
mem_snapshot "T6-before"

# Force GC
force_gc

T6_START=$(date +%s)
T6_SUCCESS=0
T6_FAIL=0

for i in $(seq 1 200); do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 10 -u "$AUTH" \
        "${BASE_URL}?cmisselector=contentChanges&changeLogToken=$((RANDOM * RANDOM))&maxItems=100" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        T6_SUCCESS=$((T6_SUCCESS + 1))
    else
        T6_FAIL=$((T6_FAIL + 1))
    fi
    if [ $((i % 50)) -eq 0 ]; then
        mem_snapshot "T6-at-${i}req"
    fi
done
T6_END=$(date +%s)
T6_ELAPSED=$((T6_END - T6_START))

mem_snapshot "T6-after-200calls"

if [ $T6_FAIL -eq 0 ]; then
    ok "TEST 6: 200x getContentChanges (sustained) — all success, ${T6_ELAPSED}s"
else
    fail "TEST 6: 200x getContentChanges — ${T6_SUCCESS}/200 success, ${T6_FAIL} failures, ${T6_ELAPSED}s"
fi

# ============================================================
# CLEANUP
# ============================================================
echo ""
log "========== CLEANUP =========="

# Delete load test folder (recursive)
curl -s -o /dev/null -u "$AUTH" -X POST \
    -F "cmisaction=deleteTree" \
    -F "continueOnFailure=true" \
    "${BASE_URL}?objectId=${LOAD_FOLDER_ID}" 2>/dev/null
log "Deleted load test folder"

# Final memory snapshot
mem_snapshot "FINAL"

# ============================================================
# SUMMARY
# ============================================================
echo ""
echo "============================================================"
echo "  LOAD TEST SUMMARY"
echo "============================================================"
echo ""
cat "$RESULTS_DIR/memory-log.csv" | column -t -s ','
echo ""
echo "============================================================"

# Check if heap grew beyond 2.5GB (warning threshold for 3GB max)
FINAL_HEAP=$(tail -1 "$RESULTS_DIR/memory-log.csv" | cut -d',' -f4)
if [ "${FINAL_HEAP:-0}" -gt 2560 ]; then
    fail "WARNING: Final heap ${FINAL_HEAP}MB exceeds 2.5GB threshold (max 3GB)"
    exit 1
else
    ok "Final heap ${FINAL_HEAP}MB — within safe limits (max 3GB)"
fi
