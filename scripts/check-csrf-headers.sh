#!/bin/bash
#
# CI guard: verify that all shell/Python/Markdown files calling
# /rest/repo/ with mutating HTTP methods include a CSRF header.
#
# Detects both single-line and multi-line curl commands (backslash
# continuations), as well as Python requests.post/put calls.
#
# Usage: scripts/check-csrf-headers.sh
#
# Exit code 0 = no violations found, 1 = violations exist.

set -euo pipefail
cd "$(dirname "$0")/.."

VIOLATIONS=0

echo "=== Checking CSRF header compliance for /rest/repo/ mutating calls ==="

check_file() {
    local file="$1"

    # Skip self, generated dirs, and dev/test tooling. tools/test-env holds
    # demo-seeding / fuzz scripts that post to MCP (/mcp) and webhook-receiver
    # endpoints (CSRF-exempt) and some intentionally omit the header to test
    # rejection — they are not product REST callers, so the guard skips them.
    case "$file" in
        */node_modules/*|*/target/*|*/.git/*|*/check-csrf-headers.sh) return ;;
        */tools/test-env/*) return ;;
    esac

    # Python: check requests.post/put/delete calls that target REST API.
    # CMIS Browser Binding (/browser/) does not go through ResourceBase
    # and needs no CSRF header.  We detect REST API calls by checking
    # whether the URL argument references _get_rest_url or contains /rest/.
    if [[ "$file" == *.py ]]; then
        # Skip files that don't use the REST API at all
        if ! grep -q "_get_rest_url\|/rest/" "$file" 2>/dev/null; then
            return
        fi
        while IFS= read -r match; do
            linenum=$(echo "$match" | cut -d: -f1)
            # Look at call + surrounding lines
            context=$(sed -n "$((linenum > 3 ? linenum - 3 : 1)),$((linenum + 5))p" "$file" 2>/dev/null)
            # Skip if this call targets CMIS browser binding (not REST API)
            if echo "$context" | grep -qi "browser_url\|cmisaction\|browser/"; then
                continue
            fi
            # Check for CSRF header
            if ! echo "$context" | grep -qi "_get_rest_headers\|X-Requested-With\|XMLHttpRequest\|csrf"; then
                echo "  VIOLATION: $file:$linenum"
                echo "    $(sed -n "${linenum}p" "$file")"
                VIOLATIONS=$((VIOLATIONS + 1))
            fi
        done < <(grep -n "requests\.\(post\|put\|delete\)" "$file" 2>/dev/null || true)
        return
    fi

    # Shell/Markdown: join backslash-continued lines, then search
    # This collapses "curl ... \\\n  -X POST \\\n  url" into one logical line.
    local joined
    joined=$(awk '/\\$/ { sub(/\\$/, ""); printf "%s", $0; next } { print }' "$file" 2>/dev/null)

    while IFS= read -r match; do
        linenum=$(echo "$match" | cut -d: -f1)
        content=$(echo "$match" | cut -d: -f2-)

        # Check if CSRF header is present in the logical line
        if ! echo "$content" | grep -qi "X-Requested-With\|Origin:\|\$CSRF\|\${CSRF"; then
            # Map back to approximate original line (awk collapses lines)
            echo "  VIOLATION: $file (near joined-line $linenum)"
            echo "    $(echo "$content" | head -c 120)"
            VIOLATIONS=$((VIOLATIONS + 1))
        fi
    done < <(echo "$joined" | grep -n "curl.*-X \(POST\|PUT\|DELETE\).*rest/" 2>/dev/null || true)
}

while IFS= read -r file; do
    check_file "$file"
done < <(find . \( -name "*.sh" -o -name "*.md" -o -name "*.py" \) -not -path "*/node_modules/*" -not -path "*/target/*" -not -path "*/.git/*" -not -path "*/tools/test-env/*")

echo
if [ "$VIOLATIONS" -eq 0 ]; then
    echo "✅ No CSRF header violations found."
    exit 0
else
    echo "❌ Found $VIOLATIONS CSRF header violation(s)."
    echo "   Add -H 'X-Requested-With: XMLHttpRequest' to mutating /rest/ calls."
    exit 1
fi
