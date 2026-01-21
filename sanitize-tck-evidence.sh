#!/bin/bash

# TCK Test Evidence XML Sanitizer
# Removes <properties> section containing personal/environment information

set -e

EVIDENCE_DIR="$1"

if [ -z "$EVIDENCE_DIR" ]; then
    echo "Usage: $0 <evidence-directory>"
    exit 1
fi

if [ ! -d "$EVIDENCE_DIR" ]; then
    echo "Error: Directory $EVIDENCE_DIR does not exist"
    exit 1
fi

echo "Sanitizing TCK evidence XML files in $EVIDENCE_DIR"

# Process all TEST-*.xml files
for xml_file in "$EVIDENCE_DIR"/TEST-*.xml; do
    if [ -f "$xml_file" ]; then
        echo "Processing: $(basename "$xml_file")"
        
        # Create backup
        cp "$xml_file" "$xml_file.backup"
        
        # Use Python to remove <properties> section while preserving XML structure
        python3 - "$xml_file" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

xml_file = sys.argv[1]

# Parse XML
tree = ET.parse(xml_file)
root = tree.getroot()

# Find and remove <properties> element
for properties in root.findall('properties'):
    root.remove(properties)

# Write back to file with XML declaration
tree.write(xml_file, encoding='UTF-8', xml_declaration=True)

print(f"  ✓ Removed <properties> section")
PYEOF
    fi
done

echo ""
echo "Sanitization complete!"
echo "Original files backed up with .backup extension"
echo ""
echo "Review changes with:"
echo "  diff -u <file>.backup <file>"
