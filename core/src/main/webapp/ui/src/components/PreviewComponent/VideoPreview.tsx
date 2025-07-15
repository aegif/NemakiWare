import React from 'react';
import ReactPlayer from 'react-player';

interface VideoPreviewProps {
  url: string;
  fileName: string;
}

export const VideoPreview: React.FC<VideoPreviewProps> = ({ url, fileName }) => {
  return (
    <div style={{ maxWidth: '100%', textAlign: 'center' }}>
      <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>
      <div style={{ width: '100%', height: '400px' }}>
        <ReactPlayer 
          url={url} 
          controls
        />
      </div>
    </div>
  );
};
