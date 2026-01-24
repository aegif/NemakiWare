#!/bin/bash
#
# Setup MCP Service Account
#
# Creates a dedicated service account for MCP transport-level authentication.
# This account has NO admin privileges - user permissions are controlled
# via sessionToken obtained from nemakiware_login.
#
# Usage: ./setup-mcp-service-account.sh [NEMAKIWARE_URL] [ADMIN_USER] [ADMIN_PASSWORD]
#

set -e

NEMAKIWARE_URL="${1:-http://localhost:8080/core}"
ADMIN_USER="${2:-admin}"
ADMIN_PASSWORD="${3:-admin}"

MCP_SERVICE_USER="mcp-service"
MCP_SERVICE_PASSWORD="mcp-secure-token-2026"

echo "=== NemakiWare MCP Service Account Setup ==="
echo "NemakiWare URL: $NEMAKIWARE_URL"
echo ""

# Check if Python3 and bcrypt are available
if ! command -v python3 &> /dev/null; then
    echo "Error: python3 is required"
    exit 1
fi

# Generate bcrypt hash
echo "Generating password hash..."
PASSWORD_HASH=$(python3 -c "
import bcrypt
print(bcrypt.hashpw(b'$MCP_SERVICE_PASSWORD', bcrypt.gensalt(rounds=10, prefix=b'2a')).decode())
")

# Check if user already exists
echo "Checking if mcp-service account exists..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -u "$ADMIN_USER:$ADMIN_PASSWORD" \
    "$NEMAKIWARE_URL/api/v1/cmis/repositories/bedroom/users/$MCP_SERVICE_USER")

if [ "$HTTP_STATUS" = "200" ]; then
    echo "mcp-service account already exists. Updating password..."
    curl -s -u "$ADMIN_USER:$ADMIN_PASSWORD" \
        -X PUT \
        -H "Content-Type: application/json" \
        -d "{\"password\":\"$PASSWORD_HASH\"}" \
        "$NEMAKIWARE_URL/api/v1/cmis/repositories/bedroom/users/$MCP_SERVICE_USER" > /dev/null
    echo "✓ Password updated"
else
    echo "Creating mcp-service account..."
    curl -s -u "$ADMIN_USER:$ADMIN_PASSWORD" \
        -X POST \
        -H "Content-Type: application/json" \
        -d "{
            \"userId\": \"$MCP_SERVICE_USER\",
            \"userName\": \"MCP Service Account\",
            \"password\": \"$PASSWORD_HASH\",
            \"isAdmin\": false
        }" \
        "$NEMAKIWARE_URL/api/v1/cmis/repositories/bedroom/users" > /dev/null
    echo "✓ Account created"
fi

# Generate Base64 auth header
AUTH_BASE64=$(echo -n "$MCP_SERVICE_USER:$MCP_SERVICE_PASSWORD" | base64)

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Claude Desktop configuration (claude_desktop_config.json):"
echo ""
echo "{"
echo "  \"mcpServers\": {"
echo "    \"nemakiware\": {"
echo "      \"url\": \"$NEMAKIWARE_URL/mcp/message\","
echo "      \"headers\": {"
echo "        \"Authorization\": \"Basic $AUTH_BASE64\""
echo "      }"
echo "    }"
echo "  }"
echo "}"
echo ""
echo "Or for mcp-bridge.js, set environment variable:"
echo "  export MCP_AUTH=\"Basic $AUTH_BASE64\""
echo ""
