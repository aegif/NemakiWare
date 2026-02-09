import React, { useState, useEffect } from 'react';
import {
  Table,
  Button,
  Space,
  message,
  Popconfirm,
  Card,
  Tooltip,
  Tabs,
  Descriptions,
  Badge,
  Tag,
  DatePicker,
  Modal,
  Empty
} from 'antd';
import {
  InboxOutlined,
  ReloadOutlined,
  FileOutlined,
  FolderOutlined,
  DownloadOutlined,
  EyeOutlined,
  SettingOutlined,
  HistoryOutlined,
  WarningOutlined,
  DeleteOutlined,
  CalendarOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { CMISService } from '../../services/cmis';
import { CMISObject, RetentionSettings, MigrationLog, PendingArchive } from '../../types/cmis';
import { useTranslation } from 'react-i18next';

interface ArchiveManagementProps {
  repositoryId: string;
}

import { useAuth } from '../../contexts/AuthContext';
export const ArchiveManagement: React.FC<ArchiveManagementProps> = ({ repositoryId }) => {
  const [archives, setArchives] = useState<CMISObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [retentionSettings, setRetentionSettings] = useState<RetentionSettings | null>(null);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [migrationLogs, setMigrationLogs] = useState<MigrationLog[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);
  const [pendingArchives, setPendingArchives] = useState<PendingArchive[]>([]);
  const [pendingLoading, setPendingLoading] = useState(false);
  const [isAdminUser, setIsAdminUser] = useState(true);
  const [extendModalVisible, setExtendModalVisible] = useState(false);
  const [extendTarget, setExtendTarget] = useState<PendingArchive | null>(null);
  const [extendDate, setExtendDate] = useState<any>(null);
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();

  const { handleAuthError } = useAuth();
  const cmisService = new CMISService(handleAuthError);

  useEffect(() => {
    loadArchives();
  }, [repositoryId]);

  const loadArchives = async () => {
    setLoading(true);
    try {
      const result = await cmisService.getArchives(repositoryId);
      setArchives(result.archives);
      setIsAdminUser(result.isAdmin);
    } catch (error) {
      message.error(t('archiveManagement.messages.loadError'));
    } finally {
      setLoading(false);
    }
  };

  const loadRetentionSettings = async () => {
    setSettingsLoading(true);
    try {
      const settings = await cmisService.getRetentionSettings(repositoryId);
      setRetentionSettings(settings);
    } catch (error) {
      message.error(t('archiveManagement.messages.settingsLoadError'));
    } finally {
      setSettingsLoading(false);
    }
  };

  const loadMigrationLogs = async () => {
    setLogsLoading(true);
    try {
      const logs = await cmisService.getMigrationLogs(repositoryId, 50);
      setMigrationLogs(logs);
    } catch (error) {
      message.error(t('archiveManagement.messages.logsLoadError'));
    } finally {
      setLogsLoading(false);
    }
  };

  const loadPendingArchives = async () => {
    setPendingLoading(true);
    try {
      const pending = await cmisService.getPendingArchives(repositoryId);
      setPendingArchives(pending);
    } catch (error) {
      console.error('Failed to load pending archives:', error);
      message.error(t('archiveManagement.messages.pendingLoadError'));
    } finally {
      setPendingLoading(false);
    }
  };

  const handleRestore = async (objectId: string) => {
    try {
      await cmisService.restoreObject(repositoryId, objectId);
      message.success(t('archiveManagement.messages.restoreSuccess'));
      loadArchives();
    } catch (error) {
      if (error instanceof Error && error.message === 'ERR_RESTORE_BECAUSE_PARENT_NO_LONGER_EXISTS') {
        message.error(t('archiveManagement.messages.restoreErrorParentNotFound'));
      } else {
        message.error(t('archiveManagement.messages.restoreError'));
      }
    }
  };

  const handleDownload = (archiveId: string) => {
    const url = cmisService.getArchiveDownloadUrl(repositoryId, archiveId);
    window.open(url, '_blank');
  };

  const handleForceArchive = async (objectId: string) => {
    try {
      await cmisService.forceArchive(repositoryId, objectId);
      message.success(t('archiveManagement.messages.forceArchiveSuccess'));
      loadPendingArchives();
      loadArchives();
    } catch (error) {
      message.error(t('archiveManagement.messages.forceArchiveError'));
    }
  };

  const handleExtendExpiration = async () => {
    if (!extendTarget || !extendDate) return;
    try {
      const isoDate = extendDate.toISOString();
      await cmisService.extendExpiration(repositoryId, extendTarget.id, isoDate);
      message.success(t('archiveManagement.messages.extendExpirationSuccess'));
      setExtendModalVisible(false);
      setExtendTarget(null);
      setExtendDate(null);
      loadPendingArchives();
    } catch (error) {
      message.error(t('archiveManagement.messages.extendExpirationError'));
    }
  };

  const handleTabChange = (key: string) => {
    if (key === 'settings' && !retentionSettings) {
      loadRetentionSettings();
    } else if (key === 'logs' && migrationLogs.length === 0) {
      loadMigrationLogs();
    } else if (key === 'pending' && pendingArchives.length === 0) {
      loadPendingArchives();
    }
  };

  const archiveColumns = [
    {
      title: t('archiveManagement.columns.type'),
      dataIndex: 'baseType',
      key: 'type',
      width: 60,
      render: (baseType: string) => (
        baseType === 'cmis:folder' ?
          <FolderOutlined style={{ color: '#1890ff', fontSize: '16px' }} /> :
          <FileOutlined style={{ color: '#52c41a', fontSize: '16px' }} />
      ),
    },
    {
      title: t('archiveManagement.columns.name'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('archiveManagement.columns.originalPath'),
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
    },
    {
      title: t('archiveManagement.columns.archiveState'),
      dataIndex: 'archiveState',
      key: 'archiveState',
      width: 140,
      render: (state: string) => {
        let color = 'default';
        if (state === 'ARCHIVED_LOCAL') color = 'green';
        else if (state === 'ARCHIVED_COLD') color = 'blue';
        else if (state === 'ARCHIVING' || state === 'COLD_MOVING') color = 'orange';
        return <Tag color={color}>{state}</Tag>;
      },
    },
    {
      title: t('archiveManagement.columns.archivedAt'),
      dataIndex: 'archivedAt',
      key: 'archivedAt',
      width: 180,
      render: (date: string) => date ? new Date(date).toLocaleString(i18n.language === 'ja' ? 'ja-JP' : 'en-US') : '-',
    },
    {
      title: t('archiveManagement.columns.coldMoveMode'),
      dataIndex: 'coldMoveMode',
      key: 'coldMoveMode',
      width: 100,
      render: (mode: string) => {
        if (!mode) return '-';
        return <Tag color={mode === 'COPY' ? 'blue' : 'orange'}>{mode}</Tag>;
      },
    },
    {
      title: t('archiveManagement.columns.size'),
      dataIndex: 'contentStreamLength',
      key: 'size',
      width: 100,
      render: (size: number) => {
        if (size == null || size < 0) return '-';
        if (size === 0) return '0 B';
        if (size < 1024) return `${size} B`;
        if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`;
        return `${(size / (1024 * 1024)).toFixed(1)} MB`;
      },
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 150,
      render: (_: any, record: CMISObject) => (
        <Space>
          <Tooltip title={t('archiveManagement.viewDetails')}>
            <Button
              icon={<EyeOutlined />}
              size="small"
              onClick={() => navigate(`/documents/${record.id}`)}
            />
          </Tooltip>
          {record.baseType === 'cmis:document' && (
            <Tooltip title={t('common.download')}>
              <Button
                icon={<DownloadOutlined />}
                size="small"
                onClick={() => handleDownload(record.id)}
              />
            </Tooltip>
          )}
          <Popconfirm
            title={t('archiveManagement.confirmRestore')}
            onConfirm={() => handleRestore(record.id)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Tooltip title={t('archiveManagement.restore')}>
              <Button
                icon={<ReloadOutlined />}
                size="small"
                type="primary"
              >
                {t('archiveManagement.restore')}
              </Button>
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const pendingColumns = [
    {
      title: t('archiveManagement.columns.name'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('archiveManagement.pendingArchives.expirationDate'),
      dataIndex: 'expirationDate',
      key: 'expirationDate',
      width: 180,
      render: (date: string) => date ? new Date(date).toLocaleString(i18n.language === 'ja' ? 'ja-JP' : 'en-US') : '-',
    },
    {
      title: t('archiveManagement.pendingArchives.lastModifiedBy'),
      dataIndex: 'lastModifiedBy',
      key: 'lastModifiedBy',
      width: 150,
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 280,
      render: (_: any, record: PendingArchive) => (
        <Space>
          <Popconfirm
            title={t('archiveManagement.pendingArchives.forceArchive') + '?'}
            onConfirm={() => handleForceArchive(record.id)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Button
              icon={<DeleteOutlined />}
              size="small"
              danger
            >
              {t('archiveManagement.pendingArchives.forceArchive')}
            </Button>
          </Popconfirm>
          <Button
            icon={<CalendarOutlined />}
            size="small"
            onClick={() => {
              setExtendTarget(record);
              setExtendModalVisible(true);
            }}
          >
            {t('archiveManagement.pendingArchives.extendExpiration')}
          </Button>
        </Space>
      ),
    },
  ];

  const logColumns = [
    {
      title: t('archiveManagement.migrationLogs.startedAt'),
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 180,
      render: (ts: number) => ts ? new Date(ts).toLocaleString(i18n.language === 'ja' ? 'ja-JP' : 'en-US') : '-',
    },
    {
      title: t('archiveManagement.migrationLogs.jobType'),
      dataIndex: 'jobType',
      key: 'jobType',
      width: 120,
    },
    {
      title: t('archiveManagement.migrationLogs.processed'),
      dataIndex: 'processed',
      key: 'processed',
      width: 80,
    },
    {
      title: t('archiveManagement.migrationLogs.succeeded'),
      dataIndex: 'succeeded',
      key: 'succeeded',
      width: 80,
    },
    {
      title: t('archiveManagement.migrationLogs.failed'),
      dataIndex: 'failed',
      key: 'failed',
      width: 80,
    },
    {
      title: t('archiveManagement.migrationLogs.status'),
      dataIndex: 'status',
      key: 'status',
      width: 140,
      render: (status: string) => {
        let color = 'default';
        if (status === 'SUCCESS') color = 'success';
        else if (status === 'PARTIAL_FAILURE') color = 'warning';
        else if (status === 'FAILURE') color = 'error';
        return <Tag color={color}>{status}</Tag>;
      },
    },
    {
      title: t('archiveManagement.migrationLogs.duration'),
      key: 'duration',
      width: 100,
      render: (_: any, record: MigrationLog) => {
        if (record.startedAt && record.completedAt) {
          const durationMs = record.completedAt - record.startedAt;
          if (durationMs < 1000) return `${durationMs}ms`;
          return `${(durationMs / 1000).toFixed(1)}s`;
        }
        return '-';
      },
    },
  ];

  const tabItems = [
    {
      key: 'archives',
      label: (
        <span>
          <InboxOutlined /> {t('archiveManagement.tabs.archives')}
        </span>
      ),
      children: (
        <Table
          columns={archiveColumns}
          dataSource={archives}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 20 }}
        />
      ),
    },
    ...(isAdminUser ? [
      {
        key: 'settings',
        label: (
          <span>
            <SettingOutlined /> {t('archiveManagement.tabs.settings')}
          </span>
        ),
        children: (
          <div>
            <Button
              icon={<ReloadOutlined />}
              onClick={loadRetentionSettings}
              loading={settingsLoading}
              style={{ marginBottom: 16 }}
            >
              {t('common.reload')}
            </Button>
            {retentionSettings && (
              <Descriptions bordered column={1}>
                <Descriptions.Item label={t('archiveManagement.settings.enabled')}>
                  <Badge
                    status={retentionSettings.enabled ? 'success' : 'default'}
                    text={retentionSettings.enabled ? t('archiveManagement.settings.enabledYes') : t('archiveManagement.settings.enabledNo')}
                  />
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.coldAfterDays')}>
                  {retentionSettings.coldAfterDays} {t('archiveManagement.settings.days')}
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.cronExpression')}>
                  <code>{retentionSettings.cronExpression || '-'}</code>
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.storageType')}>
                  <Tag color={retentionSettings.storageType === 's3' ? 'blue' : 'green'}>
                    {retentionSettings.storageType === 's3' ? 'S3' : t('archiveManagement.settings.filesystem')}
                  </Tag>
                  {retentionSettings.storageType === 's3' && (
                    <Tag color="orange" style={{ marginLeft: 4 }}>{t('archiveManagement.settings.beta')}</Tag>
                  )}
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.storageConnected')}>
                  <Badge
                    status={retentionSettings.storageConnected ? 'success' : 'error'}
                    text={retentionSettings.storageConnected ? t('archiveManagement.settings.connected') : t('archiveManagement.settings.disconnected')}
                  />
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.coldMoveMode')}>
                  <Tag color={retentionSettings.keepLocalCopy ? 'blue' : 'orange'}>
                    {retentionSettings.keepLocalCopy ? t('archiveManagement.settings.copyMode') : t('archiveManagement.settings.moveMode')}
                  </Tag>
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.localArchiveAfterDays')}>
                  {retentionSettings.localArchiveAfterDays || '-'} {retentionSettings.localArchiveAfterDays ? t('archiveManagement.settings.days') : ''}
                </Descriptions.Item>
                <Descriptions.Item label={t('archiveManagement.settings.localArchiveCron')}>
                  <code>{retentionSettings.localArchiveCron || '-'}</code>
                </Descriptions.Item>
              </Descriptions>
            )}
          </div>
        ),
      },
      {
        key: 'logs',
        label: (
          <span>
            <HistoryOutlined /> {t('archiveManagement.tabs.migrationLogs')}
          </span>
        ),
        children: (
          <div>
            <Button
              icon={<ReloadOutlined />}
              onClick={loadMigrationLogs}
              loading={logsLoading}
              style={{ marginBottom: 16 }}
            >
              {t('common.reload')}
            </Button>
            <Table
              columns={logColumns}
              dataSource={migrationLogs}
              rowKey="id"
              loading={logsLoading}
              pagination={{ pageSize: 20 }}
            />
          </div>
        ),
      },
      {
        key: 'pending',
        label: (
          <span>
            <WarningOutlined /> {t('archiveManagement.tabs.pendingArchives')}
            {pendingArchives.length > 0 && (
              <Badge count={pendingArchives.length} style={{ marginLeft: 8 }} />
            )}
          </span>
        ),
        children: (
          <div>
            <Button
              icon={<ReloadOutlined />}
              onClick={loadPendingArchives}
              loading={pendingLoading}
              style={{ marginBottom: 16 }}
            >
              {t('common.reload')}
            </Button>
            {pendingArchives.length > 0 ? (
              <>
                <div style={{ marginBottom: 16, color: '#faad14' }}>
                  <WarningOutlined /> {pendingArchives.length} {t('archiveManagement.pendingArchives.alert')}
                </div>
                <Table
                  columns={pendingColumns}
                  dataSource={pendingArchives}
                  rowKey="id"
                  loading={pendingLoading}
                  pagination={{ pageSize: 20 }}
                />
              </>
            ) : (
              <Empty description={t('archiveManagement.pendingArchives.noItems')} />
            )}
          </div>
        ),
      },
    ] : []),
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          <InboxOutlined /> {t('archiveManagement.title')}
        </h2>
      </div>

      <Tabs
        defaultActiveKey="archives"
        items={tabItems}
        onChange={handleTabChange}
      />

      <Modal
        title={t('archiveManagement.pendingArchives.extendExpiration')}
        open={extendModalVisible}
        onOk={handleExtendExpiration}
        onCancel={() => {
          setExtendModalVisible(false);
          setExtendTarget(null);
          setExtendDate(null);
        }}
        okButtonProps={{ disabled: !extendDate }}
      >
        {extendTarget && (
          <div>
            <p><strong>{extendTarget.name}</strong></p>
            <p>{t('archiveManagement.pendingArchives.newExpirationDate')}:</p>
            <DatePicker
              showTime
              style={{ width: '100%' }}
              onChange={(date) => setExtendDate(date ? date.toDate() : null)}
            />
          </div>
        )}
      </Modal>
    </Card>
  );
};
