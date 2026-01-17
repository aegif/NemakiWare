/**
 * Solr Index Maintenance Service
 * 
 * Provides API calls for Solr index maintenance operations including:
 * - Full and folder-based reindexing
 * - Reindex status monitoring
 * - Index health checks
 * - Direct Solr query execution
 * - Document-level index operations
 */

import { CmisHttpClient } from './http/CmisHttpClient';

export interface ReindexStatus {
  repositoryId: string;
  status: 'idle' | 'running' | 'completed' | 'error' | 'cancelled';
  totalDocuments: number;
  indexedCount: number;
  errorCount: number;
  startTime: number;
  endTime: number;
  currentFolder: string | null;
  errorMessage: string | null;
  errors?: string[];
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

export interface ApiResponse<T> {
  status: boolean;
  result?: T;
  errMsg?: string[];
}

export class SolrMaintenanceService {
  private httpClient: CmisHttpClient;
  private handleAuthError: () => void;

  constructor(handleAuthError: () => void) {
    this.handleAuthError = handleAuthError;
    this.httpClient = new CmisHttpClient(() => {
      const authToken = localStorage.getItem('nemaki_auth_token');
      const headers: Record<string, string> = {};
      if (authToken) {
        headers['nemaki_auth_token'] = authToken;
      }
      return headers;
    });
  }

  private getBaseUrl(repositoryId: string): string {
    return `/core/rest/repo/${repositoryId}/search-engine`;
  }

  private async handleResponse<T>(response: { status: number; responseText: string }): Promise<T> {
    if (response.status === 401) {
      this.handleAuthError();
      throw new Error('Authentication required');
    }

    const data = JSON.parse(response.responseText) as ApiResponse<T>;
    
    if (!data.status && data.errMsg && data.errMsg.length > 0) {
      throw new Error(data.errMsg.join(', '));
    }

    return data.result as T;
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
    return this.handleResponse<{ message: string }>(response);
  }

  async startFolderReindex(repositoryId: string, folderId: string, recursive: boolean = true): Promise<{ message: string; folderId: string; recursive: boolean }> {
    const url = `${this.getBaseUrl(repositoryId)}/reindex/folder/${folderId}?recursive=${recursive}`;
    const response = await this.httpClient.request({
      method: 'POST',
      url,
      accept: 'application/json'
    });
    return this.handleResponse<{ message: string; folderId: string; recursive: boolean }>(response);
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
    return this.handleResponse<{ cancelled: boolean }>(response);
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
    return this.handleResponse<{ message: string; objectId: string }>(response);
  }

  async deleteFromIndex(repositoryId: string, objectId: string): Promise<{ message: string; objectId: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/delete/${objectId}`,
      accept: 'application/json'
    });
    return this.handleResponse<{ message: string; objectId: string }>(response);
  }

  async clearIndex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/clear`,
      accept: 'application/json'
    });
    return this.handleResponse<{ message: string }>(response);
  }

  async optimizeIndex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl(repositoryId)}/optimize`,
      accept: 'application/json'
    });
    return this.handleResponse<{ message: string }>(response);
  }
}
