import { CMISObject } from '../types/cmis';

export const getFileType = (mimeType: string): 'image' | 'video' | 'pdf' | 'text' | 'office' | 'unsupported' => {
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType.startsWith('text/') || mimeType === 'application/json') return 'text';
  if (mimeType.includes('officedocument') || mimeType.includes('opendocument')) return 'office';
  return 'unsupported';
};

export const canPreview = (object: CMISObject): boolean => {
  return object.baseType === 'cmis:document' && 
         !!object.contentStreamMimeType && 
         object.allowableActions?.includes('canGetContentStream') &&
         getFileType(object.contentStreamMimeType) !== 'unsupported';
};

export const getSupportedMimeTypes = (): string[] => {
  return [
    'image/jpeg', 'image/jpg', 'image/png', 'image/gif', 'image/bmp', 'image/webp', 'image/svg+xml',
    'video/mp4', 'video/webm', 'video/ogg', 'video/avi', 'video/mov', 'video/wmv',
    'application/pdf',
    'text/plain', 'text/html', 'text/css', 'text/javascript', 'text/xml', 'application/json',
    'application/xml', 'text/csv', 'text/markdown',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'application/vnd.oasis.opendocument.text',
    'application/vnd.oasis.opendocument.spreadsheet',
    'application/vnd.oasis.opendocument.presentation'
  ];
};
