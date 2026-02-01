/**
 * Cloud Authentication Service for Google and Microsoft direct OIDC login.
 *
 * Uses Google Identity Services (GIS) and MSAL.js popup flows
 * to obtain ID tokens, then converts them to NemakiWare auth tokens
 * via server-side verification endpoints.
 */

import { AuthToken } from './auth';

export type CloudProvider = 'google' | 'microsoft';

export interface CloudAuthConfig {
  googleEnabled: boolean;
  googleClientId?: string;
  microsoftEnabled: boolean;
  microsoftClientId?: string;
  microsoftTenantId?: string;
}

/**
 * Fetch cloud auth configuration from server.
 */
export async function fetchCloudAuthConfig(): Promise<CloudAuthConfig> {
  try {
    const response = await fetch('/core/rest/auth/config');
    if (!response.ok) {
      return { googleEnabled: false, microsoftEnabled: false };
    }
    const data = await response.json();
    return {
      googleEnabled: data.googleEnabled === true,
      googleClientId: data.googleClientId,
      microsoftEnabled: data.microsoftEnabled === true,
      microsoftClientId: data.microsoftClientId,
      microsoftTenantId: data.microsoftTenantId,
    };
  } catch {
    return { googleEnabled: false, microsoftEnabled: false };
  }
}

/**
 * Initiate Google Sign-In and return NemakiWare auth token.
 *
 * Uses Google Identity Services "Sign In With Google" button rendered in a
 * temporary container. The callback receives a JWT credential (ID token)
 * which is sent to /google/convert for server-side verification.
 */
export async function signInWithGoogle(
  clientId: string,
  repositoryId: string
): Promise<AuthToken> {
  await loadGoogleIdentityScript();

  return new Promise<AuthToken>((resolve, reject) => {
    // @ts-expect-error google.accounts is loaded dynamically
    const google = window.google;
    if (!google?.accounts?.id) {
      reject(new Error('Google Identity Services not loaded'));
      return;
    }

    google.accounts.id.initialize({
      client_id: clientId,
      callback: async (response: { credential: string }) => {
        try {
          // response.credential is a JWT (ID token) signed by Google
          const authToken = await convertGoogleToken(response.credential, repositoryId);
          resolve(authToken);
        } catch (err) {
          reject(err);
        }
      },
      auto_select: false,
      cancel_on_tap_outside: false,
    });

    // Render a temporary Google Sign-In button and click it programmatically
    const container = document.createElement('div');
    container.id = 'google-signin-temp';
    container.style.position = 'fixed';
    container.style.top = '-9999px';
    document.body.appendChild(container);

    google.accounts.id.renderButton(container, {
      type: 'standard',
      theme: 'outline',
      size: 'large',
    });

    // Click the rendered button to trigger the popup
    const btn = container.querySelector('div[role="button"]') as HTMLElement;
    if (btn) {
      btn.click();
    } else {
      // Fallback: use One Tap prompt
      google.accounts.id.prompt();
    }

    // Cleanup temp container after a delay
    setTimeout(() => container.remove(), 30000);
  });
}

/**
 * Initiate Microsoft Sign-In using popup and return NemakiWare auth token.
 *
 * Uses MSAL.js (loaded dynamically) to perform Authorization Code flow
 * with PKCE in a popup window.
 */
export async function signInWithMicrosoft(
  clientId: string,
  tenantId: string,
  repositoryId: string
): Promise<AuthToken> {
  // Dynamically import MSAL
  const { PublicClientApplication } = await import('@azure/msal-browser');

  const msalConfig = {
    auth: {
      clientId,
      authority: `https://login.microsoftonline.com/${tenantId}`,
      redirectUri: `${window.location.origin}/core/ui/`,
    },
  };

  const msalInstance = new PublicClientApplication(msalConfig);
  await msalInstance.initialize();

  const loginResponse = await msalInstance.loginPopup({
    scopes: ['openid', 'profile', 'email'],
  });

  if (!loginResponse.idToken) {
    throw new Error('Microsoft login did not return an ID token');
  }

  return convertMicrosoftToken(loginResponse.idToken, repositoryId);
}

/**
 * Convert Google ID token to NemakiWare auth token via server endpoint.
 */
async function convertGoogleToken(idToken: string, repositoryId: string): Promise<AuthToken> {
  const response = await fetch(`/core/rest/repo/${repositoryId}/authtoken/google/convert`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id_token: idToken }),
  });

  if (!response.ok) {
    throw new Error('Failed to convert Google token');
  }

  const result = await response.json();
  if (!result.status) {
    throw new Error(result.errMsg?.[0] || 'Google authentication failed');
  }

  return {
    token: result.value.token,
    repositoryId,
    username: result.value.userName,
    authMethod: 'google',
  };
}

/**
 * Convert Microsoft ID token to NemakiWare auth token via server endpoint.
 */
async function convertMicrosoftToken(idToken: string, repositoryId: string): Promise<AuthToken> {
  const response = await fetch(`/core/rest/repo/${repositoryId}/authtoken/microsoft/convert`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id_token: idToken }),
  });

  if (!response.ok) {
    throw new Error('Failed to convert Microsoft token');
  }

  const result = await response.json();
  if (!result.status) {
    throw new Error(result.errMsg?.[0] || 'Microsoft authentication failed');
  }

  return {
    token: result.value.token,
    repositoryId,
    username: result.value.userName,
    authMethod: 'microsoft',
  };
}

/**
 * Load Google Identity Services script dynamically.
 */
function loadGoogleIdentityScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (document.getElementById('google-identity-script')) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.id = 'google-identity-script';
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('Failed to load Google Identity Services'));
    document.head.appendChild(script);
  });
}
