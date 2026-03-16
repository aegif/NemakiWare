/**
 * Auth Config Service
 * Fetches SSO configuration from backend for login button visibility.
 * Configuration is cached to avoid multiple API calls.
 * Supports optional repositoryId for repository-specific overrides.
 */

export interface AuthConfig {
  oidcEnabled: boolean;
  samlEnabled: boolean;
  googleEnabled: boolean;
  microsoftEnabled: boolean;
  samlSsoUrl?: string;
  samlSpEntityId?: string;
  samlSloUrl?: string;
}

// Cache for auth configuration (keyed by repositoryId, '' = global)
const configCache: Map<string, AuthConfig> = new Map();
const fetchPromises: Map<string, Promise<AuthConfig>> = new Map();

/**
 * Get auth configuration from backend.
 * Fetches once and caches the result for subsequent calls.
 * @param repositoryId Optional repository ID for repository-specific overrides
 */
export const getAuthConfig = async (repositoryId?: string): Promise<AuthConfig> => {
  const cacheKey = repositoryId || '';

  // Return cached config if available
  const cached = configCache.get(cacheKey);
  if (cached !== undefined) {
    return cached;
  }

  // Return existing promise if fetch is in progress (deduplication)
  const existingPromise = fetchPromises.get(cacheKey);
  if (existingPromise !== undefined) {
    return existingPromise;
  }

  // Fetch from backend
  const promise = fetchAuthConfig(repositoryId);
  fetchPromises.set(cacheKey, promise);

  try {
    const config = await promise;
    configCache.set(cacheKey, config);
    return config;
  } finally {
    fetchPromises.delete(cacheKey);
  }
};

/**
 * Fetch auth configuration from backend API.
 * @param repositoryId Optional repository ID for repository-specific overrides
 */
export const fetchAuthConfig = async (repositoryId?: string): Promise<AuthConfig> => {
  try {
    const url = repositoryId
      ? `/core/rest/auth/config?repositoryId=${encodeURIComponent(repositoryId)}`
      : '/core/rest/auth/config';
    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json',
      },
    });

    if (!response.ok) {
      console.warn('Failed to fetch auth config, using defaults (buttons hidden)');
      return getDefaultConfig();
    }

    const data = await response.json();

    return {
      oidcEnabled: data.oidcEnabled === true,
      samlEnabled: data.samlEnabled === true,
      googleEnabled: data.googleEnabled === true,
      microsoftEnabled: data.microsoftEnabled === true,
      samlSsoUrl: data.samlSsoUrl || undefined,
      samlSpEntityId: data.samlSpEntityId || undefined,
      samlSloUrl: data.samlSloUrl || undefined,
    };
  } catch (error) {
    console.warn('Error fetching auth config:', error);
    // Return safe defaults on error (buttons hidden)
    return getDefaultConfig();
  }
};

/**
 * Get default config (safe defaults with buttons hidden).
 */
const getDefaultConfig = (): AuthConfig => ({
  oidcEnabled: false,
  samlEnabled: false,
  googleEnabled: false,
  microsoftEnabled: false,
});

/**
 * Clear cached config (for testing or config reload).
 * @param repositoryId Optional: clear only specific repository cache. Omit to clear all.
 */
export const clearAuthConfigCache = (repositoryId?: string): void => {
  if (repositoryId !== undefined) {
    configCache.delete(repositoryId);
    fetchPromises.delete(repositoryId);
  } else {
    configCache.clear();
    fetchPromises.clear();
  }
};
