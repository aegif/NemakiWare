import React, { useState, useEffect } from 'react';
import { Worker, Viewer } from '@react-pdf-viewer/core';
import '@react-pdf-viewer/core/lib/styles/index.css';
import { Spin, Alert } from 'antd';
import { CMISService } from '../../services/cmis';

interface PDFPreviewProps {
  url: string;
  fileName: string;
  repositoryId?: string;
  objectId?: string;
}

export const PDFPreview: React.FC<PDFPreviewProps> = ({ url, fileName, repositoryId, objectId }) => {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const loadPdf = async () => {
      setLoading(true);
      setError(null);
      
      try {
        if (repositoryId && objectId) {
          const cmisService = new CMISService();
          const arrayBuffer = await cmisService.getContentStream(repositoryId, objectId);
          const blob = new Blob([arrayBuffer], { type: 'application/pdf' });
          const objectUrl = URL.createObjectURL(blob);
          setBlobUrl(objectUrl);
        } else {
          setBlobUrl(url);
        }
      } catch (err) {
        console.error('Failed to load PDF:', err);
        setError('PDFの読み込みに失敗しました');
      } finally {
        setLoading(false);
      }
    };

    loadPdf();

    return () => {
      if (blobUrl && blobUrl.startsWith('blob:')) {
        URL.revokeObjectURL(blobUrl);
      }
    };
  }, [repositoryId, objectId, url]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" />
        <p style={{ marginTop: '16px' }}>PDFを読み込み中...</p>
      </div>
    );
  }

  if (error) {
    return <Alert message="エラー" description={error} type="error" />;
  }

  if (!blobUrl) {
    return <Alert message="エラー" description="PDFのURLが取得できませんでした" type="error" />;
  }

  return (
    <div>
      <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>
      <div style={{ height: '600px', border: '1px solid #d9d9d9' }}>
        <Worker workerUrl="https://unpkg.com/pdfjs-dist@3.4.120/build/pdf.worker.min.js">
          <Viewer fileUrl={blobUrl} />
        </Worker>
      </div>
    </div>
  );
};
