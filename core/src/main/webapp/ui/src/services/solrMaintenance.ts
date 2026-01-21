/**
 * Solr Index Maintenance Service
 *
 * Provides API calls for Solr index maintenance operations including:
 * - Full and folder-based reindexing
 * - Reindex status monitoring
 * - Index health checks
 * - Direct Solr query execution
 * - Document-level index operations
 *
 * Uses API v1 CMIS endpoints which support AUTH_TOKEN authentication.
 */

import { CmisHttpClient } from './http/CmisHttpClient';
import { AuthService } from './auth';

export interface ReindexStatus {
  repositoryId: string;
  status: 'idle' | 'running' | 'completed' | 'error' | 'cancelled';
  totalDocuments: number;
  indexedCount: number;
  errorCount: number;
  silentDropCount: number;   // Number of documents detected as silently dropped by Solr
  reindexedCount: number;    // Number of silently dropped documents successfully re-indexed
  verificationSkippedCount: number;  // Number of documents skipped from verification due to query length limits
  startTime: number;
  endTime: number;
  currentFolder: string | null;
  errorMessage: string | null;
  errors: string[];
  warnings: string[];  // Warnings (non-fatal issues like verification skipped)
}

export interface IndexHealthStatus {
  repositoryId: string;
  solrDocumentCount: number;
  couchDbDocumentCount: number;
  missingInSolr: number;
  orphanedInSolr: number;
  healthy: boolean;
  message: string;
  checkTime: number;
}

export interface SolrQueryResult {
  numFound: number;
  start: number;
  queryTime: number;
  docs: Record<string, unknown>[];
}

// API v1 operation response format
export interface OperationResponse {
  success: boolean;
  message: string;
  repositoryId?: string;
  objectId?: string;
  folderId?: string;
  recursive?: boolean;
}

export class SolrMaintenanceService {
  private httpClient: CmisHttpClient;
  private handleAuthError: () => void;

  constructor(handleAuthError: () => void) {
    this.handleAuthError = handleAuthError;
    this.httpClient = new CmisHttpClient(() => {
      // Use AuthService to get proper auth headers
      const authService = AuthService.getInstance();
      return authService.getAuthHeaders();
    });
  }

  /**
   * Get base URL for API v1 search-engine endpoints.
   * API v1 supports AUTH_TOKEN authentication.
   */
  private getBaseUrl(repositoryId: string): string {
    return `/core/api/v1/cmis/repositories/${repositoryId}/search-engine`;
  }

  /**
   * Handle API v1 response format.
   * API v1 returns data directly (not wrapped in {status, result, errMsg}).
   */
  private async handleResponse<T>(response: { status: number; responseText: string }): Promise<T> {
    if (response.status === 401) {
      this.handleAuthError();
      throw new Error('Authentication required');
    }

    if (response.status === 403) {
      throw new Error('Access denied. Admin privileges required.');
    }

    if (response.status >= 400) {
      // API v1 returns RFC 7807 Problem Detail for errors
      try {
        const errorData = JSON.parse(response.responseText);
        throw new Error(errorData.detail || errorData.title || `HTTP ${response.status}`);
      } catch {
        throw new Error(`HTTP ${response.status}: ${response.responseText}`);
      }
    }

    // API v1 returns data directly
    return JSON.parse(response.responseText) as T;
  }

  async getSolrUrl(repositoryId: string): Promise<string> {
    const response = await this.httpClient.getJson(`${this.getBaseUrl(repositoryId)}/url`);
    const data = await this.handleResponse<{ url: string }>(response);
    return data.url;
  }

  async startFullReindex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/reindex`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message };
  }

  async startFolderReindex(repositoryId: string, folderId: string, recursive: boolean = true): Promise<{ message: string; folderId: string; recursive: boolean }> {
    const url = `${this.getBaseUrl(repositoryId)}/reindex/folder/${folderId}?recursive=${recursive}`;
    const response = await this.httpClient.request({
      method: 'POST',
      url,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return {
      message: result.message,
      folderId: result.folderId || folderId,
      recursive: result.recursive ?? recursive
    };
  }

  async getReindexStatus(repositoryId: string): Promise<ReindexStatus> {
    const response = await this.httpClient.getJson(`${this.getBaseUrl(repositoryId)}/status`);
    return this.handleResponse<ReindexStatus>(response);
  }

  async cancelReindex(repositoryId: string): Promise<{ cancelled: boolean }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/cancel`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { cancelled: result.success };
  }

  async checkIndexHealth(repositoryId: string): Promise<IndexHealthStatus> {
    const response = await this.httpClient.getJson(`${this.getBaseUrl(repositoryId)}/health`);
    return this.handleResponse<IndexHealthStatus>(response);
  }

  async executeSolrQuery(
    repositoryId: string,
    query: string,
    start: number = 0,
    rows: number = 10,
    sort?: string,
    fields?: string
  ): Promise<SolrQueryResult> {
    const formData = new URLSearchParams();
    formData.append('q', query);
    formData.append('start', start.toString());
    formData.append('rows', rows.toString());
    if (sort) {
      formData.append('sort', sort);
    }
    if (fields) {
      formData.append('fl', fields);
    }

    const response = await this.httpClient.postUrlEncoded(
      `${this.getBaseUrl(repositoryId)}/query`,
      formData
    );
    return this.handleResponse<SolrQueryResult>(response);
  }

  async reindexDocument(repositoryId: string, objectId: string): Promise<{ message: string; objectId: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/reindex/document/${objectId}`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message, objectId: result.objectId || objectId };
  }

  async deleteFromIndex(repositoryId: string, objectId: string): Promise<{ message: string; objectId: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/delete/${objectId}`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message, objectId: result.objectId || objectId };
  }

  async clearIndex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/clear`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message };
  }

  async optimizeIndex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/optimize`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message };
  }
}
