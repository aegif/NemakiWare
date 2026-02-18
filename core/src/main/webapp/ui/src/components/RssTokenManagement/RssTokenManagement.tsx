import React, { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Modal,
  Form,
  Input,
  InputNumber,
  Checkbox,
  Popconfirm,
  Tag,
  message,
  Typography,
  Tooltip,
  Divider,
} from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined, ReloadOutlined, FolderOutlined, LinkOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { ObjectPicker } from '../ObjectPicker/ObjectPicker';
import type { CMISObject } from '../../types/cmis';

const { Paragraph, Text } = Typography;

interface RssTokenManagementProps {
  repositoryId: string;
}

interface RssTokenData {
  id: string;
  name?: string;
  token?: string;
  enabled: boolean;
  createdAt?: string;
  expiresAt?: string;
  expired?: boolean;
}

interface TokenFormValues {
  name: string;
  userId: string;
  events: string[];
  expiryDays: number;
}

interface SelectedFolder {
  id: string;
  name: string;
}

const RSS_EVENTS = ['created', 'updated', 'deleted', 'security'];

export const RssTokenManagement: React.FC<RssTokenManagementProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const { authToken } = useAuth();
  const [tokens, setTokens] = useState<RssTokenData[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [newToken, setNewToken] = useState<string | null>(null);
  const [newTokenFolders, setNewTokenFolders] = useState<SelectedFolder[]>([]);
  const [selectedFolders, setSelectedFolders] = useState<SelectedFolder[]>([]);
  const [folderPickerVisible, setFolderPickerVisible] = useState(false);
  const [form] = Form.useForm<TokenFormValues>();

  const getHeaders = useCallback(() => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (authToken?.token) {
      headers['AUTH_TOKEN'] = authToken.token;
    }
    return headers;
  }, [authToken]);

  const loadTokens = useCallback(async () => {
    if (!authToken?.username) return;
    setLoading(true);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/rss/tokens?userId=${encodeURIComponent(authToken.username)}`,
        { headers: getHeaders() }
      );
      const data = await response.json();
      if (data.status === 'success') {
        setTokens(data.tokens || []);
      } else {
        message.error(t('rssManagement.loadError'));
      }
    } catch {
      message.error(t('rssManagement.loadError'));
    } finally {
      setLoading(false);
    }
  }, [repositoryId, authToken, getHeaders, t]);

  useEffect(() => {
    loadTokens();
  }, [loadTokens]);

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();

      const body: Record<string, unknown> = {
        userId: values.userId || authToken?.username,
        name: values.name,
        expiryDays: values.expiryDays,
      };

      if (selectedFolders.length > 0) {
        body.folders = selectedFolders.map(f => f.id);
      }

      if (values.events && values.events.length > 0) {
        body.events = values.events;
      }

      const response = await fetch(
        `/core/rest/repo/${repositoryId}/rss/tokens`,
        {
          method: 'POST',
          headers: getHeaders(),
          body: JSON.stringify(body),
        }
      );
      const data = await response.json();
      if (data.status === 'success') {
        message.success(t('rssManagement.createSuccess'));
        if (data.token) {
          setNewToken(data.token);
          setNewTokenFolders([...selectedFolders]);
        }
        setModalVisible(false);
        loadTokens();
      } else {
        message.error(t('rssManagement.createError'));
      }
    } catch {
      // Form validation error
    }
  };

  const handleDisable = async (tokenId: string) => {
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/rss/tokens/${tokenId}/disable`,
        { method: 'PUT', headers: getHeaders() }
      );
      const data = await response.json();
      if (data.status === 'success') {
        loadTokens();
      }
    } catch {
      message.error(t('rssManagement.loadError'));
    }
  };

  const handleRefresh = async (tokenId: string) => {
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/rss/tokens/${tokenId}/refresh`,
        { method: 'PUT', headers: getHeaders() }
      );
      const data = await response.json();
      if (data.status === 'success') {
        message.success(t('rssManagement.refreshSuccess'));
        loadTokens();
      }
    } catch {
      message.error(t('rssManagement.loadError'));
    }
  };

  const handleDelete = async (tokenId: string) => {
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/rss/tokens/${tokenId}`,
        { method: 'DELETE', headers: getHeaders() }
      );
      const data = await response.json();
      if (data.status === 'success') {
        message.success(t('rssManagement.deleteSuccess'));
        loadTokens();
      } else {
        message.error(t('rssManagement.deleteError'));
      }
    } catch {
      message.error(t('rssManagement.deleteError'));
    }
  };

  const copyToClipboard = (text: string, msgKey?: string) => {
    navigator.clipboard.writeText(text);
    message.success(t(msgKey || 'rssManagement.tokenCopied'));
  };

  const handleFolderSelect = (obj: CMISObject) => {
    if (selectedFolders.some(f => f.id === obj.id)) {
      message.warning(t('rssManagement.folderAlreadySelected'));
    } else {
      setSelectedFolders(prev => [...prev, { id: obj.id, name: obj.name }]);
    }
    setFolderPickerVisible(false);
  };

  const handleFolderRemove = (folderId: string) => {
    setSelectedFolders(prev => prev.filter(f => f.id !== folderId));
  };

  const buildFeedUrl = (folderId: string, token: string, format: 'rss' | 'atom') => {
    const base = window.location.origin;
    return `${base}/core/rest/repo/${repositoryId}/rss/folder/${folderId}?token=${token}&format=${format}`;
  };

  const columns = [
    {
      title: t('rssManagement.name'),
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
    },
    {
      title: t('rssManagement.status'),
      key: 'status',
      width: 100,
      render: (_: unknown, record: RssTokenData) => {
        if (record.expired) {
          return <Tag color="red">{t('rssManagement.expired')}</Tag>;
        }
        return record.enabled
          ? <Tag color="green">{t('rssManagement.active')}</Tag>
          : <Tag color="default">{t('rssManagement.disabled')}</Tag>;
      },
    },
    {
      title: t('rssManagement.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
    },
    {
      title: t('rssManagement.expiresAt'),
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 180,
    },
    {
      title: t('rssManagement.actions'),
      key: 'actions',
      width: 200,
      render: (_: unknown, record: RssTokenData) => (
        <Space>
          {record.enabled && !record.expired && (
            <Tooltip title={t('rssManagement.refresh')}>
              <Button
                type="text"
                icon={<ReloadOutlined />}
                onClick={() => handleRefresh(record.id)}
                size="small"
              />
            </Tooltip>
          )}
          {record.enabled && (
            <Button
              type="text"
              size="small"
              onClick={() => handleDisable(record.id)}
            >
              {t('rssManagement.disable')}
            </Button>
          )}
          <Popconfirm
            title={t('rssManagement.deleteConfirm')}
            onConfirm={() => handleDelete(record.id)}
          >
            <Button type="text" danger icon={<DeleteOutlined />} size="small" />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('rssManagement.title')}>
      <Paragraph type="secondary" style={{ marginBottom: 16 }}>
        {t('rssManagement.description')}
      </Paragraph>

      <div style={{ marginBottom: 16 }}>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            form.resetFields();
            form.setFieldsValue({
              userId: authToken?.username || '',
              expiryDays: 30,
              events: ['created', 'updated', 'deleted'],
            });
            setSelectedFolders([]);
            setModalVisible(true);
          }}
        >
          {t('rssManagement.createToken')}
        </Button>
      </div>

      <Table
        columns={columns}
        dataSource={tokens}
        rowKey="id"
        size="small"
        loading={loading}
        pagination={false}
        locale={{ emptyText: t('rssManagement.noTokens') }}
      />

      {/* Create Token Modal */}
      <Modal
        title={t('rssManagement.createToken')}
        open={modalVisible}
        onOk={handleCreate}
        onCancel={() => setModalVisible(false)}
        width={520}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label={t('rssManagement.name')}
            rules={[{ required: true }]}
          >
            <Input placeholder={t('rssManagement.namePlaceholder')} />
          </Form.Item>

          <Form.Item name="userId" label="User ID" hidden>
            <Input />
          </Form.Item>

          <Form.Item label={t('rssManagement.selectedFolders')}>
            <div style={{ marginBottom: 8 }}>
              {selectedFolders.length === 0 ? (
                <Text type="secondary">{t('rssManagement.noFolderSelected')}</Text>
              ) : (
                <Space wrap>
                  {selectedFolders.map(folder => (
                    <Tag
                      key={folder.id}
                      closable
                      onClose={() => handleFolderRemove(folder.id)}
                      icon={<FolderOutlined />}
                    >
                      {folder.name}
                    </Tag>
                  ))}
                </Space>
              )}
            </div>
            <Button
              icon={<PlusOutlined />}
              onClick={() => setFolderPickerVisible(true)}
              size="small"
            >
              {t('rssManagement.selectFolder')}
            </Button>
          </Form.Item>

          <Form.Item
            name="events"
            label={t('rssManagement.events')}
          >
            <Checkbox.Group>
              <Space wrap>
                {RSS_EVENTS.map((e) => (
                  <Checkbox key={e} value={e}>{e}</Checkbox>
                ))}
              </Space>
            </Checkbox.Group>
          </Form.Item>

          <Form.Item
            name="expiryDays"
            label={t('rssManagement.expiryDays')}
          >
            <InputNumber min={1} max={365} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Folder Picker */}
      <ObjectPicker
        repositoryId={repositoryId}
        visible={folderPickerVisible}
        onSelect={handleFolderSelect}
        onCancel={() => setFolderPickerVisible(false)}
        title={t('rssManagement.selectFolder')}
        filterType="folder"
      />

      {/* Token Display Modal */}
      <Modal
        title={t('rssManagement.token')}
        open={!!newToken}
        onOk={() => { setNewToken(null); setNewTokenFolders([]); }}
        onCancel={() => { setNewToken(null); setNewTokenFolders([]); }}
        width={640}
        footer={[
          <Button key="copy" icon={<CopyOutlined />} onClick={() => newToken && copyToClipboard(newToken)}>
            {t('rssManagement.copyFeedUrl')}
          </Button>,
          <Button key="ok" type="primary" onClick={() => { setNewToken(null); setNewTokenFolders([]); }}>
            OK
          </Button>,
        ]}
      >
        <Paragraph>
          {t('rssManagement.token')}:
        </Paragraph>
        <Paragraph code copyable style={{ wordBreak: 'break-all' }}>
          {newToken}
        </Paragraph>

        {newToken && newTokenFolders.length > 0 && (
          <>
            <Divider />
            <Paragraph strong>
              <LinkOutlined style={{ marginRight: 4 }} />
              {t('rssManagement.feedUrls')}
            </Paragraph>
            {newTokenFolders.map(folder => (
              <div key={folder.id} style={{ marginBottom: 12 }}>
                <Text strong>
                  <FolderOutlined style={{ marginRight: 4 }} />
                  {folder.name}
                </Text>
                <div style={{ marginLeft: 20, marginTop: 4 }}>
                  <div style={{ marginBottom: 4 }}>
                    <Text type="secondary">{t('rssManagement.rssFeedUrl')}: </Text>
                    <Text code copyable style={{ wordBreak: 'break-all', fontSize: 12 }}>
                      {buildFeedUrl(folder.id, newToken, 'rss')}
                    </Text>
                  </div>
                  <div>
                    <Text type="secondary">{t('rssManagement.atomFeedUrl')}: </Text>
                    <Text code copyable style={{ wordBreak: 'break-all', fontSize: 12 }}>
                      {buildFeedUrl(folder.id, newToken, 'atom')}
                    </Text>
                  </div>
                </div>
              </div>
            ))}
          </>
        )}
      </Modal>
    </Card>
  );
};
