import React from 'react';
import { Alert, Button, Space } from 'antd';
import { DownloadOutlined, FileTextOutlined } from '@ant-design/icons';

interface OfficePreviewProps {
  url: string;
  fileName: string;
  mimeType: string;
}

export const OfficePreview: React.FC<OfficePreviewProps> = ({ url, fileName, mimeType }) => {
  const getFileTypeDescription = (mimeType: string) => {
    if (mimeType.includes('wordprocessingml')) return 'Word文書';
    if (mimeType.includes('spreadsheetml')) return 'Excel文書';
    if (mimeType.includes('presentationml')) return 'PowerPoint文書';
    if (mimeType.includes('opendocument.text')) return 'OpenDocument テキスト';
    if (mimeType.includes('opendocument.spreadsheet')) return 'OpenDocument スプレッドシート';
    if (mimeType.includes('opendocument.presentation')) return 'OpenDocument プレゼンテーション';
    return 'オフィス文書';
  };

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
            <p>オフィス文書のプレビューは現在サポートされていません。<br />ダウンロードしてローカルアプリケーションでご確認ください。</p>
            <Button 
              type="primary"
              icon={<DownloadOutlined />} 
              onClick={() => window.open(url, '_blank')}
              size="large"
            >
              ダウンロード
            </Button>
          </Space>
        }
        type="info"
        showIcon={false}
      />
    </div>
  );
};
