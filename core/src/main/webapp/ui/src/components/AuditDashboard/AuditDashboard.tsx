import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Card,
  Row,
  Col,
  Statistic,
  Button,
  Spin,
  Alert,
  Tag,
  Space,
  Popconfirm,
  message,
  Table,
  Select,
  Tooltip,
  Typography
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  StopOutlined,
  ReloadOutlined,
  BarChartOutlined,
  DeleteOutlined,
  FileTextOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import {
  AuditMetricsService,
  AuditMetricsResponse,
  AuditEntry,
  AuditEntriesResponse
} from '../../services/auditMetrics';

const { Text } = Typography;

/** Auto-refresh interval in milliseconds */
const AUTO_REFRESH_INTERVAL_MS = 30000;

const DELETE_OPERATIONS = new Set([
  'DELETE_DOCUMENT', 'DELETE_FOLDER', 'DELETE_TREE',
  'ARCHIVE_DELETE', 'DELETE_USER', 'DELETE_GROUP'
]);
const WRITE_OPERATIONS = new Set([
  'CREATE_DOCUMENT', 'CREATE_FOLDER', 'UPDATE_DOCUMENT', 'UPDATE_FOLDER',
  'SET_CONTENT_STREAM', 'CHECK_IN', 'CHECK_OUT', 'CANCEL_CHECK_OUT',
  'APPLY_ACL', 'CREATE_USER', 'UPDATE_USER', 'CREATE_GROUP', 'UPDATE_GROUP',
  'ARCHIVE_RESTORE', 'MOVE_OBJECT'
]);

function getOperationColor(op: string): string {
  if (DELETE_OPERATIONS.has(op)) return 'red';
  if (WRITE_OPERATIONS.has(op)) return 'blue';
  return 'default';
}

export const AuditDashboard: React.FC = () => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const [metrics, setMetrics] = useState<AuditMetricsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);

  // Audit entries state
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(false);
  const [entriesLoaded, setEntriesLoaded] = useState(false);
  const [entryLimit, setEntryLimit] = useState(100);

  // Memoize the service to prevent recreation on every render
  const service = useMemo(
    () => new AuditMetricsService(() => handleAuthError(null)),
    [handleAuthError]
  );

  const fetchMetrics = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await service.getMetrics();
      setMetrics(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('auditDashboard.fetchError', 'Failed to fetch metrics'));
    } finally {
      setLoading(false);
    }
  }, [service, t]);

  // Use ref to store the latest fetchMetrics function for the interval
  const fetchMetricsRef = useRef(fetchMetrics);
  useEffect(() => {
    fetchMetricsRef.current = fetchMetrics;
  }, [fetchMetrics]);

  const handleReset = useCallback(async () => {
    setResetting(true);
    try {
      await service.resetMetrics();
      message.success(t('auditDashboard.resetSuccess', 'Metrics reset successfully'));
      await fetchMetrics();
    } catch (err) {
      message.error(err instanceof Error ? err.message : t('auditDashboard.resetError', 'Failed to reset metrics'));
    } finally {
      setResetting(false);
    }
  }, [service, t, fetchMetrics]);

  const fetchEntries = useCallback(async () => {
    setEntriesLoading(true);
    try {
      const data: AuditEntriesResponse = await service.getRecentEntries(entryLimit);
      setEntries(data.entries || []);
      setEntriesLoaded(true);
    } catch (err) {
      message.error(err instanceof Error ? err.message : t('auditDashboard.loadError', 'Failed to load audit log entries'));
    } finally {
      setEntriesLoading(false);
    }
  }, [service, entryLimit, t]);

  // Initial fetch and auto-refresh setup
  useEffect(() => {
    fetchMetrics();
    const interval = setInterval(() => {
      fetchMetricsRef.current();
    }, AUTO_REFRESH_INTERVAL_MS);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const columns: ColumnsType<AuditEntry> = useMemo(() => [
    {
      title: t('auditDashboard.timestamp', 'Timestamp'),
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 180,
      render: (val: string) => val ? new Date(val).toLocaleString() : '-',
      sorter: (a: AuditEntry, b: AuditEntry) => (a.timestamp || '').localeCompare(b.timestamp || ''),
      defaultSortOrder: 'descend' as const,
    },
    {
      title: t('auditDashboard.operation', 'Operation'),
      dataIndex: 'operation',
      key: 'operation',
      width: 180,
      render: (val: string) => <Tag color={getOperationColor(val)}>{val}</Tag>,
      filters: [...new Set(entries.map(e => e.operation))].filter(Boolean).map(op => ({ text: op, value: op })),
      onFilter: (value: React.Key | boolean, record: AuditEntry) => record.operation === value,
    },
    {
      title: t('auditDashboard.user', 'User'),
      dataIndex: 'userId',
      key: 'userId',
      width: 120,
    },
    {
      title: t('auditDashboard.target', 'Target'),
      dataIndex: 'objectName',
      key: 'objectName',
      width: 200,
      ellipsis: true,
      render: (val: string) => val ? <Tooltip title={val}>{val}</Tooltip> : '-',
    },
    {
      title: t('auditDashboard.result', 'Result'),
      dataIndex: 'result',
      key: 'result',
      width: 100,
      render: (val: string) => {
        if (val === 'SUCCESS') return <Tag color="success">SUCCESS</Tag>;
        if (val === 'FAILURE') return <Tag color="error">FAILURE</Tag>;
        return <Tag>{val || '-'}</Tag>;
      },
      filters: [
        { text: 'SUCCESS', value: 'SUCCESS' },
        { text: 'FAILURE', value: 'FAILURE' },
      ],
      onFilter: (value: React.Key | boolean, record: AuditEntry) => record.result === value,
    },
    {
      title: t('auditDashboard.duration', 'Duration'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (val: number) => val != null ? `${val} ms` : '-',
      sorter: (a: AuditEntry, b: AuditEntry) => (a.durationMs ?? 0) - (b.durationMs ?? 0),
    },
    {
      title: t('auditDashboard.clientIp', 'Client IP'),
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 130,
      render: (val: string) => val || '-',
    },
    {
      title: t('auditDashboard.errorMessage', 'Error'),
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      width: 200,
      ellipsis: true,
      render: (val: string) => val ? <Text type="danger">{val}</Text> : '-',
    },
  ], [t, entries]);

  if (loading && !metrics) {
    return <Spin size="large" style={{ display: 'block', textAlign: 'center', padding: '50px' }} />;
  }

  if (error) {
    const isAuthError = error.includes('Authentication') || error.includes('401') || error.includes('403');
    return (
      <div style={{ padding: '24px' }}>
        <Alert
          message={t('auditDashboard.error', 'Error')}
          description={error}
          type={isAuthError ? 'warning' : 'error'}
          action={
            <Button size="small" onClick={fetchMetrics}>
              {t('auditDashboard.retry', 'Retry')}
            </Button>
          }
        />
      </div>
    );
  }

  if (!metrics) {
    return null;
  }

  return (
    <div style={{ padding: '24px' }}>
      <Card
        title={
          <Space>
            <BarChartOutlined />
            <span>{t('auditDashboard.title', 'Audit Log Dashboard')}</span>
          </Space>
        }
        extra={
          <Space>
            <Tag color={metrics.enabled ? 'green' : 'red'}>
              {metrics.enabled ? t('auditDashboard.enabled', 'Enabled') : t('auditDashboard.disabled', 'Disabled')}
            </Tag>
            <Tag>{t('auditDashboard.level', 'Level')}: {metrics.readAuditLevel}</Tag>
            <Button
              icon={<ReloadOutlined />}
              onClick={fetchMetrics}
              loading={loading}
            >
              {t('auditDashboard.refresh', 'Refresh')}
            </Button>
            <Popconfirm
              title={t('auditDashboard.resetConfirmTitle', 'Reset Metrics')}
              description={t('auditDashboard.resetConfirmDesc', 'Are you sure you want to reset all metrics counters?')}
              onConfirm={handleReset}
              okText={t('common.yes', 'Yes')}
              cancelText={t('common.no', 'No')}
            >
              <Button
                icon={<DeleteOutlined />}
                loading={resetting}
                danger
              >
                {t('auditDashboard.reset', 'Reset')}
              </Button>
            </Popconfirm>
          </Space>
        }
      >
        {/* Metrics Statistics */}
        <Row gutter={16} style={{ marginBottom: 24 }}>
          <Col span={6}>
            <Statistic
              title={t('auditDashboard.totalEvents', 'Total Events')}
              value={metrics.metrics['audit.events.total'] || 0}
              prefix={<BarChartOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('auditDashboard.logged', 'Logged')}
              value={metrics.metrics['audit.events.logged'] || 0}
              valueStyle={{ color: '#3f8600' }}
              prefix={<CheckCircleOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('auditDashboard.skipped', 'Skipped')}
              value={metrics.metrics['audit.events.skipped'] || 0}
              valueStyle={{ color: '#faad14' }}
              prefix={<StopOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title={t('auditDashboard.failed', 'Failed')}
              value={metrics.metrics['audit.events.failed'] || 0}
              valueStyle={{ color: '#cf1322' }}
              prefix={<CloseCircleOutlined />}
            />
          </Col>
        </Row>

        {/* Rates Display */}
        {metrics.rates && (
          <Row gutter={16} style={{ marginBottom: 24 }}>
            <Col span={8}>
              <Card size="small">
                <Statistic
                  title={t('auditDashboard.successRate', 'Success Rate')}
                  value={metrics.rates['success.rate']}
                  valueStyle={{ color: '#3f8600' }}
                />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic
                  title={t('auditDashboard.skipRate', 'Skip Rate')}
                  value={metrics.rates['skip.rate']}
                  valueStyle={{ color: '#faad14' }}
                />
              </Card>
            </Col>
            <Col span={8}>
              <Card size="small">
                <Statistic
                  title={t('auditDashboard.failureRate', 'Failure Rate')}
                  value={metrics.rates['failure.rate']}
                  valueStyle={{ color: '#cf1322' }}
                />
              </Card>
            </Col>
          </Row>
        )}

        {/* Configuration Info */}
        <Alert
          message={t('auditDashboard.config', 'Audit Log Configuration')}
          description={
            <div>
              <p><strong>{t('auditDashboard.status', 'Status')}:</strong> {metrics.enabled ? t('auditDashboard.enabled', 'Enabled') : t('auditDashboard.disabled', 'Disabled')}</p>
              <p><strong>{t('auditDashboard.readAuditLevel', 'Read Audit Level')}:</strong> {metrics.readAuditLevel}</p>
              <p><strong>{t('auditDashboard.lastUpdated', 'Last Updated')}:</strong> {new Date(metrics.timestamp).toLocaleString()}</p>
            </div>
          }
          type="info"
          showIcon
        />
      </Card>

      {/* Recent Audit Entries */}
      <Card
        title={
          <Space>
            <FileTextOutlined />
            <span>{t('auditDashboard.recentEntries', 'Recent Audit Entries')}</span>
          </Space>
        }
        extra={
          <Space>
            <Select
              value={entryLimit}
              onChange={setEntryLimit}
              style={{ width: 100 }}
              options={[
                { value: 50, label: '50' },
                { value: 100, label: '100' },
                { value: 200, label: '200' },
                { value: 500, label: '500' },
              ]}
            />
            <Button
              icon={<ReloadOutlined />}
              onClick={fetchEntries}
              loading={entriesLoading}
            >
              {t('auditDashboard.loadEntries', 'Load Logs')}
            </Button>
          </Space>
        }
        style={{ marginTop: 16 }}
      >
        {!entriesLoaded ? (
          <Alert
            message={t('auditDashboard.noEntries', 'No audit log entries')}
            description={t('auditDashboard.loadEntries', 'Load Logs')}
            type="info"
            showIcon
          />
        ) : (
          <Table<AuditEntry>
            columns={columns}
            dataSource={entries}
            rowKey={(record) => record.eventId || `${record.timestamp}-${record.operation}`}
            size="small"
            scroll={{ x: 1200 }}
            pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `${total}` }}
            locale={{ emptyText: t('auditDashboard.noEntries', 'No audit log entries') }}
          />
        )}
      </Card>
    </div>
  );
};

export default AuditDashboard;
