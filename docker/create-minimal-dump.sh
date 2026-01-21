#!/bin/bash
# Create minimal dump files containing only essential elements
# This removes sites and other content folders, keeping only core system elements

set -e

SCRIPT_DIR=$(cd $(dirname $0); pwd)
NEMAKI_HOME=$(cd $SCRIPT_DIR/..; pwd)

echo "==========================================="
echo "Creating Minimal Dump Files"
echo "==========================================="

# Function to create minimal dump from existing dump
create_minimal_dump() {
    local input_dump="$1"
    local output_dump="$2"
    local description="$3"
    
    echo "Creating minimal $description..."
    echo "Input:  $input_dump"
    echo "Output: $output_dump"
    
    if [ ! -f "$input_dump" ]; then
        echo "ERROR: Input dump file not found: $input_dump"
        return 1
    fi
    
    # Create temporary working directory
    local temp_dir=$(mktemp -d)
    local temp_filtered="$temp_dir/filtered.json"
    
    echo "Filtering dump file..."
    
    # Filter the dump to keep only essential elements
    # Keep: type definitions, design documents, root folder, admin user, basic system documents
    # Remove: sites folders, content documents, user-created content
    cat "$input_dump" | while IFS= read -r line; do
        local doc_line="$line"
        
        # Skip empty lines and invalid JSON
        if [ -z "$doc_line" ] || [ "$doc_line" = "null" ]; then
            continue
        fi
        
        # Try to parse the document type
        local doc_type=""
        local object_type=""
        local folder_path=""
        
        # Extract type information using jq if available, otherwise use grep
        if command -v jq >/dev/null 2>&1; then
            doc_type=$(echo "$doc_line" | jq -r '.type // empty' 2>/dev/null || echo "")
            object_type=$(echo "$doc_line" | jq -r '.objectType // empty' 2>/dev/null || echo "")
            folder_path=$(echo "$doc_line" | jq -r '.name // empty' 2>/dev/null || echo "")
        else
            # Fallback to grep-based extraction
            doc_type=$(echo "$doc_line" | grep -o '"type"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*:.*"\([^"]*\)".*/\1/' || echo "")
            object_type=$(echo "$doc_line" | grep -o '"objectType"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*:.*"\([^"]*\)".*/\1/' || echo "")
            folder_path=$(echo "$doc_line" | grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*:.*"\([^"]*\)".*/\1/' || echo "")
        fi
        
        # Keep essential system elements
        local keep_document=false
        
        case "$doc_type" in
            "_design")
                # Always keep design documents (CouchDB views)
                keep_document=true
                echo "Keeping design document: $doc_line" >> "$temp_dir/debug.log"
                ;;
            "typeDefinition"|"type")
                # Always keep type definitions
                keep_document=true
                echo "Keeping type definition: $object_type" >> "$temp_dir/debug.log"
                ;;
            "cmis:folder")
                # Keep root folder and essential system folders
                case "$folder_path" in
                    ""|"Root"|"root")
                        keep_document=true
                        echo "Keeping root folder" >> "$temp_dir/debug.log"
                        ;;
                    "Sites"|"sites")
                        # Skip Sites folder - will be created via CMIS API
                        echo "Skipping Sites folder" >> "$temp_dir/debug.log"
                        ;;
                    "Technical Documents"|"technical-documents")
                        # Skip Technical Documents folder - will be created via CMIS API
                        echo "Skipping Technical Documents folder" >> "$temp_dir/debug.log"
                        ;;
                    *)
                        # Skip other folders - they will be created as needed
                        echo "Skipping folder: $folder_path" >> "$temp_dir/debug.log"
                        ;;
                esac
                ;;
            "cmis:item")
                # Keep admin user and essential system items
                if echo "$doc_line" | grep -q '"userId"[[:space:]]*:[[:space:]]*"admin"'; then
                    keep_document=true
                    echo "Keeping admin user" >> "$temp_dir/debug.log"
                elif echo "$doc_line" | grep -q '"objectType"[[:space:]]*:[[:space:]]*"nemaki:user"'; then
                    # Keep system users only
                    local user_id=$(echo "$doc_line" | grep -o '"userId"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/.*:.*"\([^"]*\)".*/\1/' || echo "")
                    case "$user_id" in
                        "admin"|"system"|"anonymous")
                            keep_document=true
                            echo "Keeping system user: $user_id" >> "$temp_dir/debug.log"
                            ;;
                        *)
                            echo "Skipping user: $user_id" >> "$temp_dir/debug.log"
                            ;;
                    esac
                elif echo "$doc_line" | grep -q '"objectType"[[:space:]]*:[[:space:]]*"nemaki:group"'; then
                    # Keep essential groups
                    keep_document=true
                    echo "Keeping group" >> "$temp_dir/debug.log"
                else
                    echo "Skipping item: $object_type" >> "$temp_dir/debug.log"
                fi
                ;;
            "cmis:document")
                # Skip all documents - they will be created via CMIS API
                echo "Skipping document" >> "$temp_dir/debug.log"
                ;;
            *)
                # For unknown types, keep if they seem system-related
                if echo "$doc_line" | grep -q -E '"_id"[[:space:]]*:[[:space:]]*"_design|"type"[[:space:]]*:[[:space:]]*"system'; then
                    keep_document=true
                    echo "Keeping system document" >> "$temp_dir/debug.log"
                else
                    echo "Skipping unknown type: $doc_type" >> "$temp_dir/debug.log"
                fi
                ;;
        esac
        
        # Output the document if it should be kept
        if [ "$keep_document" = "true" ]; then
            echo "$doc_line" >> "$temp_filtered"
        fi
        
    done < "$input_dump"
    
    # Copy filtered result to output
    if [ -f "$temp_filtered" ]; then
        cp "$temp_filtered" "$output_dump"
        local original_lines=$(wc -l < "$input_dump")
        local filtered_lines=$(wc -l < "$output_dump")
        echo "✓ Filtered dump created: $filtered_lines lines (was $original_lines)"
        echo "Debug log: $temp_dir/debug.log"
    else
        echo "ERROR: Failed to create filtered dump"
        return 1
    fi
    
    # Clean up temporary directory after a delay to allow inspection
    echo "Temporary files in: $temp_dir (will be cleaned up)"
    # rm -rf "$temp_dir"
}

# Main process
main() {
    local dump_dir="$NEMAKI_HOME/setup/couchdb/initial_import"
    
    # Check for existing dump files
    if [ ! -f "$dump_dir/bedroom_init.dump" ]; then
        echo "ERROR: Original bedroom_init.dump not found in $dump_dir"
        exit 1
    fi
    
    # Create minimal dumps
    create_minimal_dump "$dump_dir/bedroom_init.dump" "$dump_dir/bedroom_init_minimal.dump" "bedroom minimal dump"
    
    # For canopy, we can use the same minimal structure
    if [ -f "$dump_dir/canopy_init.dump" ]; then
        create_minimal_dump "$dump_dir/canopy_init.dump" "$dump_dir/canopy_init_minimal.dump" "canopy minimal dump"
    else
        echo "Creating canopy minimal dump from bedroom..."
        cp "$dump_dir/bedroom_init_minimal.dump" "$dump_dir/canopy_init_minimal.dump"
    fi
    
    echo ""
    echo "==========================================="
    echo "Minimal Dump Creation Completed"
    echo "==========================================="
    echo "Created files:"
    echo "  ✓ $dump_dir/bedroom_init_minimal.dump"
    echo "  ✓ $dump_dir/canopy_init_minimal.dump"
    echo ""
    echo "These dumps contain only:"
    echo "  - Type definitions"
    echo "  - Design documents"
    echo "  - Root folder"
    echo "  - Admin user and essential system items"
    echo ""
    echo "Folders and documents will be created via CMIS API during setup."
}

# Run main process
main "$@"