#!/bin/bash
# Cleanup Test Artifacts Script for NemakiWare
# This script removes test types and test documents to ensure clean test runs

CMIS_BASE_URL="${CMIS_BASE_URL:-http://localhost:8080/core/browser/bedroom}"
REST_BASE_URL="${REST_BASE_URL:-http://localhost:8080/core/rest/repo/bedroom}"
AUTH="admin:admin"

echo "=== NemakiWare Test Artifact Cleanup ==="
echo ""

# 1. Clean up test types
echo "1. Cleaning up test types..."
TEST_TYPES=$(curl -s -u "$AUTH" "${REST_BASE_URL}/type/list" | jq -r '.types[]? | select(.id | startswith("test:")) | .id' 2>/dev/null)
if [ -n "$TEST_TYPES" ]; then
  COUNT=0
  while IFS= read -r TYPE_ID; do
    if [ -n "$TYPE_ID" ]; then
      curl -s -u "$AUTH" -X POST \
        -H "Content-Type: application/x-www-form-urlencoded" \
        "$CMIS_BASE_URL" \
        -d "cmisaction=deleteType&typeId=${TYPE_ID}" > /dev/null 2>&1
      ((COUNT++))
    fi
  done <<< "$TEST_TYPES"
  echo "   Deleted $COUNT test types"
else
  echo "   No test types found"
fi

# 2. Clean up test documents in root folder
echo "2. Cleaning up test documents..."
TEST_OBJECTS=$(curl -s -u "$AUTH" "${CMIS_BASE_URL}/root?cmisselector=children" | \
  jq -r '.objects[]?.object.properties |
    select(.["cmis:name"].value |
      if type == "array" then .[0] else . end |
      test("^test-|^search-|^bugfix-|^debug-|^archive-test")
    ) | .["cmis:objectId"].value |
    if type == "array" then .[0] else . end' 2>/dev/null)

if [ -n "$TEST_OBJECTS" ]; then
  COUNT=0
  while IFS= read -r OBJ_ID; do
    if [ -n "$OBJ_ID" ]; then
      curl -s -u "$AUTH" -X POST \
        -H "Content-Type: application/x-www-form-urlencoded" \
        "$CMIS_BASE_URL" \
        -d "cmisaction=delete&objectId=${OBJ_ID}&allVersions=true" > /dev/null 2>&1
      ((COUNT++))
    fi
  done <<< "$TEST_OBJECTS"
  echo "   Deleted $COUNT test documents"
else
  echo "   No test documents found"
fi

# 3. Clean up test folders (may contain test documents)
echo "3. Cleaning up test folders..."
TEST_FOLDERS=$(curl -s -u "$AUTH" "${CMIS_BASE_URL}/root?cmisselector=children" | \
  jq -r '.objects[]? | select(.object.properties["cmis:baseTypeId"].value == "cmis:folder") |
    select(.object.properties["cmis:name"].value | test("^test-|^bulk-")) |
    .object.properties["cmis:objectId"].value' 2>/dev/null)

if [ -n "$TEST_FOLDERS" ]; then
  COUNT=0
  while IFS= read -r FOLDER_ID; do
    if [ -n "$FOLDER_ID" ]; then
      # Delete folder tree (includes children)
      curl -s -u "$AUTH" -X POST \
        -H "Content-Type: application/x-www-form-urlencoded" \
        "$CMIS_BASE_URL" \
        -d "cmisaction=deleteTree&objectId=${FOLDER_ID}&allVersions=true&continueOnFailure=true" > /dev/null 2>&1
      ((COUNT++))
    fi
  done <<< "$TEST_FOLDERS"
  echo "   Deleted $COUNT test folders"
else
  echo "   No test folders found"
fi

echo ""
echo "=== Cleanup Complete ==="
