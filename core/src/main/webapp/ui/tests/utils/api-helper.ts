import { Page, APIRequestContext } from '@playwright/test';

/**
 * API Helper Utilities for NemakiWare Playwright E2E Tests
 *
 * Provides API-based operations for test setup and cleanup:
 * - CMIS Browser Binding API operations
 * - Fast folder/document creation and deletion
 * - ACL management via API
 * - Test data cleanup utilities
 *
 * Benefits over UI-based operations:
 * - Much faster execution (no UI rendering waits)
 * - More reliable (no UI timing issues)
 * - Better for setup/cleanup operations
 * - Reduces test flakiness
 *
 * Usage:
 * ```typescript
 * const apiHelper = new ApiHelper(page);
 * const folderId = await apiHelper.createFolder('test-folder');
 * await apiHelper.deleteObject(folderId);
 * ```
 */

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';

export interface CreateFolderOptions {
  parentId?: string;
  name: string;
  repositoryId?: string;
}

export interface CreateDocumentOptions {
  parentId?: string;
  name: string;
  content?: string;
  contentType?: string;
  repositoryId?: string;
}

export interface ApiHelperOptions {
  username?: string;
  password?: string;
  repositoryId?: string;
}

export class ApiHelper {
  private page: Page;
  private authHeader: string;
  private repositoryId: string;

  constructor(page: Page, options: ApiHelperOptions = {}) {
    this.page = page;
    const username = options.username || 'admin';
    const password = options.password || 'admin';
    this.authHeader = 'Basic ' + Buffer.from(`${username}:${password}`).toString('base64');
    this.repositoryId = options.repositoryId || REPOSITORY_ID;
  }

  /**
   * Get the root folder ID for the repository
   */
  async getRootFolderId(): Promise<string> {
    const response = await this.page.request.get(
      `${BASE_URL}/core/browser/${this.repositoryId}?cmisselector=repositoryInfo`,
      { headers: { 'Authorization': this.authHeader } }
    );

    if (!response.ok()) {
      throw new Error(`Failed to get repository info: ${response.status()}`);
    }

    const data = await response.json();
    return data[this.repositoryId]?.rootFolderId;
  }

  /**
   * Create a folder via CMIS API
   * Returns the folder ID
   */
  async createFolder(options: CreateFolderOptions): Promise<string> {
    const parentId = options.parentId || await this.getRootFolderId();
    const repositoryId = options.repositoryId || this.repositoryId;

    const response = await this.page.request.post(
      `${BASE_URL}/core/browser/${repositoryId}`,
      {
        headers: { 'Authorization': this.authHeader },
        form: {
          'cmisaction': 'createFolder',
          'objectId': parentId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': options.name
        }
      }
    );

    if (!response.ok()) {
      const errorText = await response.text();
      throw new Error(`Failed to create folder: ${response.status()} - ${errorText}`);
    }

    const data = await response.json();
    return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
  }

  /**
   * Create a document via CMIS API
   * Returns the document ID
   */
  async createDocument(options: CreateDocumentOptions): Promise<string> {
    const parentId = options.parentId || await this.getRootFolderId();
    const repositoryId = options.repositoryId || this.repositoryId;
    const content = options.content || 'Test content';
    const contentType = options.contentType || 'text/plain';

    const response = await this.page.request.post(
      `${BASE_URL}/core/browser/${repositoryId}`,
      {
        headers: { 'Authorization': this.authHeader },
        multipart: {
          'cmisaction': 'createDocument',
          'objectId': parentId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': options.name,
          'content': {
            name: options.name,
            mimeType: contentType,
            buffer: Buffer.from(content)
          }
        }
      }
    );

    if (!response.ok()) {
      const errorText = await response.text();
      throw new Error(`Failed to create document: ${response.status()} - ${errorText}`);
    }

    const data = await response.json();
    return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
  }

  /**
   * Delete an object (document or folder) via CMIS API
   */
  async deleteObject(objectId: string, allVersions: boolean = true): Promise<boolean> {
    try {
      const response = await this.page.request.post(
        `${BASE_URL}/core/browser/${this.repositoryId}`,
        {
          headers: { 'Authorization': this.authHeader },
          form: {
            'cmisaction': 'delete',
            'objectId': objectId,
            'allVersions': allVersions.toString()
          }
        }
      );
      return response.ok();
    } catch (error) {
      console.log(`ApiHelper: Failed to delete object ${objectId}:`, error);
      return false;
    }
  }

  /**
   * Delete a folder tree via CMIS API (including all contents)
   */
  async deleteFolderTree(folderId: string): Promise<boolean> {
    try {
      const response = await this.page.request.post(
        `${BASE_URL}/core/browser/${this.repositoryId}`,
        {
          headers: { 'Authorization': this.authHeader },
          form: {
            'cmisaction': 'deleteTree',
            'objectId': folderId,
            'allVersions': 'true',
            'continueOnFailure': 'true'
          }
        }
      );
      return response.ok();
    } catch (error) {
      console.log(`ApiHelper: Failed to delete folder tree ${folderId}:`, error);
      return false;
    }
  }

  /**
   * Query for objects matching a pattern
   * Returns array of object IDs
   */
  async queryObjects(cmisQuery: string): Promise<string[]> {
    try {
      const response = await this.page.request.get(
        `${BASE_URL}/core/browser/${this.repositoryId}?cmisselector=query&q=${encodeURIComponent(cmisQuery)}`,
        { headers: { 'Authorization': this.authHeader } }
      );

      if (!response.ok()) {
        return [];
      }

      const data = await response.json();
      const results = data.results || [];
      return results.map((r: any) => 
        r.properties?.['cmis:objectId']?.value || r.succinctProperties?.['cmis:objectId']
      ).filter(Boolean);
    } catch (error) {
      console.log(`ApiHelper: Query failed:`, error);
      return [];
    }
  }

  /**
   * Clean up test folders matching a pattern
   * Useful for afterEach/afterAll cleanup
   */
  async cleanupTestFolders(namePattern: string, maxDeletions: number = 10): Promise<number> {
    const query = `SELECT cmis:objectId FROM cmis:folder WHERE cmis:name LIKE '${namePattern}'`;
    const folderIds = await this.queryObjects(query);
    
    let deletedCount = 0;
    for (const folderId of folderIds.slice(0, maxDeletions)) {
      if (await this.deleteFolderTree(folderId)) {
        deletedCount++;
      }
    }
    
    return deletedCount;
  }

  /**
   * Clean up test documents matching a pattern
   */
  async cleanupTestDocuments(namePattern: string, maxDeletions: number = 10): Promise<number> {
    const query = `SELECT cmis:objectId FROM cmis:document WHERE cmis:name LIKE '${namePattern}'`;
    const docIds = await this.queryObjects(query);
    
    let deletedCount = 0;
    for (const docId of docIds.slice(0, maxDeletions)) {
      if (await this.deleteObject(docId)) {
        deletedCount++;
      }
    }
    
    return deletedCount;
  }

  /**
   * Get object properties via CMIS API
   */
  async getObject(objectId: string): Promise<any> {
    const response = await this.page.request.get(
      `${BASE_URL}/core/browser/${this.repositoryId}/${objectId}?cmisselector=object`,
      { headers: { 'Authorization': this.authHeader } }
    );

    if (!response.ok()) {
      return null;
    }

    return await response.json();
  }

  /**
   * Check if an object exists
   */
  async objectExists(objectId: string): Promise<boolean> {
    const obj = await this.getObject(objectId);
    return obj !== null;
  }

  /**
   * Apply ACL to an object
   */
  async applyAcl(objectId: string, principal: string, permission: string): Promise<boolean> {
    try {
      const response = await this.page.request.post(
        `${BASE_URL}/core/browser/${this.repositoryId}`,
        {
          headers: { 'Authorization': this.authHeader },
          form: {
            'cmisaction': 'applyACL',
            'objectId': objectId,
            'addACEPrincipal[0]': principal,
            'addACEPermission[0][0]': permission
          }
        }
      );
      return response.ok();
    } catch (error) {
      console.log(`ApiHelper: Failed to apply ACL:`, error);
      return false;
    }
  }

  /**
   * Get children of a folder
   */
  async getChildren(folderId: string): Promise<any[]> {
    const response = await this.page.request.get(
      `${BASE_URL}/core/browser/${this.repositoryId}/${folderId}?cmisselector=children`,
      { headers: { 'Authorization': this.authHeader } }
    );

    if (!response.ok()) {
      return [];
    }

    const data = await response.json();
    return data.objects || [];
  }

  /**
   * Create a user via REST API
   */
  async createUser(userId: string, password: string, isAdmin: boolean = false): Promise<boolean> {
    try {
      const response = await this.page.request.post(
        `${BASE_URL}/core/rest/repo/${this.repositoryId}/user/create`,
        {
          headers: {
            'Authorization': this.authHeader,
            'Content-Type': 'application/json'
          },
          data: {
            id: userId,
            name: userId,
            firstName: 'Test',
            lastName: 'User',
            email: `${userId}@example.com`,
            password: password,
            admin: isAdmin
          }
        }
      );
      return response.ok();
    } catch (error) {
      console.log(`ApiHelper: Failed to create user ${userId}:`, error);
      return false;
    }
  }

  /**
   * Delete a user via REST API
   */
  async deleteUser(userId: string): Promise<boolean> {
    try {
      const response = await this.page.request.delete(
        `${BASE_URL}/core/rest/repo/${this.repositoryId}/user/${encodeURIComponent(userId)}`,
        { headers: { 'Authorization': this.authHeader } }
      );
      if (!response.ok()) {
        const errorBody = (await response.text().catch(() => '')).slice(0, 200);
        console.log(`ApiHelper: Failed to delete user ${userId}: ${response.status()} ${errorBody}`);
        return false;
      }
      return true;
    } catch (error) {
      console.log(`ApiHelper: Failed to delete user ${userId}:`, error);
      return false;
    }
  }

  /**
   * Delete a group via REST API
   */
  async deleteGroup(groupId: string): Promise<boolean> {
    try {
      const response = await this.page.request.delete(
        `${BASE_URL}/core/rest/repo/${this.repositoryId}/group/${encodeURIComponent(groupId)}`,
        { headers: { 'Authorization': this.authHeader } }
      );
      if (!response.ok()) {
        const errorBody = (await response.text().catch(() => '')).slice(0, 200);
        console.log(`ApiHelper: Failed to delete group ${groupId}: ${response.status()} ${errorBody}`);
        return false;
      }
      return true;
    } catch (error) {
      console.log(`ApiHelper: Failed to delete group ${groupId}:`, error);
      return false;
    }
  }

  /**
   * Delete a type definition via REST API
   */
  async deleteType(typeId: string): Promise<boolean> {
    try {
      const response = await this.page.request.delete(
        `${BASE_URL}/core/rest/repo/${this.repositoryId}/type/${encodeURIComponent(typeId)}`,
        { headers: { 'Authorization': this.authHeader } }
      );
      if (!response.ok()) {
        const errorBody = (await response.text().catch(() => '')).slice(0, 200);
        console.log(`ApiHelper: Failed to delete type ${typeId}: ${response.status()} ${errorBody}`);
        return false;
      }
      return true;
    } catch (error) {
      console.log(`ApiHelper: Failed to delete type ${typeId}:`, error);
      return false;
    }
  }

  /**
   * Clean up test groups matching a prefix pattern.
   * Deletes all matching groups by default (maxDeletions=0) to ensure
   * no leftover test groups cause flakiness in subsequent test runs.
   * @param idPrefix - Prefix to match (uses startsWith for safety)
   * @param maxDeletions - Maximum number of groups to delete (0 = unlimited)
   */
  async cleanupTestGroups(idPrefix: string, maxDeletions: number = 0): Promise<number> {
    try {
      // Get all groups
      const response = await this.page.request.get(
        `${BASE_URL}/core/rest/repo/${this.repositoryId}/group/list`,
        { headers: { 'Authorization': this.authHeader } }
      );

      if (!response.ok()) {
        return 0;
      }

      const data = await response.json();
      const groups = data.groups || data.result || [];

      // Filter matching groups and sort by ID for deterministic deletion order
      const matchingGroups = groups
        .map((g: any) => g.groupId || g.id)
        .filter((id: string) => id && id.startsWith(idPrefix))
        .sort();

      // Apply limit if specified (0 = delete all)
      const groupsToDelete = maxDeletions > 0 ? matchingGroups.slice(0, maxDeletions) : matchingGroups;

      let deletedCount = 0;
      for (const groupId of groupsToDelete) {
        if (await this.deleteGroup(groupId)) {
          deletedCount++;
        }
      }

      return deletedCount;
    } catch (error) {
      console.log(`ApiHelper: Failed to cleanup test groups:`, error);
      return 0;
    }
  }
}

/**
 * Generate a unique test ID for test data isolation
 */
export function generateTestId(): string {
  return Math.random().toString(36).substring(2, 10);
}

/**
 * Generate a unique test folder name
 */
export function generateTestFolderName(prefix: string = 'test-folder'): string {
  return `${prefix}-${generateTestId()}`;
}

/**
 * Generate a unique test document name
 */
export function generateTestDocumentName(prefix: string = 'test-doc', extension: string = 'txt'): string {
  return `${prefix}-${generateTestId()}.${extension}`;
}
