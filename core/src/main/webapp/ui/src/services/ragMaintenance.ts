/**
 * RAG (Retrieval-Augmented Generation) Maintenance Service
 *
 * Provides API calls for RAG index maintenance operations including:
 * - Full and folder-based RAG reindexing
 * - RAG reindex status monitoring
 * - RAG health checks
 * - Index clearing
 *
 * Uses API v1 CMIS endpoints which support AUTH_TOKEN authentication.
 */

import { CmisHttpClient } from './http/CmisHttpClient';
import { AuthService } from './auth';

export interface RAGReindexStatus {
  repositoryId: string;
  status: 'idle' | 'running' | 'completed' | 'error' | 'cancelled';
  totalDocuments: number;
  indexedCount: number;
  errorCount: number;
  startTime: number;
  endTime: number;
  currentFolder: string | null;
  errorMessage: string | null;
  errors: string[];
}

export interface RAGHealthStatus {
  repositoryId: string;
  enabled: boolean;
  healthy: boolean;
  message: string;
  ragDocumentCount: number;
  ragChunkCount: number;
  eligibleDocuments: number;
  checkTime: number;
}

export interface RAGSearchResult {
  documentId: string;
  documentName: string;
  chunkId: string;
  chunkIndex: number;
  chunkText: string;
  score: number;
  objectType: string;
}

export interface RAGSearchResponse {
  query: string;
  totalResults: number;
  results: RAGSearchResult[];
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

export class RAGMaintenanceService {
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
   * Get base URL for API v1 search-engine RAG endpoints.
   */
  private getSearchEngineBaseUrl(repositoryId: string): string {
    return `/core/api/v1/cmis/repositories/${repositoryId}/search-engine`;
  }

  /**
   * Get base URL for API v1 RAG search endpoints.
   */
  private getRAGBaseUrl(repositoryId: string): string {
    return `/core/api/v1/cmis/repositories/${repositoryId}/rag`;
  }

  /**
   * Handle API v1 response format.
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

  /**
   * Check RAG health status.
   */
  async checkRAGHealth(repositoryId: string): Promise<RAGHealthStatus> {
    const response = await this.httpClient.getJson(`${this.getSearchEngineBaseUrl(repositoryId)}/rag/health`);
    return this.handleResponse<RAGHealthStatus>(response);
  }

  /**
   * Get RAG reindex status.
   */
  async getRAGReindexStatus(repositoryId: string): Promise<RAGReindexStatus> {
    const response = await this.httpClient.getJson(`${this.getSearchEngineBaseUrl(repositoryId)}/rag/status`);
    return this.handleResponse<RAGReindexStatus>(response);
  }

  /**
   * Start full RAG reindex.
   */
  async startFullRAGReindex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getSearchEngineBaseUrl(repositoryId)}/rag/reindex`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message };
  }

  /**
   * Start folder RAG reindex.
   */
  async startFolderRAGReindex(repositoryId: string, folderId: string, recursive: boolean = true): Promise<{ message: string; folderId: string; recursive: boolean }> {
    const url = `${this.getSearchEngineBaseUrl(repositoryId)}/rag/reindex/folder/${folderId}?recursive=${recursive}`;
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

  /**
   * Cancel RAG reindex.
   */
  async cancelRAGReindex(repositoryId: string): Promise<{ cancelled: boolean }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getSearchEngineBaseUrl(repositoryId)}/rag/cancel`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { cancelled: result.success };
  }

  /**
   * Clear RAG index.
   */
  async clearRAGIndex(repositoryId: string): Promise<{ message: string }> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getSearchEngineBaseUrl(repositoryId)}/rag/clear`,
      accept: 'application/json'
    });
    const result = await this.handleResponse<OperationResponse>(response);
    return { message: result.message };
  }

  /**
   * Execute RAG semantic search.
   */
  async search(
    repositoryId: string,
    query: string,
    topK: number = 10,
    minScore: number = 0.7,
    folderId?: string,
    propertyBoost?: number,
    contentBoost?: number
  ): Promise<RAGSearchResponse> {
    const requestBody: Record<string, unknown> = {
      query,
      topK,
      minScore
    };

    if (folderId) {
      requestBody.folderId = folderId;
    }
    if (propertyBoost !== undefined) {
      requestBody.propertyBoost = propertyBoost;
    }
    if (contentBoost !== undefined) {
      requestBody.contentBoost = contentBoost;
    }

    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getRAGBaseUrl(repositoryId)}/search`,
      body: JSON.stringify(requestBody),
      contentType: 'application/json',
      accept: 'application/json'
    });
    return this.handleResponse<RAGSearchResponse>(response);
  }

  /**
   * Check if RAG search is available.
   */
  async isRAGSearchAvailable(repositoryId: string): Promise<boolean> {
    try {
      const response = await this.httpClient.getJson(`${this.getRAGBaseUrl(repositoryId)}/health`);
      const health = await this.handleResponse<{ enabled: boolean; status: string }>(response);
      return health.enabled;
    } catch {
      return false;
    }
  }
}
