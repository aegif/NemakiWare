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

/**
 * Get Google Drive OAuth2 access token using popup flow.
 * Uses Google Identity Services to get an access token with drive.file scope.
 */
export function getGoogleDriveAccessToken(clientId: string): Promise<string> {
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
        callback: (response: { access_token?: string; error?: string }) => {
          if (response.error) {
            reject(new Error(response.error));
          } else if (response.access_token) {
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
 * Get Microsoft OneDrive access token using MSAL popup.
 */
export async function getOneDriveAccessToken(clientId: string, tenantId: string): Promise<string> {
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

  const loginResponse = await msalInstance.acquireTokenPopup({
    scopes: ['Files.ReadWrite'],
  });

  if (!loginResponse.accessToken) {
    throw new Error('Microsoft login did not return an access token');
  }

  return loginResponse.accessToken;
}
