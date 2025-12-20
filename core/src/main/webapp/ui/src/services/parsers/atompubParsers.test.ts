/**
 * Unit tests for AtomPub XML Parsers
 * 
 * These tests verify the pure parsing functions used to extract CMIS data from AtomPub XML responses.
 * Tests cover both success cases and edge cases (malformed XML, missing elements, etc.)
 */

import { describe, it, expect } from 'vitest';
import {
  parseXmlString,
  getAtomEntries,
  getCmisObject,
  getCmisProperties,
  getPropertyValue,
  extractAllProperties,
  extractAllowableActions,
  parseAtomEntry,
  parseAtomFeed,
  parseAtomEntryResponse,
  ParsedAtomEntry,
  ParsedAllowableActions
} from './atompubParsers';

// Sample AtomPub XML responses for testing
const SAMPLE_ATOM_ENTRY = `<?xml version="1.0" encoding="UTF-8"?>
<entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <title>Test Document</title>
  <id>urn:uuid:test-doc-123</id>
  <cmisra:object>
    <cmis:properties>
      <cmis:propertyId propertyDefinitionId="cmis:objectId">
        <cmis:value>test-doc-123</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:name">
        <cmis:value>Test Document</cmis:value>
      </cmis:propertyString>
      <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
        <cmis:value>cmis:document</cmis:value>
      </cmis:propertyId>
      <cmis:propertyId propertyDefinitionId="cmis:baseTypeId">
        <cmis:value>cmis:document</cmis:value>
      </cmis:propertyId>
      <cmis:propertyString propertyDefinitionId="cmis:createdBy">
        <cmis:value>admin</cmis:value>
      </cmis:propertyString>
      <cmis:propertyInteger propertyDefinitionId="cmis:contentStreamLength">
        <cmis:value>1024</cmis:value>
      </cmis:propertyInteger>
      <cmis:propertyBoolean propertyDefinitionId="cmis:isLatestVersion">
        <cmis:value>true</cmis:value>
      </cmis:propertyBoolean>
      <cmis:propertyBoolean propertyDefinitionId="cmis:isVersionSeriesCheckedOut">
        <cmis:value>false</cmis:value>
      </cmis:propertyBoolean>
    </cmis:properties>
    <cmis:allowableActions>
      <cmis:canDeleteObject>true</cmis:canDeleteObject>
      <cmis:canUpdateProperties>true</cmis:canUpdateProperties>
      <cmis:canGetChildren>false</cmis:canGetChildren>
      <cmis:canCheckOut>true</cmis:canCheckOut>
    </cmis:allowableActions>
  </cmisra:object>
</entry>`;

const SAMPLE_ATOM_FEED = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
  <title>Children of Root</title>
  <entry>
    <title>Document 1</title>
    <id>urn:uuid:doc-1</id>
    <cmisra:object>
      <cmis:properties>
        <cmis:propertyId propertyDefinitionId="cmis:objectId">
          <cmis:value>doc-1</cmis:value>
        </cmis:propertyId>
        <cmis:propertyString propertyDefinitionId="cmis:name">
          <cmis:value>Document 1</cmis:value>
        </cmis:propertyString>
        <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
          <cmis:value>cmis:document</cmis:value>
        </cmis:propertyId>
      </cmis:properties>
      <cmis:allowableActions>
        <cmis:canDeleteObject>true</cmis:canDeleteObject>
      </cmis:allowableActions>
    </cmisra:object>
  </entry>
  <entry>
    <title>Folder 1</title>
    <id>urn:uuid:folder-1</id>
    <cmisra:object>
      <cmis:properties>
        <cmis:propertyId propertyDefinitionId="cmis:objectId">
          <cmis:value>folder-1</cmis:value>
        </cmis:propertyId>
        <cmis:propertyString propertyDefinitionId="cmis:name">
          <cmis:value>Folder 1</cmis:value>
        </cmis:propertyString>
        <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
          <cmis:value>cmis:folder</cmis:value>
        </cmis:propertyId>
      </cmis:properties>
      <cmis:allowableActions>
        <cmis:canGetChildren>true</cmis:canGetChildren>
        <cmis:canDeleteObject>false</cmis:canDeleteObject>
      </cmis:allowableActions>
    </cmisra:object>
  </entry>
</feed>`;

// XML without namespace prefixes (some servers return this format)
const SAMPLE_ENTRY_NO_PREFIX = `<?xml version="1.0" encoding="UTF-8"?>
<entry xmlns="http://www.w3.org/2005/Atom">
  <title>No Prefix Doc</title>
  <id>urn:uuid:no-prefix-123</id>
  <object xmlns="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
    <properties xmlns="http://docs.oasis-open.org/ns/cmis/core/200908/">
      <propertyId propertyDefinitionId="cmis:objectId">
        <value>no-prefix-123</value>
      </propertyId>
      <propertyString propertyDefinitionId="cmis:name">
        <value>No Prefix Doc</value>
      </propertyString>
    </properties>
  </object>
</entry>`;

describe('parseXmlString', () => {
  it('should parse valid XML string', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    expect(doc).not.toBeNull();
    expect(doc?.documentElement.tagName).toBe('entry');
  });

  it('should return null for invalid XML', () => {
    const doc = parseXmlString('<invalid><unclosed>');
    expect(doc).toBeNull();
  });

  it('should return null for empty string', () => {
    const doc = parseXmlString('');
    expect(doc).toBeNull();
  });

  it('should handle XML with special characters', () => {
    const xml = `<?xml version="1.0"?><root><text>Test &amp; &lt;special&gt;</text></root>`;
    const doc = parseXmlString(xml);
    expect(doc).not.toBeNull();
  });
});

describe('getAtomEntries', () => {
  it('should extract entries from feed', () => {
    const doc = parseXmlString(SAMPLE_ATOM_FEED);
    expect(doc).not.toBeNull();
    const entries = getAtomEntries(doc!);
    expect(entries).toHaveLength(2);
  });

  it('should return empty array for document without entries', () => {
    const doc = parseXmlString('<?xml version="1.0"?><feed></feed>');
    expect(doc).not.toBeNull();
    const entries = getAtomEntries(doc!);
    expect(entries).toHaveLength(0);
  });

  it('should handle single entry document', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    expect(doc).not.toBeNull();
    // Single entry document - getAtomEntries returns the root entry
    const entries = getAtomEntries(doc!);
    expect(entries.length).toBeGreaterThanOrEqual(1);
  });
});

describe('getCmisObject', () => {
  it('should extract cmisra:object from entry', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    expect(doc).not.toBeNull();
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    expect(cmisObject).not.toBeNull();
  });

  it('should return null when no cmisra:object exists', () => {
    const doc = parseXmlString('<?xml version="1.0"?><entry><title>Test</title></entry>');
    expect(doc).not.toBeNull();
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    expect(cmisObject).toBeNull();
  });
});

describe('getCmisProperties', () => {
  it('should extract cmis:properties from cmisra:object', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    expect(doc).not.toBeNull();
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    expect(cmisObject).not.toBeNull();
    const properties = getCmisProperties(cmisObject!);
    expect(properties).not.toBeNull();
  });
});

describe('getPropertyValue', () => {
  it('should extract string property value', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    expect(doc).not.toBeNull();
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    const properties = getCmisProperties(cmisObject!);
    expect(properties).not.toBeNull();
    
    const name = getPropertyValue(properties!, 'cmis:name', 'propertyString');
    expect(name).toBe('Test Document');
  });

  it('should extract id property value', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    const properties = getCmisProperties(cmisObject!);
    
    const objectId = getPropertyValue(properties!, 'cmis:objectId', 'propertyId');
    expect(objectId).toBe('test-doc-123');
  });

  it('should return empty string for non-existent property', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    const properties = getCmisProperties(cmisObject!);
    
    const nonExistent = getPropertyValue(properties!, 'cmis:nonExistent', 'propertyString');
    expect(nonExistent).toBe('');
  });
});

describe('extractAllProperties', () => {
  it('should extract all properties from cmis:properties', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    const propertiesElem = getCmisProperties(cmisObject!);
    
    const props = extractAllProperties(propertiesElem!);
    
    expect(props['cmis:objectId']).toBe('test-doc-123');
    expect(props['cmis:name']).toBe('Test Document');
    expect(props['cmis:objectTypeId']).toBe('cmis:document');
    expect(props['cmis:createdBy']).toBe('admin');
  });

  it('should convert integer properties to numbers', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    const propertiesElem = getCmisProperties(cmisObject!);
    
    const props = extractAllProperties(propertiesElem!);
    
    expect(props['cmis:contentStreamLength']).toBe(1024);
    expect(typeof props['cmis:contentStreamLength']).toBe('number');
  });

  it('should convert boolean properties to booleans', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    const propertiesElem = getCmisProperties(cmisObject!);
    
    const props = extractAllProperties(propertiesElem!);
    
    expect(props['cmis:isLatestVersion']).toBe(true);
    expect(props['cmis:isVersionSeriesCheckedOut']).toBe(false);
    expect(typeof props['cmis:isLatestVersion']).toBe('boolean');
    expect(typeof props['cmis:isVersionSeriesCheckedOut']).toBe('boolean');
  });
});

describe('extractAllowableActions', () => {
  it('should extract allowable actions with true values', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    
    const actions = extractAllowableActions(cmisObject!);
    
    expect(actions['canDeleteObject']).toBe(true);
    expect(actions['canUpdateProperties']).toBe(true);
    expect(actions['canCheckOut']).toBe(true);
  });

  it('should preserve explicit false values (SECURITY CRITICAL)', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = doc!.documentElement;
    const cmisObject = getCmisObject(entry);
    
    const actions = extractAllowableActions(cmisObject!);
    
    // This is critical for security - explicit false must be preserved
    expect(actions['canGetChildren']).toBe(false);
    expect('canGetChildren' in actions).toBe(true);
  });

  it('should return empty object when no allowableActions element exists', () => {
    const xml = `<?xml version="1.0"?><object xmlns="http://docs.oasis-open.org/ns/cmis/restatom/200908/"></object>`;
    const doc = parseXmlString(xml);
    const actions = extractAllowableActions(doc!.documentElement);
    
    expect(Object.keys(actions)).toHaveLength(0);
  });

  it('should handle mixed true/false values correctly', () => {
    const doc = parseXmlString(SAMPLE_ATOM_FEED);
    const entries = getAtomEntries(doc!);
    const folder = entries[1]; // Folder 1
    const cmisObject = getCmisObject(folder);
    
    const actions = extractAllowableActions(cmisObject!);
    
    expect(actions['canGetChildren']).toBe(true);
    expect(actions['canDeleteObject']).toBe(false);
  });
});

describe('parseAtomEntry', () => {
  it('should parse complete entry with all metadata', () => {
    const doc = parseXmlString(SAMPLE_ATOM_ENTRY);
    const entry = parseAtomEntry(doc!.documentElement);
    
    expect(entry.atomTitle).toBe('Test Document');
    expect(entry.atomId).toBe('urn:uuid:test-doc-123');
    expect(entry.properties['cmis:objectId']).toBe('test-doc-123');
    expect(entry.properties['cmis:name']).toBe('Test Document');
    expect(entry.allowableActions['canDeleteObject']).toBe(true);
    expect(entry.allowableActions['canGetChildren']).toBe(false);
  });

  it('should use atom title as fallback for cmis:name', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom">
      <title>Fallback Title</title>
      <id>urn:uuid:fallback-123</id>
    </entry>`;
    const doc = parseXmlString(xml);
    const entry = parseAtomEntry(doc!.documentElement);
    
    expect(entry.properties['cmis:name']).toBe('Fallback Title');
  });

  it('should use atom id as fallback for cmis:objectId', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom">
      <title>Test</title>
      <id>urn:uuid:fallback-id</id>
    </entry>`;
    const doc = parseXmlString(xml);
    const entry = parseAtomEntry(doc!.documentElement);
    
    expect(entry.properties['cmis:objectId']).toBe('urn:uuid:fallback-id');
  });
});

describe('parseAtomFeed', () => {
  it('should parse feed with multiple entries', () => {
    const entries = parseAtomFeed(SAMPLE_ATOM_FEED);
    
    expect(entries).toHaveLength(2);
    expect(entries[0].properties['cmis:name']).toBe('Document 1');
    expect(entries[1].properties['cmis:name']).toBe('Folder 1');
  });

  it('should return empty array for invalid XML', () => {
    const entries = parseAtomFeed('<invalid>');
    expect(entries).toHaveLength(0);
  });

  it('should return empty array for empty feed', () => {
    const entries = parseAtomFeed('<?xml version="1.0"?><feed></feed>');
    expect(entries).toHaveLength(0);
  });

  it('should preserve allowable actions for each entry', () => {
    const entries = parseAtomFeed(SAMPLE_ATOM_FEED);
    
    expect(entries[0].allowableActions['canDeleteObject']).toBe(true);
    expect(entries[1].allowableActions['canGetChildren']).toBe(true);
    expect(entries[1].allowableActions['canDeleteObject']).toBe(false);
  });
});

describe('parseAtomEntryResponse', () => {
  it('should parse single entry response', () => {
    const entry = parseAtomEntryResponse(SAMPLE_ATOM_ENTRY);
    
    expect(entry).not.toBeNull();
    expect(entry!.properties['cmis:objectId']).toBe('test-doc-123');
    expect(entry!.properties['cmis:name']).toBe('Test Document');
  });

  it('should return null for invalid XML', () => {
    const entry = parseAtomEntryResponse('<invalid>');
    expect(entry).toBeNull();
  });

  it('should return null for non-entry root element', () => {
    const entry = parseAtomEntryResponse('<?xml version="1.0"?><feed></feed>');
    expect(entry).toBeNull();
  });

  it('should handle entry without namespace prefix', () => {
    // Some servers return XML without namespace prefixes
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom">
      <title>Simple Entry</title>
      <id>simple-123</id>
    </entry>`;
    const entry = parseAtomEntryResponse(xml);
    
    expect(entry).not.toBeNull();
    expect(entry!.atomTitle).toBe('Simple Entry');
  });
});

describe('Edge Cases', () => {
  it('should handle XML with CDATA sections', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom">
      <title><![CDATA[Document with <special> chars]]></title>
      <id>cdata-123</id>
    </entry>`;
    const entry = parseAtomEntryResponse(xml);
    
    expect(entry).not.toBeNull();
    expect(entry!.atomTitle).toBe('Document with <special> chars');
  });

  it('should handle empty property values', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
      <title>Empty Props</title>
      <id>empty-123</id>
      <cmisra:object>
        <cmis:properties>
          <cmis:propertyString propertyDefinitionId="cmis:description">
            <cmis:value></cmis:value>
          </cmis:propertyString>
        </cmis:properties>
      </cmisra:object>
    </entry>`;
    const entry = parseAtomEntryResponse(xml);
    
    expect(entry).not.toBeNull();
    expect(entry!.properties['cmis:description']).toBe('');
  });

  it('should handle properties without value element', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
      <title>No Value</title>
      <id>no-value-123</id>
      <cmisra:object>
        <cmis:properties>
          <cmis:propertyString propertyDefinitionId="cmis:noValue">
          </cmis:propertyString>
        </cmis:properties>
      </cmisra:object>
    </entry>`;
    const entry = parseAtomEntryResponse(xml);
    
    expect(entry).not.toBeNull();
    // Property without value element should not be in the result
    expect(entry!.properties['cmis:noValue']).toBeUndefined();
  });

  it('should handle decimal property values', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
      <title>Decimal</title>
      <id>decimal-123</id>
      <cmisra:object>
        <cmis:properties>
          <cmis:propertyDecimal propertyDefinitionId="custom:price">
            <cmis:value>19.99</cmis:value>
          </cmis:propertyDecimal>
        </cmis:properties>
      </cmisra:object>
    </entry>`;
    const entry = parseAtomEntryResponse(xml);
    
    expect(entry).not.toBeNull();
    expect(entry!.properties['custom:price']).toBe(19.99);
    expect(typeof entry!.properties['custom:price']).toBe('number');
  });

  it('should handle invalid integer values gracefully', () => {
    const xml = `<?xml version="1.0"?>
    <entry xmlns="http://www.w3.org/2005/Atom" xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/" xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/">
      <title>Invalid Int</title>
      <id>invalid-int-123</id>
      <cmisra:object>
        <cmis:properties>
          <cmis:propertyInteger propertyDefinitionId="custom:count">
            <cmis:value>not-a-number</cmis:value>
          </cmis:propertyInteger>
        </cmis:properties>
      </cmisra:object>
    </entry>`;
    const entry = parseAtomEntryResponse(xml);
    
    expect(entry).not.toBeNull();
    expect(entry!.properties['custom:count']).toBeUndefined();
  });
});
