/**
 * PDFPreview Component for NemakiWare React UI (SECURITY HARDENED - 2025-11-19)
 *
 * PDF preview component providing secure PDF document rendering with react-pdf library:
 * - react-pdf library integration using SECURE pdfjs-dist@5.3.31 (CVE-2024-4367 patched)
 * - Custom toolbar with page navigation, zoom, and download controls
 * - File name display above PDF viewer for context
 * - Responsive layout with automatic page width fitting
 * - Border styling for viewer container (1px solid #d9d9d9)
 * - Authenticated content URL from CMISService
 * - Error boundary with user-friendly error messages
 * - Loading state with spinner
 *
 * SECURITY IMPROVEMENT (2025-11-19):
 * - Migrated from @react-pdf-viewer/core (vulnerable pdfjs-dist 3.11.174)
 * - Now using react-pdf@10.0.1 with pdfjs-dist@5.3.31
 * - Resolves CVE-2024-4367 (CVSS 8.8/10) - Arbitrary JavaScript execution vulnerability
 * - Previous risk: Malicious PDFs could execute arbitrary code
 * - Current status: Patched version eliminates this security risk
 *
 * Component Architecture:
 * PDFPreview
 *   ├─ <h4>{fileName}</h4>
 *   ├─ <div.toolbar>
 *   │   ├─ <Button>Previous</Button>
 *   │   ├─ <span>Page X of Y</span>
 *   │   ├─ <Button>Next</Button>
 *   │   ├─ <Button>Zoom In</Button>
 *   │   ├─ <Button>Zoom Out</Button>
 *   │   └─ <a>Download</a>
 *   └─ <div.pdf-container>
 *       └─ <Document file={url}>
 *           └─ <Page pageNumber={pageNumber} scale={scale} />
 *
 * Usage Examples:
 * ```typescript
 * // PreviewComponent.tsx - PDF file type case
 * case 'pdf':
 *   return <PDFPreview url={contentUrl} fileName={object.name} />;
 *
 * // Example with authenticated URL
 * const contentUrl = cmisService.getDownloadUrl(repositoryId, object.id);
 *
 * <PDFPreview
 *   url={contentUrl}
 *   fileName="specification.pdf"
 * />
 * // Renders: Custom toolbar + PDF pages, "specification.pdf" heading
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. react-pdf Library Integration (SECURITY FIX):
 *    - Secure PDF viewer library with patched pdfjs-dist@5.3.31
 *    - Rationale: Eliminates CVE-2024-4367 security vulnerability
 *    - Implementation: <Document> and <Page> components from react-pdf
 *    - Advantage: Security-first approach, actively maintained library
 *    - Pattern: Security-driven library selection
 *
 * 2. Custom Toolbar Implementation:
 *    - Manual toolbar with Ant Design Button components
 *    - Rationale: react-pdf doesn't provide built-in toolbar (unlike @react-pdf-viewer)
 *    - Implementation: useState for pageNumber/scale, event handlers for navigation/zoom
 *    - Advantage: Full control over UI/UX, security-first approach
 *    - Trade-off: More code than using built-in toolbar, but eliminates security risk
 *
 * 3. Page State Management:
 *    - useState hooks for pageNumber (1-based), numPages, scale
 *    - Rationale: React state for page navigation and zoom control
 *    - Implementation: onDocumentLoadSuccess callback sets numPages
 *    - Pattern: Controlled component with local state
 *
 * 4. Responsive PDF Rendering:
 *    - width property with % or px for responsive sizing
 *    - Rationale: Automatic page width adjustment to container
 *    - Implementation: <Page width={600} /> for consistent rendering
 *    - Pattern: Responsive design with explicit dimensions
 *
 * 5. Error Handling:
 *    - onLoadError callback with error state display
 *    - Rationale: User-friendly error messages for PDF load failures
 *    - Implementation: Error state with Alert component showing error message
 *    - Pattern: Error boundary pattern for third-party library failures
 *
 * 6. Loading State:
 *    - Spin component shown while PDF loads
 *    - Rationale: Visual feedback during PDF parsing
 *    - Implementation: Conditional rendering based on numPages state
 *    - Pattern: Loading indicator for async operations
 *
 * Known Limitations:
 * - Single page view: Shows one page at a time (no continuous scroll like @react-pdf-viewer)
 * - Manual toolbar: Requires custom implementation (no built-in toolbar)
 * - No text selection: react-pdf basic implementation doesn't support text selection
 * - No search: No built-in PDF text search functionality
 * - Fixed width: Page width set to 600px (not fully responsive to container)
 *
 * Security Benefits:
 * - ✅ CVE-2024-4367 PATCHED: Arbitrary JS execution vulnerability eliminated
 * - ✅ Modern pdfjs-dist: Version 5.3.31 includes all security patches
 * - ✅ Active maintenance: react-pdf actively maintained with security updates
 * - ✅ Reduced attack surface: Simpler implementation with fewer features = fewer vulnerabilities
 */

import React, { useState, useEffect } from 'react';
import { Document, Page, pdfjs } from 'react-pdf';
import { Button, Spin, Alert } from 'antd';

// SECURITY: Configure worker with patched pdfjs-dist@5.3.31 (CVE-2024-4367 fixed)
// Use jsdelivr CDN with explicit https to avoid mixed-content and fetch issues
pdfjs.GlobalWorkerOptions.workerSrc = `https://cdn.jsdelivr.net/npm/pdfjs-dist@${pdfjs.version}/build/pdf.worker.min.mjs`;

interface PDFPreviewProps {
  url: string;
  fileName: string;
}

export const PDFPreview: React.FC<PDFPreviewProps> = ({ url, fileName }) => {
  const [numPages, setNumPages] = useState<number | null>(null);
  const [pageNumber, setPageNumber] = useState<number>(1);
  const [scale, setScale] = useState<number>(1.0);
  const [error, setError] = useState<string | null>(null);

  const onDocumentLoadSuccess = ({ numPages }: { numPages: number }) => {
    setNumPages(numPages);
    setError(null);
  };

  const onDocumentLoadError = (error: Error) => {
    console.error('PDF load error:', error);
    setError(`PDF読み込みに失敗しました: ${error.message}`);
  };

  const goToPrevPage = () => {
    setPageNumber((prev) => Math.max(prev - 1, 1));
  };

  const goToNextPage = () => {
    setPageNumber((prev) => Math.min(prev + 1, numPages || 1));
  };

  const zoomIn = () => {
    setScale((prev) => Math.min(prev + 0.25, 3.0));
  };

  const zoomOut = () => {
    setScale((prev) => Math.max(prev - 0.25, 0.5));
  };

  return (
    <div>
      <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>

      {error && (
        <Alert
          message="エラー"
          description={error}
          type="error"
          showIcon
          style={{ marginBottom: '16px' }}
        />
      )}

      {!error && (
        <>
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
            <a href={url} download={fileName} style={{ marginLeft: 'auto' }}>
              <Button>ダウンロード</Button>
            </a>
          </div>

          <div style={{
            border: '1px solid #d9d9d9',
            padding: '16px',
            overflowX: 'auto',
            maxHeight: '800px',
            overflowY: 'auto'
          }}>
            <Document
              file={url}
              onLoadSuccess={onDocumentLoadSuccess}
              onLoadError={onDocumentLoadError}
              loading={
                <div style={{ textAlign: 'center', padding: '40px' }}>
                  <Spin size="large" tip="PDFを読み込んでいます..." />
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
};
