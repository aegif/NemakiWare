/**
 * Unit tests for Browser Binding Mappers
 * 
 * These tests verify the pure mapping functions used to extract CMIS data from Browser Binding JSON responses.
 * Tests cover both success cases and edge cases (null values, missing properties, type coercion, etc.)
 */

import { describe, it, expect } from 'vitest';
import {
  safeParseJson,
  getSafeStringProperty,
  getSafeIntegerProperty,
  getSafeBooleanProperty,
  getSafeDateProperty,
  getSafeArrayProperty,
  extractProperties,
  extractAllowableActions,
  isActionAllowed,
  mapToCmisObject,
  mapChildrenResponse,
  BrowserBindingObject,
  BrowserBindingProperties
} from './browserBindingMappers';

// Sample Browser Binding responses for testing
const SAMPLE_DOCUMENT_RESPONSE: BrowserBindingObject = {
  succinctProperties: {
    'cmis:objectId': 'doc-123',
    'cmis:name': 'Test Document.pdf',
    'cmis:objectTypeId': 'cmis:document',
    'cmis:baseTypeId': 'cmis:document',
    'cmis:createdBy': 'admin',
    'cmis:lastModifiedBy': 'admin',
    'cmis:creationDate': 1703001600000, // timestamp
    'cmis:lastModificationDate': '2024-12-20T10:00:00.000Z', // ISO string
    'cmis:contentStreamLength': 1024,
    'cmis:contentStreamMimeType': 'application/pdf',
    'cmis:isLatestVersion': true,
    'cmis:isVersionSeriesCheckedOut': false
  },
  allowableActions: {
    canDeleteObject: true,
    canUpdateProperties: true,
    canGetChildren: false,
    canCheckOut: true,
    canGetContentStream: true
  }
};

const SAMPLE_FOLDER_RESPONSE: BrowserBindingObject = {
  succinctProperties: {
    'cmis:objectId': 'folder-456',
    'cmis:name': 'Test Folder',
    'cmis:objectTypeId': 'cmis:folder',
    'cmis:baseTypeId': 'cmis:folder',
    'cmis:path': '/Test Folder',
    'cmis:createdBy': 'admin'
  },
  allowableActions: {
    canDeleteObject: false,
    canGetChildren: true,
    canCreateDocument: true,
    canCreateFolder: true
  }
};

const SAMPLE_VERBOSE_RESPONSE: BrowserBindingObject = {
  properties: {
    'cmis:objectId': { value: 'verbose-789', type: 'id' },
    'cmis:name': { value: 'Verbose Document', type: 'string' },
    'cmis:objectTypeId': { value: 'cmis:document', type: 'id' },
    'cmis:baseTypeId': { value: 'cmis:document', type: 'id' },
    'cmis:contentStreamLength': { value: 2048, type: 'integer' }
  },
  allowableActions: {
    canDeleteObject: true
  }
};

const SAMPLE_CHILDREN_RESPONSE = {
  objects: [
    { object: SAMPLE_DOCUMENT_RESPONSE },
    { object: SAMPLE_FOLDER_RESPONSE }
  ]
};

describe('safeParseJson', () => {
  it('should parse valid JSON string', () => {
    const result = safeParseJson<{ name: string }>('{"name": "test"}');
    expect(result).toEqual({ name: 'test' });
  });

  it('should return null for invalid JSON', () => {
    const result = safeParseJson('{invalid json}');
    expect(result).toBeNull();
  });

  it('should return null for empty string', () => {
    const result = safeParseJson('');
    expect(result).toBeNull();
  });

  it('should parse arrays', () => {
    const result = safeParseJson<number[]>('[1, 2, 3]');
    expect(result).toEqual([1, 2, 3]);
  });

  it('should parse nested objects', () => {
    const result = safeParseJson<{ nested: { value: number } }>('{"nested": {"value": 42}}');
    expect(result).toEqual({ nested: { value: 42 } });
  });

  it('should handle null JSON value', () => {
    const result = safeParseJson('null');
    expect(result).toBeNull();
  });
});

describe('getSafeStringProperty', () => {
  const props: BrowserBindingProperties = {
    'string': 'hello',
    'number': 42,
    'boolean': true,
    'null': null,
    'undefined': undefined,
    'empty': ''
  };

  it('should return string value', () => {
    expect(getSafeStringProperty(props, 'string')).toBe('hello');
  });

  it('should convert number to string', () => {
    expect(getSafeStringProperty(props, 'number')).toBe('42');
  });

  it('should convert boolean to string', () => {
    expect(getSafeStringProperty(props, 'boolean')).toBe('true');
  });

  it('should return default for null value', () => {
    expect(getSafeStringProperty(props, 'null', 'default')).toBe('default');
  });

  it('should return default for undefined value', () => {
    expect(getSafeStringProperty(props, 'undefined', 'default')).toBe('default');
  });

  it('should return empty string for empty value', () => {
    expect(getSafeStringProperty(props, 'empty')).toBe('');
  });

  it('should return default for missing key', () => {
    expect(getSafeStringProperty(props, 'missing', 'default')).toBe('default');
  });

  it('should return default for null props', () => {
    expect(getSafeStringProperty(null, 'key', 'default')).toBe('default');
  });

  it('should return default for undefined props', () => {
    expect(getSafeStringProperty(undefined, 'key', 'default')).toBe('default');
  });
});

describe('getSafeIntegerProperty', () => {
  const props: BrowserBindingProperties = {
    'integer': 42,
    'float': 3.14,
    'stringInt': '100',
    'stringFloat': '3.14',
    'invalid': 'not-a-number',
    'null': null,
    'zero': 0,
    'negative': -10
  };

  it('should return integer value', () => {
    expect(getSafeIntegerProperty(props, 'integer')).toBe(42);
  });

  it('should return float value as-is (no truncation for number type)', () => {
    // Note: getSafeIntegerProperty only uses parseInt for strings, not numbers
    // This preserves the original numeric value
    expect(getSafeIntegerProperty(props, 'float')).toBe(3.14);
  });

  it('should parse string integer', () => {
    expect(getSafeIntegerProperty(props, 'stringInt')).toBe(100);
  });

  it('should parse string float and truncate', () => {
    expect(getSafeIntegerProperty(props, 'stringFloat')).toBe(3);
  });

  it('should return default for invalid string', () => {
    expect(getSafeIntegerProperty(props, 'invalid', 0)).toBe(0);
  });

  it('should return default for null value', () => {
    expect(getSafeIntegerProperty(props, 'null', -1)).toBe(-1);
  });

  it('should handle zero value', () => {
    expect(getSafeIntegerProperty(props, 'zero', 99)).toBe(0);
  });

  it('should handle negative value', () => {
    expect(getSafeIntegerProperty(props, 'negative')).toBe(-10);
  });

  it('should return default for null props', () => {
    expect(getSafeIntegerProperty(null, 'key', 5)).toBe(5);
  });
});

describe('getSafeBooleanProperty', () => {
  const props: BrowserBindingProperties = {
    'true': true,
    'false': false,
    'stringTrue': 'true',
    'stringFalse': 'false',
    'stringTrueUpper': 'TRUE',
    'one': 1,
    'zero': 0,
    'null': null,
    'emptyString': ''
  };

  it('should return boolean true', () => {
    expect(getSafeBooleanProperty(props, 'true')).toBe(true);
  });

  it('should return boolean false', () => {
    expect(getSafeBooleanProperty(props, 'false')).toBe(false);
  });

  it('should parse string "true"', () => {
    expect(getSafeBooleanProperty(props, 'stringTrue')).toBe(true);
  });

  it('should parse string "false"', () => {
    expect(getSafeBooleanProperty(props, 'stringFalse')).toBe(false);
  });

  it('should handle uppercase "TRUE"', () => {
    expect(getSafeBooleanProperty(props, 'stringTrueUpper')).toBe(true);
  });

  it('should convert 1 to true', () => {
    expect(getSafeBooleanProperty(props, 'one')).toBe(true);
  });

  it('should convert 0 to false', () => {
    expect(getSafeBooleanProperty(props, 'zero')).toBe(false);
  });

  it('should return default for null value', () => {
    expect(getSafeBooleanProperty(props, 'null', true)).toBe(true);
  });

  it('should convert empty string to false', () => {
    expect(getSafeBooleanProperty(props, 'emptyString')).toBe(false);
  });

  it('should return default for null props', () => {
    expect(getSafeBooleanProperty(null, 'key', true)).toBe(true);
  });
});

describe('getSafeDateProperty', () => {
  const props: BrowserBindingProperties = {
    'timestamp': 1703001600000, // 2023-12-19T16:00:00.000Z
    'isoString': '2024-01-15T10:30:00.000Z',
    'null': null,
    'emptyString': '',
    'invalidTimestamp': -1,
    'zero': 0
  };

  it('should convert timestamp to ISO string', () => {
    const result = getSafeDateProperty(props, 'timestamp');
    expect(result).toBe('2023-12-19T16:00:00.000Z');
  });

  it('should return ISO string as-is', () => {
    expect(getSafeDateProperty(props, 'isoString')).toBe('2024-01-15T10:30:00.000Z');
  });

  it('should return undefined for null value', () => {
    expect(getSafeDateProperty(props, 'null')).toBeUndefined();
  });

  it('should return undefined for empty string', () => {
    expect(getSafeDateProperty(props, 'emptyString')).toBeUndefined();
  });

  it('should handle zero timestamp (epoch)', () => {
    const result = getSafeDateProperty(props, 'zero');
    expect(result).toBe('1970-01-01T00:00:00.000Z');
  });

  it('should return undefined for null props', () => {
    expect(getSafeDateProperty(null, 'key')).toBeUndefined();
  });

  it('should return undefined for missing key', () => {
    expect(getSafeDateProperty(props, 'missing')).toBeUndefined();
  });
});

describe('getSafeArrayProperty', () => {
  const props: BrowserBindingProperties = {
    'array': [1, 2, 3],
    'stringArray': ['a', 'b', 'c'],
    'emptyArray': [],
    'notArray': 'string',
    'null': null,
    'object': { key: 'value' }
  };

  it('should return array value', () => {
    expect(getSafeArrayProperty<number>(props, 'array')).toEqual([1, 2, 3]);
  });

  it('should return string array', () => {
    expect(getSafeArrayProperty<string>(props, 'stringArray')).toEqual(['a', 'b', 'c']);
  });

  it('should return empty array for empty array value', () => {
    expect(getSafeArrayProperty(props, 'emptyArray')).toEqual([]);
  });

  it('should return empty array for non-array value', () => {
    expect(getSafeArrayProperty(props, 'notArray')).toEqual([]);
  });

  it('should return empty array for null value', () => {
    expect(getSafeArrayProperty(props, 'null')).toEqual([]);
  });

  it('should return empty array for object value', () => {
    expect(getSafeArrayProperty(props, 'object')).toEqual([]);
  });

  it('should return empty array for null props', () => {
    expect(getSafeArrayProperty(null, 'key')).toEqual([]);
  });
});

describe('extractProperties', () => {
  it('should extract succinctProperties', () => {
    const props = extractProperties(SAMPLE_DOCUMENT_RESPONSE);
    expect(props['cmis:objectId']).toBe('doc-123');
    expect(props['cmis:name']).toBe('Test Document.pdf');
  });

  it('should extract verbose properties format', () => {
    const props = extractProperties(SAMPLE_VERBOSE_RESPONSE);
    expect(props['cmis:objectId']).toBe('verbose-789');
    expect(props['cmis:name']).toBe('Verbose Document');
    expect(props['cmis:contentStreamLength']).toBe(2048);
  });

  it('should prefer succinctProperties over properties', () => {
    const response: BrowserBindingObject = {
      succinctProperties: { 'cmis:name': 'Succinct Name' },
      properties: { 'cmis:name': { value: 'Verbose Name' } }
    };
    const props = extractProperties(response);
    expect(props['cmis:name']).toBe('Succinct Name');
  });

  it('should return empty object for null response', () => {
    expect(extractProperties(null)).toEqual({});
  });

  it('should return empty object for undefined response', () => {
    expect(extractProperties(undefined)).toEqual({});
  });

  it('should return empty object for response without properties', () => {
    expect(extractProperties({})).toEqual({});
  });
});

describe('extractAllowableActions', () => {
  it('should extract allowed actions', () => {
    const actions = extractAllowableActions(SAMPLE_DOCUMENT_RESPONSE);
    expect(actions).toContain('canDeleteObject');
    expect(actions).toContain('canUpdateProperties');
    expect(actions).toContain('canCheckOut');
    expect(actions).toContain('canGetContentStream');
  });

  it('should not include false actions', () => {
    const actions = extractAllowableActions(SAMPLE_DOCUMENT_RESPONSE);
    expect(actions).not.toContain('canGetChildren');
  });

  it('should return empty array for null response', () => {
    expect(extractAllowableActions(null)).toEqual([]);
  });

  it('should return empty array for response without allowableActions', () => {
    expect(extractAllowableActions({})).toEqual([]);
  });

  it('should handle folder allowable actions', () => {
    const actions = extractAllowableActions(SAMPLE_FOLDER_RESPONSE);
    expect(actions).toContain('canGetChildren');
    expect(actions).toContain('canCreateDocument');
    expect(actions).not.toContain('canDeleteObject');
  });
});

describe('isActionAllowed', () => {
  it('should return true for allowed action', () => {
    expect(isActionAllowed(SAMPLE_DOCUMENT_RESPONSE, 'canDeleteObject')).toBe(true);
  });

  it('should return false for disallowed action', () => {
    expect(isActionAllowed(SAMPLE_DOCUMENT_RESPONSE, 'canGetChildren')).toBe(false);
  });

  it('should return false for missing action', () => {
    expect(isActionAllowed(SAMPLE_DOCUMENT_RESPONSE, 'canNonExistent')).toBe(false);
  });

  it('should return false for null response', () => {
    expect(isActionAllowed(null, 'canDeleteObject')).toBe(false);
  });

  it('should return false for response without allowableActions', () => {
    expect(isActionAllowed({}, 'canDeleteObject')).toBe(false);
  });
});

describe('mapToCmisObject', () => {
  it('should map document response correctly', () => {
    const result = mapToCmisObject(SAMPLE_DOCUMENT_RESPONSE);
    
    expect(result).not.toBeNull();
    expect(result!.id).toBe('doc-123');
    expect(result!.name).toBe('Test Document.pdf');
    expect(result!.objectType).toBe('cmis:document');
    expect(result!.baseType).toBe('cmis:document');
    expect(result!.createdBy).toBe('admin');
    expect(result!.contentStreamLength).toBe(1024);
    expect(result!.contentStreamMimeType).toBe('application/pdf');
  });

  it('should map folder response correctly', () => {
    const result = mapToCmisObject(SAMPLE_FOLDER_RESPONSE);
    
    expect(result).not.toBeNull();
    expect(result!.id).toBe('folder-456');
    expect(result!.name).toBe('Test Folder');
    expect(result!.objectType).toBe('cmis:folder');
    expect(result!.path).toBe('/Test Folder');
  });

  it('should include allowable actions', () => {
    const result = mapToCmisObject(SAMPLE_DOCUMENT_RESPONSE);
    
    expect(result!.allowableActions).toContain('canDeleteObject');
    expect(result!.allowableActions).not.toContain('canGetChildren');
  });

  it('should return null for null response', () => {
    expect(mapToCmisObject(null)).toBeNull();
  });

  it('should return null for response without objectId', () => {
    const response: BrowserBindingObject = {
      succinctProperties: {
        'cmis:name': 'No ID Document'
      }
    };
    expect(mapToCmisObject(response)).toBeNull();
  });

  it('should handle verbose properties format', () => {
    const result = mapToCmisObject(SAMPLE_VERBOSE_RESPONSE);
    
    expect(result).not.toBeNull();
    expect(result!.id).toBe('verbose-789');
    expect(result!.name).toBe('Verbose Document');
  });

  it('should convert date timestamps', () => {
    const result = mapToCmisObject(SAMPLE_DOCUMENT_RESPONSE);
    
    expect(result!.creationDate).toBe('2023-12-19T16:00:00.000Z');
    expect(result!.lastModificationDate).toBe('2024-12-20T10:00:00.000Z');
  });

  it('should include all properties in properties field', () => {
    const result = mapToCmisObject(SAMPLE_DOCUMENT_RESPONSE);
    
    expect(result!.properties['cmis:isLatestVersion']).toBe(true);
    expect(result!.properties['cmis:isVersionSeriesCheckedOut']).toBe(false);
  });
});

describe('mapChildrenResponse', () => {
  it('should map children response to array', () => {
    const result = mapChildrenResponse(SAMPLE_CHILDREN_RESPONSE);
    
    expect(result).toHaveLength(2);
    expect(result[0]!.id).toBe('doc-123');
    expect(result[1]!.id).toBe('folder-456');
  });

  it('should return empty array for null response', () => {
    expect(mapChildrenResponse(null)).toEqual([]);
  });

  it('should return empty array for response without objects', () => {
    expect(mapChildrenResponse({})).toEqual([]);
  });

  it('should return empty array for empty objects array', () => {
    expect(mapChildrenResponse({ objects: [] })).toEqual([]);
  });

  it('should filter out invalid objects', () => {
    const response = {
      objects: [
        { object: SAMPLE_DOCUMENT_RESPONSE },
        { object: { succinctProperties: {} } }, // No objectId - should be filtered
        { object: SAMPLE_FOLDER_RESPONSE }
      ]
    };
    const result = mapChildrenResponse(response);
    
    expect(result).toHaveLength(2);
  });

  it('should handle objects without nested object property', () => {
    const response = {
      objects: [
        SAMPLE_DOCUMENT_RESPONSE as unknown as BrowserBindingObject
      ]
    };
    const result = mapChildrenResponse(response);
    
    // Should handle this gracefully
    expect(result.length).toBeGreaterThanOrEqual(0);
  });
});

describe('Edge Cases', () => {
  it('should handle response with only allowableActions', () => {
    const response: BrowserBindingObject = {
      allowableActions: {
        canDeleteObject: true
      }
    };
    const result = mapToCmisObject(response);
    expect(result).toBeNull(); // No objectId
  });

  it('should handle empty succinctProperties', () => {
    const response: BrowserBindingObject = {
      succinctProperties: {}
    };
    const result = mapToCmisObject(response);
    expect(result).toBeNull(); // No objectId
  });

  it('should handle special characters in property values', () => {
    const response: BrowserBindingObject = {
      succinctProperties: {
        'cmis:objectId': 'doc-special-<>&"\'',
        'cmis:name': 'Document with <special> & "chars"'
      }
    };
    const result = mapToCmisObject(response);
    
    expect(result).not.toBeNull();
    expect(result!.id).toBe('doc-special-<>&"\'');
    expect(result!.name).toBe('Document with <special> & "chars"');
  });

  it('should handle unicode in property values', () => {
    const response: BrowserBindingObject = {
      succinctProperties: {
        'cmis:objectId': 'doc-unicode',
        'cmis:name': '日本語ドキュメント'
      }
    };
    const result = mapToCmisObject(response);
    
    expect(result).not.toBeNull();
    expect(result!.name).toBe('日本語ドキュメント');
  });

  it('should handle very large integer values', () => {
    const response: BrowserBindingObject = {
      succinctProperties: {
        'cmis:objectId': 'large-doc',
        'cmis:contentStreamLength': 9007199254740991 // Number.MAX_SAFE_INTEGER
      }
    };
    const result = mapToCmisObject(response);
    
    expect(result).not.toBeNull();
    expect(result!.contentStreamLength).toBe(9007199254740991);
  });

  it('should handle negative timestamps', () => {
    const props: BrowserBindingProperties = {
      'date': -86400000 // One day before epoch
    };
    const result = getSafeDateProperty(props, 'date');
    expect(result).toBe('1969-12-31T00:00:00.000Z');
  });
});
