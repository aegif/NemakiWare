/**
 * AtomPubClient - CMIS AtomPub Binding protocol client
 * 
 * This client handles CMIS AtomPub Binding operations (GET/XML).
 * AtomPub Binding is used for read operations like getChildren, getObject, search.
 * 
 * Design Principles:
 * - Uses CmisHttpClient for HTTP operations
 * - Uses atompubParsers for response parsing
 * - Returns strongly typed responses
 * - Preserves existing error handling patterns
 * 
 * AtomPub Operations:
 * - getChildren (feed of entries)
 * - getObject (single entry)
 * - getObjectByPath
 * - getVersionHistory
 * - getRelationships
 * - query (search)
 */

import { CmisHttpClient, CmisHttpResponse } from '../http';
import { 
  parseXmlString,
  getAtomEntries,
  parseAtomEntry,
  ParsedAtomEntry
} from '../parsers';

/**
 * Result of an AtomPub operation
 */
export interface AtomPubResult<T = unknown> {
  success: boolean;
  status: number;
  data?: T;
  error?: string;
}

/**
 * Pagination info from AtomPub feed
 */
export interface AtomPubPagination {
  numItems?: number;
  hasMoreItems: boolean;
  skipCount: number;
  maxItems: number;
}

/**
 * AtomPub feed result with pagination
 */
export interface AtomPubFeedResult {
  entries: ParsedAtomEntry[];
  pagination: AtomPubPagination;
}

/**
 * AtomPubClient - Client for CMIS AtomPub Binding protocol
 */
export class AtomPubClient {
  private httpClient: CmisHttpClient;
  private baseUrl: string;

  /**
   * Create a new AtomPubClient
   * 
   * @param httpClient CmisHttpClient instance for HTTP operations
   * @param baseUrl Base URL for AtomPub (e.g., '/core/atom')
   */
  constructor(httpClient: CmisHttpClient, baseUrl: string = '/core/atom') {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
  }

  /**
   * Build query string from parameters
   */
  private buildQueryString(params: Record<string, string | number | boolean | undefined>): string {
    const filtered = Object.entries(params)
      .filter(([_, v]) => v !== undefined && v !== null)
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
    
    return filtered.length > 0 ? `?${filtered.join('&')}` : '';
  }

  /**
   * Extract pagination info from AtomPub feed
   */
  private extractPagination(xmlDoc: Document, skipCount: number, maxItems: number): AtomPubPagination {
    // Try to get numItems from cmisra:numItems
    const numItemsElem = xmlDoc.getElementsByTagName('cmisra:numItems')[0] ||
                        xmlDoc.getElementsByTagName('numItems')[0];
    const numItems = numItemsElem ? parseInt(numItemsElem.textContent || '0', 10) : undefined;

    // Check for next link to determine hasMoreItems
    const links = xmlDoc.getElementsByTagName('link');
    let hasMoreItems = false;
    for (let i = 0; i < links.length; i++) {
      if (links[i].getAttribute('rel') === 'next') {
        hasMoreItems = true;
        break;
      }
    }

    // If we have numItems, we can calculate hasMoreItems more accurately
    if (numItems !== undefined) {
      hasMoreItems = (skipCount + maxItems) < numItems;
    }

    return {
      numItems,
      hasMoreItems,
      skipCount,
      maxItems
    };
  }

  /**
   * Process XML response into AtomPubResult
   */
  private processXmlResponse<T>(
    response: CmisHttpResponse,
    parser: (xmlDoc: Document) => T | null
  ): AtomPubResult<T> {
    if (response.status !== 200) {
      return {
        success: false,
        status: response.status,
        error: response.statusText || `HTTP ${response.status}`
      };
    }

    const xmlDoc = parseXmlString(response.responseText);
    if (!xmlDoc) {
      return {
        success: false,
        status: response.status,
        error: 'Invalid XML response'
      };
    }

    const data = parser(xmlDoc);
    if (data === null) {
      return {
        success: false,
        status: response.status,
        error: 'Failed to parse response'
      };
    }

    return {
      success: true,
      status: response.status,
      data
    };
  }

  /**
   * Get children of a folder
   * 
   * @param repositoryId Repository ID
   * @param folderId Folder ID
   * @param options Query options
   * @returns Feed result with entries and pagination
   */
  async getChildren(
    repositoryId: string,
    folderId: string,
    options: {
      maxItems?: number;
      skipCount?: number;
      orderBy?: string;
      filter?: string;
      includeAllowableActions?: boolean;
      includeRelationships?: 'none' | 'source' | 'target' | 'both';
    } = {}
  ): Promise<AtomPubResult<AtomPubFeedResult>> {
    const {
      maxItems = 100,
      skipCount = 0,
      orderBy,
      filter,
      includeAllowableActions = true,
      includeRelationships = 'none'
    } = options;

    const queryString = this.buildQueryString({
      maxItems,
      skipCount,
      orderBy,
      filter,
      includeAllowableActions,
      includeRelationships
    });

    const encodedFolderId = encodeURIComponent(folderId);
    const url = `${this.baseUrl}/${repositoryId}/children?id=${encodedFolderId}${queryString ? '&' + queryString.slice(1) : ''}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        const entries = getAtomEntries(xmlDoc).map(parseAtomEntry);
        const pagination = this.extractPagination(xmlDoc, skipCount, maxItems);
        return { entries, pagination };
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Get a single object by ID
   * 
   * @param repositoryId Repository ID
   * @param objectId Object ID
   * @param options Query options
   * @returns Single entry result
   */
  async getObject(
    repositoryId: string,
    objectId: string,
    options: {
      filter?: string;
      includeAllowableActions?: boolean;
      includeRelationships?: 'none' | 'source' | 'target' | 'both';
      includeACL?: boolean;
    } = {}
  ): Promise<AtomPubResult<ParsedAtomEntry>> {
    const {
      filter,
      includeAllowableActions = true,
      includeRelationships = 'none',
      includeACL = false
    } = options;

    const queryString = this.buildQueryString({
      filter,
      includeAllowableActions,
      includeRelationships,
      includeACL
    });

    const encodedObjectId = encodeURIComponent(objectId);
    const url = `${this.baseUrl}/${repositoryId}/id?id=${encodedObjectId}${queryString ? '&' + queryString.slice(1) : ''}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        // For single object, the response is an entry, not a feed
        const entry = xmlDoc.documentElement;
        if (!entry || (entry.localName !== 'entry' && !entry.tagName.endsWith(':entry'))) {
          // Try to find entry element
          const entries = getAtomEntries(xmlDoc);
          if (entries.length > 0) {
            return parseAtomEntry(entries[0]);
          }
          return null;
        }
        return parseAtomEntry(entry);
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Get an object by path
   * 
   * @param repositoryId Repository ID
   * @param path Object path (e.g., '/folder/document.txt')
   * @param options Query options
   * @returns Single entry result
   */
  async getObjectByPath(
    repositoryId: string,
    path: string,
    options: {
      filter?: string;
      includeAllowableActions?: boolean;
      includeRelationships?: 'none' | 'source' | 'target' | 'both';
      includeACL?: boolean;
    } = {}
  ): Promise<AtomPubResult<ParsedAtomEntry>> {
    const {
      filter,
      includeAllowableActions = true,
      includeRelationships = 'none',
      includeACL = false
    } = options;

    const queryString = this.buildQueryString({
      filter,
      includeAllowableActions,
      includeRelationships,
      includeACL
    });

    // Path should start with /
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    const encodedPath = encodeURIComponent(normalizedPath);
    const url = `${this.baseUrl}/${repositoryId}/path?path=${encodedPath}${queryString ? '&' + queryString.slice(1) : ''}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        const entry = xmlDoc.documentElement;
        if (!entry || (entry.localName !== 'entry' && !entry.tagName.endsWith(':entry'))) {
          const entries = getAtomEntries(xmlDoc);
          if (entries.length > 0) {
            return parseAtomEntry(entries[0]);
          }
          return null;
        }
        return parseAtomEntry(entry);
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Get version history of a document
   * 
   * @param repositoryId Repository ID
   * @param objectId Document ID
   * @returns Feed result with version entries
   */
  async getVersionHistory(
    repositoryId: string,
    objectId: string
  ): Promise<AtomPubResult<ParsedAtomEntry[]>> {
    const encodedObjectId = encodeURIComponent(objectId);
    const url = `${this.baseUrl}/${repositoryId}/versions?id=${encodedObjectId}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        return getAtomEntries(xmlDoc).map(parseAtomEntry);
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Get relationships of an object
   * 
   * @param repositoryId Repository ID
   * @param objectId Object ID
   * @param options Query options
   * @returns Feed result with relationship entries
   */
  async getRelationships(
    repositoryId: string,
    objectId: string,
    options: {
      includeSubRelationshipTypes?: boolean;
      relationshipDirection?: 'source' | 'target' | 'either';
      typeId?: string;
      maxItems?: number;
      skipCount?: number;
    } = {}
  ): Promise<AtomPubResult<AtomPubFeedResult>> {
    const {
      includeSubRelationshipTypes = true,
      relationshipDirection = 'either',
      typeId,
      maxItems = 100,
      skipCount = 0
    } = options;

    const queryString = this.buildQueryString({
      includeSubRelationshipTypes,
      relationshipDirection,
      typeId,
      maxItems,
      skipCount
    });

    const encodedObjectId = encodeURIComponent(objectId);
    const url = `${this.baseUrl}/${repositoryId}/relationships?id=${encodedObjectId}${queryString ? '&' + queryString.slice(1) : ''}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        const entries = getAtomEntries(xmlDoc).map(parseAtomEntry);
        const pagination = this.extractPagination(xmlDoc, skipCount, maxItems);
        return { entries, pagination };
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Execute a CMIS query
   * 
   * @param repositoryId Repository ID
   * @param statement CMIS query statement
   * @param options Query options
   * @returns Feed result with query results
   */
  async query(
    repositoryId: string,
    statement: string,
    options: {
      searchAllVersions?: boolean;
      includeAllowableActions?: boolean;
      includeRelationships?: 'none' | 'source' | 'target' | 'both';
      maxItems?: number;
      skipCount?: number;
    } = {}
  ): Promise<AtomPubResult<AtomPubFeedResult>> {
    const {
      searchAllVersions = false,
      includeAllowableActions = false,
      includeRelationships = 'none',
      maxItems = 100,
      skipCount = 0
    } = options;

    const queryString = this.buildQueryString({
      q: statement,
      searchAllVersions,
      includeAllowableActions,
      includeRelationships,
      maxItems,
      skipCount
    });

    const url = `${this.baseUrl}/${repositoryId}/query${queryString}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        const entries = getAtomEntries(xmlDoc).map(parseAtomEntry);
        const pagination = this.extractPagination(xmlDoc, skipCount, maxItems);
        return { entries, pagination };
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Get parent folders of an object
   * 
   * @param repositoryId Repository ID
   * @param objectId Object ID
   * @returns Feed result with parent entries
   */
  async getObjectParents(
    repositoryId: string,
    objectId: string
  ): Promise<AtomPubResult<ParsedAtomEntry[]>> {
    const encodedObjectId = encodeURIComponent(objectId);
    const url = `${this.baseUrl}/${repositoryId}/parents?id=${encodedObjectId}`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        return getAtomEntries(xmlDoc).map(parseAtomEntry);
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }

  /**
   * Get the root folder of a repository
   * 
   * @param repositoryId Repository ID
   * @returns Single entry result for root folder
   */
  async getRootFolder(
    repositoryId: string
  ): Promise<AtomPubResult<ParsedAtomEntry>> {
    const url = `${this.baseUrl}/${repositoryId}/root`;

    try {
      const response = await this.httpClient.getXml(url);
      
      return this.processXmlResponse(response, (xmlDoc) => {
        const entry = xmlDoc.documentElement;
        if (!entry || (entry.localName !== 'entry' && !entry.tagName.endsWith(':entry'))) {
          const entries = getAtomEntries(xmlDoc);
          if (entries.length > 0) {
            return parseAtomEntry(entries[0]);
          }
          return null;
        }
        return parseAtomEntry(entry);
      });
    } catch (error) {
      return {
        success: false,
        status: 0,
        error: error instanceof Error ? error.message : 'Network error'
      };
    }
  }
}
