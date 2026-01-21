#!/bin/bash

echo "=== Testing 2 parallel user creations with detailed output ==="

create_user_verbose() {
    local user_id=$1
    echo "[$(date +%s)] START: Creating $user_id"
    
    RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -u admin:admin \
        -X POST \
        -H "Content-Type: application/json" \
        -d "{\"id\":\"$user_id\",\"name\":\"Test $user_id\",\"firstName\":\"Test\",\"lastName\":\"User\",\"email\":\"${user_id}@example.com\",\"password\":\"password123\",\"groups\":[\"testgroup\"]}" \
        "http://localhost:8080/core/rest/repo/bedroom/user/create-json/$user_id")
    
    HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE" | cut -d: -f2)
    BODY=$(echo "$RESPONSE" | grep -v "HTTP_CODE")
    
    echo "[$(date +%s)] DONE: $user_id - HTTP $HTTP_CODE - $BODY"
}

# Run 2 in parallel
create_user_verbose "parallel_test_1" &
PID1=$!
create_user_verbose "parallel_test_2" &
PID2=$!

# Wait for both
wait $PID1
wait $PID2

echo "=== Both requests completed ==="
