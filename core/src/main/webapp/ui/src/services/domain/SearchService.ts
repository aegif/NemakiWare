/**
 * SearchService - Domain service for search operations
 * 
 * This service provides a high-level API for CMIS query operations.
 * It uses AtomPubClient for query execution.
 * 
 * Operations:
 * - Execute CMIS query
 * - Full-text search
 * - Property-based search
 */

import { AtomPubClient, AtomPubPagination } from '../clients/AtomPubClient';
import { CmisHttpClient } from '../http';
import { ParsedAtomEntry } from '../parsers';

/**
 * Search options
 */
export interface SearchOptions {
  maxItems?: number;
  skipCount?: number;
  searchAllVersions?: boolean;
  includeAllowableActions?: boolean;
}

/**
 * Search result item
 */
export interface SearchResultItem {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  isFolder: boolean;
  properties: Record<string, unknown>;
  allowableActions: string[];
}

/**
 * Search result with pagination
 */
export interface SearchResult {
  items: SearchResultItem[];
  pagination: AtomPubPagination;
}

/**
 * Query builder options
 */
export interface QueryBuilderOptions {
  select?: string[];
  from: string;
  where?: string;
  orderBy?: string;
  includeSecondaryTypes?: boolean;
}

/**
 * SearchService - High-level API for search operations
 */
export class SearchService {
  private atomClient: AtomPubClient;
  private repositoryId: string;

  /**
   * Create a new SearchService
   * 
   * @param httpClient CmisHttpClient instance
   * @param repositoryId Repository ID
   * @param atomBaseUrl Base URL for AtomPub (default: '/core/atom')
   */
  constructor(
    httpClient: CmisHttpClient,
    repositoryId: string,
    atomBaseUrl: string = '/core/atom'
  ) {
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
   * Convert parsed entry to SearchResultItem
   */
  private entryToSearchResultItem(entry: ParsedAtomEntry): SearchResultItem {
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
   * Execute a CMIS query
   * 
   * @param statement CMIS query statement
   * @param options Search options
   * @returns Search result with pagination
   */
  async query(
    statement: string,
    options: SearchOptions = {}
  ): Promise<{ success: boolean; result?: SearchResult; error?: string }> {
    const atomResult = await this.atomClient.query(this.repositoryId, statement, {
      maxItems: options.maxItems,
      skipCount: options.skipCount,
      searchAllVersions: options.searchAllVersions,
      includeAllowableActions: options.includeAllowableActions
    });

    if (!atomResult.success || !atomResult.data) {
      return { success: false, error: atomResult.error };
    }

    const items = atomResult.data.entries.map(entry => this.entryToSearchResultItem(entry));
    return {
      success: true,
      result: {
        items,
        pagination: atomResult.data.pagination
      }
    };
  }

  /**
   * Build a CMIS query statement
   * 
   * @param options Query builder options
   * @returns CMIS query statement
   */
  buildQuery(options: QueryBuilderOptions): string {
    const { select = ['*'], from, where, orderBy, includeSecondaryTypes } = options;

    let query = `SELECT ${select.join(', ')} FROM ${from}`;

    if (includeSecondaryTypes) {
      // Join with secondary types for full property access
      query = `SELECT ${select.join(', ')} FROM ${from} AS d JOIN cmis:secondary AS s ON d.cmis:objectId = s.cmis:objectId`;
    }

    if (where) {
      query += ` WHERE ${where}`;
    }

    if (orderBy) {
      query += ` ORDER BY ${orderBy}`;
    }

    return query;
  }

  /**
   * Full-text search using CONTAINS
   * 
   * @param searchText Text to search for
   * @param options Search options
   * @returns Search result with pagination
   */
  async fullTextSearch(
    searchText: string,
    options: SearchOptions & { objectType?: string } = {}
  ): Promise<{ success: boolean; result?: SearchResult; error?: string }> {
    const { objectType = 'cmis:document', ...searchOptions } = options;

    // Escape single quotes in search text
    const escapedText = searchText.replace(/'/g, "\\'");

    const statement = this.buildQuery({
      select: ['*'],
      from: objectType,
      where: `CONTAINS('${escapedText}')`
    });

    return this.query(statement, searchOptions);
  }

  /**
   * Search by property value
   * 
   * @param propertyName Property name (e.g., 'cmis:name')
   * @param value Property value to search for
   * @param options Search options
   * @returns Search result with pagination
   */
  async searchByProperty(
    propertyName: string,
    value: string | number | boolean,
    options: SearchOptions & { objectType?: string; operator?: string } = {}
  ): Promise<{ success: boolean; result?: SearchResult; error?: string }> {
    const { objectType = 'cmis:document', operator = '=', ...searchOptions } = options;

    let whereClause: string;
    if (typeof value === 'string') {
      // Escape single quotes in string value
      const escapedValue = value.replace(/'/g, "\\'");
      whereClause = `${propertyName} ${operator} '${escapedValue}'`;
    } else {
      whereClause = `${propertyName} ${operator} ${value}`;
    }

    const statement = this.buildQuery({
      select: ['*'],
      from: objectType,
      where: whereClause
    });

    return this.query(statement, searchOptions);
  }

  /**
   * Search by name pattern (LIKE)
   * 
   * @param namePattern Name pattern (e.g., 'test%' for names starting with 'test')
   * @param options Search options
   * @returns Search result with pagination
   */
  async searchByName(
    namePattern: string,
    options: SearchOptions & { objectType?: string } = {}
  ): Promise<{ success: boolean; result?: SearchResult; error?: string }> {
    const { objectType = 'cmis:document', ...searchOptions } = options;

    // Escape single quotes in pattern
    const escapedPattern = namePattern.replace(/'/g, "\\'");

    const statement = this.buildQuery({
      select: ['*'],
      from: objectType,
      where: `cmis:name LIKE '${escapedPattern}'`
    });

    return this.query(statement, searchOptions);
  }

  /**
   * Search in folder (including subfolders)
   * 
   * @param folderId Folder ID to search in
   * @param searchText Optional text to search for
   * @param options Search options
   * @returns Search result with pagination
   */
  async searchInFolder(
    folderId: string,
    searchText?: string,
    options: SearchOptions & { objectType?: string; includeSubfolders?: boolean } = {}
  ): Promise<{ success: boolean; result?: SearchResult; error?: string }> {
    const { objectType = 'cmis:document', includeSubfolders = true, ...searchOptions } = options;

    const folderPredicate = includeSubfolders
      ? `IN_TREE('${folderId}')`
      : `IN_FOLDER('${folderId}')`;

    let whereClause = folderPredicate;

    if (searchText) {
      // Escape single quotes in search text
      const escapedText = searchText.replace(/'/g, "\\'");
      whereClause = `${folderPredicate} AND CONTAINS('${escapedText}')`;
    }

    const statement = this.buildQuery({
      select: ['*'],
      from: objectType,
      where: whereClause
    });

    return this.query(statement, searchOptions);
  }

  /**
   * Search by secondary type
   * 
   * @param secondaryTypeId Secondary type ID
   * @param propertyFilters Optional property filters
   * @param options Search options
   * @returns Search result with pagination
   */
  async searchBySecondaryType(
    secondaryTypeId: string,
    propertyFilters?: Record<string, string | number | boolean>,
    options: SearchOptions = {}
  ): Promise<{ success: boolean; result?: SearchResult; error?: string }> {
    // Build WHERE clause for secondary type
    let whereClause = `ANY cmis:secondaryObjectTypeIds IN ('${secondaryTypeId}')`;

    // Add property filters if provided
    if (propertyFilters) {
      const filterClauses = Object.entries(propertyFilters).map(([key, value]) => {
        if (typeof value === 'string') {
          const escapedValue = value.replace(/'/g, "\\'");
          return `${key} = '${escapedValue}'`;
        }
        return `${key} = ${value}`;
      });

      if (filterClauses.length > 0) {
        whereClause = `${whereClause} AND ${filterClauses.join(' AND ')}`;
      }
    }

    const statement = this.buildQuery({
      select: ['*'],
      from: 'cmis:document',
      where: whereClause
    });

    return this.query(statement, options);
  }
}
