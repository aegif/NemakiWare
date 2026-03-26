import React, { useState } from 'react';
import {
  Input,
  Card,
  Descriptions,
  Table,
  Typography,
  Alert,
  Spin,
  Space,
  Tag
} from 'antd';
import {
  FileOutlined,
  FolderOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject } from '../../types/cmis';
import { useAuth } from '../../contexts/AuthContext';

interface ObjectIdSearchProps {
  repositoryId: string;
  onDocumentClick?: (objectId: string) => void;
}

export const ObjectIdSearch: React.FC<ObjectIdSearchProps> = ({
  repositoryId,
  onDocumentClick
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  const [loading, setLoading] = useState(false);
  const [targetObject, setTargetObject] = useState<CMISObject | null>(null);
  const [parents, setParents] = useState<CMISObject[]>([]);
  const [children, setChildren] = useState<CMISObject[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);

  const handleSearch = async (value: string) => {
    const objectId = value.trim();
    if (!objectId) return;

    setLoading(true);
    setError(null);
    setTargetObject(null);
    setParents([]);
    setChildren([]);
    setSearched(true);

    try {
      const obj = await cmisService.getObject(repositoryId, objectId);
      setTargetObject(obj);

      // Fetch parents
      try {
        const parentList = await cmisService.getObjectParents(repositoryId, objectId);
        setParents(parentList);
      } catch {
        // Parents may not be available for root folder
      }

      // Fetch children if folder
      if (obj.baseType === 'cmis:folder') {
        try {
          const childList = await cmisService.getChildren(repositoryId, objectId);
          setChildren(childList);
        } catch {
          // Children fetch may fail
        }
      }
    } catch (err: any) {
      if (err.status === 404 || err.message?.includes('404')) {
        setError(t('search.objectIdSearchNotFound'));
      } else {
        setError(err.message || String(err));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleObjectClick = (objectId: string) => {
    if (onDocumentClick) {
      onDocumentClick(objectId);
    } else {
      navigate(`/documents/${objectId}`);
    }
  };

  const childColumns = [
    {
      title: '',
      dataIndex: 'baseType',
      key: 'icon',
      width: 40,
      render: (baseType: string) =>
        baseType === 'cmis:folder' ? <FolderOutlined /> : <FileOutlined />
    },
    {
      title: t('search.columns.name'),
      dataIndex: 'name',
      key: 'name',
      render: (name: string, record: CMISObject) => (
        <a onClick={() => handleObjectClick(record.id)}>{name}</a>
      )
    },
    {
      title: t('search.columns.objectType'),
      dataIndex: 'objectType',
      key: 'objectType',
      width: 200
    },
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 300,
      render: (id: string) => (
        <Typography.Text copyable code style={{ fontSize: 12 }}>
          {id}
        </Typography.Text>
      )
    }
  ];

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
          {t('search.objectIdSearchDescription')}
        </Typography.Paragraph>
        <Input.Search
          placeholder={t('search.objectIdSearchPlaceholder')}
          enterButton
          onSearch={handleSearch}
          loading={loading}
          allowClear
          style={{ maxWidth: 600 }}
        />
      </Card>

      {loading && (
        <div style={{ textAlign: 'center', padding: 40 }}>
          <Spin size="large" />
        </div>
      )}

      {error && searched && !loading && (
        <Alert type="warning" message={error} showIcon style={{ marginBottom: 16 }} />
      )}

      {targetObject && !loading && (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Card title={t('search.objectIdSearchDirectMatch')} size="small">
            <Descriptions column={{ xs: 1, sm: 2 }} bordered size="small">
              <Descriptions.Item label={t('search.columns.name')}>
                <a onClick={() => handleObjectClick(targetObject.id)}>
                  {targetObject.baseType === 'cmis:folder'
                    ? <><FolderOutlined style={{ marginRight: 4 }} />{targetObject.name}</>
                    : <><FileOutlined style={{ marginRight: 4 }} />{targetObject.name}</>
                  }
                </a>
              </Descriptions.Item>
              <Descriptions.Item label={t('search.columns.objectType')}>
                <Tag>{targetObject.objectType}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="ID">
                <Typography.Text copyable code style={{ fontSize: 12 }}>
                  {targetObject.id}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('search.columns.path')}>
                {targetObject.path || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('search.columns.createdAt')}>
                {targetObject.creationDate
                  ? new Date(targetObject.creationDate).toLocaleString()
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('search.columns.createdBy')}>
                {targetObject.createdBy || '-'}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          {parents.length > 0 && (
            <Card title={t('search.objectIdSearchParent')} size="small">
              {parents.map((parent) => (
                <div key={parent.id} style={{ marginBottom: 4 }}>
                  <FolderOutlined style={{ marginRight: 8 }} />
                  <a onClick={() => handleObjectClick(parent.id)}>
                    {parent.name}
                  </a>
                  <Typography.Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                    ({parent.id})
                  </Typography.Text>
                </div>
              ))}
            </Card>
          )}

          {targetObject.baseType === 'cmis:folder' && (
            <Card
              title={`${t('search.objectIdSearchChildren')} (${children.length})`}
              size="small"
            >
              <Table
                dataSource={children}
                columns={childColumns}
                rowKey="id"
                size="small"
                pagination={{ pageSize: 20, hideOnSinglePage: true }}
              />
            </Card>
          )}
        </Space>
      )}
    </div>
  );
};
