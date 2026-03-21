import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Descriptions, Divider, Space, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { PurviewGovernanceService, PurviewGovernanceView } from '../../services/purviewGovernance';

interface PurviewGovernancePanelProps {
  repositoryId: string;
  objectId: string;
}

export const PurviewGovernancePanel: React.FC<PurviewGovernancePanelProps> = ({ repositoryId, objectId }) => {
  const { t } = useTranslation();
  const { handleAuthError } = useAuth();
  const governanceService = useMemo(
    () => new PurviewGovernanceService(() => handleAuthError(null)),
    [handleAuthError]
  );

  const [governance, setGovernance] = useState<PurviewGovernanceView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let disposed = false;

    const load = async () => {
      setLoading(true);
      setError(null);
      try {
        const response = await governanceService.getGovernance(repositoryId, objectId);
        if (!disposed) {
          setGovernance(response);
        }
      } catch (e) {
        if (!disposed) {
          setError(e instanceof Error ? e.message : t('documentViewer.purviewGovernance.loadFailed'));
        }
      } finally {
        if (!disposed) {
          setLoading(false);
        }
      }
    };

    load();
    return () => {
      disposed = true;
    };
  }, [governanceService, objectId, repositoryId]);

  if (loading) {
    return <div>{t('common.loading')}</div>;
  }

  if (error) {
    return <Alert type="error" showIcon message={t('documentViewer.purviewGovernance.loadFailed')} description={error} />;
  }

  if (!governance) {
    return <Alert type="info" showIcon message={t('documentViewer.purviewGovernance.empty')} />;
  }

  if (!governance.featureEnabled || !governance.available || !governance.supportedObjectType || !governance.entityFound) {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message={t('documentViewer.purviewGovernance.status')}
          description={governance.message || t('documentViewer.purviewGovernance.empty')}
        />
        {governance.qualifiedName && (
          <Descriptions bordered size="small" column={1}>
            <Descriptions.Item label={t('documentViewer.purviewGovernance.qualifiedName')}>
              <Typography.Text copyable>{governance.qualifiedName}</Typography.Text>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Space>
    );
  }

  const businessMetadataEntries = Object.entries(governance.businessMetadata || {});

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Descriptions bordered size="small" column={1}>
        <Descriptions.Item label={t('documentViewer.purviewGovernance.qualifiedName')}>
          <Typography.Text copyable>{governance.qualifiedName}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('documentViewer.purviewGovernance.entityType')}>
          {governance.entityTypeName}
        </Descriptions.Item>
        <Descriptions.Item label={t('documentViewer.purviewGovernance.atlasBasePath')}>
          {governance.atlasBasePath}
        </Descriptions.Item>
      </Descriptions>

      <div>
        <strong>{t('documentViewer.purviewGovernance.classifications')}</strong>
        <div style={{ marginTop: 8 }}>
          {governance.classifications.length > 0 ? (
            governance.classifications.map((classification) => (
              <Tag key={`${classification.typeName}-${classification.entityStatus}`} color="red">
                {classification.typeName}
              </Tag>
            ))
          ) : (
            <span>{t('documentViewer.purviewGovernance.none')}</span>
          )}
        </div>
      </div>

      <div>
        <strong>{t('documentViewer.purviewGovernance.glossaryTerms')}</strong>
        <div style={{ marginTop: 8 }}>
          {governance.glossaryTerms.length > 0 ? (
            governance.glossaryTerms.map((term) => (
              <Tag key={term.relationGuid || term.termGuid || term.displayText} color="blue">
                {term.displayText}
              </Tag>
            ))
          ) : (
            <span>{t('documentViewer.purviewGovernance.none')}</span>
          )}
        </div>
      </div>

      <div>
        <strong>{t('documentViewer.purviewGovernance.labels')}</strong>
        <div style={{ marginTop: 8 }}>
          {governance.labels.length > 0 ? (
            governance.labels.map((label) => (
              <Tag key={label}>{label}</Tag>
            ))
          ) : (
            <span>{t('documentViewer.purviewGovernance.none')}</span>
          )}
        </div>
      </div>

      <div>
        <strong>{t('documentViewer.purviewGovernance.businessMetadata')}</strong>
        {businessMetadataEntries.length > 0 ? (
          businessMetadataEntries.map(([name, attributes]) => (
            <div key={name}>
              <Divider orientation="left">{name}</Divider>
              <Descriptions bordered size="small" column={1}>
                {Object.entries(attributes).map(([attributeName, value]) => (
                  <Descriptions.Item key={`${name}-${attributeName}`} label={attributeName}>
                    {String(value)}
                  </Descriptions.Item>
                ))}
              </Descriptions>
            </div>
          ))
        ) : (
          <div style={{ marginTop: 8 }}>{t('documentViewer.purviewGovernance.none')}</div>
        )}
      </div>
    </Space>
  );
};
