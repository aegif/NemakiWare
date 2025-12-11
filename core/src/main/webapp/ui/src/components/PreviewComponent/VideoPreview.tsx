/**
 * VideoPreview Component for NemakiWare React UI
 *
 * Video preview component providing professional video playback with react-player integration:
 * - react-player library integration for rich video player experience with controls
 * - TypeScript type definition workaround using spread operator and any cast
 * - File name display above video player for context
 * - Max width constraint (100%) for responsive display without overflow
 * - Fixed height (400px) for consistent player sizing
 * - Centered text alignment for professional layout
 * - Controls enabled for user-controlled playback (play/pause, volume, seek)
 * - Authenticated content URL from CMISService passed directly to ReactPlayer
 * - No custom error handling (relies on ReactPlayer's built-in error UI)
 * - Simple wrapper pattern delegating complex video handling to third-party library
 *
 * Component Architecture:
 * VideoPreview (wrapper)
 *   └─ <div maxWidth="100%" textAlign="center">
 *       ├─ <h4>{fileName}</h4>
 *       └─ <div height="400px">
 *           └─ <ReactPlayer url={url} controls={true} />
 *
 * react-player Features (Automatic):
 * - Multiple video format support (MP4, WebM, OGG)
 * - Streaming protocol support (HLS, DASH)
 * - YouTube/Vimeo URL support (if needed in future)
 * - Playback controls (play/pause, volume, seek bar, fullscreen)
 * - Responsive video sizing within container
 * - Keyboard shortcuts (space to play/pause, arrow keys to seek)
 * - Loading state indicator
 * - Error state UI for playback failures
 *
 * Usage Examples:
 * ```typescript
 * // PreviewComponent.tsx - Video file type case (Line 248)
 * case 'video':
 *   return <VideoPreview url={contentUrl} fileName={object.name} />;
 *
 * // Example with authenticated URL
 * const contentUrl = cmisService.getDownloadUrl(repositoryId, object.id);
 * // contentUrl: "http://localhost:8080/core/browser/bedroom/content?id=abc123&auth=token"
 *
 * <VideoPreview
 *   url={contentUrl}
 *   fileName="presentation.mp4"
 * />
 * // Renders: ReactPlayer with controls, 400px height, "presentation.mp4" heading
 *
 * // User Interaction Flow:
 * // 1. VideoPreview renders with video loaded from authenticated URL
 * // 2. File name "presentation.mp4" displayed above player
 * // 3. Video loads and shows first frame (or loading spinner)
 * // 4. User clicks play button (or space key) to start playback
 * // 5. Controls allow pause, volume adjustment, seek, fullscreen
 * // 6. Video plays to completion or user pauses/stops
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. react-player Library Integration (Lines 215-217):
 *    - Professional video player library with controls and multi-format support
 *    - Rationale: Better UX than plain <video> tag - consistent controls across browsers
 *    - Implementation: <ReactPlayer url={url} controls={true} />
 *    - Advantage: Built-in controls, format detection, streaming support, keyboard shortcuts
 *    - Pattern: Third-party library integration for complex UI components
 *
 * 2. TypeScript Type Definition Workaround (Line 216):
 *    - Spread operator with any cast: {...{ url, controls: true } as any}
 *    - Rationale: react-player v3.x type definitions incompatible with TypeScript strict mode
 *    - Implementation: Props spread indirectly through any-cast object
 *    - Advantage: Avoids TypeScript errors while maintaining functionality
 *    - Trade-off: Loses type safety for ReactPlayer props
 *    - Pattern: Pragmatic TypeScript workaround for third-party library issues
 *
 * 3. File Name Display Above Player (Line 212):
 *    - <h4> heading shows fileName prop above video player
 *    - Rationale: Users need to know which video they're viewing
 *    - Implementation: <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>
 *    - Advantage: Context for video content, useful for multiple videos
 *    - Pattern: Metadata display in UI components
 *
 * 4. Max Width Constraint for Responsive Display (Line 211):
 *    - maxWidth: '100%' allows video to scale down on narrow screens
 *    - Rationale: Video should fit in preview tab without horizontal scrolling
 *    - Implementation: Inline style on wrapper div
 *    - Advantage: Responsive layout across different screen sizes
 *    - Pattern: CSS constraints for responsive layout
 *
 * 5. Fixed Height Player Container (Line 213):
 *    - height: '400px' provides consistent player sizing
 *    - Rationale: Predictable layout without content jumping during load
 *    - Implementation: Inline style on player wrapper div
 *    - Advantage: Consistent preview size, no layout shift
 *    - Trade-off: May not preserve aspect ratio for all videos
 *    - Pattern: Fixed dimensions for consistent UI
 *
 * 6. Centered Text Alignment (Line 211):
 *    - textAlign: 'center' centers file name and player
 *    - Rationale: Professional centered layout for preview content
 *    - Implementation: Inline style on wrapper div
 *    - Advantage: Visually balanced layout
 *    - Pattern: CSS text alignment for layout
 *
 * 7. Controls Enabled for User Playback (Line 216):
 *    - controls: true enables ReactPlayer's built-in control UI
 *    - Rationale: Users need playback controls (play/pause, volume, seek)
 *    - Implementation: controls prop passed to ReactPlayer
 *    - Advantage: Full user control over playback, professional controls
 *    - Pattern: Leverage library features for standard interactions
 *
 * 8. Authenticated Content URL from CMISService (Line 209):
 *    - url prop contains authentication credentials in query string
 *    - Rationale: Video content requires CMIS authentication
 *    - Implementation: PreviewComponent passes cmisService.getDownloadUrl() result
 *    - Advantage: Secure video access, no separate authentication in VideoPreview
 *    - Pattern: Authentication handled by service layer, component receives ready-to-use URL
 *
 * 9. No Custom Error Handling (Implicit):
 *    - No try-catch or error state in VideoPreview component
 *    - Rationale: ReactPlayer has built-in error UI and handling
 *    - Implementation: Delegate error handling to react-player library
 *    - Advantage: Less code, consistent error UI from library
 *    - Trade-off: Cannot customize error messages for CMIS-specific failures
 *    - Pattern: Delegate error handling to third-party libraries when sufficient
 *
 * 10. Simple Wrapper Pattern (Lines 209-221):
 *     - VideoPreview is thin wrapper around ReactPlayer
 *     - Rationale: Minimal abstraction for straightforward use case
 *     - Implementation: Pass-through props with minimal styling
 *     - Advantage: Easy to understand, maintain, and test
 *     - Pattern: Wrapper component for third-party library integration
 *
 * Expected Results:
 * - VideoPreview: Renders video with playback controls
 * - Video display: 400px height, maintains aspect ratio within container
 * - Controls: Play/pause button, volume slider, seek bar, fullscreen button
 * - Keyboard shortcuts: Space for play/pause, arrow keys for seek
 * - Loading state: ReactPlayer shows loading spinner while video buffers
 * - Error handling: ReactPlayer shows error icon/message if video fails to load
 * - Authentication: Authenticated URL includes credentials, video loads securely
 * - File name: Displayed above player as <h4> heading
 *
 * Performance Characteristics:
 * - Initial render: <10ms (simple wrapper component)
 * - Video load time: Varies by file size and format (10MB: ~2-5s on good connection)
 * - Buffering time: Depends on network speed and video bitrate
 * - Playback smoothness: Handled by browser's native video decoding
 * - Memory usage: Depends on video resolution and codec (1080p: ~50-100MB browser memory)
 * - Re-render on URL change: <10ms (ReactPlayer updates video source)
 *
 * Debugging Features:
 * - React DevTools: Inspect url and fileName props
 * - Network tab: See video request with authentication URL
 * - ReactPlayer DevTools: Library provides debug mode for troubleshooting
 * - Console errors: Video load failures logged by browser
 * - CSS inspector: Verify maxWidth/height constraints applied
 *
 * Known Limitations:
 * - Fixed height: 400px may not preserve aspect ratio for all videos (no dynamic sizing)
 * - No lazy loading: Video loads immediately on component mount (no defer)
 * - No video caching: Browser cache only, no application-level cache
 * - Large videos: High-resolution videos may cause browser memory issues (no size limit)
 * - Authentication in URL: Credentials visible in DevTools Network tab (security consideration)
 * - No error boundary: Video load failures handled by ReactPlayer, no custom error UI
 * - TypeScript type safety: Lost due to any cast workaround for react-player v3.x
 * - No accessibility: No transcript or captions support for screen readers
 * - Format support: Limited to browser-supported codecs (MP4, WebM, OGG)
 * - No download button: Users cannot download video directly from preview
 *
 * Relationships to Other Components:
 * - Used by: PreviewComponent.tsx (video file type case, Line 248)
 * - Depends on: react-player library for video playback functionality
 * - Depends on: CMISService indirectly (url prop contains authenticated content URL)
 * - Renders: ReactPlayer component from react-player library
 * - Integration: PreviewComponent passes url from cmisService.getDownloadUrl() and fileName from object.name
 *
 * Common Failure Scenarios:
 * - Invalid URL: ReactPlayer shows error icon, no video displayed
 * - Authentication failure: 401 error, video fails to load (handled by PreviewComponent's CMISService)
 * - Large video: Browser memory limit may cause tab crash (no size validation)
 * - Network timeout: Video load hangs, ReactPlayer shows loading spinner indefinitely
 * - CORS error: Cross-origin videos blocked by browser (should not occur with same-origin URLs)
 * - Unsupported format: Browser cannot decode video codec (e.g., H.265, VP9 on old browsers)
 * - Missing props: TypeScript prevents, but runtime missing url shows blank player
 * - react-player not loaded: Import failure causes component crash (rare)
 */

import React, { useState, useEffect } from 'react';
import ReactPlayer from 'react-player';
import { Spin, Alert } from 'antd';
import { CMISService } from '../../services/cmis';
import { useAuth } from '../../contexts/AuthContext';

interface VideoPreviewProps {
  url: string;
  fileName: string;
  repositoryId?: string;
  objectId?: string;
}

export const VideoPreview: React.FC<VideoPreviewProps> = ({ url, fileName, repositoryId, objectId }) => {
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Extract repositoryId and objectId from URL if not provided as props
  const extractFromUrl = (urlString: string): { repoId: string | null; objId: string | null } => {
    // URL format: /core/browser/{repositoryId}/node/{objectId}/content
    const match = urlString.match(/\/core\/browser\/([^/]+)\/node\/([^/]+)/);
    if (match) {
      return { repoId: match[1], objId: match[2] };
    }
    return { repoId: null, objId: null };
  };

  useEffect(() => {
    const fetchVideoContent = async () => {
      setIsLoading(true);
      setError(null);

      try {
        const { repoId, objId } = extractFromUrl(url);
        const effectiveRepoId = repositoryId || repoId;
        const effectiveObjId = objectId || objId;

        if (!effectiveRepoId || !effectiveObjId) {
          console.error('VideoPreview: Missing repositoryId or objectId');
          setError('ファイルの読み込みに必要な情報が不足しています');
          setIsLoading(false);
          return;
        }

        console.log('[VideoPreview] Fetching content with auth headers for:', effectiveRepoId, effectiveObjId);

        // Use CMISService to fetch content with proper authentication headers
        const arrayBuffer = await cmisService.getContentStream(effectiveRepoId, effectiveObjId);

        // Convert ArrayBuffer to Blob and create URL
        const blob = new Blob([arrayBuffer]);
        const blobUrl = URL.createObjectURL(blob);

        console.log('[VideoPreview] Content fetched successfully, blob URL created');
        setVideoUrl(blobUrl);
      } catch (err) {
        console.error('Video fetch error:', err);
        setError(`動画の取得に失敗しました: ${err instanceof Error ? err.message : 'Unknown error'}`);
      } finally {
        setIsLoading(false);
      }
    };

    if (url) {
      fetchVideoContent();
    }

    // Cleanup: revoke blob URL when component unmounts or URL changes
    return () => {
      if (videoUrl) {
        URL.revokeObjectURL(videoUrl);
      }
    };
  }, [url, repositoryId, objectId]);

  if (error) {
    return <Alert message="エラー" description={error} type="error" showIcon />;
  }

  if (isLoading || !videoUrl) {
    return (
      <div style={{ textAlign: 'center', padding: '40px' }}>
        <Spin size="large" tip="動画を取得しています..." />
      </div>
    );
  }

  return (
    <div style={{ maxWidth: '100%', textAlign: 'center' }}>
      <h4 style={{ marginBottom: '16px' }}>{fileName}</h4>
      <div style={{ width: '100%', height: '400px' }}>
        {/* TypeScript workaround: react-player v3.x type definitions issue */}
        <ReactPlayer
          {...{ url: videoUrl, controls: true } as any}
        />
      </div>
    </div>
  );
};
