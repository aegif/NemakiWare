#!/bin/bash
#
# CI guard: verify that all active Dockerfiles copy server.xml
# (required for RemoteIpValve CSRF configuration).
#
# Usage: scripts/check-dockerfile-parity.sh

set -euo pipefail
cd "$(dirname "$0")/.."

VIOLATIONS=0

echo "=== Checking Dockerfile server.xml parity ==="

# Find all Dockerfiles referenced by compose files
for compose in docker/docker-compose*.yml; do
    while IFS= read -r dockerfile; do
        fullpath="docker/core/$dockerfile"
        if [ ! -f "$fullpath" ]; then
            continue
        fi
        if ! grep -q "server\.xml" "$fullpath"; then
            echo "  VIOLATION: $fullpath does not COPY server.xml (used by $compose)"
            VIOLATIONS=$((VIOLATIONS + 1))
        fi
    done < <(grep "dockerfile:" "$compose" 2>/dev/null | sed 's/.*dockerfile: *//' | tr -d ' ')
done

echo
if [ "$VIOLATIONS" -eq 0 ]; then
    echo "✅ All active Dockerfiles copy server.xml."
    exit 0
else
    echo "❌ Found $VIOLATIONS Dockerfile(s) missing server.xml."
    echo "   Add: COPY server.xml /usr/local/tomcat/conf/server.xml"
    exit 1
fi
