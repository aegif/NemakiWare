import React, { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Space,
  Table,
  Typography,
} from 'antd';
import { useTranslation } from 'react-i18next';
import type { PurviewGovernanceBulkItemView } from '../../services/purviewGovernance';
import { parseGovernanceLookupObjectIds } from './purviewUtils';
import { renderStatusTag, renderTagList, tableLocaleFactory } from './purviewRenderers';

const { Text } = Typography;

interface GovernanceLookupCardProps {
  repositoryId: string;
  runningAction: string | null;
  onLookup: (repositoryId: string, objectIds: string[]) => Promise<PurviewGovernanceBulkItemView[]>;
}

export const GovernanceLookupCard: React.FC<GovernanceLookupCardProps> = ({
  repositoryId,
  runningAction,
  onLookup,
}) => {
  const { t } = useTranslation();
  const [governanceLookupInput, setGovernanceLookupInput] = useState('');
  const [governanceLookupResults, setGovernanceLookupResults] = useState<PurviewGovernanceBulkItemView[]>([]);
  const [selectedGovernanceObjectId, setSelectedGovernanceObjectId] = useState<string | null>(null);
  const [governanceLookupError, setGovernanceLookupError] = useState<string | null>(null);

  const governanceLookupObjectIds = useMemo(
    () => parseGovernanceLookupObjectIds(governanceLookupInput),
    [governanceLookupInput]
  );

  const selectedGovernanceLookupResult = useMemo(
    () =>
      governanceLookupResults.find((item) => item.objectId === selectedGovernanceObjectId)
      || governanceLookupResults[0]
      || null,
    [governanceLookupResults, selectedGovernanceObjectId]
  );

  const governanceLookupSuccessCount = useMemo(
    () => governanceLookupResults.filter((item) => item.status === 'OK').length,
    [governanceLookupResults]
  );

  const tableLocale = tableLocaleFactory(t);

  const handleGovernanceLookup = async () => {
    if (governanceLookupObjectIds.length === 0) {
      return;
    }

    setGovernanceLookupError(null);
    try {
      const result = await onLookup(repositoryId, governanceLookupObjectIds);
      setGovernanceLookupResults(result);
      setSelectedGovernanceObjectId(
        result.find((item) => item.status === 'OK')?.objectId || result[0]?.objectId || null
      );
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : t('common.unknownError');
      setGovernanceLookupResults([]);
      setSelectedGovernanceObjectId(null);
      setGovernanceLookupError(errorMessage);
    }
  };

  const governanceLookupColumns = [
    {
      title: t('purviewManagement.governanceLookup.objectId'),
      dataIndex: 'objectId',
      key: 'objectId',
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('purviewManagement.governanceLookup.status'),
      dataIndex: 'status',
      key: 'status',
      render: (value: string) => renderStatusTag(value),
    },
    {
      title: t('purviewManagement.governanceLookup.entityType'),
      dataIndex: 'entityTypeName',
      key: 'entityTypeName',
      render: (value?: string) => value || '-',
    },
    {
      title: t('purviewManagement.governanceLookup.message'),
      dataIndex: 'message',
      key: 'message',
      render: (value?: string) => value || '-',
    },
  ];

  return (
    <Card title={t('purviewManagement.sections.governanceLookup')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap style={{ width: '100%' }}>
          <Input.TextArea
            value={governanceLookupInput}
            onChange={(event) => setGovernanceLookupInput(event.target.value)}
            placeholder={t('purviewManagement.governanceLookup.objectIdPlaceholder')}
            autoSize={{ minRows: 2, maxRows: 5 }}
            style={{ minWidth: 320 }}
          />
          <Button
            type="primary"
            onClick={() => void handleGovernanceLookup()}
            loading={runningAction === 'lookup-governance'}
            disabled={governanceLookupObjectIds.length === 0}
          >
            {t('purviewManagement.actions.lookupGovernance')}
          </Button>
        </Space>

        {governanceLookupError && (
          <Alert
            type="error"
            message={t('purviewManagement.governanceLookup.failed')}
            description={governanceLookupError}
            showIcon
          />
        )}

        {governanceLookupResults.length > 0 && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Alert
              type={governanceLookupSuccessCount === governanceLookupResults.length ? 'success' : 'info'}
              message={t('purviewManagement.governanceLookup.resultSummary', {
                count: governanceLookupResults.length,
                successCount: governanceLookupSuccessCount,
              })}
              showIcon
            />

            <Table<PurviewGovernanceBulkItemView>
              columns={governanceLookupColumns}
              dataSource={governanceLookupResults}
              rowKey={(record) => record.objectId}
              size="small"
              pagination={false}
              locale={tableLocale}
              rowSelection={{
                type: 'radio',
                selectedRowKeys: selectedGovernanceObjectId ? [selectedGovernanceObjectId] : [],
                onChange: (selectedRowKeys) =>
                  setSelectedGovernanceObjectId(selectedRowKeys[0] ? String(selectedRowKeys[0]) : null),
              }}
            />

            {selectedGovernanceLookupResult && (
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.objectId')}>
                  <Text code>{selectedGovernanceLookupResult.objectId}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.qualifiedName')}>
                  <Text code>{selectedGovernanceLookupResult.qualifiedName || '-'}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.entityType')}>
                  {selectedGovernanceLookupResult.entityTypeName || '-'}
                </Descriptions.Item>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.message')}>
                  {selectedGovernanceLookupResult.message || '-'}
                </Descriptions.Item>
              </Descriptions>
            )}

            {selectedGovernanceLookupResult?.status === 'OK' && (
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.classifications')}>
                  {renderTagList(
                    (selectedGovernanceLookupResult.classifications ?? []).map(
                      (classification) => classification.typeName
                    )
                  )}
                </Descriptions.Item>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.glossaryTerms')}>
                  {renderTagList(
                    (selectedGovernanceLookupResult.glossaryTerms ?? []).map((term) => term.displayText)
                  )}
                </Descriptions.Item>
                <Descriptions.Item label={t('purviewManagement.governanceLookup.labels')}>
                  {renderTagList(selectedGovernanceLookupResult.labels ?? [])}
                </Descriptions.Item>
              </Descriptions>
            )}

            {selectedGovernanceLookupResult?.status === 'OK'
              && Object.keys(selectedGovernanceLookupResult.businessMetadata || {}).length > 0 && (
              <Descriptions bordered size="small" column={1}>
                {Object.entries(selectedGovernanceLookupResult.businessMetadata || {}).map(([name, attributes]) => (
                  <Descriptions.Item key={name} label={name}>
                    <Space direction="vertical" size={4}>
                      {Object.entries(attributes).map(([attributeName, value]) => (
                        <Text key={`${name}-${attributeName}`}>
                          {attributeName}: {String(value)}
                        </Text>
                      ))}
                    </Space>
                  </Descriptions.Item>
                ))}
              </Descriptions>
            )}
          </Space>
        )}
      </Space>
    </Card>
  );
};
