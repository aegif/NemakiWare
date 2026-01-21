/**
 * CMIS Property Utility Functions
 *
 * Helper functions for safely extracting values from CMIS Browser Binding property format.
 * CMIS Browser Binding returns properties in the format: {id: "propertyId", value: [...]}
 * These utilities handle both the {value: [...]} format and direct value format for compatibility.
 */

/**
 * Safely extract an array value from a CMIS property.
 * CMIS Browser Binding returns multi-value properties as {value: [...]}.
 * This function handles both formats:
 * - {value: [...]} - Browser Binding format
 * - [...] - Direct array format (legacy or parsed)
 * - undefined/null - Returns empty array
 *
 * @param property - The property value from CMIS object.properties
 * @returns Array of strings, empty array if property is not valid
 */
export function getSafeArrayValue(property: unknown): string[] {
  if (property === null || property === undefined) {
    return [];
  }

  // Handle Browser Binding format: {value: [...]}
  if (typeof property === 'object' && !Array.isArray(property) && property !== null) {
    const propObj = property as Record<string, unknown>;
    if ('value' in propObj) {
      const value = propObj.value;
      if (Array.isArray(value)) {
        return value.map(v => String(v));
      } else if (typeof value === 'string' && value !== '') {
        return [value];
      }
      return [];
    }
  }

  // Handle direct array format
  if (Array.isArray(property)) {
    return property.map(v => String(v));
  }

  // Handle single string value
  if (typeof property === 'string' && property !== '') {
    return [property];
  }

  return [];
}

/**
 * Safely extract a string value from a CMIS property.
 * CMIS Browser Binding returns single-value properties as {value: "..."}.
 * This function handles both formats:
 * - {value: "..."} - Browser Binding format
 * - "..." - Direct string format
 *
 * @param property - The property value from CMIS object.properties
 * @param defaultValue - Default value if property is not valid
 * @returns String value or default
 */
export function getSafeStringValue(property: unknown, defaultValue: string = ''): string {
  if (property === null || property === undefined) {
    return defaultValue;
  }

  // Handle Browser Binding format: {value: "..."}
  if (typeof property === 'object' && !Array.isArray(property) && property !== null) {
    const propObj = property as Record<string, unknown>;
    if ('value' in propObj) {
      const value = propObj.value;
      if (typeof value === 'string') {
        return value;
      } else if (value !== null && value !== undefined) {
        return String(value);
      }
      return defaultValue;
    }
  }

  // Handle direct string value
  if (typeof property === 'string') {
    return property;
  }

  // Handle other types by converting to string
  if (typeof property === 'number' || typeof property === 'boolean') {
    return String(property);
  }

  return defaultValue;
}

/**
 * Safely extract a boolean value from a CMIS property.
 * Handles both Browser Binding format and direct values.
 * Also handles string "true"/"false" as returned by AtomPub binding.
 *
 * @param property - The property value from CMIS object.properties
 * @param defaultValue - Default value if property is not valid
 * @returns Boolean value or default
 */
export function getSafeBooleanValue(property: unknown, defaultValue: boolean = false): boolean {
  if (property === null || property === undefined) {
    return defaultValue;
  }

  // Handle Browser Binding format: {value: true/false}
  if (typeof property === 'object' && !Array.isArray(property) && property !== null) {
    const propObj = property as Record<string, unknown>;
    if ('value' in propObj) {
      const value = propObj.value;
      if (typeof value === 'boolean') {
        return value;
      }
      if (typeof value === 'string') {
        return value === 'true';
      }
      return defaultValue;
    }
  }

  // Handle direct boolean value
  if (typeof property === 'boolean') {
    return property;
  }

  // Handle string "true"/"false"
  if (typeof property === 'string') {
    return property === 'true';
  }

  return defaultValue;
}
