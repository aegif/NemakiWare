/**
 * HTTP module for CMIS operations
 * 
 * This module provides the HTTP boundary layer for CMIS operations,
 * separating HTTP concerns from CMIS business logic.
 */

export {
  CmisHttpClient,
  CmisNetworkError,
  type CmisHttpRequestOptions,
  type CmisHttpResponse,
  type HeaderProvider,
  type ResponseType
} from './CmisHttpClient';
