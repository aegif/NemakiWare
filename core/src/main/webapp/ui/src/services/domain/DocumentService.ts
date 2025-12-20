/**
 * DocumentService - Domain service for document operations
 * 
 * This service provides a high-level API for document-related CMIS operations.
 * It uses BrowserBindingClient for write operations and AtomPubClient for read operations.
 * 
 * Operations:
 * - Create document
 * - Update document properties
 * - Delete document
 * - Get/Set content stream
 * - Version management (checkOut, checkIn, cancelCheckOut, getVersionHistory)
 * - Get renditions
 */

import { BrowserBindingClient } from '../clients/BrowserBindingClient';
import { AtomPubClient } from '../clients/AtomPubClient';
import { CmisHttpClient } from '../http';
import { ParsedAtomEntry } from '../parsers';

/**
 * Document creation options
 */
export interface CreateDocumentOptions {
  name: string;
  objectTypeId?: string;
  properties?: Record<string, unknown>;
  content?: File;
  secondaryTypeIds?: string[];
}

/**
 * Document update options
 */
export interface UpdateDocumentOptions {
  properties: Record<string, unknown>;
}

/**
 * Check-in options
 */
export interface CheckInOptions {
  major?: boolean;
  comment?: string;
  content?: File;
}

/**
 * Document with parsed properties
 */
export interface Document {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  createdBy?: string;
  lastModifiedBy?: string;
  creationDate?: string;
  lastModificationDate?: string;
  contentStreamLength?: number;
  contentStreamMimeType?: string;
  contentStreamFileName?: string;
  isLatestVersion?: boolean;
  isLatestMajorVersion?: boolean;
  isMajorVersion?: boolean;
  versionLabel?: string;
  versionSeriesId?: string;
  isVersionSeriesCheckedOut?: boolean;
  versionSeriesCheckedOutBy?: string;
  versionSeriesCheckedOutId?: string;
  checkinComment?: string;
  properties: Record<string, unknown>;
  allowableActions: string[];
}

/**
 * DocumentService - High-level API for document operations
 */
export class DocumentService {
  private browserClient: BrowserBindingClient;
  private atomClient: AtomPubClient;
  private repositoryId: string;

  /**
   * Create a new DocumentService
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
   * Convert parsed entry to Document
   */
  private entryToDocument(entry: ParsedAtomEntry): Document {
    const props = entry.properties;
    return {
      id: String(props['cmis:objectId'] || ''),
      name: String(props['cmis:name'] || 'Unknown'),
      objectType: String(props['cmis:objectTypeId'] || 'cmis:document'),
      baseType: String(props['cmis:baseTypeId'] || 'cmis:document'),
      createdBy: props['cmis:createdBy'] as string | undefined,
      lastModifiedBy: props['cmis:lastModifiedBy'] as string | undefined,
      creationDate: props['cmis:creationDate'] as string | undefined,
      lastModificationDate: props['cmis:lastModificationDate'] as string | undefined,
      contentStreamLength: props['cmis:contentStreamLength'] as number | undefined,
      contentStreamMimeType: props['cmis:contentStreamMimeType'] as string | undefined,
      contentStreamFileName: props['cmis:contentStreamFileName'] as string | undefined,
      isLatestVersion: props['cmis:isLatestVersion'] as boolean | undefined,
      isLatestMajorVersion: props['cmis:isLatestMajorVersion'] as boolean | undefined,
      isMajorVersion: props['cmis:isMajorVersion'] as boolean | undefined,
      versionLabel: props['cmis:versionLabel'] as string | undefined,
      versionSeriesId: props['cmis:versionSeriesId'] as string | undefined,
      isVersionSeriesCheckedOut: props['cmis:isVersionSeriesCheckedOut'] as boolean | undefined,
      versionSeriesCheckedOutBy: props['cmis:versionSeriesCheckedOutBy'] as string | undefined,
      versionSeriesCheckedOutId: props['cmis:versionSeriesCheckedOutId'] as string | undefined,
      checkinComment: props['cmis:checkinComment'] as string | undefined,
      properties: props,
      allowableActions: entry.allowableActions
    };
  }

  /**
   * Create a new document
   * 
   * @param folderId Parent folder ID
   * @param options Document creation options
   * @returns Created document or error
   */
  async createDocument(
    folderId: string,
    options: CreateDocumentOptions
  ): Promise<{ success: boolean; document?: Document; error?: string }> {
    const { name, objectTypeId = 'cmis:document', properties = {}, content, secondaryTypeIds } = options;

    // Add secondary type IDs to properties if provided
    const allProperties = { ...properties };
    if (secondaryTypeIds && secondaryTypeIds.length > 0) {
      allProperties['cmis:secondaryObjectTypeIds'] = secondaryTypeIds;
    }

    const result = await this.browserClient.createDocument(
      this.repositoryId,
      folderId,
      name,
      objectTypeId,
      allProperties,
      content
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the created document to get full properties
    if (result.data?.succinctProperties?.['cmis:objectId']) {
      const objectId = String(result.data.succinctProperties['cmis:objectId']);
      const getResult = await this.getDocument(objectId);
      if (getResult.success && getResult.document) {
        return { success: true, document: getResult.document };
      }
    }

    return { success: true };
  }

  /**
   * Get a document by ID
   * 
   * @param objectId Document ID
   * @returns Document or error
   */
  async getDocument(
    objectId: string
  ): Promise<{ success: boolean; document?: Document; error?: string }> {
    const result = await this.atomClient.getObject(this.repositoryId, objectId);

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    return { success: true, document: this.entryToDocument(result.data) };
  }

  /**
   * Update document properties
   * 
   * @param objectId Document ID
   * @param options Update options
   * @returns Updated document or error
   */
  async updateDocument(
    objectId: string,
    options: UpdateDocumentOptions
  ): Promise<{ success: boolean; document?: Document; error?: string }> {
    const result = await this.browserClient.updateProperties(
      this.repositoryId,
      objectId,
      options.properties
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the updated document
    return this.getDocument(objectId);
  }

  /**
   * Delete a document
   * 
   * @param objectId Document ID
   * @param allVersions Whether to delete all versions (default: true)
   * @returns Success or error
   */
  async deleteDocument(
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
   * Set content stream
   * 
   * @param objectId Document ID
   * @param content File content
   * @param overwrite Whether to overwrite existing content (default: true)
   * @returns Updated document or error
   */
  async setContentStream(
    objectId: string,
    content: File,
    overwrite: boolean = true
  ): Promise<{ success: boolean; document?: Document; error?: string }> {
    const result = await this.browserClient.setContentStream(
      this.repositoryId,
      objectId,
      content,
      overwrite
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the updated document
    return this.getDocument(objectId);
  }

  /**
   * Delete content stream
   * 
   * @param objectId Document ID
   * @returns Success or error
   */
  async deleteContentStream(
    objectId: string
  ): Promise<{ success: boolean; error?: string }> {
    const result = await this.browserClient.deleteContentStream(
      this.repositoryId,
      objectId
    );

    return { success: result.success, error: result.error };
  }

  /**
   * Check out a document
   * 
   * @param objectId Document ID
   * @returns Private working copy or error
   */
  async checkOut(
    objectId: string
  ): Promise<{ success: boolean; pwc?: Document; error?: string }> {
    const result = await this.browserClient.checkOut(this.repositoryId, objectId);

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the PWC
    if (result.data?.succinctProperties?.['cmis:objectId']) {
      const pwcId = String(result.data.succinctProperties['cmis:objectId']);
      const getResult = await this.getDocument(pwcId);
      if (getResult.success && getResult.document) {
        return { success: true, pwc: getResult.document };
      }
    }

    return { success: true };
  }

  /**
   * Check in a document
   * 
   * @param objectId Private working copy ID
   * @param options Check-in options
   * @returns Checked-in document or error
   */
  async checkIn(
    objectId: string,
    options: CheckInOptions = {}
  ): Promise<{ success: boolean; document?: Document; error?: string }> {
    const { major = true, comment, content } = options;

    const result = await this.browserClient.checkIn(
      this.repositoryId,
      objectId,
      major,
      comment,
      content
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the checked-in document
    if (result.data?.succinctProperties?.['cmis:objectId']) {
      const docId = String(result.data.succinctProperties['cmis:objectId']);
      return this.getDocument(docId);
    }

    return { success: true };
  }

  /**
   * Cancel check out
   * 
   * @param objectId Private working copy ID
   * @returns Success or error
   */
  async cancelCheckOut(
    objectId: string
  ): Promise<{ success: boolean; error?: string }> {
    const result = await this.browserClient.cancelCheckOut(
      this.repositoryId,
      objectId
    );

    return { success: result.success, error: result.error };
  }

  /**
   * Get version history
   * 
   * @param objectId Document ID
   * @returns Array of document versions or error
   */
  async getVersionHistory(
    objectId: string
  ): Promise<{ success: boolean; versions?: Document[]; error?: string }> {
    const result = await this.atomClient.getVersionHistory(this.repositoryId, objectId);

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    const versions = result.data.map(entry => this.entryToDocument(entry));
    return { success: true, versions };
  }

  /**
   * Get download URL for document content
   * 
   * @param objectId Document ID
   * @returns Download URL
   */
  getDownloadUrl(objectId: string): string {
    const encodedId = encodeURIComponent(objectId);
    return `/core/browser/${this.repositoryId}/root?objectId=${encodedId}&cmisselector=content`;
  }
}
