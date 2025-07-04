#!/bin/bash

# NemakiWare Test Data Creation Script (Browser Binding)
# Creates proper CMIS test data using Browser API (simpler than AtomPub)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CMIS_BASE_URL="http://localhost:8080/core/browser/bedroom"
USERNAME="admin"
PASSWORD="admin"
ROOT_ID="e02f784f8360a02cc14d1314c10038ff"

echo "=== NemakiWare Test Data Creation (Browser API) ==="
echo "Target repository: bedroom"
echo "CMIS endpoint: $CMIS_BASE_URL"
echo "Root folder ID: $ROOT_ID"
echo

# Function to create folder via CMIS Browser binding
create_folder() {
    local parent_id=$1
    local folder_name=$2
    local description=$3
    
    echo "Creating folder: $folder_name"
    
    # Use Browser binding createFolder
    local response=$(curl -s -u "$USERNAME:$PASSWORD" \
        -X POST \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "cmisaction=createFolder" \
        -d "propertyId[0]=cmis:objectTypeId" \
        -d "propertyValue[0]=cmis:folder" \
        -d "propertyId[1]=cmis:name" \
        -d "propertyValue[1]=$folder_name" \
        -d "propertyId[2]=cmis:description" \
        -d "propertyValue[2]=$description" \
        "$CMIS_BASE_URL?objectId=$parent_id")
    
    # Extract object ID from JSON response
    local object_id=$(echo "$response" | grep -o '"cmis:objectId"[^}]*"value"[^"]*"[^"]*"' | sed 's/.*"value"[^"]*"//' | sed 's/".*//' | head -1)
    
    if [ -n "$object_id" ]; then
        echo "✓ Folder created: $folder_name (ID: $object_id)"
        echo "$object_id"
    else
        echo "✗ Failed to create folder: $folder_name"
        echo "Response: $response"
        return 1
    fi
}

# Function to create document via CMIS Browser binding
create_document() {
    local parent_id=$1
    local doc_name=$2
    local content=$3
    local description=$4
    
    echo "Creating document: $doc_name"
    
    # Create temporary file for content
    local temp_file="/tmp/content_$$"
    echo "$content" > "$temp_file"
    
    # Use Browser binding createDocument with multipart form
    local response=$(curl -s -u "$USERNAME:$PASSWORD" \
        -X POST \
        -F "cmisaction=createDocument" \
        -F "propertyId[0]=cmis:objectTypeId" \
        -F "propertyValue[0]=cmis:document" \
        -F "propertyId[1]=cmis:name" \
        -F "propertyValue[1]=$doc_name" \
        -F "propertyId[2]=cmis:description" \
        -F "propertyValue[2]=$description" \
        -F "content=@$temp_file;type=text/plain" \
        "$CMIS_BASE_URL?objectId=$parent_id")
    
    # Clean up
    rm -f "$temp_file"
    
    # Extract object ID from JSON response
    local object_id=$(echo "$response" | grep -o '"cmis:objectId"[^}]*"value"[^"]*"[^"]*"' | sed 's/.*"value"[^"]*"//' | sed 's/".*//' | head -1)
    
    if [ -n "$object_id" ]; then
        echo "✓ Document created: $doc_name (ID: $object_id)"
        echo "$object_id"
    else
        echo "✗ Failed to create document: $doc_name"
        echo "Response excerpt: $(echo "$response" | head -c 200)..."
        return 1
    fi
}

# Main execution
echo "1. Testing repository access..."
TEST_RESPONSE=$(curl -s -u "$USERNAME:$PASSWORD" "$CMIS_BASE_URL?cmisaction=getRepositoryInfo")
if echo "$TEST_RESPONSE" | grep -q "bedroom"; then
    echo "✓ Repository access confirmed"
else
    echo "✗ Repository access failed"
    exit 1
fi

echo

echo "2. Creating test folder structure..."

# Create main test folders
TEST_FOLDER_ID=$(create_folder "$ROOT_ID" "TestFolder" "Main test folder for CMIS query testing")
if [ $? -ne 0 ]; then exit 1; fi

DOCUMENTS_FOLDER_ID=$(create_folder "$ROOT_ID" "Documents" "Test documents folder")
if [ $? -ne 0 ]; then exit 1; fi

PROJECTS_FOLDER_ID=$(create_folder "$ROOT_ID" "Projects" "Projects folder for hierarchical testing")
if [ $? -ne 0 ]; then exit 1; fi

echo

echo "3. Creating sub-folders..."

# Create sub-folders
PROJECT_A_ID=$(create_folder "$PROJECTS_FOLDER_ID" "ProjectA" "Project A documentation")
if [ $? -ne 0 ]; then exit 1; fi

PROJECT_B_ID=$(create_folder "$PROJECTS_FOLDER_ID" "ProjectB" "Project B documentation")
if [ $? -ne 0 ]; then exit 1; fi

ARCHIVE_FOLDER_ID=$(create_folder "$DOCUMENTS_FOLDER_ID" "Archive" "Archived documents")
if [ $? -ne 0 ]; then exit 1; fi

echo

echo "4. Creating test documents..."

# Create various test documents
create_document "$TEST_FOLDER_ID" "sample.txt" "This is a sample text document for CMIS testing.
It contains multiple lines and searchable content.
Keywords: test, sample, cmis, query" "Sample text document"

create_document "$TEST_FOLDER_ID" "readme.md" "# README

This is a README file for testing CMIS functionality.

## Features
- Document creation
- Query testing
- Content search

## Keywords
readme, documentation, markdown, testing" "README documentation file"

create_document "$DOCUMENTS_FOLDER_ID" "report.txt" "Monthly Report - January 2025

Summary of activities and accomplishments:
1. CMIS implementation completed
2. Query functionality tested
3. Documentation updated

Keywords: report, monthly, january, 2025" "Monthly report document"

create_document "$PROJECT_A_ID" "requirements.txt" "Project A Requirements

1. CMIS compliance
2. Query performance optimization  
3. Authentication security
4. Multi-repository support

Status: In Progress
Priority: High" "Project A requirements document"

create_document "$PROJECT_B_ID" "specification.txt" "Project B Technical Specification

Architecture Overview:
- RESTful API endpoints
- CouchDB data persistence
- Solr search integration
- Docker containerization

Keywords: specification, technical, architecture" "Project B specification"

create_document "$ARCHIVE_FOLDER_ID" "old_doc.txt" "This is an archived document from 2024.
Contains historical data and legacy information.
Should be included in archive queries only.

Keywords: archive, legacy, historical, 2024" "Archived legacy document"

echo

echo "5. Verifying created content..."

# Verify content creation using Browser binding
echo "Checking folder count..."
FOLDER_QUERY_RESPONSE=$(curl -s -u "$USERNAME:$PASSWORD" -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "cmisaction=query" \
    -d "q=SELECT COUNT(*) FROM cmis:folder" \
    "$CMIS_BASE_URL")

echo "Checking document count..."
DOCUMENT_QUERY_RESPONSE=$(curl -s -u "$USERNAME:$PASSWORD" -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "cmisaction=query" \
    -d "q=SELECT COUNT(*) FROM cmis:document" \
    "$CMIS_BASE_URL")

echo "Query responses received - checking if data creation was successful"

# Check if we can list children of root folder
CHILDREN_RESPONSE=$(curl -s -u "$USERNAME:$PASSWORD" \
    "$CMIS_BASE_URL?cmisaction=getChildren&objectId=$ROOT_ID&maxItems=20")

if echo "$CHILDREN_RESPONSE" | grep -q "TestFolder"; then
    echo "✓ Test data creation verified - TestFolder found in root"
else
    echo "⚠ Could not verify test data creation"
fi

echo
echo "=== Test Data Creation Completed ==="
echo "Created folder structure:"
echo "  /TestFolder/"
echo "  /Documents/"
echo "    /Archive/"
echo "  /Projects/"
echo "    /ProjectA/"
echo "    /ProjectB/"
echo
echo "Created 6 test documents with varied content for query testing"
echo "Ready for CMIS query testing with realistic data!"