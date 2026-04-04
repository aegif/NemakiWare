import { useState, useEffect, useCallback } from 'react';
import { Table, Button, Tag, Space, Card, App, Popconfirm, Tabs, Typography } from 'antd';
import { ReloadOutlined, DeleteOutlined, RedoOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  listIngestJobs, listDlqEntries, retryDlqEntry, deleteDlqEntry,
  type IngestJobRecord, type DlqEntry,
} from '../../services/externalIngest';

const { Text } = Typography;

const STATUS_COLORS: Record<string, string> = {
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
  PARTIAL: 'warning',
};

export function IngestJobsTab() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [jobs, setJobs] = useState<IngestJobRecord[]>([]);
  const [dlqEntries, setDlqEntries] = useState<DlqEntry[]>([]);
  const [loading, setLoading] = useState(false);

  const loadJobs = useCallback(async () => {
    setLoading(true);
    try {
      setJobs(await listIngestJobs(50));
    } catch { /* ignore */ } finally { setLoading(false); }
  }, []);

  const loadDlq = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listDlqEntries(100);
      setDlqEntries(result.entries || []);
    } catch { /* ignore */ } finally { setLoading(false); }
  }, []);

  useEffect(() => { loadJobs(); loadDlq(); }, [loadJobs, loadDlq]);

  const retryDlq = async (dlqId: string) => {
    try {
      const body = await retryDlqEntry(dlqId);
      if (body.status === 'success' || body.status === 'resolved') {
        message.success(t('ingestJobs.retrySuccess'));
        loadDlq();
      } else {
        message.error(body.errors?.[0] || t('ingestJobs.retryFailed'));
      }
    } catch {
      message.error(t('ingestJobs.retryFailed'));
    }
  };

  const handleDeleteDlq = async (dlqId: string) => {
    try {
      await deleteDlqEntry(dlqId);
      message.success(t('ingestJobs.deleteSuccess'));
      loadDlq();
    } catch {
      message.error(t('ingestJobs.deleteFailed'));
    }
  };

  const jobColumns = [
    { title: t('ingestJobs.columns.jobId'), dataIndex: 'jobId', key: 'jobId', width: 120 },
    { title: t('ingestJobs.columns.profileId'), dataIndex: 'profileId', key: 'profileId' },
    { title: t('ingestJobs.columns.status'), dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color={STATUS_COLORS[s] || 'default'}>{s}</Tag> },
    { title: t('ingestJobs.columns.fetched'), dataIndex: 'fetched', key: 'fetched', width: 80 },
    { title: t('ingestJobs.columns.imported'), dataIndex: 'imported', key: 'imported', width: 80 },
    { title: t('ingestJobs.columns.skipped'), dataIndex: 'skipped', key: 'skipped', width: 80 },
    { title: t('ingestJobs.columns.failed'), dataIndex: 'failed', key: 'failed', width: 80 },
    { title: t('ingestJobs.columns.startedAt'), dataIndex: 'startedAt', key: 'startedAt', width: 180 },
    { title: t('ingestJobs.columns.completedAt'), dataIndex: 'completedAt', key: 'completedAt', width: 180 },
  ];

  const dlqColumns = [
    { title: t('ingestJobs.dlq.columns.dlqId'), dataIndex: 'dlqId', key: 'dlqId', width: 120 },
    { title: t('ingestJobs.dlq.columns.sourceObjectId'), dataIndex: 'sourceObjectId', key: 'sourceObjectId' },
    { title: t('ingestJobs.dlq.columns.sourceObjectType'), dataIndex: 'sourceObjectType', key: 'sourceObjectType', width: 100 },
    { title: t('ingestJobs.dlq.columns.error'), dataIndex: 'errorMessage', key: 'errorMessage',
      ellipsis: true },
    { title: t('ingestJobs.dlq.columns.retryCount'), dataIndex: 'retryCount', key: 'retryCount', width: 80 },
    { title: t('ingestJobs.dlq.columns.failedAt'), dataIndex: 'failedAt', key: 'failedAt', width: 180 },
    { title: t('ingestJobs.dlq.columns.actions'), key: 'actions', width: 120,
      render: (_: unknown, record: DlqEntry) => (
        <Space size="small">
          <Button icon={<RedoOutlined />} size="small" onClick={() => retryDlq(record.dlqId)}
            title={t('ingestJobs.dlq.retry')} />
          <Popconfirm title={t('ingestJobs.dlq.deleteConfirm')}
            okText={t('common.delete')} cancelText={t('common.cancel')}
            onConfirm={() => handleDeleteDlq(record.dlqId)}>
            <Button icon={<DeleteOutlined />} size="small" danger />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card title={t('ingestJobs.title')}>
      <Tabs items={[
        {
          key: 'jobs',
          label: t('ingestJobs.tabs.history'),
          children: (
            <>
              <Button icon={<ReloadOutlined />} onClick={loadJobs} style={{ marginBottom: 16 }}>
                {t('common.refresh')}
              </Button>
              <Table columns={jobColumns} dataSource={jobs} rowKey="jobId"
                loading={loading} pagination={{ pageSize: 20 }} size="small" bordered />
            </>
          ),
        },
        {
          key: 'dlq',
          label: <>{t('ingestJobs.tabs.dlq')} {dlqEntries.length > 0 && <Tag color="error">{dlqEntries.length}</Tag>}</>,
          children: (
            <>
              <Button icon={<ReloadOutlined />} onClick={loadDlq} style={{ marginBottom: 16 }}>
                {t('common.refresh')}
              </Button>
              {dlqEntries.length === 0 ? (
                <Text type="secondary">{t('ingestJobs.dlq.empty')}</Text>
              ) : (
                <Table columns={dlqColumns} dataSource={dlqEntries} rowKey="dlqId"
                  loading={loading} pagination={{ pageSize: 20 }} size="small" bordered />
              )}
            </>
          ),
        },
      ]} />
    </Card>
  );
}
