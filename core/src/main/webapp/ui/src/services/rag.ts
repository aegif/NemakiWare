/**
 * RAG (Retrieval-Augmented Generation) Search Service
 *
 * Provides semantic search functionality using vector embeddings.
 * Requires RAG feature to be enabled on the server.
 */

import { AuthService } from './auth';

// Helper to get auth headers from singleton AuthService
const getAuthHeaders = () => AuthService.getInstance().getAuthHeaders();

/**
 * RAG search result representing a document chunk.
 */
export interface RAGSearchResult {
  chunkId: string;
  chunkIndex: number;
  chunkText: string;
  documentId: string;
  documentName: string;
  path: string;
  objectType: string;
  score: number;
}

/**
 * RAG search request parameters.
 */
export interface RAGSearchRequest {
  query: string;
  topK?: number;
  minScore?: number;
  folderId?: string;
  /** Property boost factor (0.0-1.0). Higher values give more weight to metadata. */
  propertyBoost?: number;
  /** Content boost factor (0.0-1.0). Higher values give more weight to body content. */
  contentBoost?: number;
  /** Admin only: Simulate search as another user. */
  simulateAsUserId?: string;
}

/**
 * RAG search response.
 */
export interface RAGSearchResponse {
  query: string;
  totalResults: number;
  results: RAGSearchResult[];
}

/**
 * RAG health status.
 */
export interface RAGHealthStatus {
  enabled: boolean;
  status: 'healthy' | 'unavailable';
}

/**
 * RAG Search Service class.
 */
export class RAGService {
  private baseUrl: string;
  private repositoryId: string;

  constructor(baseUrl: string, repositoryId: string) {
    this.baseUrl = baseUrl;
    this.repositoryId = repositoryId;
  }

  /**
   * Check if RAG semantic search is available.
   */
  async getHealth(): Promise<RAGHealthStatus> {
    const url = `${this.baseUrl}/core/api/v1/cmis/repositories/${this.repositoryId}/rag/health`;

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        ...getAuthHeaders(),
        'Accept': 'application/json'
      }
    });

    if (!response.ok) {
      throw new Error(`RAG health check failed: ${response.status}`);
    }

    return response.json();
  }

  /**
   * Perform semantic search using RAG.
   *
   * @param request Search request parameters
   * @returns Search results with matching document chunks
   */
  async search(request: RAGSearchRequest): Promise<RAGSearchResponse> {
    const url = `${this.baseUrl}/core/api/v1/cmis/repositories/${this.repositoryId}/rag/search`;

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        ...getAuthHeaders(),
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
      body: JSON.stringify({
        query: request.query,
        topK: request.topK ?? 10,
        minScore: request.minScore ?? 0.7,
        folderId: request.folderId,
        propertyBoost: request.propertyBoost,
        contentBoost: request.contentBoost,
        simulateAsUserId: request.simulateAsUserId
      })
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.detail || `RAG search failed: ${response.status}`);
    }

    return response.json();
  }

  /**
   * Perform semantic search using GET method (for simple queries).
   */
  async searchGet(
    query: string,
    topK: number = 10,
    minScore: number = 0.7,
    folderId?: string
  ): Promise<RAGSearchResponse> {
    const params = new URLSearchParams({
      q: query,
      topK: topK.toString(),
      minScore: minScore.toString()
    });

    if (folderId) {
      params.append('folderId', folderId);
    }

    const url = `${this.baseUrl}/core/api/v1/cmis/repositories/${this.repositoryId}/rag/search?${params.toString()}`;

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        ...getAuthHeaders(),
        'Accept': 'application/json'
      }
    });

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.detail || `RAG search failed: ${response.status}`);
    }

    return response.json();
  }
}

// Singleton instance cache
let ragServiceInstance: RAGService | null = null;

/**
 * Get RAG service instance.
 */
export function getRAGService(baseUrl: string, repositoryId: string): RAGService {
  if (!ragServiceInstance || ragServiceInstance['repositoryId'] !== repositoryId) {
    ragServiceInstance = new RAGService(baseUrl, repositoryId);
  }
  return ragServiceInstance;
}
