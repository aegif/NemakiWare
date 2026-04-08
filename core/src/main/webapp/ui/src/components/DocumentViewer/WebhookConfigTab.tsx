import React, { useState, useEffect, useCallback } from 'react';
import {
  Table,
  Button,
  Space,
  Modal,
  Form,
  Input,
  Select,
  Switch,
  InputNumber,
  Checkbox,
  Popconfirm,
  Tag,
  message,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { parseJsonResponseBody } from '../../services/http/jsonFetch';
import { getResourceBaseErrorMessage } from '../../services/http/restResult';

interface WebhookConfigTabProps {
  repositoryId: string;
  objectId: string;
}

interface WebhookConfigData {
  id: string;
  url: string;
  events: string[];
  authType?: string;
  hasAuthCredential?: boolean;
  hasSecret?: boolean;
  includeChildren: boolean;
  maxDepth?: number;
  retryCount?: number;
  enabled: boolean;
  headerKeys?: string[];
}

interface WebhookFormValues {
  url: string;
  events: string[];
  authType: string;
  authCredential?: string;
  secret?: string;
  headers?: { key: string; value: string }[];
  includeChildren: boolean;
  maxDepth?: number;
  retryCount: number;
  enabled: boolean;
}

const EVENT_TYPES = [
  'created', 'updated', 'deleted', 'security', 'content_updated',
  'child_created', 'child_updated', 'child_deleted',
];

const AUTH_TYPES = ['none', 'basic', 'bearer', 'hmac'];

export const WebhookConfigTab: React.FC<WebhookConfigTabProps> = ({ repositoryId, objectId }) => {
  const { t } = useTranslation();
  const { authToken } = useAuth();
  const [configs, setConfigs] = useState<WebhookConfigData[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingConfig, setEditingConfig] = useState<WebhookConfigData | null>(null);
  const [form] = Form.useForm<WebhookFormValues>();

  const getHeaders = useCallback(() => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
    };
    if (authToken?.token) {
      headers['AUTH_TOKEN'] = authToken.token;
    }
    return headers;
  }, [authToken]);

  const loadConfigs = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/config/${objectId}`,
        { headers: getHeaders(), credentials: 'include' }
      );
      const data = await parseJsonResponseBody(response, 'documentViewer.webhooks.load');
      if (data.status === 'success') {
        setConfigs((data.webhookConfigs as WebhookConfigData[] | undefined) || []);
      } else {
        message.error(
          getResourceBaseErrorMessage(data as Record<string, unknown>, t('documentViewer.webhooks.loadError'))
        );
      }
    } catch {
      message.error(t('documentViewer.webhooks.loadError'));
    } finally {
      setLoading(false);
    }
  }, [repositoryId, objectId, getHeaders, t]);

  useEffect(() => {
    loadConfigs();
  }, [loadConfigs]);

  const handleAdd = () => {
    setEditingConfig(null);
    form.resetFields();
    form.setFieldsValue({
      enabled: true,
      authType: 'none',
      includeChildren: false,
      retryCount: 3,
      events: [],
    });
    setModalVisible(true);
  };

  const handleEdit = (config: WebhookConfigData) => {
    setEditingConfig(config);
    form.setFieldsValue({
      url: config.url,
      events: config.events,
      authType: config.authType || 'none',
      includeChildren: config.includeChildren,
      maxDepth: config.maxDepth,
      retryCount: config.retryCount ?? 3,
      enabled: config.enabled,
    });
    setModalVisible(true);
  };

  const handleDelete = async (webhookId: string) => {
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/config/${objectId}/${webhookId}`,
        { method: 'DELETE', headers: getHeaders(), credentials: 'include' }
      );
      const data = await parseJsonResponseBody(response, 'documentViewer.webhooks.delete');
      if (data.status === 'success') {
        message.success(t('documentViewer.webhooks.deleteSuccess'));
        loadConfigs();
      } else {
        message.error(
          getResourceBaseErrorMessage(data as Record<string, unknown>, t('documentViewer.webhooks.deleteError'))
        );
      }
    } catch {
      message.error(t('documentViewer.webhooks.deleteError'));
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();

      // Build request body
      const body: Record<string, unknown> = {
        url: values.url,
        events: values.events,
        enabled: values.enabled,
        includeChildren: values.includeChildren,
        retryCount: values.retryCount,
      };

      // Always send authType so it can be reset to 'none'
      body.authType = values.authType || 'none';

      if (body.authType === 'none') {
        // Explicitly clear credentials when switching to no auth
        body.authCredential = '';
        body.secret = '';
      } else {
        // Send credentials if provided (empty string clears on server)
        if (values.authCredential != null) {
          body.authCredential = values.authCredential;
        }
        if (values.secret != null) {
          body.secret = values.secret;
        }
      }
      if (values.maxDepth != null) {
        body.maxDepth = values.maxDepth;
      }

      // Build headers object from form list
      // Always send headers key so server can clear existing headers when all are removed
      const headersMap: Record<string, string> = {};
      if (values.headers && values.headers.length > 0) {
        for (const h of values.headers) {
          if (h.key && h.value) {
            headersMap[h.key] = h.value;
          }
        }
      }
      body.headers = headersMap;

      let url: string;
      let method: string;

      if (editingConfig) {
        url = `/core/rest/repo/${repositoryId}/webhook/config/${objectId}/${editingConfig.id}`;
        method = 'PUT';
      } else {
        url = `/core/rest/repo/${repositoryId}/webhook/config/${objectId}`;
        method = 'POST';
      }

      const response = await fetch(url, {
        method,
        headers: getHeaders(),
        credentials: 'include',
        body: JSON.stringify(body),
      });
      const data = await parseJsonResponseBody(response, 'documentViewer.webhooks.save');
      if (data.status === 'success') {
        message.success(t('documentViewer.webhooks.saveSuccess'));
        setModalVisible(false);
        loadConfigs();
      } else {
        message.error(
          getResourceBaseErrorMessage(data as Record<string, unknown>, t('documentViewer.webhooks.saveError'))
        );
      }
    } catch {
      // Form validation error - ignore
    }
  };

  const authTypeValue = Form.useWatch('authType', form);

  const columns = [
    {
      title: t('documentViewer.webhooks.url'),
      dataIndex: 'url',
      key: 'url',
      ellipsis: true,
    },
    {
      title: t('documentViewer.webhooks.events'),
      dataIndex: 'events',
      key: 'events',
      render: (events: string[]) => (
        <Space wrap>
          {events?.map((e: string) => (
            <Tag key={e} color="blue">
              {t(`documentViewer.webhooks.eventTypes.${e}`, e)}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t('documentViewer.webhooks.authType'),
      dataIndex: 'authType',
      key: 'authType',
      width: 120,
      render: (type: string) => type || t('documentViewer.webhooks.authTypeNone'),
    },
    {
      title: t('documentViewer.webhooks.enabled'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>
          {enabled ? t('documentViewer.webhooks.enabled') : t('documentViewer.webhooks.disabled')}
        </Tag>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_: unknown, record: WebhookConfigData) => (
        <Space>
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
            size="small"
          />
          <Popconfirm
            title={t('documentViewer.webhooks.deleteConfirm')}
            onConfirm={() => handleDelete(record.id)}
          >
            <Button type="text" danger icon={<DeleteOutlined />} size="small" />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          {t('documentViewer.webhooks.add')}
        </Button>
      </div>

      <Table
        columns={columns}
        dataSource={configs}
        rowKey="id"
        size="small"
        loading={loading}
        pagination={false}
        locale={{ emptyText: t('documentViewer.webhooks.noWebhooks') }}
      />

      <Modal
        title={editingConfig ? t('documentViewer.webhooks.edit') : t('documentViewer.webhooks.add')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        width={640}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="url"
            label={t('documentViewer.webhooks.url')}
            rules={[
              { required: true, message: t('documentViewer.webhooks.url') },
              { type: 'url', message: t('common.validation.invalidFormat', { field: 'URL' }) },
            ]}
          >
            <Input placeholder={t('documentViewer.webhooks.urlPlaceholder')} />
          </Form.Item>

          <Form.Item
            name="events"
            label={t('documentViewer.webhooks.events')}
            rules={[{ required: true, message: t('documentViewer.webhooks.events') }]}
          >
            <Checkbox.Group>
              <Space wrap>
                {EVENT_TYPES.map((et) => (
                  <Checkbox key={et} value={et}>
                    {t(`documentViewer.webhooks.eventTypes.${et}`, et)}
                  </Checkbox>
                ))}
              </Space>
            </Checkbox.Group>
          </Form.Item>

          <Form.Item name="authType" label={t('documentViewer.webhooks.authType')}>
            <Select>
              {AUTH_TYPES.map((at) => (
                <Select.Option key={at} value={at}>
                  {t(`documentViewer.webhooks.authType${at.charAt(0).toUpperCase() + at.slice(1)}`)}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          {authTypeValue && authTypeValue !== 'none' && authTypeValue !== 'hmac' && (
            <Form.Item name="authCredential" label={t('documentViewer.webhooks.authCredential')}>
              <Input.Password placeholder={t('documentViewer.webhooks.authCredentialPlaceholder')} />
            </Form.Item>
          )}

          {authTypeValue === 'hmac' && (
            <Form.Item name="secret" label={t('documentViewer.webhooks.secret')}>
              <Input.Password placeholder={t('documentViewer.webhooks.secretPlaceholder')} />
            </Form.Item>
          )}

          <Form.List name="headers">
            {(fields, { add, remove }) => (
              <>
                <div style={{ marginBottom: 8, fontWeight: 500 }}>
                  {t('documentViewer.webhooks.headers')}
                </div>
                {fields.map(({ key, name, ...restField }) => (
                  <Space key={key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                    <Form.Item {...restField} name={[name, 'key']} style={{ marginBottom: 0 }}>
                      <Input placeholder={t('documentViewer.webhooks.headerKey')} />
                    </Form.Item>
                    <Form.Item {...restField} name={[name, 'value']} style={{ marginBottom: 0 }}>
                      <Input placeholder={t('documentViewer.webhooks.headerValue')} />
                    </Form.Item>
                    <MinusCircleOutlined onClick={() => remove(name)} />
                  </Space>
                ))}
                <Button type="dashed" onClick={() => add()} icon={<PlusOutlined />} block>
                  {t('documentViewer.webhooks.addHeader')}
                </Button>
              </>
            )}
          </Form.List>

          <div style={{ marginTop: 16 }} />

          <Space>
            <Form.Item name="includeChildren" label={t('documentViewer.webhooks.includeChildren')} valuePropName="checked">
              <Switch />
            </Form.Item>

            <Form.Item name="maxDepth" label={t('documentViewer.webhooks.maxDepth')}>
              <InputNumber min={1} max={50} />
            </Form.Item>

            <Form.Item name="retryCount" label={t('documentViewer.webhooks.retryCount')}>
              <InputNumber min={0} max={5} />
            </Form.Item>

            <Form.Item name="enabled" label={t('documentViewer.webhooks.enabled')} valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  );
};
