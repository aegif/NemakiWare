#!/bin/bash

# Concurrent user creation test to verify ThreadLockService implementation
# Creates multiple users in parallel, all added to the same group

REPO_ID="bedroom"
GROUP_ID="testgroup"
BASE_URL="http://localhost:8080/core/rest/repo/${REPO_ID}"
NUM_USERS=5

echo "=== Concurrent User Creation Test ==="
echo "Creating $NUM_USERS users in parallel, all added to group: $GROUP_ID"
echo

# Function to create a single user
create_user() {
    local user_id=$1
    local timestamp=$(date +%s)

    echo "[$timestamp] Creating user: $user_id"

    # Create user JSON payload
    local json_payload=$(cat <<EOF
{
    "id": "$user_id",
    "name": "Test User $user_id",
    "firstName": "Test",
    "lastName": "User$user_id",
    "email": "${user_id}@example.com",
    "password": "password123",
    "groups": ["$GROUP_ID"]
}
EOF
)

    # Send POST request
    local response=$(curl -s -u admin:admin \
        -X POST \
        -H "Content-Type: application/json" \
        -d "$json_payload" \
        "${BASE_URL}/user/create-json/${user_id}")

    local end_timestamp=$(date +%s)
    local duration=$((end_timestamp - timestamp))

    echo "[$end_timestamp] User $user_id creation completed in ${duration}s"
    echo "Response: $response" | head -c 200
    echo
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
echo "=== Checking for revision conflicts in logs ==="
docker logs docker-core-1 2>&1 | grep -E "(conflict|Conflict|revision|Acquired write lock|Released write lock)" | tail -20
