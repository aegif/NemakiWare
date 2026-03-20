import { AuthService } from './auth';
import { CmisHttpClient } from './http/CmisHttpClient';

export interface PurviewConnectionStatus {
  status: string;
  connected: boolean;
  featureEnabled: boolean;
  endpoint: string;
  atlasBasePath: string;
  message: string;
}

export interface PurviewSchemaState {
  collection: string;
  schemaVersion: string;
  schemaHash: string;
  lastAppliedAt: string;
  lastAppliedBy: string;
  lastDiffSummary: string;
}

export interface PurviewSchemaDiff {
  collection: string;
  currentSchemaVersion: string;
  currentSchemaHash: string;
  desiredSchemaVersion: string;
  desiredSchemaHash: string;
  applyRequired: boolean;
  customTypeNames: string[];
  relationshipTypeNames: string[];
  businessMetadataNames: string[];
}

export interface PurviewJobState {
  jobId: string;
  jobKind: string;
  repositoryId: string;
  status: string;
  startedAt: string;
  endedAt: string;
  processedCount: number;
  failedCount: number;
  checkpoint: string;
  errorSummary: string;
}

export interface PurviewSchemaApplyJobResult extends PurviewJobState {
  applied: boolean;
  message: string;
  collection?: string;
  schemaVersion?: string;
  schemaHash?: string;
  lastAppliedAt?: string;
  lastAppliedBy?: string;
}

export interface PurviewCursorState {
  repositoryId: string;
  streamKind: string;
  cursor: string;
  cursorKind: string;
  lastRunAt: string;
  lastSuccessAt: string;
  lastErrorAt: string;
  lastErrorMessage: string;
  consecutiveFailureCount: number;
  deadLetterCount: number;
}

export interface PurviewLockState {
  repositoryId: string;
  jobKind: string;
  locked: boolean;
  ownerJobId: string;
  lockedAt: string;
}

export interface PurviewTombstoneState {
  repositoryId: string;
  objectId: string;
  typeName: string;
  qualifiedName: string;
  changeToken: string;
  firstSeenAt: string;
  dueAt: string;
  status: string;
}

export interface PurviewDeadLetterState {
  repositoryId: string;
  streamKind: string;
  entryKey: string;
  typeName: string;
  qualifiedName: string;
  firstFailedAt: string;
  lastFailedAt: string;
  failureCount: number;
  checkpoint: string;
  errorSummary: string;
}

export interface PurviewStateOverview {
  collection: string;
  schemaState: PurviewSchemaState;
  jobs: PurviewJobState[];
  cursors: PurviewCursorState[];
  locks: PurviewLockState[];
  tombstones: PurviewTombstoneState[];
  deadLetters: PurviewDeadLetterState[];
}

interface DeadLettersResponse {
  deadLetters: PurviewDeadLetterState[];
}

export class PurviewAdminService {
  private httpClient: CmisHttpClient;
  private handleAuthError: () => void;

  constructor(handleAuthError: () => void) {
    this.handleAuthError = handleAuthError;
    this.httpClient = new CmisHttpClient(() => {
      const authService = AuthService.getInstance();
      return authService.getAuthHeaders();
    });
  }

  private getBaseUrl(): string {
    return '/core/v1/admin/purview';
  }

  private async handleResponse<T>(response: { status: number; responseText: string }): Promise<T> {
    if (response.status === 401) {
      this.handleAuthError();
      throw new Error('Authentication required');
    }

    if (response.status === 403) {
      throw new Error('Access denied. Admin privileges required.');
    }

    if (response.status >= 400) {
      try {
        const errorData = JSON.parse(response.responseText);
        throw new Error(errorData.detail || errorData.message || errorData.title || `HTTP ${response.status}`);
      } catch {
        throw new Error(`HTTP ${response.status}: ${response.responseText}`);
      }
    }

    return JSON.parse(response.responseText) as T;
  }

  private async get<T>(path: string): Promise<T> {
    const response = await this.httpClient.getJson(`${this.getBaseUrl()}${path}`);
    return this.handleResponse<T>(response);
  }

  private async post<T>(path: string): Promise<T> {
    const response = await this.httpClient.request({
      method: 'POST',
      url: `${this.getBaseUrl()}${path}`,
      accept: 'application/json',
    });
    return this.handleResponse<T>(response);
  }

  async testConnection(): Promise<PurviewConnectionStatus> {
    return this.post<PurviewConnectionStatus>('/test-connection');
  }

  async getSchemaState(): Promise<PurviewSchemaState> {
    return this.get<PurviewSchemaState>('/schema-state');
  }

  async getSchemaDiff(): Promise<PurviewSchemaDiff> {
    return this.get<PurviewSchemaDiff>('/type-definitions/diff');
  }

  async applyTypeDefinitions(): Promise<PurviewSchemaApplyJobResult> {
    return this.post<PurviewSchemaApplyJobResult>('/type-definitions/apply');
  }

  async startFullSync(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/full-sync/${encodeURIComponent(repositoryId)}`);
  }

  async startIncrementalSync(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/incremental-sync/${encodeURIComponent(repositoryId)}`);
  }

  async reconcileArchives(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/reconcile/archives/${encodeURIComponent(repositoryId)}`);
  }

  async reconcileCloudMetadata(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/reconcile/cloud-metadata/${encodeURIComponent(repositoryId)}`);
  }

  async reconcileContainment(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/reconcile/containment/${encodeURIComponent(repositoryId)}`);
  }

  async reconcileTypes(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/reconcile/types/${encodeURIComponent(repositoryId)}`);
  }

  async resolveDeletes(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/delete-resolution/${encodeURIComponent(repositoryId)}`);
  }

  async getJob(jobId: string): Promise<PurviewJobState> {
    return this.get<PurviewJobState>(`/jobs/${encodeURIComponent(jobId)}`);
  }

  async getCursorState(repositoryId: string, streamKind: string): Promise<PurviewCursorState> {
    return this.get<PurviewCursorState>(
      `/cursor-state/${encodeURIComponent(repositoryId)}/${encodeURIComponent(streamKind)}`
    );
  }

  async getStateOverview(): Promise<PurviewStateOverview> {
    return this.get<PurviewStateOverview>('/state');
  }

  async getDeadLetters(): Promise<PurviewDeadLetterState[]> {
    const response = await this.get<DeadLettersResponse>('/dead-letters');
    return response.deadLetters;
  }

  async retryFailed(repositoryId: string): Promise<PurviewJobState> {
    return this.post<PurviewJobState>(`/retry-failed/${encodeURIComponent(repositoryId)}`);
  }
}
