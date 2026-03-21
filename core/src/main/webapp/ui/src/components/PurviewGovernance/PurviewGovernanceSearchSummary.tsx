import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Space, Spin, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { PurviewGovernanceBulkItemView, PurviewGovernanceService } from '../../services/purviewGovernance';
import type { CMISObject } from '../../types/cmis';

const { Text } = Typography;

interface PurviewGovernanceSearchSummaryProps {
  repositoryId: string;
  objects: CMISObject[];
}

const collectUniqueObjectIds = (objects: CMISObject[]): string[] => {
  const objectIds = new Set<string>();
  for (const object of objects) {
    if ((object.baseType === 'cmis:document' || object.baseType === 'cmis:folder') && object.id) {
      objectIds.add(object.id);
    }
  }
  return Array.from(objectIds);
};

const collectTopValues = (
  items: PurviewGovernanceBulkItemView[],
  selector: (item: PurviewGovernanceBulkItemView) => string[] | undefined
): string[] => {
  const values = new Set<string>();
  for (const item of items) {
    for (const value of selector(item) ?? []) {
      if (value) {
        values.add(value);
      }
      if (values.size >= 5) {
        return Array.from(values);
      }
    }
  }
  return Array.from(values);
};

export const PurviewGovernanceSearchSummary: React.FC<PurviewGovernanceSearchSummaryProps> = ({
  repositoryId,
  objects,
}) => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const governanceService = useMemo(
    () => new PurviewGovernanceService(() => handleAuthError(null)),
    [handleAuthError]
  );
  const [items, setItems] = useState<PurviewGovernanceBulkItemView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const objectIds = useMemo(() => collectUniqueObjectIds(objects), [objects]);

  useEffect(() => {
    let active = true;

    const load = async () => {
      if (objectIds.length === 0) {
        setItems([]);
        setError(null);
        setLoading(false);
        return;
      }

      setLoading(true);
      setError(null);
      try {
        const response = await governanceService.getGovernanceBulk(repositoryId, objectIds);
        if (active) {
          setItems(response);
        }
      } catch (e) {
        if (active) {
          setItems([]);
          setError(e instanceof Error ? e.message : t('common.unknownError'));
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };

    void load();
    return () => {
      active = false;
    };
  }, [governanceService, objectIds, repositoryId, t]);

  const syncedCount = useMemo(
    () => items.filter((item) => item.status === 'OK' && item.entityFound).length,
    [items]
  );
  const classifiedCount = useMemo(
    () => items.filter((item) => item.status === 'OK' && (item.classifications?.length ?? 0) > 0).length,
    [items]
  );
  const glossaryCount = useMemo(
    () => items.filter((item) => item.status === 'OK' && (item.glossaryTerms?.length ?? 0) > 0).length,
    [items]
  );
  const labeledCount = useMemo(
    () => items.filter((item) => item.status === 'OK' && (item.labels?.length ?? 0) > 0).length,
    [items]
  );
  const unresolvedCount = useMemo(
    () => items.filter((item) => item.status !== 'OK' || !item.entityFound).length,
    [items]
  );
  const topClassificationNames = useMemo(
    () => collectTopValues(items, (item) => item.classifications?.map((classification) => classification.typeName)),
    [items]
  );
  const topGlossaryTerms = useMemo(
    () => collectTopValues(items, (item) => item.glossaryTerms?.map((term) => term.displayText)),
    [items]
  );

  if (objectIds.length === 0) {
    return null;
  }

  return (
    <Card size="small" title={t('searchResults.purviewGovernance.title')} style={{ marginBottom: 16 }}>
      {loading ? (
        <Space>
          <Spin size="small" />
          <Text type="secondary">{t('searchResults.purviewGovernance.loading')}</Text>
        </Space>
      ) : error ? (
        <Alert
          type="warning"
          showIcon
          message={t('searchResults.purviewGovernance.failed')}
          description={error}
        />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space size={[8, 8]} wrap>
            <Tag color="blue">{t('searchResults.purviewGovernance.scanned', { count: objectIds.length })}</Tag>
            <Tag color="green">{t('searchResults.purviewGovernance.synced', { count: syncedCount })}</Tag>
            <Tag color="purple">{t('searchResults.purviewGovernance.classified', { count: classifiedCount })}</Tag>
            <Tag color="geekblue">{t('searchResults.purviewGovernance.glossary', { count: glossaryCount })}</Tag>
            <Tag color="gold">{t('searchResults.purviewGovernance.labeled', { count: labeledCount })}</Tag>
            {unresolvedCount > 0 && (
              <Tag color="warning">
                {t('searchResults.purviewGovernance.unresolved', { count: unresolvedCount })}
              </Tag>
            )}
          </Space>

          {topClassificationNames.length > 0 && (
            <Space direction="vertical" size={4}>
              <Text strong>{t('searchResults.purviewGovernance.topClassifications')}</Text>
              <Space size={[4, 8]} wrap>
                {topClassificationNames.map((name) => (
                  <Tag key={name}>{name}</Tag>
                ))}
              </Space>
            </Space>
          )}

          {topGlossaryTerms.length > 0 && (
            <Space direction="vertical" size={4}>
              <Text strong>{t('searchResults.purviewGovernance.topGlossaryTerms')}</Text>
              <Space size={[4, 8]} wrap>
                {topGlossaryTerms.map((name) => (
                  <Tag key={name}>{name}</Tag>
                ))}
              </Space>
            </Space>
          )}
        </Space>
      )}
    </Card>
  );
};
