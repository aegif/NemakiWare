/**
 * ApiKeyManagement Component
 *
 * Manages API keys for programmatic access to NemakiWare.
 * API keys are useful for:
 * - MCP (Model Context Protocol) clients like Claude Desktop
 * - Cloud-only users who cannot use password authentication
 * - Programmatic access without session management
 */

import React, { useState, useEffect } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Modal,
  Form,
  Input,
  message,
  Tag,
  Popconfirm,
  Alert,
  Typography,
  Empty,
  Tooltip,
  DatePicker,
  Select
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import {
  KeyOutlined,
  PlusOutlined,
  DeleteOutlined,
  CopyOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { listApiKeys, createApiKey, revokeApiKey, ApiKey, ApiKeyCreationResult } from '../../services/apiKey';

const { Text, Paragraph } = Typography;

interface ApiKeyManagementProps {
  repositoryId: string;
  userId?: string; // If not provided, uses current user
}

export const ApiKeyManagement: React.FC<ApiKeyManagementProps> = ({
  repositoryId,
  userId
}) => {
  const { t } = useTranslation();
  const { authToken } = useAuth();
  const [apiKeys, setApiKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [createdKeyResult, setCreatedKeyResult] = useState<ApiKeyCreationResult | null>(null);
  const [form] = Form.useForm();

  const targetUserId = userId || authToken?.username;

  useEffect(() => {
    if (targetUserId) {
      loadApiKeys();
    }
  }, [repositoryId, targetUserId]);

  const loadApiKeys = async () => {
    if (!targetUserId) return;

    setLoading(true);
    try {
      const keys = await listApiKeys(repositoryId, targetUserId);
      setApiKeys(keys);
    } catch (error: any) {
      message.error(t('apiKeyManagement.messages.loadError'));
    } finally {
      setLoading(false);
    }
  };

  const handleCreateKey = async (values: { name: string; description?: string; expiresAt?: Dayjs; expirationPreset?: string }) => {
    console.log('handleCreateKey called with values:', values);

    if (!targetUserId) {
      console.error('No targetUserId available');
      message.error('User ID not available');
      return;
    }

    try {
      // Calculate expiration date
      let expiresAtMs: number | undefined = undefined;

      if (values.expirationPreset && values.expirationPreset !== 'never') {
        const now = dayjs();
        switch (values.expirationPreset) {
          case '7days':
            expiresAtMs = now.add(7, 'day').valueOf();
            break;
          case '30days':
            expiresAtMs = now.add(30, 'day').valueOf();
            break;
          case '90days':
            expiresAtMs = now.add(90, 'day').valueOf();
            break;
          case '1year':
            expiresAtMs = now.add(1, 'year').valueOf();
            break;
          case 'custom':
            if (values.expiresAt) {
              expiresAtMs = values.expiresAt.valueOf();
            }
            break;
        }
      }

      console.log('Creating API key:', { repositoryId, targetUserId, name: values.name, expiresAtMs });

      const result = await createApiKey(
        repositoryId,
        targetUserId,
        values.name,
        values.description,
        expiresAtMs
      );
      console.log('API key created successfully:', result);
      setCreatedKeyResult(result);
      message.success(t('apiKeyManagement.messages.createSuccess'));
      loadApiKeys();
      form.resetFields();
      setExpirationPreset('never');
    } catch (error: any) {
      console.error('Failed to create API key:', error);
      message.error(t('apiKeyManagement.messages.createError') + ': ' + (error.message || 'Unknown error'));
    }
  };

  const handleRevokeKey = async (keyId: string) => {
    try {
      await revokeApiKey(repositoryId, keyId);
      message.success(t('apiKeyManagement.messages.revokeSuccess'));
      loadApiKeys();
    } catch (error: any) {
      message.error(t('apiKeyManagement.messages.revokeError'));
    }
  };

  const handleCopyKey = async (key: string) => {
    try {
      await navigator.clipboard.writeText(key);
      message.success(t('apiKeyManagement.modal.copySuccess'));
    } catch (error) {
      // Fallback for browsers that don't support clipboard API
      const textArea = document.createElement('textarea');
      textArea.value = key;
      document.body.appendChild(textArea);
      textArea.select();
      document.execCommand('copy');
      document.body.removeChild(textArea);
      message.success(t('apiKeyManagement.modal.copySuccess'));
    }
  };

  const handleCloseCreateModal = () => {
    setCreateModalVisible(false);
    setCreatedKeyResult(null);
    setExpirationPreset('never');
    form.resetFields();
  };

  const formatDate = (timestamp?: number) => {
    if (!timestamp) return t('apiKeyManagement.neverUsed');
    return new Date(timestamp).toLocaleString();
  };

  const formatExpiration = (expiresAt?: number, expired?: boolean) => {
    if (!expiresAt) return t('apiKeyManagement.neverExpires');
    const date = new Date(expiresAt).toLocaleString();
    if (expired) {
      return `${date} (${t('apiKeyManagement.status.expired')})`;
    }
    return date;
  };

  const [expirationPreset, setExpirationPreset] = useState<string>('never');

  const columns = [
    {
      title: t('apiKeyManagement.columns.name'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('apiKeyManagement.columns.keyPrefix'),
      dataIndex: 'keyPrefix',
      key: 'keyPrefix',
      render: (prefix: string) => (
        <Text code>{prefix}...</Text>
      ),
    },
    {
      title: t('apiKeyManagement.columns.created'),
      dataIndex: 'created',
      key: 'created',
      render: (timestamp: number) => formatDate(timestamp),
    },
    {
      title: t('apiKeyManagement.columns.lastUsed'),
      dataIndex: 'lastUsed',
      key: 'lastUsed',
      render: (timestamp: number) => formatDate(timestamp),
    },
    {
      title: t('apiKeyManagement.columns.expiresAt'),
      key: 'expiresAt',
      render: (_: any, record: ApiKey) => formatExpiration(record.expiresAt, record.expired),
    },
    {
      title: t('apiKeyManagement.columns.status'),
      key: 'status',
      render: (_: any, record: ApiKey) => {
        if (record.expired) {
          return <Tag color="orange">{t('apiKeyManagement.status.expired')}</Tag>;
        }
        return (
          <Tag color={record.active ? 'green' : 'red'}>
            {record.active ? t('apiKeyManagement.status.active') : t('apiKeyManagement.status.inactive')}
          </Tag>
        );
      },
    },
    {
      title: t('apiKeyManagement.columns.actions'),
      key: 'actions',
      render: (_: any, record: ApiKey) => (
        <Space>
          <Popconfirm
            title={t('apiKeyManagement.confirmRevoke')}
            onConfirm={() => handleRevokeKey(record.id)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Button
              danger
              icon={<DeleteOutlined />}
              size="small"
            >
              {t('apiKeyManagement.revoke')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <KeyOutlined /> {t('apiKeyManagement.title')}
        </h2>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setCreateModalVisible(true)}
        >
          {t('apiKeyManagement.createKey')}
        </Button>
      </div>

      <Paragraph type="secondary" style={{ marginBottom: 16 }}>
        {t('apiKeyManagement.description')}
      </Paragraph>

      {apiKeys.length === 0 && !loading ? (
        <Empty
          description={
            <span>
              <div>{t('apiKeyManagement.noKeys')}</div>
              <Text type="secondary">{t('apiKeyManagement.noKeysDescription')}</Text>
            </span>
          }
        />
      ) : (
        <Table
          columns={columns}
          dataSource={apiKeys}
          rowKey="id"
          loading={loading}
          pagination={false}
        />
      )}

      {/* Create API Key Modal */}
      <Modal
        title={t('apiKeyManagement.modal.createTitle')}
        open={createModalVisible}
        onCancel={handleCloseCreateModal}
        footer={createdKeyResult ? [
          <Button key="close" type="primary" onClick={handleCloseCreateModal}>
            {t('common.close')}
          </Button>
        ] : null}
        width={600}
        maskClosable={false}
      >
        {!createdKeyResult ? (
          <Form
            form={form}
            onFinish={handleCreateKey}
            onFinishFailed={(errorInfo) => {
              console.error('Form validation failed:', errorInfo);
            }}
            layout="vertical"
            initialValues={{ expirationPreset: 'never' }}
          >
            <Form.Item
              name="name"
              label={t('apiKeyManagement.modal.keyName')}
              rules={[{ required: true, message: t('apiKeyManagement.modal.keyNameRequired') }]}
            >
              <Input placeholder={t('apiKeyManagement.modal.keyNamePlaceholder')} />
            </Form.Item>

            <Form.Item
              name="description"
              label={t('apiKeyManagement.modal.keyDescription')}
            >
              <Input.TextArea
                placeholder={t('apiKeyManagement.modal.keyDescriptionPlaceholder')}
                rows={3}
              />
            </Form.Item>

            <Form.Item
              name="expirationPreset"
              label={t('apiKeyManagement.modal.expiration')}
            >
              <Select
                onChange={(value) => setExpirationPreset(value)}
                options={[
                  { value: 'never', label: t('apiKeyManagement.modal.expirationNever') },
                  { value: '7days', label: t('apiKeyManagement.modal.expiration7Days') },
                  { value: '30days', label: t('apiKeyManagement.modal.expiration30Days') },
                  { value: '90days', label: t('apiKeyManagement.modal.expiration90Days') },
                  { value: '1year', label: t('apiKeyManagement.modal.expiration1Year') },
                  { value: 'custom', label: t('apiKeyManagement.modal.expirationCustom') },
                ]}
              />
            </Form.Item>

            {expirationPreset === 'custom' && (
              <Form.Item
                name="expiresAt"
                label={t('apiKeyManagement.modal.customExpiration')}
                rules={[{ required: true, message: t('apiKeyManagement.modal.customExpirationRequired') }]}
              >
                <DatePicker
                  showTime
                  disabledDate={(current) => current && current < dayjs().startOf('day')}
                  style={{ width: '100%' }}
                />
              </Form.Item>
            )}

            <Form.Item>
              <Space>
                <Button type="primary" htmlType="submit">
                  {t('common.create')}
                </Button>
                <Button onClick={handleCloseCreateModal}>
                  {t('common.cancel')}
                </Button>
              </Space>
            </Form.Item>
          </Form>
        ) : (
          <div>
            <Alert
              message={t('apiKeyManagement.modal.warningTitle')}
              description={t('apiKeyManagement.modal.warningMessage')}
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
            />

            <div style={{
              background: '#f5f5f5',
              padding: 16,
              borderRadius: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 16
            }}>
              <Text code style={{ fontSize: 14, wordBreak: 'break-all' }}>
                {createdKeyResult.apiKey}
              </Text>
              <Tooltip title={t('apiKeyManagement.modal.copyButton')}>
                <Button
                  icon={<CopyOutlined />}
                  onClick={() => handleCopyKey(createdKeyResult.apiKey)}
                />
              </Tooltip>
            </div>

            <Space direction="vertical" style={{ width: '100%' }}>
              <Text><strong>{t('apiKeyManagement.columns.name')}:</strong> {createdKeyResult.name}</Text>
              {createdKeyResult.description && (
                <Text><strong>{t('common.description')}:</strong> {createdKeyResult.description}</Text>
              )}
            </Space>
          </div>
        )}
      </Modal>
    </Card>
  );
};

export default ApiKeyManagement;
