/**
 * AtomPub XML Parsers - Pure functions for parsing CMIS AtomPub XML responses
 * 
 * This module provides pure functions for parsing AtomPub XML responses from CMIS servers.
 * These functions are extracted from CMISService to improve testability and maintainability.
 * 
 * Design Principles:
 * - Pure functions: No side effects, same input always produces same output
 * - Namespace-aware: Handles CMIS XML namespaces correctly
 * - Fail-safe: Returns null/empty values on parse errors instead of throwing
 * - Type-safe: Returns strongly typed objects
 * 
 * CMIS Namespaces:
 * - Atom: http://www.w3.org/2005/Atom
 * - CMIS Core: http://docs.oasis-open.org/ns/cmis/core/200908/
 * - CMIS RestAtom: http://docs.oasis-open.org/ns/cmis/restatom/200908/
 */

const CMIS_CORE_NS = 'http://docs.oasis-open.org/ns/cmis/core/200908/';
const CMIS_RESTATOM_NS = 'http://docs.oasis-open.org/ns/cmis/restatom/200908/';

/**
 * Parsed CMIS properties from XML
 */
export interface ParsedCmisProperties {
  [key: string]: string | number | boolean | undefined;
}

/**
 * Parsed CMIS object from AtomPub entry
 */
export interface ParsedAtomEntry {
  atomTitle: string;
  atomId: string;
  properties: ParsedCmisProperties;
  allowableActions: string[];
}

/**
 * Parse XML string into Document
 * 
 * @param xmlString Raw XML string
 * @returns Parsed Document or null if parsing failed
 */
export function parseXmlString(xmlString: string): Document | null {
  try {
    const parser = new DOMParser();
    const xmlDoc = parser.parseFromString(xmlString, 'text/xml');
    
    // Check for parse errors
    const parseError = xmlDoc.querySelector('parsererror');
    if (parseError) {
      return null;
    }
    
    return xmlDoc;
  } catch (e) {
    return null;
  }
}

/**
 * Get atom:entry elements from an AtomPub feed
 * 
 * @param xmlDoc Parsed XML document
 * @returns Array of entry elements
 */
export function getAtomEntries(xmlDoc: Document): Element[] {
  // Try both atom:entry and entry (with and without namespace prefix)
  const entriesWithPrefix = xmlDoc.getElementsByTagName('atom:entry');
  if (entriesWithPrefix.length > 0) {
    return Array.from(entriesWithPrefix);
  }
  
  const entriesWithoutPrefix = xmlDoc.getElementsByTagName('entry');
  return Array.from(entriesWithoutPrefix);
}

/**
 * Get the cmisra:object element from an entry
 * 
 * @param entry Atom entry element
 * @returns cmisra:object element or null
 */
export function getCmisObject(entry: Element): Element | null {
  // Try namespace-aware lookup first
  const nsObject = entry.getElementsByTagNameNS(CMIS_RESTATOM_NS, 'object')[0];
  if (nsObject) return nsObject;
  
  // Fallback to querySelector with escaped colon
  const qsObject = entry.querySelector('cmisra\\:object, object');
  return qsObject;
}

/**
 * Get the cmis:properties element from a cmisra:object or entry
 * 
 * @param parent Parent element (cmisra:object or entry)
 * @returns cmis:properties element or null
 */
export function getCmisProperties(parent: Element): Element | null {
  // Try namespace-aware lookup first
  const nsProps = parent.getElementsByTagNameNS(CMIS_CORE_NS, 'properties')[0];
  if (nsProps) return nsProps;
  
  // Fallback to querySelector with escaped colon
  const qsProps = parent.querySelector('cmis\\:properties, properties');
  return qsProps;
}

/**
 * Get a single property value from cmis:properties
 * 
 * @param properties cmis:properties element
 * @param propertyName Property definition ID (e.g., 'cmis:objectId')
 * @param propertyType Property type element name (e.g., 'propertyString', 'propertyId')
 * @returns Property value as string, or empty string if not found
 */
export function getPropertyValue(
  properties: Element,
  propertyName: string,
  propertyType: string = 'propertyString'
): string {
  const propElements = properties.getElementsByTagName(`cmis:${propertyType}`);
  
  for (let i = 0; i < propElements.length; i++) {
    const elem = propElements[i];
    if (elem.getAttribute('propertyDefinitionId') === propertyName) {
      // Use getElementsByTagName for XML namespace compatibility
      const valueElem = elem.getElementsByTagName('cmis:value')[0] || 
                       elem.getElementsByTagName('value')[0];
      return valueElem?.textContent || '';
    }
  }
  
  return '';
}

/**
 * Extract all properties from cmis:properties element
 * 
 * @param properties cmis:properties element
 * @returns Object with all property values
 */
export function extractAllProperties(properties: Element): ParsedCmisProperties {
  const result: ParsedCmisProperties = {};
  
  // Property types to extract
  const propertyTypes = [
    'propertyBoolean',
    'propertyString', 
    'propertyInteger',
    'propertyDateTime',
    'propertyId',
    'propertyDecimal',
    'propertyHtml',
    'propertyUri'
  ];
  
  for (const propType of propertyTypes) {
    const propElements = properties.getElementsByTagName(`cmis:${propType}`);
    
    for (let i = 0; i < propElements.length; i++) {
      const elem = propElements[i];
      const propName = elem.getAttribute('propertyDefinitionId');
      
      if (propName && result[propName] === undefined) {
        // Get value element
        const valueElem = elem.getElementsByTagName('cmis:value')[0] || 
                         elem.getElementsByTagName('value')[0];
        
        if (valueElem) {
          const textValue = valueElem.textContent || '';
          
          // Convert to appropriate type
          if (propType === 'propertyBoolean') {
            result[propName] = textValue === 'true';
          } else if (propType === 'propertyInteger' || propType === 'propertyDecimal') {
            const numValue = propType === 'propertyInteger' 
              ? parseInt(textValue, 10) 
              : parseFloat(textValue);
            result[propName] = isNaN(numValue) ? undefined : numValue;
          } else {
            result[propName] = textValue;
          }
        }
      }
    }
  }
  
  return result;
}

/**
 * Extract allowable actions from cmis:allowableActions element
 * 
 * @param parent Parent element containing allowableActions
 * @returns Array of allowed action names
 */
export function extractAllowableActions(parent: Element): string[] {
  const actions: string[] = [];
  
  // Try namespace-aware lookup first
  let allowableActionsElem = parent.getElementsByTagNameNS(CMIS_CORE_NS, 'allowableActions')[0];
  
  // Fallback to querySelector
  if (!allowableActionsElem) {
    allowableActionsElem = parent.querySelector('cmis\\:allowableActions, allowableActions') as Element;
  }
  
  if (allowableActionsElem) {
    // Get all child elements that have 'true' as text content
    const children = allowableActionsElem.children;
    for (let i = 0; i < children.length; i++) {
      const child = children[i];
      if (child.textContent === 'true') {
        // Extract action name from tag (e.g., 'cmis:canDeleteObject' -> 'canDeleteObject')
        const tagName = child.localName || child.tagName.replace(/^cmis:/, '');
        actions.push(tagName);
      }
    }
  }
  
  return actions;
}

/**
 * Parse a single atom:entry into a structured object
 * 
 * @param entry Atom entry element
 * @returns Parsed entry data
 */
export function parseAtomEntry(entry: Element): ParsedAtomEntry {
  // Extract atom metadata
  const atomTitle = entry.querySelector('title')?.textContent || 'Unknown';
  const atomId = entry.querySelector('id')?.textContent || '';
  
  // Get cmisra:object
  const cmisObject = getCmisObject(entry);
  
  // Get properties (from cmisra:object or directly from entry)
  let propertiesElem: Element | null = null;
  if (cmisObject) {
    propertiesElem = getCmisProperties(cmisObject);
  }
  if (!propertiesElem) {
    propertiesElem = getCmisProperties(entry);
  }
  
  // Extract all properties
  const properties = propertiesElem ? extractAllProperties(propertiesElem) : {};
  
  // Add atom metadata to properties if not already present
  if (!properties['cmis:name']) {
    properties['cmis:name'] = atomTitle;
  }
  if (!properties['cmis:objectId']) {
    properties['cmis:objectId'] = atomId;
  }
  
  // Extract allowable actions
  const allowableActions = cmisObject 
    ? extractAllowableActions(cmisObject)
    : extractAllowableActions(entry);
  
  return {
    atomTitle,
    atomId,
    properties,
    allowableActions
  };
}

/**
 * Parse an AtomPub feed response into an array of entries
 * 
 * @param xmlString Raw XML response string
 * @returns Array of parsed entries, or empty array on error
 */
export function parseAtomFeed(xmlString: string): ParsedAtomEntry[] {
  const xmlDoc = parseXmlString(xmlString);
  if (!xmlDoc) {
    return [];
  }
  
  const entries = getAtomEntries(xmlDoc);
  return entries.map(parseAtomEntry);
}

/**
 * Parse a single AtomPub entry response (not a feed)
 * 
 * @param xmlString Raw XML response string
 * @returns Parsed entry, or null on error
 */
export function parseAtomEntryResponse(xmlString: string): ParsedAtomEntry | null {
  const xmlDoc = parseXmlString(xmlString);
  if (!xmlDoc) {
    return null;
  }
  
  // Get the root entry element
  const entry = xmlDoc.documentElement;
  if (!entry || (entry.localName !== 'entry' && !entry.tagName.endsWith(':entry'))) {
    return null;
  }
  
  return parseAtomEntry(entry);
}
