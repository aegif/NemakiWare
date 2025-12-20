/**
 * Parsers module exports
 * 
 * Provides pure functions for parsing CMIS responses.
 * - AtomPub parsers: XML parsing for AtomPub binding responses
 * - Browser Binding mappers: JSON mapping for Browser Binding responses
 */

// AtomPub XML parsers
export {
  parseXmlString,
  getAtomEntries,
  getCmisObject,
  getCmisProperties,
  getPropertyValue,
  extractAllProperties,
  extractAllowableActions as extractAllowableActionsFromXml,
  parseAtomEntry,
  parseAtomFeed,
  parseAtomEntryResponse,
  type ParsedCmisProperties,
  type ParsedAtomEntry
} from './atompubParsers';

// Browser Binding JSON mappers
export {
  safeParseJson,
  getSafeStringProperty,
  getSafeIntegerProperty,
  getSafeBooleanProperty,
  getSafeDateProperty,
  getSafeArrayProperty,
  extractProperties,
  extractAllowableActions as extractAllowableActionsFromJson,
  isActionAllowed,
  mapToCmisObject,
  mapChildrenResponse,
  type BrowserBindingProperties,
  type BrowserBindingObject
} from './browserBindingMappers';
