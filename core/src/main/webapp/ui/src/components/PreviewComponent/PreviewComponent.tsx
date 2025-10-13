import React from 'react';
import { Alert, Card } from 'antd';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';
import { getFileType } from '../../utils/previewUtils';
import { ImagePreview } from './ImagePreview';
import { VideoPreview } from './VideoPreview';
import { PDFPreview } from './PDFPreview';
import { TextPreview } from './TextPreview';
import { OfficePreview } from './OfficePreview';

interface PreviewComponentProps {
  repositoryId: string;
  object: CMISObject;
}

import { useAuth } from '../../contexts/AuthContext';
export const PreviewComponent: React.FC<PreviewComponentProps> = ({ repositoryId, object }) => {
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  if (!object.contentStreamMimeType) {
    return <Alert message="プレビューできません" description="ファイルにコンテンツがありません" type="info" />;
  }

  const fileType = getFileType(object.contentStreamMimeType);
  const contentUrl = cmisService.getDownloadUrl(repositoryId, object.id);

  const renderPreview = () => {
    try {
      switch (fileType) {
        case 'image':
          return <ImagePreview url={contentUrl} fileName={object.name} />;
        case 'video':
          return <VideoPreview url={contentUrl} fileName={object.name} />;
        case 'pdf':
          return <PDFPreview url={contentUrl} fileName={object.name} />;
        case 'text':
          return <TextPreview url={contentUrl} fileName={object.name} />;
        case 'office':
          return <OfficePreview url={contentUrl} fileName={object.name} mimeType={object.contentStreamMimeType!} />;
        default:
          return <Alert message="プレビューできません" description={`${object.contentStreamMimeType} はサポートされていません`} type="warning" />;
      }
    } catch (err) {
      return <Alert message="プレビューエラー" description="プレビューの表示中にエラーが発生しました" type="error" />;
    }
  };

  return (
    <Card>
      {renderPreview()}
    </Card>
  );
};
