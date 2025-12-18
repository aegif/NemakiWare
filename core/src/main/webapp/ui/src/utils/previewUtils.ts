import { CMISObject } from '../types/cmis';

export const getFileType = (mimeType: string): 'image' | 'video' | 'pdf' | 'text' | 'office' | 'unsupported' => {
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
         getFileType(object.contentStreamMimeType) !== 'unsupported';
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
    'text/rtf'
  ];
};
