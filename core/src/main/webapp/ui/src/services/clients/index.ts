/**
 * Clients module exports
 * 
 * Provides protocol-specific clients for CMIS operations.
 * - BrowserBindingClient: POST/JSON operations (create, update, delete)
 * - AtomPubClient: GET/XML operations (read, query, navigation)
 */

export {
  BrowserBindingClient,
  type BrowserBindingResult,
  type CmisAction
} from './BrowserBindingClient';

export {
  AtomPubClient,
  type AtomPubResult,
  type AtomPubPagination,
  type AtomPubFeedResult
} from './AtomPubClient';
