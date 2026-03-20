import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Row,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  DatabaseOutlined,
  DisconnectOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import {
  PurviewAdminService,
  PurviewConnectionStatus,
  PurviewCursorState,
  PurviewDeadLetterState,
  PurviewJobState,
  PurviewLockState,
  PurviewSchemaApplyJobResult,
  PurviewSchemaDiff,
  PurviewStateOverview,
  PurviewTombstoneState,
} from '../../services/purviewAdmin';

const { Text } = Typography;

interface PurviewManagementProps {
  repositoryId: string;
}

interface ActionResult {
  kind: string;
  message: string;
  job?: PurviewJobState;
}

const statusColorMap: Record<string, string> = {
  COMPLETED: 'success',
  COMPLETED_WITH_ERRORS: 'warning',
  FAILED: 'error',
  REJECTED: 'warning',
  RUNNING: 'processing',
  PENDING: 'processing',
  ARCHIVED: 'blue',
  PURGED: 'default',
  ACTIVE: 'success',
};

const formatTimestamp = (value?: string): string => {
  if (!value) {
    return '-';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
};

const isCollectionScope = (repositoryId?: string): boolean =>
  !!repositoryId && repositoryId.startsWith('collection:');

const renderStatusTag = (status?: string): React.ReactNode => {
  if (!status) {
    return <Tag>-</Tag>;
  }
  return <Tag color={statusColorMap[status] || 'default'}>{status}</Tag>;
};

const renderTagList = (values: string[]): React.ReactNode => {
  if (!values || values.length === 0) {
    return <Text type="secondary">-</Text>;
  }

  return (
    <Space size={[4, 8]} wrap>
      {values.map((value) => (
        <Tag key={value}>{value}</Tag>
      ))}
    </Space>
  );
};

export const PurviewManagement: React.FC<PurviewManagementProps> = ({ repositoryId }) => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const service = useMemo(
    () => new PurviewAdminService(() => handleAuthError(null)),
    [handleAuthError]
  );

  const [overview, setOverview] = useState<PurviewStateOverview | null>(null);
  const [schemaDiff, setSchemaDiff] = useState<PurviewSchemaDiff | null>(null);
  const [connectionStatus, setConnectionStatus] = useState<PurviewConnectionStatus | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const [actionResult, setActionResult] = useState<ActionResult | null>(null);
  const initialLoadRef = useRef(true);

  const loadAll = useCallback(async () => {
    setRefreshing(true);
    const results = await Promise.allSettled([
      service.getStateOverview(),
      service.getSchemaDiff(),
      service.testConnection(),
    ]);

    const nextErrors: string[] = [];

    const [overviewResult, diffResult, connectionResult] = results;

    if (overviewResult.status === 'fulfilled') {
      setOverview(overviewResult.value);
    } else {
      nextErrors.push(overviewResult.reason instanceof Error ? overviewResult.reason.message : t('common.unknownError'));
    }

    if (diffResult.status === 'fulfilled') {
      setSchemaDiff(diffResult.value);
    } else {
      nextErrors.push(diffResult.reason instanceof Error ? diffResult.reason.message : t('common.unknownError'));
    }

    if (connectionResult.status === 'fulfilled') {
      setConnectionStatus(connectionResult.value);
    } else {
      nextErrors.push(
        connectionResult.reason instanceof Error ? connectionResult.reason.message : t('common.unknownError')
      );
    }

    setLoadError(nextErrors.length > 0 ? nextErrors.join(' / ') : null);
    setRefreshing(false);
    initialLoadRef.current = false;
  }, [service, t]);

  useEffect(() => {
    void loadAll();
  }, [loadAll, repositoryId]);

  const matchesScope = useCallback(
    (value?: string) => !value || value === repositoryId || isCollectionScope(value),
    [repositoryId]
  );

  const jobs = useMemo(
    () =>
      (overview?.jobs ?? [])
        .filter((job) => matchesScope(job.repositoryId))
        .sort((a, b) => (b.startedAt || '').localeCompare(a.startedAt || '')),
    [overview, matchesScope]
  );

  const cursors = useMemo(
    () =>
      (overview?.cursors ?? [])
        .filter((cursor) => matchesScope(cursor.repositoryId))
        .sort((a, b) => a.streamKind.localeCompare(b.streamKind)),
    [overview, matchesScope]
  );

  const locks = useMemo(
    () =>
      (overview?.locks ?? [])
        .filter((lock) => matchesScope(lock.repositoryId))
        .sort((a, b) => a.jobKind.localeCompare(b.jobKind)),
    [overview, matchesScope]
  );

  const tombstones = useMemo(
    () =>
      (overview?.tombstones ?? [])
        .filter((tombstone) => matchesScope(tombstone.repositoryId))
        .sort((a, b) => (b.dueAt || '').localeCompare(a.dueAt || '')),
    [overview, matchesScope]
  );

  const deadLetters = useMemo(
    () =>
      (overview?.deadLetters ?? [])
        .filter((deadLetter) => matchesScope(deadLetter.repositoryId))
        .sort((a, b) => (b.lastFailedAt || '').localeCompare(a.lastFailedAt || '')),
    [overview, matchesScope]
  );

  const hasRunningJobs = jobs.some((job) => job.status === 'RUNNING');

  useEffect(() => {
    if (!hasRunningJobs) {
      return;
    }

    const intervalId = window.setInterval(() => {
      void loadAll();
    }, 5000);

    return () => window.clearInterval(intervalId);
  }, [hasRunningJobs, loadAll]);

  const handleRefresh = async () => {
    await loadAll();
  };

  const handleConnectionProbe = async () => {
    setRunningAction('test-connection');
    try {
      const result = await service.testConnection();
      setConnectionStatus(result);
      setActionResult({
        kind: result.connected ? 'COMPLETED' : 'FAILED',
        message: result.message,
      });
      if (result.connected) {
        message.success(result.message);
      } else {
        message.warning(result.message);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
      setActionResult({ kind: 'FAILED', message: errorMessage });
      message.error(errorMessage);
    } finally {
      setRunningAction(null);
    }
  };

  const runJobAction = async (
    actionKey: string,
    runner: () => Promise<PurviewJobState | PurviewSchemaApplyJobResult>,
    successMessageKey: string
  ) => {
    setRunningAction(actionKey);
    try {
      const result = await runner();
      const actionMessage =
        'message' in result && result.message
          ? result.message
          : t(successMessageKey);
      setActionResult({
        kind: result.status,
        message: actionMessage,
        job: result,
      });

      if (result.status === 'FAILED') {
        message.error(result.errorSummary || actionMessage);
      } else if (result.status === 'REJECTED' || result.status === 'COMPLETED_WITH_ERRORS') {
        message.warning(result.errorSummary || actionMessage);
      } else {
        message.success(actionMessage);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
      setActionResult({ kind: 'FAILED', message: errorMessage });
      message.error(errorMessage);
    } finally {
      setRunningAction(null);
      await loadAll();
    }
  };

  const jobColumns = [
    {
      title: t('purviewManagement.tables.jobs.jobId'),
      dataIndex: 'jobId',
      key: 'jobId',
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('purviewManagement.tables.jobs.jobKind'),
      dataIndex: 'jobKind',
      key: 'jobKind',
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('purviewManagement.tables.jobs.repositoryId'),
      dataIndex: 'repositoryId',
      key: 'repositoryId',
      render: (value: string) => <Text>{value || '-'}</Text>,
    },
    {
      title: t('purviewManagement.tables.jobs.status'),
      dataIndex: 'status',
      key: 'status',
      render: (value: string) => renderStatusTag(value),
    },
    {
      title: t('purviewManagement.tables.jobs.processedCount'),
      dataIndex: 'processedCount',
      key: 'processedCount',
    },
    {
      title: t('purviewManagement.tables.jobs.failedCount'),
      dataIndex: 'failedCount',
      key: 'failedCount',
    },
    {
      title: t('purviewManagement.tables.jobs.startedAt'),
      dataIndex: 'startedAt',
      key: 'startedAt',
      render: (value: string) => formatTimestamp(value),
    },
    {
      title: t('purviewManagement.tables.jobs.errorSummary'),
      dataIndex: 'errorSummary',
      key: 'errorSummary',
      render: (value: string) => value || '-',
    },
  ];

  const cursorColumns = [
    {
      title: t('purviewManagement.tables.cursors.streamKind'),
      dataIndex: 'streamKind',
      key: 'streamKind',
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('purviewManagement.tables.cursors.cursor'),
      dataIndex: 'cursor',
      key: 'cursor',
      render: (value: string) => <Text code>{value || '-'}</Text>,
    },
    {
      title: t('purviewManagement.tables.cursors.lastSuccessAt'),
      dataIndex: 'lastSuccessAt',
      key: 'lastSuccessAt',
      render: (value: string) => formatTimestamp(value),
    },
    {
      title: t('purviewManagement.tables.cursors.lastErrorAt'),
      dataIndex: 'lastErrorAt',
      key: 'lastErrorAt',
      render: (value: string) => formatTimestamp(value),
    },
    {
      title: t('purviewManagement.tables.cursors.deadLetterCount'),
      dataIndex: 'deadLetterCount',
      key: 'deadLetterCount',
    },
    {
      title: t('purviewManagement.tables.cursors.lastErrorMessage'),
      dataIndex: 'lastErrorMessage',
      key: 'lastErrorMessage',
      render: (value: string) => value || '-',
    },
  ];

  const lockColumns = [
    {
      title: t('purviewManagement.tables.locks.jobKind'),
      dataIndex: 'jobKind',
      key: 'jobKind',
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('purviewManagement.tables.locks.locked'),
      dataIndex: 'locked',
      key: 'locked',
      render: (value: boolean) =>
        value ? <Tag color="warning">{t('common.enabled')}</Tag> : <Tag color="success">{t('common.disabled')}</Tag>,
    },
    {
      title: t('purviewManagement.tables.locks.ownerJobId'),
      dataIndex: 'ownerJobId',
      key: 'ownerJobId',
      render: (value: string) => <Text code>{value || '-'}</Text>,
    },
    {
      title: t('purviewManagement.tables.locks.lockedAt'),
      dataIndex: 'lockedAt',
      key: 'lockedAt',
      render: (value: string) => formatTimestamp(value),
    },
  ];

  const tombstoneColumns = [
    {
      title: t('purviewManagement.tables.tombstones.objectId'),
      dataIndex: 'objectId',
      key: 'objectId',
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('purviewManagement.tables.tombstones.typeName'),
      dataIndex: 'typeName',
      key: 'typeName',
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('purviewManagement.tables.tombstones.status'),
      dataIndex: 'status',
      key: 'status',
      render: (value: string) => renderStatusTag(value),
    },
    {
      title: t('purviewManagement.tables.tombstones.dueAt'),
      dataIndex: 'dueAt',
      key: 'dueAt',
      render: (value: string) => formatTimestamp(value),
    },
    {
      title: t('purviewManagement.tables.tombstones.qualifiedName'),
      dataIndex: 'qualifiedName',
      key: 'qualifiedName',
      render: (value: string) => <Text code>{value}</Text>,
    },
  ];

  const deadLetterColumns = [
    {
      title: t('purviewManagement.tables.deadLetters.streamKind'),
      dataIndex: 'streamKind',
      key: 'streamKind',
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('purviewManagement.tables.deadLetters.entryKey'),
      dataIndex: 'entryKey',
      key: 'entryKey',
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('purviewManagement.tables.deadLetters.failureCount'),
      dataIndex: 'failureCount',
      key: 'failureCount',
    },
    {
      title: t('purviewManagement.tables.deadLetters.lastFailedAt'),
      dataIndex: 'lastFailedAt',
      key: 'lastFailedAt',
      render: (value: string) => formatTimestamp(value),
    },
    {
      title: t('purviewManagement.tables.deadLetters.errorSummary'),
      dataIndex: 'errorSummary',
      key: 'errorSummary',
      render: (value: string) => value || '-',
    },
  ];

  const tableLocale = {
    emptyText: <Empty description={t('common.noData')} image={Empty.PRESENTED_IMAGE_SIMPLE} />,
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <DatabaseOutlined />
            {t('purviewManagement.title')}
          </Space>
        }
        extra={
          <Space wrap>
            <Button
              icon={<ReloadOutlined />}
              onClick={handleRefresh}
              loading={refreshing}
            >
              {t('common.reload')}
            </Button>
            <Button
              icon={<SafetyCertificateOutlined />}
              onClick={handleConnectionProbe}
              loading={runningAction === 'test-connection'}
            >
              {t('purviewManagement.actions.testConnection')}
            </Button>
          </Space>
        }
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {loadError && (
            <Alert
              type="error"
              message={t('purviewManagement.alerts.loadFailed')}
              description={loadError}
              showIcon
            />
          )}

          {connectionStatus && !connectionStatus.connected && (
            <Alert
              type="error"
              message={t('purviewManagement.alerts.connectionFailed')}
              description={connectionStatus.message}
              icon={<DisconnectOutlined />}
              showIcon
            />
          )}

          {connectionStatus && connectionStatus.connected && !connectionStatus.featureEnabled && (
            <Alert
              type="warning"
              message={t('purviewManagement.alerts.featureDisabled')}
              description={connectionStatus.message}
              showIcon
            />
          )}

          {schemaDiff?.applyRequired && (
            <Alert
              type="warning"
              message={t('purviewManagement.alerts.applyRequired')}
              description={t('purviewManagement.alerts.applyRequiredDescription')}
              icon={<WarningOutlined />}
              showIcon
            />
          )}

          {deadLetters.length > 0 && (
            <Alert
              type="warning"
              message={t('purviewManagement.alerts.deadLettersPresent')}
              description={t('purviewManagement.alerts.deadLettersPresentDescription', { count: deadLetters.length })}
              showIcon
            />
          )}

          {actionResult && (
            <Alert
              type={actionResult.kind === 'FAILED' ? 'error' : actionResult.kind === 'COMPLETED_WITH_ERRORS' || actionResult.kind === 'REJECTED' ? 'warning' : 'success'}
              message={t('purviewManagement.latestAction.title')}
              description={
                <Space direction="vertical" size={4}>
                  {actionResult.job && (
                    <Space wrap>
                      <Text code>{actionResult.job.jobId}</Text>
                      <Tag>{actionResult.job.jobKind}</Tag>
                      {renderStatusTag(actionResult.job.status)}
                    </Space>
                  )}
                  <Text>{actionResult.message}</Text>
                </Space>
              }
              showIcon
            />
          )}

          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('purviewManagement.stats.jobs')}
                  value={jobs.length}
                  valueStyle={{ fontSize: 24 }}
                />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('purviewManagement.stats.cursors')}
                  value={cursors.length}
                  valueStyle={{ fontSize: 24 }}
                />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('purviewManagement.stats.deadLetters')}
                  value={deadLetters.length}
                  valueStyle={{ fontSize: 24 }}
                />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('purviewManagement.stats.tombstones')}
                  value={tombstones.length}
                  valueStyle={{ fontSize: 24 }}
                />
              </Card>
            </Col>
          </Row>

          <Descriptions bordered size="small" column={{ xs: 1, lg: 2 }}>
            <Descriptions.Item label={t('purviewManagement.summary.repositoryId')}>
              <Text code>{repositoryId}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.collection')}>
              {overview?.collection || schemaDiff?.collection || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.connection')}>
              {connectionStatus?.connected ? (
                <Space>
                  <CheckCircleOutlined style={{ color: '#389e0d' }} />
                  {t('purviewManagement.summary.connectionHealthy')}
                </Space>
              ) : (
                <Space>
                  <DisconnectOutlined style={{ color: '#cf1322' }} />
                  {t('purviewManagement.summary.connectionUnavailable')}
                </Space>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.applyRequired')}>
              {schemaDiff?.applyRequired ? renderStatusTag('PENDING') : renderStatusTag('COMPLETED')}
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.endpoint')}>
              <Text code>{connectionStatus?.endpoint || '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.atlasBasePath')}>
              <Text code>{connectionStatus?.atlasBasePath || '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.schemaVersion')}>
              {overview?.schemaState?.schemaVersion || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.schemaHash')}>
              <Text code>{overview?.schemaState?.schemaHash || '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.lastAppliedAt')}>
              {formatTimestamp(overview?.schemaState?.lastAppliedAt)}
            </Descriptions.Item>
            <Descriptions.Item label={t('purviewManagement.summary.lastAppliedBy')}>
              {overview?.schemaState?.lastAppliedBy || '-'}
            </Descriptions.Item>
          </Descriptions>
        </Space>
      </Card>

      <Card
        title={t('purviewManagement.sections.schema')}
        extra={schemaDiff?.applyRequired ? renderStatusTag('PENDING') : renderStatusTag('COMPLETED')}
      >
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label={t('purviewManagement.schema.current')}>
            <Space wrap>
              <Text>{schemaDiff?.currentSchemaVersion || '-'}</Text>
              <Text code>{schemaDiff?.currentSchemaHash || '-'}</Text>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label={t('purviewManagement.schema.desired')}>
            <Space wrap>
              <Text>{schemaDiff?.desiredSchemaVersion || '-'}</Text>
              <Text code>{schemaDiff?.desiredSchemaHash || '-'}</Text>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label={t('purviewManagement.schema.customTypes')}>
            {renderTagList(schemaDiff?.customTypeNames || [])}
          </Descriptions.Item>
          <Descriptions.Item label={t('purviewManagement.schema.relationshipTypes')}>
            {renderTagList(schemaDiff?.relationshipTypeNames || [])}
          </Descriptions.Item>
          <Descriptions.Item label={t('purviewManagement.schema.businessMetadata')}>
            {renderTagList(schemaDiff?.businessMetadataNames || [])}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title={t('purviewManagement.sections.actions')}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <div>
            <Text strong>{t('purviewManagement.actionGroups.bootstrap')}</Text>
            <div style={{ marginTop: 8 }}>
              <Space wrap>
                <Button
                  type="primary"
                  onClick={() =>
                    runJobAction(
                      'apply-schema',
                      () => service.applyTypeDefinitions(),
                      'purviewManagement.messages.schemaApplied'
                    )
                  }
                  loading={runningAction === 'apply-schema'}
                >
                  {t('purviewManagement.actions.applyTypeDefinitions')}
                </Button>
              </Space>
            </div>
          </div>

          <div>
            <Text strong>{t('purviewManagement.actionGroups.sync')}</Text>
            <div style={{ marginTop: 8 }}>
              <Space wrap>
                <Button
                  type="primary"
                  icon={<SyncOutlined />}
                  onClick={() =>
                    runJobAction(
                      'full-sync',
                      () => service.startFullSync(repositoryId),
                      'purviewManagement.messages.fullSyncStarted'
                    )
                  }
                  loading={runningAction === 'full-sync'}
                >
                  {t('purviewManagement.actions.fullSync')}
                </Button>
                <Button
                  onClick={() =>
                    runJobAction(
                      'incremental-sync',
                      () => service.startIncrementalSync(repositoryId),
                      'purviewManagement.messages.incrementalSyncStarted'
                    )
                  }
                  loading={runningAction === 'incremental-sync'}
                >
                  {t('purviewManagement.actions.incrementalSync')}
                </Button>
              </Space>
            </div>
          </div>

          <div>
            <Text strong>{t('purviewManagement.actionGroups.reconciliation')}</Text>
            <div style={{ marginTop: 8 }}>
              <Space wrap>
                <Button
                  onClick={() =>
                    runJobAction(
                      'reconcile-types',
                      () => service.reconcileTypes(repositoryId),
                      'purviewManagement.messages.typeReconciliationStarted'
                    )
                  }
                  loading={runningAction === 'reconcile-types'}
                >
                  {t('purviewManagement.actions.reconcileTypes')}
                </Button>
                <Button
                  onClick={() =>
                    runJobAction(
                      'reconcile-archives',
                      () => service.reconcileArchives(repositoryId),
                      'purviewManagement.messages.archiveReconciliationStarted'
                    )
                  }
                  loading={runningAction === 'reconcile-archives'}
                >
                  {t('purviewManagement.actions.reconcileArchives')}
                </Button>
                <Button
                  onClick={() =>
                    runJobAction(
                      'reconcile-cloud',
                      () => service.reconcileCloudMetadata(repositoryId),
                      'purviewManagement.messages.cloudMetadataReconciliationStarted'
                    )
                  }
                  loading={runningAction === 'reconcile-cloud'}
                >
                  {t('purviewManagement.actions.reconcileCloudMetadata')}
                </Button>
                <Button
                  onClick={() =>
                    runJobAction(
                      'reconcile-containment',
                      () => service.reconcileContainment(repositoryId),
                      'purviewManagement.messages.containmentReconciliationStarted'
                    )
                  }
                  loading={runningAction === 'reconcile-containment'}
                >
                  {t('purviewManagement.actions.reconcileContainment')}
                </Button>
                <Button
                  onClick={() =>
                    runJobAction(
                      'resolve-deletes',
                      () => service.resolveDeletes(repositoryId),
                      'purviewManagement.messages.deleteResolutionStarted'
                    )
                  }
                  loading={runningAction === 'resolve-deletes'}
                >
                  {t('purviewManagement.actions.resolveDeletes')}
                </Button>
                <Button
                  onClick={() =>
                    runJobAction(
                      'retry-failed',
                      () => service.retryFailed(repositoryId),
                      'purviewManagement.messages.retryFailedStarted'
                    )
                  }
                  loading={runningAction === 'retry-failed'}
                >
                  {t('purviewManagement.actions.retryFailed')}
                </Button>
              </Space>
            </div>
          </div>
        </Space>
      </Card>

      <Card title={t('purviewManagement.sections.state')}>
        <Tabs
          items={[
            {
              key: 'jobs',
              label: t('purviewManagement.tabs.jobs'),
              children: (
                <Table<PurviewJobState>
                  columns={jobColumns}
                  dataSource={jobs}
                  rowKey={(record) => record.jobId}
                  size="small"
                  pagination={{ pageSize: 10 }}
                  locale={tableLocale}
                />
              ),
            },
            {
              key: 'cursors',
              label: t('purviewManagement.tabs.cursors'),
              children: (
                <Table<PurviewCursorState>
                  columns={cursorColumns}
                  dataSource={cursors}
                  rowKey={(record) => `${record.repositoryId}-${record.streamKind}`}
                  size="small"
                  pagination={{ pageSize: 10 }}
                  locale={tableLocale}
                />
              ),
            },
            {
              key: 'deadLetters',
              label: t('purviewManagement.tabs.deadLetters'),
              children: (
                <Table<PurviewDeadLetterState>
                  columns={deadLetterColumns}
                  dataSource={deadLetters}
                  rowKey={(record) => `${record.streamKind}-${record.entryKey}`}
                  size="small"
                  pagination={{ pageSize: 10 }}
                  locale={tableLocale}
                />
              ),
            },
            {
              key: 'tombstones',
              label: t('purviewManagement.tabs.tombstones'),
              children: (
                <Table<PurviewTombstoneState>
                  columns={tombstoneColumns}
                  dataSource={tombstones}
                  rowKey={(record) => `${record.objectId}-${record.changeToken}`}
                  size="small"
                  pagination={{ pageSize: 10 }}
                  locale={tableLocale}
                />
              ),
            },
            {
              key: 'locks',
              label: t('purviewManagement.tabs.locks'),
              children: (
                <Table<PurviewLockState>
                  columns={lockColumns}
                  dataSource={locks}
                  rowKey={(record) => `${record.repositoryId}-${record.jobKind}`}
                  size="small"
                  pagination={{ pageSize: 10 }}
                  locale={tableLocale}
                />
              ),
            },
          ]}
        />
      </Card>
    </Space>
  );
};
