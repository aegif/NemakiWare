#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEMAKI_HOME="$(dirname "$SCRIPT_DIR")"
DUMP_DIR="$NEMAKI_HOME/setup/couchdb/initial_import"

echo "Creating repository-specific dump files..."

echo "Creating canopy_init.dump from bedroom_init.dump..."
if [ -f "$DUMP_DIR/bedroom_init.dump" ]; then
    cp "$DUMP_DIR/bedroom_init.dump" "$DUMP_DIR/canopy_init.dump"
    echo "✓ Created canopy_init.dump"
else
    echo "✗ ERROR: bedroom_init.dump not found"
    exit 1
fi

for repo in bedroom canopy; do
    dump_file="$DUMP_DIR/${repo}_init.dump"
    if [ -f "$dump_file" ]; then
        echo "✓ ${repo}_init.dump exists"
        echo "  Sample content:"
        head -3 "$dump_file" | sed 's/^/    /'
    else
        echo "✗ ERROR: ${repo}_init.dump missing"
        exit 1
    fi
done

echo ""
echo "Repository-specific dump files created successfully!"
echo "Note: Object ID transformation will happen during loading via bjornloka.jar"
