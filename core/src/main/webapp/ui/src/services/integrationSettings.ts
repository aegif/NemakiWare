import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

const BASE_URL = '/core/api/v1/admin/integration-settings';

export type SettingSource = 'system_property' | 'environment' | 'couchdb' | 'properties_file' | 'default' | 'none';

export interface IntegrationSettingsResponse {
  settings: Record<string, string>;
  sources: Record<string, SettingSource>;
  warning?: string;
}

export interface UpdateResult {
  status: string;
  message: string;
  updatedKeys?: string[];
  warning?: string;
  warnings?: string[];
}

export interface ConnectionTestResult {
  status: 'success' | 'failure' | 'disabled';
  message: string;
  connected?: boolean;
  featureEnabled?: boolean;
  endpoint?: string;
  wellKnownUrl?: string;
}

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();
  return fetch(url, {
    ...options,
    headers: {
      'Accept': 'application/json',
      ...headers,
      ...options.headers,
    },
  });
}

async function getSettings(group: string): Promise<IntegrationSettingsResponse> {
  const response = await fetchWithAuth(`${BASE_URL}/${group}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch ${group} settings: ${response.status}`);
  }
  return (await parseJsonResponseBody(response, `getSettings(${group})`)) as unknown as IntegrationSettingsResponse;
}

async function updateSettings(group: string, settings: Record<string, string>): Promise<UpdateResult> {
  const response = await fetchWithAuth(`${BASE_URL}/${group}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
  const data = await parseJsonResponseBody(response, `updateSettings(${group})`);
  if (!response.ok) {
    throw new Error(
      (typeof data.message === 'string' ? data.message : null) || `Failed to update ${group} settings: ${response.status}`
    );
  }
  return data as unknown as UpdateResult;
}

async function testConnection(group: string, body?: Record<string, string>): Promise<ConnectionTestResult> {
  const options: RequestInit = {
    method: 'POST',
  };
  if (body) {
    options.headers = { 'Content-Type': 'application/json' };
    options.body = JSON.stringify(body);
  }
  const response = await fetchWithAuth(`${BASE_URL}/${group}/test-connection`, options);
  if (!response.ok) {
    throw new Error(`Connection test failed: ${response.status}`);
  }
  return (await parseJsonResponseBody(response, `testConnection(${group})`)) as unknown as ConnectionTestResult;
}

// OIDC
export const getOidcSettings = () => getSettings('oidc');
export const updateOidcSettings = (settings: Record<string, string>) => updateSettings('oidc', settings);
export const testOidcConnection = () => testConnection('oidc');

// Google Auth
export const getGoogleAuthSettings = () => getSettings('google-auth');
export const updateGoogleAuthSettings = (settings: Record<string, string>) => updateSettings('google-auth', settings);

// Microsoft Auth
export const getMicrosoftAuthSettings = () => getSettings('microsoft-auth');
export const updateMicrosoftAuthSettings = (settings: Record<string, string>) => updateSettings('microsoft-auth', settings);

// SAML
export const getSamlSettings = () => getSettings('saml');
export const updateSamlSettings = (settings: Record<string, string>) => updateSettings('saml', settings);

// Directory Sync
export const getDirectorySyncSettings = () => getSettings('directory-sync');
export const updateDirectorySyncSettings = (settings: Record<string, string>) => updateSettings('directory-sync', settings);

// Purview
export const getPurviewSettings = () => getSettings('purview');
export const updatePurviewSettings = (settings: Record<string, string>) => updateSettings('purview', settings);
export const testPurviewConnection = (formValues?: Record<string, string>) =>
  testConnection('purview', formValues);

// Lineage
export const getLineageSettings = () => getSettings('lineage');
export const updateLineageSettings = (settings: Record<string, string>) => updateSettings('lineage', settings);

// Atlas
export const getAtlasSettings = () => getSettings('atlas');
export const updateAtlasSettings = (settings: Record<string, string>) => updateSettings('atlas', settings);
export const testAtlasConnection = (formValues?: Record<string, string>) =>
  testConnection('atlas', formValues);

// Dataplex
export const getDataplexSettings = () => getSettings('dataplex');
export const updateDataplexSettings = (settings: Record<string, string>) => updateSettings('dataplex', settings);

// Property Mappings
export interface PropertyMappingEntry {
  enabled: boolean;
  catalogName: string;
}

export interface PropertyMappingsResponse {
  mappings: Record<string, Record<string, PropertyMappingEntry>>;
  message?: string;
  warnings?: string[];
}

export async function getPropertyMappings(repositoryId: string): Promise<PropertyMappingsResponse> {
  const response = await fetchWithAuth(`${BASE_URL}/property-mappings/${encodeURIComponent(repositoryId)}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch property mappings: ${response.status}`);
  }
  return (await parseJsonResponseBody(response, 'getPropertyMappings')) as unknown as PropertyMappingsResponse;
}

export async function updatePropertyMappings(
  repositoryId: string,
  mappings: Record<string, Record<string, PropertyMappingEntry>>
): Promise<UpdateResult> {
  const response = await fetchWithAuth(`${BASE_URL}/property-mappings/${encodeURIComponent(repositoryId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mappings }),
  });
  const data = await parseJsonResponseBody(response, 'updatePropertyMappings');
  if (!response.ok) {
    throw new Error(
      (typeof data.message === 'string' ? data.message : null) || `Failed to update property mappings: ${response.status}`
    );
  }
  return data as unknown as UpdateResult;
}
