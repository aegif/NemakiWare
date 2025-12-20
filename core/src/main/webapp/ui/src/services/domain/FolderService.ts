/**
 * FolderService - Domain service for folder operations
 * 
 * This service provides a high-level API for folder-related CMIS operations.
 * It uses BrowserBindingClient for write operations and AtomPubClient for read operations.
 * 
 * Operations:
 * - Create folder
 * - Update folder properties
 * - Delete folder
 * - Get children
 * - Get folder tree
 * - Move folder
 */

import { BrowserBindingClient } from '../clients/BrowserBindingClient';
import { AtomPubClient, AtomPubPagination } from '../clients/AtomPubClient';
import { CmisHttpClient } from '../http';
import { ParsedAtomEntry } from '../parsers';

/**
 * Folder creation options
 */
export interface CreateFolderOptions {
  name: string;
  objectTypeId?: string;
  properties?: Record<string, unknown>;
  secondaryTypeIds?: string[];
}

/**
 * Folder update options
 */
export interface UpdateFolderOptions {
  properties: Record<string, unknown>;
}

/**
 * Get children options
 */
export interface GetChildrenOptions {
  maxItems?: number;
  skipCount?: number;
  orderBy?: string;
  filter?: string;
  includeAllowableActions?: boolean;
}

/**
 * Folder with parsed properties
 */
export interface Folder {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  parentId?: string;
  path?: string;
  createdBy?: string;
  lastModifiedBy?: string;
  creationDate?: string;
  lastModificationDate?: string;
  properties: Record<string, unknown>;
  allowableActions: string[];
}

/**
 * Child item (document or folder)
 */
export interface ChildItem {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  isFolder: boolean;
  properties: Record<string, unknown>;
  allowableActions: string[];
}

/**
 * Children result with pagination
 */
export interface ChildrenResult {
  items: ChildItem[];
  pagination: AtomPubPagination;
}

/**
 * FolderService - High-level API for folder operations
 */
export class FolderService {
  private browserClient: BrowserBindingClient;
  private atomClient: AtomPubClient;
  private repositoryId: string;

  /**
   * Create a new FolderService
   * 
   * @param httpClient CmisHttpClient instance
   * @param repositoryId Repository ID
   * @param browserBaseUrl Base URL for Browser Binding (default: '/core/browser')
   * @param atomBaseUrl Base URL for AtomPub (default: '/core/atom')
   */
  constructor(
    httpClient: CmisHttpClient,
    repositoryId: string,
    browserBaseUrl: string = '/core/browser',
    atomBaseUrl: string = '/core/atom'
  ) {
    this.browserClient = new BrowserBindingClient(httpClient, browserBaseUrl);
    this.atomClient = new AtomPubClient(httpClient, atomBaseUrl);
    this.repositoryId = repositoryId;
  }

  /**
   * Set the repository ID
   */
  setRepositoryId(repositoryId: string): void {
    this.repositoryId = repositoryId;
  }

  /**
   * Convert parsed entry to Folder
   */
  private entryToFolder(entry: ParsedAtomEntry): Folder {
    const props = entry.properties;
    return {
      id: String(props['cmis:objectId'] || ''),
      name: String(props['cmis:name'] || 'Unknown'),
      objectType: String(props['cmis:objectTypeId'] || 'cmis:folder'),
      baseType: String(props['cmis:baseTypeId'] || 'cmis:folder'),
      parentId: props['cmis:parentId'] as string | undefined,
      path: props['cmis:path'] as string | undefined,
      createdBy: props['cmis:createdBy'] as string | undefined,
      lastModifiedBy: props['cmis:lastModifiedBy'] as string | undefined,
      creationDate: props['cmis:creationDate'] as string | undefined,
      lastModificationDate: props['cmis:lastModificationDate'] as string | undefined,
      properties: props,
      allowableActions: entry.allowableActions
    };
  }

  /**
   * Convert parsed entry to ChildItem
   */
  private entryToChildItem(entry: ParsedAtomEntry): ChildItem {
    const props = entry.properties;
    const baseType = String(props['cmis:baseTypeId'] || '');
    return {
      id: String(props['cmis:objectId'] || ''),
      name: String(props['cmis:name'] || 'Unknown'),
      objectType: String(props['cmis:objectTypeId'] || ''),
      baseType,
      isFolder: baseType === 'cmis:folder',
      properties: props,
      allowableActions: entry.allowableActions
    };
  }

  /**
   * Create a new folder
   * 
   * @param parentFolderId Parent folder ID
   * @param options Folder creation options
   * @returns Created folder or error
   */
  async createFolder(
    parentFolderId: string,
    options: CreateFolderOptions
  ): Promise<{ success: boolean; folder?: Folder; error?: string }> {
    const { name, objectTypeId = 'cmis:folder', properties = {}, secondaryTypeIds } = options;

    // Add secondary type IDs to properties if provided
    const allProperties = { ...properties };
    if (secondaryTypeIds && secondaryTypeIds.length > 0) {
      allProperties['cmis:secondaryObjectTypeIds'] = secondaryTypeIds;
    }

    const result = await this.browserClient.createFolder(
      this.repositoryId,
      parentFolderId,
      name,
      objectTypeId,
      allProperties
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the created folder to get full properties
    if (result.data?.succinctProperties?.['cmis:objectId']) {
      const objectId = String(result.data.succinctProperties['cmis:objectId']);
      const getResult = await this.getFolder(objectId);
      if (getResult.success && getResult.folder) {
        return { success: true, folder: getResult.folder };
      }
    }

    return { success: true };
  }

  /**
   * Get a folder by ID
   * 
   * @param objectId Folder ID
   * @returns Folder or error
   */
  async getFolder(
    objectId: string
  ): Promise<{ success: boolean; folder?: Folder; error?: string }> {
    const result = await this.atomClient.getObject(this.repositoryId, objectId);

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    return { success: true, folder: this.entryToFolder(result.data) };
  }

  /**
   * Get a folder by path
   * 
   * @param path Folder path (e.g., '/folder1/folder2')
   * @returns Folder or error
   */
  async getFolderByPath(
    path: string
  ): Promise<{ success: boolean; folder?: Folder; error?: string }> {
    const result = await this.atomClient.getObjectByPath(this.repositoryId, path);

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    return { success: true, folder: this.entryToFolder(result.data) };
  }

  /**
   * Update folder properties
   * 
   * @param objectId Folder ID
   * @param options Update options
   * @returns Updated folder or error
   */
  async updateFolder(
    objectId: string,
    options: UpdateFolderOptions
  ): Promise<{ success: boolean; folder?: Folder; error?: string }> {
    const result = await this.browserClient.updateProperties(
      this.repositoryId,
      objectId,
      options.properties
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the updated folder
    return this.getFolder(objectId);
  }

  /**
   * Delete a folder
   * 
   * @param objectId Folder ID
   * @param allVersions Whether to delete all versions (default: true)
   * @returns Success or error
   */
  async deleteFolder(
    objectId: string,
    allVersions: boolean = true
  ): Promise<{ success: boolean; error?: string }> {
    const result = await this.browserClient.deleteObject(
      this.repositoryId,
      objectId,
      allVersions
    );

    return { success: result.success, error: result.error };
  }

  /**
   * Get children of a folder
   * 
   * @param folderId Folder ID
   * @param options Query options
   * @returns Children result with pagination
   */
  async getChildren(
    folderId: string,
    options: GetChildrenOptions = {}
  ): Promise<{ success: boolean; result?: ChildrenResult; error?: string }> {
    const atomResult = await this.atomClient.getChildren(this.repositoryId, folderId, {
      maxItems: options.maxItems,
      skipCount: options.skipCount,
      orderBy: options.orderBy,
      filter: options.filter,
      includeAllowableActions: options.includeAllowableActions
    });

    if (!atomResult.success || !atomResult.data) {
      return { success: false, error: atomResult.error };
    }

    const items = atomResult.data.entries.map(entry => this.entryToChildItem(entry));
    return {
      success: true,
      result: {
        items,
        pagination: atomResult.data.pagination
      }
    };
  }

  /**
   * Get the root folder
   * 
   * @returns Root folder or error
   */
  async getRootFolder(): Promise<{ success: boolean; folder?: Folder; error?: string }> {
    const result = await this.atomClient.getRootFolder(this.repositoryId);

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    return { success: true, folder: this.entryToFolder(result.data) };
  }

  /**
   * Get parent folders of an object
   * 
   * @param objectId Object ID
   * @returns Array of parent folders or error
   */
  async getParents(
    objectId: string
  ): Promise<{ success: boolean; parents?: Folder[]; error?: string }> {
    const result = await this.atomClient.getObjectParents(this.repositoryId, objectId);

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    const parents = result.data.map(entry => this.entryToFolder(entry));
    return { success: true, parents };
  }
}
