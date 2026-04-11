#!/bin/bash
#
# CI guard: verify that all shell/Python/Markdown files calling
# /rest/repo/ with mutating HTTP methods include a CSRF header.
#
# Usage: scripts/check-csrf-headers.sh [--fix]
#
# Exit code 0 = no violations found, 1 = violations exist.

set -euo pipefail
cd "$(dirname "$0")/.."

VIOLATIONS=0

echo "=== Checking CSRF header compliance for /rest/repo/ mutating calls ==="

# Find shell scripts and markdown files with mutating REST calls
while IFS= read -r file; do
    # Skip node_modules, target, .git
    case "$file" in
        */node_modules/*|*/target/*|*/.git/*|*/check-csrf-headers.sh) continue ;;
    esac

    # Extract lines with mutating REST calls (POST/PUT/DELETE to /rest/)
    while IFS= read -r line; do
        linenum=$(echo "$line" | cut -d: -f1)
        content=$(echo "$line" | cut -d: -f2-)

        # Check if X-Requested-With or Origin header is present
        # Look at this line and next 3 lines for multi-line curl
        context=$(sed -n "${linenum},$((linenum + 4))p" "$file" 2>/dev/null)
        if ! echo "$context" | grep -qi "X-Requested-With\|Origin:\|_get_rest_headers\|CSRF"; then
            echo "  VIOLATION: $file:$linenum"
            echo "    $content"
            VIOLATIONS=$((VIOLATIONS + 1))
        fi
    done < <(grep -n "curl.*-X POST.*rest/\|curl.*-X DELETE.*rest/\|curl.*-X PUT.*rest/\|requests\.post.*rest\|requests\.put.*rest" "$file" 2>/dev/null || true)
done < <(find . -name "*.sh" -o -name "*.md" -o -name "*.py" | grep -v node_modules | grep -v target | grep -v .git)

echo
if [ "$VIOLATIONS" -eq 0 ]; then
    echo "✅ No CSRF header violations found."
    exit 0
else
    echo "❌ Found $VIOLATIONS CSRF header violation(s)."
    echo "   Add -H 'X-Requested-With: XMLHttpRequest' to mutating /rest/ calls."
    exit 1
fi
