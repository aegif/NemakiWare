import React from 'react';

interface ImagePreviewProps {
  url: string;
  fileName: string;
}

export const ImagePreview: React.FC<ImagePreviewProps> = ({ url, fileName }) => {
  return (
    <div style={{ maxWidth: '100%', maxHeight: '600px', textAlign: 'center' }}>
      <img 
        src={url} 
        alt={fileName}
        style={{ 
          maxWidth: '100%', 
          maxHeight: '600px', 
          objectFit: 'contain',
          border: '1px solid #ddd',
          borderRadius: '4px'
        }}
      />
      <p style={{ marginTop: '8px', fontSize: '14px', color: '#666' }}>
        {fileName}
      </p>
    </div>
  );
};
