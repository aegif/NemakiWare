/**
 * Domain services module exports
 * 
 * Provides high-level domain-specific services for CMIS operations.
 * Each service uses protocol clients (BrowserBindingClient, AtomPubClient)
 * internally and provides a clean, domain-focused API.
 * 
 * Services:
 * - DocumentService: Document CRUD, versioning, content stream
 * - FolderService: Folder CRUD, navigation, children
 * - AclService: ACL management
 * - SearchService: CMIS query, full-text search
 */

export {
  DocumentService,
  type CreateDocumentOptions,
  type UpdateDocumentOptions,
  type CheckInOptions,
  type Document
} from './DocumentService';

export {
  FolderService,
  type CreateFolderOptions,
  type UpdateFolderOptions,
  type GetChildrenOptions,
  type Folder,
  type ChildItem,
  type ChildrenResult
} from './FolderService';

export {
  AclService,
  type Ace,
  type Acl,
  type ApplyAclOptions
} from './AclService';

export {
  SearchService,
  type SearchOptions,
  type SearchResultItem,
  type SearchResult,
  type QueryBuilderOptions
} from './SearchService';
