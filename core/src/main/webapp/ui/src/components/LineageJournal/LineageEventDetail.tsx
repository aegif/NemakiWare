import { Descriptions, Tag, Typography, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { displayProcessIdentity, type LineageEventSummary } from '../../services/lineageJournal';

const { Text } = Typography;

interface Props {
  event: LineageEventSummary;
}

export default function LineageEventDetail({ event }: Props) {
  const { t } = useTranslation();

  return (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label={t('integrationSettings.lineage.eventId')}>
        <Text copyable>{event.eventId}</Text>
      </Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.eventKey')}>
        <Text copyable>{displayProcessIdentity(event)}</Text>
      </Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.repository')}>{event.repositoryId}</Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.processType')}>{event.processType || '-'}</Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.occurredAt')}>
        {event.occurredAt ? new Date(event.occurredAt).toLocaleString() : '-'}
      </Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.inputs')}>
        {event.inputs?.length ? event.inputs.join(', ') : '-'}
      </Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.outputs')}>
        {event.outputs?.length ? event.outputs.join(', ') : '-'}
      </Descriptions.Item>
      <Descriptions.Item label={t('integrationSettings.lineage.statusByTarget')}>
        <Space direction="vertical">
          {Object.entries(event.publishStatusByTarget || {}).map(([target, status]) => (
            <Tag key={target} color={status === 'PUBLISHED' ? 'green' : status === 'FAILED' ? 'red' : 'blue'}>
              {target}: {status}
            </Tag>
          ))}
        </Space>
      </Descriptions.Item>
    </Descriptions>
  );
}
