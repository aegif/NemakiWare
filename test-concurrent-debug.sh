#!/bin/bash

REPO_ID="bedroom"
GROUP_ID="testgroup"
BASE_URL="http://localhost:8080/core/rest/repo/${REPO_ID}"

echo "=== Testing single concurrent request ==

="

# Single request in foreground to see immediate response
JSON_PAYLOAD='{"id":"debug_user_1","name":"Debug User 1","firstName":"Debug","lastName":"User1","email":"debug1@example.com","password":"password123","groups":["testgroup"]}'

echo "Sending request..."
RESPONSE=$(curl -v -u admin:admin \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$JSON_PAYLOAD" \
    "${BASE_URL}/user/create-json/debug_user_1" 2>&1)

echo "Full response:"
echo "$RESPONSE"
