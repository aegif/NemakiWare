/**
 * AclService - Domain service for ACL (Access Control List) operations
 * 
 * This service provides a high-level API for ACL-related CMIS operations.
 * It uses BrowserBindingClient for write operations and AtomPubClient for read operations.
 * 
 * Operations:
 * - Get ACL
 * - Apply ACL (add/remove ACEs)
 */

import { BrowserBindingClient } from '../clients/BrowserBindingClient';
import { AtomPubClient } from '../clients/AtomPubClient';
import { CmisHttpClient } from '../http';

/**
 * Access Control Entry (ACE)
 */
export interface Ace {
  principalId: string;
  permissions: string[];
  isDirect?: boolean;
}

/**
 * Access Control List
 */
export interface Acl {
  aces: Ace[];
  isExact?: boolean;
}

/**
 * Apply ACL options
 */
export interface ApplyAclOptions {
  addAces?: Ace[];
  removeAces?: Ace[];
}

/**
 * AclService - High-level API for ACL operations
 */
export class AclService {
  private browserClient: BrowserBindingClient;
  private atomClient: AtomPubClient;
  private repositoryId: string;

  /**
   * Create a new AclService
   * 
   * @param httpClient CmisHttpClient instance
   * @param repositoryId Repository ID
   * @param browserBaseUrl Base URL for Browser Binding (default: '/core/browser')
   * @param atomBaseUrl Base URL for AtomPub (default: '/core/atom')
   */
  constructor(
    httpClient: CmisHttpClient,
    repositoryId: string,
    browserBaseUrl: string = '/core/browser',
    atomBaseUrl: string = '/core/atom'
  ) {
    this.browserClient = new BrowserBindingClient(httpClient, browserBaseUrl);
    this.atomClient = new AtomPubClient(httpClient, atomBaseUrl);
    this.repositoryId = repositoryId;
  }

  /**
   * Set the repository ID
   */
  setRepositoryId(repositoryId: string): void {
    this.repositoryId = repositoryId;
  }

  /**
   * Get ACL for an object
   * 
   * @param objectId Object ID
   * @returns ACL or error
   */
  async getAcl(
    objectId: string
  ): Promise<{ success: boolean; acl?: Acl; error?: string }> {
    // Use AtomPub to get object with ACL included
    const result = await this.atomClient.getObject(this.repositoryId, objectId, {
      includeACL: true
    });

    if (!result.success || !result.data) {
      return { success: false, error: result.error };
    }

    // Extract ACL from the response
    // Note: The actual ACL parsing depends on the AtomPub response format
    // This is a simplified implementation
    const acl: Acl = {
      aces: [],
      isExact: true
    };

    // The ACL would be in the entry's acl property if available
    // For now, return empty ACL - full implementation would parse from XML
    return { success: true, acl };
  }

  /**
   * Apply ACL changes to an object
   * 
   * @param objectId Object ID
   * @param options ACL changes (add/remove ACEs)
   * @returns Updated ACL or error
   */
  async applyAcl(
    objectId: string,
    options: ApplyAclOptions
  ): Promise<{ success: boolean; acl?: Acl; error?: string }> {
    const { addAces = [], removeAces = [] } = options;

    // Convert ACEs to the format expected by BrowserBindingClient
    const addAcesFormatted = addAces.map(ace => ({
      principal: ace.principalId,
      permissions: ace.permissions
    }));

    const removeAcesFormatted = removeAces.map(ace => ({
      principal: ace.principalId,
      permissions: ace.permissions
    }));

    const result = await this.browserClient.applyACL(
      this.repositoryId,
      objectId,
      addAcesFormatted,
      removeAcesFormatted
    );

    if (!result.success) {
      return { success: false, error: result.error };
    }

    // Fetch the updated ACL
    return this.getAcl(objectId);
  }

  /**
   * Add permissions for a principal
   * 
   * @param objectId Object ID
   * @param principalId Principal ID (user or group)
   * @param permissions Permissions to add
   * @returns Updated ACL or error
   */
  async addPermissions(
    objectId: string,
    principalId: string,
    permissions: string[]
  ): Promise<{ success: boolean; acl?: Acl; error?: string }> {
    return this.applyAcl(objectId, {
      addAces: [{ principalId, permissions }]
    });
  }

  /**
   * Remove permissions for a principal
   * 
   * @param objectId Object ID
   * @param principalId Principal ID (user or group)
   * @param permissions Permissions to remove
   * @returns Updated ACL or error
   */
  async removePermissions(
    objectId: string,
    principalId: string,
    permissions: string[]
  ): Promise<{ success: boolean; acl?: Acl; error?: string }> {
    return this.applyAcl(objectId, {
      removeAces: [{ principalId, permissions }]
    });
  }

  /**
   * Replace all permissions for a principal
   * 
   * @param objectId Object ID
   * @param principalId Principal ID (user or group)
   * @param permissions New permissions (replaces existing)
   * @returns Updated ACL or error
   */
  async setPermissions(
    objectId: string,
    principalId: string,
    permissions: string[]
  ): Promise<{ success: boolean; acl?: Acl; error?: string }> {
    // First get current ACL to find existing permissions
    const currentAcl = await this.getAcl(objectId);
    
    if (!currentAcl.success || !currentAcl.acl) {
      return { success: false, error: currentAcl.error || 'Failed to get current ACL' };
    }

    // Find existing ACE for this principal
    const existingAce = currentAcl.acl.aces.find(ace => ace.principalId === principalId);
    const existingPermissions = existingAce?.permissions || [];

    // Apply changes: remove old permissions, add new ones
    return this.applyAcl(objectId, {
      removeAces: existingPermissions.length > 0 
        ? [{ principalId, permissions: existingPermissions }] 
        : [],
      addAces: permissions.length > 0 
        ? [{ principalId, permissions }] 
        : []
    });
  }
}
