import React from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Row,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  DatabaseOutlined,
  DisconnectOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type {
  PurviewConnectionStatus,
  PurviewSchemaDiff,
  PurviewStateOverview,
} from '../../services/purviewAdmin';
import type { ActionResult } from './purviewUtils';
import { formatTimestamp } from './purviewUtils';
import { renderStatusTag } from './purviewRenderers';

const { Text } = Typography;

interface ConnectionCardProps {
  repositoryId: string;
  overview: PurviewStateOverview | null;
  schemaDiff: PurviewSchemaDiff | null;
  connectionStatus: PurviewConnectionStatus | null;
  loadError: string | null;
  refreshing: boolean;
  runningAction: string | null;
  actionResult: ActionResult | null;
  jobCount: number;
  cursorCount: number;
  deadLetterCount: number;
  tombstoneCount: number;
  onRefresh: () => void;
  onConnectionProbe: () => void;
}

export const ConnectionCard: React.FC<ConnectionCardProps> = ({
  repositoryId,
  overview,
  schemaDiff,
  connectionStatus,
  loadError,
  refreshing,
  runningAction,
  actionResult,
  jobCount,
  cursorCount,
  deadLetterCount,
  tombstoneCount,
  onRefresh,
  onConnectionProbe,
}) => {
  const { t } = useTranslation();

  return (
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
            onClick={onRefresh}
            loading={refreshing}
          >
            {t('common.reload')}
          </Button>
          <Button
            icon={<SafetyCertificateOutlined />}
            onClick={onConnectionProbe}
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

        {deadLetterCount > 0 && (
          <Alert
            type="warning"
            message={t('purviewManagement.alerts.deadLettersPresent')}
            description={t('purviewManagement.alerts.deadLettersPresentDescription', { count: deadLetterCount })}
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
                value={jobCount}
                valueStyle={{ fontSize: 24 }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card size="small">
              <Statistic
                title={t('purviewManagement.stats.cursors')}
                value={cursorCount}
                valueStyle={{ fontSize: 24 }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card size="small">
              <Statistic
                title={t('purviewManagement.stats.deadLetters')}
                value={deadLetterCount}
                valueStyle={{ fontSize: 24 }}
              />
            </Card>
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <Card size="small">
              <Statistic
                title={t('purviewManagement.stats.tombstones')}
                value={tombstoneCount}
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
  );
};
