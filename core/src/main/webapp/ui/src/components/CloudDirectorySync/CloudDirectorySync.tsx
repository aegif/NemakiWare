import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card,
  Button,
  Space,
  message,
  Progress,
  Statistic,
  Row,
  Col,
  Tag,
  Alert,
  Tabs,
  Descriptions,
  Typography,
  List,
  Popconfirm
} from 'antd';
import {
  SyncOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  CloudOutlined,
  GoogleOutlined,
  WindowsOutlined,
  StopOutlined,
  ApiOutlined
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  CloudSyncStatus,
  startDeltaSync,
  startFullReconciliation,
  getSyncStatus,
  cancelSync,
  testConnection
} from '../../services/cloudDirectorySync';

const { Text } = Typography;

interface CloudDirectorySyncProps {
  repositoryId: string;
}

const POLL_INTERVAL = 3000;

export const CloudDirectorySync: React.FC<CloudDirectorySyncProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const [activeProvider, setActiveProvider] = useState<string>('google');
  const [syncStatus, setSyncStatus] = useState<Record<string, CloudSyncStatus>>({});
  const [loading, setLoading] = useState<Record<string, boolean>>({});
  const [testingConnection, setTestingConnection] = useState<Record<string, boolean>>({});
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const isRunning = useCallback((provider: string) => {
    return syncStatus[provider]?.status === 'RUNNING';
  }, [syncStatus]);

  // Poll status for running syncs
  useEffect(() => {
    const pollRunning = async () => {
      for (const provider of ['google', 'microsoft']) {
        if (isRunning(provider)) {
          try {
            const status = await getSyncStatus(repositoryId, provider);
            setSyncStatus(prev => ({ ...prev, [provider]: status }));
          } catch {
            // ignore poll errors
          }
        }
      }
    };

    pollTimerRef.current = setInterval(pollRunning, POLL_INTERVAL);
    return () => {
      if (pollTimerRef.current) clearInterval(pollTimerRef.current);
    };
  }, [repositoryId, isRunning]);

  // Load initial status
  useEffect(() => {
    const loadStatus = async () => {
      for (const provider of ['google', 'microsoft']) {
        try {
          const status = await getSyncStatus(repositoryId, provider);
          setSyncStatus(prev => ({ ...prev, [provider]: status }));
        } catch {
          // ignore
        }
      }
    };
    loadStatus();
  }, [repositoryId]);

  const handleDeltaSync = async (provider: string) => {
    setLoading(prev => ({ ...prev, [provider]: true }));
    try {
      const result = await startDeltaSync(repositoryId, provider);
      setSyncStatus(prev => ({ ...prev, [provider]: result }));
      message.success(t('cloudSync.deltaSyncStarted'));
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : t('cloudSync.syncFailed'));
    } finally {
      setLoading(prev => ({ ...prev, [provider]: false }));
    }
  };

  const handleFullReconciliation = async (provider: string) => {
    setLoading(prev => ({ ...prev, [provider]: true }));
    try {
      const result = await startFullReconciliation(repositoryId, provider);
      setSyncStatus(prev => ({ ...prev, [provider]: result }));
      message.success(t('cloudSync.fullReconciliationStarted'));
    } catch (e: unknown) {
      message.error(e instanceof Error ? e.message : t('cloudSync.syncFailed'));
    } finally {
      setLoading(prev => ({ ...prev, [provider]: false }));
    }
  };

  const handleCancel = async (provider: string) => {
    try {
      await cancelSync(repositoryId, provider);
      message.info(t('cloudSync.syncCancelled'));
      // Refresh status
      const status = await getSyncStatus(repositoryId, provider);
      setSyncStatus(prev => ({ ...prev, [provider]: status }));
    } catch {
      message.error(t('cloudSync.cancelFailed'));
    }
  };

  const handleTestConnection = async (provider: string) => {
    setTestingConnection(prev => ({ ...prev, [provider]: true }));
    try {
      const connected = await testConnection(repositoryId, provider);
      if (connected) {
        message.success(t('cloudSync.connectionSuccess'));
      } else {
        message.warning(t('cloudSync.connectionFailed'));
      }
    } catch {
      message.error(t('cloudSync.connectionFailed'));
    } finally {
      setTestingConnection(prev => ({ ...prev, [provider]: false }));
    }
  };

  const renderStatusTag = (status: string | undefined) => {
    switch (status) {
      case 'RUNNING':
        return <Tag icon={<SyncOutlined spin />} color="processing">{t('cloudSync.statusRunning')}</Tag>;
      case 'COMPLETED':
        return <Tag icon={<CheckCircleOutlined />} color="success">{t('cloudSync.statusCompleted')}</Tag>;
      case 'ERROR':
        return <Tag icon={<WarningOutlined />} color="error">{t('cloudSync.statusError')}</Tag>;
      case 'CANCELLED':
        return <Tag color="default">{t('cloudSync.statusCancelled')}</Tag>;
      default:
        return <Tag color="default">{t('cloudSync.statusIdle')}</Tag>;
    }
  };

  const renderProviderPanel = (provider: string) => {
    const status = syncStatus[provider];
    const running = isRunning(provider);

    return (
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        {/* Actions */}
        <Card size="small" title={t('cloudSync.actions')}>
          <Space wrap>
            <Button
              type="primary"
              icon={<SyncOutlined />}
              loading={loading[provider]}
              disabled={running}
              onClick={() => handleDeltaSync(provider)}
            >
              {t('cloudSync.deltaSyncButton')}
            </Button>
            <Popconfirm
              title={t('cloudSync.fullReconciliationConfirm')}
              onConfirm={() => handleFullReconciliation(provider)}
              disabled={running}
            >
              <Button
                icon={<CloudOutlined />}
                loading={loading[provider]}
                disabled={running}
              >
                {t('cloudSync.fullReconciliationButton')}
              </Button>
            </Popconfirm>
            {running && (
              <Button
                danger
                icon={<StopOutlined />}
                onClick={() => handleCancel(provider)}
              >
                {t('cloudSync.cancelButton')}
              </Button>
            )}
            <Button
              icon={<ApiOutlined />}
              loading={testingConnection[provider]}
              onClick={() => handleTestConnection(provider)}
            >
              {t('cloudSync.testConnectionButton')}
            </Button>
          </Space>
        </Card>

        {/* Status */}
        {status && status.status !== 'IDLE' && (
          <Card size="small" title={t('cloudSync.syncStatus')}>
            <Descriptions column={2} size="small">
              <Descriptions.Item label={t('cloudSync.status')}>
                {renderStatusTag(status.status)}
              </Descriptions.Item>
              <Descriptions.Item label={t('cloudSync.mode')}>
                {status.syncMode === 'DELTA' ? t('cloudSync.modeDelta') : t('cloudSync.modeFull')}
              </Descriptions.Item>
              <Descriptions.Item label={t('cloudSync.startTime')}>
                {status.startTime ? new Date(status.startTime).toLocaleString() : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('cloudSync.endTime')}>
                {status.endTime ? new Date(status.endTime).toLocaleString() : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('cloudSync.currentPage')}>
                {status.currentPage}
              </Descriptions.Item>
            </Descriptions>

            {running && (
              <Progress
                percent={status.totalPages > 0 ? Math.round((status.currentPage / status.totalPages) * 100) : 0}
                status="active"
                style={{ marginTop: 8 }}
              />
            )}

            {/* Counters */}
            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
              <Col span={6}>
                <Statistic title={t('cloudSync.usersCreated')} value={status.usersCreated} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.usersUpdated')} value={status.usersUpdated} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.usersDeleted')} value={status.usersDeleted} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.usersSkipped')} value={status.usersSkipped} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.groupsCreated')} value={status.groupsCreated} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.groupsUpdated')} value={status.groupsUpdated} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.groupsDeleted')} value={status.groupsDeleted} />
              </Col>
              <Col span={6}>
                <Statistic title={t('cloudSync.groupsSkipped')} value={status.groupsSkipped} />
              </Col>
            </Row>

            {/* Errors */}
            {status.errors && status.errors.length > 0 && (
              <Alert
                type="error"
                message={t('cloudSync.errors')}
                description={
                  <List
                    size="small"
                    dataSource={status.errors}
                    renderItem={item => <List.Item><Text type="danger">{item}</Text></List.Item>}
                  />
                }
                style={{ marginTop: 16 }}
              />
            )}

            {/* Warnings */}
            {status.warnings && status.warnings.length > 0 && (
              <Alert
                type="warning"
                message={t('cloudSync.warnings')}
                description={
                  <List
                    size="small"
                    dataSource={status.warnings}
                    renderItem={item => <List.Item><Text type="warning">{item}</Text></List.Item>}
                  />
                }
                style={{ marginTop: 16 }}
              />
            )}
          </Card>
        )}
      </Space>
    );
  };

  return (
    <Card
      title={
        <Space>
          <CloudOutlined />
          {t('cloudSync.title')}
        </Space>
      }
    >
      <Tabs
        activeKey={activeProvider}
        onChange={setActiveProvider}
        items={[
          {
            key: 'google',
            label: (
              <Space>
                <GoogleOutlined />
                Google Workspace
              </Space>
            ),
            children: renderProviderPanel('google'),
          },
          {
            key: 'microsoft',
            label: (
              <Space>
                <WindowsOutlined />
                Microsoft Entra ID
              </Space>
            ),
            children: renderProviderPanel('microsoft'),
          },
        ]}
      />
    </Card>
  );
};
