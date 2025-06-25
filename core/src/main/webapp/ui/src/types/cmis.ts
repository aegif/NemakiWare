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
}

export interface Permission {
  principalId: string;
  permissions: string[];
  direct: boolean;
}

export interface ACL {
  permissions: Permission[];
  isExact: boolean;
}

export interface User {
  id: string;
  name: string;
  email?: string;
  groups: string[];
}

export interface Group {
  id: string;
  name: string;
  members: string[];
}

export interface CMISObject {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  properties: Record<string, any>;
  allowableActions: string[];
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
