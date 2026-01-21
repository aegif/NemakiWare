/**
 * BrowserBindingClient - CMIS Browser Binding protocol client
 * 
 * This client handles CMIS Browser Binding operations (POST/JSON).
 * Browser Binding is used for write operations like create, update, delete.
 * 
 * Design Principles:
 * - Uses CmisHttpClient for HTTP operations
 * - Uses browserBindingMappers for response parsing
 * - Returns strongly typed responses
 * - Preserves existing error handling patterns
 * 
 * Browser Binding Operations:
 * - createDocument, createFolder
 * - updateProperties
 * - deleteObject
 * - checkOut, checkIn, cancelCheckOut
 * - setACL
 * - move, copy
 */

import { CmisHttpClient, CmisHttpResponse } from '../http';
import { 
  safeParseJson,
  BrowserBindingObject 
} from '../parsers';

/**
 * Result of a Browser Binding operation
 */
export interface BrowserBindingResult<T = unknown> {
  success: boolean;
  status: number;
  data?: T;
  error?: string;
}

/**
 * CMIS action types for Browser Binding
 */
export type CmisAction = 
  | 'createDocument'
  | 'createFolder'
  | 'updateProperties'
  | 'delete'
  | 'checkOut'
  | 'checkIn'
  | 'cancelCheckOut'
  | 'setContent'
  | 'deleteContent'
  | 'move'
  | 'addObjectToFolder'
  | 'removeObjectFromFolder'
  | 'applyACL'
  | 'createRelationship'
  | 'deleteTree';

/**
 * BrowserBindingClient - Client for CMIS Browser Binding protocol
 */
export class BrowserBindingClient {
  private httpClient: CmisHttpClient;
  private baseUrl: string;

  /**
   * Create a new BrowserBindingClient
   * 
   * @param httpClient CmisHttpClient instance for HTTP operations
   * @param baseUrl Base URL for Browser Binding (e.g., '/core/browser')
   */
  constructor(httpClient: CmisHttpClient, baseUrl: string = '/core/browser') {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
  }

  /**
   * Build the URL for a Browser Binding operation
   * 
   * @param repositoryId Repository ID
   * @param objectId Optional object ID (for operations on existing objects)
   * @returns Full URL for the operation
   */
  private buildUrl(repositoryId: string, objectId?: string): string {
    if (objectId) {
      // Encode object ID for URL safety
      const encodedId = encodeURIComponent(objectId).replace(/%2F/g, '/');
      return `${this.baseUrl}/${repositoryId}/root?objectId=${encodedId}`;
    }
    return `${this.baseUrl}/${repositoryId}/root`;
  }

  /**
   * Execute a Browser Binding action
   * 
   * @param repositoryId Repository ID
   * @param action CMIS action to perform
   * @param formData FormData containing action parameters
   * @param objectId Optional object ID for operations on existing objects
   * @returns Result of the operation
   */
  async executeAction<T = BrowserBindingObject>(
    repositoryId: string,
    action: CmisAction,
    formData: FormData,
    objectId?: string
  ): Promise<BrowserBindingResult<T>> {
    // Add the cmisaction parameter
    formData.append('cmisaction', action);

    const url = this.buildUrl(repositoryId, objectId);

    try {
      const response = await this.httpClient.postFormData(url, formData);
      return this.processResponse<T>(response);
    } catch (error) {
      // Network error
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Process HTTP response into BrowserBindingResult
   */
  private processResponse<T>(response: CmisHttpResponse): BrowserBindingResult<T> {
    // Success status codes
    if (response.status === 200 || response.status === 201) {
      const data = safeParseJson<T>(response.responseText);
      return {
        success: true,
        status: response.status,
        data: data ?? undefined
      };
    }

    // No content (success for delete operations)
    if (response.status === 204) {
      return {
        success: true,
        status: response.status
      };
    }

    // Error response
    const errorData = safeParseJson<{ message?: string; exception?: string }>(response.responseText);
    return {
      success: false,
      status: response.status,
      error: errorData?.message || errorData?.exception || response.statusText
    };
  }

  /**
   * Create a document
   * 
   * @param repositoryId Repository ID
   * @param folderId Parent folder ID
   * @param name Document name
   * @param objectTypeId Document type ID
   * @param properties Additional properties
   * @param content Optional file content
   * @returns Created document data
   */
  async createDocument(
    repositoryId: string,
    folderId: string,
    name: string,
    objectTypeId: string = 'cmis:document',
    properties: Record<string, unknown> = {},
    content?: File
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('objectId', folderId);
    formData.append('propertyId[0]', 'cmis:name');
    formData.append('propertyValue[0]', name);
    formData.append('propertyId[1]', 'cmis:objectTypeId');
    formData.append('propertyValue[1]', objectTypeId);

    // Add additional properties
    let propIndex = 2;
    for (const [key, value] of Object.entries(properties)) {
      if (value !== undefined && value !== null) {
        formData.append(`propertyId[${propIndex}]`, key);
        formData.append(`propertyValue[${propIndex}]`, String(value));
        propIndex++;
      }
    }

    // Add content if provided
    if (content) {
      formData.append('content', content);
    }

    return this.executeAction(repositoryId, 'createDocument', formData, folderId);
  }

  /**
   * Create a folder
   * 
   * @param repositoryId Repository ID
   * @param parentFolderId Parent folder ID
   * @param name Folder name
   * @param objectTypeId Folder type ID
   * @param properties Additional properties
   * @returns Created folder data
   */
  async createFolder(
    repositoryId: string,
    parentFolderId: string,
    name: string,
    objectTypeId: string = 'cmis:folder',
    properties: Record<string, unknown> = {}
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('objectId', parentFolderId);
    formData.append('propertyId[0]', 'cmis:name');
    formData.append('propertyValue[0]', name);
    formData.append('propertyId[1]', 'cmis:objectTypeId');
    formData.append('propertyValue[1]', objectTypeId);

    // Add additional properties
    let propIndex = 2;
    for (const [key, value] of Object.entries(properties)) {
      if (value !== undefined && value !== null) {
        formData.append(`propertyId[${propIndex}]`, key);
        formData.append(`propertyValue[${propIndex}]`, String(value));
        propIndex++;
      }
    }

    return this.executeAction(repositoryId, 'createFolder', formData, parentFolderId);
  }

  /**
   * Update object properties
   * 
   * @param repositoryId Repository ID
   * @param objectId Object ID to update
   * @param properties Properties to update
   * @returns Updated object data
   */
  async updateProperties(
    repositoryId: string,
    objectId: string,
    properties: Record<string, unknown>
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('objectId', objectId);

    let propIndex = 0;
    for (const [key, value] of Object.entries(properties)) {
      formData.append(`propertyId[${propIndex}]`, key);
      if (value === null || value === undefined) {
        // Clear the property
        formData.append(`propertyValue[${propIndex}]`, '');
      } else if (Array.isArray(value)) {
        // Multi-value property
        value.forEach((v, i) => {
          formData.append(`propertyValue[${propIndex}][${i}]`, String(v));
        });
      } else {
        formData.append(`propertyValue[${propIndex}]`, String(value));
      }
      propIndex++;
    }

    return this.executeAction(repositoryId, 'updateProperties', formData, objectId);
  }

  /**
   * Delete an object
   * 
   * @param repositoryId Repository ID
   * @param objectId Object ID to delete
   * @param allVersions Whether to delete all versions (default: true)
   * @returns Result of delete operation
   */
  async deleteObject(
    repositoryId: string,
    objectId: string,
    allVersions: boolean = true
  ): Promise<BrowserBindingResult<void>> {
    const formData = new FormData();
    formData.append('objectId', objectId);
    formData.append('allVersions', String(allVersions));

    return this.executeAction(repositoryId, 'delete', formData, objectId);
  }

  /**
   * Check out a document
   * 
   * @param repositoryId Repository ID
   * @param objectId Document ID to check out
   * @returns Private working copy data
   */
  async checkOut(
    repositoryId: string,
    objectId: string
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('objectId', objectId);

    return this.executeAction(repositoryId, 'checkOut', formData, objectId);
  }

  /**
   * Check in a document
   * 
   * @param repositoryId Repository ID
   * @param objectId Private working copy ID
   * @param major Whether this is a major version
   * @param comment Check-in comment
   * @param content Optional new content
   * @returns Checked-in document data
   */
  async checkIn(
    repositoryId: string,
    objectId: string,
    major: boolean = true,
    comment?: string,
    content?: File
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('objectId', objectId);
    formData.append('major', String(major));
    
    if (comment) {
      formData.append('checkinComment', comment);
    }
    
    if (content) {
      formData.append('content', content);
    }

    return this.executeAction(repositoryId, 'checkIn', formData, objectId);
  }

  /**
   * Cancel check out
   * 
   * @param repositoryId Repository ID
   * @param objectId Private working copy ID
   * @returns Result of cancel operation
   */
  async cancelCheckOut(
    repositoryId: string,
    objectId: string
  ): Promise<BrowserBindingResult<void>> {
    const formData = new FormData();
    formData.append('objectId', objectId);

    return this.executeAction(repositoryId, 'cancelCheckOut', formData, objectId);
  }

  /**
   * Apply ACL to an object
   * 
   * @param repositoryId Repository ID
   * @param objectId Object ID
   * @param addAces ACEs to add
   * @param removeAces ACEs to remove
   * @returns Updated ACL data
   */
  async applyACL(
    repositoryId: string,
    objectId: string,
    addAces: Array<{ principal: string; permissions: string[] }> = [],
    removeAces: Array<{ principal: string; permissions: string[] }> = []
  ): Promise<BrowserBindingResult<unknown>> {
    const formData = new FormData();
    formData.append('objectId', objectId);

    // Add ACEs to add
    addAces.forEach((ace, i) => {
      formData.append(`addACEPrincipal[${i}]`, ace.principal);
      ace.permissions.forEach((perm, j) => {
        formData.append(`addACEPermission[${i}][${j}]`, perm);
      });
    });

    // Add ACEs to remove
    removeAces.forEach((ace, i) => {
      formData.append(`removeACEPrincipal[${i}]`, ace.principal);
      ace.permissions.forEach((perm, j) => {
        formData.append(`removeACEPermission[${i}][${j}]`, perm);
      });
    });

    return this.executeAction(repositoryId, 'applyACL', formData, objectId);
  }

  /**
   * Set content stream
   * 
   * @param repositoryId Repository ID
   * @param objectId Document ID
   * @param content File content
   * @param overwrite Whether to overwrite existing content
   * @returns Updated document data
   */
  async setContentStream(
    repositoryId: string,
    objectId: string,
    content: File,
    overwrite: boolean = true
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('objectId', objectId);
    formData.append('content', content);
    formData.append('overwriteFlag', String(overwrite));

    return this.executeAction(repositoryId, 'setContent', formData, objectId);
  }

  /**
   * Delete content stream
   * 
   * @param repositoryId Repository ID
   * @param objectId Document ID
   * @returns Result of delete operation
   */
  async deleteContentStream(
    repositoryId: string,
    objectId: string
  ): Promise<BrowserBindingResult<void>> {
    const formData = new FormData();
    formData.append('objectId', objectId);

    return this.executeAction(repositoryId, 'deleteContent', formData, objectId);
  }

  /**
   * Create a relationship
   * 
   * @param repositoryId Repository ID
   * @param sourceId Source object ID
   * @param targetId Target object ID
   * @param relationshipTypeId Relationship type ID
   * @returns Created relationship data
   */
  async createRelationship(
    repositoryId: string,
    sourceId: string,
    targetId: string,
    relationshipTypeId: string
  ): Promise<BrowserBindingResult<BrowserBindingObject>> {
    const formData = new FormData();
    formData.append('propertyId[0]', 'cmis:objectTypeId');
    formData.append('propertyValue[0]', relationshipTypeId);
    formData.append('propertyId[1]', 'cmis:sourceId');
    formData.append('propertyValue[1]', sourceId);
    formData.append('propertyId[2]', 'cmis:targetId');
    formData.append('propertyValue[2]', targetId);

    return this.executeAction(repositoryId, 'createRelationship', formData);
  }
}
