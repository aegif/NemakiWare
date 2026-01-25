/**
 * Audit Metrics Service
 *
 * Provides API calls for audit log metrics monitoring including:
 * - Fetching audit event statistics
 * - Resetting metrics counters
 *
 * Uses REST API endpoints which support Basic authentication.
 */

export interface AuditMetrics {
  'audit.events.total': number;
  'audit.events.logged': number;
  'audit.events.skipped': number;
  'audit.events.failed': number;
}

export interface AuditRates {
  'success.rate': string;
  'skip.rate': string;
  'failure.rate': string;
}

export interface AuditMetricsResponse {
  status: string;
  metrics: AuditMetrics;
  rates?: AuditRates;
  enabled: boolean;
  readAuditLevel: string;
  timestamp: number;
}

export interface AuditMetricsResetResponse {
  status: string;
  message: string;
  previousValues: AuditMetrics;
  timestamp: number;
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
   */
  async getMetrics(): Promise<AuditMetricsResponse> {
    const response = await fetch(this.baseUrl, {
      credentials: 'include'
    });

    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to fetch metrics: ${response.status}`);
    }

    return response.json();
  }

  /**
   * Resets all audit metrics counters to zero.
   * Requires admin authentication.
   */
  async resetMetrics(): Promise<AuditMetricsResetResponse> {
    const response = await fetch(`${this.baseUrl}/reset`, {
      method: 'POST',
      credentials: 'include'
    });

    if (response.status === 401 || response.status === 403) {
      this.onAuthError();
      throw new Error('Authentication required');
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || `Failed to reset metrics: ${response.status}`);
    }

    return response.json();
  }

  /**
   * Fetches audit metrics in Prometheus format.
   * Requires admin authentication.
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
