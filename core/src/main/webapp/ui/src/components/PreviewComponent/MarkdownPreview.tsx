import React, { useState, useEffect, useMemo } from 'react';
import { Alert, Spin, Segmented } from 'antd';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';

interface MarkdownPreviewProps {
  url: string;
  fileName: string;
  repositoryId?: string;
  objectId?: string;
}

export const MarkdownPreview: React.FC<MarkdownPreviewProps> = ({
  url,
  repositoryId,
  objectId,
}) => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const cmisService = useMemo(() => new CMISService(handleAuthError), [handleAuthError]);

  const [loading, setLoading] = useState(true);
  const [content, setContent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<'preview' | 'source'>('preview');

  useEffect(() => {
    let cancelled = false;
    const fetchContent = async () => {
      try {
        if (!repositoryId || !objectId) {
          // Fall back to fetching from URL directly
          const response = await fetch(url, { credentials: 'include' });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          const text = await response.text();
          if (!cancelled) setContent(text);
          return;
        }

        const arrayBuffer = await cmisService.getContentStream(repositoryId, objectId);
        const text = new TextDecoder('utf-8').decode(arrayBuffer);
        if (!cancelled) setContent(text);
      } catch (err: any) {
        console.error('[MarkdownPreview] Error fetching content:', err);
        if (!cancelled) setError(t('preview.markdown.fetchError'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    fetchContent();
    return () => { cancelled = true; };
  }, [url, repositoryId, objectId]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '60px' }}>
        <Spin size="large" />
        <p style={{ marginTop: '16px', color: '#666' }}>{t('preview.markdown.loading')}</p>
      </div>
    );
  }

  if (error || !content) {
    return <Alert message={error || t('preview.markdown.noContent')} type="warning" />;
  }

  return (
    <div data-testid="markdown-preview">
      <div style={{ marginBottom: 12 }}>
        <Segmented
          options={[
            { label: t('preview.markdown.previewMode'), value: 'preview' },
            { label: t('preview.markdown.sourceMode'), value: 'source' },
          ]}
          value={viewMode}
          onChange={(val) => setViewMode(val as 'preview' | 'source')}
        />
      </div>

      {viewMode === 'preview' ? (
        <div
          style={{
            padding: '24px',
            border: '1px solid #d9d9d9',
            borderRadius: 4,
            background: '#fff',
            maxHeight: '70vh',
            overflow: 'auto',
          }}
          className="markdown-preview-content"
        >
          <ReactMarkdown>{content}</ReactMarkdown>
        </div>
      ) : (
        <pre
          style={{
            padding: '16px',
            border: '1px solid #d9d9d9',
            borderRadius: 4,
            background: '#f5f5f5',
            maxHeight: '70vh',
            overflow: 'auto',
            whiteSpace: 'pre-wrap',
            wordWrap: 'break-word',
            fontSize: '13px',
            lineHeight: '1.6',
          }}
        >
          {content}
        </pre>
      )}
    </div>
  );
};
