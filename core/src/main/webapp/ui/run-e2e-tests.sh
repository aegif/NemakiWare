#!/bin/bash
# E2E Test Runner for NemakiWare UI
# Usage: ./run-e2e-tests.sh [mode] [browser] [--no-cleanup]
#   mode: all | quick | required | type | auth | document | cleanup
#   browser: chromium (default) | firefox | webkit
#   --no-cleanup: Skip pre-test cleanup

set -e

MODE="${1:-all}"
PROJECT="${2:-chromium}"
SKIP_CLEANUP=false

# Parse options
for arg in "$@"; do
  case $arg in
    --no-cleanup)
      SKIP_CLEANUP=true
      ;;
  esac
done

cd "$(dirname "$0")"

echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║           NemakiWare UI E2E Tests                             ║"
echo "╠═══════════════════════════════════════════════════════════════╣"
echo "║  Mode:    $MODE"
echo "║  Browser: $PROJECT"
echo "║  Cleanup: $([ "$SKIP_CLEANUP" = true ] && echo "Skipped" || echo "Enabled")"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# Run cleanup before tests (unless skipped or cleanup-only mode)
if [ "$SKIP_CLEANUP" = false ] && [ "$MODE" != "cleanup" ]; then
  echo "📋 Running pre-test cleanup..."
  ./scripts/cleanup-test-artifacts.sh
  echo ""
fi

case "$MODE" in
  cleanup)
    echo "🧹 Running cleanup only..."
    ./scripts/cleanup-test-artifacts.sh
    ;;
  quick)
    echo "⚡ Running quick sanity tests (~2 minutes)..."
    npx playwright test tests/basic-connectivity.spec.ts tests/debug-auth.spec.ts --project="$PROJECT"
    ;;
  required)
    echo "✅ Running required property validation tests (~3 minutes)..."
    npx playwright test tests/documents/required-property-validation.spec.ts --project="$PROJECT"
    ;;
  type)
    echo "📝 Running type management tests (~10 minutes)..."
    npx playwright test tests/admin/type-*.spec.ts tests/admin/custom-type-*.spec.ts --project="$PROJECT"
    ;;
  auth)
    echo "🔐 Running authentication tests (~5 minutes)..."
    npx playwright test tests/auth/*.spec.ts --project="$PROJECT"
    ;;
  document)
    echo "📄 Running document operation tests (~10 minutes)..."
    npx playwright test tests/documents/*.spec.ts --project="$PROJECT"
    ;;
  all)
    echo "🚀 Running all tests (~20 minutes)..."
    npx playwright test --project="$PROJECT"
    ;;
  *)
    echo "❌ Unknown mode: $MODE"
    echo ""
    echo "Available modes:"
    echo "  all      - Run all tests (default, ~20 minutes)"
    echo "  quick    - Quick sanity tests (~2 minutes)"
    echo "  required - Required property validation tests (~3 minutes)"
    echo "  type     - Type management tests (~10 minutes)"
    echo "  auth     - Authentication tests (~5 minutes)"
    echo "  document - Document operation tests (~10 minutes)"
    echo "  cleanup  - Run cleanup only (no tests)"
    echo ""
    echo "Options:"
    echo "  --no-cleanup  Skip pre-test cleanup"
    exit 1
    ;;
esac

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║                     Tests Complete                            ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
