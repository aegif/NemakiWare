/**
 * Auth module exports
 * 
 * Provides authentication-related utilities for CMIS operations.
 */

export {
  getCmisAuthHeaders,
  hasCmisAuth,
  getCmisAuthUsername,
  getCmisAuthRepositoryId
} from './CmisAuthHeaderProvider';
