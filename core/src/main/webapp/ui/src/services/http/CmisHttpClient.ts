/**
 * CmisHttpClient - HTTP boundary layer for CMIS operations
 * 
 * This module provides a thin wrapper around XMLHttpRequest for CMIS operations.
 * It centralizes HTTP request handling while preserving the existing behavior
 * of the CMISService.
 * 
 * Design Principles:
 * - Minimal behavior change: Preserves existing error handling patterns
 * - Promise-based: Returns Promises for async operations
 * - Response type agnostic: Returns raw response data, parsing is done by caller
 * - Header provider pattern: Allows injection of auth headers without coupling
 * - Conservative error handling: Only rejects on network errors, not HTTP errors
 * 
 * Usage:
 * ```typescript
 * const client = new CmisHttpClient(() => ({
 *   'Authorization': 'Basic ...',
 *   'nemaki_auth_token': '...'
 * }));
 * 
 * // GET request returning text
 * const response = await client.request({
 *   method: 'GET',
 *   url: '/core/browser/bedroom/root',
 *   accept: 'application/json'
 * });
 * 
 * // POST with FormData
 * const formData = new FormData();
 * formData.append('cmisaction', 'createDocument');
 * const response = await client.request({
 *   method: 'POST',
 *   url: '/core/browser/bedroom',
 *   body: formData,
 *   accept: 'application/json'
 * });
 * ```
 */

/**
 * Response type options for XMLHttpRequest
 */
export type ResponseType = '' | 'arraybuffer' | 'blob' | 'document' | 'json' | 'text';

/**
 * Request options for CmisHttpClient
 */
export interface CmisHttpRequestOptions {
  /** HTTP method (GET, POST, etc.) */
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  
  /** Request URL */
  url: string;
  
  /** Accept header value */
  accept?: string;
  
  /** Content-Type header (only set for non-FormData bodies) */
  contentType?: string;
  
  /** Request body (FormData, URLSearchParams, string, or null) */
  body?: FormData | URLSearchParams | string | null;
  
  /** Response type for XMLHttpRequest */
  responseType?: ResponseType;
  
  /** Whether to include auth headers (default: true) */
  includeAuth?: boolean;
  
  /** Additional headers to include */
  additionalHeaders?: Record<string, string>;
  
  /** Request timeout in milliseconds (default: no timeout) */
  timeout?: number;
}

/**
 * Response from CmisHttpClient
 * 
 * Note: This returns the raw response without parsing.
 * The caller is responsible for parsing JSON/XML as needed.
 */
export interface CmisHttpResponse {
  /** HTTP status code */
  status: number;
  
  /** HTTP status text */
  statusText: string;
  
  /** Response URL (may differ from request URL due to redirects) */
  responseURL: string;
  
  /** Response text (for text/json responses) */
  responseText: string;
  
  /** Raw response (for arraybuffer/blob responses) */
  response: any;
  
  /** Response headers */
  getResponseHeader: (name: string) => string | null;
}

/**
 * Error thrown on network failures
 */
export class CmisNetworkError extends Error {
  constructor(
    message: string,
    public readonly url: string
  ) {
    super(message);
    this.name = 'CmisNetworkError';
  }
}

/**
 * Header provider function type
 * Returns headers to be included in authenticated requests
 */
export type HeaderProvider = () => Record<string, string>;

/**
 * CmisHttpClient - HTTP boundary layer for CMIS operations
 */
export class CmisHttpClient {
  private headerProvider: HeaderProvider;

  /**
   * Create a new CmisHttpClient
   * 
   * @param headerProvider Function that returns auth headers
   */
  constructor(headerProvider: HeaderProvider = () => ({})) {
    this.headerProvider = headerProvider;
  }

  /**
   * Set the header provider
   * 
   * @param provider Function that returns auth headers
   */
  setHeaderProvider(provider: HeaderProvider): void {
    this.headerProvider = provider;
  }

  /**
   * Make an HTTP request
   * 
   * @param options Request options
   * @returns Promise that resolves with the response
   * @throws CmisNetworkError on network failures (including timeout and abort)
   * 
   * Note: This method does NOT reject on HTTP error status codes (4xx, 5xx).
   * The caller should check response.status and handle errors appropriately.
   * This preserves the existing behavior where some methods return fallback
   * values on HTTP errors instead of throwing.
   * 
   * Event handling: Uses onload for successful completion and onerror/ontimeout/onabort
   * for network failures. This avoids the double-settlement issue where onreadystatechange
   * at readyState === 4 could race with error events.
   */
  request(options: CmisHttpRequestOptions): Promise<CmisHttpResponse> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      
      // Guard against double-settlement (defensive programming)
      let settled = false;
      
      const safeResolve = (response: CmisHttpResponse) => {
        if (settled) return;
        settled = true;
        resolve(response);
      };
      
      const safeReject = (error: CmisNetworkError) => {
        if (settled) return;
        settled = true;
        reject(error);
      };
      
      xhr.open(options.method, options.url, true);
      
      // Set Accept header if provided
      if (options.accept) {
        xhr.setRequestHeader('Accept', options.accept);
      }
      
      // Set Content-Type header if provided (don't set for FormData - browser handles it)
      if (options.contentType && !(options.body instanceof FormData)) {
        xhr.setRequestHeader('Content-Type', options.contentType);
      }
      
      // Set auth headers if requested (default: true)
      if (options.includeAuth !== false) {
        const authHeaders = this.headerProvider();
        Object.entries(authHeaders).forEach(([key, value]) => {
          xhr.setRequestHeader(key, value);
        });
      }
      
      // Set additional headers
      if (options.additionalHeaders) {
        Object.entries(options.additionalHeaders).forEach(([key, value]) => {
          xhr.setRequestHeader(key, value);
        });
      }
      
      // Set response type if provided
      if (options.responseType) {
        xhr.responseType = options.responseType;
      }
      
      // Set timeout if provided
      if (options.timeout) {
        xhr.timeout = options.timeout;
      }
      
      // Use onload for successful HTTP completion (fires when request completes at network layer)
      // This includes HTTP 4xx/5xx responses - we resolve those and let callers handle status
      xhr.onload = () => {
        safeResolve({
          status: xhr.status,
          statusText: xhr.statusText,
          responseURL: xhr.responseURL || options.url,
          responseText: xhr.responseText,
          response: xhr.response,
          getResponseHeader: (name: string) => xhr.getResponseHeader(name)
        });
      };
      
      // Network-level failures reject with CmisNetworkError
      xhr.onerror = () => {
        safeReject(new CmisNetworkError('Network error', options.url));
      };
      
      xhr.ontimeout = () => {
        safeReject(new CmisNetworkError('Request timeout', options.url));
      };
      
      xhr.onabort = () => {
        safeReject(new CmisNetworkError('Request aborted', options.url));
      };
      
      // Send the request
      xhr.send(options.body ?? null);
    });
  }

  /**
   * Convenience method for GET requests expecting JSON response
   */
  getJson(url: string, options: Partial<CmisHttpRequestOptions> = {}): Promise<CmisHttpResponse> {
    return this.request({
      method: 'GET',
      url,
      accept: 'application/json',
      ...options
    });
  }

  /**
   * Convenience method for GET requests expecting XML response
   */
  getXml(url: string, options: Partial<CmisHttpRequestOptions> = {}): Promise<CmisHttpResponse> {
    return this.request({
      method: 'GET',
      url,
      accept: 'application/atom+xml',
      ...options
    });
  }

  /**
   * Convenience method for GET requests expecting binary response
   */
  getBinary(url: string, responseType: 'arraybuffer' | 'blob' = 'arraybuffer', options: Partial<CmisHttpRequestOptions> = {}): Promise<CmisHttpResponse> {
    return this.request({
      method: 'GET',
      url,
      responseType,
      ...options
    });
  }

  /**
   * Convenience method for POST requests with FormData
   */
  postFormData(url: string, formData: FormData, options: Partial<CmisHttpRequestOptions> = {}): Promise<CmisHttpResponse> {
    return this.request({
      method: 'POST',
      url,
      body: formData,
      accept: 'application/json',
      ...options
    });
  }

  /**
   * Convenience method for POST requests with URL-encoded body
   */
  postUrlEncoded(url: string, params: URLSearchParams | string, options: Partial<CmisHttpRequestOptions> = {}): Promise<CmisHttpResponse> {
    return this.request({
      method: 'POST',
      url,
      body: params,
      contentType: 'application/x-www-form-urlencoded',
      accept: 'application/json',
      ...options
    });
  }

  /**
   * Convenience method for POST requests with JSON body
   */
  postJson(url: string, data: unknown, options: Partial<CmisHttpRequestOptions> = {}): Promise<CmisHttpResponse> {
    return this.request({
      method: 'POST',
      url,
      body: JSON.stringify(data),
      contentType: 'application/json',
      accept: 'application/json',
      ...options
    });
  }
}
