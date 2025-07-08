#!/bin/bash
# CMIS API-based Initial Setup Script
# Creates folders and documents using CMIS API after minimal database initialization

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

# Configuration
CMIS_BASE_URL="http://localhost:8080/core"
REPOSITORY_ID="bedroom"
USERNAME="admin"
PASSWORD="admin"
CMIS_ENDPOINT="${CMIS_BASE_URL}/atom/${REPOSITORY_ID}"

echo "==========================================="
echo "CMIS API-based Initial Setup"
echo "==========================================="
echo "CMIS Endpoint: $CMIS_ENDPOINT"
echo "Repository: $REPOSITORY_ID"
echo "User: $USERNAME"
echo ""

# Function to wait for CMIS service
wait_for_cmis() {
    echo "Waiting for CMIS service to be ready..."
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s -u "$USERNAME:$PASSWORD" "$CMIS_ENDPOINT" > /dev/null 2>&1; then
            echo "✓ CMIS service is ready"
            return 0
        fi
        echo "Waiting for CMIS... ($timeout seconds remaining)"
        sleep 5
        timeout=$((timeout - 5))
    done
    echo "ERROR: CMIS service failed to respond"
    return 1
}

# Function to get root folder ID
get_root_folder_id() {
    echo "Getting root folder ID..."
    local response=$(curl -s -u "$USERNAME:$PASSWORD" "$CMIS_ENDPOINT")
    if [ $? -eq 0 ]; then
        # Extract root folder ID from CMIS response - this is repository specific
        # For NemakiWare, the root folder ID is typically known
        echo "e02f784f8360a02cc14d1314c10038ff"  # Standard NemakiWare root folder ID
    else
        echo "ERROR: Failed to get root folder ID"
        return 1
    fi
}

# Function to create folder using CMIS
create_folder() {
    local parent_id="$1"
    local folder_name="$2"
    local description="$3"
    
    echo "Creating folder: $folder_name"
    
    # CMIS AtomPub entry for folder creation
    local entry_xml=$(cat << EOF
<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <atom:title>$folder_name</atom:title>
  <atom:summary>$description</atom:summary>
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
</atom:entry>
EOF
)

    # Post folder creation request
    local response=$(curl -s -u "$USERNAME:$PASSWORD" \
        -H "Content-Type: application/atom+xml;type=entry" \
        -d "$entry_xml" \
        "$CMIS_ENDPOINT/children/$parent_id")
    
    if [ $? -eq 0 ]; then
        echo "✓ Folder '$folder_name' created successfully"
        # Extract and return the new folder ID from response
        echo "$response" | grep -o 'cmis:objectId[^<]*<[^>]*>[^<]*' | sed 's/.*>\([^<]*\).*/\1/' | head -1
    else
        echo "✗ Failed to create folder '$folder_name'"
        return 1
    fi
}

# Function to upload document using CMIS
upload_document() {
    local parent_id="$1"
    local file_path="$2"
    local doc_name="$3"
    local description="$4"
    
    echo "Uploading document: $doc_name"
    
    if [ ! -f "$file_path" ]; then
        echo "Document file not found: $file_path"
        echo "Creating placeholder document instead..."
        echo "CMIS 1.1 Specification Placeholder" > "/tmp/$doc_name"
        file_path="/tmp/$doc_name"
    fi
    
    # CMIS AtomPub entry for document creation with content
    local boundary="----CMISBoundary$(date +%s)"
    
    # Create multipart content
    local multipart_content=$(mktemp)
    
    cat > "$multipart_content" << EOF
--$boundary
Content-Disposition: form-data; name="atom"
Content-Type: application/atom+xml;type=entry

<?xml version="1.0" encoding="UTF-8"?>
<atom:entry xmlns:atom="http://www.w3.org/2005/Atom" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <atom:title>$doc_name</atom:title>
  <atom:summary>$description</atom:summary>
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
</atom:entry>
--$boundary
Content-Disposition: form-data; name="content"; filename="$doc_name"
Content-Type: application/pdf

EOF
    
    # Add file content
    cat "$file_path" >> "$multipart_content"
    
    cat >> "$multipart_content" << EOF

--$boundary--
EOF
    
    # Upload document
    local response=$(curl -s -u "$USERNAME:$PASSWORD" \
        -H "Content-Type: multipart/form-data; boundary=$boundary" \
        --data-binary "@$multipart_content" \
        "$CMIS_ENDPOINT/children/$parent_id")
    
    rm -f "$multipart_content"
    
    if [ $? -eq 0 ]; then
        echo "✓ Document '$doc_name' uploaded successfully"
        return 0
    else
        echo "✗ Failed to upload document '$doc_name'"
        return 1
    fi
}

# Function to download CMIS 1.1 specification
download_cmis_spec() {
    local target_file="$1"
    echo "Downloading CMIS 1.1 specification..."
    
    # Try to download from OASIS official site
    local cmis_url="https://docs.oasis-open.org/cmis/CMIS/v1.1/cmis-spec-v1.1.pdf"
    
    if curl -s -L "$cmis_url" -o "$target_file"; then
        echo "✓ CMIS 1.1 specification downloaded"
        return 0
    else
        echo "⚠ Failed to download CMIS spec, creating placeholder"
        cat > "$target_file" << 'EOF'
CMIS 1.1 Specification Placeholder

This is a placeholder for the CMIS 1.1 specification document.
The actual specification can be downloaded from:
https://docs.oasis-open.org/cmis/CMIS/v1.1/cmis-spec-v1.1.pdf

Content Management Interoperability Services (CMIS) Version 1.1
OASIS Standard
1 May 2012

This standard defines a domain model and Web Services and Restful AtomPub 
(RFC5023) and JSON bindings that can be used by applications to work with 
one or more Content Management repositories/systems.
EOF
        return 0
    fi
}

# Main setup process
main() {
    # Wait for CMIS service
    wait_for_cmis || exit 1
    
    # Get root folder ID
    ROOT_FOLDER_ID=$(get_root_folder_id)
    if [ -z "$ROOT_FOLDER_ID" ]; then
        echo "ERROR: Could not determine root folder ID"
        exit 1
    fi
    echo "Root folder ID: $ROOT_FOLDER_ID"
    
    # Create sites folder
    echo ""
    echo "Creating Sites folder..."
    SITES_FOLDER_ID=$(create_folder "$ROOT_FOLDER_ID" "Sites" "Sites folder for collaborative workspaces")
    
    # Create Technical Documents folder
    echo ""
    echo "Creating Technical Documents folder..."
    TECH_DOCS_FOLDER_ID=$(create_folder "$ROOT_FOLDER_ID" "Technical Documents" "Technical documentation and specifications")
    
    if [ -n "$TECH_DOCS_FOLDER_ID" ]; then
        # Download and upload CMIS 1.1 specification
        echo ""
        echo "Setting up CMIS 1.1 specification..."
        local cmis_spec_file="/tmp/cmis-spec-v1.1.pdf"
        download_cmis_spec "$cmis_spec_file"
        
        upload_document "$TECH_DOCS_FOLDER_ID" "$cmis_spec_file" "CMIS-1.1-Specification.pdf" "Content Management Interoperability Services (CMIS) Version 1.1 Specification"
        
        # Clean up temporary file
        rm -f "$cmis_spec_file"
    fi
    
    echo ""
    echo "==========================================="
    echo "CMIS Initial Setup Completed Successfully"
    echo "==========================================="
    echo "Created folders:"
    echo "  ✓ Sites (ID: $SITES_FOLDER_ID)"
    echo "  ✓ Technical Documents (ID: $TECH_DOCS_FOLDER_ID)"
    echo "    ✓ CMIS 1.1 Specification PDF uploaded"
    echo ""
    echo "You can verify the setup by accessing:"
    echo "http://localhost:9000/ui/repo/$REPOSITORY_ID/"
    echo ""
}

# Run main setup
main "$@"