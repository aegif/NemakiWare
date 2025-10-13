#!/bin/bash

echo "=== Cleaning up test users and group ==="

# Delete test users
for i in {1..5}; do
    echo "Deleting concurrent_user_$i..."
    curl -s -u admin:admin -X DELETE \
        "http://localhost:8080/core/rest/repo/bedroom/users/concurrent_user_$i" \
        -o /dev/null
done

# Delete test group
echo "Deleting testgroup..."
curl -s -u admin:admin -X DELETE \
    "http://localhost:8080/core/rest/repo/bedroom/groups/testgroup" \
    -o /dev/null

echo "=== Cleanup completed ==="
