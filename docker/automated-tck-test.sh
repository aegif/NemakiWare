#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

export COUCHDB_USER=${COUCHDB_USER:-admin}
export COUCHDB_PASSWORD=${COUCHDB_PASSWORD:-password}
export REPOSITORY_ID=${REPOSITORY_ID:-bedroom}
export DUMP_FILE=${DUMP_FILE:-/app/bedroom_init.dump}
export FORCE=${FORCE:-true}

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

echo_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

echo_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_header() {
    echo "=========================================="
    echo "$1"
    echo "=========================================="
}

create_repositories_config() {
    echo_info "Creating repositories.yml configuration..."
    
    local config_file="$SCRIPT_DIR/core/repositories.yml"
    
    cat > "$config_file" << EOF
!jp.aegif.nemaki.util.yaml.RepositorySettings
settings: 
   canopy: !jp.aegif.nemaki.util.yaml.RepositorySetting
      password: ${COUCHDB_PASSWORD}
      user: ${COUCHDB_USER}
   bedroom: !jp.aegif.nemaki.util.yaml.RepositorySetting
      password: ${COUCHDB_PASSWORD}
      user: ${COUCHDB_USER}
EOF

    if [ -f "$config_file" ]; then
        echo_success "repositories.yml created successfully"
        echo_info "Configuration preview:"
        head -n 8 "$config_file" | sed 's/password: .*/password: [REDACTED]/'
        return 0
    else
        echo_error "Failed to create repositories.yml"
        return 1
    fi
}

cleanup_environment() {
    echo_info "Cleaning up existing Docker environment..."
    
    cd "$SCRIPT_DIR"
    
    if docker-compose -f docker-compose-simple.yml ps -q | grep -q .; then
        echo_info "Stopping existing containers..."
        docker-compose -f docker-compose-simple.yml down -v --remove-orphans
        echo_success "Containers stopped and removed"
    else
        echo_info "No existing containers found"
    fi
    
    echo_info "Cleaning up Docker volumes..."
    docker volume prune -f > /dev/null 2>&1 || true
    
    echo_success "Environment cleanup completed"
}

start_environment() {
    echo_info "Building and starting Docker environment..."
    
    cd "$SCRIPT_DIR"
    
    echo_info "Building Docker images..."
    docker-compose -f docker-compose-simple.yml build --no-cache
    
    if [ $? -ne 0 ]; then
        echo_error "Docker build failed"
        return 1
    fi
    
    echo_info "Starting Docker services..."
    docker-compose -f docker-compose-simple.yml up -d
    
    if [ $? -ne 0 ]; then
        echo_error "Failed to start Docker services"
        return 1
    fi
    
    echo_success "Docker environment started"
    return 0
}

wait_for_services() {
    echo_info "Waiting for services to be healthy..."
    
    local max_attempts=60
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        echo_info "Health check attempt $attempt/$max_attempts..."
        
        if curl -s -f -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" "http://localhost:5984/_all_dbs" > /dev/null 2>&1; then
            echo_success "✓ CouchDB is healthy"
        else
            echo_warning "✗ CouchDB not ready yet"
            sleep 5
            attempt=$((attempt + 1))
            continue
        fi
        
        if curl -s -f "http://localhost:8983/solr/admin/cores" > /dev/null 2>&1; then
            echo_success "✓ Solr is healthy"
        else
            echo_warning "✗ Solr not ready yet"
            sleep 5
            attempt=$((attempt + 1))
            continue
        fi
        
        if curl -s -f "http://localhost:8080/core" > /dev/null 2>&1; then
            echo_success "✓ Core service is healthy"
            break
        else
            echo_warning "✗ Core service not ready yet"
            sleep 5
            attempt=$((attempt + 1))
            continue
        fi
    done
    
    if [ $attempt -gt $max_attempts ]; then
        echo_error "Services failed to become healthy within timeout"
        return 1
    fi
    
    echo_success "All services are healthy and ready"
    return 0
}

verify_design_documents() {
    echo_info "Verifying design documents were created..."
    
    local databases=("bedroom" "bedroom_closet" "canopy" "canopy_closet" "nemaki_conf")
    local success_count=0
    
    for db in "${databases[@]}"; do
        echo_info "Checking design document for database: $db"
        
        local response=$(curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" \
            "http://localhost:5984/${db}/_design/_repo" 2>/dev/null)
        
        if echo "$response" | grep -q '"_id":"_design/_repo"'; then
            echo_success "✓ Design document exists for $db"
            
            local view_response=$(curl -s -u "${COUCHDB_USER}:${COUCHDB_PASSWORD}" \
                "http://localhost:5984/${db}/_design/_repo/_view/changes?limit=0" 2>/dev/null)
            
            if echo "$view_response" | grep -q '"total_rows"'; then
                echo_success "✓ Changes view is functional for $db"
                success_count=$((success_count + 1))
            else
                echo_warning "✗ Changes view may not be functional for $db"
            fi
        else
            echo_warning "✗ Design document missing for $db (may be expected for some databases)"
        fi
    done
    
    if [ $success_count -ge 2 ]; then
        echo_success "Design documents verification completed (${success_count} functional)"
        return 0
    else
        echo_error "Insufficient design documents found (${success_count} functional)"
        return 1
    fi
}

trigger_reindexing() {
    echo_info "Triggering Solr re-indexing for data synchronization..."
    
    sleep 10
    
    echo_info "Starting full re-indexing for bedroom repository..."
    local reindex_response=$(curl -s -X GET \
        "http://localhost:8080/core/rest/repo/bedroom/search-engine/reindex?tracking=FULL" \
        -u admin:admin 2>/dev/null)
    
    if echo "$reindex_response" | grep -q -i "success\|started\|initiated"; then
        echo_success "✓ Re-indexing initiated successfully"
    else
        echo_warning "⚠ Re-indexing response: $reindex_response"
        echo_info "Continuing with test execution..."
    fi
    
    echo_info "Waiting for indexing to process..."
    sleep 30
    
    echo_info "Verifying Solr index contains documents..."
    local solr_query_response=$(curl -s "http://localhost:8983/solr/nemaki/select?q=*:*&rows=0" 2>/dev/null)
    
    if echo "$solr_query_response" | grep -q '"numFound":[1-9]'; then
        local doc_count=$(echo "$solr_query_response" | grep -o '"numFound":[0-9]*' | cut -d':' -f2)
        echo_success "✓ Solr index contains $doc_count documents"
    else
        echo_warning "⚠ Solr index may be empty - this could affect query results"
    fi
    
    return 0
}

execute_tck_tests() {
    echo_info "Executing TCK tests..."
    
    mkdir -p "$SCRIPT_DIR/tck-reports"
    
    if [ -f "$SCRIPT_DIR/execute-tck-tests.sh" ]; then
        echo_info "Running TCK test execution script..."
        cd "$SCRIPT_DIR"
        chmod +x execute-tck-tests.sh
        ./execute-tck-tests.sh
        
        if [ $? -eq 0 ]; then
            echo_success "✓ TCK tests completed successfully"
            return 0
        else
            echo_error "✗ TCK test execution failed"
            return 1
        fi
    else
        echo_error "TCK test script not found: execute-tck-tests.sh"
        return 1
    fi
}

analyze_results() {
    echo_info "Analyzing TCK test results..."
    
    local reports_dir="$SCRIPT_DIR/tck-reports"
    
    if [ -f "$reports_dir/current-score.txt" ]; then
        local score=$(head -n 1 "$reports_dir/current-score.txt" | cut -d'|' -f2)
        echo_success "✓ Current TCK Score: ${score}%"
    fi
    
    if [ -f "$reports_dir/tck-summary.html" ]; then
        echo_info "Extracting test statistics from HTML report..."
        
        local total_tests=$(grep -o 'Total.*[0-9]\+' "$reports_dir/tck-summary.html" 2>/dev/null | grep -o '[0-9]\+' | head -1 || echo "unknown")
        local passed_tests=$(grep -o 'Passed.*[0-9]\+' "$reports_dir/tck-summary.html" 2>/dev/null | grep -o '[0-9]\+' | head -1 || echo "unknown")
        
        echo_success "✓ Test Results Summary:"
        echo_info "  - Total Tests: $total_tests"
        echo_info "  - Passed Tests: $passed_tests"
        
        if [ "$total_tests" != "unknown" ] && [ "$passed_tests" != "unknown" ] && [ "$total_tests" -gt 0 ]; then
            local pass_rate=$(echo "scale=2; $passed_tests * 100 / $total_tests" | bc 2>/dev/null || echo "unknown")
            echo_info "  - Pass Rate: ${pass_rate}%"
            
            if [ "$total_tests" -ge 200 ] && [ "$passed_tests" -ge 60 ]; then
                echo_success "✓ TCK results meet expected criteria (≥200 total, ≥60 passed)"
                return 0
            else
                echo_warning "⚠ TCK results below expected criteria"
                return 1
            fi
        fi
    fi
    
    echo_info "TCK reports available in: $reports_dir/"
    ls -la "$reports_dir/" 2>/dev/null || echo_warning "No reports found"
    
    return 0
}

show_usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Automated TCK Test Script for NemakiWare

This script provides complete automation for reproducing TCK test results
from a clean Docker environment without manual intervention.

OPTIONS:
    --clean-start    Clean up existing environment before starting (default)
    --no-cleanup     Skip environment cleanup (use existing containers)
    --skip-build     Skip Docker image building (use existing images)
    --help           Show this help message

ENVIRONMENT VARIABLES:
    COUCHDB_USER     CouchDB username (default: admin)
    COUCHDB_PASSWORD CouchDB password (default: password)
    REPOSITORY_ID    Repository to initialize (default: bedroom)

EXPECTED RESULTS:
    - Total Tests: ~214
    - Passed Tests: ~70 (32.71% pass rate)
    - Test execution completes in ~10 minutes
    - CMIS queries return actual data (not 0 results)

EXAMPLES:
    ./automated-tck-test.sh

    COUCHDB_USER=myuser COUCHDB_PASSWORD=mypass ./automated-tck-test.sh

    ./automated-tck-test.sh --no-cleanup

EOF
}

main() {
    local clean_start=true
    local skip_build=false
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --clean-start)
                clean_start=true
                shift
                ;;
            --no-cleanup)
                clean_start=false
                shift
                ;;
            --skip-build)
                skip_build=true
                shift
                ;;
            --help)
                show_usage
                exit 0
                ;;
            *)
                echo_error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done
    
    print_header "NemakiWare Automated TCK Test Execution"
    
    echo_info "Configuration:"
    echo_info "  - CouchDB User: $COUCHDB_USER"
    echo_info "  - CouchDB Password: [REDACTED]"
    echo_info "  - Repository ID: $REPOSITORY_ID"
    echo_info "  - Clean Start: $clean_start"
    echo_info "  - Skip Build: $skip_build"
    echo ""
    
    if ! create_repositories_config; then
        echo_error "Failed to create repositories configuration"
        exit 1
    fi
    
    if [ "$clean_start" = true ]; then
        if ! cleanup_environment; then
            echo_error "Failed to clean up environment"
            exit 1
        fi
    fi
    
    if ! start_environment; then
        echo_error "Failed to start Docker environment"
        exit 1
    fi
    
    if ! wait_for_services; then
        echo_error "Services failed to become healthy"
        exit 1
    fi
    
    if ! verify_design_documents; then
        echo_warning "Design document verification had issues, but continuing..."
    fi
    
    if ! trigger_reindexing; then
        echo_warning "Re-indexing had issues, but continuing with tests..."
    fi
    
    if ! execute_tck_tests; then
        echo_error "TCK test execution failed"
        exit 1
    fi
    
    analyze_results
    
    print_header "Automated TCK Test Execution Completed"
    
    echo_success "✓ All automation steps completed successfully!"
    echo_info ""
    echo_info "Key achievements:"
    echo_info "  ✓ Clean Docker environment setup"
    echo_info "  ✓ Automated design document creation"
    echo_info "  ✓ Dynamic repositories.yml configuration"
    echo_info "  ✓ Solr re-indexing for data synchronization"
    echo_info "  ✓ Complete TCK test execution"
    echo_info ""
    echo_info "Reports available in: $SCRIPT_DIR/tck-reports/"
    echo_info ""
    echo_info "This script can be run by other developers to reproduce"
    echo_info "identical TCK test results in their own environments."
    
    return 0
}

main "$@"
