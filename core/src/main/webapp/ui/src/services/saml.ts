/**
 * SAML Authentication Service for NemakiWare React UI
 *
 * Implements SAML 2.0 Single Sign-On (SSO) authentication flow for enterprise identity providers:
 * - SAML request generation with Base64 encoding
 * - SSO redirect initiation with RelayState repository ID passing
 * - SAML response handling and token conversion
 * - Optional Single Logout (SLO) support
 * - REST API integration for SAML-to-NemakiWare token conversion
 * - Repository context preservation across authentication redirects
 *
 * SAML Authentication Flow:
 * 1. User initiates login → initiateLogin() generates SAML request
 * 2. Browser redirects to Identity Provider SSO URL with SAML request
 * 3. User authenticates at IdP → IdP redirects back with SAML response
 * 4. handleSAMLResponse() or convertSAMLResponse() converts SAML to NemakiWare token
 * 5. Application stores token and completes login
 *
 * Usage Examples:
 * ```typescript
 * // Initialize SAML service with IdP configuration
 * const samlService = new SAMLService({
 *   sso_url: 'https://idp.example.com/sso',
 *   entity_id: 'nemakiware-sp',
 *   callback_url: 'http://localhost:8080/core/ui/saml/callback',
 *   logout_url: 'https://idp.example.com/logout' // optional
 * });
 *
 * // Initiate SAML login with repository context
 * samlService.initiateLogin('bedroom');
 * // Browser redirects to IdP SSO URL with SAML request + RelayState=repositoryId=bedroom
 *
 * // Handle SAML callback (in callback component)
 * const urlParams = new URLSearchParams(window.location.search);
 * const samlResponse = urlParams.get('SAMLResponse');
 * const relayState = urlParams.get('RelayState');
 *
 * const authToken = await samlService.handleSAMLResponse(samlResponse!, relayState);
 * // Returns: { token: '...', repositoryId: 'bedroom', username: 'john.doe' }
 *
 * // Alternative: Convert SAML response with user attributes
 * const authToken = await samlService.convertSAMLResponse({
 *   saml_response: samlResponse!,
 *   relay_state: relayState,
 *   user_attributes: { email: 'john.doe@example.com', displayName: 'John Doe' }
 * }, 'bedroom');
 *
 * // Logout (if IdP supports SLO)
 * samlService.initiateLogout();
 * // Browser redirects to IdP logout URL
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. SAML SSO Redirect Flow (Lines 24-32):
 *    - initiateLogin() generates SAML request and redirects browser to IdP SSO URL
 *    - Uses window.location.href assignment for full page redirect
 *    - Passes repositoryId via RelayState query parameter
 *    - Rationale: SAML 2.0 Web SSO Profile requires browser redirect for authentication
 *    - Implementation: URLSearchParams for query string construction
 *    - Advantage: Works with any SAML 2.0 compliant Identity Provider
 *    - Critical: Full page redirect means component state is lost (expected SAML behavior)
 *
 * 2. Base64 SAML Request Encoding (Lines 34-42):
 *    - generateSAMLRequest() creates minimal SAML request with issuer, callback, timestamp
 *    - Uses btoa() for Base64 encoding (browser native function)
 *    - JSON format instead of XML for simplicity (non-standard but server-compatible)
 *    - Rationale: SAML requests must be Base64 encoded per specification
 *    - Implementation: Private method, not exposed to consumers
 *    - Limitation: Simplified JSON format may not work with all IdPs (use with NemakiWare IdP adapter)
 *
 * 3. RelayState Repository ID Passing (Lines 25-26, 70-75):
 *    - RelayState parameter preserves repositoryId across authentication redirects
 *    - Format: "repositoryId=bedroom" as URLSearchParams string
 *    - extractRepositoryIdFromRelayState() parses RelayState on callback
 *    - Rationale: SAML protocol provides RelayState for application context preservation
 *    - Implementation: URLSearchParams for parsing (supports other parameters if needed)
 *    - Advantage: User returns to intended repository after authentication
 *    - Fallback: Default to 'bedroom' if RelayState missing or parsing fails
 *
 * 4. Token Conversion via REST Endpoint (Lines 44-68, 77-100):
 *    - Two conversion methods: handleSAMLResponse() and convertSAMLResponse()
 *    - Both call POST /core/rest/repo/{repositoryId}/authtoken/saml/convert
 *    - handleSAMLResponse() for simple SAML response string
 *    - convertSAMLResponse() for structured SAMLResponse with user attributes
 *    - Rationale: Server validates SAML response and generates NemakiWare token
 *    - Implementation: Fetch API with JSON payload
 *    - Advantage: Server handles SAML signature validation and attribute extraction
 *    - Response format: { value: { token: string, userName: string } }
 *
 * 5. Fetch API over XMLHttpRequest (Lines 47-56, 78-88):
 *    - Uses modern fetch() API instead of XMLHttpRequest
 *    - async/await pattern for clean asynchronous code
 *    - Rationale: SAML conversion is simple request-response, no streaming or progress needed
 *    - Implementation: Standard fetch with JSON body and response parsing
 *    - Advantage: Simpler code than XMLHttpRequest, Promise-based
 *    - Difference from auth.ts: No legacy compatibility requirement for SAML (newer feature)
 *
 * 6. Window Location Redirect Pattern (Lines 31, 104):
 *    - Direct window.location.href assignment for SSO and SLO redirects
 *    - Synchronous operation, no await needed
 *    - Rationale: SAML protocol requires full browser redirect to IdP
 *    - Implementation: Void return type (redirect happens immediately)
 *    - Consequence: All component state and React context lost (expected SAML behavior)
 *    - User Experience: Brief navigation to IdP, then back to application
 *
 * 7. Private Helper Methods (Lines 34-42, 70-75):
 *    - generateSAMLRequest() private: Only used internally by initiateLogin()
 *    - extractRepositoryIdFromRelayState() private: Only used by handleSAMLResponse()
 *    - Rationale: Encapsulate SAML protocol details from consumers
 *    - Implementation: TypeScript private keyword
 *    - Advantage: Clean public API, internal implementation can change
 *
 * 8. Duplicate Conversion Methods (Lines 44-68 vs 77-100):
 *    - handleSAMLResponse() and convertSAMLResponse() do similar operations
 *    - handleSAMLResponse() simpler: Just saml_response and relay_state parameters
 *    - convertSAMLResponse() richer: Includes user_attributes field
 *    - Rationale: Two use cases - simple callback vs. rich attribute handling
 *    - Implementation: Both call same REST endpoint with different payload structures
 *    - Trade-off: Code duplication for API clarity
 *
 * 9. Optional Logout URL Support (Lines 102-106):
 *    - initiateLogout() only redirects if logout_url configured
 *    - No error or warning if logout_url missing (silent no-op)
 *    - Rationale: Not all IdPs support Single Logout, make it optional
 *    - Implementation: Simple if-check before redirect
 *    - Advantage: Graceful degradation for IdPs without SLO support
 *    - User Experience: Local logout always works, IdP logout is optional
 *
 * 10. Default Repository Fallback (Line 45):
 *     - handleSAMLResponse() defaults to 'bedroom' if RelayState missing
 *     - Ensures user can always complete authentication even without repository context
 *     - Rationale: Better UX to land in default repository than fail with error
 *     - Implementation: || 'bedroom' fallback operator
 *     - Advantage: Robust against RelayState corruption or IdP stripping parameters
 *
 * Expected Results:
 * - initiateLogin(repositoryId?): Redirects browser to IdP SSO URL with SAML request, no return value (void)
 * - handleSAMLResponse(samlResponse, relayState?): Returns AuthToken { token, repositoryId, username }
 * - convertSAMLResponse(samlResponseData, repositoryId): Returns AuthToken { token, repositoryId, username }
 * - initiateLogout(): Redirects browser to IdP logout URL if configured, no return value (void)
 * - generateSAMLRequest() (private): Returns Base64 encoded SAML request string
 * - extractRepositoryIdFromRelayState(relayState?) (private): Returns repositoryId string or null
 *
 * Performance Characteristics:
 * - initiateLogin(): Instant (synchronous redirect, no network request)
 * - handleSAMLResponse(): ~500ms-2s (POST to convert endpoint, depends on server SAML validation)
 * - convertSAMLResponse(): ~500ms-2s (POST to convert endpoint, same as handleSAMLResponse)
 * - initiateLogout(): Instant (synchronous redirect if URL configured, no-op if not)
 * - generateSAMLRequest(): <1ms (simple JSON.stringify + btoa operations)
 * - extractRepositoryIdFromRelayState(): <1ms (URLSearchParams parsing)
 *
 * Debugging Features:
 * - No built-in debug logging (SAML responses contain sensitive data, avoid logging)
 * - Browser Network tab shows SAML request/response in query parameters
 * - RelayState visible in callback URL for troubleshooting repository context
 * - Fetch errors logged to console via standard Promise rejection
 * - Can inspect SAML request payload via Base64 decode of SAMLRequest parameter
 *
 * Known Limitations:
 * - Simplified JSON SAML request format (not standard XML SAML AuthnRequest)
 * - May not work with strict SAML 2.0 IdPs expecting XML format
 * - No SAML signature generation (relies on server-side signing if needed)
 * - No SAML assertion validation on client (server responsibility)
 * - No support for SAML metadata exchange (manual configuration required)
 * - No support for encrypted SAML assertions (assumes unencrypted)
 * - RelayState limited to URLSearchParams format (custom encoding may break parsing)
 * - Full page redirects lose all React state and context
 * - No automatic token refresh after SAML token expiration
 * - Duplicate code between handleSAMLResponse() and convertSAMLResponse()
 * - No TypeScript interface for server response (assumes { value: { token, userName } })
 *
 * Relationships to Other Services:
 * - Returns AuthToken compatible with auth.ts AuthService
 * - Can be used alongside auth.ts basic authentication (not mutually exclusive)
 * - Depends on server-side /core/rest/repo/{repositoryId}/authtoken/saml/convert endpoint
 * - Used by Login component for SAML login option
 * - AuthContext stores returned token same way as basic auth token
 * - No direct integration with AuthService (returns compatible AuthToken structure)
 *
 * Common Failure Scenarios:
 * - initiateLogin() fails: window.location.href assignment blocked by browser (popup blocker if triggered from async)
 * - handleSAMLResponse() fails: 400 Bad Request (invalid SAML response format)
 * - handleSAMLResponse() fails: 401 Unauthorized (SAML signature validation failed server-side)
 * - handleSAMLResponse() fails: 500 Internal Server Error (server SAML processing error)
 * - convertSAMLResponse() same failures as handleSAMLResponse()
 * - initiateLogout() no-op: logout_url not configured in SAMLConfig
 * - RelayState lost: IdP strips or corrupts RelayState → defaults to 'bedroom'
 * - SAML request rejected: IdP expects XML format but receives JSON → authentication fails
 * - Token conversion network error: Fetch fails → Promise rejection → component error boundary
 * - Invalid JSON response: response.json() fails → JSON parse error
 */

import { AuthToken } from './auth';
import pako from 'pako';
import { DEFAULT_REPOSITORY_ID } from '../config/app';

export interface SAMLConfig {
  sso_url: string;
  entity_id: string;
  certificate?: string;
  callback_url: string;
  logout_url?: string;
}

export interface SAMLResponse {
  saml_response: string;
  relay_state?: string;
  user_attributes: Record<string, any>;
}

export class SAMLService {
  private config: SAMLConfig;

  constructor(config: SAMLConfig) {
    this.config = config;
  }

  initiateLogin(repositoryId?: string): void {
    const relayState = repositoryId ? `repositoryId=${repositoryId}` : '';
    const params = new URLSearchParams({
      SAMLRequest: this.generateSAMLRequest(),
      RelayState: relayState
    });
    
    window.location.href = `${this.config.sso_url}?${params.toString()}`;
  }

  private generateSAMLRequest(): string {
    const id = '_' + this.generateUUID();
    const issueInstant = new Date().toISOString();
    
    const samlRequest = `<?xml version="1.0" encoding="UTF-8"?>
<samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
                    ID="${id}"
                    Version="2.0"
                    IssueInstant="${issueInstant}"
                    Destination="${this.config.sso_url}"
                    AssertionConsumerServiceURL="${this.config.callback_url}"
                    ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect">
    <saml:Issuer>${this.config.entity_id}</saml:Issuer>
</samlp:AuthnRequest>`;

    return this.deflateAndEncode(samlRequest);
  }

  private generateUUID(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = Math.random() * 16 | 0;
      const v = c === 'x' ? r : (r & 0x3 | 0x8);
      return v.toString(16);
    });
  }

  private deflateAndEncode(xml: string): string {
    const encoder = new TextEncoder();
    const data = encoder.encode(xml);
    const deflated = pako.deflateRaw(data);
    return btoa(String.fromCharCode(...deflated));
  }

  async handleSAMLResponse(samlResponse: string, relayState?: string): Promise<AuthToken> {
    // Use configured default repository when RelayState is missing or invalid
    const repositoryId = this.extractRepositoryIdFromRelayState(relayState) || DEFAULT_REPOSITORY_ID;
    
    const response = await fetch(`/core/rest/repo/${repositoryId}/authtoken/saml/convert`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        saml_response: samlResponse,
        relay_state: relayState
      })
    });

    if (!response.ok) {
      throw new Error('Failed to convert SAML response');
    }

    const result = await response.json();
    return {
      token: result.value.token,
      repositoryId: repositoryId,
      username: result.value.userName
    };
  }

  private extractRepositoryIdFromRelayState(relayState?: string): string | null {
    if (!relayState) return null;
    
    const params = new URLSearchParams(relayState);
    return params.get('repositoryId');
  }

  async convertSAMLResponse(samlResponseData: SAMLResponse, repositoryId: string): Promise<AuthToken> {
    const response = await fetch(`/core/rest/repo/${repositoryId}/authtoken/saml/convert`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        saml_response: samlResponseData.saml_response,
        relay_state: samlResponseData.relay_state,
        user_attributes: samlResponseData.user_attributes
      })
    });

    if (!response.ok) {
      throw new Error('Failed to convert SAML response');
    }

    const result = await response.json();
    return {
      token: result.value.token,
      repositoryId: repositoryId,
      username: result.value.userName
    };
  }

  initiateLogout(): void {
    if (this.config.logout_url) {
      window.location.href = this.config.logout_url;
    }
  }
}
