import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Alert, Button, Space, Spin, message } from 'antd';
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import DOMPurify from 'dompurify';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';
import { extractIdsFromUrl } from '../../utils/previewUtils';

interface CadPreviewProps {
  url: string;
  fileName: string;
  mimeType: string;
  repositoryId?: string;
  objectId?: string;
  lastModified?: string | number;
}

export const CadPreview: React.FC<CadPreviewProps> = ({
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
  const [error, setError] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [zoom, setZoom] = useState(1);

  const fetchSvgContent = async (repoId: string, objId: string, streamId: string): Promise<string | null> => {
    try {
      const blob = await cmisService.getRenditionContent(repoId, objId, streamId);
      return await blob.text();
    } catch (err) {
      console.error('[CadPreview] Error fetching SVG content:', err);
      return null;
    }
  };

  const fetchRenditions = async () => {
    const { repoId, objId } = extractIdsFromUrl(url);
    const effectiveRepoId = repositoryId || repoId;
    const effectiveObjId = objectId || objId;

    if (!effectiveRepoId || !effectiveObjId) {
      setError(t('preview.cad.missingInfo'));
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
              setError(t('preview.cad.svgContentError'));
            }
          }
        }
      } else {
        await generateRendition(effectiveRepoId, effectiveObjId, false);
      }
    } catch (err) {
      console.error('[CadPreview] Error fetching renditions:', err);
      setError(t('preview.cad.renditionFetchError'));
    } finally {
      setLoading(false);
    }
  };

  const generateRendition = async (repoId: string, objId: string, force: boolean = false) => {
    setGenerating(true);
    try {
      await cmisService.generateRenditions(repoId, objId, force);
      message.info(t('preview.cad.generating'));

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
            message.success(t('preview.cad.generationComplete'));
          } else {
            setError(t('preview.cad.svgContentError'));
          }
        }
      } else {
        setError(t('preview.cad.generationTakingLong'));
      }
    } catch (err: any) {
      setError(t('preview.cad.generationFailed', { error: err.message || t('common.unknownError') }));
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

  if (loading || generating) {
    return (
      <div style={{ textAlign: 'center', padding: '60px' }}>
        <Spin size="large" />
        <p style={{ marginTop: '16px', color: '#666' }}>
          {generating ? t('preview.cad.generating') : t('preview.cad.loadingPreview')}
        </p>
      </div>
    );
  }

  if (svgContent) {
    const sanitized = DOMPurify.sanitize(svgContent, {
      USE_PROFILES: { svg: true, svgFilters: true },
      ADD_TAGS: ['use'],
    });

    return (
      <div data-testid="cad-preview-svg">
        <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
          <Button size="small" onClick={() => setZoom(z => Math.max(0.25, z - 0.25))}>-</Button>
          <span>{Math.round(zoom * 100)}%</span>
          <Button size="small" onClick={() => setZoom(z => Math.min(4, z + 0.25))}>+</Button>
          <Button size="small" onClick={() => setZoom(1)}>{t('preview.cad.resetZoom')}</Button>
          <Button size="small" icon={<ReloadOutlined />} onClick={handleForceRegenerate}>
            {t('preview.cad.regenerate')}
          </Button>
        </div>
        <div
          style={{
            overflow: 'auto',
            border: '1px solid #d9d9d9',
            borderRadius: 4,
            background: '#fff',
            maxHeight: '70vh',
          }}
        >
          <div
            style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
            dangerouslySetInnerHTML={{ __html: sanitized }}
          />
        </div>
      </div>
    );
  }

  return (
    <div style={{ textAlign: 'center', padding: '40px' }} data-testid="cad-preview-fallback">
      <Alert
        message={error || t('preview.cad.title')}
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
              <Button type="primary" icon={<DownloadOutlined />} onClick={() => window.open(url, '_blank', 'noopener,noreferrer')} size="large">
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
