/**
 * Audit Metrics Service
 *
 * Provides API calls for audit log metrics monitoring including:
 * - Fetching audit event statistics
 * - Resetting metrics counters
 * - Prometheus format metrics export
 *
 * Uses REST API endpoints which support Basic authentication.
 */

/** Audit event metrics data */
export interface AuditMetrics {
  'audit.events.total': number;
  'audit.events.logged': number;
  'audit.events.skipped': number;
  'audit.events.failed': number;
}

/** Calculated success/skip/failure rates */
export interface AuditRates {
  'success.rate': string;
  'skip.rate': string;
  'failure.rate': string;
}

/** Successful audit metrics response */
export interface AuditMetricsSuccessResponse {
  status: 'ok';
  metrics: AuditMetrics;
  rates?: AuditRates;
  enabled: boolean;
  readAuditLevel: string;
  timestamp: number;
}

/** Error response from audit metrics API */
export interface AuditMetricsErrorResponse {
  status: 'error';
  message: string;
  errors?: Array<{ [key: string]: string }>;
}

/** Discriminated union type for audit metrics response */
export type AuditMetricsApiResponse = AuditMetricsSuccessResponse | AuditMetricsErrorResponse;

/**
 * Type alias for backward compatibility.
 * Components should use this type when expecting successful responses.
 */
export type AuditMetricsResponse = AuditMetricsSuccessResponse;

/** Successful reset response */
export interface AuditMetricsResetSuccessResponse {
  status: 'ok';
  message: string;
  previousValues: AuditMetrics;
  timestamp: number;
}

/** Discriminated union type for reset response */
export type AuditMetricsResetApiResponse = AuditMetricsResetSuccessResponse | AuditMetricsErrorResponse;

/**
 * Type alias for backward compatibility.
 * Components should use this type when expecting successful responses.
 */
export type AuditMetricsResetResponse = AuditMetricsResetSuccessResponse;

/**
 * Type guard to check if response is a successful metrics response
 */
export function isSuccessResponse(response: AuditMetricsApiResponse): response is AuditMetricsSuccessResponse {
  return response.status === 'ok';
}

/**
 * Type guard to check if response is an error response
 */
export function isErrorResponse(
  response: AuditMetricsApiResponse | AuditMetricsResetApiResponse
): response is AuditMetricsErrorResponse {
  return response.status === 'error';
}

export class AuditMetricsService {
  private baseUrl = '/core/rest/all/audit/metrics';
  private onAuthError: () => void;

  constructor(onAuthError: () => void) {
    this.onAuthError = onAuthError;
  }

  /**
   * Fetches audit metrics in JSON format.
   * Requires admin authentication.
   *
   * @returns Promise resolving to audit metrics data
   * @throws Error if authentication fails or request errors
   */
  async getMetrics(): Promise<AuditMetricsSuccessResponse> {
    const response = await fetch(this.baseUrl, {
      credentials: 'include'
    });

    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: 'Unknown error' })) as AuditMetricsErrorResponse;
      throw new Error(errorData.message || `Failed to fetch metrics: ${response.status}`);
    }

    const data = await response.json() as AuditMetricsApiResponse;

    if (isErrorResponse(data)) {
      throw new Error(data.message);
    }

    return data;
  }

  /**
   * Resets all audit metrics counters to zero.
   * Requires admin authentication.
   *
   * @returns Promise resolving to reset confirmation with previous values
   * @throws Error if authentication fails or request errors
   */
  async resetMetrics(): Promise<AuditMetricsResetSuccessResponse> {
    const response = await fetch(`${this.baseUrl}/reset`, {
      method: 'POST',
      credentials: 'include'
    });

    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: 'Unknown error' })) as AuditMetricsErrorResponse;
      throw new Error(errorData.message || `Failed to reset metrics: ${response.status}`);
    }

    const data = await response.json() as AuditMetricsResetApiResponse;

    if (isErrorResponse(data)) {
      throw new Error(data.message);
    }

    return data;
  }

  /**
   * Fetches audit metrics in Prometheus format.
   * Requires admin authentication.
   *
   * @returns Promise resolving to Prometheus-formatted metrics string
   * @throws Error if authentication fails or request errors
   */
  async getPrometheusMetrics(): Promise<string> {
    const response = await fetch(`${this.baseUrl}/prometheus`, {
      credentials: 'include'
    });

    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }

    if (!response.ok) {
      throw new Error(`Failed to fetch Prometheus metrics: ${response.status}`);
    }

    return response.text();
  }
}
