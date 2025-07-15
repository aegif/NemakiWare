import React from 'react';
import { Worker, Viewer } from '@react-pdf-viewer/core';
import '@react-pdf-viewer/core/lib/styles/index.css';

interface PDFPreviewProps {
  url: string;
  fileName: string;
}

export const PDFPreview: React.FC<PDFPreviewProps> = ({ url, fileName }) => {
  return (
    <div>
      <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>
      <div style={{ height: '600px', border: '1px solid #d9d9d9' }}>
        <Worker workerUrl="https://unpkg.com/pdfjs-dist@3.4.120/build/pdf.worker.min.js">
          <Viewer fileUrl={url} />
        </Worker>
      </div>
    </div>
  );
};
