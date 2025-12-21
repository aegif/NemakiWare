export interface PropertyDefinition {
  id: string;
  displayName: string;
  description: string;
  propertyType: 'string' | 'integer' | 'decimal' | 'boolean' | 'datetime';
  cardinality: 'single' | 'multi';
  required: boolean;
  queryable: boolean;
  updatable: boolean;
  defaultValue?: any[];
  choices?: Choice[];
  maxLength?: number;
  minValue?: number;
  maxValue?: number;
}

export interface Choice {
  displayName: string;
  value: any[];
}

export interface TypeDefinition {
  id: string;
  displayName: string;
  description: string;
  baseTypeId: string;
  parentTypeId?: string;
  propertyDefinitions: Record<string, PropertyDefinition>;
  creatable: boolean;
  fileable: boolean;
  queryable: boolean;
  deletable?: boolean;
  // Relationship-specific fields (only for cmis:relationship types)
  allowedSourceTypes?: string[];
  allowedTargetTypes?: string[];
}

export interface Permission {
  principalId: string;
  permissions: string[];
  direct: boolean;
}

export interface ACL {
  permissions: Permission[];
  isExact: boolean;
  aclInherited?: boolean;
}

export interface User {
  id: string;
  name: string;
  firstName?: string;
  lastName?: string;
  email?: string;
  password?: string;
  groups: string[];
}

export interface Group {
  id: string;
  name: string;
  members: string[];
}

/**
 * CMIS AllowableActions object
 * Contains boolean flags indicating which operations are allowed on an object.
 * This matches the CMIS 1.1 specification where allowableActions is an object
 * with boolean properties, NOT an array of strings.
 */
export interface AllowableActions {
  canDeleteObject?: boolean;
  canUpdateProperties?: boolean;
  canGetFolderTree?: boolean;
  canGetProperties?: boolean;
  canGetObjectRelationships?: boolean;
  canGetObjectParents?: boolean;
  canGetFolderParent?: boolean;
  canGetDescendants?: boolean;
  canMoveObject?: boolean;
  canDeleteContentStream?: boolean;
  canCheckOut?: boolean;
  canCancelCheckOut?: boolean;
  canCheckIn?: boolean;
  canSetContentStream?: boolean;
  canGetAllVersions?: boolean;
  canAddObjectToFolder?: boolean;
  canRemoveObjectFromFolder?: boolean;
  canGetContentStream?: boolean;
  canApplyPolicy?: boolean;
  canGetAppliedPolicies?: boolean;
  canRemovePolicy?: boolean;
  canGetChildren?: boolean;
  canCreateDocument?: boolean;
  canCreateFolder?: boolean;
  canCreateRelationship?: boolean;
  canDeleteTree?: boolean;
  canGetRenditions?: boolean;
  canGetACL?: boolean;
  canApplyACL?: boolean;
}

export interface CMISObject {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  properties: Record<string, any>;
  allowableActions?: AllowableActions;
  parentId?: string;
  path?: string;
  contentStreamLength?: number;
  contentStreamMimeType?: string;
  versionLabel?: string;
  isLatestVersion?: boolean;
  isLatestMajorVersion?: boolean;
  createdBy?: string;
  creationDate?: string;
  lastModifiedBy?: string;
  lastModificationDate?: string;
  aclInherited?: boolean;
  secondaryTypeIds?: string[];
}

export interface SearchResult {
  objects: CMISObject[];
  hasMoreItems: boolean;
  numItems: number;
}

export interface VersionHistory {
  versions: CMISObject[];
  latestVersion: CMISObject;
}

export interface Relationship {
  id: string;
  sourceId: string;
  targetId: string;
  relationshipType: string;
  properties: Record<string, any>;
}

export interface ActionDefinition {
  id: string;
  title: string;
  description: string;
  triggerType: 'UserButton' | 'UserCreate';
  canExecute: boolean;
  fontAwesome?: string;
}

export interface ActionFormField {
  name: string;
  type: 'text' | 'select' | 'textarea' | 'number' | 'date';
  label: string;
  required: boolean;
  options?: { value: string; label: string }[];
  defaultValue?: any;
}

export interface ActionForm {
  actionId: string;
  title: string;
  fields: ActionFormField[];
}

export interface ActionExecutionResult {
  success: boolean;
  message: string;
  data?: any;
}
