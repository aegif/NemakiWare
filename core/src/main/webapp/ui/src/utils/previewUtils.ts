import { CMISObject } from '../types/cmis';

/** CAD MIME types */
const CAD_MIME_TYPES = new Set([
  'image/vnd.dxf',
  'application/x-dwg',
  'application/x-jww',
  'application/x-sfc',
  'application/x-p21',
]);

/** Diagram MIME types */
const DIAGRAM_MIME_TYPES = new Set([
  'text/x-plantuml',
  'text/vnd.graphviz',
]);

/** Extension → file type mapping for octet-stream fallback */
const EXTENSION_FILE_TYPE_MAP: Record<string, FileType> = {
  dxf: 'cad',
  dwg: 'cad',
  jww: 'cad',
  sfc: 'cad',
  p21: 'cad',
  puml: 'diagram',
  plantuml: 'diagram',
  dot: 'diagram',
  gv: 'diagram',
  md: 'markdown',
};

export type FileType = 'image' | 'video' | 'pdf' | 'text' | 'office' | 'cad' | 'markdown' | 'diagram' | 'unsupported';

/**
 * Determine the file type from MIME type (and optionally file name for extension fallback).
 */
export const getFileType = (mimeType: string, fileName?: string): FileType => {
  // CAD formats
  if (CAD_MIME_TYPES.has(mimeType)) return 'cad';
  // Diagram formats
  if (DIAGRAM_MIME_TYPES.has(mimeType)) return 'diagram';
  // Markdown (specific check before generic text/* to avoid falling into 'text')
  if (mimeType === 'text/markdown') return 'markdown';

  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType.startsWith('text/') || mimeType === 'application/json') return 'text';
  // Office documents: OpenXML, ODF, legacy MS Office formats, and RTF
  if (mimeType.includes('officedocument') ||
      mimeType.includes('opendocument') ||
      mimeType === 'application/rtf' ||
      mimeType === 'text/rtf' ||
      mimeType === 'application/msword' ||
      mimeType === 'application/vnd.ms-excel' ||
      mimeType === 'application/vnd.ms-powerpoint') return 'office';

  // Extension-based fallback for application/octet-stream
  if (mimeType === 'application/octet-stream' && fileName) {
    const ext = fileName.split('.').pop()?.toLowerCase();
    if (ext && ext in EXTENSION_FILE_TYPE_MAP) {
      return EXTENSION_FILE_TYPE_MAP[ext];
    }
  }

  return 'unsupported';
};

/**
 * Determines if an object can be previewed.
 * Checks:
 * 1. Object is a document (baseType === 'cmis:document')
 * 2. Has content (contentStreamMimeType is set)
 * 3. User has permission to get content stream (allowableActions.canGetContentStream === true)
 * 4. File type is supported for preview
 *
 * Note: CMIS allowableActions is an object with boolean properties, NOT an array.
 * Example: { canGetContentStream: true, canDeleteObject: false, ... }
 */
export const canPreview = (object: CMISObject): boolean => {
  // Check if allowableActions allows content stream access
  // CMIS 1.1 returns allowableActions as an object with boolean values
  const canGetContent = object.allowableActions?.canGetContentStream === true;

  return object.baseType === 'cmis:document' &&
         !!object.contentStreamMimeType &&
         canGetContent &&
         getFileType(object.contentStreamMimeType, object.name) !== 'unsupported';
};

/**
 * Extract repositoryId and objectId from a CMIS content URL.
 * URL format: /core/browser/{repositoryId}/node/{objectId}/content
 */
export const extractIdsFromUrl = (url: string): { repoId: string | null; objId: string | null } => {
  const match = url.match(/\/core\/browser\/([^/]+)\/node\/([^/]+)/);
  if (match) return { repoId: match[1], objId: match[2] };
  return { repoId: null, objId: null };
};

export const getSupportedMimeTypes = (): string[] => {
  return [
    'image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/bmp', 'image/webp', 'image/svg+xml',
    'video/mp4', 'video/webm', 'video/ogg', 'video/avi', 'video/mov', 'video/wmv',
    'application/pdf',
    'text/plain', 'text/html', 'text/css', 'text/javascript', 'text/xml', 'application/json',
    'application/xml', 'text/csv', 'text/markdown',
    // Modern Office formats (OpenXML)
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    // OpenDocument formats
    'application/vnd.oasis.opendocument.text',
    'application/vnd.oasis.opendocument.spreadsheet',
    'application/vnd.oasis.opendocument.presentation',
    // Legacy Office formats
    'application/msword',
    'application/vnd.ms-excel',
    'application/vnd.ms-powerpoint',
    // RTF
    'application/rtf',
    'text/rtf',
    // CAD formats
    ...CAD_MIME_TYPES,
    // Diagram formats
    ...DIAGRAM_MIME_TYPES,
  ];
};
