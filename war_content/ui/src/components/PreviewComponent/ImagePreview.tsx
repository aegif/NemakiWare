import React from 'react';
import ImageGallery from 'react-image-gallery';
import 'react-image-gallery/styles/css/image-gallery.css';

interface ImagePreviewProps {
  url: string;
  fileName: string;
}

export const ImagePreview: React.FC<ImagePreviewProps> = ({ url, fileName }) => {
  const images = [{ 
    original: url, 
    thumbnail: url, 
    description: fileName 
  }];
  
  return (
    <div style={{ maxWidth: '100%', maxHeight: '600px' }}>
      <ImageGallery 
        items={images} 
        showThumbnails={false} 
        showPlayButton={false}
        showFullscreenButton={true}
        showNav={false}
        showBullets={false}
      />
    </div>
  );
};
