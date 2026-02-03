/**
 * Cloud Drive Service for pushing/pulling documents to/from Google Drive and OneDrive.
 */

export interface CloudDrivePushResult {
  cloudFileId: string;
  cloudFileUrl: string;
  provider: string;
}

export interface CloudDrivePullResult {
  objectId: string;
  pulled: boolean;
}

export interface CloudDriveUrlResult {
  cloudFileUrl: string;
  provider: string;
  cloudFileId: string;
}

/**
 * Push a document to cloud drive.
 */
export async function pushToCloud(
  repositoryId: string,
  objectId: string,
  provider: 'google' | 'microsoft',
  accessToken: string
): Promise<CloudDrivePushResult> {
  const response = await fetch(`/core/rest/repo/${repositoryId}/cloud-drive/push/${objectId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, accessToken }),
  });

  const result = await response.json();
  if (!result.status) {
    throw new Error(result.errMsg?.[0] || 'Failed to push to cloud');
  }
  return result;
}

/**
 * Pull a document from cloud drive.
 */
export async function pullFromCloud(
  repositoryId: string,
  objectId: string,
  provider: 'google' | 'microsoft',
  accessToken: string,
  cloudFileId: string
): Promise<CloudDrivePullResult> {
  const response = await fetch(`/core/rest/repo/${repositoryId}/cloud-drive/pull/${objectId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, accessToken, cloudFileId }),
  });

  const result = await response.json();
  if (!result.status) {
    throw new Error(result.errMsg?.[0] || 'Failed to pull from cloud');
  }
  return result;
}

/**
 * Get cloud file URL for a document.
 */
export async function getCloudUrl(
  repositoryId: string,
  objectId: string
): Promise<CloudDriveUrlResult | null> {
  const response = await fetch(`/core/rest/repo/${repositoryId}/cloud-drive/url/${objectId}`);
  const result = await response.json();
  if (!result.status) {
    return null;
  }
  return result;
}

// Session cache for Google Drive access token (avoids repeated OAuth2 popups)
let _cachedGoogleDriveToken: { token: string; expiresAt: number } | null = null;

/**
 * Get Google Drive OAuth2 access token using popup flow.
 * Uses Google Identity Services to get an access token with drive.file scope.
 * Caches the token for the session duration (typically 1 hour).
 * @param clientId - Google OAuth2 client ID
 * @param loginHint - Optional email to pre-select the Google account (skips account chooser)
 */
export function getGoogleDriveAccessToken(clientId: string, loginHint?: string): Promise<string> {
  // Return cached token if still valid (with 60s margin)
  if (_cachedGoogleDriveToken && Date.now() < _cachedGoogleDriveToken.expiresAt - 60000) {
    return Promise.resolve(_cachedGoogleDriveToken.token);
  }

  return new Promise((resolve, reject) => {
    // Load GIS script if needed
    const loadScript = (): Promise<void> => {
      return new Promise((res, rej) => {
        if (document.getElementById('google-identity-script')) {
          res();
          return;
        }
        const script = document.createElement('script');
        script.id = 'google-identity-script';
        script.src = 'https://accounts.google.com/gsi/client';
        script.async = true;
        script.onload = () => res();
        script.onerror = () => rej(new Error('Failed to load Google Identity Services'));
        document.head.appendChild(script);
      });
    };

    loadScript().then(() => {
      // @ts-expect-error google.accounts is loaded dynamically
      const google = window.google;
      if (!google?.accounts?.oauth2) {
        reject(new Error('Google OAuth2 not available'));
        return;
      }

      const tokenClient = google.accounts.oauth2.initTokenClient({
        client_id: clientId,
        scope: 'https://www.googleapis.com/auth/drive.file',
        hint: loginHint,
        callback: (response: { access_token?: string; error?: string; expires_in?: number }) => {
          if (response.error) {
            reject(new Error(response.error));
          } else if (response.access_token) {
            // Cache token (default 3600s expiry)
            const expiresIn = (response.expires_in || 3600) * 1000;
            _cachedGoogleDriveToken = {
              token: response.access_token,
              expiresAt: Date.now() + expiresIn,
            };
            resolve(response.access_token);
          } else {
            reject(new Error('No access token received'));
          }
        },
      });

      tokenClient.requestAccessToken();
    }).catch(reject);
  });
}

/**
 * Get Microsoft OneDrive access token using MSAL.
 * Reuses the singleton MSAL instance from cloud-auth.ts.
 * Tries silent token acquisition first, falls back to popup.
 */
export async function getOneDriveAccessToken(clientId: string, tenantId: string): Promise<string> {
  const { PublicClientApplication } = await import('@azure/msal-browser');
  const { msalInstance: sharedInstance } = await import('./cloud-auth');

  let instance = sharedInstance;
  if (!instance) {
    // Initialize MSAL if not yet done (user hasn't logged in via Microsoft)
    instance = new PublicClientApplication({
      auth: {
        clientId,
        authority: `https://login.microsoftonline.com/${tenantId}`,
        redirectUri: `${window.location.origin}/core/ui/auth-popup.html`,
      },
    });
    await instance.initialize();
  }

  const scopes = ['Files.ReadWrite'];

  // Try silent acquisition first (if user already logged in via Microsoft)
  const accounts = instance.getAllAccounts();
  if (accounts.length > 0) {
    try {
      const silentResponse = await instance.acquireTokenSilent({
        scopes,
        account: accounts[0],
      });
      if (silentResponse.accessToken) {
        return silentResponse.accessToken;
      }
    } catch {
      // Silent failed, fall through to popup
    }
  }

  // Fall back to popup
  const popupResponse = await instance.acquireTokenPopup({ scopes });

  if (!popupResponse.accessToken) {
    throw new Error('Microsoft login did not return an access token');
  }

  return popupResponse.accessToken;
}
