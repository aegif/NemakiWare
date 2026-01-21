#!/bin/bash

REPO_ID="bedroom"
GROUP_ID="testgroup"
BASE_URL="http://localhost:8080/core/rest/repo/${REPO_ID}"
NUM_USERS=5

echo "=== Concurrent User Creation Test (Fixed with Verbose Output) ==="
echo "Creating $NUM_USERS users in parallel, all added to group: $GROUP_ID"
echo

# Function to create a single user with detailed output
create_user() {
    local user_id=$1
    local timestamp=$(date +%s)

    echo "[$timestamp] Creating user: $user_id" >&2

    # Create user JSON payload
    local json_payload="{\"id\":\"$user_id\",\"name\":\"Test User $user_id\",\"firstName\":\"Test\",\"lastName\":\"User$user_id\",\"email\":\"${user_id}@example.com\",\"password\":\"password123\",\"groups\":[\"$GROUP_ID\"]}"

    # Send POST request with detailed output
    local response=$(curl -s -w "\nHTTP_CODE:%{http_code}" -u admin:admin \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$json_payload" \
        "${BASE_URL}/user/create-json/${user_id}")

    local end_timestamp=$(date +%s)
    local duration=$((end_timestamp - timestamp))
    local http_code=$(echo "$response" | grep "HTTP_CODE" | cut -d: -f2)
    local body=$(echo "$response" | grep -v "HTTP_CODE")

    echo "[$end_timestamp] User $user_id creation completed in ${duration}s - HTTP $http_code" >&2
    echo "Response: $body" >&2
}

# Start parallel user creation
for i in $(seq 1 $NUM_USERS); do
    create_user "concurrent_user_$i" &
done

# Wait for all background jobs
echo "Waiting for all user creation requests to complete..."
wait

echo
echo "=== Checking final group membership ==="
curl -s -u admin:admin "${BASE_URL}/group/show/${GROUP_ID}" | jq '.result.users'

echo
echo "=== Verify users were created ==="
for i in $(seq 1 $NUM_USERS); do
    echo -n "concurrent_user_$i: "
    curl -s -u admin:password "http://localhost:5984/bedroom/_design/_repo/_view/userItemsById?key=\"concurrent_user_$i\"" | jq -r '.rows | length'
done
