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
  message
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  StopOutlined,
  ReloadOutlined,
  BarChartOutlined,
  DeleteOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { AuditMetricsService, AuditMetricsResponse } from '../../services/auditMetrics';

/** Auto-refresh interval in milliseconds */
const AUTO_REFRESH_INTERVAL_MS = 30000;

export const AuditDashboard: React.FC = () => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const [metrics, setMetrics] = useState<AuditMetricsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);

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
  // This prevents unnecessary interval re-registration when fetchMetrics changes
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

  // Initial fetch and auto-refresh setup
  useEffect(() => {
    // Initial fetch
    fetchMetrics();

    // Auto-refresh using ref to avoid re-registration
    const interval = setInterval(() => {
      fetchMetricsRef.current();
    }, AUTO_REFRESH_INTERVAL_MS);

    return () => clearInterval(interval);
    // Only run on mount and unmount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
    </div>
  );
};

export default AuditDashboard;
