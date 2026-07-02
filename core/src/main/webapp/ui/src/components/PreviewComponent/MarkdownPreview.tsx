import React, { useState, useEffect, useMemo, useRef } from 'react';
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

/**
 * Context shared with each rendered <img> so it can resolve a relative image
 * reference against the CMIS folder that contains the Markdown document.
 */
interface MarkdownImageContext {
  repositoryId?: string;
  /** Absolute CMIS path of the folder containing the .md document (null if unresolved). */
  baseFolderPath: string | null;
  /** True once the parent-folder lookup has settled (success or failure). */
  baseFolderReady: boolean;
  cmisService: CMISService;
}

/**
 * A src is treated as external / self-contained (rendered as-is, no CMIS lookup)
 * when it carries an explicit scheme (http/https/data/blob) or is protocol-relative.
 */
const isSelfContainedSrc = (src: string): boolean =>
  /^(https?:|data:|blob:)/i.test(src) || src.startsWith('//');

/**
 * Resolve a relative Markdown image reference into an absolute CMIS path,
 * anchored at the folder that contains the document. Supports subfolders and
 * parent references (./ , ../ , leading / = repository root). Query/hash are
 * stripped. Returns null when the reference cannot be resolved to a path.
 */
export function resolveRelativeCmisPath(baseFolderPath: string, ref: string): string | null {
  let cleaned = ref.split('#')[0].split('?')[0].trim();
  if (!cleaned) return null;
  try {
    cleaned = decodeURIComponent(cleaned);
  } catch {
    // keep the raw form if it is not valid percent-encoding
  }

  const absoluteFromRoot = cleaned.startsWith('/');
  const baseSegments = absoluteFromRoot ? [] : baseFolderPath.split('/').filter(Boolean);
  const refSegments = cleaned.split('/').filter(seg => seg.length > 0);

  const stack = [...baseSegments];
  for (const seg of refSegments) {
    if (seg === '.') continue;
    if (seg === '..') {
      if (stack.length > 0) stack.pop();
      continue;
    }
    stack.push(seg);
  }
  if (stack.length === 0) return null;
  return '/' + stack.join('/');
}

/**
 * Renders a Markdown image. External / data / blob sources pass through
 * unchanged; relative sources are resolved to a CMIS object in (or relative to)
 * the document's folder and streamed into a blob URL. On any failure the alt
 * text is shown alongside a broken-image indicator.
 */
const MarkdownImage: React.FC<{
  src?: string;
  alt?: string;
  title?: string;
  ctx: MarkdownImageContext;
}> = ({ src, alt, title, ctx }) => {
  const { t } = useTranslation();
  const [resolvedSrc, setResolvedSrc] = useState<string | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const blobUrlRef = useRef<string | null>(null);

  const selfContained = !!src && isSelfContainedSrc(src);

  useEffect(() => {
    let cancelled = false;

    const revokePreviousBlob = () => {
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current);
        blobUrlRef.current = null;
      }
    };

    if (!src) {
      setStatus('error');
      return;
    }

    // External / data / blob: render directly, no CMIS resolution.
    if (selfContained) {
      setResolvedSrc(src);
      setStatus('ready');
      return;
    }

    // Relative reference: wait until the parent folder lookup has settled.
    if (!ctx.baseFolderReady) {
      setStatus('loading');
      return;
    }

    const resolve = async () => {
      setStatus('loading');
      try {
        if (!ctx.repositoryId || !ctx.baseFolderPath) {
          throw new Error('Base folder path unavailable');
        }
        const targetPath = resolveRelativeCmisPath(ctx.baseFolderPath, src);
        if (!targetPath) {
          throw new Error(`Unresolvable image reference: ${src}`);
        }
        const obj = await ctx.cmisService.getObjectByPath(ctx.repositoryId, targetPath);
        const buffer = await ctx.cmisService.getContentStream(ctx.repositoryId, obj.id);
        if (cancelled) return;
        const mime = obj.contentStreamMimeType || 'application/octet-stream';
        revokePreviousBlob();
        const blobUrl = URL.createObjectURL(new Blob([buffer], { type: mime }));
        blobUrlRef.current = blobUrl;
        setResolvedSrc(blobUrl);
        setStatus('ready');
      } catch (err) {
        if (!cancelled) {
          console.warn('[MarkdownPreview] Failed to resolve image:', src, err);
          setStatus('error');
        }
      }
    };

    resolve();
    return () => {
      cancelled = true;
    };
  }, [src, selfContained, ctx.baseFolderReady, ctx.baseFolderPath, ctx.repositoryId, ctx.cmisService]);

  // Revoke the blob URL when this image unmounts.
  useEffect(() => {
    return () => {
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current);
        blobUrlRef.current = null;
      }
    };
  }, []);

  if (status === 'ready' && resolvedSrc) {
    return <img src={resolvedSrc} alt={alt || ''} title={title} style={{ maxWidth: '100%' }} />;
  }

  if (status === 'loading') {
    return (
      <span
        data-testid="markdown-image-loading"
        style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: '#999' }}
      >
        <Spin size="small" /> {t('preview.markdown.imageLoading')}
      </span>
    );
  }

  // error / unresolved: alt text + broken-image indicator.
  return (
    <span
      data-testid="markdown-image-unresolved"
      title={t('preview.markdown.imageUnresolved')}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '2px 8px',
        border: '1px dashed #d9a441',
        borderRadius: 4,
        background: '#fffbe6',
        color: '#8a6d3b',
        fontSize: 13,
      }}
    >
      <span aria-hidden="true">🖼️</span>
      <span>{alt || src || t('preview.markdown.imageUnresolved')}</span>
      <span style={{ color: '#b8860b' }}>（{t('preview.markdown.imageUnresolved')}）</span>
    </span>
  );
};

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

  // Absolute CMIS path of the folder containing this document, used to resolve
  // relative image references. baseFolderReady flips true once the lookup
  // settles (so images know whether to wait or fall back).
  const [baseFolderPath, setBaseFolderPath] = useState<string | null>(null);
  const [baseFolderReady, setBaseFolderReady] = useState(false);

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

  // Resolve the document's parent folder path (for relative image resolution).
  useEffect(() => {
    let cancelled = false;
    const resolveBaseFolder = async () => {
      setBaseFolderReady(false);
      setBaseFolderPath(null);
      if (!repositoryId || !objectId) {
        if (!cancelled) setBaseFolderReady(true);
        return;
      }
      try {
        const parents = await cmisService.getObjectParents(repositoryId, objectId);
        if (!cancelled && parents.length > 0 && parents[0].path) {
          setBaseFolderPath(parents[0].path);
        }
      } catch (err) {
        console.warn('[MarkdownPreview] Failed to resolve parent folder for image resolution:', err);
      } finally {
        if (!cancelled) setBaseFolderReady(true);
      }
    };
    resolveBaseFolder();
    return () => { cancelled = true; };
  }, [repositoryId, objectId, cmisService]);

  const imageContext = useMemo<MarkdownImageContext>(() => ({
    repositoryId,
    baseFolderPath,
    baseFolderReady,
    cmisService,
  }), [repositoryId, baseFolderPath, baseFolderReady, cmisService]);

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
          <ReactMarkdown
            components={{
              img: ({ src, alt, title }) => (
                <MarkdownImage
                  src={typeof src === 'string' ? src : undefined}
                  alt={alt}
                  title={title}
                  ctx={imageContext}
                />
              ),
            }}
          >
            {content}
          </ReactMarkdown>
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
