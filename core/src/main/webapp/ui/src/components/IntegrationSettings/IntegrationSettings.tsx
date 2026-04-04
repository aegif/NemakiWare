import { Space, Tabs, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
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
import { ImportProfileManagementTab } from './ImportProfileManagementTab';
import { ManualIngestTab } from './ManualIngestTab';
import { IngestJobsTab } from './IngestJobsTab';
import { SchedulerStatusTab } from './SchedulerStatusTab';

const { Title } = Typography;

interface IntegrationSettingsProps {
  repositoryId: string;
}

export function IntegrationSettings({ repositoryId }: IntegrationSettingsProps) {
  const { t } = useTranslation();

  const items = [
    {
      key: 'oidc',
      label: t('integrationSettings.tabs.oidc'),
      children: <OidcSettingsTab />,
    },
    {
      key: 'google',
      label: t('integrationSettings.tabs.google'),
      children: <GoogleAuthSettingsTab />,
    },
    {
      key: 'microsoft',
      label: t('integrationSettings.tabs.microsoft'),
      children: <MicrosoftAuthSettingsTab />,
    },
    {
      key: 'saml',
      label: t('integrationSettings.tabs.saml'),
      children: <SamlSettingsTab />,
    },
    {
      key: 'directory-sync',
      label: t('integrationSettings.tabs.directorySync'),
      children: <DirectorySyncSettingsTab />,
    },
    {
      key: 'purview',
      label: <Space size={4}>{t('integrationSettings.tabs.purview')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <PurviewSettingsTab />,
    },
    {
      key: 'atlas',
      label: <Space size={4}>{t('integrationSettings.tabs.atlas')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <AtlasSettingsTab />,
    },
    {
      key: 'dataplex',
      label: <Space size={4}>{t('integrationSettings.tabs.dataplex')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <DataplexSettingsTab />,
    },
    {
      key: 'lineage',
      label: <Space size={4}>{t('integrationSettings.tabs.lineage')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <LineageSettingsTab />,
    },
    {
      key: 'property-mapping',
      label: <Space size={4}>{t('integrationSettings.tabs.propertyMapping')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <PropertyMappingSection repositoryId={repositoryId} />,
    },
    {
      key: 'connectors',
      label: <Space size={4}>{t('integrationSettings.tabs.connectors')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <ConnectorManagementTab />,
    },
    {
      key: 'import-profiles',
      label: <Space size={4}>{t('integrationSettings.tabs.importProfiles')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <ImportProfileManagementTab repositoryId={repositoryId} />,
    },
    {
      key: 'manual-ingest',
      label: <Space size={4}>{t('integrationSettings.tabs.manualIngest')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <ManualIngestTab repositoryId={repositoryId} />,
    },
    {
      key: 'ingest-jobs',
      label: <Space size={4}>{t('integrationSettings.tabs.ingestJobs')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <IngestJobsTab />,
    },
    {
      key: 'scheduler-status',
      label: <Space size={4}>{t('integrationSettings.tabs.schedulerStatus')} <Tag color="blue" style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>{t('common.beta')}</Tag></Space>,
      children: <SchedulerStatusTab />,
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Title level={3}>{t('integrationSettings.title')}</Title>
      <Tabs defaultActiveKey="oidc" items={items} />
    </div>
  );
}
