#!/bin/bash
#
# NemakiWare Standard Test Runner
#
# This script runs the standard Playwright tests without requiring
# LDAP or Keycloak external authentication services.
#
# Prerequisites:
# - Docker containers running (docker-compose-simple.yml)
# - NemakiWare backend available at http://localhost:8080
#
# Usage:
#   ./scripts/run-standard-tests.sh
#   ./scripts/run-standard-tests.sh --headed  # Run with browser visible
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UI_DIR="$(dirname "$SCRIPT_DIR")"

cd "$UI_DIR"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║           NemakiWare Standard Test Suite                       ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Check if backend is available
echo "🔍 Checking NemakiWare backend..."
if curl -s -o /dev/null -w "%{http_code}" -u admin:admin http://localhost:8080/core/atom/bedroom | grep -q "200"; then
    echo "✅ Backend is available"
else
    echo "❌ Backend is not available at http://localhost:8080"
    echo ""
    echo "Please start the Docker containers:"
    echo "  cd docker && docker compose -f docker-compose-simple.yml up -d"
    exit 1
fi

echo ""
echo "🧪 Running standard tests (external auth tests will be skipped)..."
echo ""

# Set environment variable to skip Keycloak
export SKIP_KEYCLOAK=true

# Run tests excluding external auth specific tests
npx playwright test \
    --project=chromium \
    --ignore-snapshots \
    "$@"

echo ""
echo "✅ Standard test suite completed!"
