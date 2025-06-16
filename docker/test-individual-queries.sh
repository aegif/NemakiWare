#!/bin/bash

echo "=== Testing Individual CMIS Queries ==="

ROOT_FOLDER_ID="e02f784f8360a02cc14d1314c10038ff"

echo "1. Testing basic folder query:"
FOLDER_QUERY='SELECT * FROM cmis:folder'
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query" \
  -d "q=$(echo "$FOLDER_QUERY" | sed 's/ /%20/g')" \
  -H "Content-Type: application/x-www-form-urlencoded" | \
  grep -o 'numItems">[0-9]*' | head -1

echo -e "\n2. Testing root folder by ID query:"
ROOT_QUERY="SELECT cmis:name, cmis:objectId FROM cmis:folder WHERE cmis:objectId = '$ROOT_FOLDER_ID'"
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query" \
  -d "q=$(echo "$ROOT_QUERY" | sed 's/ /%20/g;s/:/%3A/g;s/=/%3D/g;s/'\''/%27/g')" \
  -H "Content-Type: application/x-www-form-urlencoded" | \
  grep -o 'numItems">[0-9]*' | head -1

echo -e "\n3. Testing document query:"
DOC_QUERY='SELECT * FROM cmis:document'
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query" \
  -d "q=$(echo "$DOC_QUERY" | sed 's/ /%20/g')" \
  -H "Content-Type: application/x-www-form-urlencoded" | \
  grep -o 'numItems">[0-9]*' | head -1

echo -e "\n4. Testing simple name query:"
NAME_QUERY="SELECT cmis:name FROM cmis:folder WHERE cmis:name = '/'"
curl -s -u admin:admin "http://localhost:8080/core/atom/bedroom/query" \
  -d "q=$(echo "$NAME_QUERY" | sed 's/ /%20/g;s/:/%3A/g;s/=/%3D/g;s/'\''/%27/g')" \
  -H "Content-Type: application/x-www-form-urlencoded" | \
  grep -o 'numItems">[0-9]*' | head -1
