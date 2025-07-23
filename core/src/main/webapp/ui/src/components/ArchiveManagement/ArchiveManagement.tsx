import React, { useState, useEffect } from 'react';
import { 
  Table, 
  Button, 
  Space, 
  message, 
  Popconfirm,
  Card,
  Tooltip
} from 'antd';
import { 
  InboxOutlined, 
  ReloadOutlined,
  FileOutlined,
  FolderOutlined,
  DownloadOutlined,
  EyeOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';

interface ArchiveManagementProps {
  repositoryId: string;
}

export const ArchiveManagement: React.FC<ArchiveManagementProps> = ({ repositoryId }) => {
  const [archives, setArchives] = useState<CMISObject[]>([]);
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const cmisService = new CMISService();

  useEffect(() => {
    loadArchives();
  }, [repositoryId]);

  const loadArchives = async () => {
    setLoading(true);
    try {
      const archiveList = await cmisService.getArchives(repositoryId);
      setArchives(archiveList);
    } catch (error) {
      message.error('アーカイブの読み込みに失敗しました');
    } finally {
      setLoading(false);
    }
  };

  const handleRestore = async (objectId: string) => {
    try {
      await cmisService.restoreObject(repositoryId, objectId);
      message.success('オブジェクトを復元しました');
      loadArchives();
    } catch (error) {
      message.error('復元に失敗しました');
    }
  };

  const handleDownload = (objectId: string) => {
    const url = cmisService.getDownloadUrl(repositoryId, objectId);
    window.open(url, '_blank');
  };

  const columns = [
    {
      title: 'タイプ',
      dataIndex: 'baseType',
      key: 'type',
      width: 60,
      render: (baseType: string) => (
        baseType === 'cmis:folder' ? 
          <FolderOutlined style={{ color: '#1890ff', fontSize: '16px' }} /> :
          <FileOutlined style={{ color: '#52c41a', fontSize: '16px' }} />
      ),
    },
    {
      title: '名前',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: 'オリジナルパス',
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
    },
    {
      title: 'アーカイブ日時',
      dataIndex: 'lastModificationDate',
      key: 'archived',
      width: 180,
      render: (date: string) => date ? new Date(date).toLocaleString('ja-JP') : '-',
    },
    {
      title: 'サイズ',
      dataIndex: 'contentStreamLength',
      key: 'size',
      width: 100,
      render: (size: number) => size ? `${Math.round(size / 1024)} KB` : '-',
    },
    {
      title: 'アクション',
      key: 'actions',
      width: 150,
      render: (_, record: CMISObject) => (
        <Space>
          <Tooltip title="詳細表示">
            <Button 
              icon={<EyeOutlined />} 
              size="small"
              onClick={() => navigate(`/documents/${record.id}`)}
            />
          </Tooltip>
          {record.baseType === 'cmis:document' && (
            <Tooltip title="ダウンロード">
              <Button 
                icon={<DownloadOutlined />} 
                size="small"
                onClick={() => handleDownload(record.id)}
              />
            </Tooltip>
          )}
          <Popconfirm
            title="このオブジェクトを復元しますか？"
            onConfirm={() => handleRestore(record.id)}
            okText="はい"
            cancelText="いいえ"
          >
            <Tooltip title="復元">
              <Button 
                icon={<ReloadOutlined />} 
                size="small"
                type="primary"
              >
                復元
              </Button>
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <InboxOutlined /> アーカイブ管理
        </h2>
      </div>

      <Table
        columns={columns}
        dataSource={archives}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 20 }}
      />
    </Card>
  );
};
