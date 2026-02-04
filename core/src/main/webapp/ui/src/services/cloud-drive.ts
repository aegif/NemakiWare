/**
 * Cloud Drive Service for pushing/pulling documents to/from Google Drive and OneDrive.
 */

/** Google Drive file metadata */
export interface GoogleDriveFile {
  id: string;
  name: string;
  mimeType: string;
  modifiedTime?: string;
  size?: string;
  iconLink?: string;
}

/** OneDrive file metadata */
export interface OneDriveFile {
  id: string;
  name: string;
  mimeType?: string;
  lastModifiedDateTime?: string;
  size?: number;
}

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
  // Server returns status: "success" | "failure" (string), and error: [...] array
  if (result.status !== 'success') {
    const errorMsg = Array.isArray(result.error) && result.error.length > 0
      ? result.error[0]
      : 'Failed to push to cloud';
    throw new Error(errorMsg);
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
  // Server returns status: "success" | "failure" (string), and error: [...] array
  if (result.status !== 'success') {
    const errorMsg = Array.isArray(result.error) && result.error.length > 0
      ? result.error[0]
      : 'Failed to pull from cloud';
    throw new Error(errorMsg);
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
  // Server returns status: "success" | "failure" (string)
  if (result.status !== 'success') {
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

      console.log('[GoogleDrive] Initializing OAuth2 token client with clientId:', clientId);
      console.log('[GoogleDrive] Current origin:', window.location.origin);

      // Track if promise has been resolved/rejected to avoid race conditions
      // COOP issues can cause error_callback to fire even after success
      let settled = false;

      const tokenClient = google.accounts.oauth2.initTokenClient({
        client_id: clientId,
        // drive.readonly: See and download all Google Drive files
        // drive.file: Only files created/opened by this app (too restrictive for import)
        // drive.activity.readonly: Required for fetching approval history and activities
        // documents.readonly: Required for fetching Google Docs approval metadata
        scope: 'https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.activity.readonly https://www.googleapis.com/auth/documents.readonly',
        hint: loginHint,
        callback: (response: { access_token?: string; error?: string; error_description?: string; expires_in?: number }) => {
          console.log('[GoogleDrive] OAuth2 callback received:', {
            hasToken: !!response.access_token,
            error: response.error,
            errorDescription: response.error_description
          });
          if (settled) {
            console.log('[GoogleDrive] Promise already settled, ignoring callback');
            return;
          }
          if (response.error) {
            settled = true;
            const errorMsg = response.error_description
              ? `${response.error}: ${response.error_description}`
              : response.error;
            reject(new Error(errorMsg));
          } else if (response.access_token) {
            settled = true;
            // Cache token (default 3600s expiry)
            const expiresIn = (response.expires_in || 3600) * 1000;
            _cachedGoogleDriveToken = {
              token: response.access_token,
              expiresAt: Date.now() + expiresIn,
            };
            resolve(response.access_token);
          } else {
            settled = true;
            reject(new Error('No access token received'));
          }
        },
        error_callback: (error: { type: string; message?: string }) => {
          console.log('[GoogleDrive] OAuth2 error_callback:', error);
          // IMPORTANT: If we already got a token, ignore the error_callback
          // This happens due to COOP (Cross-Origin-Opener-Policy) issues
          if (settled) {
            console.log('[GoogleDrive] Promise already settled, ignoring error_callback (COOP race condition)');
            return;
          }
          settled = true;
          // error.type can be: 'popup_failed_to_open', 'popup_closed', 'unknown'
          let errorMsg = 'Google OAuth error';
          if (error.type === 'popup_failed_to_open') {
            errorMsg = 'ポップアップがブロックされました。ポップアップを許可してください。';
          } else if (error.type === 'popup_closed') {
            errorMsg = 'ログインがキャンセルされました';
          } else if (error.message) {
            errorMsg = `${error.type}: ${error.message}`;
          } else {
            errorMsg = `OAuth error: ${error.type}`;
          }
          reject(new Error(errorMsg));
        },
      });

      console.log('[GoogleDrive] Requesting access token...');
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

  // Files.ReadWrite: Read and write files
  // Sites.Read.All: Read activities and metadata (for activity history)
  const scopes = ['Files.ReadWrite', 'Sites.Read.All'];

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

/**
 * List files from Google Drive.
 * Returns files from the user's Drive (not folders).
 */
export async function listGoogleDriveFiles(accessToken: string): Promise<GoogleDriveFile[]> {
  const response = await fetch(
    'https://www.googleapis.com/drive/v3/files?' +
    new URLSearchParams({
      q: "mimeType != 'application/vnd.google-apps.folder' and trashed = false",
      fields: 'files(id,name,mimeType,modifiedTime,size,iconLink)',
      orderBy: 'modifiedTime desc',
      pageSize: '100',
    }),
    {
      headers: { Authorization: `Bearer ${accessToken}` },
    }
  );

  if (!response.ok) {
    throw new Error(`Failed to list Google Drive files: ${response.status}`);
  }

  const data = await response.json();
  return data.files || [];
}

/**
 * List files from OneDrive.
 * Returns files from the user's root folder (excludes folders).
 */
export async function listOneDriveFiles(accessToken: string): Promise<OneDriveFile[]> {
  const response = await fetch(
    'https://graph.microsoft.com/v1.0/me/drive/root/children?' +
    new URLSearchParams({
      $select: 'id,name,file,lastModifiedDateTime,size',
      $orderby: 'lastModifiedDateTime desc',
      $top: '100',
    }),
    {
      headers: { Authorization: `Bearer ${accessToken}` },
    }
  );

  if (!response.ok) {
    throw new Error(`Failed to list OneDrive files: ${response.status}`);
  }

  const data = await response.json();
  // Filter out folders (items without 'file' property) on client side
  // Microsoft Graph API doesn't support "$filter=file ne null" syntax
  return (data.value || [])
    .filter((item: any) => item.file)
    .map((item: any) => ({
      id: item.id,
      name: item.name,
      mimeType: item.file?.mimeType,
      lastModifiedDateTime: item.lastModifiedDateTime,
      size: item.size,
    }));
}

/**
 * Import a file from Google Drive to NemakiWare.
 * Downloads the file from Google Drive and uploads it to the specified folder.
 */
export async function importFromGoogleDrive(
  repositoryId: string,
  folderId: string,
  file: GoogleDriveFile,
  accessToken: string
): Promise<{ objectId: string; name: string }> {
  // For Google Docs formats, export as Office format
  let downloadUrl: string;
  let exportMimeType: string | null = null;
  let fileName = file.name;

  if (file.mimeType.startsWith('application/vnd.google-apps.')) {
    // Google Docs format - need to export
    switch (file.mimeType) {
      case 'application/vnd.google-apps.document':
        exportMimeType = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
        fileName = file.name.endsWith('.docx') ? file.name : `${file.name}.docx`;
        break;
      case 'application/vnd.google-apps.spreadsheet':
        exportMimeType = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
        fileName = file.name.endsWith('.xlsx') ? file.name : `${file.name}.xlsx`;
        break;
      case 'application/vnd.google-apps.presentation':
        exportMimeType = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
        fileName = file.name.endsWith('.pptx') ? file.name : `${file.name}.pptx`;
        break;
      default:
        exportMimeType = 'application/pdf';
        fileName = file.name.endsWith('.pdf') ? file.name : `${file.name}.pdf`;
    }
    downloadUrl = `https://www.googleapis.com/drive/v3/files/${file.id}/export?mimeType=${encodeURIComponent(exportMimeType)}`;
  } else {
    // Regular file - direct download
    downloadUrl = `https://www.googleapis.com/drive/v3/files/${file.id}?alt=media`;
  }

  // Download file content from Google Drive
  console.log('[CloudDrive] Downloading from Google Drive:', downloadUrl);
  const downloadResponse = await fetch(downloadUrl, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  console.log('[CloudDrive] Download response status:', downloadResponse.status);

  if (!downloadResponse.ok) {
    const status = downloadResponse.status;
    if (status === 401) {
      throw new Error('Google Drive authentication expired. Please re-authenticate.');
    } else if (status === 403) {
      throw new Error('Access denied to Google Drive file. Please check file permissions.');
    } else if (status === 404) {
      throw new Error('Google Drive file not found. It may have been deleted.');
    } else if (status === 429) {
      throw new Error('Google Drive rate limit exceeded. Please try again later.');
    }
    throw new Error(`Failed to download from Google Drive (HTTP ${status})`);
  }

  const blob = await downloadResponse.blob();
  const finalMimeType = exportMimeType || file.mimeType;

  // Get NemakiWare auth token for REST API
  const authData = localStorage.getItem('nemakiware_auth');
  const authHeaders: Record<string, string> = {};
  if (authData) {
    try {
      const auth = JSON.parse(authData);
      if (auth.token) {
        authHeaders['nemaki_auth_token'] = auth.token;
      }
    } catch {
      console.warn('[CloudDrive] Failed to parse auth data');
    }
  }

  // Upload to NemakiWare using REST API (handles secondary type and comments)
  const formData = new FormData();
  formData.append('content', new File([blob], fileName, { type: finalMimeType }));
  formData.append('provider', 'google');
  formData.append('cloudFileId', file.id);
  formData.append('accessToken', accessToken);
  formData.append('fileName', fileName);

  const importUrl = `/core/rest/repo/${repositoryId}/cloud-drive/import/${folderId}`;
  console.log('[CloudDrive] Uploading to NemakiWare:', importUrl);
  console.log('[CloudDrive] Auth headers:', Object.keys(authHeaders));

  const uploadResponse = await fetch(importUrl, {
    method: 'POST',
    headers: authHeaders,
    body: formData,
  });

  console.log('[CloudDrive] Upload response status:', uploadResponse.status);
  const result = await uploadResponse.json();
  console.log('[CloudDrive] Upload result:', result);

  // Server returns status: "success" | "failure" (string), and error: [...] array
  if (result.status !== 'success') {
    const errorMsg = Array.isArray(result.error) && result.error.length > 0
      ? result.error[0]
      : 'Failed to import from Google Drive';
    console.error('[CloudDrive] Import failed:', errorMsg, result.error);
    throw new Error(errorMsg);
  }

  return {
    objectId: result.objectId,
    name: result.name || fileName,
  };
}

/**
 * Import a file from OneDrive to NemakiWare.
 * Downloads the file from OneDrive and uploads it to the specified folder.
 */
export async function importFromOneDrive(
  repositoryId: string,
  folderId: string,
  file: OneDriveFile,
  accessToken: string
): Promise<{ objectId: string; name: string }> {
  // Download file content from OneDrive
  const downloadResponse = await fetch(
    `https://graph.microsoft.com/v1.0/me/drive/items/${file.id}/content`,
    {
      headers: { Authorization: `Bearer ${accessToken}` },
    }
  );

  if (!downloadResponse.ok) {
    const status = downloadResponse.status;
    if (status === 401) {
      throw new Error('OneDrive authentication expired. Please re-authenticate.');
    } else if (status === 403) {
      throw new Error('Access denied to OneDrive file. Please check file permissions.');
    } else if (status === 404) {
      throw new Error('OneDrive file not found. It may have been deleted.');
    } else if (status === 429) {
      throw new Error('OneDrive rate limit exceeded. Please try again later.');
    }
    throw new Error(`Failed to download from OneDrive (HTTP ${status})`);
  }

  const blob = await downloadResponse.blob();
  const mimeType = file.mimeType || 'application/octet-stream';

  // Get NemakiWare auth token for REST API
  const authData = localStorage.getItem('nemakiware_auth');
  const authHeaders: Record<string, string> = {};
  if (authData) {
    try {
      const auth = JSON.parse(authData);
      if (auth.token) {
        authHeaders['nemaki_auth_token'] = auth.token;
      }
    } catch {
      console.warn('[CloudDrive] Failed to parse auth data');
    }
  }

  // Upload to NemakiWare using REST API (handles secondary type and comments)
  const formData = new FormData();
  formData.append('content', new File([blob], file.name, { type: mimeType }));
  formData.append('provider', 'microsoft');
  formData.append('cloudFileId', file.id);
  formData.append('accessToken', accessToken);
  formData.append('fileName', file.name);

  const uploadResponse = await fetch(`/core/rest/repo/${repositoryId}/cloud-drive/import/${folderId}`, {
    method: 'POST',
    headers: authHeaders,
    body: formData,
  });

  const result = await uploadResponse.json();

  // Server returns status: "success" | "failure" (string), and error: [...] array
  if (result.status !== 'success') {
    const errorMsg = Array.isArray(result.error) && result.error.length > 0
      ? result.error[0]
      : 'Failed to import from OneDrive';
    console.error('[CloudDrive] Import failed:', errorMsg, result.error);
    throw new Error(errorMsg);
  }

  return {
    objectId: result.objectId,
    name: result.name || file.name,
  };
}
