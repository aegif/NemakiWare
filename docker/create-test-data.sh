#!/bin/bash

# NemakiWare Test Data Creation Script
# Creates proper CMIS test data using AtomPub API

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CMIS_BASE_URL="http://localhost:8080/core/atom/bedroom"
USERNAME="admin"
PASSWORD="admin"

echo "=== NemakiWare Test Data Creation ==="
echo "Target repository: bedroom"
echo "CMIS endpoint: $CMIS_BASE_URL"
echo

# Function to create folder via CMIS AtomPub
create_folder() {
    local parent_id=$1
    local folder_name=$2
    local description=$3
    
    echo "Creating folder: $folder_name"
    
    # CMIS AtomPub create folder entry
    local atom_entry=$(cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <title>$folder_name</title>
  <cmis:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:folder</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>$folder_name</cmis:value>
      </cmis:propertyString>
      <cmis:propertyString propertyDefinitionId="cmis:description">
        <cmis:value>$description</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmis:object>
</entry>
EOF
)
    
    # POST to create folder  
    local response=$(curl -s -u "$USERNAME:$PASSWORD" \
        -H "Content-Type: application/atom+xml;type=entry" \
        -X POST \
        -d "$atom_entry" \
        "$CMIS_BASE_URL/children?id=$parent_id")
    
    # Extract object ID from response
    local object_id=$(echo "$response" | grep -o 'cmis:objectId.*value>[^<]*' | sed 's/.*value>//' | head -1)
    
    if [ -n "$object_id" ]; then
        echo "✓ Folder created: $folder_name (ID: $object_id)"
        echo "$object_id"
    else
        echo "✗ Failed to create folder: $folder_name"
        echo "Response: $response"
        return 1
    fi
}

# Function to create document via CMIS AtomPub
create_document() {
    local parent_id=$1
    local doc_name=$2
    local content=$3
    local description=$4
    
    echo "Creating document: $doc_name"
    
    # Create multipart content
    local boundary="----NemakiWareBoundary$(date +%s)"
    local temp_file="/tmp/cmis_multipart_$$"
    
    # Build multipart request
    cat > "$temp_file" <<EOF
--$boundary
Content-Type: application/atom+xml; charset=UTF-8

<?xml version="1.0" encoding="UTF-8"?>
<entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <title>$doc_name</title>
  <cmis:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:document</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>$doc_name</cmis:value>
      </cmis:propertyString>
      <cmis:propertyString propertyDefinitionId="cmis:description">
        <cmis:value>$description</cmis:value>
      </cmis:propertyString>
    </cmis:properties>
  </cmis:object>
</entry>
--$boundary
Content-Type: text/plain; charset=UTF-8
Content-Disposition: attachment; filename="$doc_name"

$content
--$boundary--
EOF
    
    # POST to create document
    local response=$(curl -s -u "$USERNAME:$PASSWORD" \
        -H "Content-Type: multipart/related; boundary=$boundary" \
        -X POST \
        --data-binary "@$temp_file" \
        "$CMIS_BASE_URL/children?id=$parent_id")
    
    # Clean up
    rm -f "$temp_file"
    
    # Extract object ID from response
    local object_id=$(echo "$response" | grep -o 'cmis:objectId.*value>[^<]*' | sed 's/.*value>//' | head -1)
    
    if [ -n "$object_id" ]; then
        echo "✓ Document created: $doc_name (ID: $object_id)"
        echo "$object_id"
    else
        echo "✗ Failed to create document: $doc_name"
        echo "Response: $response"
        return 1
    fi
}

# Main execution
echo "1. Getting repository root folder ID..."
ROOT_RESPONSE=$(curl -s -u "$USERNAME:$PASSWORD" "$CMIS_BASE_URL/")
ROOT_ID=$(echo "$ROOT_RESPONSE" | grep -o 'cmis:rootFolderId>[^<]*' | sed 's/cmis:rootFolderId>//' | head -1)

if [ -z "$ROOT_ID" ]; then
    echo "✗ Failed to get root folder ID"
    echo "Response excerpt: $(echo "$ROOT_RESPONSE" | head -c 500)..."
    exit 1
fi

echo "✓ Root folder ID: $ROOT_ID"
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

# Verify content creation
echo "Checking folder count..."
FOLDER_COUNT=$(curl -s -u "$USERNAME:$PASSWORD" -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "q=SELECT COUNT(*) FROM cmis:folder" \
    "$CMIS_BASE_URL/query" | grep -o '<cmis:propertyInteger.*>[0-9]*' | sed 's/.*>//' | head -1)

echo "Checking document count..."
DOCUMENT_COUNT=$(curl -s -u "$USERNAME:$PASSWORD" -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "q=SELECT COUNT(*) FROM cmis:document" \
    "$CMIS_BASE_URL/query" | grep -o '<cmis:propertyInteger.*>[0-9]*' | sed 's/.*>//' | head -1)

echo "✓ Total folders: $FOLDER_COUNT"
echo "✓ Total documents: $DOCUMENT_COUNT"

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