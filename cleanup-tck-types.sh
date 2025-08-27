#!/bin/bash
echo "=== TCK Test Types Cleanup ==="

# Get all TCK test types (tck:testid_*) - Fixed JSON structure
TCK_TYPES=$(curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=typeChildren&typeId=cmis:document" | jq -r '.types[]? | select(.id | startswith("tck:testid_")) | .id')

if [ -z "$TCK_TYPES" ]; then
    echo "No TCK test types found to clean up"
    exit 0
fi

echo "Found TCK test types to cleanup:"
echo "$TCK_TYPES"
echo

TYPE_COUNT=0
for TYPE_ID in $TCK_TYPES; do
    echo "Deleting type: $TYPE_ID"
    
    # Delete the type definition using Browser Binding
    RESULT=$(curl -s -u admin:admin -X POST \
        -F "cmisaction=deleteType" \
        -F "typeId=$TYPE_ID" \
        "http://localhost:8080/core/browser/bedroom")
    
    if echo "$RESULT" | grep -q error; then
        echo "  ❌ Failed to delete $TYPE_ID: $RESULT"
    else
        echo "  ✅ Successfully deleted $TYPE_ID"
        TYPE_COUNT=$((TYPE_COUNT + 1))
    fi
done

echo
echo "=== Cleanup Summary ==="
echo "Deleted $TYPE_COUNT TCK test types"

# Verify cleanup
REMAINING=$(curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=typeChildren&typeId=cmis:document" | jq -r '.types[]? | select(.id | startswith("tck:testid_")) | .id' | wc -l)

echo "Remaining TCK types: $REMAINING"

if [ "$REMAINING" -eq 0 ]; then
    echo "🎉 All TCK test types successfully cleaned up!"
else
    echo "⚠️  Some TCK types remain - may need manual cleanup"
    echo "Remaining types:"
    curl -s -u admin:admin "http://localhost:8080/core/browser/bedroom?cmisselector=typeChildren&typeId=cmis:document" | jq -r '.types[]? | select(.id | startswith("tck:testid_")) | .id'
fi
