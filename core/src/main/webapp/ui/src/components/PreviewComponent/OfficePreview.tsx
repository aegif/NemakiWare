/**
 * OfficePreview Component for NemakiWare React UI
 *
 * Office document preview component with PDF rendition support:
 * - Attempts to load PDF rendition for Office documents (Word, Excel, PowerPoint, OpenDocument)
 * - Uses react-pdf (PDF.js) for reliable PDF rendering (avoids iframe issues)
 * - Falls back to download-only UI if no rendition is available
 * - Retry button to regenerate rendition if initial load fails
 * - Error handling with user-friendly messages
 * - Japanese localized messages for user-friendly communication
 *
 * Component Architecture:
 * OfficePreview (rendition-aware preview)
 *   ├─ Loading state: <Spin> while fetching renditions
 *   ├─ PDF Preview state: react-pdf Document/Page with blob URL
 *   └─ Fallback state: Download-only UI with retry option
 *
 * Supported Office File Types (via MIME Type Detection):
 * - Microsoft Word: application/vnd.openxmlformats-officedocument.wordprocessingml.document (.docx)
 * - Microsoft Excel: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet (.xlsx)
 * - Microsoft PowerPoint: application/vnd.openxmlformats-officedocument.presentationml.presentation (.pptx)
 * - OpenDocument Text: application/vnd.oasis.opendocument.text (.odt)
 * - OpenDocument Spreadsheet: application/vnd.oasis.opendocument.spreadsheet (.ods)
 * - OpenDocument Presentation: application/vnd.oasis.opendocument.presentation (.odp)
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Alert, Button, Space, Spin, message } from 'antd';
import { DownloadOutlined, FileTextOutlined, ReloadOutlined } from '@ant-design/icons';
import { CMISService } from '../../services/cmis';
import { Document, Page, pdfjs } from 'react-pdf';

// Required CSS for react-pdf text and annotation layers
import 'react-pdf/dist/Page/TextLayer.css';
import 'react-pdf/dist/Page/AnnotationLayer.css';

// SECURITY: Configure worker with patched pdfjs-dist@5.3.31 (CVE-2024-4367 fixed)
// Use local worker file from public directory to avoid CORS issues with CDN
pdfjs.GlobalWorkerOptions.workerSrc = '/core/ui/dist/pdf-worker/pdf.worker.min.mjs';

interface OfficePreviewProps {
  url: string;
  fileName: string;
  mimeType: string;
  repositoryId?: string;
  objectId?: string;
}

interface Rendition {
  id: string;
  title: string;
  kind: string;
  mimetype: string;
  length: number;
}

export const OfficePreview: React.FC<OfficePreviewProps> = ({
  url,
  fileName,
  mimeType,
  repositoryId,
  objectId
}) => {
  const [loading, setLoading] = useState(true);
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retryCount, setRetryCount] = useState(0);
  const maxRetries = 3;

  // react-pdf state
  const [numPages, setNumPages] = useState<number | null>(null);
  const [pageNumber, setPageNumber] = useState<number>(1);
  const [scale, setScale] = useState<number>(1.0);
  const [pdfError, setPdfError] = useState<string | null>(null);

  const getFileTypeDescription = (mimeType: string) => {
    if (mimeType.includes('wordprocessingml')) return 'Word文書';
    if (mimeType.includes('spreadsheetml')) return 'Excel文書';
    if (mimeType.includes('presentationml')) return 'PowerPoint文書';
    if (mimeType.includes('opendocument.text')) return 'OpenDocument テキスト';
    if (mimeType.includes('opendocument.spreadsheet')) return 'OpenDocument スプレッドシート';
    if (mimeType.includes('opendocument.presentation')) return 'OpenDocument プレゼンテーション';
    return 'オフィス文書';
  };

  // react-pdf callbacks
  const onDocumentLoadSuccess = ({ numPages }: { numPages: number }) => {
    setNumPages(numPages);
    setPdfError(null);
  };

  const onDocumentLoadError = (err: Error) => {
    console.error('PDF load error:', err);
    setPdfError(`PDF読み込みに失敗しました: ${err.message}`);
  };

  // Navigation and zoom handlers
  const goToPrevPage = () => setPageNumber((prev) => Math.max(prev - 1, 1));
  const goToNextPage = () => setPageNumber((prev) => Math.min(prev + 1, numPages || 1));
  const zoomIn = () => setScale((prev) => Math.min(prev + 0.25, 3.0));
  const zoomOut = () => setScale((prev) => Math.max(prev - 0.25, 0.5));

  const loadRendition = useCallback(async () => {
    if (!repositoryId || !objectId) {
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const cmisService = new CMISService();
      
      // Get renditions for this document
      const renditions: Rendition[] = await cmisService.getRenditions(repositoryId, objectId);
      
      // Find PDF rendition (cmis:preview kind)
      const pdfRendition = renditions.find(
        (r: Rendition) => r.kind === 'cmis:preview' && r.mimetype === 'application/pdf'
      );

      if (pdfRendition) {
        // Load PDF rendition as blob URL
        const blobUrl = await cmisService.getRenditionBlobUrl(
          repositoryId, 
          objectId, 
          pdfRendition.id, 
          'application/pdf'
        );
        setPdfBlobUrl(blobUrl);
      } else {
        // No rendition available
        setError('rendition_not_available');
      }
    } catch (err) {
      console.error('Failed to load rendition:', err);
      setError('load_failed');
    } finally {
      setLoading(false);
    }
  }, [repositoryId, objectId]);

  const handleRetry = async () => {
    if (retryCount >= maxRetries) {
      message.error('再試行回数の上限に達しました。ダウンロードしてご確認ください。');
      return;
    }

    setRetryCount(prev => prev + 1);
    setLoading(true);
    setError(null);

    try {
      const cmisService = new CMISService();
      
      // Try to generate rendition
      await cmisService.generateRendition(repositoryId!, objectId!, false);
      
      // Wait a moment for rendition to be created
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      // Try to load again
      await loadRendition();
    } catch (err) {
      console.error('Failed to generate rendition:', err);
      message.error('レンディション生成に失敗しました');
      setError('generation_failed');
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRendition();

    // Cleanup blob URL on unmount
    return () => {
      if (pdfBlobUrl) {
        URL.revokeObjectURL(pdfBlobUrl);
      }
    };
  }, [loadRendition]);

  // Loading state
  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '40px' }}>
        <Spin size="large" />
        <p style={{ marginTop: '16px' }}>プレビューを読み込み中...</p>
      </div>
    );
  }

  // PDF rendition available - show with react-pdf
  if (pdfBlobUrl) {
    return (
      <div>
        {pdfError && (
          <Alert
            message="エラー"
            description={pdfError}
            type="error"
            showIcon
            style={{ marginBottom: '16px' }}
          />
        )}

        {!pdfError && (
          <>
            {/* Toolbar */}
            <div style={{
              marginBottom: '16px',
              display: 'flex',
              gap: '8px',
              alignItems: 'center',
              flexWrap: 'wrap'
            }}>
              <Button onClick={goToPrevPage} disabled={pageNumber <= 1}>
                前へ
              </Button>
              <span>
                {numPages ? `${pageNumber} / ${numPages}` : 'ページ読み込み中...'}
              </span>
              <Button onClick={goToNextPage} disabled={!numPages || pageNumber >= numPages}>
                次へ
              </Button>
              <Button onClick={zoomOut} disabled={scale <= 0.5}>
                縮小
              </Button>
              <span>{Math.round(scale * 100)}%</span>
              <Button onClick={zoomIn} disabled={scale >= 3.0}>
                拡大
              </Button>
              <Button
                type="primary"
                icon={<DownloadOutlined />}
                onClick={() => window.open(url, '_blank')}
                style={{ marginLeft: 'auto' }}
              >
                ダウンロード
              </Button>
            </div>

            {/* PDF Document */}
            <div style={{
              border: '1px solid #d9d9d9',
              padding: '16px',
              overflowX: 'auto',
              maxHeight: '800px',
              overflowY: 'auto'
            }}>
              <Document
                file={pdfBlobUrl}
                onLoadSuccess={onDocumentLoadSuccess}
                onLoadError={onDocumentLoadError}
                loading={
                  <div style={{ textAlign: 'center', padding: '40px' }}>
                    <Spin size="large" />
                    <p style={{ marginTop: '16px' }}>PDFを読み込んでいます...</p>
                  </div>
                }
              >
                {numPages && (
                  <Page
                    pageNumber={pageNumber}
                    scale={scale}
                    renderTextLayer={true}
                    renderAnnotationLayer={true}
                  />
                )}
              </Document>
            </div>
          </>
        )}
      </div>
    );
  }

  // Fallback: Download-only UI with retry option
  return (
    <div style={{ textAlign: 'center', padding: '40px' }}>
      <FileTextOutlined style={{ fontSize: '64px', color: '#1890ff', marginBottom: '24px' }} />
      <Alert
        message="オフィス文書のプレビュー"
        description={
          <Space direction="vertical" size="large">
            <div>
              <p><strong>{fileName}</strong></p>
              <p>{getFileTypeDescription(mimeType)}</p>
            </div>
            {error === 'rendition_not_available' && (
              <p>このドキュメントのPDFプレビューはまだ生成されていません。</p>
            )}
            {error === 'load_failed' && (
              <p>プレビューの読み込みに失敗しました。</p>
            )}
            {error === 'generation_failed' && (
              <p>プレビューの生成に失敗しました。</p>
            )}
            {!error && (
              <p>オフィス文書のプレビューは現在サポートされていません。<br />ダウンロードしてローカルアプリケーションでご確認ください。</p>
            )}
            <Space>
              {repositoryId && objectId && retryCount < maxRetries && (
                <Button 
                  icon={<ReloadOutlined />} 
                  onClick={handleRetry}
                >
                  プレビュー生成を試行 ({maxRetries - retryCount}回)
                </Button>
              )}
              <Button 
                type="primary"
                icon={<DownloadOutlined />} 
                onClick={() => window.open(url, '_blank')}
                size="large"
              >
                ダウンロード
              </Button>
            </Space>
          </Space>
        }
        type="info"
        showIcon={false}
      />
    </div>
  );
};
