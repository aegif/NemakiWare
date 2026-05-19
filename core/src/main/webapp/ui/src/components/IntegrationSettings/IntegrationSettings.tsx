import { Alert, Space, Tabs, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../contexts/AuthContext';
import { OidcSettingsTab } from './OidcSettingsTab';
import { GoogleAuthSettingsTab } from './GoogleAuthSettingsTab';
import { MicrosoftAuthSettingsTab } from './MicrosoftAuthSettingsTab';
import { SamlSettingsTab } from './SamlSettingsTab';
import { DirectorySyncSettingsTab } from './DirectorySyncSettingsTab';
import { PurviewSettingsTab } from './PurviewSettingsTab';
import { AtlasSettingsTab } from './AtlasSettingsTab';
import { DataplexSettingsTab } from './DataplexSettingsTab';
import { LineageSettingsTab } from './LineageSettingsTab';
import PropertyMappingSection from './PropertyMappingSection';
import { ConnectorManagementTab } from './ConnectorManagementTab';
import { ConnectorGovernanceTab } from './ConnectorGovernanceTab';
import { ImportProfileManagementTab } from './ImportProfileManagementTab';
import { ManualIngestTab } from './ManualIngestTab';
import { IngestJobsTab } from './IngestJobsTab';
import { SchedulerStatusTab } from './SchedulerStatusTab';
import { McpSettingsTab } from './McpSettingsTab';

const { Title } = Typography;

interface IntegrationSettingsProps {
  repositoryId: string;
}

export function IntegrationSettings({ repositoryId }: IntegrationSettingsProps) {
  const { t } = useTranslation();
  const { authToken } = useAuth();
  const isAdmin = authToken?.isAdmin === true;

  // Tabs that any folder owner with cmis:all (delegated) may use. Everything
  // else is admin-only — connector credentials, repository-wide auth config,
  // catalog backends, scheduler, DLQ, etc.
  const delegatedTabKeys = new Set(['import-profiles', 'manual-ingest']);

  const beta = (
    <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>
      {t('common.beta')}
    </Tag>
  );

  const allItems = [
    { key: 'oidc', label: t('integrationSettings.tabs.oidc'), children: <OidcSettingsTab /> },
    { key: 'google', label: t('integrationSettings.tabs.google'), children: <GoogleAuthSettingsTab /> },
    { key: 'microsoft', label: t('integrationSettings.tabs.microsoft'), children: <MicrosoftAuthSettingsTab /> },
    { key: 'saml', label: t('integrationSettings.tabs.saml'), children: <SamlSettingsTab /> },
    { key: 'directory-sync', label: t('integrationSettings.tabs.directorySync'), children: <DirectorySyncSettingsTab /> },
    { key: 'purview', label: <Space size={4}>{t('integrationSettings.tabs.purview')} {beta}</Space>, children: <PurviewSettingsTab /> },
    { key: 'atlas', label: <Space size={4}>{t('integrationSettings.tabs.atlas')} {beta}</Space>, children: <AtlasSettingsTab /> },
    { key: 'dataplex', label: <Space size={4}>{t('integrationSettings.tabs.dataplex')} {beta}</Space>, children: <DataplexSettingsTab /> },
    { key: 'lineage', label: <Space size={4}>{t('integrationSettings.tabs.lineage')} {beta}</Space>, children: <LineageSettingsTab /> },
    { key: 'property-mapping', label: <Space size={4}>{t('integrationSettings.tabs.propertyMapping')} {beta}</Space>, children: <PropertyMappingSection repositoryId={repositoryId} /> },
    { key: 'connectors', label: <Space size={4}>{t('integrationSettings.tabs.connectors')} {beta}</Space>, children: <ConnectorManagementTab /> },
    { key: 'connector-governance', label: <Space size={4}>{t('integrationSettings.tabs.connectorGovernance', { defaultValue: 'Connector Access' })} {beta}</Space>, children: <ConnectorGovernanceTab repositoryId={repositoryId} /> },
    { key: 'import-profiles', label: <Space size={4}>{t('integrationSettings.tabs.importProfiles')} {beta}</Space>, children: <ImportProfileManagementTab repositoryId={repositoryId} /> },
    { key: 'manual-ingest', label: <Space size={4}>{t('integrationSettings.tabs.manualIngest')} {beta}</Space>, children: <ManualIngestTab repositoryId={repositoryId} /> },
    { key: 'ingest-jobs', label: <Space size={4}>{t('integrationSettings.tabs.ingestJobs')} {beta}</Space>, children: <IngestJobsTab /> },
    { key: 'scheduler-status', label: <Space size={4}>{t('integrationSettings.tabs.schedulerStatus')} {beta}</Space>, children: <SchedulerStatusTab /> },
    { key: 'mcp', label: t('integrationSettings.tabs.mcp', 'MCP'), children: <McpSettingsTab /> },
  ];

  const visibleItems = isAdmin ? allItems : allItems.filter(item => delegatedTabKeys.has(item.key));
  const defaultActive = visibleItems[0]?.key;

  return (
    <div style={{ padding: '24px' }}>
      <Title level={3}>{t('integrationSettings.title')}</Title>
      {!isAdmin && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('integrationSettings.delegatedNotice', { defaultValue: 'Delegated view: showing tabs available to folder owners with cmis:all.' })}
        />
      )}
      {visibleItems.length === 0 ? (
        <Alert
          type="warning"
          message={t('integrationSettings.noAccess', { defaultValue: 'No integration settings tabs are available for your account.' })}
        />
      ) : (
        <Tabs defaultActiveKey={defaultActive} items={visibleItems} />
      )}
    </div>
  );
}
