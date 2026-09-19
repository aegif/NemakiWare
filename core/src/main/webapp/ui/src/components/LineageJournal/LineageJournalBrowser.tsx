import { useEffect, useState, useCallback } from 'react';
import { Alert, Button, Drawer, Select, Space, Table, Tag, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { displayProcessIdentity, getEvents, type LineageEventSummary } from '../../services/lineageJournal';
import LineageEventDetail from './LineageEventDetail';

export default function LineageJournalBrowser() {
  const { t } = useTranslation();
  const [events, setEvents] = useState<LineageEventSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 50 });
  const [processTypeFilter, setProcessTypeFilter] = useState<string | undefined>();
  const [selectedEvent, setSelectedEvent] = useState<LineageEventSummary | null>(null);
  const [undecodableRows, setUndecodableRows] = useState(0);

  const fetchEvents = useCallback(async () => {
    setLoading(true);
    try {
      const offset = (pagination.current - 1) * pagination.pageSize;
      const result = await getEvents({
        limit: pagination.pageSize,
        offset,
        processType: processTypeFilter,
      });
      setEvents(result.events || []);
      // Server uses limit+1 pattern: total is an estimate that ensures
      // the "next page" button appears when hasMore is true.
      setTotal(result.total ?? result.events?.length ?? 0);
      // Rows the server could not turn into events are NOT in this page, and it says so. Not
      // showing that leaves the table presenting itself as the whole of what the journal holds
      // — the substitution the server side was corrected for, arriving at the last step.
      setUndecodableRows(result.undecodableRows ?? 0);
    } catch {
      message.error(t('integrationSettings.lineage.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [pagination, processTypeFilter, t]);

  useEffect(() => { fetchEvents(); }, [fetchEvents]);

  const statusColor = (status: string) => {
    switch (status) {
      case 'PUBLISHED': return 'green';
      case 'PENDING': return 'blue';
      case 'PROJECTING': return 'orange';
      case 'FAILED': return 'red';
      case 'DISCARDED': return 'default';
      case 'SKIPPED': return 'default';
      default: return 'default';
    }
  };

  const columns = [
    {
      title: t('integrationSettings.lineage.eventKey'),
      key: 'processIdentity',
      render: (_: unknown, record: LineageEventSummary) => displayProcessIdentity(record),
      ellipsis: true,
      width: 200,
    },
    {
      title: t('integrationSettings.lineage.processType'),
      dataIndex: 'processType',
      key: 'processType',
      width: 180,
    },
    {
      title: t('integrationSettings.lineage.repository'),
      dataIndex: 'repositoryId',
      key: 'repositoryId',
      width: 120,
    },
    {
      title: t('integrationSettings.lineage.occurredAt'),
      dataIndex: 'occurredAt',
      key: 'occurredAt',
      width: 200,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    {
      title: t('integrationSettings.lineage.status'),
      key: 'status',
      width: 200,
      render: (_: unknown, record: LineageEventSummary) => (
        <Space wrap>
          {Object.entries(record.publishStatusByTarget || {}).map(([target, status]) => (
            <Tag color={statusColor(status)} key={target}>
              {target}: {status}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 80,
      render: (_: unknown, record: LineageEventSummary) => (
        <Button size="small" type="link" onClick={() => setSelectedEvent(record)}>
          {t('integrationSettings.lineage.detail')}
        </Button>
      ),
    },
  ];

  return (
    <>
      {undecodableRows > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('integrationSettings.lineage.undecodableRows', { count: undecodableRows })}
        />
      )}
      <Space style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder={t('integrationSettings.lineage.filterProcessType')}
          style={{ width: 200 }}
          onChange={(v) => {
            setProcessTypeFilter(v);
            setPagination((prev) => ({ ...prev, current: 1 }));
          }}
          options={[
            'ARCHIVE_COLD', 'ARCHIVE_LOCAL',
            'CLOUD_SYNC_UPLOAD', 'CLOUD_SYNC_DOWNLOAD',
            'IMPORT_FILESYSTEM', 'IMPORT_UPLOADED',
            'EXPORT_FILESYSTEM', 'EXPORT_ZIP_FOLDER', 'EXPORT_SELECTED_OBJECTS',
          ].map((pt) => ({ label: pt, value: pt }))}
        />
        <Button icon={<ReloadOutlined />} onClick={fetchEvents} loading={loading}>
          {t('common.refresh', 'Refresh')}
        </Button>
      </Space>

      <Table
        dataSource={events}
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
      />

      <Drawer
        title={t('integrationSettings.lineage.detail')}
        open={!!selectedEvent}
        onClose={() => setSelectedEvent(null)}
        width={600}
      >
        {selectedEvent && <LineageEventDetail event={selectedEvent} />}
      </Drawer>
    </>
  );
}
