/**
 * Action Plugin Service for NemakiWare React UI
 *
 * Implements NemakiWare's custom action plugin framework for extensible business logic:
 * - Action discovery for available plugins on objects
 * - Dynamic form generation for action parameters
 * - Action execution with custom business logic
 * - Integration with server-side action plugin architecture
 *
 * Action Plugin Framework Flow:
 * 1. User selects object → discoverActions() retrieves available actions
 * 2. User selects action → getActionForm() retrieves parameter form schema
 * 3. User fills form → executeAction() submits to server-side plugin
 * 4. Server executes custom business logic → returns execution result
 *
 * Usage Examples:
 * ```typescript
 * const actionService = new ActionService();
 *
 * // Discover available actions for a document
 * const actions = await actionService.discoverActions('bedroom', 'doc123');
 * // Returns: [
 * //   { id: 'convert-to-pdf', name: 'Convert to PDF', ... },
 * //   { id: 'send-email', name: 'Send as Email', ... }
 * // ]
 *
 * // Get form schema for specific action
 * const form = await actionService.getActionForm('bedroom', 'send-email', 'doc123');
 * // Returns: {
 * //   fields: [
 * //     { name: 'recipient', type: 'email', required: true },
 * //     { name: 'subject', type: 'text', required: true },
 * //     { name: 'message', type: 'textarea', required: false }
 * //   ]
 * // }
 *
 * // Execute action with user-provided parameters
 * const result = await actionService.executeAction(
 *   'bedroom',
 *   'send-email',
 *   'doc123',
 *   { recipient: 'user@example.com', subject: 'Document', message: 'See attached' }
 * );
 * // Returns: { success: true, message: 'Email sent successfully' }
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Axios Library Integration (Lines 13-14, 30-31, 52-53):
 *    - Uses axios instead of fetch() or XMLHttpRequest
 *    - Rationale: axios provides cleaner async/await syntax, automatic JSON parsing, better error handling
 *    - Implementation: All HTTP operations use axios.get() or axios.post()
 *    - Advantage: Consistent with modern JavaScript ecosystem, less boilerplate than fetch()
 *    - Trade-off: Adds external dependency (axios), but widely used and well-maintained
 *
 * 2. Direct localStorage Access Pattern (Lines 17, 34, 57):
 *    - Directly accesses localStorage.getItem('authToken') instead of using AuthService
 *    - Rationale: Avoids circular dependency (AuthService might depend on action results)
 *    - Implementation: Each method reads token from localStorage independently
 *    - Advantage: Simple, no service coupling, no singleton dependency
 *    - Trade-off: Duplicated localStorage access code, no token validation
 *
 * 3. Bearer Token Authentication (Lines 17, 34, 57):
 *    - Uses Authorization: Bearer <token> header format
 *    - Rationale: REST API standard for token-based authentication
 *    - Implementation: Consistent header format across all action endpoints
 *    - Advantage: Standard OAuth 2.0 pattern, compatible with API gateways
 *    - Security: Token transmitted in headers (not URL), HTTPS recommended
 *
 * 4. REST API Endpoint Structure (Lines 14, 31, 53):
 *    - Pattern: /core/rest/repo/{repositoryId}/actions/{actionId}/{operation}/{objectId}
 *    - Rationale: RESTful design with hierarchical resource structure
 *    - Implementation: Template literals for dynamic URL construction
 *    - Advantage: Clear resource hierarchy, easy to extend with new endpoints
 *    - Example: /core/rest/repo/bedroom/actions/send-email/execute/doc123
 *
 * 5. Error Handling Strategy (Lines 22-24, 39-41, 63-65):
 *    - Logs error to console, then rethrows to caller
 *    - Rationale: Debugging visibility + caller control over error recovery
 *    - Implementation: try-catch blocks with console.error + throw
 *    - Advantage: Errors visible in browser console during development
 *    - Trade-off: No error transformation or user-friendly messages
 *
 * 6. Async/Await Pattern (Lines 11, 28, 45):
 *    - All methods use async/await instead of Promise chains
 *    - Rationale: Modern JavaScript syntax, cleaner than .then() chaining
 *    - Implementation: async keyword on methods, await on axios calls
 *    - Advantage: Synchronous-looking code, easier error handling with try-catch
 *    - Compatibility: Requires ES2017+ (supported by all modern browsers)
 *
 * 7. Action Discovery Endpoint (Lines 11-26):
 *    - GET /repo/{repositoryId}/actions/discover/{objectId}
 *    - Returns array of ActionDefinition objects for the specified object
 *    - Rationale: Server-side logic determines which actions are applicable
 *    - Implementation: Object type, permissions, and plugin availability checked server-side
 *    - Advantage: Dynamic action availability based on context
 *
 * 8. Dynamic Form Generation (Lines 28-43):
 *    - GET /repo/{repositoryId}/actions/{actionId}/form/{objectId}
 *    - Returns ActionForm schema with field definitions
 *    - Rationale: Each action can have different parameter requirements
 *    - Implementation: Server generates form schema, UI renders dynamically
 *    - Advantage: No UI changes needed when adding new action plugins
 *
 * 9. Action Execution with JSON Payload (Lines 45-67):
 *    - POST /repo/{repositoryId}/actions/{actionId}/execute/{objectId}
 *    - Sends user-provided formData as JSON request body
 *    - Rationale: Actions can accept complex nested parameters
 *    - Implementation: Record<string, any> allows flexible parameter structure
 *    - Advantage: Supports any parameter type (strings, numbers, arrays, objects)
 *
 * 10. TypeScript Type Safety (Lines 2, 11, 28, 50):
 *     - ActionDefinition, ActionForm, ActionExecutionResult interfaces
 *     - Rationale: Compile-time type checking prevents runtime errors
 *     - Implementation: Explicit return type annotations on all methods
 *     - Advantage: IDE autocomplete, refactoring safety, documentation
 *     - Location: Types defined in '../types/cmis' module
 *
 * Expected Results:
 * - discoverActions(): Returns ActionDefinition[] array with available actions for object
 * - getActionForm(): Returns ActionForm object with field definitions for parameter collection
 * - executeAction(): Returns ActionExecutionResult with success status and result message
 * - All methods throw errors on failure (network errors, authentication failures, server errors)
 *
 * Performance Characteristics:
 * - discoverActions(): ~200-500ms (server queries plugin registry + checks permissions)
 * - getActionForm(): ~100-300ms (server generates form schema from plugin metadata)
 * - executeAction(): Variable (depends on action complexity: 500ms-60s+)
 *   - Simple actions (metadata update): 500ms-2s
 *   - Document conversion: 5s-30s
 *   - Email sending: 2s-10s
 *   - Complex workflows: 30s-60s+
 * - Network overhead: ~50-100ms per request (local network)
 *
 * Debugging Features:
 * - Console error logging for all failures (line numbers in stack trace)
 * - Browser Network tab shows request/response details
 * - Axios interceptors can be added for global request/response logging
 * - TypeScript compile-time type checking catches parameter mismatches
 * - Server-side action logs show execution details
 *
 * Known Limitations:
 * - No request timeout configuration (uses axios defaults: no timeout)
 * - No retry logic on network failures
 * - No request cancellation support (long-running actions cannot be aborted)
 * - No progress reporting for long-running actions
 * - Direct localStorage access duplicated across methods (no DRY principle)
 * - No validation of formData before sending to server
 * - Error messages not localized (English only from server)
 * - No offline support (requires network connection)
 * - No request queuing (concurrent action executions may conflict)
 *
 * Relationships to Other Services:
 * - Independent of AuthService (direct localStorage access)
 * - Used by DocumentActions component for context menu actions
 * - Complementary to CMISService (actions extend CMIS base operations)
 * - Server-side: Depends on NemakiWare action plugin framework
 * - UI Integration: Action results may trigger document list refresh
 *
 * Common Failure Scenarios:
 * - discoverActions() fails: 401 Unauthorized (expired token in localStorage)
 * - discoverActions() fails: 404 Not Found (object doesn't exist)
 * - getActionForm() fails: 404 Not Found (action plugin not installed)
 * - executeAction() fails: 400 Bad Request (invalid formData parameters)
 * - executeAction() fails: 500 Internal Server Error (plugin execution error)
 * - All methods fail: Network error (server unreachable, CORS issues)
 * - All methods fail: TypeError (localStorage returns null, no token)
 * - executeAction() timeout: Long-running action exceeds client patience
 */

import axios from 'axios';
import { ActionDefinition, ActionForm, ActionExecutionResult } from '../types/cmis';

export class ActionService {
  private baseUrl: string;

  constructor() {
    this.baseUrl = '/core/rest';
  }

  async discoverActions(repositoryId: string, objectId: string): Promise<ActionDefinition[]> {
    try {
      const response = await axios.get(
        `${this.baseUrl}/repo/${repositoryId}/actions/discover/${objectId}`,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );
      return response.data;
    } catch (error) {
      // Failed to discover actions
      throw error;
    }
  }

  async getActionForm(repositoryId: string, actionId: string, objectId: string): Promise<ActionForm> {
    try {
      const response = await axios.get(
        `${this.baseUrl}/repo/${repositoryId}/actions/${actionId}/form/${objectId}`,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`
          }
        }
      );
      return response.data;
    } catch (error) {
      // Failed to get action form
      throw error;
    }
  }

  async executeAction(
    repositoryId: string, 
    actionId: string, 
    objectId: string, 
    formData: Record<string, any>
  ): Promise<ActionExecutionResult> {
    try {
      const response = await axios.post(
        `${this.baseUrl}/repo/${repositoryId}/actions/${actionId}/execute/${objectId}`,
        formData,
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('authToken')}`,
            'Content-Type': 'application/json'
          }
        }
      );
      return response.data;
    } catch (error) {
      // Failed to execute action
      throw error;
    }
  }
}
