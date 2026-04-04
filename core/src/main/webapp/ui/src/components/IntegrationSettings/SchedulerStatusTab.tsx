import { useState, useEffect, useCallback } from 'react';
import { Card, Table, Button, Tag, Space, App, Typography } from 'antd';
import { PlayCircleOutlined, PauseCircleOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getSchedulerStatus, triggerProfile,
  startIdleMonitoring, stopIdleMonitoring,
} from '../../services/externalIngest';

const { Text } = Typography;

interface ScheduledProfile {
  profileId: string;
  repositoryId: string;
  displayName?: string;
  connectorId?: string;
  connectorSystem?: string;
  ready: boolean;
}

export function SchedulerStatusTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [profiles, setProfiles] = useState<ScheduledProfile[]>([]);
  const [idleProfiles, setIdleProfiles] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const status = await getSchedulerStatus();
      setProfiles((status.scheduledProfiles ?? []) as ScheduledProfile[]);
      setIdleProfiles((status as Record<string, unknown>).idleProfiles as string[] ?? []);
    } catch { /* ignore */ } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleTrigger = async (profileId: string) => {
    try {
      const result = await triggerProfile(profileId);
      if (result.status === 'success' || result.status === 'partial') {
        message.success(t('schedulerStatus.triggerSuccess'));
      } else {
        message.error(String(result.errors ?? result.message ?? t('schedulerStatus.triggerFailed')));
      }
    } catch {
      message.error(t('schedulerStatus.triggerFailed'));
    }
  };

  const handleStartIdle = async (profileId: string) => {
    try {
      const result = await startIdleMonitoring(profileId);
      if (result.status === 'success') {
        message.success(t('schedulerStatus.idleStarted'));
        load();
      } else {
        message.error(result.message || t('schedulerStatus.idleFailed'));
      }
    } catch {
      message.error(t('schedulerStatus.idleFailed'));
    }
  };

  const handleStopIdle = async (profileId: string) => {
    try {
      const result = await stopIdleMonitoring(profileId);
      if (result.status === 'success') {
        message.success(t('schedulerStatus.idleStopped'));
        load();
      } else {
        message.error(result.message);
      }
    } catch {
      message.error(t('schedulerStatus.idleFailed'));
    }
  };

  const columns = [
    { title: t('schedulerStatus.columns.profileId'), dataIndex: 'profileId', key: 'profileId' },
    { title: t('schedulerStatus.columns.displayName'), dataIndex: 'displayName', key: 'displayName' },
    { title: t('schedulerStatus.columns.connector'), dataIndex: 'connectorSystem', key: 'connectorSystem',
      render: (s: string, r: ScheduledProfile) => s ? `${s} (${r.connectorId})` : '-' },
    { title: t('schedulerStatus.columns.ready'), dataIndex: 'ready', key: 'ready', width: 80,
      render: (v: boolean) => <Tag color={v ? 'green' : 'red'}>{v ? t('common.on') : t('common.off')}</Tag> },
    { title: t('schedulerStatus.columns.idle'), key: 'idle', width: 80,
      render: (_: unknown, r: ScheduledProfile) =>
        <Tag color={idleProfiles.includes(r.profileId) ? 'blue' : 'default'}>
          {idleProfiles.includes(r.profileId) ? 'IDLE' : '-'}
        </Tag>
    },
    { title: t('schedulerStatus.columns.actions'), key: 'actions', width: 200,
      render: (_: unknown, record: ScheduledProfile) => (
        <Space size="small">
          <Button icon={<ThunderboltOutlined />} size="small"
            disabled={!record.ready}
            onClick={() => handleTrigger(record.profileId)}
            title={t('schedulerStatus.trigger')}>
            {t('schedulerStatus.trigger')}
          </Button>
          {record.connectorSystem === 'imap' && !idleProfiles.includes(record.profileId) && (
            <Button icon={<PlayCircleOutlined />} size="small" type="primary" ghost
              onClick={() => handleStartIdle(record.profileId)}>
              IDLE
            </Button>
          )}
          {idleProfiles.includes(record.profileId) && (
            <Button icon={<PauseCircleOutlined />} size="small" danger
              onClick={() => handleStopIdle(record.profileId)}>
              {t('schedulerStatus.stopIdle')}
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('schedulerStatus.title')}>
      <Button icon={<ReloadOutlined />} onClick={load} style={{ marginBottom: 16 }}>
        {t('common.refresh')}
      </Button>
      {profiles.length === 0 ? (
        <Text type="secondary">{t('schedulerStatus.noProfiles')}</Text>
      ) : (
        <Table columns={columns} dataSource={profiles} rowKey="profileId"
          loading={loading} pagination={false} size="small" bordered />
      )}
    </Card>
  );
}
