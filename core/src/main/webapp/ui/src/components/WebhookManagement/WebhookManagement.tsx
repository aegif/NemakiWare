import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table,
  Button,
  Space,
  Modal,
  Form,
  Input,
  message,
  Card,
  Tag,
  Select,
  Tooltip,
  Spin,
  Alert,
  Descriptions,
  Tabs
} from 'antd';
import {
  ReloadOutlined,
  SendOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExperimentOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { getCmisAuthHeaders } from '../../services/auth/CmisAuthHeaderProvider';

interface WebhookManagementProps {
  repositoryId: string;
}

interface DeliveryLog {
  deliveryId: string;
  attemptId?: string;
  objectId: string;
  eventType: string;
  webhookUrl: string;
  statusCode: number | null;
  success: boolean;
  attemptCount: number;
  deliveredAt: string | null;
  responseBody: string | null;
}

interface WebhookConfigEntry {
  id: string;
  enabled: boolean;
  url: string;
  events: string[];
  authType: string | null;
  includeChildren: boolean;
  maxDepth: number | null;
  retryCount: number | null;
  hasAuthCredential: boolean;
  hasSecret: boolean;
}

interface WebhookableObject {
  objectId: string;
  objectName: string;
  objectPath: string | null;
  webhookConfigs: WebhookConfigEntry[];
}

/** Flattened row for the configs table: one row per config, with object info */
interface ConfigRow {
  key: string;
  objectId: string;
  objectName: string;
  objectPath: string | null;
  config: WebhookConfigEntry;
}

interface TestResult {
  success: boolean;
  statusCode: number;
  responseTime: number;
  responseBody: string | null;
}

/** Resolve objectId -> path via CMIS browser binding */
async function resolveObjectPath(
  repositoryId: string,
  objectId: string,
  headers: Record<string, string>
): Promise<{ name: string; path: string } | null> {
  try {
    const resp = await fetch(
      `/core/browser/${repositoryId}/root?objectId=${encodeURIComponent(objectId)}&cmisselector=object&succinct=true`,
      { headers, credentials: 'include' }
    );
    if (!resp.ok) return null;
    const data = await resp.json();
    const props = data.succinctProperties || {};
    return {
      name: props['cmis:name'] || objectId,
      path: props['cmis:path'] || props['nk:parentPath'] || null,
    };
  } catch {
    return null;
  }
}

export const WebhookManagement: React.FC<WebhookManagementProps> = ({ repositoryId }) => {
  const [deliveryLogs, setDeliveryLogs] = useState<DeliveryLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [testModalVisible, setTestModalVisible] = useState(false);
  const [testLoading, setTestLoading] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [form] = Form.useForm();
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();

  // Configs tab state
  const [configRows, setConfigRows] = useState<ConfigRow[]>([]);
  const [configsLoading, setConfigsLoading] = useState(false);

  // Delivery log path cache: objectId -> display label
  const [pathCache, setPathCache] = useState<Record<string, string>>({});
  const pathCacheRef = useRef<Record<string, string>>({});

  // Track current repositoryId for stale response detection.
  // Updated synchronously during render (not in useEffect) to ensure there is no
  // window between re-render and effect execution where the ref holds a stale value.
  const repositoryIdRef = useRef(repositoryId);
  repositoryIdRef.current = repositoryId;

  const getAuthHeaders = useCallback((): Record<string, string> => {
    return {
      'Content-Type': 'application/json',
      ...getCmisAuthHeaders()
    };
  }, []);

  // --- Delivery Logs ---
  const loadDeliveryLogs = useCallback(async (signal?: AbortSignal) => {
    const isStale = () => signal?.aborted || repositoryId !== repositoryIdRef.current;
    setLoading(true);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/deliveries`,
        { method: 'GET', headers: getAuthHeaders(), credentials: 'include', signal }
      );
      if (isStale()) return;
      if (response.status === 401 || response.status === 403) {
        handleAuthError(null);
        return;
      }
      const data = await response.json();
      if (isStale()) return;
      if (data.status === 'success' && data.deliveries) {
        setDeliveryLogs(data.deliveries);
        // Resolve paths only for objectIds not already cached
        const allObjectIds = [...new Set((data.deliveries as DeliveryLog[]).map(d => d.objectId))];
        const uncachedIds = allObjectIds.filter(oid => !(oid in pathCacheRef.current));
        if (uncachedIds.length > 0) {
          const headers = getAuthHeaders();
          const entries = await Promise.all(
            uncachedIds.map(async (oid) => {
              const resolved = await resolveObjectPath(repositoryId, oid, headers);
              return [oid, resolved ? (resolved.path || resolved.name) : oid] as [string, string];
            })
          );
          if (isStale()) return;
          const newEntries: Record<string, string> = {};
          for (const [k, v] of entries) {
            newEntries[k] = v;
          }
          pathCacheRef.current = { ...pathCacheRef.current, ...newEntries };
          setPathCache(prev => ({ ...prev, ...newEntries }));
        }
      } else if (data.status === 'failure') {
        message.error(t('webhookManagement.messages.loadError'));
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
      if (isStale()) return;
      message.error(t('webhookManagement.messages.loadError'));
    } finally {
      if (!isStale()) {
        setLoading(false);
      }
    }
  }, [repositoryId, getAuthHeaders, handleAuthError, t]);

  // --- Webhook Configs ---
  const loadConfigs = useCallback(async (signal?: AbortSignal) => {
    const isStale = () => signal?.aborted || repositoryId !== repositoryIdRef.current;
    setConfigsLoading(true);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/configs`,
        { method: 'GET', headers: getAuthHeaders(), credentials: 'include', signal }
      );
      if (isStale()) return;
      if (response.status === 401 || response.status === 403) {
        handleAuthError(null);
        return;
      }
      const data = await response.json();
      if (isStale()) return;
      if (data.status === 'success' && data.objects) {
        const rows: ConfigRow[] = [];
        for (const obj of data.objects as WebhookableObject[]) {
          for (const config of obj.webhookConfigs) {
            rows.push({
              key: `${obj.objectId}-${config.id}`,
              objectId: obj.objectId,
              objectName: obj.objectName,
              objectPath: obj.objectPath,
              config,
            });
          }
        }
        setConfigRows(rows);
      } else {
        message.error(t('webhookManagement.configsLoadError'));
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return;
      if (isStale()) return;
      message.error(t('webhookManagement.configsLoadError'));
    } finally {
      if (!isStale()) {
        setConfigsLoading(false);
      }
    }
  }, [repositoryId, getAuthHeaders, handleAuthError, t]);

  useEffect(() => {
    // Clear path cache when repository changes
    pathCacheRef.current = {};
    setPathCache({});
    const controller = new AbortController();
    loadConfigs(controller.signal);
    loadDeliveryLogs(controller.signal);
    return () => controller.abort();
  }, [repositoryId]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleRetry = async (deliveryId: string) => {
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/deliveries/${deliveryId}/retry`,
        { method: 'POST', headers: getAuthHeaders(), credentials: 'include' }
      );
      if (response.status === 401 || response.status === 403) {
        handleAuthError(null);
        return;
      }
      const data = await response.json();
      if (data.status === 'success') {
        message.success(t('webhookManagement.messages.retryQueued'));
        loadDeliveryLogs();
      } else {
        message.error(t('webhookManagement.messages.retryError'));
      }
    } catch {
      message.error(t('webhookManagement.messages.retryError'));
    }
  };

  const handleTestWebhook = async (values: { url: string; secret?: string }) => {
    setTestLoading(true);
    setTestResult(null);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/test`,
        {
          method: 'POST',
          headers: getAuthHeaders(),
          credentials: 'include',
          body: JSON.stringify({ url: values.url, secret: values.secret || null })
        }
      );
      if (response.status === 401 || response.status === 403) {
        handleAuthError(null);
        return;
      }
      const data = await response.json();
      if (data.status === 'success') {
        setTestResult({
          success: data.success,
          statusCode: data.statusCode,
          responseTime: data.responseTime,
          responseBody: data.responseBody || null
        });
      } else {
        message.error(t('webhookManagement.messages.testError'));
      }
    } catch {
      message.error(t('webhookManagement.messages.testError'));
    } finally {
      setTestLoading(false);
    }
  };

  const filteredLogs = deliveryLogs.filter(log => {
    if (statusFilter === 'all') return true;
    if (statusFilter === 'success') return log.success;
    if (statusFilter === 'failed') return !log.success;
    return true;
  });

  // --- Render object path as a link ---
  const renderObjectLink = (objectId: string, displayPath: string | null) => {
    const label = displayPath || objectId;
    return (
      <Tooltip title={objectId}>
        <a href={`#/documents/${objectId}`} style={{ fontSize: '12px' }}>
          {label}
        </a>
      </Tooltip>
    );
  };

  // --- Configs table columns ---
  const configColumns = [
    {
      title: t('webhookManagement.configColumns.objectPath'),
      key: 'objectPath',
      width: 200,
      ellipsis: true,
      render: (_: unknown, row: ConfigRow) => renderObjectLink(row.objectId, row.objectPath || row.objectName),
    },
    {
      title: t('webhookManagement.configColumns.url'),
      key: 'url',
      width: 250,
      ellipsis: true,
      render: (_: unknown, row: ConfigRow) => (
        <Tooltip title={row.config.url}>
          <span style={{ fontSize: '12px' }}>
            {row.config.url && row.config.url.length > 40
              ? row.config.url.substring(0, 40) + '...'
              : row.config.url || '-'}
          </span>
        </Tooltip>
      ),
    },
    {
      title: t('webhookManagement.configColumns.events'),
      key: 'events',
      width: 200,
      render: (_: unknown, row: ConfigRow) => (
        <Space size={[0, 4]} wrap>
          {(row.config.events || []).map(ev => (
            <Tag key={ev} color="blue">{ev}</Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t('webhookManagement.configColumns.authType'),
      key: 'authType',
      width: 100,
      render: (_: unknown, row: ConfigRow) => row.config.authType || '-',
    },
    {
      title: t('webhookManagement.configColumns.enabled'),
      key: 'enabled',
      width: 80,
      render: (_: unknown, row: ConfigRow) => (
        row.config.enabled
          ? <Tag color="green">{t('webhookManagement.status.enabled')}</Tag>
          : <Tag color="default">{t('webhookManagement.status.disabled')}</Tag>
      ),
    },
    {
      title: t('webhookManagement.configColumns.includeChildren'),
      key: 'includeChildren',
      width: 100,
      render: (_: unknown, row: ConfigRow) => (
        row.config.includeChildren
          ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
          : <CloseCircleOutlined style={{ color: '#d9d9d9' }} />
      ),
    },
  ];

  // --- Delivery log columns ---
  const deliveryColumns = [
    {
      title: t('webhookManagement.columns.deliveryId'),
      dataIndex: 'deliveryId',
      key: 'deliveryId',
      width: 200,
      ellipsis: true,
      render: (text: string) => (
        <Tooltip title={text}>
          <span style={{ fontFamily: 'monospace', fontSize: '12px' }}>
            {text?.substring(0, 8)}...
          </span>
        </Tooltip>
      )
    },
    {
      title: t('webhookManagement.columns.objectPath'),
      dataIndex: 'objectId',
      key: 'objectPath',
      width: 200,
      ellipsis: true,
      render: (objectId: string) => renderObjectLink(objectId, pathCache[objectId] || null),
    },
    {
      title: t('webhookManagement.columns.webhookUrl'),
      dataIndex: 'webhookUrl',
      key: 'webhookUrl',
      width: 200,
      ellipsis: true,
      render: (text: string) => (
        <Tooltip title={text}>
          <span style={{ fontSize: '12px' }}>
            {text ? (text.length > 30 ? text.substring(0, 30) + '...' : text) : '-'}
          </span>
        </Tooltip>
      )
    },
    {
      title: t('webhookManagement.columns.eventType'),
      dataIndex: 'eventType',
      key: 'eventType',
      width: 120,
      render: (text: string) => <Tag color="blue">{text}</Tag>
    },
    {
      title: t('webhookManagement.columns.status'),
      dataIndex: 'success',
      key: 'success',
      width: 100,
      render: (success: boolean) => (
        success ? (
          <Tag icon={<CheckCircleOutlined />} color="success">
            {t('webhookManagement.status.success')}
          </Tag>
        ) : (
          <Tag icon={<CloseCircleOutlined />} color="error">
            {t('webhookManagement.status.failed')}
          </Tag>
        )
      )
    },
    {
      title: t('webhookManagement.columns.statusCode'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 100,
      render: (code: number | null) => (
        code ? (
          <Tag color={code >= 200 && code < 300 ? 'green' : 'red'}>
            {code}
          </Tag>
        ) : '-'
      )
    },
    {
      title: t('webhookManagement.columns.attemptCount'),
      dataIndex: 'attemptCount',
      key: 'attemptCount',
      width: 80,
      render: (count: number) => count || 1
    },
    {
      title: t('webhookManagement.columns.deliveredAt'),
      dataIndex: 'deliveredAt',
      key: 'deliveredAt',
      width: 180,
      render: (text: string | null) => text || '-'
    },
    {
      title: t('webhookManagement.columns.errorDetail'),
      dataIndex: 'responseBody',
      key: 'responseBody',
      width: 150,
      render: (text: string | null, record: DeliveryLog) => (
        !record.success && text ? (
          <Tooltip
            title={
              <pre style={{ maxWidth: 400, maxHeight: 200, overflow: 'auto', margin: 0, whiteSpace: 'pre-wrap' }}>
                {text}
              </pre>
            }
            overlayStyle={{ maxWidth: 450 }}
          >
            <span style={{ color: '#ff4d4f', fontSize: '12px', cursor: 'pointer' }}>
              {text.length > 20 ? text.substring(0, 20) + '...' : text}
            </span>
          </Tooltip>
        ) : '-'
      )
    },
    {
      title: t('webhookManagement.columns.actions'),
      key: 'actions',
      width: 100,
      render: (_: unknown, record: DeliveryLog) => (
        <Space>
          {!record.success && (
            <Tooltip title={t('webhookManagement.actions.retry')}>
              <Button
                type="link"
                icon={<ReloadOutlined />}
                onClick={() => handleRetry(record.deliveryId)}
              />
            </Tooltip>
          )}
        </Space>
      )
    }
  ];

  const tabItems = [
    {
      key: 'configs',
      label: t('webhookManagement.tabs.configs'),
      children: (
        <Table
          columns={configColumns}
          dataSource={configRows}
          rowKey="key"
          loading={configsLoading}
          pagination={false}
          scroll={{ x: 1000 }}
          locale={{ emptyText: t('webhookManagement.noConfigs') }}
        />
      ),
    },
    {
      key: 'deliveryLogs',
      label: t('webhookManagement.tabs.deliveryLogs'),
      children: (
        <>
          <div style={{ marginBottom: 16 }}>
            <Space>
              <span>{t('webhookManagement.filter.status')}:</span>
              <Select
                value={statusFilter}
                onChange={setStatusFilter}
                style={{ width: 150 }}
              >
                <Select.Option value="all">{t('webhookManagement.filter.all')}</Select.Option>
                <Select.Option value="success">{t('webhookManagement.filter.success')}</Select.Option>
                <Select.Option value="failed">{t('webhookManagement.filter.failed')}</Select.Option>
              </Select>
            </Space>
          </div>
          <Table
            columns={deliveryColumns}
            dataSource={filteredLogs}
            rowKey={(record: DeliveryLog) => record.attemptId || record.deliveryId}
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => t('webhookManagement.pagination.total', { total })
            }}
            scroll={{ x: 1400 }}
          />
        </>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>{t('webhookManagement.title')}</h2>
        <Space>
          <Button
            icon={<ExperimentOutlined />}
            onClick={() => {
              setTestResult(null);
              form.resetFields();
              setTestModalVisible(true);
            }}
          >
            {t('webhookManagement.actions.testWebhook')}
          </Button>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => { loadConfigs(); loadDeliveryLogs(); }}
            loading={loading || configsLoading}
          >
            {t('common.refresh')}
          </Button>
        </Space>
      </div>

      <Tabs items={tabItems} defaultActiveKey="configs" />

      <Modal
        title={t('webhookManagement.testModal.title')}
        open={testModalVisible}
        onCancel={() => setTestModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleTestWebhook}
        >
          <Form.Item
            name="url"
            label={t('webhookManagement.testModal.url')}
            rules={[
              { required: true, message: t('webhookManagement.testModal.urlRequired') },
              { type: 'url', message: t('webhookManagement.testModal.urlInvalid') }
            ]}
          >
            <Input placeholder="https://example.com/webhook" />
          </Form.Item>

          <Form.Item
            name="secret"
            label={t('webhookManagement.testModal.secret')}
          >
            <Input.Password placeholder={t('webhookManagement.testModal.secretPlaceholder')} />
          </Form.Item>

          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SendOutlined />}
              loading={testLoading}
            >
              {t('webhookManagement.testModal.send')}
            </Button>
          </Form.Item>
        </Form>

        {testLoading && (
          <div style={{ textAlign: 'center', padding: 20 }}>
            <Spin tip={t('webhookManagement.testModal.sending')} />
          </div>
        )}

        {testResult && (
          <div style={{ marginTop: 16 }}>
            <Alert
              type={testResult.success ? 'success' : 'error'}
              message={testResult.success ? t('webhookManagement.testModal.resultSuccess') : t('webhookManagement.testModal.resultFailed')}
              style={{ marginBottom: 16 }}
            />
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label={t('webhookManagement.testModal.statusCode')}>
                <Tag color={testResult.statusCode >= 200 && testResult.statusCode < 300 ? 'green' : 'red'}>
                  {testResult.statusCode}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('webhookManagement.testModal.responseTime')}>
                {testResult.responseTime}ms
              </Descriptions.Item>
              {testResult.responseBody && (
                <Descriptions.Item label={t('webhookManagement.testModal.responseBody')}>
                  <pre style={{
                    maxHeight: 200,
                    overflow: 'auto',
                    background: '#f5f5f5',
                    padding: 8,
                    margin: 0,
                    fontSize: 12
                  }}>
                    {testResult.responseBody}
                  </pre>
                </Descriptions.Item>
              )}
            </Descriptions>
          </div>
        )}
      </Modal>
    </Card>
  );
};

export default WebhookManagement;
