import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Alert, Button, Space, Spin, Segmented, message } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import DOMPurify from 'dompurify';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';
import { extractIdsFromUrl } from '../../utils/previewUtils';

interface DiagramPreviewProps {
  url: string;
  fileName: string;
  mimeType: string;
  repositoryId?: string;
  objectId?: string;
  lastModified?: string | number;
}

export const DiagramPreview: React.FC<DiagramPreviewProps> = ({
  url,
  fileName,
  mimeType,
  repositoryId,
  objectId,
  lastModified
}) => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const cmisService = useMemo(() => new CMISService(handleAuthError), [handleAuthError]);
  const cancelledRef = useRef(false);

  const [loading, setLoading] = useState(true);
  const [svgContent, setSvgContent] = useState<string | null>(null);
  const [sourceContent, setSourceContent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [viewMode, setViewMode] = useState<'diagram' | 'source'>('diagram');

  const fetchSvgContent = async (repoId: string, objId: string, streamId: string): Promise<string | null> => {
    try {
      const blob = await cmisService.getRenditionContent(repoId, objId, streamId);
      return await blob.text();
    } catch (err) {
      console.error('[DiagramPreview] Error fetching SVG content:', err);
      return null;
    }
  };

  const fetchSourceContent = async () => {
    try {
      const effectiveRepoId = repositoryId;
      const effectiveObjId = objectId;

      if (!effectiveRepoId || !effectiveObjId) return;

      const arrayBuffer = await cmisService.getContentStream(effectiveRepoId, effectiveObjId);
      const text = new TextDecoder('utf-8').decode(arrayBuffer);
      setSourceContent(text);
    } catch (err) {
      console.error('[DiagramPreview] Error fetching source:', err);
    }
  };

  const fetchRenditions = async () => {
    const { repoId, objId } = extractIdsFromUrl(url);
    const effectiveRepoId = repositoryId || repoId;
    const effectiveObjId = objectId || objId;

    if (!effectiveRepoId || !effectiveObjId) {
      setError(t('preview.diagram.missingInfo'));
      setLoading(false);
      return;
    }

    try {
      const renditions = await cmisService.getRenditions(effectiveRepoId, effectiveObjId);

      const svgRendition = renditions.find((r: any) =>
        r.mimeType === 'image/svg+xml' || r.contentMimeType === 'image/svg+xml'
      );

      if (svgRendition) {
        const renditionModified = svgRendition.modified || svgRendition.created;
        let isStale = false;
        if (lastModified && renditionModified) {
          const docModifiedMs = typeof lastModified === 'number' ? lastModified : new Date(lastModified).getTime();
          if (docModifiedMs > renditionModified) isStale = true;
        }

        if (isStale) {
          await generateRendition(effectiveRepoId, effectiveObjId, true);
        } else {
          const streamId = svgRendition.streamId || svgRendition.renditionDocumentId;
          if (streamId) {
            const svg = await fetchSvgContent(effectiveRepoId, effectiveObjId, streamId);
            if (svg) {
              setSvgContent(svg);
              setError(null);
            } else {
              setError(t('preview.diagram.svgContentError'));
            }
          }
        }
      } else {
        await generateRendition(effectiveRepoId, effectiveObjId, false);
      }
    } catch (err) {
      console.error('[DiagramPreview] Error fetching renditions:', err);
      setError(t('preview.diagram.renditionFetchError'));
    } finally {
      setLoading(false);
    }
  };

  const generateRendition = async (repoId: string, objId: string, force: boolean = false) => {
    setGenerating(true);
    try {
      await cmisService.generateRenditions(repoId, objId, force);
      message.info(t('preview.diagram.generating'));

      await new Promise(resolve => setTimeout(resolve, 5000));

      const renditions = await cmisService.getRenditions(repoId, objId);
      const svgRendition = renditions.find((r: any) =>
        r.mimeType === 'image/svg+xml' || r.contentMimeType === 'image/svg+xml'
      );

      if (svgRendition) {
        const streamId = svgRendition.streamId || svgRendition.renditionDocumentId;
        if (streamId) {
          const svg = await fetchSvgContent(repoId, objId, streamId);
          if (svg) {
            setSvgContent(svg);
            setError(null);
            message.success(t('preview.diagram.generationComplete'));
          } else {
            setError(t('preview.diagram.svgContentError'));
          }
        }
      } else {
        setError(t('preview.diagram.generationTakingLong'));
      }
    } catch (err: any) {
      setError(t('preview.diagram.generationFailed', { error: err.message || t('common.unknownError') }));
    } finally {
      setGenerating(false);
    }
  };

  const handleForceRegenerate = async () => {
    const { repoId, objId } = extractIdsFromUrl(url);
    const effectiveRepoId = repositoryId || repoId;
    const effectiveObjId = objectId || objId;

    if (effectiveRepoId && effectiveObjId) {
      setSvgContent(null);
      setError(null);
      await generateRendition(effectiveRepoId, effectiveObjId, true);
    }
  };

  useEffect(() => {
    cancelledRef.current = false;
    fetchRenditions();
    return () => { cancelledRef.current = true; };
  }, [url, repositoryId, objectId]);

  // Lazy-load source content only when source tab is selected
  useEffect(() => {
    if (viewMode === 'source' && sourceContent === null) {
      fetchSourceContent();
    }
  }, [viewMode]);

  if (loading || generating) {
    return (
      <div style={{ textAlign: 'center', padding: '60px' }}>
        <Spin size="large" />
        <p style={{ marginTop: '16px', color: '#666' }}>
          {generating ? t('preview.diagram.generating') : t('preview.diagram.loadingPreview')}
        </p>
      </div>
    );
  }

  if (svgContent || sourceContent) {
    const sanitized = svgContent
      ? DOMPurify.sanitize(svgContent, {
          USE_PROFILES: { svg: true, svgFilters: true },
          ADD_TAGS: ['use'],
        })
      : '';

    const diagramType = mimeType === 'text/vnd.graphviz' ? 'Graphviz DOT' : 'PlantUML';

    return (
      <div data-testid="diagram-preview">
        <div style={{ marginBottom: 12, display: 'flex', gap: 12, alignItems: 'center' }}>
          <Segmented
            options={[
              { label: t('preview.diagram.diagramMode'), value: 'diagram' },
              { label: t('preview.diagram.sourceMode'), value: 'source' },
            ]}
            value={viewMode}
            onChange={(val) => setViewMode(val as 'diagram' | 'source')}
          />
          {viewMode === 'diagram' && (
            <Button size="small" icon={<ReloadOutlined />} onClick={handleForceRegenerate}>
              {t('preview.diagram.regenerate')}
            </Button>
          )}
        </div>

        {viewMode === 'diagram' ? (
          svgContent ? (
            <div
              style={{
                overflow: 'auto',
                border: '1px solid #d9d9d9',
                borderRadius: 4,
                background: '#fff',
                maxHeight: '70vh',
                padding: 16,
              }}
              dangerouslySetInnerHTML={{ __html: sanitized }}
            />
          ) : (
            <Alert message={t('preview.diagram.noSvg')} type="info" />
          )
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
            <div style={{ color: '#999', marginBottom: 8 }}>{diagramType}</div>
            {sourceContent || t('preview.diagram.sourceNotAvailable')}
          </pre>
        )}
      </div>
    );
  }

  return (
    <div style={{ textAlign: 'center', padding: '40px' }} data-testid="diagram-preview-fallback">
      <Alert
        message={error || t('preview.diagram.title')}
        description={
          <Space direction="vertical" size="large">
            <div>
              <p><strong>{fileName}</strong></p>
              <p>{mimeType}</p>
            </div>
            <Space>
              {error && (
                <Button icon={<ReloadOutlined />} onClick={handleForceRegenerate}>
                  {t('common.retry')}
                </Button>
              )}
              <Button type="primary" icon={<DownloadOutlined />} onClick={() => window.open(url, '_blank')} size="large">
                {t('common.download')}
              </Button>
            </Space>
          </Space>
        }
        type={error ? 'warning' : 'info'}
        showIcon={false}
      />
    </div>
  );
};
