import React from 'react';
import { Card, Table, Tabs, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type {
  PurviewCursorState,
  PurviewDeadLetterState,
  PurviewJobState,
  PurviewLockState,
  PurviewTombstoneState,
} from '../../services/purviewAdmin';
import { formatTimestamp } from './purviewUtils';
import { renderStatusTag, tableLocaleFactory } from './purviewRenderers';

const { Text } = Typography;

interface StateTablesCardProps {
  jobs: PurviewJobState[];
  cursors: PurviewCursorState[];
  deadLetters: PurviewDeadLetterState[];
  tombstones: PurviewTombstoneState[];
  locks: PurviewLockState[];
}

export const StateTablesCard: React.FC<StateTablesCardProps> = ({
  jobs,
  cursors,
  deadLetters,
  tombstones,
  locks,
}) => {
  const { t } = useTranslation();
  const tableLocale = tableLocaleFactory(t);

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

  return (
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
  );
};
