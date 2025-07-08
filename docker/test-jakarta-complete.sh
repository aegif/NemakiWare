#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "==========================================="
echo "NemakiWare Jakarta EE Complete Test Suite"
echo "==========================================="
echo ""

# Set Java 17 environment (CRITICAL)
# Try to detect Java 17 automatically, fallback to user setting
if [ -z "$JAVA_HOME" ]; then
    # Common Java 17 locations
    for java_path in \
        "/Users/ishiiakinori/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home" \
        "/opt/homebrew/Cellar/openjdk@17/*/libexec/openjdk.jdk/Contents/Home" \
        "/Library/Java/JavaVirtualMachines/jdk-17.*/Contents/Home" \
        "/usr/lib/jvm/java-17-openjdk"; do
        
        if [ -d "$java_path" ]; then
            export JAVA_HOME="$java_path"
            break
        fi
    done
fi

if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: Java 17 not found. Please set JAVA_HOME manually:"
    echo "export JAVA_HOME=/path/to/your/java-17"
    echo "export PATH=\$JAVA_HOME/bin:\$PATH"
    exit 1
fi

export PATH=$JAVA_HOME/bin:$PATH

echo "Using Java version: $(java -version 2>&1 | head -1)"
echo ""

# ========================================
# 1. BUILD JAKARTA EE WAR
# ========================================
echo "1. BUILDING JAKARTA EE COMPATIBLE WAR..."
echo "----------------------------------------"

cd $NEMAKI_HOME

echo "Building core WAR with Jakarta profile..."
mvn clean package -f core/pom.xml -Pjakarta -DskipTests

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to build Jakarta EE WAR"
    exit 1
fi

echo "✓ Jakarta EE WAR built successfully"
ls -la core/target/core.war

# ========================================
# 2. DOCKER ENVIRONMENT SETUP  
# ========================================
echo ""
echo "2. SETTING UP DOCKER ENVIRONMENT..."
echo "----------------------------------------"

cd $SCRIPT_DIR

# Stop existing environment
echo "Stopping existing containers..."
docker compose -f docker-compose-jakarta-complete.yml down -v 2>/dev/null || true

# Copy WAR to Docker context
echo "Copying Jakarta WAR to Docker context..."
cp ../core/target/core.war core/core.war

# Build Docker images
echo "Building Docker images..."
docker build --no-cache -t nemakiware-tomcat10 -f core/Dockerfile.jakarta core/

# Start complete environment
echo "Starting Jakarta EE environment..."
docker compose -f docker-compose-jakarta-complete.yml up -d

echo "✓ Docker environment started"

# ========================================
# 3. WAIT FOR INITIALIZATION
# ========================================
echo ""
echo "3. WAITING FOR INITIALIZATION..."
echo "----------------------------------------"

echo "Waiting for CouchDB to be ready..."
timeout=60
while [ $timeout -gt 0 ]; do
    if curl -s -f -u admin:password http://localhost:5984/_all_dbs > /dev/null; then
        echo "✓ CouchDB is ready"
        break
    fi
    echo "Waiting for CouchDB... ($timeout seconds remaining)"
    sleep 5
    timeout=$((timeout - 5))
done

if [ $timeout -le 0 ]; then
    echo "ERROR: CouchDB failed to start"
    exit 1
fi

echo "Waiting for Core application to start..."
timeout=120
while [ $timeout -gt 0 ]; do
    status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/core)
    if [ "$status" = "200" ] || [ "$status" = "302" ]; then
        echo "✓ Core application is ready (HTTP $status)"
        break
    fi
    echo "Waiting for Core application... ($timeout seconds remaining)"
    sleep 10
    timeout=$((timeout - 10))
done

if [ $timeout -le 0 ]; then
    echo "ERROR: Core application failed to start"
    docker logs nemaki-core-tomcat10 --tail 20
    exit 1
fi

echo "Waiting for Solr to be ready..."
timeout=60
while [ $timeout -gt 0 ]; do
    if curl -s -f http://localhost:8983/solr/ > /dev/null; then
        echo "✓ Solr is ready"
        break
    fi
    echo "Waiting for Solr... ($timeout seconds remaining)"
    sleep 5
    timeout=$((timeout - 5))
done

# ========================================
# 4. BASIC FUNCTIONALITY VERIFICATION
# ========================================
echo ""
echo "4. BASIC FUNCTIONALITY VERIFICATION..."
echo "----------------------------------------"

# Test CMIS endpoints
echo "Testing CMIS AtomPub endpoint..."
CMIS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin http://localhost:8080/core/atom/bedroom)
echo "CMIS AtomPub Status: $CMIS_STATUS"

if [ "$CMIS_STATUS" = "200" ]; then
    echo "✓ CMIS AtomPub endpoint working"
else
    echo "✗ CMIS AtomPub endpoint failed"
fi

# Test simple query
echo "Testing basic CMIS query..."
QUERY_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:folder")
echo "Query Status: $QUERY_STATUS"

if [ "$QUERY_STATUS" = "200" ]; then
    echo "✓ CMIS query endpoint working"
else
    echo "✗ CMIS query endpoint failed"
fi

# ========================================
# 5. DETAILED QUERY VALIDATION
# ========================================
echo ""
echo "5. DETAILED QUERY VALIDATION..."
echo "----------------------------------------"

# Create detailed query validation script inside container
cat > /tmp/query_validation.sh << 'EOF'
#!/bin/bash

echo "=== DETAILED QUERY VALIDATION REPORT ==="
echo "Generated: $(date)"
echo "Repository: bedroom"
echo ""

# Define test queries
declare -a queries=(
    "SELECT * FROM cmis:document"
    "SELECT * FROM cmis:folder" 
    "SELECT * FROM cmis:item"
    "SELECT cmis:objectId, cmis:name FROM cmis:document"
    "SELECT cmis:objectId, cmis:name FROM cmis:folder"
)

declare -a descriptions=(
    "All documents in repository"
    "All folders in repository"
    "All items (users/groups) in repository"
    "Document IDs and names only"
    "Folder IDs and names only"
)

total_queries=0
successful_queries=0
queries_with_results=0

for i in "${!queries[@]}"; do
    query="${queries[$i]}"
    description="${descriptions[$i]}"
    total_queries=$((total_queries + 1))
    
    echo ""
    echo "$((i+1)). $description"
    echo "   Query: $query"
    
    # Execute query and capture response
    response=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=$(echo "$query" | sed 's/ /+/g')")
    status=$?
    
    if [ $status -eq 0 ]; then
        # Count entries in AtomPub response
        entry_count=$(echo "$response" | grep -c "<entry>" || echo "0")
        
        echo "   ✓ EXECUTED successfully"
        echo "   ✓ RESULT COUNT: $entry_count items"
        
        if [ $entry_count -gt 0 ]; then
            queries_with_results=$((queries_with_results + 1))
            echo "   ✓ STATUS: SUCCESS with data"
            
            # Show first result for verification
            first_entry=$(echo "$response" | grep -A 10 "<entry>" | head -10)
            if [ -n "$first_entry" ]; then
                echo "   Sample result:"
                echo "$first_entry" | grep -E "(title|id)" | sed 's/^/     /'
            fi
        else
            echo "   ⚠ STATUS: SUCCESS but no results"
        fi
        
        successful_queries=$((successful_queries + 1))
    else
        echo "   ✗ ERROR: Query execution failed"
    fi
done

echo ""
echo "=== QUERY VALIDATION SUMMARY ==="
echo "Total Queries Tested: $total_queries"
echo "Successfully Executed: $successful_queries ($((successful_queries * 100 / total_queries))%)"
echo "Queries with Results: $queries_with_results ($((queries_with_results * 100 / total_queries))%)"
echo "Queries with No Results: $((successful_queries - queries_with_results))"
echo ""

if [ $successful_queries -eq $total_queries ]; then
    echo "✅ OVERALL STATUS: ALL QUERIES EXECUTED SUCCESSFULLY"
else
    echo "❌ OVERALL STATUS: $((total_queries - successful_queries)) QUERIES FAILED"
fi

# Repository content analysis
echo ""
echo "=== REPOSITORY CONTENT ANALYSIS ==="

doc_response=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:document")
doc_count=$(echo "$doc_response" | grep -c "<entry>" || echo "0")

folder_response=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:folder")
folder_count=$(echo "$folder_response" | grep -c "<entry>" || echo "0")

item_response=$(curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query?q=SELECT+*+FROM+cmis:item")
item_count=$(echo "$item_response" | grep -c "<entry>" || echo "0")

echo "Repository Content Summary:"
echo "- Documents: $doc_count"
echo "- Folders: $folder_count"
echo "- Items (users/groups): $item_count"
echo "- Total Objects: $((doc_count + folder_count + item_count))"

if [ $doc_count -eq 0 ] && [ $folder_count -le 1 ] && [ $item_count -eq 0 ]; then
    echo ""
    echo "⚠️  WARNING: Repository appears to be mostly empty!"
    echo "   This may explain why some queries return 0 results."
    echo "   The initialization may not have completed properly."
fi

echo ""
EOF

# Execute query validation
chmod +x /tmp/query_validation.sh
/tmp/query_validation.sh > $SCRIPT_DIR/query-validation-report.txt

echo "Detailed query validation completed!"
echo "Report saved to: $SCRIPT_DIR/query-validation-report.txt"

# Show summary
echo ""
echo "=== FINAL SUMMARY ==="
cat $SCRIPT_DIR/query-validation-report.txt | grep -A 20 "QUERY VALIDATION SUMMARY"

echo ""
echo "=== ACCESS INFORMATION ==="
echo "Core CMIS: http://localhost:8080/core"
echo "Solr Admin: http://localhost:8983/solr"
echo "CouchDB: http://localhost:5984 (admin:password)"
echo ""
echo "CMIS Authentication: admin:admin"
echo ""

echo "Jakarta EE Complete Test Suite finished!"
echo "For detailed query analysis, see: $SCRIPT_DIR/query-validation-report.txt"