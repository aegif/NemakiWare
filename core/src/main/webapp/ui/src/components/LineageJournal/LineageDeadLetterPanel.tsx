import { useEffect, useState, useCallback } from 'react';
import { Table, Tag, Button, Space, message, Popconfirm } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  getDeadLetters,
  replayDeadLetter,
  replayAllDeadLetters,
  type DeadLetterRecord,
} from '../../services/lineageJournal';

export default function LineageDeadLetterPanel() {
  const { t } = useTranslation();
  const [records, setRecords] = useState<DeadLetterRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20 });

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const offset = (pagination.current - 1) * pagination.pageSize;
      const result = await getDeadLetters({ limit: pagination.pageSize, offset });
      setRecords(result.deadLetters || []);
      setTotal(result.total ?? result.deadLetters?.length ?? 0);
    } catch {
      message.error(t('integrationSettings.lineage.loadDeadLettersFailed'));
    } finally {
      setLoading(false);
    }
  }, [pagination, t]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const handleReplay = async (eventId: string) => {
    try {
      const result = await replayDeadLetter(eventId);
      if (result.status === 'ok') {
        message.success(t('integrationSettings.lineage.deadLetterReplaySuccess'));
        fetchData();
      } else {
        message.error(t('integrationSettings.lineage.deadLetterReplayFailed'));
      }
    } catch {
      message.error(t('integrationSettings.lineage.deadLetterReplayFailed'));
    }
  };

  const handleReplayAll = async () => {
    try {
      const result = await replayAllDeadLetters();
      message.success(t('integrationSettings.lineage.replayedCount', { count: result.replayed }));
      fetchData();
    } catch {
      message.error(t('integrationSettings.lineage.deadLetterReplayFailed'));
    }
  };

  const columns = [
    {
      title: t('integrationSettings.lineage.eventId'),
      dataIndex: 'eventId',
      key: 'eventId',
      ellipsis: true,
      width: 200,
    },
    {
      title: t('integrationSettings.lineage.deadLetterReason'),
      dataIndex: 'reason',
      key: 'reason',
      ellipsis: true,
    },
    {
      title: t('integrationSettings.lineage.deadLetterRecordedAt'),
      dataIndex: 'recordedAt',
      key: 'recordedAt',
      width: 200,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    {
      title: t('integrationSettings.lineage.status'),
      dataIndex: 'replayed',
      key: 'replayed',
      width: 120,
      render: (replayed: boolean) => (
        <Tag color={replayed ? 'green' : 'orange'}>
          {replayed
            ? t('integrationSettings.lineage.deadLetterReplayed')
            : t('integrationSettings.lineage.deadLetterNotReplayed')}
        </Tag>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_: unknown, record: DeadLetterRecord) => (
        !record.replayed ? (
          <Button size="small" type="primary" onClick={() => handleReplay(record.eventId)}>
            {t('integrationSettings.lineage.deadLetterReplay')}
          </Button>
        ) : null
      ),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={fetchData} loading={loading}>
          {t('common.refresh', 'Refresh')}
        </Button>
        <Popconfirm title={t('integrationSettings.lineage.replayAllConfirm')} onConfirm={handleReplayAll}>
          <Button type="primary">
            {t('integrationSettings.lineage.deadLetterReplayAll')}
          </Button>
        </Popconfirm>
      </Space>

      <Table
        dataSource={records}
        columns={columns}
        rowKey="eventId"
        loading={loading}
        size="small"
        pagination={{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total,
          onChange: (page, pageSize) => setPagination({ current: page, pageSize }),
          showSizeChanger: true,
        }}
        locale={{ emptyText: t('integrationSettings.lineage.deadLetterEmpty') }}
      />
    </>
  );
}
