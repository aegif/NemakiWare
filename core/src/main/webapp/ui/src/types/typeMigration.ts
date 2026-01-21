export type MigrationPropertyType =
  | 'string'
  | 'integer'
  | 'decimal'
  | 'boolean'
  | 'datetime'
  | 'id'
  | 'html'
  | 'uri';

export interface MigrationPropertyDefinition {
  id: string;
  displayName: string;
  description?: string;
  propertyType: MigrationPropertyType;
  cardinality: 'single' | 'multi';
  required: boolean;
  defaultValue?: unknown;
  choices?: Array<{ displayName: string; value: unknown }>;
}

export interface CompatibleType {
  id: string;
  displayName: string;
  description?: string;
  baseTypeId: string;
  additionalRequiredProperties: Record<string, MigrationPropertyDefinition>;
}
