/**
 * WebhookManagement Component for NemakiWare React UI
 *
 * Webhook management component providing delivery log viewing and retry operations:
 * - Delivery log list display with Ant Design Table component
 * - Filtering by status (success/failed/all)
 * - Retry failed deliveries
 * - Test webhook endpoint functionality
 * - View webhook configurations for objects
 *
 * Component Architecture:
 * WebhookManagement (stateful management orchestrator)
 *   ├─ useState: deliveryLogs, loading, testModalVisible, testUrl, testSecret, testResult
 *   ├─ useAuth: handleAuthError for authentication failure
 *   ├─ REST API: /rest/repo/{repositoryId}/webhook/* endpoints
 *   ├─ useEffect: loadDeliveryLogs() on repository change
 *   └─ Rendering:
 *       ├─ <Card> (container)
 *       │   ├─ Header: <h2> + <Button Test Webhook>
 *       │   ├─ <Select> (status filter)
 *       │   ├─ <Table> (delivery log list)
 *       │   │   ├─ Column: Delivery ID
 *       │   │   ├─ Column: Object ID
 *       │   │   ├─ Column: Event Type
 *       │   │   ├─ Column: Status
 *       │   │   ├─ Column: Status Code
 *       │   │   ├─ Column: Delivered At
 *       │   │   └─ Column: Actions (retry button)
 *       │   └─ <Modal> (test webhook form)
 */

import React, { useState, useEffect } from 'react';
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
  Descriptions
} from 'antd';
import {
  ReloadOutlined,
  SendOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
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
  objectId: string;
  eventType: string;
  webhookUrl: string;
  statusCode: number | null;
  success: boolean;
  attemptCount: number;
  deliveredAt: string | null;
  responseBody: string | null;
}

interface TestResult {
  success: boolean;
  statusCode: number;
  responseTime: number;
  responseBody: string | null;
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

  useEffect(() => {
    loadDeliveryLogs();
  }, [repositoryId]);

  /**
   * Get authentication headers for API requests.
   * Uses centralized auth header provider for Bearer token format.
   * This eliminates direct localStorage access and ensures consistent auth handling.
   */
  const getAuthHeaders = (): Record<string, string> => {
    return {
      'Content-Type': 'application/json',
      ...getCmisAuthHeaders()
    };
  };

  const loadDeliveryLogs = async () => {
    setLoading(true);
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/deliveries`,
        {
          method: 'GET',
          headers: getAuthHeaders(),
          credentials: 'include' // Send HttpOnly cookies automatically
        }
      );

      if (response.status === 401 || response.status === 403) {
        handleAuthError();
        return;
      }

      const data = await response.json();
      if (data.status === true && data.deliveries) {
        setDeliveryLogs(data.deliveries);
      } else if (data.errMsg) {
        message.error(t('webhookManagement.messages.loadError'));
      }
    } catch (error: any) {
      console.error('Failed to load delivery logs:', error);
      message.error(t('webhookManagement.messages.loadError'));
    } finally {
      setLoading(false);
    }
  };

  const handleRetry = async (deliveryId: string) => {
    try {
      const response = await fetch(
        `/core/rest/repo/${repositoryId}/webhook/deliveries/${deliveryId}/retry`,
        {
          method: 'POST',
          headers: getAuthHeaders(),
          credentials: 'include' // Send HttpOnly cookies automatically
        }
      );

      if (response.status === 401 || response.status === 403) {
        handleAuthError();
        return;
      }

      const data = await response.json();
      if (data.status === true) {
        message.success(t('webhookManagement.messages.retryQueued'));
        loadDeliveryLogs();
      } else {
        message.error(data.errMsg?.[0]?.msg || t('webhookManagement.messages.retryError'));
      }
    } catch (error: any) {
      console.error('Failed to retry delivery:', error);
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
          credentials: 'include', // Send HttpOnly cookies automatically
          body: JSON.stringify({
            url: values.url,
            secret: values.secret || null
          })
        }
      );

      if (response.status === 401 || response.status === 403) {
        handleAuthError();
        return;
      }

      const data = await response.json();
      if (data.status === true) {
        setTestResult({
          success: data.success,
          statusCode: data.statusCode,
          responseTime: data.responseTime,
          responseBody: data.responseBody || null
        });
      } else {
        message.error(data.errMsg?.[0]?.msg || t('webhookManagement.messages.testError'));
      }
    } catch (error: any) {
      console.error('Failed to test webhook:', error);
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

  const columns = [
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
      title: t('webhookManagement.columns.objectId'),
      dataIndex: 'objectId',
      key: 'objectId',
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
      render: (_: any, record: DeliveryLog) => (
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
            onClick={loadDeliveryLogs}
            loading={loading}
          >
            {t('common.refresh')}
          </Button>
        </Space>
      </div>

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
        columns={columns}
        dataSource={filteredLogs}
        rowKey="deliveryId"
        loading={loading}
        pagination={{
          pageSize: 20,
          showSizeChanger: true,
          showTotal: (total) => t('webhookManagement.pagination.total', { total })
        }}
        scroll={{ x: 1400 }}
      />

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
