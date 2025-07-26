#!/bin/sh

# DEPRECATED: This initialization approach is replaced by Core module's PatchService
# The Core module now handles all database initialization automatically on startup
# This includes:
# - Database creation
# - Design document setup  
# - Dump file data loading
# - Configuration database initialization

echo "=== LEGACY INITIALIZER ==="
echo "This initializer is deprecated. Database initialization is now handled"
echo "automatically by the Core module's PatchService during startup."
echo ""
echo "No manual initialization required."
echo "=== INITIALIZATION SKIPPED ==="

exit 0