/**
 * WebAuthn API service for passkey registration and authentication.
 * Handles Base64url encoding/decoding and WebAuthn API calls.
 */

import { parseJsonResponseBody } from './http/jsonFetch';
import { getResourceBaseErrorMessage, isResourceBaseSuccess } from './http/restResult';

// Base64url encode/decode utilities
function base64urlEncode(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlDecode(str: string): ArrayBuffer {
  // Add padding
  str = str.replace(/-/g, '+').replace(/_/g, '/');
  while (str.length % 4) {
    str += '=';
  }
  const binary = atob(str);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

export interface PasskeyCredential {
  id: string;
  credentialId: string;
  displayName: string;
  createdAt: number | null;
  transports: string[] | null;
}

export class WebAuthnService {
  private baseUrl: string;

  constructor(baseUrl: string = '') {
    this.baseUrl = baseUrl || window.location.origin;
  }

  /**
   * Check if WebAuthn is supported in this browser
   */
  isSupported(): boolean {
    return !!(window.PublicKeyCredential);
  }

  /**
   * Begin passkey registration
   */
  async registerBegin(repositoryId: string): Promise<PublicKeyCredentialCreationOptions> {
    const res = await fetch(`${this.baseUrl}/core/rest/repo/${repositoryId}/webauthn/register/begin`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
    });
    const data = (await parseJsonResponseBody(res, 'webauthn.registerBegin')) as Record<string, unknown>;
    if (!isResourceBaseSuccess(data)) {
      throw new Error(getResourceBaseErrorMessage(data, 'Registration begin failed'));
    }

    const options = data.options as Record<string, any>;

    // Convert Base64url fields to ArrayBuffer for WebAuthn API
    options.challenge = base64urlDecode(options.challenge);
    options.user.id = base64urlDecode(options.user.id);
    if (options.excludeCredentials) {
      options.excludeCredentials = options.excludeCredentials.map((cred: any) => ({
        ...cred,
        id: base64urlDecode(cred.id),
      }));
    }

    // Clean up null values that the WebAuthn browser API doesn't accept
    // (Yubico library serializes Optional.empty() as null)
    if (options.timeout === null || options.timeout === undefined) {
      delete options.timeout;
    }
    if (options.authenticatorSelection) {
      if (options.authenticatorSelection.authenticatorAttachment === null) {
        delete options.authenticatorSelection.authenticatorAttachment;
      }
    }
    // Remove null extension values
    if (options.extensions) {
      for (const key of Object.keys(options.extensions)) {
        if (options.extensions[key] === null) {
          delete options.extensions[key];
        }
      }
    }

    return options as unknown as PublicKeyCredentialCreationOptions;
  }

  /**
   * Complete passkey registration
   */
  async registerComplete(
    repositoryId: string,
    credential: PublicKeyCredential,
    displayName?: string
  ): Promise<{ credentialId: string; displayName: string }> {
    const attestationResponse = credential.response as AuthenticatorAttestationResponse;

    const credentialData = {
      id: credential.id,
      rawId: base64urlEncode(credential.rawId),
      type: credential.type,
      response: {
        clientDataJSON: base64urlEncode(attestationResponse.clientDataJSON),
        attestationObject: base64urlEncode(attestationResponse.attestationObject),
        transports: attestationResponse.getTransports?.() || [],
      },
      clientExtensionResults: credential.getClientExtensionResults(),
    };

    const res = await fetch(`${this.baseUrl}/core/rest/repo/${repositoryId}/webauthn/register/complete`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      body: JSON.stringify({
        credential: credentialData,
        displayName: displayName || undefined,
      }),
    });
    const data = (await parseJsonResponseBody(res, 'webauthn.registerComplete')) as Record<string, unknown>;
    if (!isResourceBaseSuccess(data)) {
      throw new Error(getResourceBaseErrorMessage(data, 'Registration complete failed'));
    }
    return { credentialId: data.credentialId as string, displayName: data.displayName as string };
  }

  /**
   * Begin passkey authentication
   */
  async authenticateBegin(repositoryId: string): Promise<PublicKeyCredentialRequestOptions> {
    const res = await fetch(`${this.baseUrl}/core/rest/repo/${repositoryId}/webauthn/authenticate/begin`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
    });
    const data = (await parseJsonResponseBody(res, 'webauthn.authenticateBegin')) as Record<string, unknown>;
    if (!isResourceBaseSuccess(data)) {
      throw new Error(getResourceBaseErrorMessage(data, 'Authentication begin failed'));
    }

    const options = data.options as Record<string, any>;

    // Convert Base64url fields to ArrayBuffer for WebAuthn API
    options.challenge = base64urlDecode(options.challenge);
    if (options.allowCredentials && Array.isArray(options.allowCredentials)) {
      options.allowCredentials = options.allowCredentials.map((cred: any) => ({
        ...cred,
        id: base64urlDecode(cred.id),
      }));
    } else {
      // Browser WebAuthn API rejects null; remove it so the browser treats it as discoverable
      delete options.allowCredentials;
    }

    // Clean up null values that the WebAuthn browser API doesn't accept
    if (options.timeout === null || options.timeout === undefined) {
      delete options.timeout;
    }
    if (options.extensions) {
      for (const key of Object.keys(options.extensions)) {
        if (options.extensions[key] === null) {
          delete options.extensions[key];
        }
      }
    }

    return options as unknown as PublicKeyCredentialRequestOptions;
  }

  /**
   * Complete passkey authentication
   */
  async authenticateComplete(
    repositoryId: string,
    credential: PublicKeyCredential
  ): Promise<{
    userName: string;
    token: string;
    expiration: number;
    repositoryId: string;
  }> {
    const assertionResponse = credential.response as AuthenticatorAssertionResponse;

    const credentialData = {
      id: credential.id,
      rawId: base64urlEncode(credential.rawId),
      type: credential.type,
      response: {
        clientDataJSON: base64urlEncode(assertionResponse.clientDataJSON),
        authenticatorData: base64urlEncode(assertionResponse.authenticatorData),
        signature: base64urlEncode(assertionResponse.signature),
        userHandle: assertionResponse.userHandle
          ? base64urlEncode(assertionResponse.userHandle)
          : null,
      },
      clientExtensionResults: credential.getClientExtensionResults(),
    };

    const res = await fetch(`${this.baseUrl}/core/rest/repo/${repositoryId}/webauthn/authenticate/complete`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      body: JSON.stringify({ credential: credentialData }),
    });
    const data = (await parseJsonResponseBody(res, 'webauthn.authenticateComplete')) as Record<string, unknown>;
    if (!isResourceBaseSuccess(data)) {
      throw new Error(getResourceBaseErrorMessage(data, 'Authentication failed'));
    }
    return data.value as {
      userName: string;
      token: string;
      expiration: number;
      repositoryId: string;
    };
  }

  /**
   * List registered passkeys for the current user
   */
  async listCredentials(repositoryId: string): Promise<PasskeyCredential[]> {
    const res = await fetch(`${this.baseUrl}/core/rest/repo/${repositoryId}/webauthn/credentials`, {
      method: 'GET',
      credentials: 'include',
    });
    const data = (await parseJsonResponseBody(res, 'webauthn.listCredentials')) as Record<string, unknown>;
    if (!isResourceBaseSuccess(data)) {
      throw new Error(getResourceBaseErrorMessage(data, 'Failed to list credentials'));
    }
    return (data.credentials as PasskeyCredential[]) || [];
  }

  /**
   * Delete a registered passkey
   */
  async deleteCredential(repositoryId: string, id: string): Promise<void> {
    const res = await fetch(`${this.baseUrl}/core/rest/repo/${repositoryId}/webauthn/credentials/${id}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
    });
    const data = (await parseJsonResponseBody(res, 'webauthn.deleteCredential')) as Record<string, unknown>;
    if (!isResourceBaseSuccess(data)) {
      throw new Error(getResourceBaseErrorMessage(data, 'Failed to delete credential'));
    }
  }
}
