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
 * Parsed allowable actions from AtomPub XML
 * 
 * This is an object mapping action names to boolean values.
 * Both true and false values are preserved to distinguish between:
 * - Action explicitly allowed (true)
 * - Action explicitly denied (false)
 * - Action not specified (key not present)
 */
export interface ParsedAllowableActions {
  [actionName: string]: boolean;
}

/**
 * Parsed CMIS extension element from XML
 */
export interface ParsedCmisExtension {
  namespace: string;
  name: string;
  attributes: Record<string, string>;
  value?: string;
  children: ParsedCmisExtension[];
}

/**
 * Parsed coercion warning from NemakiWare extensions
 */
export interface ParsedCoercionWarning {
  propertyId: string;
  type: string;
  reason: string;
  elementCount?: number;
  elementIndex?: number;
}

/**
 * Parsed CMIS object from AtomPub entry
 */
export interface ParsedAtomEntry {
  atomTitle: string;
  atomId: string;
  properties: ParsedCmisProperties;
  allowableActions: ParsedAllowableActions;
  extensions: ParsedCmisExtension[];
  coercionWarnings: ParsedCoercionWarning[];
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
 * Extract CMIS extensions from a parent element
 * 
 * CMIS extensions are vendor-specific elements that can be added to responses.
 * NemakiWare uses extensions to include coercion warnings when property values
 * don't match current type definitions.
 * 
 * @param parent Parent element (cmis:properties or cmisra:object)
 * @returns Array of parsed extension elements
 */
export function extractExtensions(parent: Element): ParsedCmisExtension[] {
  const extensions: ParsedCmisExtension[] = [];
  
  // Look for any elements that are not standard CMIS elements
  // Extensions typically have a custom namespace
  const children = parent.children;
  for (let i = 0; i < children.length; i++) {
    const child = children[i];
    const tagName = child.localName || child.tagName;
    
    // Skip standard CMIS elements
    if (tagName.startsWith('cmis:') || tagName.startsWith('cmisra:') ||
        ['properties', 'allowableActions', 'object', 'entry', 'title', 'id', 
         'link', 'author', 'updated', 'published', 'content', 'summary'].includes(tagName)) {
      continue;
    }
    
    // This might be an extension element
    const namespace = child.namespaceURI || '';
    
    // Check if this is a NemakiWare coercion extension
    if (namespace.includes('nemakiware') || namespace.includes('coercion') || 
        tagName === 'coercionWarnings' || tagName === 'warning') {
      extensions.push(parseExtensionElement(child));
    }
  }
  
  return extensions;
}

/**
 * Parse a single extension element recursively
 * 
 * @param element Extension element to parse
 * @returns Parsed extension object
 */
export function parseExtensionElement(element: Element): ParsedCmisExtension {
  const attributes: Record<string, string> = {};
  
  // Extract all attributes
  for (let i = 0; i < element.attributes.length; i++) {
    const attr = element.attributes[i];
    attributes[attr.name] = attr.value;
  }
  
  // Parse child elements recursively
  const children: ParsedCmisExtension[] = [];
  for (let i = 0; i < element.children.length; i++) {
    children.push(parseExtensionElement(element.children[i]));
  }
  
  return {
    namespace: element.namespaceURI || '',
    name: element.localName || element.tagName,
    attributes,
    value: element.children.length === 0 ? (element.textContent || undefined) : undefined,
    children
  };
}

/**
 * Extract coercion warnings from extensions
 * 
 * NemakiWare adds coercion warnings as CMIS extensions when property values
 * were coerced or dropped due to type/cardinality mismatches.
 * 
 * @param extensions Array of parsed extensions
 * @returns Array of coercion warnings
 */
export function extractCoercionWarnings(extensions: ParsedCmisExtension[]): ParsedCoercionWarning[] {
  const warnings: ParsedCoercionWarning[] = [];
  
  for (const ext of extensions) {
    if (ext.name === 'coercionWarnings') {
      // Process child warning elements - don't recurse further since we handle children here
      for (const child of ext.children) {
        if (child.name === 'warning') {
          warnings.push({
            propertyId: child.attributes['propertyId'] || '',
            type: child.attributes['type'] || '',
            reason: child.attributes['reason'] || '',
            elementCount: child.attributes['elementCount'] ? parseInt(child.attributes['elementCount'], 10) : undefined,
            elementIndex: child.attributes['elementIndex'] ? parseInt(child.attributes['elementIndex'], 10) : undefined
          });
        }
      }
    } else if (ext.name === 'warning') {
      // Direct warning element (standalone, not inside coercionWarnings)
      warnings.push({
        propertyId: ext.attributes['propertyId'] || '',
        type: ext.attributes['type'] || '',
        reason: ext.attributes['reason'] || '',
        elementCount: ext.attributes['elementCount'] ? parseInt(ext.attributes['elementCount'], 10) : undefined,
        elementIndex: ext.attributes['elementIndex'] ? parseInt(ext.attributes['elementIndex'], 10) : undefined
      });
    } else if (ext.children.length > 0) {
      // Only recurse for non-coercionWarnings elements to find nested warnings
      warnings.push(...extractCoercionWarnings(ext.children));
    }
  }
  
  return warnings;
}

/**
 * Extract allowable actions from cmis:allowableActions element
 * 
 * IMPORTANT: This function preserves both true AND false values from the server.
 * This is critical for security - if the server explicitly returns canGetChildren=false,
 * we must preserve that to prevent UI from incorrectly enabling actions for restricted users.
 * 
 * @param parent Parent element containing allowableActions
 * @returns Object mapping action names to boolean values (both true and false are preserved)
 */
export function extractAllowableActions(parent: Element): ParsedAllowableActions {
  const actions: ParsedAllowableActions = {};
  
  // Try namespace-aware lookup first
  let allowableActionsElem = parent.getElementsByTagNameNS(CMIS_CORE_NS, 'allowableActions')[0];
  
  // Fallback to querySelector
  if (!allowableActionsElem) {
    allowableActionsElem = parent.querySelector('cmis\\:allowableActions, allowableActions') as Element;
  }
  
  if (allowableActionsElem) {
    // Get all child elements and preserve both true AND false values
    const children = allowableActionsElem.children;
    for (let i = 0; i < children.length; i++) {
      const child = children[i];
      const textContent = (child.textContent || '').trim().toLowerCase();
      // Only process elements that have 'true' or 'false' as text content
      if (textContent === 'true' || textContent === 'false') {
        // Extract action name from tag (e.g., 'cmis:canDeleteObject' -> 'canDeleteObject')
        const tagName = child.localName || child.tagName.replace(/^cmis:/, '');
        actions[tagName] = textContent === 'true';
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
  
  // Extract CMIS extensions (including NemakiWare coercion warnings)
  let extensions: ParsedCmisExtension[] = [];
  if (propertiesElem) {
    extensions = extractExtensions(propertiesElem);
  }
  if (cmisObject && extensions.length === 0) {
    extensions = extractExtensions(cmisObject);
  }
  
  // Extract coercion warnings from extensions
  const coercionWarnings = extractCoercionWarnings(extensions);
  
  return {
    atomTitle,
    atomId,
    properties,
    allowableActions,
    extensions,
    coercionWarnings
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
