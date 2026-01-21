/**
 * Browser Binding Mappers - Pure functions for mapping CMIS Browser Binding JSON responses
 * 
 * This module provides pure functions for mapping Browser Binding JSON responses from CMIS servers.
 * These functions are extracted from CMISService to improve testability and maintainability.
 * 
 * Design Principles:
 * - Pure functions: No side effects, same input always produces same output
 * - Fail-safe: Returns default values on parse errors instead of throwing
 * - Type-safe: Returns strongly typed objects
 * - Null-safe: Handles missing/undefined properties gracefully
 * 
 * Browser Binding Response Format:
 * - Uses JSON instead of XML
 * - Properties in 'succinctProperties' or 'properties' object
 * - Allowable actions in 'allowableActions' object
 */

/**
 * Parsed CMIS properties from Browser Binding JSON
 */
export interface BrowserBindingProperties {
  [key: string]: unknown;
}

/**
 * Browser Binding object response structure
 */
export interface BrowserBindingObject {
  succinctProperties?: BrowserBindingProperties;
  properties?: Record<string, { value?: unknown; type?: string }>;
  allowableActions?: Record<string, boolean>;
  [key: string]: unknown;
}

/**
 * Safely parse JSON string
 * 
 * @param jsonString Raw JSON string
 * @returns Parsed object or null if parsing failed
 */
export function safeParseJson<T = unknown>(jsonString: string): T | null {
  try {
    return JSON.parse(jsonString) as T;
  } catch (e) {
    return null;
  }
}

/**
 * Get a string property value safely
 * 
 * @param props Properties object (succinctProperties or raw object)
 * @param key Property key
 * @param defaultValue Default value if property is missing
 * @returns Property value as string
 */
export function getSafeStringProperty(
  props: BrowserBindingProperties | null | undefined,
  key: string,
  defaultValue: string = ''
): string {
  if (!props) return defaultValue;
  
  const value = props[key];
  if (value === null || value === undefined) return defaultValue;
  
  return String(value);
}

/**
 * Get an integer property value safely
 * 
 * @param props Properties object
 * @param key Property key
 * @param defaultValue Default value if property is missing or invalid
 * @returns Property value as number
 */
export function getSafeIntegerProperty(
  props: BrowserBindingProperties | null | undefined,
  key: string,
  defaultValue: number = 0
): number {
  if (!props) return defaultValue;
  
  const value = props[key];
  if (value === null || value === undefined) return defaultValue;
  
  const numValue = typeof value === 'number' ? value : parseInt(String(value), 10);
  return isNaN(numValue) ? defaultValue : numValue;
}

/**
 * Get a boolean property value safely
 * 
 * @param props Properties object
 * @param key Property key
 * @param defaultValue Default value if property is missing
 * @returns Property value as boolean
 */
export function getSafeBooleanProperty(
  props: BrowserBindingProperties | null | undefined,
  key: string,
  defaultValue: boolean = false
): boolean {
  if (!props) return defaultValue;
  
  const value = props[key];
  if (value === null || value === undefined) return defaultValue;
  
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') return value.toLowerCase() === 'true';
  
  return Boolean(value);
}

/**
 * Get a date property value safely
 * 
 * @param props Properties object
 * @param key Property key
 * @returns ISO date string or undefined if property is missing/invalid
 */
export function getSafeDateProperty(
  props: BrowserBindingProperties | null | undefined,
  key: string
): string | undefined {
  if (!props) return undefined;
  
  const value = props[key];
  if (value === null || value === undefined) return undefined;
  
  // Handle numeric timestamp (milliseconds since epoch)
  if (typeof value === 'number') {
    try {
      return new Date(value).toISOString();
    } catch (e) {
      return undefined;
    }
  }
  
  // Handle string date
  if (typeof value === 'string' && value.trim()) {
    return value;
  }
  
  return undefined;
}

/**
 * Get an array property value safely
 * 
 * @param props Properties object
 * @param key Property key
 * @returns Array value or empty array if property is missing/invalid
 */
export function getSafeArrayProperty<T = unknown>(
  props: BrowserBindingProperties | null | undefined,
  key: string
): T[] {
  if (!props) return [];
  
  const value = props[key];
  if (!Array.isArray(value)) return [];
  
  return value as T[];
}

/**
 * Extract properties from Browser Binding response
 * Handles both 'succinctProperties' and 'properties' formats
 * 
 * @param response Browser Binding response object
 * @returns Normalized properties object
 */
export function extractProperties(response: BrowserBindingObject | null | undefined): BrowserBindingProperties {
  if (!response) return {};
  
  // Prefer succinctProperties (simpler format)
  if (response.succinctProperties) {
    return response.succinctProperties;
  }
  
  // Fall back to properties (verbose format with type info)
  if (response.properties) {
    const result: BrowserBindingProperties = {};
    for (const [key, propObj] of Object.entries(response.properties)) {
      if (propObj && typeof propObj === 'object' && 'value' in propObj) {
        result[key] = propObj.value;
      }
    }
    return result;
  }
  
  return {};
}

/**
 * Extract allowable actions from Browser Binding response
 * 
 * @param response Browser Binding response object
 * @returns Array of allowed action names
 */
export function extractAllowableActions(response: BrowserBindingObject | null | undefined): string[] {
  if (!response?.allowableActions) return [];
  
  const actions: string[] = [];
  for (const [action, allowed] of Object.entries(response.allowableActions)) {
    if (allowed === true) {
      actions.push(action);
    }
  }
  
  return actions;
}

/**
 * Check if a specific action is allowed
 * 
 * @param response Browser Binding response object
 * @param action Action name to check
 * @returns true if action is allowed
 */
export function isActionAllowed(
  response: BrowserBindingObject | null | undefined,
  action: string
): boolean {
  if (!response?.allowableActions) return false;
  return response.allowableActions[action] === true;
}

/**
 * Map Browser Binding response to a standard CMISObject-like structure
 * 
 * @param response Browser Binding response object
 * @returns Mapped object with standard properties
 */
export function mapToCmisObject(response: BrowserBindingObject | null | undefined): {
  id: string;
  name: string;
  objectType: string;
  baseType: string;
  createdBy?: string;
  lastModifiedBy?: string;
  creationDate?: string;
  lastModificationDate?: string;
  contentStreamLength?: number;
  contentStreamMimeType?: string;
  path?: string;
  properties: BrowserBindingProperties;
  allowableActions: string[];
} | null {
  if (!response) return null;
  
  const props = extractProperties(response);
  const allowableActions = extractAllowableActions(response);
  
  const id = getSafeStringProperty(props, 'cmis:objectId', '');
  if (!id) return null;
  
  return {
    id,
    name: getSafeStringProperty(props, 'cmis:name', 'Unknown'),
    objectType: getSafeStringProperty(props, 'cmis:objectTypeId', 'cmis:document'),
    baseType: getSafeStringProperty(props, 'cmis:baseTypeId', 'cmis:document'),
    createdBy: getSafeStringProperty(props, 'cmis:createdBy') || undefined,
    lastModifiedBy: getSafeStringProperty(props, 'cmis:lastModifiedBy') || undefined,
    creationDate: getSafeDateProperty(props, 'cmis:creationDate'),
    lastModificationDate: getSafeDateProperty(props, 'cmis:lastModificationDate'),
    contentStreamLength: getSafeIntegerProperty(props, 'cmis:contentStreamLength') || undefined,
    contentStreamMimeType: getSafeStringProperty(props, 'cmis:contentStreamMimeType') || undefined,
    path: getSafeStringProperty(props, 'cmis:path') || undefined,
    properties: props,
    allowableActions
  };
}

/**
 * Map Browser Binding children response to array of objects
 * 
 * @param response Browser Binding response with 'objects' array
 * @returns Array of mapped objects
 */
export function mapChildrenResponse(response: { objects?: BrowserBindingObject[] } | null | undefined): ReturnType<typeof mapToCmisObject>[] {
  if (!response?.objects || !Array.isArray(response.objects)) {
    return [];
  }
  
  return response.objects
    .map(obj => mapToCmisObject(obj.object as BrowserBindingObject || obj))
    .filter((obj): obj is NonNullable<typeof obj> => obj !== null);
}
