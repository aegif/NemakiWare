/**
 * Auth Config Service
 * Fetches SSO configuration from backend for login button visibility.
 * Configuration is cached to avoid multiple API calls.
 */

export interface AuthConfig {
  oidcEnabled: boolean;
  samlEnabled: boolean;
}

// Cache for auth configuration
let cachedConfig: AuthConfig | null = null;
let fetchPromise: Promise<AuthConfig> | null = null;

/**
 * Get auth configuration from backend.
 * Fetches once and caches the result for subsequent calls.
 */
export const getAuthConfig = async (): Promise<AuthConfig> => {
  // Return cached config if available
  if (cachedConfig !== null) {
    return cachedConfig;
  }

  // Return existing promise if fetch is in progress (deduplication)
  if (fetchPromise !== null) {
    return fetchPromise;
  }

  // Fetch from backend
  fetchPromise = fetchAuthConfig();

  try {
    cachedConfig = await fetchPromise;
    return cachedConfig;
  } finally {
    fetchPromise = null;
  }
};

/**
 * Fetch auth configuration from backend API.
 */
const fetchAuthConfig = async (): Promise<AuthConfig> => {
  try {
    const response = await fetch('/core/rest/auth/config', {
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
});

/**
 * Clear cached config (for testing or config reload).
 */
export const clearAuthConfigCache = (): void => {
  cachedConfig = null;
  fetchPromise = null;
};
