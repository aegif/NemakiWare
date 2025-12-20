/**
 * CMIS Browser Binding Service for NemakiWare React UI
 *
 * Comprehensive CMIS 1.1 implementation providing complete document repository operations:
 * - CMIS Browser Binding + AtomPub Binding hybrid approach for maximum compatibility
 * - Complete CRUD operations for documents, folders, and objects
 * - Versioning operations: check-out, check-in, cancel, version history
 * - Access Control List (ACL) management with permission inheritance
 * - User and Group management with REST API integration
 * - Type definition operations: create, update, delete custom types
 * - Advanced search with CMIS SQL queries
 * - Archive operations with object restoration
 * - Content stream handling with binary data support
 * - Authentication token integration with automatic retry
 * - Property extraction with Browser Binding format compatibility
 * - Comprehensive error handling with auth error callbacks
 *
 * Architecture Design:
 * - Browser Binding (POST): createDocument, createFolder, updateProperties, deleteObject, checkOut, checkIn, setACL
 * - AtomPub Binding (GET/XML): getChildren, getObject, getVersionHistory, getRelationships, getContentStream
 * - REST API (JSON): User management, Group management, Type management, Archives
 * - Hybrid approach: Uses most reliable binding for each operation type
 * - XMLHttpRequest: All requests for consistent error handling and progress monitoring
 * - Property helpers: Flexible extraction supporting both Browser Binding {value:} and legacy formats
 *
 * Usage Examples:
 * ```typescript
 * const cmisService = new CMISService(onAuthError);
 *
 * // Get repositories
 * const repos = await cmisService.getRepositories();
 *
 * // Navigate folders
 * const rootFolder = await cmisService.getRootFolder('bedroom');
 * const children = await cmisService.getChildren('bedroom', rootFolder.id);
 *
 * // Create document
 * const file = new File(['content'], 'test.txt', { type: 'text/plain' });
 * const doc = await cmisService.createDocument('bedroom', rootFolder.id, file, {
 *   'cmis:name': 'test.txt',
 *   'cmis:objectTypeId': 'cmis:document'
 * });
 *
 * // Versioning workflow
 * const pwc = await cmisService.checkOut('bedroom', doc.id);
 * const newFile = new File(['new content'], 'test.txt');
 * const newVersion = await cmisService.checkIn('bedroom', pwc.id, newFile, {
 *   major: true,
 *   checkinComment: 'Updated content'
 * });
 *
 * // ACL management
 * const acl = await cmisService.getACL('bedroom', doc.id);
 * await cmisService.setACL('bedroom', doc.id, {
 *   permissions: [
 *     { principalId: 'admin', permissions: ['cmis:all'], direct: true },
 *     { principalId: 'GROUP_EVERYONE', permissions: ['cmis:read'], direct: true }
 *   ],
 *   isExact: true
 * });
 *
 * // Search with CMIS SQL
 * const results = await cmisService.search('bedroom',
 *   "SELECT * FROM cmis:document WHERE cmis:name LIKE '%test%'");
 *
 * // User management
 * const users = await cmisService.getUsers('bedroom');
 * await cmisService.createUser('bedroom', {
 *   id: 'newuser',
 *   name: 'newuser',
 *   password: 'password',
 *   email: 'newuser@example.com'
 * });
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Browser Binding vs AtomPub Hybrid Strategy (Multiple locations):
 *    - Browser Binding for POST operations: createDocument (678), createFolder (737), updateProperties (789), deleteObject (843)
 *    - AtomPub for GET operations with XML parsing: getChildren (392), getObject (589), getVersionHistory (912), getContentStream (2156)
 *    - Rationale: Browser Binding better for mutations (JSON responses), AtomPub better for queries (richer XML metadata)
 *    - Implementation: Different bindings for different operations, not configurable per-request
 *    - Advantage: Uses strengths of each binding, works around limitations
 *    - Critical: getChildren uses AtomPub exclusively due to Browser Binding empty result issues (Lines 388-391)
 *
 * 2. Safe Property Extraction with Multiple Format Support (Lines 19-96):
 *    - getSafeStringProperty() handles Browser Binding {value: "x"} and legacy "x" formats
 *    - getSafeDateProperty() converts timestamps to ISO strings (Lines 49-71)
 *    - getSafeIntegerProperty() parses both number and string values (Lines 73-96)
 *    - Rationale: CMIS Browser Binding returns properties in object format, legacy code used direct values
 *    - Implementation: Type checking with fallback chains
 *    - Advantage: Compatible with both current and legacy CMIS server responses
 *
 * 3. Authentication Integration with AuthService Singleton (Lines 177-210):
 *    - getAuthHeaders() reads localStorage directly, doesn't use AuthService.getAuthHeaders()
 *    - Returns both Basic auth header + nemaki_auth_token custom header
 *    - Basic auth format: `Basic ${btoa(username:dummy)}` using username from token
 *    - Rationale: Provides username context while using token-based authentication
 *    - Implementation: Reads nemakiware_auth from localStorage, parses JSON
 *    - Comprehensive debug logging: localStorage presence, auth data structure, token length
 *    - Advantage: Works even if AuthService not initialized, detailed troubleshooting logs
 *
 * 4. Authentication Error Handling with Callback Pattern (Lines 212-245):
 *    - handleHttpError() only handles 401/403 as authentication errors
 *    - CRITICAL FIX (2025-10-22): 404 Not Found removed from auth error handling
 *    - Calls onAuthError callback for 401/403, allowing component-level redirect to login
 *    - Rationale: 404 is API failure not auth failure, components should handle 404 differently
 *    - Implementation: Status code filtering, optional callback invocation
 *    - Advantage: Centralized auth error handling, component-level customization
 *
 * 5. Browser Binding Form Data Property Format (Lines 128-141, used throughout):
 *    - appendPropertiesToFormData() uses propertyId[N] and propertyValue[N] array format
 *    - Index-based parameter naming required by CMIS Browser Binding specification
 *    - Example: propertyId[0]=cmis:name, propertyValue[0]=test.txt
 *    - Rationale: CMIS Browser Binding standard requires this exact format
 *    - Implementation: Object.entries() iteration with propertyIndex counter
 *    - Critical: Direct property names like cmis:name=test.txt DO NOT WORK in Browser Binding
 *
 * 6. AtomPub XML Parsing with Namespace Compatibility (Lines 412-543):
 *    - Uses DOMParser for XML parsing with namespace-aware selectors
 *    - Supports both prefixed (atom:entry, cmis:properties) and unprefixed (entry, properties) elements
 *    - getElementsByTagNameNS() for namespace-specific elements
 *    - querySelector() with escaped namespace (cmis\\:value) as fallback
 *    - Rationale: Different CMIS servers use different XML namespace prefixes
 *    - Implementation: Dual selector pattern trying both formats
 *    - CRITICAL FIX (Lines 478-511): Extracts ALL properties from XML, not just hardcoded ones
 *    - Advantage: Works with both NemakiWare and other CMIS 1.1 servers
 *
 * 7. Versioning Operations with Private Working Copy Pattern (Lines 987-1148):
 *    - checkOut() returns PWC (Private Working Copy) object (Lines 987-1030)
 *    - checkIn() accepts PWC objectId + optional file + properties (Lines 1044-1104)
 *    - cancelCheckOut() discards PWC and changes (Lines 1113-1148)
 *    - Major/minor version control via properties.major boolean (default true)
 *    - Rationale: Standard CMIS versioning workflow with PWC pattern
 *    - Implementation: Browser Binding with form-urlencoded for checkOut/cancelCheckOut, multipart for checkIn
 *    - Advantage: Follows CMIS 1.1 specification exactly, interoperable with CMIS clients
 *
 * 8. ACL Management with Remove-Then-Add Strategy (Lines 1220-1283):
 *    - setACL() first gets current ACL, removes all direct ACEs, then adds new ACEs
 *    - Ensures complete ACL replacement rather than incremental addition
 *    - Uses Browser Binding applyACL action with removeACEPrincipal[N] and addACEPrincipal[N]
 *    - Permissions array format: addACEPermission[aceIndex][permIndex]
 *    - Rationale: CMIS applyACL is incremental by default, complete replacement requires explicit remove
 *    - Implementation: Two-step operation with promise chaining
 *    - Advantage: Predictable ACL state, avoids permission accumulation bugs
 *
 * 9. Type Operations with Base + Child Type Hierarchy (Lines 1665-1764):
 *    - getTypes() fetches both base types AND child types recursively
 *    - CRITICAL FIX (2025-10-21): Previous implementation only returned base types (6), missing custom types
 *    - Uses Browser Binding cmisselector=typeChildren with optional typeId parameter
 *    - Combines base types and all child types into single flat array
 *    - Rationale: UI needs complete type list including custom nemaki:* types
 *    - Implementation: Promise.all() for parallel child type fetching
 *    - Advantage: Type Management UI now shows all 10 types instead of just 6 base types
 *
 * 10. Content Stream Operations with Binary Data Support (Lines 2151-2184):
 *     - getContentStream() uses AtomPub binding with arraybuffer response type
 *     - Returns ArrayBuffer for binary data (PDFs, images, etc.)
 *     - getDownloadUrl() generates URL with token parameter for direct browser download
 *     - Rationale: AtomPub provides reliable binary data streaming
 *     - Implementation: xhr.responseType = 'arraybuffer' for binary safety
 *     - Advantage: Supports all content types without encoding issues
 *
 * Expected Results by Operation Category:
 *
 * Repository Operations:
 * - getRepositories(): Returns string array of repository IDs (e.g., ["bedroom", "canopy"])
 * - getRootFolder(): Returns CMISObject with id, name, path="/"
 *
 * Folder/Object Operations:
 * - getChildren(): Returns CMISObject array with complete properties from AtomPub XML
 * - getObject(): Returns single CMISObject with basic metadata
 * - createDocument(): Returns created CMISObject with server-generated id
 * - createFolder(): Returns created CMISObject with path information
 * - updateProperties(): Returns updated CMISObject with new property values
 * - deleteObject(): Returns void, throws error if failed
 *
 * Versioning Operations:
 * - checkOut(): Returns PWC object with cmis:isPrivateWorkingCopy=true
 * - checkIn(): Returns new version object with incremented cmis:versionLabel
 * - cancelCheckOut(): Returns void, PWC deleted
 * - getVersionHistory(): Returns VersionHistory with versions array and latestVersion
 *
 * ACL Operations:
 * - getACL(): Returns ACL object with permissions array and isExact flag
 * - setACL(): Returns void, ACL applied to object
 *
 * User/Group Operations:
 * - getUsers(): Returns User array with id, name, email, groups
 * - createUser(): Returns created User object
 * - updateUser(): Returns updated User object
 * - deleteUser(): Returns void
 * - getGroups(): Returns Group array with id, name, members
 * - createGroup(): Returns created Group object
 * - updateGroup(): Returns updated Group object
 * - deleteGroup(): Returns void
 *
 * Type Operations:
 * - getTypes(): Returns TypeDefinition array (base types + child types)
 * - getType(): Returns single TypeDefinition with property definitions
 * - createType(): Returns created TypeDefinition
 * - updateType(): Returns updated TypeDefinition
 * - deleteType(): Returns void
 *
 * Search/Archive Operations:
 * - search(): Returns SearchResult with objects array, hasMoreItems, numItems
 * - getArchives(): Returns CMISObject array of archived objects
 * - archiveObject(): Returns void
 * - restoreObject(): Returns void
 *
 * Performance Characteristics:
 * - getRepositories(): ~100-500ms (unauthenticated endpoint, fast)
 * - getRootFolder(): ~200-800ms (Browser Binding JSON response)
 * - getChildren(): ~500ms-3s (AtomPub XML parsing, depends on child count)
 * - createDocument(): ~1-5s (multipart upload, depends on file size)
 * - updateProperties(): ~500ms-2s (Browser Binding form POST)
 * - deleteObject(): ~300ms-1s (Browser Binding form POST)
 * - search(): ~1-10s (depends on query complexity and result set size)
 * - checkOut/checkIn(): ~1-3s each (version operations with metadata updates)
 * - getACL/setACL(): ~500ms-2s (ACL parsing and application)
 * - getUsers/getGroups(): ~500ms-3s (REST API with array transformation)
 * - getTypes(): ~2-5s (parallel fetching of base types + child types)
 * - getContentStream(): ~500ms-30s (depends on file size, network speed)
 *
 * Error Handling Features:
 * - Error logging for failed CMIS operations and authentication issues
 * - Request/response error logging for troubleshooting server issues
 * - XML parsing error detection with parsererror element checking
 * - Property extraction logging showing raw vs transformed data
 * - User/Group data transformation logging with before/after comparison
 *
 * Known Limitations:
 * - AtomPub XML parsing may fail on malformed server responses
 * - Browser Binding requires exact propertyId[N]/propertyValue[N] format, no alternative accepted
 * - getChildren limited to AtomPub due to Browser Binding empty result issues
 * - Content stream operations don't support progress callbacks for large files
 * - Type operations limited to REST API endpoints, not available in Browser/AtomPub bindings
 * - Archive operations endpoints may vary by NemakiWare version
 * - Relationship operations incomplete (sourceId/targetId extraction not implemented)
 * - No automatic retry for failed operations (except auth errors)
 * - XMLHttpRequest instead of modern fetch API (consistent with AuthService)
 * - No request cancellation support
 * - No batch operation support (all operations individual)
 * - getTypes() may be slow with many custom types (parallel fetching helps)
 *
 * Relationships to Other Services:
 * - Uses AuthService.getInstance() for authentication token retrieval
 * - AuthContext listens for auth errors via onAuthError callback
 * - All React components use CMISService for CMIS operations
 * - DocumentList component calls getChildren() for folder navigation
 * - DocumentUpload component calls createDocument() for file uploads
 * - UserManagement component calls getUsers()/createUser()/updateUser()/deleteUser()
 * - GroupManagement component calls getGroups()/createGroup()/updateGroup()/deleteGroup()
 * - TypeManagement component calls getTypes()/getType()/deleteType()
 * - VersionHistory component calls getVersionHistory()/checkOut()/checkIn()
 * - ACLManagement component calls getACL()/setACL()
 *
 * Common Failure Scenarios:
 * - getRepositories() fails: Network error or server not running (resolve: [])
 * - getRootFolder() fails: Authentication required (fallback folder provided)
 * - getChildren() fails: Invalid folderId or permission denied (reject with error)
 * - createDocument() fails: Invalid properties or missing required fields (reject with error)
 * - updateProperties() fails: Object locked by checkout (reject with error)
 * - deleteObject() fails: Object has children (folders) or is checked out (reject with error)
 * - checkOut() fails: Object already checked out by another user (reject with error)
 * - checkIn() fails: PWC not found or content validation failed (reject with error)
 * - getACL() fails: Object not found or permission denied (reject with error)
 * - setACL() fails: Invalid principal or permission name (reject with error)
 * - getUsers() fails: Server error or JSON parse error (reject with error)
 * - createUser() fails: User already exists or missing required fields (reject with error)
 * - getTypes() fails: Browser Binding typeChildren not supported (reject with error)
 * - search() fails: Invalid CMIS SQL syntax (reject with error)
 * - getContentStream() fails: Content not available or permission denied (reject with error)
 */

import { AuthService } from './auth';
import { getCmisAuthHeaders } from './auth/CmisAuthHeaderProvider';
import { CmisHttpClient } from './http';
import { AtomPubClient } from './clients';
import { ParsedAtomEntry } from './parsers';
import { CMISObject, SearchResult, VersionHistory, Relationship, TypeDefinition, PropertyDefinition, User, Group, ACL, AllowableActions } from '../types/cmis';
import { CompatibleType, MigrationPropertyDefinition, MigrationPropertyType } from '../types/typeMigration';

/**
 * Convert ParsedAtomEntry to CMISObject
 * 
 * This helper function bridges the new parser output format to the existing CMISObject interface.
 * It converts the allowableActions array to an object with boolean properties.
 */
function convertParsedEntryToCmisObject(entry: ParsedAtomEntry): CMISObject {
  const props = entry.properties;
  
  // Convert allowableActions array to object with boolean properties
  const allowableActions: AllowableActions = {};
  for (const action of entry.allowableActions) {
    (allowableActions as Record<string, boolean>)[action] = true;
  }
  
  // Extract common properties
  const id = String(props['cmis:objectId'] || entry.atomId.split('/').pop() || '');
  const name = String(props['cmis:name'] || entry.atomTitle || 'Unknown');
  const objectType = String(props['cmis:objectTypeId'] || 'cmis:document');
  const baseTypeId = String(props['cmis:baseTypeId'] || '');
  const baseType = baseTypeId || (objectType.includes('folder') ? 'cmis:folder' : 'cmis:document');
  
  return {
    id,
    name,
    objectType,
    baseType,
    properties: props,
    allowableActions: Object.keys(allowableActions).length > 0 ? allowableActions : undefined,
    path: props['cmis:path'] as string | undefined,
    createdBy: props['cmis:createdBy'] as string | undefined,
    lastModifiedBy: props['cmis:lastModifiedBy'] as string | undefined,
    creationDate: props['cmis:creationDate'] as string | undefined,
    lastModificationDate: props['cmis:lastModificationDate'] as string | undefined,
    contentStreamLength: typeof props['cmis:contentStreamLength'] === 'number' 
      ? props['cmis:contentStreamLength'] 
      : undefined,
    contentStreamMimeType: props['cmis:contentStreamMimeType'] as string | undefined,
    versionLabel: props['cmis:versionLabel'] as string | undefined,
    isLatestVersion: props['cmis:isLatestVersion'] as boolean | undefined,
    isLatestMajorVersion: props['cmis:isLatestMajorVersion'] as boolean | undefined
  };
}

const MIGRATION_PROPERTY_TYPES: MigrationPropertyType[] = [
  'string',
  'integer',
  'decimal',
  'boolean',
  'datetime',
  'id',
  'html',
  'uri'
];

export class CMISService {
  private baseUrl = '/core/browser';
  private restBaseUrl = '/core/rest/repo';  // REST API for type management operations
  private authService: AuthService;
  private httpClient: CmisHttpClient;
  private atomPubClient: AtomPubClient;
  private onAuthError?: (error: any) => void;

  constructor(onAuthError?: (error: any) => void) {
    this.authService = AuthService.getInstance();
    this.onAuthError = onAuthError;
    // Initialize HTTP client with auth header provider from dedicated module
    this.httpClient = new CmisHttpClient(getCmisAuthHeaders);
    // Initialize AtomPub client for read operations (getChildren, getObject, query)
    this.atomPubClient = new AtomPubClient(this.httpClient, '/core/atom');
  }

  setAuthErrorHandler(handler: (error: any) => void) {
    this.onAuthError = handler;
  }

  // Helper method to safely extract string values from CMIS properties
  // CMIS properties can be either strings, arrays, or objects with 'value' property (Browser Binding format)
  private getSafeStringProperty(props: Record<string, any>, key: string, defaultValue: string = ''): string {
    const property = props[key];
    
    // Handle Browser Binding format: {id: "cmis:name", value: "actual_value"}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'string') {
        return value;
      } else if (Array.isArray(value) && value.length > 0) {
        return String(value[0]);
      } else if (Array.isArray(value) && value.length === 0) {
        return defaultValue;
      }
      return String(value);
    }
    
    // Handle legacy format: direct string or array values
    if (typeof property === 'string') {
      return property;
    } else if (Array.isArray(property) && property.length > 0) {
      return String(property[0]); // Take first element if array
    } else if (Array.isArray(property) && property.length === 0) {
      return defaultValue; // Empty array returns default
    }
    
    return defaultValue;
  }

  private getSafeDateProperty(props: Record<string, any>, key: string): string {
    const property = props[key];
    
    // Handle Browser Binding format: {id: "cmis:creationDate", value: timestamp_or_date_string}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'number') {
        // Convert timestamp to ISO string
        return new Date(value).toISOString();
      } else if (typeof value === 'string') {
        return value;
      }
    }
    
    // Handle direct value
    if (typeof property === 'number') {
      return new Date(property).toISOString();
    } else if (typeof property === 'string') {
      return property;
    }
    
    return '';
  }

  private getSafeIntegerProperty(props: Record<string, any>, key: string): number | undefined {
    const property = props[key];

    // Handle Browser Binding format: {id: "cmis:contentStreamLength", value: number}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (typeof value === 'number') {
        return value;
      } else if (typeof value === 'string') {
        const parsed = parseInt(value);
        return isNaN(parsed) ? undefined : parsed;
      }
    }

    // Handle direct value
    if (typeof property === 'number') {
      return property;
    } else if (typeof property === 'string') {
      const parsed = parseInt(property);
      return isNaN(parsed) ? undefined : parsed;
    }

    return undefined;
  }

  // Helper method to safely extract array values from CMIS properties
  // Used for multi-value properties like cmis:secondaryObjectTypeIds
  private getSafeArrayProperty(props: Record<string, any>, key: string): string[] {
    const property = props[key];

    // Handle Browser Binding format: {id: "cmis:secondaryObjectTypeIds", value: ["type1", "type2"]}
    if (property && typeof property === 'object' && property.value !== undefined) {
      const value = property.value;
      if (Array.isArray(value)) {
        return value.map(v => String(v));
      } else if (typeof value === 'string' && value !== '') {
        return [value];
      }
      return [];
    }

    // Handle direct value
    if (Array.isArray(property)) {
      return property.map(v => String(v));
    } else if (typeof property === 'string' && property !== '') {
      return [property];
    }

    return [];
  }

  // REMOVED: getSafeBooleanProperty - unused helper method (2025-10-22)

  /**
   * Extract allowableActions from CMIS response data.
   * CMIS 1.1 returns allowableActions as an object with boolean properties.
   * Example: { canGetContentStream: true, canDeleteObject: false, ... }
   *
   * @param allowableActionsData - Raw allowableActions data from CMIS response
   * @returns AllowableActions object with boolean properties
   */
  private extractAllowableActions(allowableActionsData: any): AllowableActions | undefined {
    if (!allowableActionsData) {
      return undefined;
    }

    const data = allowableActionsData.allowableActions ?? allowableActionsData;

    if (typeof data === 'object' && !Array.isArray(data)) {
      // CMIS 1.1 standard: allowableActions is an object with boolean values
      return data as AllowableActions;
    }

    // Fallback: If somehow we receive an array (non-standard), convert to object
    if (Array.isArray(data)) {
      const result: AllowableActions = {};
      data.forEach(action => {
        const key = String(action) as keyof AllowableActions;
        (result as any)[key] = true;
      });
      return result;
    }

    return undefined;
  }

  private encodeObjectIdSegment(objectId?: string): string {
    if (!objectId || objectId === 'root') {
      return 'root';
    }

    return encodeURIComponent(objectId);
  }

  private appendPropertiesToFormData(formData: FormData, properties: Record<string, any>, defaults: Record<string, any> = {}) {
    const merged = { ...defaults, ...properties };
    let propertyIndex = 0;

    Object.entries(merged).forEach(([key, value]) => {
      if (value === undefined || value === null) {
        return;
      }

      formData.append(`propertyId[${propertyIndex}]`, key);

      // CRITICAL FIX (2025-12-11): Handle multi-value properties correctly
      // CMIS Browser Binding expects propertyValue[n][m] format for arrays
      // Without this fix, arrays are converted to "[value1,value2]" string
      if (Array.isArray(value)) {
        value.forEach((v, i) => {
          formData.append(`propertyValue[${propertyIndex}][${i}]`, String(v));
        });
      } else {
        formData.append(`propertyValue[${propertyIndex}]`, String(value));
      }
      propertyIndex++;
    });
  }

  private buildCmisObjectFromBrowserData(data: any): CMISObject {
    const props = data.succinctProperties || data.properties || {};
    
    return {
      id: this.getSafeStringProperty(props, 'cmis:objectId', ''),
      name: this.getSafeStringProperty(props, 'cmis:name', 'Unknown'),
      objectType: this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document'),
      baseType: this.getSafeStringProperty(props, 'cmis:baseTypeId', 'cmis:document'),
      properties: props,
      allowableActions: this.extractAllowableActions(data.allowableActions),
      createdBy: this.getSafeStringProperty(props, 'cmis:createdBy'),
      lastModifiedBy: this.getSafeStringProperty(props, 'cmis:lastModifiedBy'),
      creationDate: this.getSafeDateProperty(props, 'cmis:creationDate'),
      lastModificationDate: this.getSafeDateProperty(props, 'cmis:lastModificationDate'),
      contentStreamLength: this.getSafeIntegerProperty(props, 'cmis:contentStreamLength'),
      path: this.getSafeStringProperty(props, 'cmis:path')
    };
  }

  private buildTypeDefinitionFromBrowserData(rawType: any): TypeDefinition {
    // CRITICAL FIX (2025-11-17): Map CMIS updatability field to boolean updatable field
    // CMIS Server returns "updatability" with values: "readonly", "readwrite", "oncreate", "whencheckedout"
    // PropertyEditor expects boolean "updatable" field to filter editable properties
    const propertyDefinitions: Record<string, PropertyDefinition> = {};

    if (rawType?.propertyDefinitions) {
      Object.entries(rawType.propertyDefinitions).forEach(([propId, rawPropDef]: [string, any]) => {
        // Map CMIS updatability to boolean updatable
        const updatability = rawPropDef.updatability;
        const updatable = updatability === 'readwrite' || updatability === 'whencheckedout';

        // Map CMIS propertyType to our expected format
        const propertyTypeMap: Record<string, PropertyDefinition['propertyType']> = {
          'string': 'string',
          'integer': 'integer',
          'decimal': 'decimal',
          'boolean': 'boolean',
          'datetime': 'datetime',
          'id': 'string',
          'uri': 'string',
          'html': 'string'
        };

        propertyDefinitions[propId] = {
          id: rawPropDef.id || propId,
          displayName: rawPropDef.displayName || rawPropDef.localName || propId,
          description: rawPropDef.description || '',
          propertyType: propertyTypeMap[rawPropDef.propertyType?.toLowerCase()] || 'string',
          cardinality: rawPropDef.cardinality?.toLowerCase() === 'multi' ? 'multi' : 'single',
          required: rawPropDef.required === true,
          queryable: rawPropDef.queryable !== false,
          updatable,
          defaultValue: rawPropDef.defaultValue,
          choices: rawPropDef.choices,
          maxLength: rawPropDef.maxLength,
          minValue: rawPropDef.minValue,
          maxValue: rawPropDef.maxValue
        };
      });
    }

    return {
      id: rawType?.id || 'unknown',
      displayName: rawType?.displayName || rawType?.id || 'Unknown Type',
      description: rawType?.description || `${rawType?.displayName || rawType?.id || 'Unknown'} type definition`,
      baseTypeId: rawType?.baseTypeId || rawType?.baseId || (rawType?.id?.startsWith('cmis:folder') ? 'cmis:folder' : 'cmis:document'),
      parentTypeId: rawType?.parentTypeId || rawType?.parentId,
      creatable: rawType?.creatable !== false,
      fileable: rawType?.fileable !== false,
      queryable: rawType?.queryable !== false,
      deletable: rawType?.typeMutability?.delete !== false && rawType?.deletable !== false,
      propertyDefinitions
    };
  }

  private getAuthHeaders(): Record<string, string> {
    try {
      const authData = localStorage.getItem('nemakiware_auth');

      if (authData) {
        const auth = JSON.parse(authData);

        if (auth.username && auth.token) {
          // Use Basic auth with username to provide username context
          const credentials = btoa(`${auth.username}:dummy`);
          return {
            'Authorization': `Basic ${credentials}`,
            'nemaki_auth_token': String(auth.token)
          };
        }
      }
    } catch (e) {
      // localStorage access failed - return empty headers
    }

    return {};
  }

  private normalizeMigrationPropertyDefinition(rawPropDef: unknown, fallbackId?: string): MigrationPropertyDefinition {
    const prop = (rawPropDef ?? {}) as Record<string, unknown>;
    const propertyTypeCandidate = typeof prop.propertyType === 'string' ? prop.propertyType : undefined;
    const propertyType: MigrationPropertyType = MIGRATION_PROPERTY_TYPES.includes(propertyTypeCandidate as MigrationPropertyType)
      ? (propertyTypeCandidate as MigrationPropertyType)
      : 'string';
    const cardinality = prop.cardinality === 'multi' ? 'multi' : 'single';
    const id = (String(prop.id ?? fallbackId ?? '').trim()) || (fallbackId ?? 'unknown');

    const choices = Array.isArray((prop as Record<string, unknown>).choices)
      ? (prop as { choices: Array<Record<string, unknown>> }).choices.map((choice) => ({
          displayName: String(choice.displayName ?? choice.value ?? ''),
          value: choice.value
        }))
      : undefined;

    return {
      id,
      displayName: String(prop.displayName ?? prop.id ?? id ?? 'Unknown property'),
      description: typeof prop.description === 'string' ? prop.description : undefined,
      propertyType,
      cardinality,
      required: Boolean(prop.required),
      defaultValue: prop.defaultValue,
      choices,
    };
  }

  private handleHttpError(status: number, statusText: string, url: string) {
    const error = {
      status,
      statusText,
      url,
      message: `HTTP ${status}: ${statusText}`
    };

    // CRITICAL FIX (2025-10-22): Only handle authentication errors (401, 403)
    // DO NOT handle 404 Not Found errors - these are not authentication failures
    // 404 errors should be handled by components as normal API failures
    if (status === 401) {
      if (this.onAuthError) {
        this.onAuthError(error);
      }
    } else if (status === 403) {
      if (this.onAuthError) {
        this.onAuthError(error);
      }
    }
    // REMOVED: 404 handling - not an authentication error, should be handled by components

    return error;
  }

  async getRepositories(): Promise<string[]> {
    try {
      // Use unauthenticated endpoint for getting repository list
      // This is needed for the login screen where user hasn't authenticated yet
      const response = await this.httpClient.request({
        method: 'GET',
        url: '/core/rest/all/repositories',
        accept: 'application/json',
        includeAuth: false  // No auth needed for repository list
      });

      if (response.status === 200) {
        try {
          const data = JSON.parse(response.responseText);
          // Extract repository IDs from the response
          if (Array.isArray(data)) {
            const repositoryIds = data.map(repo => repo.id).filter(id => id);
            return repositoryIds;
          } else if (data.repositories) {
            return data.repositories;
          } else {
            return [];
          }
        } catch (e) {
          // Failed to parse response - return empty array
          return [];
        }
      } else {
        // Failed to fetch repositories
        if (response.status === 401) {
          this.handleHttpError(response.status, response.statusText, response.responseURL);
        }
        return [];
      }
    } catch (error) {
      // Network error or exception - return empty array
      return [];
    }
  }


  /**
   * Get root folder of a repository using the new AtomPubClient
   * 
   * MIGRATION NOTE: This method has been migrated to use the new AtomPubClient
   * which provides better separation of concerns and testability.
   * The fallback behavior is preserved: always resolves with a folder, never rejects.
   * 
   * @param repositoryId Repository ID (e.g., 'bedroom')
   * @returns Promise resolving to CMISObject (root folder)
   */
  async getRootFolder(repositoryId: string): Promise<CMISObject> {
    // Fallback folder used when request fails or returns invalid data
    const fallbackFolder: CMISObject = {
      id: 'e02f784f8360a02cc14d1314c10038ff',
      name: 'Root Folder',
      objectType: 'cmis:folder',
      baseType: 'cmis:folder',
      properties: {},
      allowableActions: { canGetChildren: true },
      path: '/'
    };

    try {
      // Use the new AtomPubClient for getRootFolder
      const result = await this.atomPubClient.getRootFolder(repositoryId);

      if (!result.success) {
        // Handle HTTP errors - notify about auth error but still return fallback
        if (result.status === 401 || result.status === 403) {
          this.handleHttpError(result.status, result.error || 'Unauthorized', '');
        }
        return fallbackFolder;
      }

      if (!result.data) {
        return fallbackFolder;
      }

      // Convert ParsedAtomEntry to CMISObject
      const rootFolder = convertParsedEntryToCmisObject(result.data);
      
      // Ensure allowableActions has canGetChildren for root folder
      if (!rootFolder.allowableActions) {
        rootFolder.allowableActions = { canGetChildren: true };
      }
      
      return rootFolder;
    } catch (error) {
      // Network error or exception - return fallback folder
      return fallbackFolder;
    }
  }

  /**
   * Get children of a folder using the new AtomPubClient
   * 
   * MIGRATION NOTE: This method has been migrated to use the new AtomPubClient
   * which provides better separation of concerns and testability.
   * The behavior is preserved: uses AtomPub binding with filter=* and includeAllowableActions=true
   * 
   * @param repositoryId Repository ID (e.g., 'bedroom')
   * @param folderId Folder ID to get children from
   * @returns Promise resolving to array of CMISObject
   */
  async getChildren(repositoryId: string, folderId: string): Promise<CMISObject[]> {
    try {
      // Use the new AtomPubClient for getChildren
      // Options match the original implementation: filter=* and includeAllowableActions=true
      const result = await this.atomPubClient.getChildren(repositoryId, folderId, {
        filter: '*',
        includeAllowableActions: true
      });

      if (!result.success) {
        // Handle HTTP errors using existing error handler
        if (result.status === 401 || result.status === 403) {
          const error = this.handleHttpError(result.status, result.error || 'Unauthorized', '');
          throw error;
        }
        throw new Error(result.error || `HTTP ${result.status}`);
      }

      // Convert ParsedAtomEntry[] to CMISObject[]
      const children: CMISObject[] = result.data?.entries.map(convertParsedEntryToCmisObject) || [];
      
      return children;
    } catch (error) {
      // Re-throw errors to preserve existing behavior
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Network error');
    }
  }

  /**
   * Get a single CMIS object by ID using the new AtomPubClient
   * 
   * MIGRATION NOTE: This method has been migrated to use the new AtomPubClient
   * which provides better separation of concerns and testability.
   * The behavior is preserved: uses AtomPub binding with includeAllowableActions=true
   * 
   * @param repositoryId Repository ID (e.g., 'bedroom')
   * @param objectId Object ID to retrieve
   * @returns Promise resolving to CMISObject
   */
  async getObject(repositoryId: string, objectId: string): Promise<CMISObject> {
    try {
      // Use the new AtomPubClient for getObject
      // Options match the original implementation: includeAllowableActions=true
      const result = await this.atomPubClient.getObject(repositoryId, objectId, {
        includeAllowableActions: true
      });

      if (!result.success) {
        // Handle HTTP errors using existing error handler
        if (result.status === 401 || result.status === 403) {
          const error = this.handleHttpError(result.status, result.error || 'Unauthorized', '');
          throw error;
        }
        throw new Error(result.error || `HTTP ${result.status}`);
      }

      if (!result.data) {
        throw new Error('No entry found in AtomPub response');
      }

      // Convert ParsedAtomEntry to CMISObject
      return convertParsedEntryToCmisObject(result.data);
    } catch (error) {
      // Re-throw errors to preserve existing behavior
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Network error');
    }
  }

  /**
   * Get a CMIS object by its path using the new AtomPubClient
   * 
   * MIGRATION NOTE: This method has been migrated to use the new AtomPubClient
   * which provides better separation of concerns and testability.
   * The behavior is preserved: uses AtomPub binding with includeAllowableActions=true
   *
   * @param repositoryId Repository ID (e.g., 'bedroom')
   * @param path Full path to the object (e.g., '/Sites/Documents')
   * @returns CMISObject with id, name, and basic properties
   */
  async getObjectByPath(repositoryId: string, path: string): Promise<CMISObject> {
    try {
      // Use the new AtomPubClient for getObjectByPath
      // Options match the original implementation: includeAllowableActions=true
      const result = await this.atomPubClient.getObjectByPath(repositoryId, path, {
        includeAllowableActions: true
      });

      if (!result.success) {
        // Handle HTTP errors using existing error handler
        if (result.status === 401 || result.status === 403) {
          const error = this.handleHttpError(result.status, result.error || 'Unauthorized', '');
          throw error;
        }
        throw new Error(result.error || `HTTP ${result.status}`);
      }

      if (!result.data) {
        throw new Error('No entry found in AtomPub response');
      }

      // Convert ParsedAtomEntry to CMISObject
      return convertParsedEntryToCmisObject(result.data);
    } catch (error) {
      // Re-throw errors to preserve existing behavior
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Network error');
    }
  }

  async createDocument(repositoryId: string, parentId: string, file: File, properties: Record<string, any>): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Browser Binding createDocument uses repository base URL, not object-specific URL
      // Parent folder is specified via folderId parameter in form data
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201) {
            try {
              const response = JSON.parse(xhr.responseText);
              const created = this.buildCmisObjectFromBrowserData(response);
              resolve(created);
            } catch (e) {
              reject(new Error('Failed to parse Browser Binding response'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const documentName = properties?.['cmis:name'] || properties?.name || file.name;
      const defaults = {
        'cmis:objectTypeId': properties?.['cmis:objectTypeId'] || 'cmis:document',
        'cmis:name': documentName
      };

      const formData = new FormData();
      formData.append('cmisaction', 'createDocument');
      formData.append('succinct', 'true');
      formData.append('_charset_', 'UTF-8');
      if (parentId) {
        formData.append('folderId', parentId);
        formData.append('objectId', parentId);
      } else {
        formData.append('folderId', 'root');
        formData.append('objectId', 'root');
      }

      this.appendPropertiesToFormData(formData, properties || {}, defaults);
      formData.append('content', file, documentName);
      if (file.type) {
        formData.append('contentType', file.type);
      }

      xhr.send(formData);
    });
  }

  async createFolder(repositoryId: string, parentId: string, name: string, properties: Record<string, any> = {}): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const parentSegment = this.encodeObjectIdSegment(parentId);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/${parentSegment}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201) {
            try {
              const response = JSON.parse(xhr.responseText);
              const created = this.buildCmisObjectFromBrowserData(response);
              resolve(created);
            } catch (e) {
              reject(new Error('Failed to parse Browser Binding response'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const defaults = {
        'cmis:objectTypeId': properties?.['cmis:objectTypeId'] || 'cmis:folder',
        'cmis:name': properties?.['cmis:name'] || name
      };

      const formData = new FormData();
      formData.append('cmisaction', 'createFolder');
      formData.append('succinct', 'true');
      if (parentId) {
        formData.append('folderId', parentId);
        formData.append('objectId', parentId);
      } else {
        formData.append('folderId', 'root');
        formData.append('objectId', 'root');
      }

      this.appendPropertiesToFormData(formData, properties || {}, defaults);
      xhr.send(formData);
    });
  }

  async updateProperties(repositoryId: string, objectId: string, properties: Record<string, any>, changeToken?: string): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use Browser Binding updateProperties action
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const props = response.succinctProperties || response.properties || {};

              // CRITICAL FIX (2025-11-18): Extract all properties DocumentViewer expects
              // Previously only extracted id/name/objectType/baseType, causing all other properties
              // (createdBy, lastModifiedBy, dates, etc.) to be undefined, displaying as hyphens in UI
              const updatedObject: CMISObject = {
                id: this.getSafeStringProperty(props, 'cmis:objectId', objectId),
                name: this.getSafeStringProperty(props, 'cmis:name', 'Unknown'),
                objectType: this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document'),
                baseType: this.getSafeStringProperty(props, 'cmis:baseTypeId', 'cmis:document'),
                properties: props,
                allowableActions: this.extractAllowableActions(response.allowableActions),
                createdBy: this.getSafeStringProperty(props, 'cmis:createdBy'),
                lastModifiedBy: this.getSafeStringProperty(props, 'cmis:lastModifiedBy'),
                creationDate: this.getSafeDateProperty(props, 'cmis:creationDate'),
                lastModificationDate: this.getSafeDateProperty(props, 'cmis:lastModificationDate'),
                contentStreamLength: this.getSafeIntegerProperty(props, 'cmis:contentStreamLength'),
                path: this.getSafeStringProperty(props, 'cmis:path')
              };
              resolve(updatedObject);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      // Use FormData for Browser Binding updateProperties
      const formData = new FormData();
      formData.append('cmisaction', 'updateProperties');
      formData.append('objectId', objectId);
      formData.append('succinct', 'true'); // Request succinct format for direct property values

      // CRITICAL FIX (2025-11-17): Include change token for optimistic locking
      // The CMIS server requires the change token to prevent concurrent update conflicts (HTTP 409)
      if (changeToken) {
        formData.append('changeToken', changeToken);
      }

      let propertyIndex = 0;
      Object.entries(properties).forEach(([key, value]) => {
        formData.append(`propertyId[${propertyIndex}]`, key);

        // Handle multi-value properties (arrays) according to CMIS Browser Binding spec
        if (Array.isArray(value)) {
          if (value.length === 0) {
            // Empty array - don't add any propertyValue entries
            // CMIS Browser Binding: absence of propertyValue means empty multi-value property
          } else {
            // Non-empty array - add each value with sub-index
            value.forEach((item, subIndex) => {
              formData.append(`propertyValue[${propertyIndex}][${subIndex}]`, String(item));
            });
          }
        } else if (value === null || value === undefined) {
          // Null/undefined - send empty string to clear property
          formData.append(`propertyValue[${propertyIndex}]`, '');
        } else {
          // Single value property - convert to string
          formData.append(`propertyValue[${propertyIndex}]`, String(value));
        }

        propertyIndex++;
      });

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData);
    });
  }

  async deleteObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // CRITICAL FIX (2025-11-18): Use application/x-www-form-urlencoded for Browser Binding
      // Previous bug: Used FormData without Content-Type, causing multipart/form-data
      // Browser Binding requires form-urlencoded for operations without file uploads
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      // Use URLSearchParams for application/x-www-form-urlencoded (matches checkOut pattern)
      const formData = new URLSearchParams();
      formData.append('cmisaction', 'deleteObject');
      formData.append('objectId', objectId);

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData.toString());
    });
  }

  async search(repositoryId: string, query: string): Promise<SearchResult> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}?cmisselector=query&q=${encodeURIComponent(query)}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);

              // CRITICAL FIX (2025-11-19): Transform search results to extract CMIS properties
              // The CMIS Browser Binding returns properties in format: {"cmis:name": {"value": "x"}}
              // The UI expects flat format: {name: "x", objectType: "y", ...}
              // This transformation is required for search result cells to display data correctly
              const transformedResults = (response.results || []).map((result: any) => {
                const props = result.properties || {};

                return {
                  id: this.getSafeStringProperty(props, 'cmis:objectId', ''),
                  name: this.getSafeStringProperty(props, 'cmis:name', 'Unknown'),
                  objectType: this.getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document'),
                  baseType: this.getSafeStringProperty(props, 'cmis:baseTypeId', 'cmis:document'),
                  properties: props,
                  allowableActions: this.extractAllowableActions(result.allowableActions),
                  createdBy: this.getSafeStringProperty(props, 'cmis:createdBy'),
                  lastModifiedBy: this.getSafeStringProperty(props, 'cmis:lastModifiedBy'),
                  creationDate: this.getSafeDateProperty(props, 'cmis:creationDate'),
                  lastModificationDate: this.getSafeDateProperty(props, 'cmis:lastModificationDate'),
                  contentStreamLength: this.getSafeIntegerProperty(props, 'cmis:contentStreamLength'),
                  contentStreamMimeType: this.getSafeStringProperty(props, 'cmis:contentStreamMimeType'),
                  path: this.getSafeStringProperty(props, 'cmis:path'),
                  secondaryTypeIds: this.getSafeArrayProperty(props, 'cmis:secondaryObjectTypeIds')
                };
              });

              resolve({
                objects: transformedResults,
                hasMoreItems: response.hasMoreItems || false,
                numItems: response.numItems || 0
              });
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during search'));
      xhr.send();
    });
  }

  /**
   * Get version history of a document using the new AtomPubClient
   * 
   * MIGRATION NOTE: This method has been migrated to use the new AtomPubClient
   * which provides better separation of concerns and testability.
   * 
   * @param repositoryId Repository ID (e.g., 'bedroom')
   * @param objectId Document ID to get version history for
   * @returns Promise resolving to VersionHistory
   */
  async getVersionHistory(repositoryId: string, objectId: string): Promise<VersionHistory> {
    try {
      // Use the new AtomPubClient for getVersionHistory
      const result = await this.atomPubClient.getVersionHistory(repositoryId, objectId);

      if (!result.success) {
        // Handle HTTP errors using existing error handler
        if (result.status === 401 || result.status === 403) {
          const error = this.handleHttpError(result.status, result.error || 'Unauthorized', '');
          throw error;
        }
        throw new Error(result.error || `HTTP ${result.status}`);
      }

      // Convert ParsedAtomEntry[] to CMISObject[]
      const versions: CMISObject[] = (result.data || []).map(convertParsedEntryToCmisObject);

      // Use first version as latestVersion (versions are typically ordered newest-first)
      const latestVersion: CMISObject = versions[0] || {
        id: '',
        name: '',
        objectType: 'cmis:document',
        baseType: 'cmis:document',
        properties: {},
        allowableActions: undefined
      };

      return {
        versions: versions,
        latestVersion: latestVersion
      };
    } catch (error) {
      // Re-throw errors to preserve existing behavior
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Network error');
    }
  }

  /**
   * Check out a document (CMIS Browser Binding standard)
   * Creates a Private Working Copy (PWC) and returns it
   *
   * @param repositoryId Repository ID
   * @param objectId Document object ID to check out
   * @returns PWC (Private Working Copy) object
   */
  async checkOut(repositoryId: string, objectId: string): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // CMIS Browser Binding checkOut: POST with form-urlencoded
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const pwc = this.buildCmisObjectFromBrowserData(response);
              resolve(pwc);
            } catch (e) {
              // Failed to parse response
              reject(new Error('Invalid response format'));
            }
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      // Build form data for Browser Binding checkOut
      const formData = new URLSearchParams();
      formData.append('cmisaction', 'checkOut');
      formData.append('objectId', objectId);
      formData.append('succinct', 'true');

      xhr.send(formData.toString());
    });
  }

  /**
   * Check in a PWC (Private Working Copy) (CMIS Browser Binding standard)
   * Completes the check-out/check-in cycle and creates a new version
   *
   * @param repositoryId Repository ID
   * @param objectId PWC (Private Working Copy) object ID
   * @param file Optional file to upload as new content
   * @param properties Optional properties including:
   *   - major: boolean (true for major version, false for minor)
   *   - checkinComment: string (version comment)
   * @returns New version object
   */
  async checkIn(repositoryId: string, objectId: string, file?: File, properties?: Record<string, any>): Promise<CMISObject> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // CMIS Browser Binding checkIn: POST with multipart/form-data
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const newVersion = this.buildCmisObjectFromBrowserData(response);
              resolve(newVersion);
            } catch (e) {
              // Failed to parse response
              reject(new Error('Invalid response format'));
            }
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      // Build multipart form data for Browser Binding checkIn
      const formData = new FormData();
      formData.append('cmisaction', 'checkIn');
      formData.append('objectId', objectId);
      formData.append('succinct', 'true');

      // Extract version type from properties (default to major version)
      const major = properties?.major !== undefined ? properties.major : true;
      formData.append('major', String(major));

      // Extract check-in comment from properties
      if (properties?.checkinComment) {
        formData.append('checkinComment', properties.checkinComment);
      }

      // Add file content if provided
      if (file) {
        formData.append('content', file);
        formData.append('filename', file.name);
        if (file.type) {
          formData.append('mimetype', file.type);
        }
      }

      xhr.send(formData);
    });
  }

  /**
   * Cancel check-out of a PWC (Private Working Copy) (CMIS Browser Binding standard)
   * Discards the PWC and any changes made
   *
   * @param repositoryId Repository ID
   * @param objectId PWC (Private Working Copy) object ID to cancel
   */
  async cancelCheckOut(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // CMIS Browser Binding cancelCheckOut: POST with form-urlencoded
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      // Build form data for Browser Binding cancelCheckOut
      const formData = new URLSearchParams();
      formData.append('cmisaction', 'cancelCheckOut');
      formData.append('objectId', objectId);

      xhr.send(formData.toString());
    });
  }

  /**
   * Get ACL for an object (REST API endpoint)
   * Retrieves the Access Control List (permissions) for a CMIS object
   *
   * CRITICAL FIX (2025-11-22): Switched from Browser Binding to REST API endpoint
   * - Old: /core/browser/{repositoryId}/{objectId}?cmisselector=acl
   * - New: /core/rest/repo/{repositoryId}/node/{objectId}/acl
   * - Reason: Tests expect REST API, Browser Binding causes root folder 500 errors
   *
   * @param repositoryId Repository ID
   * @param objectId Object ID to get ACL for
   * @returns ACL object containing permissions
   */
  async getACL(repositoryId: string, objectId: string): Promise<ACL> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `/core/rest/repo/${repositoryId}/node/${objectId}/acl`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);

              const aclData = response.result?.acl || response.acl || {};
              const permissions = (aclData.permissions || []).map((perm: any) => ({
                principalId: perm.principalId,
                permissions: perm.permissions || [],
                direct: perm.direct !== false // Default to true if not specified
              }));

              // Extract aclInherited (default to true if not specified)
              const aclInherited = aclData.aclInherited !== false;

              const acl: ACL = {
                permissions: permissions,
                isExact: aclData.isExact !== false, // Default to true if not specified
                aclInherited
              };

              resolve(acl);
            } catch (e) {
              // Failed to parse response
              reject(new Error('Invalid response format'));
            }
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  /**
   * Set ACL for an object (REST API endpoint)
   * Sets the Access Control List (permissions) for a CMIS object
   *
   * CRITICAL FIX (2025-11-22): Switched from Browser Binding to REST API endpoint
   * - Old: /core/browser/{repositoryId} with cmisaction=applyACL
   * - New: /core/rest/repo/{repositoryId}/node/{objectId}/acl
   * - Reason: Tests expect REST API, simpler JSON payload format
   *
   * @param repositoryId Repository ID
   * @param objectId Object ID to set ACL for
   * @param acl ACL object containing permissions to apply
   * @param options Optional parameters including breakInheritance flag
   */
  async setACL(repositoryId: string, objectId: string, acl: ACL, options?: { breakInheritance?: boolean }): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/core/rest/repo/${repositoryId}/node/${objectId}/acl`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));

      const payload: {
        permissions: { principalId: string; permissions: string[]; direct: boolean }[];
        breakInheritance?: boolean;
      } = {
        permissions: acl.permissions.map(perm => ({
          principalId: perm.principalId,
          permissions: perm.permissions,
          direct: perm.direct !== false
        }))
      };

      // Add breakInheritance option if specified
      if (options?.breakInheritance !== undefined) {
        payload.breakInheritance = options.breakInheritance;
      }

      xhr.send(JSON.stringify(payload));
    });
  }

  async getUsers(repositoryId: string): Promise<User[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // サーバー側修正に合わせて正しいRESTエンドポイントを使用
      xhr.open('GET', `/core/rest/repo/${repositoryId}/user/list`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const rawUsers = response.users || [];
              
              // Transform user data to match UI expectations
              // Preserve firstName and lastName as separate fields for table display
              const transformedUsers = rawUsers.map((user: any) => ({
                id: user.userId || user.id,
                name: user.userName || user.userId || user.id,
                firstName: user.firstName,
                lastName: user.lastName,
                email: user.email,
                groups: user.groups || []
              }));

              resolve(transformedUsers);
            } catch (e) {
              // Failed to parse response
              reject(new Error('Invalid response format'));
            }
          } else if (xhr.status === 500) {
            // サーバー側エラーの詳細情報を解析
            let errorMessage = 'サーバーエラーが発生しました';
            let errorDetails = '';
            try {
              const errorResponse = JSON.parse(xhr.responseText);
              if (errorResponse.message) {
                errorMessage = errorResponse.message;
              }
              if (errorResponse.error) {
                errorDetails = errorResponse.error;
              }
              if (errorResponse.errorType) {
                errorDetails += ` (${errorResponse.errorType})`;
              }
            } catch (e) {
              // JSONパースできない場合はレスポンステキストをそのまま使用
              errorDetails = xhr.responseText || 'Unknown server error';
            }
            
            const error = new Error(errorMessage);
            (error as any).details = errorDetails;
            (error as any).status = xhr.status;
            reject(error);
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createUser(repositoryId: string, user: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.restBaseUrl}/${repositoryId}/user/create/${user.id}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during user creation'));

      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', user.name || '');  // FORM_USERNAME = "name"
      formData.append('firstName', user.firstName || '');  // FORM_FIRSTNAME = "firstName"
      formData.append('lastName', user.lastName || '');  // FORM_LASTNAME = "lastName"
      formData.append('email', user.email || '');  // FORM_EMAIL = "email"
      formData.append('password', user.password || '');  // FORM_PASSWORD = "password"
      // Add groups parameter - serialize as JSON
      if (user.groups && user.groups.length > 0) {
        formData.append('groups', JSON.stringify(user.groups));
      }

      xhr.send(formData.toString());
    });
  }

  async updateUser(repositoryId: string, userId: string, user: Partial<User>): Promise<User> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', `${this.restBaseUrl}/${repositoryId}/user/update/${userId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during user update'));

      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', user.name || '');  // FORM_USERNAME = "name"
      formData.append('firstName', user.firstName || '');  // FORM_FIRSTNAME = "firstName"
      formData.append('lastName', user.lastName || '');  // FORM_LASTNAME = "lastName"
      formData.append('email', user.email || '');  // FORM_EMAIL = "email"
      // Add groups parameter - serialize as JSON
      if (user.groups !== undefined) {
        formData.append('groups', JSON.stringify(user.groups));
      }

      xhr.send(formData.toString());
    });
  }

  async deleteUser(repositoryId: string, userId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.restBaseUrl}/${repositoryId}/user/delete/${userId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during user deletion'));
      xhr.send();
    });
  }

  async getGroups(repositoryId: string): Promise<Group[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // サーバー側修正に合わせて正しいRESTエンドポイントを使用
      xhr.open('GET', `/core/rest/repo/${repositoryId}/group/list`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const rawGroups = response.groups || [];
              
              // Transform group data to match UI expectations
              const transformedGroups = rawGroups.map((group: any) => ({
                id: group.groupId || group.id,
                name: group.groupName || group.name || group.groupId || 'Unknown Group',
                members: group.users || []
              }));

              resolve(transformedGroups);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else if (xhr.status === 500) {
            // サーバー側エラーの詳細情報を解析
            let errorMessage = 'サーバーエラーが発生しました';
            let errorDetails = '';
            try {
              const errorResponse = JSON.parse(xhr.responseText);
              if (errorResponse.message) {
                errorMessage = errorResponse.message;
              }
              if (errorResponse.error) {
                errorDetails = errorResponse.error;
              }
              if (errorResponse.errorType) {
                errorDetails += ` (${errorResponse.errorType})`;
              }
            } catch (e) {
              // JSONパースできない場合はレスポンステキストをそのまま使用
              errorDetails = xhr.responseText || 'Unknown server error';
            }
            
            const error = new Error(errorMessage);
            (error as any).details = errorDetails;
            (error as any).status = xhr.status;
            reject(error);
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createGroup(repositoryId: string, group: Partial<Group>): Promise<Group> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.restBaseUrl}/${repositoryId}/group/create/${group.id}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during group creation'));

      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', group.name || '');  // FORM_GROUPNAME = "name"
      // CRITICAL FIX: GroupManagement sends 'members' field, but API expects 'users' field
      // Convert members array to users for server-side compatibility
      const members = (group as any).members || (group as any).users || [];
      formData.append('users', JSON.stringify(members));  // FORM_MEMBER_USERS = "users"
      formData.append('groups', JSON.stringify((group as any).groups || []));  // FORM_MEMBER_GROUPS = "groups"

      xhr.send(formData.toString());
    });
  }

  async updateGroup(repositoryId: string, groupId: string, group: Partial<Group>): Promise<Group> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', `${this.restBaseUrl}/${repositoryId}/group/update/${groupId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader('Accept', 'application/json');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during group update'));

      // Convert to form data - match server-side FORM_ constants
      const formData = new URLSearchParams();
      formData.append('name', group.name || '');  // FORM_GROUPNAME = "name"
      // CRITICAL FIX: GroupManagement sends 'members' field, but API expects 'users' field
      // Convert members array to users for server-side compatibility
      const members = (group as any).members || (group as any).users || [];
      formData.append('users', JSON.stringify(members));  // FORM_MEMBER_USERS = "users"
      formData.append('groups', JSON.stringify((group as any).groups || []));  // FORM_MEMBER_GROUPS = "groups"

      xhr.send(formData.toString());
    });
  }

  async deleteGroup(repositoryId: string, groupId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.restBaseUrl}/${repositoryId}/group/delete/${groupId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during group deletion'));
      xhr.send();
    });
  }

  async getTypes(repositoryId: string): Promise<TypeDefinition[]> {
    // CRITICAL FIX (2025-10-26): Use REST API instead of Browser Binding for consistency
    // All type CRUD operations (create/update/delete) use REST API, getTypes must use same API
    // to ensure newly created types appear in the list
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.restBaseUrl}/${repositoryId}/type/list`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);

              // Response format: { types: [...] }
              if (!response.types || !Array.isArray(response.types)) {
                // Invalid response format - return empty array
                resolve([]);
                return;
              }

              const types: TypeDefinition[] = response.types.map((type: any) => ({
                id: type.id,
                displayName: type.displayName || type.id,
                description: type.description || '',
                baseTypeId: type.baseTypeId || type.baseId,
                parentTypeId: type.parentTypeId,
                creatable: type.creatable !== false,
                fileable: type.fileable !== false,
                queryable: type.queryable !== false,
                deletable: !type.id.startsWith('cmis:') && type.typeMutability?.delete !== false,
                propertyDefinitions: type.propertyDefinitions || {}
              }));

              resolve(types);
            } catch (e) {
              // Failed to parse response
              reject(new Error('Failed to parse type list response'));
            }
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        // Network error occurred
        reject(new Error('Network error during type list fetch'));
      };

      xhr.send();
    });
  }

  async getType(repositoryId: string, typeId: string): Promise<TypeDefinition> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}?cmisselector=typeDefinition&typeId=${encodeURIComponent(typeId)}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              const typeDefinition = this.buildTypeDefinitionFromBrowserData(response);
              resolve(typeDefinition);
            } catch (e) {
              reject(new Error('Failed to parse type definition response'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createType(repositoryId: string, type: Partial<TypeDefinition>): Promise<TypeDefinition> {
    // First, create the type via REST API
    await new Promise<void>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.restBaseUrl}/${repositoryId}/type/create`, true);
      xhr.timeout = 30000; // 30 second timeout to prevent indefinite hangs
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              // Backend returns {message, status} not {type}
              // Just verify success and then fetch the type separately
              if (response.status === 'success') {
                resolve();
              } else {
                reject(new Error(response.message || 'Type creation failed'));
              }
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during type creation'));
      xhr.ontimeout = () => reject(new Error('Request timed out - type creation took too long'));

      // Map frontend TypeDefinition field names to backend expectations
      // Backend expects "baseId" instead of "baseTypeId" for CMIS type definitions
      const backendPayload = {
        ...type,
        baseId: type.baseTypeId, // Map baseTypeId to baseId for backend
      };
      // Remove baseTypeId to avoid confusion
      delete (backendPayload as any).baseTypeId;

      xhr.send(JSON.stringify(backendPayload));
    });

    // Then fetch the created type via CMIS API to return complete definition
    return this.getType(repositoryId, type.id!);
  }

  async updateType(repositoryId: string, typeId: string, type: Partial<TypeDefinition>): Promise<TypeDefinition> {
    // First, update the type via REST API
    await new Promise<void>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', `${this.restBaseUrl}/${repositoryId}/type/update/${typeId}`, true);
      xhr.timeout = 30000; // 30 second timeout to prevent indefinite hangs
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              // Backend returns {message, status} not {type}
              // Just verify success and then fetch the type separately
              if (response.status === 'success') {
                resolve();
              } else {
                reject(new Error(response.message || 'Type update failed'));
              }
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during type update'));
      xhr.ontimeout = () => reject(new Error('Request timed out - type update took too long'));

      // Map frontend TypeDefinition field names to backend expectations
      // Backend expects "baseId" instead of "baseTypeId" for CMIS type definitions
      const backendPayload = {
        ...type,
        baseId: type.baseTypeId, // Map baseTypeId to baseId for backend
      };
      // Remove baseTypeId to avoid confusion
      delete (backendPayload as any).baseTypeId;

      xhr.send(JSON.stringify(backendPayload));
    });

    // Then fetch the updated type via CMIS API to return complete definition
    return this.getType(repositoryId, typeId);
  }

  async deleteType(repositoryId: string, typeId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('DELETE', `${this.restBaseUrl}/${repositoryId}/type/delete/${typeId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during type deletion'));
      xhr.send();
    });
  }

  async getRelationships(repositoryId: string, objectId: string): Promise<Relationship[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use CMIS AtomPub binding for relationships (Browser Binding doesn't support relationships endpoint)
      // CRITICAL FIX (2025-12-17): Add relationshipDirection=either to get bidirectional relationships
      // Without this parameter, CMIS defaults to 'source' direction only, meaning relationships where
      // the object is the TARGET will not be returned. For bidirectional relationships (like
      // nemaki:bidirectionalRelationship), users expect to see the relationship from both sides.
      xhr.open('GET', `/core/atom/${repositoryId}/relationships?id=${objectId}&relationshipDirection=either`, true);
      xhr.setRequestHeader('Accept', 'application/atom+xml');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              // Parse AtomPub XML response for relationships
              const parser = new DOMParser();
              const xmlDoc = parser.parseFromString(xhr.responseText, 'text/xml');

              // CRITICAL FIX (2025-12-13): Use getElementsByTagNameNS for atom:entry elements
              // getElementsByTagName('entry') doesn't find <atom:entry> because of namespace prefix
              const ATOM_NS = 'http://www.w3.org/2005/Atom';
              const CMIS_NS = 'http://docs.oasis-open.org/ns/cmis/core/200908/';

              console.log('[CMIS DEBUG] getRelationships parsing XML, response length:', xhr.responseText.length);
              console.log('[CMIS DEBUG] getRelationships first 500 chars:', xhr.responseText.substring(0, 500));

              const entries = xmlDoc.getElementsByTagNameNS(ATOM_NS, 'entry');
              console.log('[CMIS DEBUG] getRelationships found', entries.length, 'entry elements');

              const relationships: Relationship[] = [];
              for (let i = 0; i < entries.length; i++) {
                const entry = entries[i];

                // Helper function to get property value from CMIS XML
                // CMIS XML structure: <cmis:properties><cmis:propertyId propertyDefinitionId="cmis:sourceId"><cmis:value>...</cmis:value></cmis:propertyId></cmis:properties>
                // CRITICAL FIX (2025-12-13): Need to search all property types using proper namespaces
                const getPropertyValue = (propName: string): string => {
                  const properties = entry.getElementsByTagNameNS(CMIS_NS, 'properties')[0] ||
                                   entry.querySelector('cmis\\:properties, properties');
                  if (!properties) return '';

                  // Search through all child elements with propertyDefinitionId attribute
                  // CRITICAL FIX: Use getElementsByTagNameNS instead of getElementsByTagName
                  const propTypes = ['propertyId', 'propertyString', 'propertyDateTime', 'propertyBoolean', 'propertyInteger', 'propertyDecimal'];
                  for (const propType of propTypes) {
                    const propElements = properties.getElementsByTagNameNS(CMIS_NS, propType);
                    for (let j = 0; j < propElements.length; j++) {
                      const elem = propElements[j];
                      if (elem.getAttribute('propertyDefinitionId') === propName) {
                        // CRITICAL FIX: Use getElementsByTagNameNS for value element too
                        const valueElems = elem.getElementsByTagNameNS(CMIS_NS, 'value');
                        if (valueElems.length > 0) {
                          return valueElems[0].textContent || '';
                        }
                        // Fallback to querySelector
                        const valueElem = elem.querySelector('cmis\\:value, value');
                        return valueElem?.textContent || '';
                      }
                    }
                  }
                  return '';
                };

                // CRITICAL FIX: Get objectId from cmis:objectId property, not from <atom:id> which is base64 encoded
                const relationshipId = getPropertyValue('cmis:objectId');
                const sourceId = getPropertyValue('cmis:sourceId');
                const targetId = getPropertyValue('cmis:targetId');
                const objectTypeId = getPropertyValue('cmis:objectTypeId') || 'cmis:relationship';

                console.log(`[CMIS DEBUG] getRelationships entry ${i}: id=${relationshipId}, sourceId=${sourceId}, targetId=${targetId}, type=${objectTypeId}`);

                // Only add if we have valid source and target IDs
                if (sourceId && targetId) {
                  relationships.push({
                    id: relationshipId,
                    sourceId: sourceId,
                    targetId: targetId,
                    relationshipType: objectTypeId,
                    properties: {}
                  });
                } else {
                  console.log(`[CMIS DEBUG] getRelationships entry ${i} skipped: missing sourceId or targetId`);
                }
              }
              console.log('[CMIS DEBUG] getRelationships returning', relationships.length, 'relationships');
              
              resolve(relationships);
            } catch (e) {
              reject(new Error('Failed to parse relationships XML'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send();
    });
  }

  async createRelationship(repositoryId: string, relationship: Partial<Relationship>): Promise<Relationship> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // CRITICAL FIX: Use CMIS Browser Binding standard endpoint instead of custom REST endpoint
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201) {
            try {
              const response = JSON.parse(xhr.responseText);
              // Build Relationship object from CMIS Browser Binding response
              const createdRelationship: Relationship = {
                id: response.properties?.['cmis:objectId']?.value || response.succinctProperties?.['cmis:objectId'],
                sourceId: response.properties?.['cmis:sourceId']?.value || response.succinctProperties?.['cmis:sourceId'] || relationship.sourceId || '',
                targetId: response.properties?.['cmis:targetId']?.value || response.succinctProperties?.['cmis:targetId'] || relationship.targetId || '',
                relationshipType: response.properties?.['cmis:objectTypeId']?.value || response.succinctProperties?.['cmis:objectTypeId'] || relationship.relationshipType || '',
                properties: response.properties || response.succinctProperties || {}
              };
              resolve(createdRelationship);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during relationship creation'));

      // Use CMIS Browser Binding form data format
      const formData = new FormData();
      formData.append('cmisaction', 'createRelationship');
      formData.append('propertyId[0]', 'cmis:objectTypeId');
      formData.append('propertyValue[0]', relationship.relationshipType || 'nemaki:bidirectionalRelationship');
      formData.append('propertyId[1]', 'cmis:sourceId');
      formData.append('propertyValue[1]', relationship.sourceId || '');
      formData.append('propertyId[2]', 'cmis:targetId');
      formData.append('propertyValue[2]', relationship.targetId || '');

      xhr.send(formData);
    });
  }

  async deleteRelationship(repositoryId: string, relationshipId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // CRITICAL FIX: Use CMIS Browser Binding standard delete endpoint
      xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during relationship deletion'));

      // Use CMIS Browser Binding form data format for delete
      const formData = new FormData();
      formData.append('cmisaction', 'delete');
      formData.append('objectId', relationshipId);
      xhr.send(formData);
    });
  }

  async initSearchEngine(repositoryId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/search-engine/init`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during search engine initialization'));
      xhr.send();
    });
  }

  async reindexSearchEngine(repositoryId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${this.baseUrl}/${repositoryId}/search-engine/reindex`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during search engine reindexing'));
      xhr.send();
    });
  }

  async getArchives(repositoryId: string): Promise<CMISObject[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use correct REST endpoint for archive index
      xhr.open('GET', `/core/rest/repo/${repositoryId}/archive/index`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.archives || []);
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during archive retrieval'));
      xhr.send();
    });
  }

  async archiveObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${this.baseUrl}/${repositoryId}/archive/${objectId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during object archiving'));
      xhr.send(JSON.stringify({}));
    });
  }

  async restoreObject(repositoryId: string, objectId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      // Use REST API endpoint for archive restore (PUT method, matches ArchiveResource.java)
      // ArchiveResource: @PUT @Path("/restore/{id}")
      xhr.open('PUT', `/core/rest/repo/${repositoryId}/archive/restore/${objectId}`, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 204) {
            resolve();
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => reject(new Error('Network error during object restoration'));
      xhr.send(JSON.stringify({}));
    });
  }

  /**
   * Get parent folders for an object (document or folder) using the new AtomPubClient
   * Returns array of parent folder objects (typically 1 parent, but CMIS supports multi-filing)
   * Used to calculate document paths from parent folder paths
   *
   * MIGRATION NOTE: This method has been migrated to use the new AtomPubClient
   * which provides better separation of concerns and testability.
   *
   * @param repositoryId - Repository ID
   * @param objectId - Object ID
   * @returns Promise resolving to array of parent folder objects with id, name, and path
   */
  async getObjectParents(repositoryId: string, objectId: string): Promise<CMISObject[]> {
    try {
      // Use the new AtomPubClient for getObjectParents
      const result = await this.atomPubClient.getObjectParents(repositoryId, objectId);

      if (!result.success) {
        // Handle HTTP errors using existing error handler
        if (result.status === 401 || result.status === 403) {
          const error = this.handleHttpError(result.status, result.error || 'Unauthorized', '');
          throw error;
        }
        throw new Error(result.error || `HTTP ${result.status}`);
      }

      // Convert ParsedAtomEntry[] to CMISObject[]
      const parents: CMISObject[] = (result.data || []).map(convertParsedEntryToCmisObject);

      return parents;
    } catch (error) {
      // Re-throw errors to preserve existing behavior
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Network error during getObjectParents');
    }
  }

  getDownloadUrl(repositoryId: string, objectId: string): string {
    const token = this.authService.getAuthToken();
    return `${this.baseUrl}/${repositoryId}/node/${objectId}/content?token=${token}`;
  }

  async getContentStream(repositoryId: string, objectId: string): Promise<ArrayBuffer> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      
      // Use AtomPub binding for content stream download
      xhr.open('GET', `/core/atom/${repositoryId}/content?id=${objectId}`, true);
      xhr.responseType = 'arraybuffer';
      xhr.setRequestHeader('Accept', 'application/octet-stream');
      
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            resolve(xhr.response);
          } else {
            // Request failed - handle errors
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };
      
      xhr.onerror = () => {
        // Network error occurred
        reject(new Error('Network error'));
      };
      
      xhr.send();
    });
  }

  /**
   * Get renditions for a document (e.g., PDF preview for Office files)
   * @param repositoryId Repository ID
   * @param objectId Document object ID
   * @returns Array of rendition objects with streamId, mimeType, kind, etc.
   */
  async getRenditions(repositoryId: string, objectId: string): Promise<any[]> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      // Use Jersey REST API endpoint for renditions
      xhr.open('GET', `/core/rest/repo/${repositoryId}/renditions/${objectId}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response.renditions || response || []);
            } catch (e) {
              resolve([]);
            }
          } else if (xhr.status === 404) {
            // No renditions found
            resolve([]);
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        reject(new Error('Network error'));
      };

      xhr.send();
    });
  }

  /**
   * Generate renditions for a document (triggers PDF conversion for Office files)
   * @param repositoryId Repository ID
   * @param objectId Document object ID
   * @param force Force regeneration even if rendition exists
   */
  async generateRenditions(repositoryId: string, objectId: string, force: boolean = false): Promise<any> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();

      xhr.open('POST', `/core/rest/repo/${repositoryId}/renditions/generate?objectId=${objectId}&force=${force}`, true);
      xhr.setRequestHeader('Accept', 'application/json');

      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200 || xhr.status === 201 || xhr.status === 202) {
            try {
              const response = JSON.parse(xhr.responseText);
              resolve(response);
            } catch (e) {
              resolve({ success: true });
            }
          } else {
            const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
            reject(error);
          }
        }
      };

      xhr.onerror = () => {
        reject(new Error('Network error'));
      };

      xhr.send();
    });
  }

  /**
   * Get rendition content as a Blob with proper authentication
   * This is needed because react-pdf can't use token-based URLs directly.
   * @param repositoryId Repository ID
   * @param objectId Original document object ID
   * @param streamId Rendition stream ID
   * @returns Promise<Blob> PDF content as blob
   */
  async getRenditionContent(repositoryId: string, objectId: string, streamId: string): Promise<Blob> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const url = `${this.baseUrl}/${repositoryId}?cmisselector=content&objectId=${objectId}&streamId=${streamId}`;

      xhr.open('GET', url, true);
      xhr.responseType = 'blob';

      // Set authentication headers (using the same headers as other CMIS requests)
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });

      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            resolve(xhr.response);
          } else {
            reject(new Error(`Failed to fetch rendition content: HTTP ${xhr.status}`));
          }
        }
      };

      xhr.onerror = () => {
        reject(new Error('Network error while fetching rendition content'));
      };

      xhr.send();
    });
  }

  /**
   * Get rendition content stream URL
   * @param repositoryId Repository ID
   * @param objectId Original document object ID
   * @param streamId Rendition stream ID
   * @returns URL for downloading rendition content
   * @deprecated Use getRenditionContent() instead for authenticated access
   */
  getRenditionUrl(repositoryId: string, objectId: string, streamId: string): string {
    const token = this.authService.getAuthToken();
    return `${this.baseUrl}/${repositoryId}?cmisselector=content&objectId=${objectId}&streamId=${streamId}&token=${token}`;
  }

  // ============================================================
  // Type Selection and Secondary Types Management (2025-12-11)
  // ============================================================

  /**
   * Get document types available for document creation.
   * Returns cmis:document and all its descendant types that are creatable.
   * @param repositoryId Repository ID
   * @returns Promise resolving to array of document TypeDefinitions
   */
  async getDocumentTypes(repositoryId: string): Promise<TypeDefinition[]> {
    const allTypes = await this.getTypes(repositoryId);

    // Build a set of all document type IDs (cmis:document and all descendants)
    const documentTypeIds = new Set<string>(['cmis:document']);

    // Iteratively find all descendants of cmis:document
    let foundNew = true;
    while (foundNew) {
      foundNew = false;
      for (const type of allTypes) {
        if (type.parentTypeId && documentTypeIds.has(type.parentTypeId) && !documentTypeIds.has(type.id)) {
          documentTypeIds.add(type.id);
          foundNew = true;
        }
      }
    }

    // Filter to only document types that are creatable
    return allTypes.filter(type =>
      documentTypeIds.has(type.id) && type.creatable !== false
    );
  }

  /**
   * Get folder types available for folder creation.
   * Returns cmis:folder and all its descendant types that are creatable.
   * @param repositoryId Repository ID
   * @returns Promise resolving to array of folder TypeDefinitions
   */
  async getFolderTypes(repositoryId: string): Promise<TypeDefinition[]> {
    const allTypes = await this.getTypes(repositoryId);

    // Build a set of all folder type IDs (cmis:folder and all descendants)
    const folderTypeIds = new Set<string>(['cmis:folder']);

    // Iteratively find all descendants of cmis:folder
    let foundNew = true;
    while (foundNew) {
      foundNew = false;
      for (const type of allTypes) {
        if (type.parentTypeId && folderTypeIds.has(type.parentTypeId) && !folderTypeIds.has(type.id)) {
          folderTypeIds.add(type.id);
          foundNew = true;
        }
      }
    }

    // Filter to only folder types that are creatable
    return allTypes.filter(type =>
      folderTypeIds.has(type.id) && type.creatable !== false
    );
  }

  /**
   * Get secondary types available for assignment.
   * Returns cmis:secondary and all its descendant types.
   * @param repositoryId Repository ID
   * @returns Promise resolving to array of secondary TypeDefinitions
   */
  async getSecondaryTypes(repositoryId: string): Promise<TypeDefinition[]> {
    const allTypes = await this.getTypes(repositoryId);

    // Build a set of all secondary type IDs (cmis:secondary and all descendants)
    const secondaryTypeIds = new Set<string>(['cmis:secondary']);

    // Iteratively find all descendants of cmis:secondary
    let foundNew = true;
    while (foundNew) {
      foundNew = false;
      for (const type of allTypes) {
        if (type.parentTypeId && secondaryTypeIds.has(type.parentTypeId) && !secondaryTypeIds.has(type.id)) {
          secondaryTypeIds.add(type.id);
          foundNew = true;
        }
      }
    }

    // Filter to only actual secondary types (exclude cmis:secondary itself as it's not directly assignable)
    return allTypes.filter(type =>
      secondaryTypeIds.has(type.id) && type.id !== 'cmis:secondary'
    );
  }

  /**
   * Update secondary types for an object using Browser Binding.
   * Uses addSecondaryTypeIds and removeSecondaryTypeIds parameters for efficient updates.
   * @param repositoryId Repository ID
   * @param objectId Object ID to update
   * @param addTypes Array of secondary type IDs to add
   * @param removeTypes Array of secondary type IDs to remove
   * @returns Promise resolving to updated CMISObject
   */
  async updateSecondaryTypes(
    repositoryId: string,
    objectId: string,
    addTypes: string[],
    removeTypes: string[],
    changeToken?: string
  ): Promise<CMISObject> {
    return new Promise(async (resolve, reject) => {
      try {
        // If no changeToken provided, fetch the current object to get it
        let token = changeToken;
        if (!token) {
          try {
            const currentObject = await this.getObject(repositoryId, objectId);
            token = currentObject.properties?.['cmis:changeToken'] as string;
          } catch (e) {
            console.warn('Could not fetch changeToken, proceeding without it:', e);
          }
        }

        const xhr = new XMLHttpRequest();
        xhr.open('POST', `${this.baseUrl}/${repositoryId}`, true);
        xhr.setRequestHeader('Accept', 'application/json');

        const headers = this.getAuthHeaders();
        Object.entries(headers).forEach(([key, value]) => {
          xhr.setRequestHeader(key, value);
        });

        xhr.onreadystatechange = () => {
          if (xhr.readyState === 4) {
            if (xhr.status === 200 || xhr.status === 201) {
              try {
                const response = JSON.parse(xhr.responseText);
                const updated = this.buildCmisObjectFromBrowserData(response);
                resolve(updated);
              } catch (e) {
                reject(new Error('Failed to parse response'));
              }
            } else {
              const error = this.handleHttpError(xhr.status, xhr.statusText, xhr.responseURL);
              reject(error);
            }
          }
        };

        xhr.onerror = () => reject(new Error('Network error'));

        const formData = new FormData();
        formData.append('cmisaction', 'update');
        formData.append('objectId', objectId);
        formData.append('succinct', 'true');
        formData.append('_charset_', 'UTF-8');

        // CRITICAL FIX (2025-12-12): Include changeToken to prevent update conflicts
        if (token) {
          formData.append('changeToken', token);
        }

        // Add secondary types to add
        if (addTypes.length > 0) {
          formData.append('addSecondaryTypeIds', addTypes.join(','));
        }

        // Add secondary types to remove
        if (removeTypes.length > 0) {
          formData.append('removeSecondaryTypeIds', removeTypes.join(','));
        }

        xhr.send(formData);
      } catch (e) {
        reject(e);
      }
    });
  }

  // =====================================================
  // Type Migration API (NemakiWare Extension)
  // =====================================================

  /**
   * Get compatible types for type migration.
   * Returns all types that an object can be migrated to (same base type).
   *
   * @param repositoryId Repository ID
   * @param objectId Object ID to check compatible types for
   * @returns Promise resolving to compatible types information
   */
  async getCompatibleTypesForMigration(
    repositoryId: string,
    objectId: string
  ): Promise<{
    currentType: string;
    currentTypeDisplayName: string;
    baseType: string;
    compatibleTypes: Record<string, CompatibleType>;
    count: number;
  }> {
    const headers = this.getAuthHeaders();
    const response = await fetch(
      `/core/api/v1/repo/${repositoryId}/type-migration/compatible-types/${objectId}`,
      {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
          ...headers,
        },
      }
    );

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to get compatible types: ${response.status}`);
    }

    const data = await response.json();
    if (data.status === 'error') {
      throw new Error(data.message || 'Failed to get compatible types');
    }

    const rawCompatibleTypes = (data.compatibleTypes ?? {}) as Record<string, unknown>;
    const compatibleTypes: Record<string, CompatibleType> = {};

    Object.entries(rawCompatibleTypes).forEach(([rawId, rawType]) => {
      const type = (rawType ?? {}) as Record<string, unknown>;
      const additionalRequiredPropertiesRaw = (type.additionalRequiredProperties ?? {}) as Record<string, unknown>;
      const additionalRequiredProperties: Record<string, MigrationPropertyDefinition> = {};

      Object.entries(additionalRequiredPropertiesRaw).forEach(([propId, propDef]) => {
        additionalRequiredProperties[propId] = this.normalizeMigrationPropertyDefinition(propDef, propId);
      });

      const normalizedId = String(type.id ?? rawId ?? '').trim() || rawId;

      compatibleTypes[normalizedId] = {
        id: normalizedId,
        displayName: typeof type.displayName === 'string' ? type.displayName : normalizedId,
        description: typeof type.description === 'string' ? type.description : undefined,
        baseTypeId: typeof type.baseTypeId === 'string'
          ? type.baseTypeId
          : typeof type.baseId === 'string'
            ? type.baseId
            : '',
        additionalRequiredProperties,
      };
    });

    return {
      currentType: String(data.currentType ?? ''),
      currentTypeDisplayName: typeof data.currentTypeDisplayName === 'string'
        ? data.currentTypeDisplayName
        : String(data.currentType ?? ''),
      baseType: String(data.baseType ?? ''),
      compatibleTypes,
      count: typeof data.count === 'number' ? data.count : Object.keys(compatibleTypes).length,
    };
  }

  /**
   * Migrate an object to a new type.
   * This is a NemakiWare-specific extension - CMIS standard does not support changing object types.
   *
   * @param repositoryId Repository ID
   * @param objectId Object ID to migrate
   * @param newTypeId Target type ID
   * @param additionalProperties Additional properties required by the new type
   * @returns Promise resolving to migration result
   */
  async migrateObjectType(
    repositoryId: string,
    objectId: string,
    newTypeId: string,
    additionalProperties?: Record<string, unknown>
  ): Promise<{
    objectId: string;
    previousType: string;
    newType: string;
    changeToken: string;
  }> {
    const headers = this.getAuthHeaders();
    const response = await fetch(
      `/core/api/v1/repo/${repositoryId}/type-migration`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
          ...headers,
        },
        body: JSON.stringify({
          objectId,
          newTypeId,
          additionalProperties,
        }),
      }
    );

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || `Type migration failed: ${response.status}`);
    }

    const data = await response.json();
    if (data.status === 'error') {
      throw new Error(data.message || 'Type migration failed');
    }

    return data;
  }
}
