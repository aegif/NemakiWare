import { AuthService } from './auth';
import { CmisHttpClient } from './http/CmisHttpClient';

export interface PurviewClassification {
  typeName: string;
  entityStatus: string;
}

export interface PurviewGlossaryTerm {
  displayText: string;
  termGuid?: string;
  relationGuid?: string;
  status?: string;
}

export interface PurviewGovernanceView {
  featureEnabled: boolean;
  available: boolean;
  supportedObjectType: boolean;
  entityFound: boolean;
  repositoryId: string;
  objectId: string;
  objectBaseType: string;
  entityTypeName: string;
  qualifiedName: string;
  atlasBasePath: string;
  message: string;
  classifications: PurviewClassification[];
  glossaryTerms: PurviewGlossaryTerm[];
  labels: string[];
  businessMetadata: Record<string, Record<string, unknown>>;
}

export interface PurviewGovernanceBulkItemView extends Partial<PurviewGovernanceView> {
  objectId: string;
  status: string;
  message: string;
}

interface PurviewGovernanceBulkResponse {
  items: PurviewGovernanceBulkItemView[];
}

export class PurviewGovernanceService {
  private httpClient: CmisHttpClient;
  private handleAuthError: () => void;

  constructor(handleAuthError: () => void) {
    this.handleAuthError = handleAuthError;
    this.httpClient = new CmisHttpClient(() => {
      const authService = AuthService.getInstance();
      return authService.getAuthHeaders();
    });
  }

  private async handleResponse<T>(response: { status: number; responseText: string }): Promise<T> {
    if (response.status === 401) {
      this.handleAuthError();
      throw new Error('Authentication required');
    }

    if (response.status === 403) {
      throw new Error('Access denied');
    }

    if (response.status === 404) {
      throw new Error('Object not found');
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

  async getGovernance(repositoryId: string, objectId: string): Promise<PurviewGovernanceView> {
    const response = await this.httpClient.getJson(
      `/core/v1/repo/${encodeURIComponent(repositoryId)}/purview/governance/${encodeURIComponent(objectId)}`
    );
    return this.handleResponse<PurviewGovernanceView>(response);
  }

  async getGovernanceBulk(repositoryId: string, objectIds: string[]): Promise<PurviewGovernanceBulkItemView[]> {
    const response = await this.httpClient.postJson(
      `/core/v1/repo/${encodeURIComponent(repositoryId)}/purview/governance/bulk`,
      { objectIds }
    );
    const payload = await this.handleResponse<PurviewGovernanceBulkResponse>(response);
    return payload.items ?? [];
  }
}
