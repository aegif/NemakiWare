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
        {/* TypeScript workaround: react-player v3.x type definitions issue */}
        <ReactPlayer
          {...{ url, controls: true } as any}
        />
      </div>
    </div>
  );
};
