# NemakiWare SSO Authentication Guide

This document describes how to configure and verify OIDC (OpenID Connect) and SAML 2.0 authentication for NemakiWare.

## Overview

NemakiWare supports three authentication methods:

1. **Basic Authentication** - Traditional username/password login
2. **OIDC (OpenID Connect)** - OAuth 2.0 based authentication with identity providers like Keycloak, Google, Azure AD
3. **SAML 2.0** - Enterprise SSO authentication with identity providers like Keycloak, Okta, ADFS

## Prerequisites

- NemakiWare server running (default: http://localhost:8080)
- Identity Provider (IdP) configured (e.g., Keycloak at http://localhost:8088)
- Docker environment for running the full stack

## Keycloak Setup

### Starting Keycloak

```bash
cd docker
docker-compose -f docker-compose.keycloak.yml up -d
```

Keycloak will be available at http://localhost:8088 with admin credentials `admin/admin`.

### Realm Configuration

The NemakiWare realm is pre-configured in `docker/keycloak/realm-export.json` with:

- Realm name: `nemakiware`
- OIDC client: `nemakiware-oidc-client`
- SAML client: `nemakiware-saml-client`
- Test user: `testuser` / `password`

### OIDC Client Configuration

| Setting | Value |
|---------|-------|
| Client ID | `nemakiware-oidc-client` |
| Client Protocol | `openid-connect` |
| Access Type | `public` |
| Valid Redirect URIs | `http://localhost:8080/core/ui/*` |
| Web Origins | `http://localhost:8080` |

### SAML Client Configuration

| Setting | Value |
|---------|-------|
| Client ID | `nemakiware-saml-client` |
| Client Protocol | `saml` |
| Sign Documents | `ON` |
| Sign Assertions | `ON` |
| Valid Redirect URIs | `http://localhost:8080/core/ui/*` |
| Master SAML Processing URL | `http://localhost:8080/core/ui/saml-callback.html` |

## NemakiWare Configuration

### React UI Configuration

The React UI SSO configuration is located in:

- OIDC: `core/src/main/webapp/ui/src/config/oidc.ts`
- SAML: `core/src/main/webapp/ui/src/config/saml.ts`

#### OIDC Configuration (`oidc.ts`)

```typescript
export const getOIDCConfig = (): OIDCConfig => {
  return {
    authority: 'http://localhost:8088/realms/nemakiware',
    client_id: 'nemakiware-oidc-client',
    redirect_uri: `${window.location.origin}/core/ui/`,
    post_logout_redirect_uri: `${window.location.origin}/core/ui/`,
    response_type: 'code',
    scope: 'openid profile email'
  };
};
```

#### SAML Configuration (`saml.ts`)

```typescript
export const getSAMLConfig = (): SAMLConfig => {
  return {
    sso_url: 'http://localhost:8088/realms/nemakiware/protocol/saml',
    entity_id: 'nemakiware-saml-client',
    callback_url: `${window.location.origin}/core/ui/saml-callback.html`,
    logout_url: `${window.location.origin}/core/ui/`
  };
};
```

### Server-Side Endpoints

NemakiWare provides REST endpoints for SSO token conversion:

#### OIDC Token Conversion

```
POST /core/rest/repo/{repositoryId}/authtoken/oidc/convert
Content-Type: application/json

{
  "oidc_token": "access_token_from_oidc_provider",
  "id_token": "id_token_from_oidc_provider",
  "user_info": {
    "preferred_username": "username",
    "email": "user@example.com",
    "sub": "subject_id"
  }
}
```

Response:
```json
{
  "status": "success",
  "value": {
    "userName": "username",
    "token": "nemakiware_session_token",
    "repositoryId": "bedroom",
    "expiration": 1234567890
  }
}
```

#### SAML Token Conversion

```
POST /core/rest/repo/{repositoryId}/authtoken/saml/convert
Content-Type: application/json

{
  "saml_response": "base64_encoded_saml_response",
  "relay_state": "repositoryId=bedroom"
}
```

Response:
```json
{
  "status": "success",
  "value": {
    "userName": "username",
    "token": "nemakiware_session_token",
    "repositoryId": "bedroom",
    "expiration": 1234567890
  }
}
```

## Authentication Flow

### OIDC Flow

1. User clicks "OIDC" button on login page
2. Browser redirects to Keycloak authorization endpoint
3. User authenticates at Keycloak
4. Keycloak redirects back to NemakiWare with authorization code
5. React UI exchanges code for tokens via oidc-client-ts library
6. React UI calls `/authtoken/oidc/convert` to get NemakiWare session token
7. User is logged in to NemakiWare

### SAML Flow

1. User clicks "SAML" button on login page
2. Browser redirects to Keycloak SAML SSO endpoint with AuthnRequest
3. User authenticates at Keycloak
4. Keycloak posts SAML Response to NemakiWare callback URL
5. React UI extracts SAML Response and calls `/authtoken/saml/convert`
6. Server validates SAML Response and returns NemakiWare session token
7. User is logged in to NemakiWare

## Verification

### Manual Verification

1. Start the full Docker stack:
   ```bash
   cd docker
   docker-compose -f docker-compose-simple.yml up -d
   docker-compose -f docker-compose.keycloak.yml up -d
   ```

2. Open NemakiWare UI: http://localhost:8080/core/ui/

3. Click "OIDC" or "SAML" button

4. Authenticate with Keycloak using `testuser` / `password`

5. Verify redirect back to NemakiWare and successful login

### API Verification

Test OIDC token conversion:
```bash
curl -X POST "http://localhost:8080/core/rest/repo/bedroom/authtoken/oidc/convert" \
  -H "Content-Type: application/json" \
  -d '{"user_info": {"preferred_username": "testuser", "email": "test@example.com"}}'
```

Test SAML token conversion:
```bash
SAML_RESPONSE=$(echo '<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"><saml:Assertion><saml:NameID>testuser</saml:NameID></saml:Assertion></samlp:Response>' | base64)

curl -X POST "http://localhost:8080/core/rest/repo/bedroom/authtoken/saml/convert" \
  -H "Content-Type: application/json" \
  -d "{\"saml_response\": \"$SAML_RESPONSE\"}"
```

### Playwright E2E Tests

Run SSO authentication tests:
```bash
cd core/src/main/webapp/ui
npx playwright test tests/auth/oidc-login.spec.ts
npx playwright test tests/auth/saml-login.spec.ts
```

## Troubleshooting

### OIDC Issues

**Problem**: "Invalid redirect_uri" error from Keycloak

**Solution**: Ensure the redirect URI in NemakiWare matches the Valid Redirect URIs configured in Keycloak client settings.

**Problem**: Token conversion fails with 401 Unauthorized

**Solution**: The SSO endpoints bypass authentication. Check that the AuthenticationFilter is correctly configured to allow `/authtoken/saml/convert` and `/authtoken/oidc/convert` paths.

### SAML Issues

**Problem**: "Invalid Request" error from Keycloak

**Solution**: Ensure the SAML AuthnRequest is properly DEFLATE compressed and Base64 encoded. The `pako` library is used for DEFLATE compression.

**Problem**: Username not extracted from SAML Response

**Solution**: Check that the SAML Response contains either:
- `<saml:NameID>` element
- `<saml:Attribute Name="email">` element
- `<saml:Attribute Name="preferred_username">` element

### Common Issues

**Problem**: SSO buttons not visible on login page

**Solution**: Rebuild the React UI:
```bash
cd core/src/main/webapp/ui
npm run build
```

**Problem**: Keycloak not accessible

**Solution**: Ensure Keycloak container is running and the realm is imported:
```bash
docker logs keycloak
docker-compose -f docker-compose.keycloak.yml restart
```

## Security Considerations

1. **HTTPS in Production**: Always use HTTPS for SSO in production environments
2. **Token Expiration**: NemakiWare tokens expire based on `auth.token.expiration` property
3. **User Provisioning**: SSO users must exist in NemakiWare or auto-provisioning must be enabled
4. **SAML Signature Validation**: Production deployments should validate SAML signatures

## Related Files

- `core/src/main/webapp/ui/src/services/oidc.ts` - OIDC service implementation
- `core/src/main/webapp/ui/src/services/saml.ts` - SAML service implementation
- `core/src/main/webapp/ui/src/config/oidc.ts` - OIDC configuration
- `core/src/main/webapp/ui/src/config/saml.ts` - SAML configuration
- `core/src/main/java/jp/aegif/nemaki/rest/AuthTokenResource.java` - Token conversion endpoints
- `core/src/main/java/jp/aegif/nemaki/rest/AuthenticationFilter.java` - Authentication filter
- `docker/keycloak/realm-export.json` - Keycloak realm configuration
- `core/src/main/webapp/ui/tests/auth/oidc-login.spec.ts` - OIDC E2E tests
- `core/src/main/webapp/ui/tests/auth/saml-login.spec.ts` - SAML E2E tests
